/*
 * Noxs — original implementation.
 * JVM tests for the bootstrap planner: mirror derivation, storage reserves
 * and network-error classification (spec: honest, specific setup errors).
 */
package com.crossberry.noxs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class InstallerPlanTest {

    @Test
    fun `ubuntu cdimage url stays unique with no mirror`() {
        val primary = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/" +
            "ubuntu-base-24.04.5-base-arm64.tar.gz"
        val candidates = InstallerPlan.candidateUrls(primary)
        assertEquals(listOf(primary), candidates)
    }

    @Test
    fun `raw github urls gain a github com raw mirror`() {
        val primary = "https://raw.githubusercontent.com/crossberry-in/noxs/" +
            "main/noxs-ubuntu-24.04-arm64.tar.gz"
        val candidates = InstallerPlan.candidateUrls(primary)
        assertEquals(primary, candidates[0])
        assertEquals(
            "https://github.com/crossberry-in/noxs/raw/main/noxs-ubuntu-24.04-arm64.tar.gz",
            candidates[1]
        )
    }

    @Test
    fun `non raw urls have no mirror and stay unique`() {
        val candidates = InstallerPlan.candidateUrls("https://example.com/rootfs.tar.gz")
        assertEquals(listOf("https://example.com/rootfs.tar.gz"), candidates)
    }

    @Test
    fun `download reserve keeps slack above artifact size`() {
        assertEquals(29_936_675L + 128L * 1024 * 1024, InstallerPlan.downloadReserveBytes(29_936_675L))
        assertEquals(256L * 1024 * 1024, InstallerPlan.downloadReserveBytes(0L))
    }

    @Test
    fun `extraction reserve is 4x archive`() {
        assertEquals(29_936_675L * 4, InstallerPlan.extractionReserveBytes(29_936_675L))
    }

    @Test
    fun `io failures classify as network errors`() {
        assertTrue(InstallerPlan.isNetworkError(UnknownHostException("raw.githubusercontent.com")))
        assertTrue(InstallerPlan.isNetworkError(SocketTimeoutException("connect timed out")))
        assertTrue(InstallerPlan.isNetworkError(IOException("HTTP 403 for https://…")))
        assertFalse(InstallerPlan.isNetworkError(IllegalStateException("no")))
        assertFalse(InstallerPlan.isNetworkError(InsufficientStorageException(1, 0)))
    }
}
