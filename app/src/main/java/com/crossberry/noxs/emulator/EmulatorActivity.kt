/*
 * Noxs — original implementation.
 * NoxOS emulator window (Noxs × NoxOS M2/M6/M7): opens from the terminal
 * sidebar, AUTO-BOOTS the VM (sidebar click → boot → display), and shows
 * the guest framebuffer through the localhost VNC bridge. Interaction:
 * touch → VNC pointer events (virtio-tablet on the guest side), Android
 * IME + extra keys → VNC keysyms (virtio-keyboard).
 *
 * HONEST STATUS: implemented with limitations. The QEMU command set and
 * the RFB protocol logic are verified on real QEMU 7.2.22 (the M1 gate;
 * Ubuntu noble ships QEMU 8.2 for the guest runtime). This Activity compiles
 * and ships via CI, on-device interaction testing is M9. The VM keeps
 * running when this window closes and dies with the app process (M8).
 */
package com.crossberry.noxs.emulator

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.crossberry.noxs.NoxsApplication
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityEmulatorBinding
import com.crossberry.noxs.shared.emulator.DisplayTransform
import com.crossberry.noxs.shared.emulator.QemuCommand
import com.crossberry.noxs.shared.emulator.Keysym
import com.crossberry.noxs.shared.emulator.ReconnectPolicy
import com.crossberry.noxs.shared.NoxsLog
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class EmulatorActivity : AppCompatActivity() {

    private companion object {
        /** Structured log tags (integration spec §15) — no credentials ever. */
        private const val LOG_VNC = "NOXS_VNC"
        private const val LOG_RENDERER = "NOXS_RENDERER"
        private const val LOG_INPUT = "NOXS_INPUT"
        private const val LOG_FB = "NOXS_FRAMEBUFFER"
    }

    private lateinit var binding: ActivityEmulatorBinding

    private var bitmap: Bitmap? = null
    private var fbWidth: Int = 0
    private var fbHeight: Int = 0
    private var vncClient: VncClient? = null
    private var vncPort: Int = 0
    private var bootRequested: Boolean = false
    private var stickyCtrl: Boolean = false
    private var latestState: VmState = VmState.Idle

    /** VNC bridge connection state (lifecycle spec): CONNECTING →
     *  HANDSHAKING → RECEIVING_FRAMEBUFFER → LIVE, with bounded
     *  RECONNECTING between LIVE attempts and FAILED at the end. */
    private enum class ConnState {
        IDLE, CONNECTING, HANDSHAKING, RECEIVING_FRAMEBUFFER, LIVE, RECONNECTING, FAILED
    }

    private var connState: ConnState = ConnState.IDLE

    /** Bounded auto-reconnect ladder; reset on every live connection and
     *  on a manual retry (integration spec §5 — never retry forever). */
    private val reconnectPolicy = ReconnectPolicy()
    private var reconnectJob: Job? = null

    // Single source of truth for render + touch (integration spec §3):
    // the ImageView consumes the matrix computed here and the touch
    // pipeline maps coordinates through the same instance.
    private val transform = DisplayTransform()
    private var lastTransformLog: String? = null
    private var lastSentMask: Int = -1

    // Host-side gestures. VNC pointer is a single-pointer interface: the
    // guest receives ONE finger; pinch/pan/double-tap are host-side only.
    private lateinit var scaleDetector: ScaleGestureDetector
    private val doubleTapDetector: GestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                transform.resetZoom()
                applyTransform()
                return true
            }
        })
    }
    private var touchSlop: Int = 0
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var downTouchX: Float = 0f
    private var downTouchY: Float = 0f
    private var movedBeyondSlop: Boolean = false

    // Pure UI state — never touches the VM or the VNC socket.
    private var isFullscreen: Boolean = false
    private var keyboardVisible: Boolean = false

    // ------------------------------------------------- VNC bridge plumbing

    /**
     * Starts a fresh VncClient (new socket + new reader thread — a dead
     * socket is never reused) with a listener bound to THAT instance.
     * The identity guard makes a stale client's late callbacks harmless:
     * a manual retry installs a new client before the old reader thread
     * has finished, and only the CURRENT client may drive the state
     * machine or null out [vncClient] (prevents duplicate readers).
     */
    private fun startVncClient(port: Int) {
        var self: VncClient? = null
        val listener = object : VncClient.Listener {
            private fun isCurrent(): Boolean = vncClient === self

            override fun onConnecting() {
                runOnUiThread {
                    if (isCurrent()) setConnState(ConnState.HANDSHAKING)
                }
            }

            override fun onConnected(width: Int, height: Int, name: String) {
                runOnUiThread {
                    if (!isCurrent()) return@runOnUiThread
                    handleBridgeConnected(width, height, name)
                }
            }

            override fun onResized(width: Int, height: Int) {
                runOnUiThread {
                    if (!isCurrent()) return@runOnUiThread
                    handleFrameResized(width, height)
                }
            }

            override fun onRect(x: Int, y: Int, w: Int, h: Int, pixels: IntArray) {
                runOnUiThread {
                    if (!isCurrent()) return@runOnUiThread
                    handleRect(x, y, w, h, pixels)
                }
            }

            override fun onClosed(error: String?) {
                runOnUiThread {
                    if (!isCurrent()) {
                        // A newer client owns the bridge; this old reader
                        // thread's death must not touch the state machine.
                        NoxsLog.d(LOG_VNC, "stale bridge closed — ignored")
                        return@runOnUiThread
                    }
                    vncClient = null
                    handleBridgeClosed(error)
                }
            }
        }
        val client = VncClient("127.0.0.1", port, listener)
        self = client
        vncClient = client
        client.start()
    }

    private fun handleBridgeConnected(width: Int, height: Int, name: String) {
        fbWidth = width
        fbHeight = height
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        binding.emulatorView.setImageBitmap(bitmap)
        transform.setFramebuffer(width, height)
        applyTransform()
        // A live connection resets the bounded reconnect ladder.
        reconnectPolicy.reset()
        reconnectJob?.cancel()
        setConnState(ConnState.RECEIVING_FRAMEBUFFER)
        NoxsLog.i(LOG_VNC, "connected ${width}x${height} name=$name")
        NoxsLog.i(LOG_FB, "framebuffer ${width}x${height} taken from ServerInit (no render before this)")
        binding.emulatorOverlay.visibility = View.GONE
        binding.btnVmRetry.visibility = View.GONE
        setKeysVisible(true)
    }

    private fun handleFrameResized(width: Int, height: Int) {
        fbWidth = width
        fbHeight = height
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        binding.emulatorView.setImageBitmap(bitmap)
        transform.setFramebuffer(width, height)
        applyTransform()
        NoxsLog.i(LOG_FB, "framebuffer resized ${width}x${height} — transform recomputed")
    }

    private fun handleRect(x: Int, y: Int, w: Int, h: Int, pixels: IntArray) {
        val bmp = bitmap ?: return
        if (x + w > bmp.width || y + h > bmp.height) return
        bmp.setPixels(pixels, 0, w, x, y, w, h)
        if (connState != ConnState.LIVE) {
            // First pixels after (re)connect — the bridge is LIVE.
            setConnState(ConnState.LIVE)
            NoxsLog.i(LOG_FB, "first rect ${w}x${h} at ($x,$y) — bridge LIVE")
        }
        binding.emulatorView.invalidate()
    }

    private fun handleBridgeClosed(error: String?) {
        when {
            error == null ->
                // We closed the bridge ourselves (stop/destroy/retry).
                setConnState(ConnState.IDLE)
            latestState is VmState.Running ->
                // Guest is fine — only the bridge died: bounded automatic
                // reconnect, then the manual retry button takes over.
                scheduleReconnect(error)
            else -> {
                setConnState(ConnState.FAILED)
                showOverlay(
                    getString(R.string.emulator_status_failed),
                    error
                )
                binding.emulatorProgress.visibility = View.GONE
                binding.btnVmRetry.visibility = View.VISIBLE
            }
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmulatorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // AUTO-BOOT contract: opening the window starts the VM.
        triggerBootIfNeeded()

        binding.emulatorView.isFocusable = true
        binding.emulatorView.isFocusableInTouchMode = true
        binding.emulatorView.setOnTouchListener(::onDisplayTouch)
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    transform.addZoom(detector.scaleFactor)
                    applyTransform()
                    return true
                }
            }
        )
        // Any layout change (rotation, keyboard, system bars, fullscreen
        // toggles) recomputes the letterbox from the ACTUAL view size.
        binding.emulatorView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyTransform()
        }
        // Fullscreen back navigation: leave fullscreen first, never kill
        // the guest session accidentally.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isFullscreen) exitFullScreen() else finish()
                }
            }
        )

        // AUTO-BOOT: opening this window boots the VM (sidebar entry contract).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val app = application as NoxsApplication
                launch { app.emulator.state.collect { render(it) } }
                launch { app.emulator.installLines.collect { renderInstallLines(it) } }
                launch { app.emulator.setupProgress.collect { renderSetupProgress(it) } }
            }
        }

        binding.btnVmRetry.setOnClickListener {
            // The same button serves two recovery paths: a live VM with a
            // dead bridge → reconnect the VNC client; anything else → boot.
            if (latestState is VmState.Running) manualBridgeRetry() else triggerBootIfNeeded()
        }
        binding.btnVmPower.setOnClickListener { confirmPowerOff() }
        binding.btnVmLogs.setOnClickListener { showVmLogs() }
        binding.btnVmKeyboard.setOnClickListener { toggleKeyboard() }
        binding.btnVmFullscreen.setOnClickListener { enterFullScreen() }
        binding.btnFsExit.setOnClickListener { exitFullScreen() }

        wireExtraKeys()
    }

    override fun onDestroy() {
        // VM keeps running (M2 contract; M8 adds background lifecycle).
        vncClient?.close()
        vncClient = null
        super.onDestroy()
    }

    // ------------------------------------------------------------- state

    private fun render(state: VmState) {
        (application as NoxsApplication).emulator.pollForUnexpectedExit()
        latestState = state
        when (state) {
            is VmState.Idle -> {
                showOverlay(getString(R.string.emulator_status_idle), null)
                binding.btnVmRetry.visibility = View.VISIBLE
                connectIfRunning(null)
            }
            is VmState.Preparing -> {
                showOverlay(getString(R.string.emulator_status_preparing), state.detail)
                binding.btnVmRetry.visibility = View.GONE
            }
            is VmState.InstallingRuntime -> {
                showOverlay(getString(R.string.emulator_status_installing), null)
                binding.btnVmRetry.visibility = View.GONE
            }
            is VmState.Booting -> {
                showOverlay(getString(R.string.emulator_status_booting), "VNC 127.0.0.1:${QemuCommand.vncPort(state.vncDisplay)}")
                binding.btnVmRetry.visibility = View.GONE
            }
            is VmState.StartingQemu -> {
                showOverlay(getString(R.string.emulator_status_booting), "QEMU starting — VNC 127.0.0.1:${QemuCommand.vncPort(state.vncDisplay)}")
                binding.btnVmRetry.visibility = View.GONE
            }
            is VmState.Stopping -> {
                showOverlay(getString(R.string.emulator_status_stopping), null)
                binding.btnVmRetry.visibility = View.GONE
            }
            is VmState.Running -> {
                binding.emulatorOverlay.visibility = View.GONE
                setKeysVisible(true)
                connectIfRunning(QemuCommand.vncPort(state.vncDisplay))
            }
            is VmState.Stopped -> {
                reconnectJob?.cancel()
                vncClient?.close(); vncClient = null
                setConnState(ConnState.IDLE)
                showOverlay(getString(R.string.emulator_status_stopped), state.detail)
                binding.btnVmRetry.visibility = View.VISIBLE
            }
            is VmState.Failed -> {
                vncClient?.close(); vncClient = null
                // The stale guard swallows the closing client's onClosed,
                // so the chip must be updated here explicitly.
                setConnState(ConnState.FAILED)
                val message = if (state.message.contains("runtime is not ready")) {
                    getString(R.string.emulator_setup_required)
                } else state.message
                showOverlay(getString(R.string.emulator_status_failed), message)
                binding.btnVmRetry.visibility = View.VISIBLE
            }
        }
        // Progress bar / percent visibility follows the busy-state only;
        // values arrive via renderSetupProgress.
        val busy = state is VmState.Preparing || state is VmState.InstallingRuntime ||
            state is VmState.Booting || state is VmState.StartingQemu
        if (!busy) {
            binding.emulatorProgress.visibility = View.GONE
            binding.emulatorPercent.visibility = View.GONE
        }
    }

    private fun showOverlay(title: String, detail: String?) {
        binding.emulatorOverlay.visibility = View.VISIBLE
        binding.emulatorStatus.text = title
        binding.emulatorStatusDetail.text = detail ?: ""
        binding.emulatorStatusDetail.visibility = if (detail.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.emulatorView.visibility = View.VISIBLE
    }

    /** LIVE setup log: last lines of the apt/boot stream, every 200 ms. */
    private fun renderInstallLines(lines: List<String>) {
        if (lines.isEmpty()) return
        binding.emulatorInstallLines.text = lines.takeLast(8).joinToString("\n")
    }

    /** LIVE setup percent: label + determinate bar driven by the manager. */
    private fun renderSetupProgress(progress: SetupProgress) {
        val busyProgress = latestState is VmState.Preparing || latestState is VmState.InstallingRuntime ||
            latestState is VmState.Booting || latestState is VmState.StartingQemu
        if (!busyProgress || progress.percent <= 0) return
        binding.emulatorProgress.visibility = View.VISIBLE
        binding.emulatorProgress.progress = progress.percent
        binding.emulatorPercent.visibility = View.VISIBLE
        binding.emulatorPercent.text =
            getString(R.string.emulator_percent_format, progress.percent, stageLabel(progress.subPhase))
    }

    private fun stageLabel(phase: SetupSubPhase): String = when (phase) {
        SetupSubPhase.PREPARING -> getString(R.string.emulator_stage_storage)
        SetupSubPhase.KERNEL -> getString(R.string.emulator_stage_kernel)
        SetupSubPhase.PACKAGE_LISTS -> getString(R.string.emulator_stage_lists)
        SetupSubPhase.DOWNLOADING -> getString(R.string.emulator_stage_download)
        SetupSubPhase.INSTALLING_PACKAGES -> getString(R.string.emulator_stage_dpkg)
        SetupSubPhase.BOOTING -> getString(R.string.emulator_status_booting)
        SetupSubPhase.RUNNING -> getString(R.string.emulator_status_running)
        SetupSubPhase.IDLE -> ""
    }

    private fun triggerBootIfNeeded() {
        val app = application as NoxsApplication
        val state = app.emulator.state.value
        val needsBoot = state is VmState.Idle || state is VmState.Stopped || state is VmState.Failed
        if (needsBoot && !bootRequested) {
            bootRequested = true
            lifecycleScope.launch {
                app.emulator.boot()
                    .onFailure {
                        bootRequested = false
                    }
                    .onSuccess {
                        bootRequested = false
                    }
            }
        }
    }

    // ------------------------------------------------------------- display

    private fun connectIfRunning(port: Int?) {
        if (port == null || vncClient != null) return
        vncPort = port
        reconnectJob?.cancel()
        setConnState(ConnState.CONNECTING)
        startVncClient(port)
    }

    /**
     * Bounded automatic reconnect (integration spec §5): the QEMU guest
     * keeps running, so a dropped bridge must heal itself — up the policy
     * ladder (1s/2s/4s/8s/15s, five attempts), then the manual retry
     * button takes over. A dead socket is never reused: every attempt
     * builds a fresh VncClient (new socket + new reader thread).
     */
    private fun scheduleReconnect(reason: String) {
        if (!reconnectPolicy.canRetry()) {
            setConnState(ConnState.FAILED)
            showOverlay(getString(R.string.emulator_status_failed), reason)
            binding.emulatorProgress.visibility = View.GONE
            binding.btnVmRetry.visibility = View.VISIBLE
            NoxsLog.w(LOG_VNC, "reconnect bound exhausted — manual retry required")
            return
        }
        val delayMs = reconnectPolicy.nextDelayMs()
        val attempt = reconnectPolicy.attemptsUsed()
        setConnState(ConnState.RECONNECTING)
        NoxsLog.w(
            LOG_VNC,
            "bridge dropped ($reason) — reconnect $attempt/${ReconnectPolicy.DEFAULT_MAX_ATTEMPTS} in ${delayMs}ms"
        )
        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            delay(delayMs)
            if (!isFinishing && latestState is VmState.Running && vncClient == null) {
                connectIfRunning(vncPort)
            }
        }
    }

    /** Manual retry: fresh ladder, fresh client — boot only when the VM
     *  is genuinely not running (the button is shared with boot retry). */
    private fun manualBridgeRetry() {
        reconnectPolicy.reset()
        reconnectJob?.cancel()
        vncClient?.close()
        vncClient = null
        connectIfRunning(vncPort)
    }

    /** Single writer for the top-bar connection chip + the NOXS_VNC trail. */
    private fun setConnState(s: ConnState) {
        if (connState == s) return
        connState = s
        val (label, colorRes): Pair<String, Int> = when (s) {
            ConnState.IDLE -> "" to R.color.noxs_text_dim
            ConnState.CONNECTING -> getString(R.string.emulator_conn_connecting) to R.color.noxs_warning
            ConnState.HANDSHAKING -> getString(R.string.emulator_conn_handshake) to R.color.noxs_warning
            ConnState.RECEIVING_FRAMEBUFFER -> getString(R.string.emulator_conn_sync) to R.color.noxs_warning
            ConnState.LIVE -> getString(R.string.emulator_conn_live) to R.color.noxs_accent
            ConnState.RECONNECTING -> getString(
                R.string.emulator_conn_reconnect,
                reconnectPolicy.attemptsUsed(),
                ReconnectPolicy.DEFAULT_MAX_ATTEMPTS
            ) to R.color.noxs_warning
            ConnState.FAILED -> getString(R.string.emulator_conn_failed) to R.color.noxs_error
        }
        binding.emulatorConnChip.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
        binding.emulatorConnChip.text = label
        binding.emulatorConnChip.setTextColor(ContextCompat.getColor(this, colorRes))
        NoxsLog.i(LOG_VNC, "bridge state → $s")
    }

    /** Keys row visibility, suppressed while in full-screen mode. */
    private fun setKeysVisible(visible: Boolean) {
        binding.emulatorKeysScroll.visibility =
            if (visible && !isFullscreen) View.VISIBLE else View.GONE
    }

    /**
     * Pushes the current [DisplayTransform] into the ImageView matrix.
     * The SAME transform instance maps touch → guest coordinates, so the
     * rendered image and the tap targets can never diverge.
     */
    private fun applyTransform() {
        if (bitmap == null) return
        transform.setViewport(binding.emulatorView.width, binding.emulatorView.height)
        if (!transform.isValid()) return
        val m = Matrix()
        val s = transform.scale()
        m.setScale(s, s)
        m.postTranslate(transform.offsetX(), transform.offsetY())
        binding.emulatorView.imageMatrix = m
        binding.emulatorView.invalidate()
        val state = transform.describe()
        if (state != lastTransformLog) {
            lastTransformLog = state
            NoxsLog.i(LOG_RENDERER, "transform $state")
        }
    }

    /** Maps a view point through [transform] and forwards it as a VNC
     *  pointer event (bit 0 = left button). Logs only state transitions
     *  (press/release), not every drag sample. */
    private fun sendGuestPointer(viewX: Float, viewY: Float, buttonMask: Int) {
        val guest = transform.toGuest(viewX, viewY) ?: return
        if (buttonMask != lastSentMask) {
            NoxsLog.d(
                LOG_INPUT,
                "pointer view=(${viewX.toInt()},${viewY.toInt()}) -> " +
                    "guest=(${guest.first},${guest.second}) mask=$buttonMask"
            )
            lastSentMask = buttonMask
        }
        vncClient?.sendPointer(guest.first, guest.second, buttonMask)
    }

    private fun releaseGuestButton() {
        if (lastSentMask == 1) {
            lastSentMask = -1
            vncClient?.sendPointer(lastGuestX, lastGuestY, 0)
        }
    }

    private var lastGuestX: Int = 0
    private var lastGuestY: Int = 0

    /**
     * Host touch pipeline (integration spec §3/§7):
     *   zoom = 1 (fit): one finger IS the guest pointer — tap, drag and
     *     swipe stream press/move/release straight to the guest (the only
     *     path the single-pointer VNC/virtio-tablet interface supports).
     *   zoom > 1: one finger pans the viewport; a still tap still clicks
     *     through; a second finger pinches (host-side zoom only).
     *   double tap: zoom reset. Multi-touch is NEVER claimed as guest
     *     input — the guest receives a single pointer, honestly.
     */
    private fun onDisplayTouch(view: View, event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        doubleTapDetector.onTouchEvent(event)
        if (bitmap == null) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                downTouchX = event.x
                downTouchY = event.y
                movedBeyondSlop = false
                if (!transform.isZoomed()) {
                    val g = transform.toGuest(event.x, event.y)
                    if (g != null) {
                        lastGuestX = g.first
                        lastGuestY = g.second
                    }
                    sendGuestPointer(event.x, event.y, 1)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Hand control to the pinch; never leave the guest button
                // pressed across the gesture transition.
                releaseGuestButton()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!movedBeyondSlop &&
                    (abs(event.x - downTouchX) > touchSlop || abs(event.y - downTouchY) > touchSlop)
                ) {
                    movedBeyondSlop = true
                }
                if (transform.isZoomed()) {
                    if (movedBeyondSlop && event.pointerCount == 1) {
                        transform.panBy(event.x - lastTouchX, event.y - lastTouchY)
                        applyTransform()
                    }
                } else if (movedBeyondSlop) {
                    val g = transform.toGuest(event.x, event.y)
                    if (g != null) {
                        lastGuestX = g.first
                        lastGuestY = g.second
                    }
                    sendGuestPointer(event.x, event.y, 1) // drag with button held
                }
            }
            MotionEvent.ACTION_UP -> {
                if (transform.isZoomed()) {
                    // Still tap while zoomed: click through at the point.
                    if (!movedBeyondSlop) {
                        sendGuestPointer(event.x, event.y, 1)
                        sendGuestPointer(event.x, event.y, 0)
                    }
                } else {
                    sendGuestPointer(event.x, event.y, 0)
                }
            }
            MotionEvent.ACTION_CANCEL -> releaseGuestButton()
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 1) releaseGuestButton()
            }
            else -> return false
        }
        lastTouchX = event.x
        lastTouchY = event.y
        return true
    }

    // ------------------------------------------------------------ keyboard

    /** Android key events → VNC keysyms while the emulator window is live. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (consumeKey(keyCode, event, down = true)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (consumeKey(keyCode, event, down = false)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun consumeKey(keyCode: Int, event: KeyEvent?, down: Boolean): Boolean {
        val client = vncClient ?: return false
        val name = when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> "ENTER"
            KeyEvent.KEYCODE_DEL -> "BACKSPACE"      // backspace
            KeyEvent.KEYCODE_FORWARD_DEL -> "DELETE"
            KeyEvent.KEYCODE_TAB -> "TAB"
            KeyEvent.KEYCODE_ESCAPE -> "ESC"
            KeyEvent.KEYCODE_DPAD_UP -> "UP"
            KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
            KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
            KeyEvent.KEYCODE_MOVE_HOME -> "HOME"
            KeyEvent.KEYCODE_MOVE_END -> "END"
            KeyEvent.KEYCODE_PAGE_UP -> "PAGEUP"
            KeyEvent.KEYCODE_PAGE_DOWN -> "PAGEDOWN"
            KeyEvent.KEYCODE_INSERT -> "INSERT"
            KeyEvent.KEYCODE_CTRL_LEFT -> "CTRL_L"
            KeyEvent.KEYCODE_CTRL_RIGHT -> "CTRL_R"
            KeyEvent.KEYCODE_SHIFT_LEFT -> "SHIFT_L"
            KeyEvent.KEYCODE_SHIFT_RIGHT -> "SHIFT_R"
            KeyEvent.KEYCODE_ALT_LEFT -> "ALT_L"
            KeyEvent.KEYCODE_ALT_RIGHT -> "ALT_R"
            KeyEvent.KEYCODE_F1 -> "F1"
            KeyEvent.KEYCODE_F2 -> "F2"
            KeyEvent.KEYCODE_F3 -> "F3"
            KeyEvent.KEYCODE_F4 -> "F4"
            KeyEvent.KEYCODE_F5 -> "F5"
            KeyEvent.KEYCODE_F6 -> "F6"
            KeyEvent.KEYCODE_F7 -> "F7"
            KeyEvent.KEYCODE_F8 -> "F8"
            KeyEvent.KEYCODE_F9 -> "F9"
            KeyEvent.KEYCODE_F10 -> "F10"
            KeyEvent.KEYCODE_F11 -> "F11"
            KeyEvent.KEYCODE_F12 -> "F12"
            else -> null
        }
        if (name != null) {
            if (down && event != null && event.repeatCount > 0) return true
            client.sendKeyByName(name, down)
            return true
        }
        // Printable characters (soft IME + hardware): Latin-1 identity keysyms.
        val ch = event?.unicodeChar
        if (ch != null && ch != 0) {
            val c = ch.toChar()
            if (Keysym.forChar(c) != null) {
                if (down && event != null && event.repeatCount > 0) return true
                client.sendChar(c, down)
                return true
            }
        }
        return false
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        binding.emulatorView.requestFocus()
        if (keyboardVisible) {
            imm.hideSoftInputFromWindow(binding.emulatorView.windowToken, 0)
            keyboardVisible = false
        } else {
            imm.showSoftInput(binding.emulatorView, 0)
            keyboardVisible = true
        }
    }

    /** Extra keys row: guest-facing keys the Android IME does not send. */
    private fun wireExtraKeys() {
        tapKey(binding.keyEsc, "ESC")
        tapKey(binding.keyTab, "TAB")
        tapKey(binding.keyUp, "UP")
        tapKey(binding.keyDown, "DOWN")
        tapKey(binding.keyLeft, "LEFT")
        tapKey(binding.keyRight, "RIGHT")
        tapKey(binding.keyHome, "HOME")
        tapKey(binding.keyEnd, "END")
        tapKey(binding.keyPageup, "PAGEUP")
        tapKey(binding.keyPagedown, "PAGEDOWN")
        // Sticky Ctrl: tap to press, tap again to release (Ctrl+C style).
        binding.keyCtrl.setOnClickListener {
            stickyCtrl = !stickyCtrl
            vncClient?.sendKeyByName("CTRL_L", stickyCtrl)
            binding.keyCtrl.setBackgroundResource(
                if (stickyCtrl) R.drawable.bg_pill_accent
                else R.drawable.bg_pill
            )
        }
    }

    private fun tapKey(view: View, name: String) {
        view.setOnClickListener {
            vncClient?.sendKeyByName(name, true)
            vncClient?.sendKeyByName(name, false)
        }
    }

    // ---------------------------------------------------------- fullscreen

    /**
     * Full-screen mode is pure UI state: the QEMU process and the VNC
     * connection are NEVER touched, so toggling cannot disturb the guest
     * session (integration spec §6).
     */
    private fun enterFullScreen() {
        if (isFullscreen) return
        isFullscreen = true
        binding.emulatorTopBar.visibility = View.GONE
        setKeysVisible(false)
        binding.btnFsExit.visibility = View.VISIBLE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, binding.emulatorDisplayArea).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        NoxsLog.i(LOG_RENDERER, "fullscreen on")
        applyTransform()
    }

    private fun exitFullScreen() {
        if (!isFullscreen) return
        isFullscreen = false
        binding.emulatorTopBar.visibility = View.VISIBLE
        setKeysVisible(latestState is VmState.Running || latestState is VmState.Booting)
        binding.btnFsExit.visibility = View.GONE
        // Back to normal (cutout/status-bar aware) window fitting.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowCompat.getInsetsController(window, binding.emulatorDisplayArea)
            .show(WindowInsetsCompat.Type.systemBars())
        NoxsLog.i(LOG_RENDERER, "fullscreen off")
        applyTransform()
    }

    // ------------------------------------------------------------ power

    private fun confirmPowerOff() {
        AlertDialog.Builder(this)
            .setTitle(R.string.emulator_power_off)
            .setMessage(R.string.emulator_power_confirm)
            .setPositiveButton(R.string.emulator_power_off) { _, _ ->
                lifecycleScope.launch {
                    (application as NoxsApplication).emulator.stop()
                    finish()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showVmLogs() {
        val storage = (application as NoxsApplication).emulator.storage()
        val qemu = storage.logTail(storage.qemuLogFile, 14)
        val serial = storage.logTail(storage.serialLogFile, 14)
        val text = buildString {
            append("qemu.log\n")
            if (qemu.isEmpty()) append("(empty)\n") else append(qemu.joinToString("\n") { "  $it" }).append("\n")
            append("\nserial.log\n")
            if (serial.isEmpty()) append("(empty)") else append(serial.joinToString("\n") { "  $it" })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.emulator_logs_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
