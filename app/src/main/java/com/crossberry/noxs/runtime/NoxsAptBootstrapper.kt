/*
 * Noxs — signed Ubuntu APT bootstrap.
 * Installs/repairs CA certificates over signed Ubuntu HTTP metadata first,
 * then switches the sole source file to HTTPS and verifies a real TLS update.
 *
 * Reliability rules implemented here (see the setup-repair task spec):
 *  - DNS and repository reachability are probed BEFORE any apt run, so a
 *    broken network surfaces as one clear message instead of minutes of
 *    generic apt resolver errors.
 *  - Network steps run with bounded retries (3 attempts, 3 s/6 s backoff).
 *    Two consecutive IDENTICAL failures abort the retries — repeating the
 *    same command against the same error cannot succeed.
 *  - Every command captures stdout+stderr, the real exit code and its wall
 *    duration; failures are never suppressed and always reach the console.
 *  - A running spinner is never treated as progress: the only success proof
 *    is a verified command result (exit code + package/bundle state).
 *  - Already-verified packages are not reinstalled again on repair runs.
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.shared.NoxsLog
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class NoxsAptBootstrapper(
    private val paths: NoxsPaths,
    private val launcher: ProotLauncher,
    private val isCancelled: () -> Boolean = { false }
) {
    data class Result(val success: Boolean, val detail: String = "")

    /**
     * Runs the bootstrap once per rootfs. [force] is used by fresh installs;
     * existing installs are repaired automatically when the marker is absent.
     */
    fun initialize(force: Boolean = false, onLog: (String) -> Unit = {}): Result =
        synchronized(PROCESS_LOCK) {
            if (isCancelled()) throw SetupCancelledException()
            if (!paths.rootfs.isDirectory) return@synchronized Result(false, "Ubuntu rootfs is missing")
            if (!force && paths.aptReadyMarker.isFile) {
                return@synchronized Result(true, "APT, dpkg and HTTPS were previously verified")
            }
            // Single-flight package policy: never start a second apt/dpkg
            // transaction (Noxs-managed or in-shell flag) concurrently.
            if (NoxsPkgTransaction.currentOwner() != null ||
                File(paths.rootfsNoxsRun, NoxsPkgTransaction.FLAG_NAME).isFile
            ) {
                return@synchronized Result(false, NoxsPkgTransaction.BUSY_MESSAGE)
            }
            NoxsPkgTransaction.armFlag(paths, "apt-bootstrap")

            try {
                paths.tmp.mkdirs()
                paths.logs.mkdirs()
                RootfsConfigurator.repairDpkgPermissions(paths)
                // Keep the temporary signed HTTP definition in place before
                // running dpkg recovery, not merely before the first apt-get.
                RootfsConfigurator.configureAptSources(paths, useHttps = false)

                // Exercise the same link operation dpkg uses for its status
                // backup in the dpkg database directory before touching packages.
                val linkProbeScript = """
                    set -eu
                    dir=${'$'}(mktemp -d /var/lib/dpkg/.noxs-link-probe.XXXXXX)
                    trap 'rm -rf "${'$'}dir"' EXIT
                    printf 'ok' > "${'$'}dir/source"
                    ln "${'$'}dir/source" "${'$'}dir/copy"
                    test "${'$'}(cat "${'$'}dir/copy")" = ok
                """.trimIndent()
                val linkProbe = run(listOf("/bin/bash", "-c", linkProbeScript), 30)
                if (!requireSuccessful("dpkg filesystem compatibility check", linkProbe, onLog)) {
                    return@synchronized Result(false, summarize("dpkg filesystem compatibility check", linkProbe))
                }
                NoxsLog.i(TAG, "PRoot dpkg filesystem compatibility check passed")

                // 1 — repositories: DNS + reachability pre-flight.
                onLog("Checking repositories")
                val dnsProbe = run(AptBootstrapCommands.dnsProbeCommand(), 30)
                val dnsOutcome = ConnectivityCheck.parse(dnsProbe.output)
                val dnsUsable = dnsOutcome.success && dnsOutcome.okHosts.isNotEmpty()
                if (!dnsUsable || dnsProbe.timedOut) {
                    val reason = if (dnsProbe.timedOut) {
                        "repository DNS probe timed out after ${dnsProbe.durationMs / 1000}s"
                    } else ConnectivityCheck.describe(dnsOutcome)
                    onLog("Checking repositories: $reason")
                    NoxsLog.e(TAG, "repository pre-flight failed: $reason")
                    return@synchronized Result(false, "Checking repositories failed: $reason")
                }
                NoxsLog.i(TAG, "repository pre-flight ok (${dnsOutcome.okHosts.joinToString()}) in ${dnsProbe.durationMs} ms")

                // 2 — interrupted dpkg configuration (detected, repaired only
                // once authenticated package metadata is available below).
                onLog("Checking for interrupted dpkg configuration")
                val auditBefore = run(AptBootstrapCommands.dpkgAuditCommand(), 60)
                if (auditBefore.output.isNotBlank()) onLog("dpkg audit found incomplete package state")
                val initialDpkg = run(AptBootstrapCommands.dpkgConfigureCommand(), 180)
                reportOutput("dpkg preflight", initialDpkg, onLog)
                if (initialDpkg.timedOut) return@synchronized failure("dpkg preflight timed out", initialDpkg, onLog)
                val interruptedConfiguration = DpkgState.isInterrupted(auditBefore.output, initialDpkg.exitCode)
                if (interruptedConfiguration) {
                    onLog("dpkg has pending configuration; will repair after signed package metadata is available")
                }

                // 3 — signed HTTP metadata (bounded retries: network step).
                onLog("Refreshing signed Ubuntu package metadata over HTTP")
                val httpUpdate = runWithRetry("signed HTTP apt update", aptCommand("update"), 240, onLog)
                if (!requireSuccessful("signed HTTP apt update", httpUpdate, onLog)) {
                    return@synchronized Result(false, summarize("signed HTTP apt update", httpUpdate))
                }
                NoxsLog.i(TAG, "HTTP apt update ok in ${httpUpdate.durationMs} ms")

                if (interruptedConfiguration) {
                    onLog("Repairing interrupted package dependencies from authenticated Ubuntu repositories")
                    val dependencyRepair = run(
                        aptCommand("--fix-broken", "install", "--yes", "--no-install-recommends"),
                        300
                    )
                    if (!requireSuccessful("apt dependency repair", dependencyRepair, onLog)) {
                        return@synchronized Result(false, summarize("apt dependency repair", dependencyRepair))
                    }
                    RootfsConfigurator.repairDpkgPermissions(paths)
                    val configured = run(AptBootstrapCommands.dpkgConfigureCommand(), 240)
                    if (!requireSuccessful("dpkg recovery", configured, onLog)) {
                        return@synchronized Result(false, summarize("dpkg recovery", configured))
                    }
                }

                // 4 — CA certificates. On repair runs where both packages are
                // already installed and verified, the (slow, repeated) apt
                // installation is skipped and only the bundle is refreshed.
                val packagesInstalled = run(
                    AptBootstrapCommands.packageInstalledCommand("ca-certificates", "ubuntu-keyring"), 30
                )
                val alreadyVerified = !packagesInstalled.failed && packagesInstalled.output.isBlank()
                if (alreadyVerified) {
                    onLog("ca-certificates and the archive keyring are already installed — refreshing the bundle")
                    NoxsLog.i(TAG, "certificate packages already installed; skipping redundant reinstall")
                } else {
                    onLog("Installing ca-certificates and the Ubuntu archive keyring")
                    val certInstall = runWithRetry(
                        "certificate package installation",
                        aptCommand("install", "--yes", "--no-install-recommends", "--reinstall",
                            "ca-certificates", "ubuntu-keyring"),
                        300, onLog
                    )
                    if (!requireSuccessful("certificate package installation", certInstall, onLog)) {
                        return@synchronized Result(false, summarize("certificate package installation", certInstall))
                    }
                    NoxsLog.i(TAG, "certificate packages installed in ${certInstall.durationMs} ms")
                }

                // 5 — regenerate the system CA bundle and really verify it.
                onLog("Updating certificate bundle")
                val updateCertificates = run(listOf("/usr/sbin/update-ca-certificates", "--fresh"), 240)
                if (!requireSuccessful("update-ca-certificates", updateCertificates, onLog)) {
                    return@synchronized Result(false, summarize("update-ca-certificates", updateCertificates))
                }
                NoxsLog.i(TAG, "update-ca-certificates --fresh ok in ${updateCertificates.durationMs} ms")

                onLog("Verifying certificates")
                val certificateCheck = run(AptBootstrapCommands.bundleCheckCommand(), 30)
                if (certificateCheck.failed) {
                    return@synchronized failure("verified CA bundle is missing or empty", certificateCheck, onLog)
                }
                onLog("Verified the generated CA certificate bundle")

                // HTTPS is enabled only after the CA bundle exists.
                RootfsConfigurator.configureAptSources(paths, useHttps = true)
                onLog("Refreshing signed Ubuntu package metadata over validated HTTPS")
                val httpsUpdate = runWithRetry("HTTPS apt update", aptCommand("update"), 240, onLog)
                if (!requireSuccessful("HTTPS apt update", httpsUpdate, onLog)) {
                    return@synchronized Result(false, summarize("HTTPS apt update", httpsUpdate))
                }
                NoxsLog.i(TAG, "HTTPS apt update ok in ${httpsUpdate.durationMs} ms")

                onLog("Finishing interrupted package configuration")
                RootfsConfigurator.repairDpkgPermissions(paths)
                var finalDpkg = run(AptBootstrapCommands.dpkgConfigureCommand(), 240)
                if (finalDpkg.failed) {
                    onLog("Retrying dpkg configuration after authenticated dependency repair")
                    val dependencyRepair = run(
                        aptCommand("--fix-broken", "install", "--yes", "--no-install-recommends"),
                        300
                    )
                    if (!requireSuccessful("final apt dependency repair", dependencyRepair, onLog)) {
                        return@synchronized Result(false, summarize("final apt dependency repair", dependencyRepair))
                    }
                    RootfsConfigurator.repairDpkgPermissions(paths)
                    finalDpkg = run(AptBootstrapCommands.dpkgConfigureCommand(), 240)
                }
                if (!requireSuccessful("dpkg --configure -a", finalDpkg, onLog)) {
                    return@synchronized Result(false, summarize("dpkg --configure -a", finalDpkg))
                }
                val audit = run(AptBootstrapCommands.dpkgAuditCommand(), 60)
                if (audit.timedOut || audit.exitCode != 0 || audit.output.isNotBlank()) {
                    return@synchronized failure("dpkg audit found incomplete package state", audit, onLog)
                }

                // 6 — final verification: real package state, real metadata.
                val installedCerts = run(
                    AptBootstrapCommands.packageInstalledCommand("ca-certificates"), 30
                )
                if (installedCerts.failed) {
                    return@synchronized failure("ca-certificates is not in the installed state", installedCerts, onLog)
                }
                val metadata = run(listOf("/usr/bin/apt-cache", "policy", "ca-certificates"), 60)
                if (metadata.exitCode != 0 || metadata.timedOut ||
                    !metadata.output.contains("Candidate:") || metadata.output.contains("Candidate: (none)")) {
                    return@synchronized failure("APT package metadata verification failed", metadata, onLog)
                }
                reportOutput("APT metadata", metadata, onLog)
                onLog("Verifying certificates: package and metadata state verified")

                val markerTemp = File(paths.base, "${paths.aptReadyMarker.name}.tmp")
                markerTemp.writeText("verified=${System.currentTimeMillis()}\nsuite=noble\ndistro=ubuntu\ntransport=https\n")
                if (paths.aptReadyMarker.exists() && !paths.aptReadyMarker.delete()) {
                    markerTemp.delete()
                    return@synchronized Result(false, "Cannot replace APT readiness marker")
                }
                if (!markerTemp.renameTo(paths.aptReadyMarker)) {
                    markerTemp.delete()
                    return@synchronized Result(false, "Cannot save APT readiness marker")
                }
                onLog("Completed: APT, dpkg, CA certificates, signed metadata and HTTPS verified")
                NoxsLog.i(TAG, "APT initialized with signed Noble (Ubuntu 24.04) HTTPS sources")
                Result(true)
            } catch (e: Exception) {
                NoxsLog.e(TAG, "APT bootstrap failed", e)
                Result(false, e.message ?: e.javaClass.simpleName)
            } finally {
                NoxsPkgTransaction.clearFlag(paths)
            }
        }

    private fun aptCommand(vararg args: String): List<String> =
        listOf("/usr/bin/apt-get") + APT_OPTIONS + args

    /**
     * Bounded retry wrapper for network-dependent steps. Retries only while
     * failures differ; two identical failures in a row abort immediately so
     * the real error reaches the user instead of an infinite loop.
     */
    private fun runWithRetry(
        step: String,
        command: List<String>,
        timeoutSeconds: Long,
        onLog: (String) -> Unit
    ): CommandResult {
        val outcome = AptRetryLoop.run(
            maxAttempts = AptRetryPolicy.MAX_ATTEMPTS,
            delayForAttempt = { AptRetryPolicy.delayForAttempt(it) },
            sleep = { sleepWithCancel(it) },
            onRetryScheduled = { retryIndex, retriesTotal, delayMs, previousSignature ->
                onLog("$step: retry $retryIndex/$retriesTotal in ${delayMs / 1000}s — " +
                    "previous failure: ${previousSignature.lineSequence().firstOrNull().orEmpty()}")
            },
            execute = { run(command, timeoutSeconds) }
        )
        if (outcome.abortedIdentical) {
            onLog("$step: the same failure repeated — further retries will not help")
            NoxsLog.e(TAG, "$step aborted after identical repeated failure: ${outcome.result.signature}")
        }
        return outcome.result
    }

    /** Cancellable sleep used between retry attempts. */
    private fun sleepWithCancel(ms: Long) {
        var remaining = ms
        while (remaining > 0) {
            if (isCancelled()) throw SetupCancelledException()
            val slice = if (remaining > 200) 200 else remaining
            try {
                Thread.sleep(slice)
            } catch (_: InterruptedException) {
                if (isCancelled()) throw SetupCancelledException()
            }
            remaining -= slice
        }
        if (isCancelled()) throw SetupCancelledException()
    }

    private fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
        if (isCancelled()) throw SetupCancelledException()
        val startedAt = System.nanoTime()
        val process = ProcessBuilder(launcher.oneShotArgv(command, asRoot = true)).apply {
            redirectErrorStream(true)
            launcher.applyEnvTo(this, mapOf(
                "NOXS_ROOT_LOGIN" to "1",
                "DEBIAN_FRONTEND" to "noninteractive",
                "DEBIAN_PRIORITY" to "critical"
            ))
        }.start()
        val output = StringBuilder()
        val outputLock = Any()
        val reader = Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { stream ->
                    while (true) {
                        val line = stream.readLine() ?: break
                        synchronized(outputLock) {
                            output.append(line).append('\n')
                            if (output.length > MAX_CAPTURE_CHARS) {
                                output.delete(0, output.length - MAX_CAPTURE_CHARS)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // The process result below carries the useful failure details.
            }
        }, "noxs-apt-output").apply { isDaemon = true }
        reader.start()

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        reader.join(5_000)
        val captured = synchronized(outputLock) { output.toString() }
        return CommandResult(
            exitCode = if (finished) process.exitValue() else 124,
            output = captured,
            timedOut = !finished,
            durationMs = (System.nanoTime() - startedAt) / 1_000_000L
        )
    }

    private fun requireSuccessful(step: String, result: CommandResult, onLog: (String) -> Unit): Boolean {
        reportOutput(step, result, onLog)
        val insecureOrTlsFailure = INSECURE_OR_TLS_FAILURE.containsMatchIn(result.output)
        val partialUpdateFailure = step.contains("apt update") && PARTIAL_UPDATE_FAILURE.containsMatchIn(result.output)
        val success = !result.timedOut && result.exitCode == 0 && !insecureOrTlsFailure && !partialUpdateFailure
        if (!success) {
            NoxsLog.e(TAG, summarize(step, result))
        } else if (result.durationMs >= 1_000) {
            NoxsLog.i(TAG, "$step ok in ${result.durationMs} ms")
        }
        return success
    }

    private fun reportOutput(step: String, result: CommandResult, onLog: (String) -> Unit) {
        result.output.lineSequence()
            .filter { it.startsWith("Err:") || it.startsWith("W:") || it.startsWith("E:") ||
                it.contains("Candidate:") || it.contains("Setting up ") || it.contains("Unpacking ") }
            .toList()
            .takeLast(10)
            .forEach { onLog("$step: $it") }
    }

    private fun failure(step: String, result: CommandResult, onLog: (String) -> Unit): Result {
        reportOutput(step, result, onLog)
        val detail = summarize(step, result)
        NoxsLog.e(TAG, detail)
        return Result(false, detail)
    }

    private fun summarize(step: String, result: CommandResult): String {
        val detail = result.output.lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(16)
            .joinToString("\n")
        return buildString {
            append(step)
            append(" failed (exit ")
            append(result.exitCode)
            append(if (result.timedOut) ", timed out" else "")
            append(", ${SetupOpTracker.formatElapsed(result.durationMs)}")
            append(")")
            if (detail.isNotBlank()) append(":\n").append(detail)
        }
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean,
        val durationMs: Long = 0L
    ) : AptRetryLoop.Attempted {
        override val failed: Boolean get() = timedOut || exitCode != 0
        override val signature: String
            get() = AptRetryPolicy.failureSignature(output, exitCode, timedOut)
    }

    companion object {
        private const val TAG = "AptBootstrap"
        private val PROCESS_LOCK = Any()
        private const val MAX_CAPTURE_CHARS = 48_000
        private val APT_OPTIONS = listOf(
            "-o", "Acquire::Retries=1",
            "-o", "Acquire::http::Timeout=20",
            "-o", "Acquire::https::Timeout=20"
        )
        private val INSECURE_OR_TLS_FAILURE = Regex(
            "(?i)(certificate verification failed|certificate is not trusted|tls handshake|\\bNO_PUBKEY\\b|repository .* is not signed|unauthenticated packages)"
        )
        private val PARTIAL_UPDATE_FAILURE = Regex(
            "(?im)(^Err:|failed to fetch|some index files failed|could not resolve|connection timed out)"
        )
    }
}
