/*
 * Noxs — original implementation.
 * SetupProgressTracker unit tests: synthetic apt-get/dpkg output streams →
 * monotonic percent + sub-phase transitions. Pure JVM (no Android).
 */
package com.crossberry.noxs.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupProgressTrackerTest {

    // --------------------------------------------------------- stage entry

    @Test
    fun stageFloorsAdvanceMonotonically() {
        val t = SetupProgressTracker()
        assertEquals(0, t.snapshot().percent)
        assertEquals(SetupSubPhase.IDLE, t.snapshot().subPhase)

        t.beginPrepare()
        assertEquals(SetupSubPhase.PREPARING, t.snapshot().subPhase)
        t.beginKernel()
        assertEquals(SetupSubPhase.KERNEL, t.snapshot().subPhase)
        t.beginUpdate()
        assertEquals(SetupSubPhase.PACKAGE_LISTS, t.snapshot().subPhase)
        t.beginInstall()
        assertEquals(SetupSubPhase.DOWNLOADING, t.snapshot().subPhase)
        t.beginBoot()
        assertEquals(SetupSubPhase.BOOTING, t.snapshot().subPhase)
        t.markRunning()
        assertEquals(SetupSubPhase.RUNNING, t.snapshot().subPhase)
        assertEquals(100, t.snapshot().percent)
    }

    // ------------------------------------------------------- apt-get update

    @Test
    fun updateGetHitLinesAdvancePercentWithinWindow() {
        val t = SetupProgressTracker()
        t.beginUpdate()
        val start = t.snapshot().percent
        t.onLine("Hit:1 http://deb.debian.org/debian bookworm InRelease")
        t.onLine("Get:2 http://deb.debian.org/debian bookworm/main arm64 Packages [8,376 kB]")
        t.onLine("Get:3 http://deb.debian.org/debian bookworm/main Translation-en [1,234 kB]")
        val after = t.snapshot().percent
        assertTrue("start=$start after=$after", after > start)
        t.onLine("Fetched 9,610 kB in 1s (9,610 kB/s)")
        t.onLine("Reading package lists... Done")
        assertEquals(28, t.snapshot().percent)
    }

    // --------------------------------------------- apt-get install: download

    @Test
    fun downloadPercentTracksFetchedBytesAgainstNeedTotal() {
        val t = SetupProgressTracker()
        t.beginInstall()
        t.onLine("Need to get 10.0 kB of archives.")
        t.onLine(
            "Get:1 http://deb.debian.org/debian bookworm/main arm64 " +
                "qemu-system-common arm64 1:7.2+dfsg-7 [4.0 kB]"
        )
        // 4/10 of the 28..58 download window → 28 + 12 = 40.
        assertEquals(40, t.snapshot().percent)
        t.onLine(
            "Get:2 http://deb.debian.org/debian bookworm/main arm64 " +
                "qemu-system-arm arm64 1:7.2+dfsg-7 [6.0 kB]"
        )
        assertEquals(58, t.snapshot().percent)
        assertEquals(SetupSubPhase.DOWNLOADING, t.snapshot().subPhase)
    }

    @Test
    fun fetchedLineCompletesDownloadEvenWithoutTotal() {
        val t = SetupProgressTracker()
        t.beginInstall()
        t.onLine("Get:1 http://deb.debian.org/debian bookworm/main arm64 ipxe-qemu [2,400 kB]")
        assertTrue(t.snapshot().percent in 29..57)
        t.onLine("Fetched 2,400 kB in 0s (11.2 MB/s)")
        assertEquals(58, t.snapshot().percent)
    }

    // ----------------------------------------------------- dpkg unpack/config

    @Test
    fun dpkgProgressLineDrivesInstallWindow() {
        val t = SetupProgressTracker()
        t.beginInstall()
        t.onLine("Need to get 18,000 kB of archives.")
        t.onLine("Fetched 18,000 kB in 2s (9,000 kB/s)")
        t.onLine("Progress: [ 50 %]")
        // download complete (58) + 50% of the 30-point dpkg window → 73.
        assertEquals(73, t.snapshot().percent)
        assertEquals(SetupSubPhase.INSTALLING_PACKAGES, t.snapshot().subPhase)
    }

    @Test
    fun unpackSetupCountingFallbackWithoutProgressLines() {
        val t = SetupProgressTracker()
        t.beginInstall()
        t.onLine("Suggested packages: samba")
        t.onLine("The following NEW packages will be installed:")
        t.onLine("  ipxe-qemu qemu-system-arm qemu-system-common qemu-system-data")
        t.onLine("0 upgraded, 5 newly installed, 0 to remove and 0 not upgraded.")
        t.onLine("Unpacking qemu-system-common (1:7.2+dfsg-7) ...")
        t.onLine("Unpacking qemu-system-data (1:7.2+dfsg-7) ...")
        t.onLine("Setting up qemu-system-common (1:7.2+dfsg-7) ...")
        val after = t.snapshot().percent
        assertTrue("expected unpack/setup progress, got $after", after > 28)
        assertTrue(after <= 88)
    }

    @Test
    fun alreadyInstalledFastForwardsInstallWindow() {
        val t = SetupProgressTracker()
        t.beginInstall()
        t.onLine("qemu-system-arm is already the newest version (1:7.2+dfsg-7+deb12u7).")
        assertEquals(88, t.snapshot().percent)
    }

    // ----------------------------------------------------------- boot crawl

    @Test
    fun bootTickCrawlsFrom88To99AndRunsAt100() {
        val t = SetupProgressTracker()
        t.beginBoot()
        assertEquals(88, t.snapshot().percent)
        t.tickBoot(45_000, 90_000)
        val mid = t.snapshot().percent
        assertTrue("mid=$mid", mid in 89..98)
        t.tickBoot(90_000, 90_000)
        assertEquals(99, t.snapshot().percent)
        t.markRunning()
        assertEquals(100, t.snapshot().percent)
    }

    // ------------------------------------------------------------ monotonic

    @Test
    fun percentNeverDecreasesAcrossWeirdLines() {
        val t = SetupProgressTracker()
        t.beginInstall()
        t.onLine("Need to get 1,000 kB of archives.")
        t.onLine("Get:1 http://deb.debian.org/debian bookworm/main arm64 x [500 kB]")
        val high = t.snapshot().percent
        // Noise that must not move the percent backwards.
        t.onLine("Progress: [ 1 %]")
        t.onLine("Reading package lists... Done")
        t.onLine("Unpacking something-new")
        assertTrue("must be monotonic: high=$high now=${t.snapshot().percent}", t.snapshot().percent >= high)
    }

    @Test
    fun unknownLinesAreIgnoredSafely() {
        val t = SetupProgressTracker()
        t.beginUpdate()
        val before = t.snapshot().percent
        t.onLine("")
        t.onLine("E: Some random error text without markers")
        t.onLine("debconf: falling back to frontend: Readline")
        assertEquals(before, t.snapshot().percent)
    }
}
