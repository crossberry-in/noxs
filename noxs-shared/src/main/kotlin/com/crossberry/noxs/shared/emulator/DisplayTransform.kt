/*
 * Noxs — original implementation.
 * DisplayTransform: the single source of truth for mapping guest
 * framebuffer pixels to view pixels (and back) in the NoxOS emulator
 * display (integration spec §3: one transform for rendering AND touch).
 *
 * The device bug class this prevents: the ImageView displayed the
 * framebuffer with its own scaleType while the touch path recomputed an
 * independent fit-center transform — any future change to one side (or a
 * mid-frame resize) silently desynchronised tap targets from what the
 * user saw. Now the renderer consumes the matrix produced here and the
 * touch pipeline asks the same instance for guest coordinates.
 *
 * Model (view units are pixels, guest units are framebuffer pixels):
 *   base    = min(viewWidth / fbWidth, viewHeight / fbHeight)  (fit-center)
 *   scale   = base * zoom
 *   offsetX = (viewWidth  - fbWidth  * scale) / 2 + panX
 *   offsetY = (viewHeight - fbHeight * scale) / 2 + panY
 *   guestX  = (viewX - offsetX) / scale   (clamped into the framebuffer)
 *   guestY  = (viewY - offsetY) / scale
 *
 * Pan is clamped so a zoomed image never reveals background beyond its
 * edges; at zoom = 1 the pan is always (0, 0) and the result is exactly
 * the classic letterboxed fit-center (aspect ratio preserved, no stretch).
 *
 * Pure JVM: no android.* imports, unit tested in DisplayTransformTest.
 */
package com.crossberry.noxs.shared.emulator

import java.util.Locale

class DisplayTransform {

    /** Zoom clamp: 1× (fit) .. [MAX_ZOOM]× (pinch limit). */
    companion object {
        const val MAX_ZOOM: Float = 4f
        const val MIN_ZOOM: Float = 1f
    }

    private var fbW: Int = 0
    private var fbH: Int = 0
    private var vw: Int = 0
    private var vh: Int = 0

    private var zoom: Float = MIN_ZOOM
    private var panX: Float = 0f
    private var panY: Float = 0f

    /** Guest framebuffer dimensions (from VNC ServerInit / resize). */
    fun setFramebuffer(width: Int, height: Int) {
        require(width >= 0 && height >= 0) { "negative framebuffer: ${width}x$height" }
        fbW = width
        fbH = height
        clampPan()
    }

    /** Actual view/canvas dimensions in pixels (recomputed on layout). */
    fun setViewport(width: Int, height: Int) {
        require(width >= 0 && height >= 0) { "negative viewport: ${width}x$height" }
        vw = width
        vh = height
        clampPan()
    }

    /** True when both a framebuffer and a measured viewport exist. */
    fun isValid(): Boolean = fbW > 0 && fbH > 0 && vw > 0 && vh > 0

    fun isZoomed(): Boolean = zoom > MIN_ZOOM + 1e-6f

    fun currentZoom(): Float = zoom

    /** Absolute zoom set (clamped to [MIN_ZOOM, MAX_ZOOM]). */
    fun setZoom(z: Float) {
        zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (zoom <= MIN_ZOOM + 1e-6f) {
            panX = 0f
            panY = 0f
        }
        clampPan()
    }

    /** Multiplicative pinch step (e.g. detector.scaleFactor). */
    fun addZoom(factor: Float) {
        if (factor > 0f) setZoom(zoom * factor)
    }

    /** Double-tap reset: back to fit-center, no pan. */
    fun resetZoom() = setZoom(MIN_ZOOM)

    /** Absolute pan set in view pixels (clamped so edges stay covered). */
    fun setPan(x: Float, y: Float) {
        if (!isZoomed()) return
        val (maxX, maxY) = panBounds()
        panX = x.coerceIn(-maxX, maxX)
        panY = y.coerceIn(-maxY, maxY)
    }

    /** Drag delta in view pixels: content follows the finger. */
    fun panBy(dx: Float, dy: Float) = setPan(panX + dx, panY + dy)

    /** Effective scale: fit-center base × zoom; 0 until valid. */
    fun scale(): Float {
        if (!isValid()) return 0f
        return baseFit() * zoom
    }

    /** Left/top of the framebuffer inside the view (view pixels). */
    fun offsetX(): Float {
        if (!isValid()) return 0f
        return (vw - fbW * scale()) / 2f + panX
    }

    fun offsetY(): Float {
        if (!isValid()) return 0f
        return (vh - fbH * scale()) / 2f + panY
    }

    /**
     * View point → guest pixel, clamped into the framebuffer (a tap in
     * the letterbox black targets the nearest guest edge instead of
     * sending out-of-bounds coordinates). Returns null while invalid.
     */
    fun toGuest(viewX: Float, viewY: Float): Pair<Int, Int>? {
        if (!isValid()) return null
        val s = scale()
        if (s <= 0f) return null
        val gx = ((viewX - offsetX()) / s).toInt().coerceIn(0, fbW - 1)
        val gy = ((viewY - offsetY()) / s).toInt().coerceIn(0, fbH - 1)
        return Pair(gx, gy)
    }

    /** Compact single-line state for NOXS_RENDERER logs. */
    fun describe(): String =
        "fb=${fbW}x${fbH} view=${vw}x${vh} scale=${"%.3f".format(Locale.US, scale())} " +
            "zoom=${"%.2f".format(Locale.US, zoom)} pan=(${panX.toInt()},${panY.toInt()}) " +
            "off=(${offsetX().toInt()},${offsetY().toInt()})"

    /** min(view/fb) letterbox scale, aspect ratio preserved. */
    private fun baseFit(): Float =
        minOf(vw.toFloat() / fbW, vh.toFloat() / fbH)

    /** Half of the overscan a zoomed image has per axis (≥ 0). */
    private fun panBounds(): Pair<Float, Float> {
        val s = baseFit() * zoom
        val maxX = maxOf(0f, (fbW * s - vw) / 2f)
        val maxY = maxOf(0f, (fbH * s - vh) / 2f)
        return Pair(maxX, maxY)
    }

    private fun clampPan() {
        if (!isValid() || !isZoomed()) {
            panX = 0f
            panY = 0f
            return
        }
        setPan(panX, panY)
    }
}
