/*
 * Noxs — original implementation.
 * JVM tests for the NX Android Bridge decision layer: request parsing,
 * capability classification and the non-identifying report builders.
 * No Android framework here — the Android shell is exercised on device.
 */
package com.crossberry.noxs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoxsAndroidBridgeTest {

    // ------------------------------------------------------------ parsing

    @Test fun `parseRequest reads operation and positional args`() {
        val request = NoxsAndroidBridge.parseRequest(
            "42-1-a",
            listOf("sms-send", "+919876543210", "", "", "", "")
        )
        assertEquals("sms-send", request.operation)
        assertEquals("+919876543210", request.arg(1))
        assertEquals("", request.arg(2))
        assertEquals("42-1-a", request.id)
    }

    @Test fun `parseRequest tolerates short requests`() {
        val request = NoxsAndroidBridge.parseRequest("x", listOf("device-info"))
        assertEquals("device-info", request.operation)
        assertEquals("", request.arg(1))
    }

    @Test fun `parseRequest lowercases the operation only`() {
        val request = NoxsAndroidBridge.parseRequest("x", listOf("CLIP-GET"))
        assertEquals("clip-get", request.operation)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsafe request ids are rejected`() {
        NoxsAndroidBridge.parseRequest("../escape", listOf("device-info"))
    }

    // ------------------------------------------------ capability set

    @Test fun `ui operations are classified`() {
        val ui = listOf("ui-files", "doc-import", "doc-gallery", "camera", "sms-send", "mount-grant", "perm-request")
        ui.forEach { assertTrue("$it must be a UI operation", NoxsAndroidBridge.needsUi(it)) }
        val immediate = listOf("device-info", "net-status", "clip-get", "mount-on", "perm-status", "notify-post")
        immediate.forEach { assertFalse("$it must be immediate", NoxsAndroidBridge.needsUi(it)) }
    }

    // ------------------------------------------------ report builders

    @Test fun `device report contains non-identifying facts only`() {
        val report = NoxsAndroidBridge.deviceReport(
            model = "Pixel 9", device = "tokay", manufacturer = "Google", brand = "google",
            sdkInt = 34, release = "14", abis = listOf("arm64-v8a", "armeabi-v7a"), appVersion = "1.2.1"
        )
        assertTrue(report.contains("Pixel 9"))
        assertTrue(report.contains("API 34"))
        assertTrue(report.contains("arm64-v8a"))
        assertTrue(report.contains("1.2.1"))
        assertFalse("no IMSI/serial in device reports", report.contains("serial", ignoreCase = true))
    }

    @Test fun `battery report formats level and charging state`() {
        val report = NoxsAndroidBridge.batteryReport(76, true, "USB", 31.5)
        assertTrue(report.contains("Level: 76%"))
        assertTrue(report.contains("yes (USB)"))
        assertTrue(report.contains("31.5"))
        val idle = NoxsAndroidBridge.batteryReport(null, false, "unknown source", null)
        assertTrue(idle.contains("on battery"))
    }

    @Test fun `net report never leaks identifiers`() {
        val report = NoxsAndroidBridge.netReport(true, "Wi-Fi", false, true)
        assertTrue(report.contains("Wi-Fi"))
        assertTrue(report.contains("validated"))
        assertFalse(report.contains("SSID", ignoreCase = true))
        assertFalse(report.contains("password", ignoreCase = true))
    }

    @Test fun `capacity line formats gigabytes`() {
        val line = NoxsAndroidBridge.capacityLine("Internal", 128L * 1024 * 1024 * 1024, 64L * 1024 * 1024 * 1024)
        assertTrue(line.contains("Internal"))
        assertTrue(line.contains("64.0 GB free"))
        assertTrue(line.contains("128.0 GB total"))
    }
}
