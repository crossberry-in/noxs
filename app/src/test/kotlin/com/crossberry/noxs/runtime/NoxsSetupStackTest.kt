/*
 * Noxs — original implementation.
 * JVM tests: honest Docker capability classification, package-manager
 * single-flight guard, and the sanitized setup event stream.
 */
package com.crossberry.noxs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoxsDockerProbeTest {

    @Test
    fun `cli missing reports cli and engine unavailable with install hint`() {
        val r = NoxsDockerProbe.classify(
            versionExit = 126, versionOut = "",
            infoExit = 126, infoErr = "", infoOut = ""
        )
        assertFalse(r.cliAvailable)
        assertFalse(r.engineAvailable)
        assertTrue(r.engineReason.contains("apt install docker.io"))
    }

    @Test
    fun `cli present with dead daemon reports real daemon error`() {
        val r = NoxsDockerProbe.classify(
            versionExit = 0,
            versionOut = "Docker version 24.0.7, build afdd8b2",
            infoExit = 1,
            infoErr = "ERROR: Cannot connect to the Docker daemon at unix:///var/run/docker.sock",
            infoOut = "Client:\n Context: default"
        )
        assertTrue(r.cliAvailable)
        assertTrue(r.cliVersion.contains("24.0.7"))
        assertFalse(r.engineAvailable)
        assertTrue(r.engineReason.contains("Cannot connect to the Docker daemon"))
    }

    @Test
    fun `cli and engine reachable reports both available`() {
        val r = NoxsDockerProbe.classify(
            versionExit = 0,
            versionOut = "Docker version 24.0.7, build afdd8b2",
            infoExit = 0,
            infoErr = "",
            infoOut = "Server:\n Engine Version: 24.0.7"
        )
        assertTrue(r.cliAvailable)
        assertTrue(r.engineAvailable)
    }

    @Test
    fun `display lines always show the real state pair`() {
        val down = NoxsDockerProbe.displayLines(
            NoxsDockerProbe.classify(0, "Docker version 1.0", 1, "ERROR: Cannot connect to the Docker daemon", ""))
        assertTrue(down.any { it == "Docker CLI: AVAILABLE" })
        assertTrue(down.any { it == "Docker Engine: UNAVAILABLE" })
    }
}

class NoxsPkgTransactionTest {

    @Test
    fun `single flight - second owner blocked until release`() {
        assertTrue(NoxsPkgTransaction.acquire("setup"))
        assertFalse(NoxsPkgTransaction.acquire("packages-screen"))
        assertEquals("setup", NoxsPkgTransaction.currentOwner())
        NoxsPkgTransaction.release("setup")
        assertNull(NoxsPkgTransaction.currentOwner())
        assertTrue(NoxsPkgTransaction.acquire("packages-screen"))
        NoxsPkgTransaction.release("packages-screen")
    }

    @Test
    fun `release by non-owner does not clear the slot`() {
        assertTrue(NoxsPkgTransaction.acquire("setup"))
        NoxsPkgTransaction.release("packages-screen")
        assertEquals("setup", NoxsPkgTransaction.currentOwner())
        NoxsPkgTransaction.release("setup")
        assertNull(NoxsPkgTransaction.currentOwner())
    }

    @Test
    fun `busy message never suggests deleting locks`() {
        val msg = NoxsPkgTransaction.BUSY_MESSAGE.lowercase()
        assertFalse(msg.contains("delete"))
        assertFalse(msg.contains("remove the lock"))
        assertFalse(msg.contains("rm "))
        assertTrue(msg.contains("busy"))
    }
}

class NoxsSetupEventLogTest {

    private fun events(file: File): List<String> = file.readLines().filter { it.isNotBlank() }

    @Test
    fun `event lines are valid json objects with event name first`() {
        val file = File.createTempFile("events", ".jsonl")
        try {
            NoxsSetupEventLog.append(file, NoxsSetupEventLog.Event(NoxsSetupEventLog.STARTED))
            NoxsSetupEventLog.append(file, NoxsSetupEventLog.Event(NoxsSetupEventLog.STEP_STARTED, 5, 12_345L))
            val lines = events(file)
            assertEquals(2, lines.size)
            assertTrue(lines[0].startsWith("{\"ts\":"))
            assertTrue(lines[0].contains("\"event\":\"setup.started\""))
            assertTrue(lines[1].contains("\"event\":\"setup.step.started\""))
            assertTrue(lines[1].contains("\"step\":5"))
            assertTrue(lines[1].contains("\"elapsed_ms\":12345"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `notes never contain urls or private paths`() {
        val dirty = "failed fetching https://ports.ubuntu.com/ubuntu-ports from /data/user/0/noxs/rootfs"
        val clean = NoxsSetupEventLog.sanitize(dirty)
        assertFalse(clean.contains("https://"))
        assertFalse(clean.contains("/data/"))
        assertTrue(clean.contains("<url>"))
        assertTrue(clean.contains("<path>"))
    }

    @Test
    fun `quotes and newlines are escaped for jsonl safety`() {
        val clean = NoxsSetupEventLog.sanitize("line1\nline2 \"quoted\"")
        assertFalse(clean.contains("\n"))
        assertFalse(clean.contains("\""))
    }
}
