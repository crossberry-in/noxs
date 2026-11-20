/*
 * Noxs — original implementation.
 * Parser for `nx docker doctor --json` output (docker-lib.sh, guest side).
 *
 * The doctor emits ONE flat JSON object with safe runtime facts only — never
 * credentials, never log bodies. Parsing here is pure and JVM-testable: the
 * Diagnostics screen feeds it real captured stdout, and unknown/absent keys
 * degrade to "unknown" status instead of fake success.
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.shared.MiniJson

object NoxsDockerDoctorJson {

    /** Status buckets shared with the diagnostics UI cards. */
    enum class Status { OK, WARN, FAIL, UNKNOWN }

    data class Report(
        val rootfs: Status,
        val cli: Status,
        val daemon: Status,
        val mode: String,
        val storage: String,
        val dns: Status,
        val registry: Status,
        val buildkit: Status,
        val kernel: Status,
        val arch: String
    ) {
        /** True when the report actually came from a doctor run (not a probe failure). */
        val valid: Boolean get() = rootfs != Status.UNKNOWN || cli != Status.UNKNOWN || daemon != Status.UNKNOWN
    }

    /** Parses REAL doctor output; returns a report with UNKNOWN fields when unparseable. */
    fun parse(output: String): Report {
        val trimmed = output.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return allUnknown()
        val parsed = runCatching { MiniJson.parse(trimmed.substring(start, end + 1)) as? Map<*, *> }
            .getOrNull() ?: return allUnknown()

        fun status(key: String, okValues: Set<String>, warnValues: Set<String> = emptySet()): Status {
            val value = (parsed[key] as? String)?.lowercase() ?: return Status.UNKNOWN
            return when {
                value in okValues -> Status.OK
                value in warnValues -> Status.WARN
                else -> Status.FAIL
            }
        }

        return Report(
            rootfs = status("rootfs", okValues = setOf("ok"), warnValues = setOf("degraded")),
            cli = status("cli", okValues = setOf("installed"), warnValues = setOf("missing")),
            daemon = status("daemon", okValues = setOf("running"), warnValues = setOf("stopped", "not_installed")),
            mode = (parsed["mode"] as? String) ?: "",
            storage = (parsed["storage"] as? String) ?: "",
            dns = status("dns", okValues = setOf("ok"), warnValues = setOf("limited")),
            registry = status("registry", okValues = setOf("reachable"), warnValues = setOf("unreachable")),
            buildkit = status("buildkit", okValues = setOf("available"), warnValues = setOf("missing")),
            kernel = status("kernel", okValues = setOf("ok"), warnValues = setOf("limited")),
            arch = (parsed["arch"] as? String) ?: "unknown"
        )
    }

    private fun allUnknown() = Report(
        rootfs = Status.UNKNOWN, cli = Status.UNKNOWN, daemon = Status.UNKNOWN,
        mode = "", storage = "", dns = Status.UNKNOWN, registry = Status.UNKNOWN,
        buildkit = Status.UNKNOWN, kernel = Status.UNKNOWN, arch = "unknown"
    )
}
