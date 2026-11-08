/*
 * Noxs — original implementation.
 * DisplayTransform contract tests: the letterbox math, the single
 * view<->guest mapping (rendering and touch MUST agree), zoom, pan
 * clamping, and resize behaviour. Pure JVM, no Android runtime.
 */
package com.crossberry.noxs.shared.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTransformTest {

    // ------------------------------------------------------------- basics

    @Test
    fun `invalid until framebuffer and viewport are set`() {
        val t = DisplayTransform()
        assertFalse(t.isValid())
        t.setFramebuffer(720, 1280)
        assertFalse(t.isValid())
        t.setViewport(1080, 2064)
        assertTrue(t.isValid())
        assertNull(DisplayTransform().toGuest(1f, 1f))
    }

    @Test
    fun `negative dimensions are rejected`() {
        val t = DisplayTransform()
        try {
            t.setFramebuffer(-1, 1280)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // contract
        }
    }

    // ----------------------------------------------------- letterbox math

    @Test
    fun `portrait fit-center on a taller view letterboxes top and bottom`() {
        val t = DisplayTransform()
        // 720x1280 into 1080x2064: scale limited by width = 1.5.
        t.setFramebuffer(720, 1280)
        t.setViewport(1080, 2064)
        assertEquals(1.5f, t.scale(), 1e-4f)
        assertEquals(0f, t.offsetX(), 1e-4f)
        // Content height = 1280*1.5 = 1920; remaining 144 px split evenly.
        assertEquals(72f, t.offsetY(), 1e-4f)
    }

    @Test
    fun `portrait framebuffer on a landscape view letterboxes the sides`() {
        val t = DisplayTransform()
        t.setFramebuffer(720, 1280)
        t.setViewport(2400, 1080)
        // scale limited by height: 1080/1280 = 0.84375
        assertEquals(0.84375f, t.scale(), 1e-4f)
        assertEquals(0f, t.offsetY(), 1e-4f)
        val contentW = 720 * 0.84375f // 607.5
        assertEquals((2400 - contentW) / 2f, t.offsetX(), 1e-3f)
    }

    @Test
    fun `same aspect ratio fills exactly with no letterbox`() {
        val t = DisplayTransform()
        t.setFramebuffer(720, 1280)
        t.setViewport(720, 1280)
        assertEquals(1f, t.scale(), 1e-6f)
        assertEquals(0f, t.offsetX(), 1e-6f)
        assertEquals(0f, t.offsetY(), 1e-6f)
    }

    // -------------------------------------------------------- mapping round trip

    @Test
    fun `view center maps to guest center and back`() {
        val t = DisplayTransform()
        t.setFramebuffer(720, 1280)
        t.setViewport(1080, 2064)
        val (gx, gy) = t.toGuest(540f, 1032f)!!
        assertEquals(360, gx)
        assertEquals(640, gy)
        // Inverse: guest center back to view center.
        val s = t.scale()
        val vx = t.offsetX() + gx * s
        val vy = t.offsetY() + gy * s
        assertEquals(540f, vx, 1e-2f)
        assertEquals(1032f, vy, 1e-2f)
    }

    @Test
    fun `letterbox taps clamp into the guest framebuffer`() {
        val t = DisplayTransform()
        t.setFramebuffer(720, 1280)
        t.setViewport(1080, 2064)
        // 100 px above the content top edge (offsetY = 72).
        val (gx, gy) = t.toGuest(540f, -28f)!!
        assertEquals(360, gx)
        assertEquals(0, gy)
        // 200 px below the content bottom edge (72 + 1920 = 1992).
        val (gx2, gy2) = t.toGuest(540f, 2192f)!!
        assertEquals(360, gx2)
        assertEquals(1279, gy2)
    }

    @Test
    fun `view corners clamp to guest corners`() {
        val t = DisplayTransform()
        t.setFramebuffer(720, 1280)
        t.setViewport(1080, 2064)
        assertEquals(Pair(0, 0), t.toGuest(-500f, -500f))
        assertEquals(Pair(719, 1279), t.toGuest(5000f, 5000f))
    }

    // ----------------------------------------------------------- zoom + pan

    @Test
    fun `zoom multiplies the fit scale and stays clamped`() {
        val t = DisplayTransform()
        t.setFramebuffer(720, 1280)
        t.setViewport(1080, 2064)
        t.setZoom(2f)
        assertEquals(3f, t.scale(), 1e-4f)
        t.setZoom(99f)
        assertEquals(DisplayTransform.MAX_ZOOM, t.currentZoom(), 1e-6f)
        t.setZoom(0.1f)
        assertEquals(DisplayTransform.MIN_ZOOM, t.currentZoom(), 1e-6f)
        // At 1x the pan is forced back to zero.
        assertEquals(0f, t.offsetX(), 1e-6f)
    }

    @Test
    fun `pinch step is multiplicative and clamped`() {
        val t = DisplayTransform()
        t.setFramebuffer(720, 1280)
        t.setViewport(1080, 2064)
        t.addZoom(2f)
        t.addZoom(2f)
        assertEquals(4f, t.currentZoom(), 1e-4f)
        t.addZoom(2f) // would be 8 — clamped
        assertEquals(4f, t.currentZoom(), 1e-4f)
    }

    @Test
    fun `pan is clamped so a zoomed image never shows background`() {
        val t = DisplayTransform()
        t.setFramebuffer(720, 1280)
        t.setViewport(1080, 2064)
        t.setZoom(2f)
        // content = 2160x3840. Fully panned right/down: the content's right
        // and bottom edges sit exactly on the view's edges (no gap).
        t.setPan(10_000f, 10_000f)
        assertEquals(0f, t.offsetX(), 1e-3f)
        assertEquals(0f, t.offsetY(), 1e-3f)
        // Fully panned left/up: left and top edges exactly covered.
        val s = t.scale() // 3.0
        t.setPan(-10_000f, -10_000f)
        assertEquals(1080f - 720f * s, t.offsetX(), 1e-3f)
        assertEquals(2064f - 1280f * s, t.offsetY(), 1e-3f)
    }

    @Test
    fun `pan at zoom 1 is a no-op`() {
        val t = DisplayTransform()
        t.setFramebuffer(720, 1280)
        t.setViewport(1080, 2064)
        t.panBy(500f, 500f)
        assertEquals(0f, t.offsetX(), 1e-6f)
        assertEquals(72f, t.offsetY(), 1e-6f)
        assertFalse(t.isZoomed())
    }

    @Test
    fun `double tap reset returns to fit-center`() {
        val t = DisplayTransform()
        t.setFramebuffer(720, 1280)
        t.setViewport(1080, 2064)
        t.setZoom(3f)
        t.setPan(100f, 100f)
        t.resetZoom()
        assertEquals(1f, t.currentZoom(), 1e-6f)
        assertEquals(1.5f, t.scale(), 1e-4f)
        assertEquals(0f, t.offsetX(), 1e-6f)
        assertEquals(72f, t.offsetY(), 1e-6f)
    }

    // -------------------------------------------------------------- resize

    @Test
    fun `viewport change recomputes the letterbox in place`() {
        val t = DisplayTransform()
        t.setFramebuffer(720, 1280)
        t.setViewport(1080, 2064)
        assertEquals(1.5f, t.scale(), 1e-4f)
        // Rotation: same fb, landscape viewport.
        t.setViewport(2400, 1080)
        assertEquals(0.84375f, t.scale(), 1e-4f)
        // Zoom is kept across rotation, pan re-clamped to the new bounds.
        t.setZoom(2f)
        t.setPan(10_000f, 10_000f)
        val s = t.scale()
        assertTrue(t.offsetY() + 1280 * s >= 1080f)
    }

    @Test
    fun `framebuffer resize keeps zoom and re-clamps pan`() {
        val t = DisplayTransform()
        t.setFramebuffer(720, 1280)
        t.setViewport(1080, 2064)
        t.setZoom(2f)
        t.setFramebuffer(1280, 720) // guest mode change
        assertEquals(2f, t.currentZoom(), 1e-6f)
        val s = t.scale()
        assertEquals(1080f / 1280f * 2f, s, 1e-4f)
        assertTrue(t.offsetX() + 1280 * s >= 1080f)
    }

    // ------------------------------------------------------------ describe

    @Test
    fun `describe carries the full state for logs`() {
        val t = DisplayTransform()
        t.setFramebuffer(720, 1280)
        t.setViewport(1080, 2064)
        val d = t.describe()
        assertTrue(d.contains("fb=720x1280"))
        assertTrue(d.contains("view=1080x2064"))
        assertTrue(d.contains("scale=1.500"))
        assertTrue(d.contains("zoom=1.00"))
    }
}
