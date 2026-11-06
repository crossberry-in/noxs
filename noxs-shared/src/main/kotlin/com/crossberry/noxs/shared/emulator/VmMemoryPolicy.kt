/*
 * Noxs — original implementation.
 * Guest RAM sizing for the NoxOS VM (Noxs × NoxOS M2). The Android host
 * keeps priority: TCG emulation needs real memory for the translation
 * blocks, so the guest never gets more than a conservative share and a
 * small device degrades gracefully. Pure JVM, unit tested.
 */
package com.crossberry.noxs.shared.emulator

object VmMemoryPolicy {

    const val DEFAULT_MEMORY_MB: Int = 512
    const val MIN_MEMORY_MB: Int = 256

    /**
     * Picks guest memory (MiB) from the host MemTotal value in kB
     * (/proc/meminfo). Tiers: <~1.2 GiB → 256, <~2.5 GiB → 384,
     * otherwise 512. Non-positive input (unreadable /proc) → default.
     */
    fun chooseMemoryMb(memTotalKb: Long): Int = when {
        memTotalKb <= 0L -> DEFAULT_MEMORY_MB
        memTotalKb < 1_200_000L -> MIN_MEMORY_MB
        memTotalKb < 2_500_000L -> 384
        else -> DEFAULT_MEMORY_MB
    }

    /**
     * Parses MemTotal (kB) out of /proc/meminfo content; null when the
     * line is missing or unparseable.
     */
    fun parseMemTotalKb(meminfo: String): Long? {
        for (line in meminfo.lineSequence()) {
            if (line.startsWith("MemTotal:")) {
                val digits = line.substringAfter(':').trim()
                    .takeWhile { it.isDigit() }
                return digits.toLongOrNull()
            }
        }
        return null
    }
}
