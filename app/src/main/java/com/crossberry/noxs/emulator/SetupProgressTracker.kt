/*
 * Noxs — original implementation.
 * Live setup-progress tracker for the NoxOS emulator pipeline (M2 UX).
 *
 * Turns raw `apt-get update` / `apt-get install` output into a single
 * monotonic 0..100 percent plus a coarse sub-phase, so the emulator window
 * can show "47% · Downloading packages…" and a live log while the QEMU
 * runtime is provisioned into the Ubuntu rootfs.
 *
 * Stage weight map (percent windows):
 *   PREPARE  → 0..2     (VM storage dirs)
 *   KERNEL   → 2..6     (kernel asset provisioning)
 *   UPDATE   → 8..28    (apt-get update: Get:/Hit: line counting)
 *   INSTALL  → 28..88   (apt-get install: download 28..58, dpkg 58..88)
 *   BOOT     → 88..99   (VNC-readiness wait, time-crawled)
 *   RUNNING  → 100
 *
 * Download percent sources (best available, in order):
 *   1. "Need to get 18.2 MB of archives." + per-package "Get:N … [1,234 kB]"
 *      sizes accumulated against the declared total.
 *   2. Fallback: Get: line count against a nominal 25 packages.
 * dpkg percent sources:
 *   1. "Progress: [ 45 %]" lines (apt emits them when Dpkg::Use-Pty=0).
 *   2. Fallback: "Unpacking"/"Setting up" line counts against
 *      "N upgraded, M newly installed".
 *
 * Percent is monotonic (never decreases) and capped at 99 until the VM
 * actually answers on its VNC port. Pure Kotlin: JVM-unit-testable, no
 * Android imports.
 */
package com.crossberry.noxs.emulator

/** Coarse phase, mapped to a localized label by the UI layer. */
enum class SetupSubPhase {
    IDLE, PREPARING, KERNEL, PACKAGE_LISTS, DOWNLOADING, INSTALLING_PACKAGES, BOOTING, RUNNING
}

data class SetupProgress(
    val percent: Int,
    val subPhase: SetupSubPhase
)

class SetupProgressTracker {

    private enum class Phase { IDLE, PREPARE, KERNEL, UPDATE, INSTALL, BOOT, DONE }

    private var phase: Phase = Phase.IDLE
    private var percent: Int = 0

    // apt-get update counters (Get:/Hit: index fetches).
    private var indexFetches = 0

    // apt-get install download bookkeeping.
    private var needBytes: Long = -1L
    private var fetchedBytes: Long = 0L
    private var fetchCount = 0
    private var downloadDone = false

    // dpkg bookkeeping.
    private var dpkgPercent = -1
    private var packagesTotal = -1
    private var unpacked = 0
    private var configured = 0

    // --------------------------------------------------------- phase entry

    fun beginPrepare() = enter(Phase.PREPARE, 2)

    fun beginKernel() = enter(Phase.KERNEL, 6)

    fun beginUpdate() {
        enter(Phase.UPDATE, 8)
    }

    fun beginInstall() {
        enter(Phase.INSTALL, 28)
    }

    fun beginBoot() = enter(Phase.BOOT, 88)

    fun markRunning() {
        phase = Phase.DONE
        percent = 100
    }

    private fun enter(next: Phase, floorPercent: Int) {
        phase = next
        if (floorPercent > percent) percent = floorPercent
    }

    // ------------------------------------------------------------- parsing

    /** Feeds one raw output line (apt/dpkg stream). Non-matching lines only
     *  affect the fallback counters; unknown phases ignore input. */
    fun onLine(line: String) {
        when (phase) {
            Phase.UPDATE -> onUpdateLine(line)
            Phase.INSTALL -> onInstallLine(line)
            else -> Unit
        }
    }

    private fun onUpdateLine(line: String) {
        if (line.startsWith("Get:") || line.startsWith("Hit:")) {
            indexFetches++
            // noble arm64 refreshes roughly two dozen index files; each
            // fetch advances a fraction of the 8..28 window.
            val sub = (indexFetches * INDEX_WINDOW) / INDEX_NOMINAL
            percent = maxOf(percent, UPDATE_FLOOR + sub.coerceAtMost(INDEX_WINDOW))
        } else if (line.startsWith("Fetched ") || line.startsWith("Reading package lists")) {
            percent = maxOf(percent, UPDATE_FLOOR + INDEX_WINDOW)
        }
    }

    private fun onInstallLine(line: String) {
        val trimmed = line.trim()

        NEED_LINE.find(trimmed)?.let { m ->
            needBytes = parseSize(m.groupValues[1], m.groupValues[2])
            return
        }
        if (trimmed.contains("newest version") || trimmed.contains("0 upgraded, 0 newly installed")) {
            // Nothing to fetch or unpack — fast-forward the install window.
            downloadDone = true
            packagesTotal = 0
            dpkgPercent = 100
        }
        FETCHED_LINE.find(trimmed)?.let { _ ->
            // "Fetched X kB in Ys" closes the download phase; fall through so
            // the percent is recomputed in this same pass (Fetched is often
            // the last line before dpkg starts).
            downloadDone = true
        }
        UPGRADED_LINE.find(trimmed)?.let { m ->
            val upgraded = m.groupValues[1].toIntOrNull() ?: 0
            val newly = m.groupValues[2].toIntOrNull() ?: 0
            packagesTotal = upgraded + newly
            return
        }
        PROGRESS_LINE.find(trimmed)?.let { m ->
            dpkgPercent = (m.groupValues[1].toIntOrNull() ?: 0).coerceIn(0, 100)
        }
        if (trimmed.startsWith("Unpacking ")) unpacked++
        if (trimmed.startsWith("Setting up ")) configured++

        GET_LINE.find(trimmed)?.let { m ->
            fetchCount++
            val size = parseSize(m.groupValues[1], m.groupValues[2])
            fetchedBytes += size
            if (needBytes > 0 && fetchedBytes >= needBytes) downloadDone = true
        }

        // Recompute the install-window percent from the best sources.
        val downloadSub = when {
            downloadDone -> 1.0
            needBytes > 0 -> (fetchedBytes.toDouble() / needBytes).coerceIn(0.0, 1.0)
            fetchCount > 0 -> minOf(1.0, fetchCount.toDouble() / DOWNLOAD_NOMINAL)
            else -> 0.0
        }
        val dpkgSub = when {
            dpkgPercent >= 0 -> dpkgPercent / 100.0
            packagesTotal > 0 ->
                ((unpacked + configured).toDouble() / (packagesTotal * 2.0)).coerceIn(0.0, 1.0)
            else -> 0.0
        }
        val value = INSTALL_FLOOR +
            (downloadSub * DOWNLOAD_WINDOW).toInt() +
            (dpkgSub * DPKG_WINDOW).toInt()
        percent = maxOf(percent, value.coerceAtMost(INSTALL_FLOOR + DOWNLOAD_WINDOW + DPKG_WINDOW))
    }

    // --------------------------------------------------------------- boot

    /** Boot-wait crawl: 88 → 99 across [timeoutMs]; stays at 99 until the
     *  VNC port actually answers ([markRunning]). */
    fun tickBoot(elapsedMs: Long, timeoutMs: Long) {
        if (phase != Phase.BOOT) return
        val ratio = if (timeoutMs <= 0) 1.0 else elapsedMs.toDouble() / timeoutMs
        val value = BOOT_FLOOR + (ratio.coerceIn(0.0, 1.0) * BOOT_WINDOW).toInt()
        percent = maxOf(percent, value)
    }

    // ------------------------------------------------------------ snapshot

    fun snapshot(): SetupProgress {
        val sub = when (phase) {
            Phase.IDLE -> SetupSubPhase.IDLE
            Phase.PREPARE -> SetupSubPhase.PREPARING
            Phase.KERNEL -> SetupSubPhase.KERNEL
            Phase.UPDATE -> SetupSubPhase.PACKAGE_LISTS
            Phase.INSTALL ->
                if (dpkgPercent >= 0 || packagesTotal > 0) SetupSubPhase.INSTALLING_PACKAGES
                else SetupSubPhase.DOWNLOADING
            Phase.BOOT -> SetupSubPhase.BOOTING
            Phase.DONE -> SetupSubPhase.RUNNING
        }
        return SetupProgress(percent, sub)
    }

    companion object {
        // Stage windows (percent points each stage may advance).
        private const val UPDATE_FLOOR = 8
        private const val INDEX_WINDOW = 20
        private const val INDEX_NOMINAL = 24
        private const val INSTALL_FLOOR = 28
        private const val DOWNLOAD_WINDOW = 30
        private const val DPKG_WINDOW = 30
        private const val BOOT_FLOOR = 88
        private const val BOOT_WINDOW = 11

        /** Fallback download model when apt gives no byte totals. */
        private const val DOWNLOAD_NOMINAL = 25

        private val NEED_LINE =
            Regex("Need to get ([\\d.,]+) ?(kB|MB|B) of archives")
        private val GET_LINE =
            Regex("^Get:\\d+ \\S+ .*\\[([\\d.,]+) ?(kB|MB|B)\\]")
        private val PROGRESS_LINE =
            Regex("Progress: \\[\\s*(\\d+) ?%\\]")
        private val UPGRADED_LINE =
            Regex("(\\d+) upgraded, (\\d+) newly installed")
        private val FETCHED_LINE =
            Regex("^Fetched [\\d.,]+ ?[kM]?B in ")

        /** "1,234" / "18.2" × kB|MB|B → bytes (SI decimal units, consistent
         *  between need-total and per-package sizes, so ratios are correct). */
        private fun parseSize(value: String, unit: String): Long {
            val number = value.replace(",", "").toDoubleOrNull() ?: return 0L
            val multiplier = when (unit) {
                "kB" -> 1_000L
                "MB" -> 1_000_000L
                else -> 1L
            }
            return (number * multiplier).toLong()
        }
    }
}
