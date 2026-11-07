/*
 * Noxs — original implementation.
 * Pure, JVM-testable policy helpers for the signed APT bootstrap:
 *  - AptRetryPolicy: bounded retries with delays and repeated-identical-
 *    failure detection (an identical failure twice in a row means further
 *    retries cannot help — the real error is surfaced instead of retried).
 *  - ConnectivityCheck: parses the in-sandbox DNS probe output.
 *  - DpkgState: interrupted-configuration detection from dpkg output.
 *  - AptBootstrapCommands: canonical command lines for verification steps.
 *
 * No Android and no process I/O here — the bootstrapper supplies the real
 * command results.
 */
package com.crossberry.noxs.runtime

object AptRetryPolicy {

    /** Total attempts per network step (1 first try + bounded retries). */
    const val MAX_ATTEMPTS: Int = 3

    /** Delay before retry attempt N (N is 1-based: 3 s, then 6 s). */
    const val RETRY_DELAY_BASE_MS: Long = 3_000L

    fun delayForAttempt(retryIndex: Int): Long = RETRY_DELAY_BASE_MS * retryIndex

    /**
     * A stable signature of a failure: normalized error lines of the captured
     * output (or "timeout"/the exit code when nothing was captured). Two
     * attempts with the same signature mean the same failure happened again.
     * The filter keeps apt/dpkg error prefixes AND the indented detail lines
     * that carry the real reason ("Temporary failure resolving 'host'", ...).
     */
    fun failureSignature(output: String, exitCode: Int, timedOut: Boolean): String {
        if (timedOut) return "timeout"
        val errorLines = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { ERROR_HINT.containsMatchIn(it) }
            .take(4)
            .toList()
        if (errorLines.isNotEmpty()) return errorLines.joinToString("\n")
        val anyLines = output.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.take(2).toList()
        return if (anyLines.isNotEmpty()) anyLines.joinToString("\n") else "exit $exitCode"
    }

    /** High-signal failure lines: apt/dpkg prefixes and their indented reasons. */
    private val ERROR_HINT = Regex(
        "(?i)(^E:|^Err:|^W:|temporary failure|could not resolve|unable to locate|" +
            "has no installation candidate|connection refused|failed to fetch|" +
            "certificate verification|is not signed|dpkg: error)"
    )
}

/**
 * The real bounded-retry loop used by the bootstrapper, expressed as a pure
 * executor so JVM tests exercise the actual semantics (not a mirror):
 *  - a successful attempt returns immediately;
 *  - a failing attempt retries up to [maxAttempts] total, sleeping between
 *    attempts (delays injected and recorded in tests);
 *  - two consecutive attempts with the SAME signature abort the loop —
 *    repeating an identical command against an identical error cannot
 *    succeed, so the real failure is surfaced instead of retried forever.
 */
object AptRetryLoop {

    interface Attempted {
        val failed: Boolean
        val signature: String
    }

    data class Outcome<T>(
        val result: T,
        val attemptsMade: Int,
        /** True when the loop stopped early because a failure repeated identically. */
        val abortedIdentical: Boolean
    )

    fun <T : Attempted> run(
        maxAttempts: Int,
        delayForAttempt: (Int) -> Long,
        sleep: (Long) -> Unit,
        onRetryScheduled: ((retryIndex: Int, retriesTotal: Int, delayMs: Long, previousSignature: String) -> Unit)? = null,
        execute: () -> T
    ): Outcome<T> {
        require(maxAttempts >= 1) { "at least one attempt is required" }
        var previous: T? = null
        var previousSignature: String? = null
        for (attempt in 0 until maxAttempts) {
            if (attempt > 0) {
                val delay = delayForAttempt(attempt)
                onRetryScheduled?.invoke(attempt, maxAttempts - 1, delay, previousSignature.orEmpty())
                sleep(delay)
            }
            val result = execute()
            if (!result.failed) return Outcome(result, attempt + 1, false)
            val signature = result.signature
            if (previousSignature != null && signature == previousSignature) {
                return Outcome(result, attempt + 1, true)
            }
            previous = result
            previousSignature = signature
        }
        return Outcome(previous!!, maxAttempts, false)
    }
}

/** Parses the DNS/repository pre-flight probe run inside the sandbox. */
object ConnectivityCheck {

    data class Outcome(val failedHost: String?, val okHosts: List<String>) {
        val success: Boolean get() = failedHost == null
    }

    /**
     * Parses lines emitted by the probe script ("OK <host>" / "FAIL <host>").
     * The first failing host wins. A probe that produced no parsable output
     * at all yields no OK hosts — callers must treat that as a failure too.
     */
    fun parse(output: String): Outcome {
        val ok = mutableListOf<String>()
        var failed: String? = null
        output.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("OK ") -> ok += line.removePrefix("OK ").trim()
                line.startsWith("FAIL ") -> if (failed == null) failed = line.removePrefix("FAIL ").trim()
            }
        }
        return Outcome(failed, ok)
    }

    /** Human-readable reason used verbatim in the console and error detail. */
    fun describe(outcome: Outcome): String = when {
        outcome.success -> "repositories reachable"
        else -> "DNS lookup failed for ${outcome.failedHost} — check the device network " +
            "connection (or the DNS servers configured in Settings → Bootstrap & Network)"
    }
}

/** Interrupted dpkg configuration detection (safe, output-based). */
object DpkgState {

    /**
     * True when dpkg reports pending work — a non-blank `dpkg --audit`
     * listing (dpkg only prints when packages are unpacked/half-configured/
     * awaiting triggers) or a failed `dpkg --configure -a`.
     */
    fun isInterrupted(auditOutput: String, configureExitCode: Int): Boolean =
        auditOutput.isNotBlank() || configureExitCode != 0
}

/** Canonical verification commands (kept pure so tests pin the exact argv). */
object AptBootstrapCommands {

    /** Official Ubuntu repository hosts only (Ubuntu-first). arm64/armhf
     *  guests use ports.ubuntu.com; amd64 builds use archive.ubuntu.com —
     *  both are probed since DNS-only checks cost nothing and the app also
     *  runs on x86_64 images. */
    val DNS_PROBE_HOSTS: List<String> = listOf(
        "ports.ubuntu.com", "archive.ubuntu.com"
    )

    /** Prints "OK <host>" / "FAIL <host>" per repository host; always exits 0 — the parsed lines decide, not the exit code. */
    fun dnsProbeCommand(hosts: List<String> = DNS_PROBE_HOSTS): List<String> = listOf(
        "/bin/bash", "-c",
        hosts.joinToString(" ") { host ->
            "if getent ahostsv4 \"$host\" >/dev/null 2>&1; then echo \"OK $host\"; " +
                "else echo \"FAIL $host\"; fi"
        } + "; exit 0"
    )

    fun dpkgConfigureCommand(): List<String> = listOf("/usr/bin/dpkg", "--configure", "-a")

    fun dpkgAuditCommand(): List<String> = listOf("/usr/bin/dpkg", "--audit")

    fun packageInstalledCommand(vararg packages: String): List<String> = listOf(
        "/bin/bash", "-c",
        packages.joinToString(" && ") { "/usr/bin/dpkg -s $it | /bin/grep -q '^Status: install ok installed\$'" }
    )

    fun bundleCheckCommand(): List<String> = listOf(
        "/bin/bash", "-c",
        "test -s /etc/ssl/certs/ca-certificates.crt && grep -q 'BEGIN CERTIFICATE' /etc/ssl/certs/ca-certificates.crt"
    )
}
