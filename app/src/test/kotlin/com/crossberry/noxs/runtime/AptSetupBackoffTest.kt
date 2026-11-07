/*
 * Noxs — original implementation.
 * JVM tests for the background APT bootstrap runner's anti-loop policy:
 * after the SAME failure repeats, automatic retries are rate-limited so the
 * repair loop stays eventually-successful (network may recover) without
 * spinning on every service start.
 */
package com.crossberry.noxs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AptSetupBackoffTest {

    @Test
    fun `fresh and few failures always attempt immediately`() {
        assertTrue(AptSetupBackoff.shouldAttempt(identicalFailures = 0, msSinceLastAttempt = 0L))
        assertTrue(AptSetupBackoff.shouldAttempt(identicalFailures = 1, msSinceLastAttempt = 0L))
        assertTrue(AptSetupBackoff.shouldAttempt(
            identicalFailures = AptSetupBackoff.MAX_IDENTICAL_BEFORE_BACKOFF - 1,
            msSinceLastAttempt = 0L
        ))
    }

    @Test
    fun `too many identical failures are rate limited`() {
        assertFalse(
            AptSetupBackoff.shouldAttempt(
                identicalFailures = AptSetupBackoff.MAX_IDENTICAL_BEFORE_BACKOFF,
                msSinceLastAttempt = 0L
            )
        )
        assertFalse(
            AptSetupBackoff.shouldAttempt(
                identicalFailures = AptSetupBackoff.MAX_IDENTICAL_BEFORE_BACKOFF + 5,
                msSinceLastAttempt = AptSetupBackoff.MIN_INTERVAL_MS - 1
            )
        )
    }

    @Test
    fun `after the backoff interval the attempt is allowed again`() {
        assertTrue(
            AptSetupBackoff.shouldAttempt(
                identicalFailures = AptSetupBackoff.MAX_IDENTICAL_BEFORE_BACKOFF + 2,
                msSinceLastAttempt = AptSetupBackoff.MIN_INTERVAL_MS
            )
        )
    }

    @Test
    fun `remaining time never goes negative`() {
        assertEquals(0L, AptSetupBackoff.remainingMs(AptSetupBackoff.MIN_INTERVAL_MS + 1))
        assertEquals(
            AptSetupBackoff.MIN_INTERVAL_MS,
            AptSetupBackoff.remainingMs(0L)
        )
    }

    @Test
    fun `failure signature is stable and bounded`() {
        val detail = "signed HTTP apt update failed (exit 100, 00:12):\n" +
            "E: Failed to fetch http://ports.ubuntu.com/ubuntu-ports/dists/noble/InRelease\n" +
            "W: Some index files failed to download"
        val signature = AptSetupBackoff.signature(detail)
        assertTrue(signature.contains("signed HTTP apt update failed"))
        assertTrue(signature.length <= 300)
        assertEquals(signature, AptSetupBackoff.signature(detail))
    }

    @Test
    fun `a changed error produces a different signature`() {
        val dns = "Checking repositories failed: DNS lookup failed for ports.ubuntu.com"
        val tls = "HTTPS apt update failed (exit 100):\nE: certificate verification failed"
        assertTrue(AptSetupBackoff.signature(dns) != AptSetupBackoff.signature(tls))
    }

    @Test
    fun `blank details still yield a usable signature`() {
        assertEquals("", AptSetupBackoff.signature("   \n  "))
    }
}
