/*
 * Noxs — original implementation.
 * JVM tests for the opt-in direct shared-storage mount decision (pure logic;
 * the Android grant probe is exercised on device).
 */
package com.crossberry.noxs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoxsStorageMountTest {

    @Test fun `bind is added only when enabled AND android granted access`() {
        assertEquals(
            listOf("/storage/emulated/0" to "/sdcard"),
            NoxsStorageMount.guestBinds(enabled = true, directReady = true)
        )
    }

    @Test fun `no bind without the explicit user toggle`() {
        assertTrue(NoxsStorageMount.guestBinds(enabled = false, directReady = true).isEmpty())
    }

    @Test fun `no bind without the android grant`() {
        assertTrue(NoxsStorageMount.guestBinds(enabled = true, directReady = false).isEmpty())
    }

    @Test fun `no bind when both are missing`() {
        assertTrue(NoxsStorageMount.guestBinds(enabled = false, directReady = false).isEmpty())
    }
}
