/*
 * Noxs — original implementation.
 * Diagnostics: Liquid Glass status board for the Linux environment.
 *
 * Every card reflects a REAL probe result — never a fake success state:
 *   - RootFS integrity: RootfsIntegrity.verify over the actual rootfs tree
 *   - Docker: `nx docker doctor --json` executed inside the sandbox
 *     (CLI, daemon, DNS, registry, storage driver, BuildKit, kernel limits)
 *   - Device/kernel capabilities: NoxsDeviceCapabilities
 *
 * Recovery actions: rootfs repair (app-owned shims + FHS/loader links),
 * guest repair dry-run and confirmed guest repair (docker-lib.sh) — Docker
 * storage/images/containers are never touched by any button here.
 */
package com.crossberry.noxs.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.crossberry.noxs.NoxsApplication
import com.crossberry.noxs.R
import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.databinding.ActivityStackBinding
import com.crossberry.noxs.runtime.NoxsDeviceCapabilities
import com.crossberry.noxs.runtime.NoxsDockerDoctorJson
import com.crossberry.noxs.runtime.NoxsRuntimeFactory
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.runtime.NoxsResources
import com.crossberry.noxs.runtime.ProotLauncher
import com.crossberry.noxs.runtime.RootfsConfigurator
import com.crossberry.noxs.runtime.RootfsIntegrity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStackBinding
    private lateinit var paths: NoxsPaths
    private lateinit var stack: LinearLayout
    private var lastGuestReport: NoxsDockerDoctorJson.Report? = null
    private var lastGuestReasons: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStackBinding.inflate(layoutInflater)
        setContentView(binding.root)
        paths = (application as NoxsApplication).paths
        stack = binding.stack

        binding.screenTitle.text = getString(R.string.title_diagnostics)
        binding.btnBack.setOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        renderRootfsCard()
        renderCapabilitiesCard()
        renderGuestActions()
        refreshDockerCard()
    }

    // ------------------------------------------------------- rootfs card

    private fun renderRootfsCard() {
        val report = RootfsIntegrity.verify(paths.rootfs)
        val status = when {
            report.healthy -> NoxsDockerDoctorJson.Status.OK
            report.missingShims.isNotEmpty() || report.missingExecutables.isNotEmpty() -> NoxsDockerDoctorJson.Status.FAIL
            else -> NoxsDockerDoctorJson.Status.WARN
        }
        val summary = when (status) {
            NoxsDockerDoctorJson.Status.OK -> getString(R.string.diag_rootfs_ok)
            else -> getString(R.string.diag_rootfs_issues, report.structuralDamage.size + report.missingExecutables.size + report.missingShims.size)
        }
        val card = DiagnosticsWidgets.glassCard(
            this,
            DiagnosticsWidgets.headlineRow(this, getString(R.string.diag_card_rootfs), status, summary)
        )
        report.rows.filter { !it.ok }.take(6).forEach {
            card.addView(DiagnosticsWidgets.statusRow(this, it.path, NoxsDockerDoctorJson.Status.FAIL, it.detail))
        }
        if (report.healthy) {
            card.addView(DiagnosticsWidgets.infoLine(this, getString(R.string.diag_rootfs_detail_ok, report.rows.size)))
        }
        card.addView(DiagnosticsWidgets.actionRow(
            this,
            DiagnosticsWidgets.actionButton(this, getString(R.string.diag_action_rootfs_repair)) { repairRootfs() },
            DiagnosticsWidgets.actionButton(this, getString(R.string.action_refresh)) { refreshAll() }
        ))
        stack.addView(DiagnosticsWidgets.sectionHeader(this, getString(R.string.diag_section_rootfs)))
        stack.addView(card)
    }

    private fun repairRootfs() {
        // Runs entirely on app-private storage — no rootfs re-extract, no
        // user data touched; restores Noxs shims + FHS/loader links only.
        lifecycleScope.launch {
            val applied = withContext(Dispatchers.IO) {
                runCatching {
                    val repairs = RootfsIntegrity.repair(paths.rootfs)
                    RootfsConfigurator.ensureHealthyRootfs(paths)
                    repairs
                }.getOrElse { listOf("repair failed: ${it.message}") }
            }
            if (isFinishing || isDestroyed) return@launch
            val text = if (applied.isEmpty()) getString(R.string.diag_repair_nothing) else applied.joinToString("\n")
            AlertDialog.Builder(this@DiagnosticsActivity)
                .setTitle(R.string.diag_action_rootfs_repair)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            refreshAll()
        }
    }

    // ------------------------------------------------------ docker card

    private fun refreshDockerCard() {
        lifecycleScope.launch {
            // Real probe: nx docker doctor --json inside the sandbox.
            // stdout = one JSON object; stderr = the human PASS/WARN/FAIL rows.
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    NoxsRuntimeFactory.executor(ProotLauncher(paths, NoxsResources(paths)))
                        .run(listOf("nx", "docker", "doctor", "--json"), asRoot = false, timeoutSec = 90)
                }
            }
            if (isFinishing || isDestroyed) return@launch
            val report = result.fold(
                onSuccess = { NoxsDockerDoctorJson.parse(it.stdout) },
                onFailure = { NoxsDockerDoctorJson.parse("") }
            )
            lastGuestReport = report
            lastGuestReasons = result.getOrNull()?.stderr
                ?.lineSequence()?.filter { it.startsWith("WARN") || it.startsWith("FAIL") }
                ?.take(6)?.toList() ?: emptyList()
            renderDockerCard(report)
        }
    }

    private fun renderDockerCard(report: NoxsDockerDoctorJson.Report) {
        // Rebuild the whole docker section slot (header + card share the tag).
        val oldIndex = dockerSectionIndex()
        if (oldIndex >= 0) {
            stack.removeViews(oldIndex, 2) // section header + card
        }

        val overall = when {
            !report.valid -> NoxsDockerDoctorJson.Status.UNKNOWN
            report.rootfs == NoxsDockerDoctorJson.Status.FAIL -> NoxsDockerDoctorJson.Status.FAIL
            report.daemon == NoxsDockerDoctorJson.Status.OK -> NoxsDockerDoctorJson.Status.OK
            else -> NoxsDockerDoctorJson.Status.WARN
        }
        val overallSummary = when {
            !report.valid -> getString(R.string.diag_docker_probe_unavailable)
            report.daemon == NoxsDockerDoctorJson.Status.OK -> getString(R.string.diag_docker_running)
            report.cli == NoxsDockerDoctorJson.Status.FAIL -> getString(R.string.diag_docker_not_installed)
            else -> getString(R.string.diag_docker_stopped)
        }

        val card = DiagnosticsWidgets.glassCard(
            this,
            DiagnosticsWidgets.headlineRow(this, getString(R.string.diag_card_docker), overall, overallSummary)
        )
        card.addView(DiagnosticsWidgets.statusRow(this, getString(R.string.diag_row_rootfs), report.rootfs,
            if (report.rootfs == NoxsDockerDoctorJson.Status.UNKNOWN) "" else getString(R.string.diag_value_from_doctor)))
        card.addView(DiagnosticsWidgets.statusRow(this, getString(R.string.diag_row_cli), report.cli, ""))
        card.addView(DiagnosticsWidgets.statusRow(this, getString(R.string.diag_row_daemon), report.daemon, ""))
        if (report.mode.isNotBlank()) {
            card.addView(DiagnosticsWidgets.infoLine(this, getString(R.string.diag_row_mode, report.mode)))
        }
        card.addView(DiagnosticsWidgets.statusRow(this, getString(R.string.diag_row_dns), report.dns, ""))
        card.addView(DiagnosticsWidgets.statusRow(this, getString(R.string.diag_row_registry), report.registry, ""))
        card.addView(DiagnosticsWidgets.statusRow(this, getString(R.string.diag_row_storage), storageStatus(report),
            report.storage.ifBlank { getString(R.string.diag_value_unknown) }))
        card.addView(DiagnosticsWidgets.statusRow(this, getString(R.string.diag_row_buildkit), report.buildkit,
            if (report.buildkit == NoxsDockerDoctorJson.Status.WARN) getString(R.string.diag_buildkit_classic) else ""))
        card.addView(DiagnosticsWidgets.statusRow(this, getString(R.string.diag_row_kernel), report.kernel,
            if (report.kernel == NoxsDockerDoctorJson.Status.WARN) getString(R.string.diag_kernel_limited) else ""))
        if (report.arch.isNotBlank() && report.valid) {
            card.addView(DiagnosticsWidgets.infoLine(this, getString(R.string.diag_row_arch, report.arch)))
        }
        // Honest failure reasons: the doctor's own WARN/FAIL rows.
        lastGuestReasons.forEach { card.addView(DiagnosticsWidgets.infoLine(this, it)) }

        card.addView(DiagnosticsWidgets.actionRow(
            this,
            DiagnosticsWidgets.actionButton(this, getString(R.string.diag_action_repair_check)) { runGuestRepair(check = true) },
            DiagnosticsWidgets.actionButton(this, getString(R.string.diag_action_repair_apply)) { confirmGuestRepair() }
        ))

        stack.addView(DiagnosticsWidgets.sectionHeader(this, getString(R.string.diag_section_docker)).also { it.tag = DOCKER_TAG })
        stack.addView(card.also { it.tag = DOCKER_TAG })
    }

    private fun storageStatus(report: NoxsDockerDoctorJson.Report): NoxsDockerDoctorJson.Status = when {
        !report.valid -> NoxsDockerDoctorJson.Status.UNKNOWN
        report.storage.isBlank() -> NoxsDockerDoctorJson.Status.UNKNOWN
        report.storage == "vfs" -> NoxsDockerDoctorJson.Status.WARN
        else -> NoxsDockerDoctorJson.Status.OK
    }

    private fun dockerSectionIndex(): Int {
        for (i in 0 until stack.childCount) {
            if (stack.getChildAt(i).tag == DOCKER_TAG) return i
        }
        return -1
    }

    private fun runGuestRepair(check: Boolean) {
        val args = if (check) listOf("nx", "docker", "repair", "--check") else listOf("nx", "docker", "repair", "--yes")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    NoxsRuntimeFactory.executor(ProotLauncher(paths, NoxsResources(paths)))
                        .run(args, asRoot = false, timeoutSec = 600)
                }
            }
            if (isFinishing || isDestroyed) return@launch
            val message = result.fold(
                onSuccess = {
                    buildString {
                        append(it.stdout.trim())
                        if (it.stderr.isNotBlank()) append("\n").append(it.stderr.trim())
                        if (!it.success) append("\n").append(getString(R.string.diag_repair_exit, it.exitCode))
                    }.ifBlank { getString(R.string.diag_repair_nothing) }
                },
                onFailure = { getString(R.string.diag_repair_probe_failed, it.message ?: "") }
            )
            AlertDialog.Builder(this@DiagnosticsActivity)
                .setTitle(if (check) R.string.diag_action_repair_check else R.string.diag_action_repair_apply)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            if (!check) refreshDockerCard()
        }
    }

    private fun confirmGuestRepair() {
        AlertDialog.Builder(this)
            .setTitle(R.string.diag_action_repair_apply)
            .setMessage(R.string.diag_repair_confirm)
            .setPositiveButton(R.string.diag_repair_confirm_apply) { _, _ -> runGuestRepair(check = false) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------- capabilities card

    private fun renderCapabilitiesCard() {
        val caps = NoxsDeviceCapabilities.probe(this, paths)
        val status = when {
            caps.ptySupported && caps.unixSocketsSupported -> NoxsDockerDoctorJson.Status.OK
            else -> NoxsDockerDoctorJson.Status.WARN
        }
        val card = DiagnosticsWidgets.glassCard(
            this,
            DiagnosticsWidgets.headlineRow(this, getString(R.string.diag_card_kernel), status, caps.arch)
        )
        caps.toDisplayLines().forEach { (k, v) ->
            card.addView(DiagnosticsWidgets.infoLine(this, "$k: $v"))
        }
        caps.detail.forEach { (k, v) ->
            card.addView(DiagnosticsWidgets.infoLine(this, "$k: $v"))
        }
        stack.addView(DiagnosticsWidgets.sectionHeader(this, getString(R.string.diag_section_kernel)))
        stack.addView(card)
    }

    // ------------------------------------------------------ guest actions

    private fun renderGuestActions() {
        val card = DiagnosticsWidgets.glassCard(
            this,
            DiagnosticsWidgets.actionRow(
                this,
                DiagnosticsWidgets.actionButton(this, getString(R.string.diag_copy)) { copyReport() }
            ),
            DiagnosticsWidgets.infoLine(this, getString(R.string.diag_privacy_note))
        )
        stack.addView(DiagnosticsWidgets.sectionHeader(this, getString(R.string.diag_section_actions)))
        stack.addView(card)
    }

    private fun refreshAll() {
        stack.removeAllViews()
        renderRootfsCard()
        renderCapabilitiesCard()
        renderGuestActions()
        refreshDockerCard()
    }

    // -------------------------------------------------------------- share

    private fun copyReport() {
        val text = buildString {
            appendLine("=== Noxs diagnostics ===")
            appendLine("RootFS integrity:")
            RootfsIntegrity.verify(paths.rootfs).displayLines().forEach { appendLine(it) }
            lastGuestReport?.let {
                appendLine("Docker doctor: cli=${it.cli} daemon=${it.daemon} storage=${it.storage} dns=${it.dns} " +
                    "registry=${it.registry} buildkit=${it.buildkit} kernel=${it.kernel} arch=${it.arch}")
            }
            lastGuestReasons.forEach { appendLine(it) }
            appendLine("=== log ring ===")
            NoxsLog.snapshot().forEach { appendLine(it) }
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("noxs-diagnostics", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val DOCKER_TAG = "diag_docker_section"
    }
}
