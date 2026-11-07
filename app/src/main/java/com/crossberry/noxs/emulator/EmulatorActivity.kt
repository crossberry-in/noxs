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
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.crossberry.noxs.NoxsApplication
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityEmulatorBinding
import com.crossberry.noxs.shared.emulator.QemuCommand
import com.crossberry.noxs.shared.emulator.Keysym
import kotlinx.coroutines.launch

class EmulatorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmulatorBinding

    private var bitmap: Bitmap? = null
    private var fbWidth: Int = 0
    private var fbHeight: Int = 0
    private var vncClient: VncClient? = null
    private var vncPort: Int = 0
    private var bootRequested: Boolean = false
    private var stickyCtrl: Boolean = false
    private var latestState: VmState = VmState.Idle

    // ------------------------------------------------------- VNC listener

    private val vncListener = object : VncClient.Listener {
        override fun onConnected(width: Int, height: Int, name: String) {
            runOnUiThread {
                fbWidth = width
                fbHeight = height
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                binding.emulatorView.setImageBitmap(bitmap)
                binding.emulatorOverlay.visibility = View.GONE
                binding.emulatorKeysScroll.visibility = View.VISIBLE
            }
        }

        override fun onResized(width: Int, height: Int) {
            runOnUiThread {
                fbWidth = width
                fbHeight = height
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                binding.emulatorView.setImageBitmap(bitmap)
            }
        }

        override fun onRect(x: Int, y: Int, w: Int, h: Int, pixels: IntArray) {
            runOnUiThread {
                val bmp = bitmap ?: return@runOnUiThread
                if (x + w > bmp.width || y + h > bmp.height) return@runOnUiThread
                bmp.setPixels(pixels, 0, w, x, y, w, h)
                binding.emulatorView.invalidate()
            }
        }

        override fun onClosed(error: String?) {
            runOnUiThread {
                if (error != null && !isFinishing) {
                    showOverlay(
                        getString(R.string.emulator_status_failed),
                        error
                    )
                    binding.emulatorProgress.visibility = View.GONE
                    binding.btnVmRetry.visibility = View.VISIBLE
                }
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

        // AUTO-BOOT: opening this window boots the VM (sidebar entry contract).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val app = application as NoxsApplication
                launch { app.emulator.state.collect { render(it) } }
                launch { app.emulator.installLines.collect { renderInstallLines(it) } }
                launch { app.emulator.setupProgress.collect { renderSetupProgress(it) } }
            }
        }

        binding.btnVmRetry.setOnClickListener { triggerBootIfNeeded() }
        binding.btnVmPower.setOnClickListener { confirmPowerOff() }
        binding.btnVmLogs.setOnClickListener { showVmLogs() }
        binding.btnVmKeyboard.setOnClickListener { toggleKeyboard() }

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
            is VmState.Running -> {
                binding.emulatorOverlay.visibility = View.GONE
                binding.emulatorKeysScroll.visibility = View.VISIBLE
                connectIfRunning(QemuCommand.vncPort(state.vncDisplay))
            }
            is VmState.Stopped -> {
                vncClient?.close(); vncClient = null
                showOverlay(getString(R.string.emulator_status_stopped), state.detail)
                binding.btnVmRetry.visibility = View.VISIBLE
            }
            is VmState.Failed -> {
                vncClient?.close(); vncClient = null
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
            state is VmState.Booting
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
        val busy = latestState is VmState.Preparing || latestState is VmState.InstallingRuntime ||
            latestState is VmState.Booting
        if (!busy || progress.percent <= 0) return
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
        val client = VncClient("127.0.0.1", port, vncListener)
        vncClient = client
        client.start()
    }

    /**
     * Maps view coordinates to framebuffer coordinates with the fitCenter
     * math and forwards pointer events (bit 0 = left button). This is the
     * "view and access" channel: touch reaches the guest through the VNC
     * pointer → QEMU virtio-tablet path.
     */
    private fun onDisplayTouch(view: View, event: MotionEvent): Boolean {
        val bmp = bitmap ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Unit
            else -> return false
        }
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        if (vw <= 0f || vh <= 0f || fbWidth <= 0 || fbHeight <= 0) return false
        val scale = minOf(vw / fbWidth, vh / fbHeight)
        val offsetX = (vw - fbWidth * scale) / 2f
        val offsetY = (vh - fbHeight * scale) / 2f
        val gx = (((event.x - offsetX) / scale).toInt()).coerceIn(0, fbWidth - 1)
        val gy = (((event.y - offsetY) / scale).toInt()).coerceIn(0, fbHeight - 1)
        val button = if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) 0 else 1
        vncClient?.sendPointer(gx, gy, button)
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
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
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
