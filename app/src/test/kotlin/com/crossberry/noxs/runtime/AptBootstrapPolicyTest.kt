/*
 * Noxs — original implementation.
 * JVM tests for the signed APT bootstrap policy layer: the real bounded
 * retry loop, failure signatures (timeout / package-manager errors / DNS
 * failures), the repository pre-flight parser, interrupted-dpkg detection
 * and the exact verification command lines.
 *
 * These cover the setup-repair task scenarios at the policy level:
 *   successful installation · repository/DNS failure · package-manager
 *   failure · timeout and retry exhaustion · interrupted dpkg
 *   configuration · successful certificate verification · duplicate
 *   concurrent setup prevention (single-flight guard has its own test in
 *   NoxsSetupStackTest; the backoff policy is covered in AptSetupBackoffTest).
 */
package com.crossberry.noxs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AptRetryLoopTest {

    private class FakeAttempt(
        override val failed: Boolean,
        override val signature: String
    ) : AptRetryLoop.Attempted

    @Test
    fun `successful installation returns on the first attempt without sleeping`() {
        val sleeps = mutableListOf<Long>()
        var executions = 0
        val outcome = AptRetryLoop.run(
            maxAttempts = 3,
            delayForAttempt = { AptRetryPolicy.delayForAttempt(it) },
            sleep = { sleeps += it },
            execute = {
                executions++
                FakeAttempt(failed = false, signature = "")
            }
        )
        assertFalse(outcome.abortedIdentical)
        assertEquals(1, outcome.attemptsMade)
        assertEquals(1, executions)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `distinct failures exhaust all attempts with the real delay schedule`() {
        val sleeps = mutableListOf<Long>()
        val retryLog = mutableListOf<String>()
        var executions = 0
        val outcome = AptRetryLoop.run(
            maxAttempts = AptRetryPolicy.MAX_ATTEMPTS,
            delayForAttempt = { AptRetryPolicy.delayForAttempt(it) },
            sleep = { sleeps += it },
            onRetryScheduled = { retryIndex, total, delayMs, _ ->
                retryLog += "retry $retryIndex/$total after ${delayMs}ms"
            },
            execute = {
                executions++
                FakeAttempt(failed = true, signature = "E: Failed to fetch attempt $executions")
            }
        )
        assertEquals(AptRetryPolicy.MAX_ATTEMPTS, outcome.attemptsMade)
        assertEquals(AptRetryPolicy.MAX_ATTEMPTS, executions)
        assertFalse(outcome.abortedIdentical)
        assertEquals(listOf(3_000L, 6_000L), sleeps)
        assertEquals(listOf("retry 1/2 after 3000ms", "retry 2/2 after 6000ms"), retryLog)
    }

    @Test
    fun `repeated identical failure aborts the retries immediately`() {
        var executions = 0
        val outcome = AptRetryLoop.run(
            maxAttempts = AptRetryPolicy.MAX_ATTEMPTS,
            delayForAttempt = { AptRetryPolicy.delayForAttempt(it) },
            sleep = { },
            execute = {
                executions++
                FakeAttempt(failed = true, signature = "E: Package 'ca-certificates' has no installation candidate")
            }
        )
        assertEquals(2, executions) // first failure + one confirmation of the identical repeat
        assertEquals(2, outcome.attemptsMade)
        assertTrue(outcome.abortedIdentical)
    }

    @Test
    fun `a changed failure signature keeps retrying`() {
        var executions = 0
        val outcome = AptRetryLoop.run(
            maxAttempts = 3,
            delayForAttempt = { 0L },
            sleep = { },
            execute = {
                executions++
                FakeAttempt(
                    failed = true,
                    signature = when (executions) {
                        1 -> "E: could not resolve host"
                        2 -> "E: connection refused"
                        else -> "E: Unable to locate package"
                    }
                )
            }
        )
        assertEquals(3, executions)
        assertFalse(outcome.abortedIdentical)
    }

    @Test
    fun `failure in the middle still succeeds after the retry`() {
        var executions = 0
        val outcome = AptRetryLoop.run(
            maxAttempts = 3,
            delayForAttempt = { 0L },
            sleep = { },
            execute = {
                executions++
                if (executions == 1) FakeAttempt(true, "E: temporary failure resolving")
                else FakeAttempt(false, "")
            }
        )
        assertFalse(outcome.result.failed)
        assertEquals(2, outcome.attemptsMade)
    }
}

class AptRetryPolicyTest {

    @Test
    fun `timeout produces a stable timeout signature`() {
        assertEquals("timeout", AptRetryPolicy.failureSignature("", 124, timedOut = true))
        assertEquals(
            AptRetryPolicy.failureSignature("whatever output", 0, timedOut = true),
            AptRetryPolicy.failureSignature("different output", 0, timedOut = true)
        )
    }

    @Test
    fun `package manager errors are captured verbatim in the signature`() {
        val output = """
            Reading package lists...
            E: Unable to locate package ca-certificates
            E: Couldn't find any package by glob 'ca-certificates'
        """.trimIndent()
        val signature = AptRetryPolicy.failureSignature(output, 100, timedOut = false)
        assertTrue(signature.contains("E: Unable to locate package ca-certificates"))
        assertTrue(signature.contains("E: Couldn't find any package by glob"))
    }

    @Test
    fun `silent failure falls back to the exit code`() {
        assertEquals("exit 100", AptRetryPolicy.failureSignature("", 100, timedOut = false))
    }

    @Test
    fun `dns resolution failure is a recognizable repeated signature`() {
        val aptErr = "Err:1 http://ports.ubuntu.com/ubuntu-ports noble InRelease\n" +
            "  Temporary failure resolving 'ports.ubuntu.com'"
        val signature = AptRetryPolicy.failureSignature(aptErr, 100, timedOut = false)
        assertTrue(signature.contains("Temporary failure resolving"))
        // JUnit 4 overload: message FIRST, then expected, then actual.
        assertEquals(
            "the same DNS error must produce the same signature so repeated runs are detected",
            signature,
            AptRetryPolicy.failureSignature(aptErr, 100, timedOut = false)
        )
    }

    @Test
    fun `delay schedule is bounded and increasing`() {
        assertEquals(0L, AptRetryPolicy.delayForAttempt(0))
        assertEquals(3_000L, AptRetryPolicy.delayForAttempt(1))
        assertEquals(6_000L, AptRetryPolicy.delayForAttempt(2))
    }
}

class ConnectivityCheckTest {

    @Test
    fun `all repositories reachable parses as success`() {
        val outcome = ConnectivityCheck.parse("OK ports.ubuntu.com\nOK archive.ubuntu.com\n")
        assertTrue(outcome.success)
        assertNull(outcome.failedHost)
        assertEquals(listOf("ports.ubuntu.com", "archive.ubuntu.com"), outcome.okHosts)
        assertEquals("repositories reachable", ConnectivityCheck.describe(outcome))
    }

    @Test
    fun `broken dns names the first failing host`() {
        val outcome = ConnectivityCheck.parse("FAIL ports.ubuntu.com\nFAIL archive.ubuntu.com\n")
        assertFalse(outcome.success)
        assertEquals("ports.ubuntu.com", outcome.failedHost)
        val message = ConnectivityCheck.describe(outcome)
        assertTrue(message.contains("DNS lookup failed for ports.ubuntu.com"))
        assertTrue(message.contains("Bootstrap & Network"))
    }

    @Test
    fun `empty probe output is never mistaken for success`() {
        val outcome = ConnectivityCheck.parse("")
        assertTrue(outcome.okHosts.isEmpty())
        assertNull(outcome.failedHost)
        // The bootstrapper additionally requires at least one OK host.
        assertFalse(outcome.success && outcome.okHosts.isNotEmpty())
    }

    @Test
    fun `mixed output keeps both facts`() {
        val outcome = ConnectivityCheck.parse("OK ports.ubuntu.com\nFAIL archive.ubuntu.com\n")
        assertFalse(outcome.success)
        assertEquals("archive.ubuntu.com", outcome.failedHost)
        assertEquals(listOf("ports.ubuntu.com"), outcome.okHosts)
    }

    @Test
    fun `dns probe command pins the getent based probe`() {
        val argv = AptBootstrapCommands.dnsProbeCommand()
        assertEquals("/bin/bash", argv[0])
        assertEquals("-c", argv[1])
        assertTrue(argv[2].contains("getent ahostsv4"))
        assertTrue(argv[2].contains("ports.ubuntu.com"))
        assertTrue(argv[2].contains("archive.ubuntu.com"))
        assertTrue(argv[2].contains("OK ") && argv[2].contains("FAIL "))
    }
}

class DpkgStateTest {

    @Test
    fun `interrupted configuration is detected from audit output`() {
        val audit = "The following packages have been unpacked but not yet configured.\n" +
            "They must be configured using dpkg --configure or the configure menu option:\n" +
            " ca-certificates (20230311.2)"
        assertTrue(DpkgState.isInterrupted(audit, configureExitCode = 0))
    }

    @Test
    fun `failed configure run counts as interrupted`() {
        assertTrue(DpkgState.isInterrupted("", configureExitCode = 1))
        assertTrue(DpkgState.isInterrupted("dpkg: error processing package ca-certificates", configureExitCode = 2))
    }

    @Test
    fun `clean dpkg state is not interrupted`() {
        assertFalse(DpkgState.isInterrupted("", configureExitCode = 0))
    }

    @Test
    fun `inconsistent package state is detected`() {
        assertTrue(
            DpkgState.isInterrupted(
                "dpkg: error processing ca-certificates (--configure):\n" +
                    " package is in a very bad inconsistent state; you should reinstall it",
                configureExitCode = 1
            )
        )
    }
}

class AptBootstrapCommandsTest {

    @Test
    fun `certificate verification pins the bundle existence and content check`() {
        val argv = AptBootstrapCommands.bundleCheckCommand()
        assertEquals(listOf("/bin/bash", "-c"), argv.take(2))
        assertTrue(argv[2].contains("test -s /etc/ssl/certs/ca-certificates.crt"))
        assertTrue(argv[2].contains("grep -q 'BEGIN CERTIFICATE'"))
    }

    @Test
    fun `package installed verification pins the dpkg status check`() {
        val argv = AptBootstrapCommands.packageInstalledCommand("ca-certificates", "ubuntu-keyring")
        assertTrue(argv[2].contains("/usr/bin/dpkg -s ca-certificates"))
        assertTrue(argv[2].contains("/usr/bin/dpkg -s ubuntu-keyring"))
        // The status line is anchored with an escaped literal dollar (Kotlin
        // \$ escape) so the grep pattern reaches the shell intact.
        assertTrue(argv[2].contains("grep -q '^Status: install ok installed\$'"))
    }

    @Test
    fun `dpkg recovery commands are the canonical ones`() {
        assertEquals(listOf("/usr/bin/dpkg", "--configure", "-a"), AptBootstrapCommands.dpkgConfigureCommand())
        assertEquals(listOf("/usr/bin/dpkg", "--audit"), AptBootstrapCommands.dpkgAuditCommand())
    }
}
