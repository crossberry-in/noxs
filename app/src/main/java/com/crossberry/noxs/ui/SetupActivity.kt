/*
 * Noxs — original implementation.
 * Setup console: the REAL terminal IS the setup screen.
 *
 * A full-screen TerminalView renders the live NoxsInstaller process stream
 * (real steps, real download bytes, real extraction counts, real apt/dpkg
 * output, real failures). A slim toolbar carries only a tiny status
 * indicator and Stop; a passwd-style masked input row collects the sudo
 * password; small [Retry] [Open Shell] [Exit] actions appear on failure.
 *
 * The setup engine itself lives in NoxsSetupSession (application scope):
 * it keeps running across recreation/backgrounding, and the console simply
 * reconnects to the same terminal session.
 */
package com.crossberry.noxs.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.crossberry.noxs.NoxsApplication
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivitySetupBinding
import com.crossberry.noxs.runtime.NoxsService
import com.crossberry.noxs.runtime.NoxsSetupSession
import com.crossberry.noxs.runtime.SetupOpTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private val session: NoxsSetupSession by lazy { (application as NoxsApplication).setupSession }
    private var transitionStarted = false

    // Live long-running operation state (toolbar spinner + elapsed)
    private var lastState: NoxsSetupSession.State = NoxsSetupSession.State.IDLE
    private var liveOpName: String = ""
    private var liveOpAnchorMs = 0L
    private var awaitingPasswordPhase = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as NoxsApplication
        if (app.paths.isInstalled()) {
            // Requirement: if setup is already completed, open the normal
            // Noxs terminal directly — no wizard, no done page.
            NoxsService.start(this)
            startActivity(Intent(this, TerminalActivity::class.java))
            finish()
            return
        }
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Reconnect to the live setup session (also after recreation).
        binding.terminal.attach(session.terminal)
        session.prepareIfNeeded()

        binding.terminal.onScrollStateChanged = { atLiveBottom ->
            binding.btnLatest.visibility = if (atLiveBottom) View.GONE else View.VISIBLE
        }
        binding.btnLatest.setOnClickListener { binding.terminal.scrollToBottom() }

        binding.btnStop.setOnClickListener { confirmStopSetup() }
        binding.btnPasswordSend.setOnClickListener { submitPassword() }
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEND) {
                submitPassword()
                true
            } else {
                false
            }
        }
        binding.btnRetry.setOnClickListener { session.retry() }
        binding.btnOpenShell.setOnClickListener { openShell() }
        binding.btnRepair.setOnClickListener { session.repair() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { session.state.collect { state ->
                    lastState = state
                    render(state)
                    refreshToolbar()
                } }
                launch {
                    session.passwordPhase.collect { phase ->
                        awaitingPasswordPhase = phase
                        binding.tvPasswordPrompt.text = getString(
                            if (phase == 2) R.string.setup_password_confirm else R.string.setup_password_enter
                        )
                        if (phase == 1) binding.etPassword.setText("")
                    }
                }
                launch {
                    session.liveOp.collect { op ->
                        liveOpName = op?.name.orEmpty()
                        liveOpAnchorMs = op?.startedAtElapsedRealtimeMs ?: 0L
                        refreshToolbar()
                    }
                }
                // Presentation ticker: spinner ~10 Hz, elapsed 1 Hz. Lifecycle-
                // aware, cancelled automatically on STOP/destroy — the setup
                // process itself keeps running (monotonic clock keeps time).
                launch {
                    while (true) {
                        session.tickLiveLine()
                        refreshToolbar()
                        delay(100)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        session.onViewChanged = { binding.terminal.onSessionOutputChanged(session.terminal) }
        binding.terminal.onSessionOutputChanged(session.terminal)
    }

    override fun onStop() {
        session.onViewChanged = null
        super.onStop()
    }

    private fun render(state: NoxsSetupSession.State) {
        val statusRes = when (state) {
            NoxsSetupSession.State.IDLE -> R.string.setup_status_idle
            NoxsSetupSession.State.AWAITING_PASSWORD -> R.string.setup_status_awaiting
            NoxsSetupSession.State.RUNNING -> R.string.setup_status_running
            NoxsSetupSession.State.COMPLETED -> R.string.setup_status_completed
            NoxsSetupSession.State.REPAIRING -> R.string.setup_status_repairing
            NoxsSetupSession.State.FAILED -> R.string.setup_status_failed
            NoxsSetupSession.State.CANCELLED -> R.string.setup_status_cancelled
        }
        val colorRes = when (state) {
            NoxsSetupSession.State.RUNNING, NoxsSetupSession.State.COMPLETED -> R.color.noxs_status_ok
            NoxsSetupSession.State.FAILED -> R.color.noxs_status_err
            NoxsSetupSession.State.REPAIRING -> R.color.noxs_status_warn
            NoxsSetupSession.State.AWAITING_PASSWORD, NoxsSetupSession.State.CANCELLED -> R.color.noxs_status_warn
            NoxsSetupSession.State.IDLE -> R.color.noxs_text_dim
        }
        binding.tvSetupStatus.text = getString(statusRes)
        binding.tvSetupStatus.setTextColor(ContextCompat.getColor(this, colorRes))

        val collecting = state == NoxsSetupSession.State.AWAITING_PASSWORD
        binding.passwordRow.visibility = if (collecting) View.VISIBLE else View.GONE
        binding.btnStop.visibility = if (state == NoxsSetupSession.State.RUNNING || state == NoxsSetupSession.State.REPAIRING) View.VISIBLE else View.GONE
        val settled = state == NoxsSetupSession.State.FAILED || state == NoxsSetupSession.State.CANCELLED
        binding.actionsRow.visibility = if (settled) View.VISIBLE else View.GONE

        if (collecting && binding.etPassword.text.isNullOrEmpty()) {
            binding.etPassword.requestFocus()
            binding.etPassword.post { showKeyboard() }
        }
        if (state == NoxsSetupSession.State.COMPLETED) transitionToTerminal()
    }

    private fun showKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etPassword, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Slim toolbar state: `⠋ Checking interrupted dpkg configuration  00:47`
     * while an operation runs; the state chip otherwise. Presentation only —
     * the terminal itself carries the real output.
     */
    private fun refreshToolbar() {
        if (liveOpName.isEmpty()) {
            binding.tvSetupElapsed.text = ""
            binding.tvSetupStatus.text = when (lastState) {
                NoxsSetupSession.State.IDLE -> getString(R.string.setup_status_idle)
                NoxsSetupSession.State.AWAITING_PASSWORD -> getString(R.string.setup_status_awaiting)
                NoxsSetupSession.State.RUNNING -> getString(R.string.setup_status_running)
                NoxsSetupSession.State.COMPLETED -> getString(R.string.setup_status_completed)
                NoxsSetupSession.State.REPAIRING -> getString(R.string.setup_status_repairing)
                NoxsSetupSession.State.FAILED -> getString(R.string.setup_status_failed)
                NoxsSetupSession.State.CANCELLED -> getString(R.string.setup_status_stopped)
            }
            return
        }
        val elapsed = android.os.SystemClock.elapsedRealtime() - liveOpAnchorMs
        val frames = SetupOpTracker.FRAMES
        val frame = frames[(((elapsed / SetupOpTracker.SPINNER_PERIOD_MS) % frames.size).toInt())]
        binding.tvSetupStatus.text = getString(R.string.setup_toolbar_operation, frame, liveOpName)
        binding.tvSetupElapsed.text = SetupOpTracker.formatElapsed(elapsed)
    }

    private fun submitPassword() {
        val text = binding.etPassword.text?.toString().orEmpty()
        if (text.isEmpty()) return
        binding.etPassword.setText("")
        session.submitPassword(text)
    }

    private fun openShell() {
        if (!(application as NoxsApplication).paths.isInstalled()) {
            // Terminal-native feedback instead of a dead button.
            session.console("\u001b[90m[ noxs ] The Linux environment is not installed yet — retry setup first.\u001b[0m\r\n")
            return
        }
        NoxsService.start(this)
        startActivity(Intent(this, TerminalActivity::class.java))
        finish()
    }

    private fun confirmStopSetup() {
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_stop_title)
            .setMessage(R.string.setup_stop_msg)
            .setPositiveButton(R.string.setup_action_stop) { _, _ -> session.cancel() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun transitionToTerminal() {
        if (transitionStarted) return
        transitionStarted = true
        // Reflect the finished install in the environment registry right away
        // so Welcome / Terminal states are consistent within this session.
        (application as NoxsApplication).environments.importLegacyInstallNow()
        lifecycleScope.launch {
            delay(1_200) // let the success lines be read
            NoxsService.start(this@SetupActivity)
            startActivity(Intent(this@SetupActivity, TerminalActivity::class.java))
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (session.state.value == NoxsSetupSession.State.RUNNING || session.state.value == NoxsSetupSession.State.REPAIRING) {
            // Warn, but never kill the running setup: it continues app-side.
            AlertDialog.Builder(this)
                .setTitle(R.string.setup_back_title)
                .setMessage(R.string.setup_back_msg)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        } else {
            super.onBackPressed()
        }
    }
}
