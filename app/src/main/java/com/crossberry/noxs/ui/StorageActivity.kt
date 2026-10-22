/*
 * Noxs — original implementation.
 * Storage management: SAF document permissions, usage report, cache cleanup, and sandbox controls.
 */
package com.crossberry.noxs.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityManagerBinding
import com.crossberry.noxs.runtime.NoxsDocumentsPolicy
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.runtime.NoxsResources
import com.crossberry.noxs.runtime.NoxsRuntimeFactory
import com.crossberry.noxs.runtime.NoxsStorageBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StorageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagerBinding
    private lateinit var paths: NoxsPaths
    private lateinit var storageBridge: NoxsStorageBridge
    private lateinit var storagePickerLauncher: ActivityResultLauncher<Intent>
    private var pendingCategory: NoxsStorageBridge.Category? = null
    private var adapter: TwoLineAdapter? = null

    private val resources by lazy { NoxsResources(paths) }
    private val storage by lazy { NoxsRuntimeFactory.storage(paths, resources) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onStoragePickerResult(result)
        }
        binding = ActivityManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        paths = (application as com.crossberry.noxs.NoxsApplication).paths
        storageBridge = NoxsStorageBridge(this, paths)

        binding.screenTitle.text = getString(R.string.title_storage)
        binding.btnAction.text = getString(R.string.action_refresh)
        binding.btnAction.setOnClickListener { render() }
        binding.inputBar.visibility = View.GONE

        adapter = TwoLineAdapter.bind(binding.recycler, listOf())

        binding.btnInputAction.setOnClickListener { }
        pendingCategory = savedInstanceState?.getString(STATE_STORAGE_CATEGORY)
            ?.let { NoxsStorageBridge.Category.fromKey(it) }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::storageBridge.isInitialized) render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingCategory?.let { outState.putString(STATE_STORAGE_CATEGORY, it.key) }
        super.onSaveInstanceState(outState)
    }

    /** Launches the system document picker rooted at the Noxs Linux home
     *  (DocumentsUI shows the NoxsDocumentsProvider root). Edits there write
     *  through to the real guest filesystem. */
    private fun openNoxsHomeInSystemFiles() {
        try {
            val initialUri = DocumentsContract.buildDocumentUri(
                packageName + ".documents", NoxsDocumentsPolicy.HOME_DOC
            )
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.err_generic, Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickStorageTree(category: NoxsStorageBridge.Category) {
        AlertDialog.Builder(this)
            .setTitle("Choose ${category.label} folder")
            .setMessage(
                "Android will ask you to choose a folder. Noxs can access only that user-selected folder through " +
                    "noxs storage commands; it will not be mounted as a normal Linux directory."
            )
            .setPositiveButton("Continue") { _, _ ->
                pendingCategory = category
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    )
                }
                storagePickerLauncher.launch(intent)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun onStoragePickerResult(result: ActivityResult) {
        val category = pendingCategory ?: return
        pendingCategory = null
        val uri: Uri? = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null) {
            Toast.makeText(this, "Folder access was cancelled; the existing mapping was kept", Toast.LENGTH_LONG).show()
            return
        }
        val outcome = runCatching {
            storageBridge.grantTree(category, uri, result.data?.flags ?: 0)
        }
        Toast.makeText(
            this,
            if (outcome.isSuccess) "${category.label} folder permission saved" else outcome.exceptionOrNull()?.message ?: "Could not save folder permission",
            Toast.LENGTH_LONG
        ).show()
        render()
    }

    private fun confirmSafReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset Noxs SAF mappings?")
            .setMessage(
                "This clears Noxs' selected-folder mappings and releases its saved SAF permissions only. " +
                    "It does not delete, move, or edit any Android documents or folders."
            )
            .setPositiveButton("Reset mappings") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val outcome = runCatching { storageBridge.resetMappings() }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@StorageActivity,
                            if (outcome.isSuccess) "Noxs mappings reset; Android files were not changed" else "Could not reset mappings",
                            Toast.LENGTH_LONG
                        ).show()
                        render()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmUnmap(category: NoxsStorageBridge.Category) {
        if (!storageBridge.hasExplicitMapping(category)) {
            Toast.makeText(this, "${category.label} is inherited from Shared or is not mapped directly", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Release ${category.label} mapping?")
            .setMessage("This removes Noxs' saved permission for the selected folder. It will not delete or change any files.")
            .setPositiveButton("Release mapping") { _, _ ->
                storageBridge.unmap(category)
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun render() {
        lifecycleScope.launch {
            val (report, safStatus) = withContext(Dispatchers.IO) {
                storage.report() to storageBridge.status()
            }
            val safRows = buildList {
                add(
                    TwoLineRow(
                        title = "Android folders (SAF)",
                        subtitle = "Choose specific folders for Noxs commands. This does not create Linux paths or a POSIX mount."
                    )
                )
                safStatus.forEach { status ->
                    val state = when {
                        status.available -> status.displayName ?: "available"
                        storageBridge.hasExplicitMapping(status.category) -> "permission revoked or unavailable"
                        else -> "not mapped"
                    }
                    add(
                        TwoLineRow(
                            title = "${status.category.label}: $state",
                            subtitle = "Tap to choose or replace with Android's folder picker; long-press to release this Noxs mapping",
                            onClick = { pickStorageTree(status.category) },
                            onLongClick = { confirmUnmap(status.category) }
                        )
                    )
                }
                add(
                    TwoLineRow(
                        title = "Reset Noxs SAF mappings",
                        subtitle = "Releases saved folder permissions only; never deletes Android documents",
                        onClick = { confirmSafReset() }
                    )
                )
                add(
                    TwoLineRow(
                        title = getString(R.string.storage_open_noxs_files),
                        subtitle = getString(R.string.storage_open_noxs_files_desc),
                        onClick = { openNoxsHomeInSystemFiles() }
                    )
                )
                add(
                    TwoLineRow(
                        title = "Use from terminal",
                        subtitle = "noxs-setup-storage · noxs storage status · noxs storage ls <category> · get / put"
                    )
                )
            }
            val rows = safRows + listOf(
                TwoLineRow(
                    title = "${getString(R.string.storage_rootfs)}: ${report.rootfsMb} MB",
                    subtitle = if (report.rootfsMb > report.warnMb) getString(R.string.storage_warn_exceeded) else "limit warning at ${report.warnMb} MB"
                ),
                TwoLineRow(
                    title = "${getString(R.string.storage_apt_cache)}: ${report.aptCacheMb} MB",
                    subtitle = getString(R.string.storage_clear_cache),
                    onClick = {
                        lifecycleScope.launch {
                            val n = withContext(Dispatchers.IO) { storage.clearAptCache() }
                            Toast.makeText(this@StorageActivity, "$n .deb removed", Toast.LENGTH_SHORT).show()
                            render()
                        }
                    }
                ),
                TwoLineRow(
                    title = "${getString(R.string.storage_logs)}: ${report.logsMb} MB",
                    subtitle = getString(R.string.diag_clear),
                    onClick = {
                        com.crossberry.noxs.shared.NoxsLog.clear()
                        render()
                    }
                ),
                TwoLineRow(
                    title = getString(R.string.storage_reset),
                    subtitle = "Deletes only Noxs' Debian app sandbox; does not affect Android shared-storage files.",
                    onClick = {
                        AlertDialog.Builder(this@StorageActivity)
                            .setTitle(R.string.confirm_reset_title)
                            .setMessage(R.string.confirm_reset_msg)
                            .setPositiveButton(R.string.action_confirm) { _, _ ->
                                lifecycleScope.launch {
                                    val ok = withContext(Dispatchers.IO) { storage.resetSandbox() }
                                    Toast.makeText(
                                        this@StorageActivity,
                                        if (ok) R.string.settings_saved else R.string.err_generic,
                                        Toast.LENGTH_LONG
                                    ).show()
                                    render()
                                }
                            }
                            .setNegativeButton(R.string.action_cancel, null)
                            .show()
                    }
                )
            )
            adapter?.submit(rows)
            binding.screenEmpty.visibility = if (paths.rootfs.isDirectory) View.GONE else View.VISIBLE
            binding.screenEmpty.text = getString(R.string.err_not_installed)
        }
    }

    private companion object {
        const val STATE_STORAGE_CATEGORY = "storage_category"
    }
}
