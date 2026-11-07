/*
 * Noxs — original implementation.
 * Honest Docker capability probe.
 *
 * Noxs never fakes a Docker daemon, never fabricates /var/run/docker.sock,
 * and never claims container support that the environment cannot provide.
 * The probe reports the REAL state:
 *   - Docker CLI: installed inside Ubuntu (via apt) or not.
 *   - Docker Engine: reachable ONLY when `docker info` actually succeeds.
 * A failed `docker info` keeps its real error text as the reason.
 */
package com.crossberry.noxs.runtime

object NoxsDockerProbe {

    data class Report(
        val cliAvailable: Boolean,
        val cliVersion: String,
        val engineAvailable: Boolean,
        val engineReason: String
    )

    /**
     * Pure classification over real command results (JVM-testable).
     * [versionExit]/[versionOut] come from `docker --version`;
     * [infoExit]/[infoErr] come from `docker info`.
     */
    fun classify(
        versionExit: Int,
        versionOut: String,
        infoExit: Int,
        infoErr: String,
        infoOut: String
    ): Report {
        val cliOk = versionExit == 0 && versionOut.contains("Docker version", ignoreCase = true)
        if (!cliOk) {
            return Report(
                cliAvailable = false,
                cliVersion = "",
                engineAvailable = false,
                engineReason = "Docker CLI is not installed. Install it with: sudo apt install docker.io"
            )
        }
        return if (infoExit == 0) {
            Report(
                cliAvailable = true,
                cliVersion = versionOut.trim(),
                engineAvailable = true,
                engineReason = "Docker Engine responded to docker info."
            )
        } else {
            Report(
                cliAvailable = true,
                cliVersion = versionOut.trim(),
                engineAvailable = false,
                engineReason = realDaemonReason(infoErr, infoOut)
            )
        }
    }

    /** Extracts the REAL daemon failure reason (never a fabricated one). */
    fun realDaemonReason(infoErr: String, infoOut: String): String {
        val combined = (infoErr + "\n" + infoOut).lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains("Cannot connect to the Docker daemon", ignoreCase = true) }
            ?: (infoErr + "\n" + infoOut).lineSequence().map { it.trim() }
                .firstOrNull { it.startsWith("ERROR") || it.startsWith("error") }
            ?: "docker info failed (exit status non-zero)."
        return combined.take(220)
    }

    /** Stable display lines for the Diagnostics screen. */
    fun displayLines(report: Report): List<String> = buildList {
        add("Docker CLI: " + if (report.cliAvailable) "AVAILABLE" else "UNAVAILABLE")
        if (report.cliAvailable) add("  " + report.cliVersion)
        add("Docker Engine: " + if (report.engineAvailable) "AVAILABLE" else "UNAVAILABLE")
        if (report.engineReason.isNotBlank()) add("  Reason: " + report.engineReason)
    }

    /**
     * Real probe inside the Ubuntu userspace. All failures surface honestly.
     * `docker info` runs as the sandbox root (the way `sudo docker info`
     * would) so a running engine is detected without socket-permission noise.
     */
    suspend fun probe(exec: OneShotExecutor): Report {
        val version = exec.run(listOf("/usr/bin/docker", "--version"), asRoot = false, timeoutSec = 15)
        val info = exec.run(listOf("/usr/bin/docker", "info"), asRoot = true, timeoutSec = 30)
        return classify(
            versionExit = version.exitCode,
            versionOut = version.stdout,
            infoExit = info.exitCode,
            infoErr = info.stderr,
            infoOut = info.stdout
        )
    }
}
