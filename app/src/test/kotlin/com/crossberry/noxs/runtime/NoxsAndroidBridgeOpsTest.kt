/*
 * Noxs — original implementation.
 * JVM tests for the Noxs Android Bridge additions in this change set:
 * app-version/open-url capability classification and the docs mapping.
 */
package com.crossberry.noxs.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoxsAndroidBridgeOpsTest {

    @Test fun `open-url is a UI operation executed by the foreground activity`() {
        assertTrue(NoxsAndroidBridge.needsUi("open-url"))
    }

    @Test fun `app-version is an immediate bridge operation`() {
        assertFalse(NoxsAndroidBridge.needsUi("app-version"))
    }

    @Test fun `existing operations keep their classification`() {
        listOf("ui-files", "mount-grant", "doc-import", "sms-send").forEach {
            assertTrue("$it must stay a UI operation", NoxsAndroidBridge.needsUi(it))
        }
        listOf("device-info", "mount-on", "perm-status", "notify-post").forEach {
            assertFalse("$it must stay immediate", NoxsAndroidBridge.needsUi(it))
        }
    }
}
