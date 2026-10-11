/*
 * Noxs — original implementation.
 * NXEditor: native code editor over the active Noxs Linux environment.
 *
 * Editing surface: sora-editor's CodeEditor — the same engine Squircle CE
 * embeds (pinned to Squircle's version). Grammar + theme assets are curated
 * from the Squircle CE repository (Apache-2.0, attributed in About/README).
 *
 * Why a native screen and not a JS plugin: sora-editor is a native Android
 * View and cannot render inside the WebView-based Plugin Window. Per the
 * Noxs architecture this ships as a built-in screen exposed through the
 * sidebar and the existing navigation conventions.
 *
 * Filesystem: every operation goes through NoxsEditorFilesystem (validated,
 * canonicalized, no shell). The workspace defaults to the active terminal
 * session's working directory as reported by the shell via OSC 7.
 */
package com.crossberry.noxs.ui

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityEditorBinding
import com.crossberry.noxs.runtime.NoxsEditorFilesystem
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.runtime.NoxsService
import com.crossberry.noxs.runtime.NoxsConstants
import com.crossberry.noxs.runtime.RuntimeHolder
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var paths: NoxsPaths
    private lateinit var fs: NoxsEditorFilesystem

    private class GrammarEntry(
        val name: String,
        val scopeName: String,
        val grammar: String,
        val languageConfiguration: String,
        val embedded: MutableMap<String, String> = HashMap()
    )

    /** One open file. The editor holds the live text; [buffer] mirrors it. */
    private class EditorTab(
        var guestPath: String,
        var title: String,
        var buffer: String,
        var savedLastModified: Long,
        var savedSize: Long,
        var dirty: Boolean,
        var cursorLeftLine: Int = 0,
        var cursorLeftColumn: Int = 0,
        var cursorRightLine: Int = 0,
        var cursorRightColumn: Int = 0
    )

    private var workspace: String = NoxsConstants.DEFAULT_USER_HOME
    private var explorerDir: String = NoxsConstants.DEFAULT_USER_HOME
    private val tabs = mutableListOf<EditorTab>()
    private var activeIndex = -1
    private var explorerAdapter: TwoLineAdapter? = null
    private var engineReady = false
    private var attachedContent: Content? = null
    private var suppressDirtyListener = false
    private var openTerminalPending = false

    private val dirtyListener = object : ContentListener {
        override fun beforeReplace(content: Content) = onContentModified()
        override fun afterInsert(
            content: Content, startLine: Int, startColumn: Int,
            endLine: Int, endColumn: Int, insertedContent: CharSequence
        ) = onContentModified()

        override fun afterDelete(
            content: Content, startLine: Int, startColumn: Int,
            endLine: Int, endColumn: Int, deletedContent: CharSequence
        ) = onContentModified()

        override fun beforeModification(content: Content) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        paths = (application as com.crossberry.noxs.NoxsApplication).environments.activePaths()
        fs = NoxsEditorFilesystem(paths.rootfs)

        workspace = resolveInitialWorkspace()
        explorerDir = workspace

        binding.editorView.setColorScheme(NoxsEditorScheme())
        binding.editorView.setTextSize(14f)
        binding.editorView.setTypefaceText(android.graphics.Typeface.MONOSPACE)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { refreshAll() }
        binding.btnToggleExplorer.setOnClickListener { toggleExplorer() }
        binding.btnNewFile.setOnClickListener { promptCreate(isDirectory = false) }
        binding.btnNewFolder.setOnClickListener { promptCreate(isDirectory = true) }
        binding.btnSave.setOnClickListener { saveActive() }
        binding.btnOpenTerminal.setOnClickListener { openWorkspaceInTerminal() }

        explorerAdapter = TwoLineAdapter.bind(binding.explorerList, emptyList())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (tabs.any { it.dirty }) confirmDiscardOrSaveAll() else finish()
            }
        })

        // Grammar engine + theme load on a background thread (assets only).
        lifecycleScope.launch {
            runCatching { ensureLanguageEngine() }
                .onFailure { com.crossberry.noxs.shared.NoxsLog.w("NXEditor", "grammar engine: ${it.message}") }
        }

        renderWorkspaceTitle()
        refreshExplorer()
    }

    override fun onDestroy() {
        detachContentListener()
        runCatching { binding.editorView.release() }
        super.onDestroy()
    }

    // ------------------------------------------------------- workspace / nav

    private fun resolveInitialWorkspace(): String {
        intent.getStringExtra(EXTRA_WORKSPACE)?.let { requested ->
            if (fs.isDirectory(requested)) return requested
        }
        // Active session cwd as reported by the shell (OSC 7).
        RuntimeHolder.sessions?.sessions?.value?.firstOrNull()?.let { entry ->
            entry.session.emulator.currentWorkingDirectory?.let { cwd ->
                if (fs.isDirectory(cwd)) return cwd
            }
        }
        if (fs.isDirectory(NoxsConstants.DEFAULT_USER_HOME)) {
            return NoxsConstants.DEFAULT_USER_HOME
        }
        Toast.makeText(this, R.string.editor_workspace_missing, Toast.LENGTH_LONG).show()
        return "/"
    }

    private fun renderWorkspaceTitle() {
        binding.workspacePath.text = workspace
    }

    private fun toggleExplorer() {
        val visible = binding.explorerList.visibility == View.VISIBLE
        binding.explorerList.visibility = if (visible) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------- explorer

    private fun refreshExplorer() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { explorerRows() }
            explorerAdapter?.submit(rows)
        }
    }

    private fun explorerRows(): List<TwoLineRow> {
        val rows = mutableListOf<TwoLineRow>()
        if (explorerDir != workspace) {
            rows.add(TwoLineRow("…", explorerDir) {
                explorerDir = File(explorerDir).parent ?: workspace
                refreshExplorer()
            })
        }
        val entries = try {
            fs.list(explorerDir)
        } catch (e: Exception) {
            rows.add(TwoLineRow(getString(R.string.err_generic), e.message ?: "", null, null))
            return rows
        }
        for (entry in entries) {
            val meta = if (entry.isDirectory) "dir" else "${entry.sizeBytes / 1024} KB"
            rows.add(
                TwoLineRow(
                    title = (if (entry.isDirectory) "▸ " else "  ") + entry.name,
                    subtitle = meta,
                    onClick = {
                        if (entry.isDirectory) {
                            explorerDir = entry.guestPath
                            refreshExplorer()
                        } else {
                            openFile(entry.guestPath)
                        }
                    },
                    onLongClick = { promptEntryActions(entry) }
                )
            )
        }
        return rows
    }

    private fun promptEntryActions(entry: NoxsEditorFilesystem.ListingEntry) {
        val options = arrayOf(
            getString(R.string.action_rename),
            getString(R.string.action_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptRename(entry)
                    1 -> confirmDelete(entry)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun promptRename(entry: NoxsEditorFilesystem.ListingEntry) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(entry.name)
            setSelection(entry.name.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_rename)
            .setView(wrapInput(input))
            .setPositiveButton(R.string.action_rename) { _, _ ->
                val newName = input.text.toString().trim()
                if (!isValidName(newName)) {
                    Toast.makeText(this, R.string.editor_invalid_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    runFs(success = getString(R.string.editor_renamed, newName)) {
                        val target = explorerDir.trimEnd('/') + "/" + newName
                        fs.rename(entry.guestPath, target)
                        // keep open tabs pointing at the renamed path
                        tabs.forEach { tab ->
                            if (tab.guestPath == entry.guestPath) {
                                tab.guestPath = target
                                tab.title = newName
                            }
                        }
                        renderTabs()
                        renderStatus()
                    }
                    refreshExplorer()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete(entry: NoxsEditorFilesystem.ListingEntry) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_delete))
            .setMessage(entry.guestPath)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    runFs(success = getString(R.string.editor_deleted, entry.name)) {
                        fs.delete(entry.guestPath)
                        closeTabsFor(entry.guestPath, force = true)
                    }
                    refreshExplorer()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun promptCreate(isDirectory: Boolean) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.editor_new_name_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(if (isDirectory) R.string.editor_new_folder else R.string.editor_new_file)
            .setView(wrapInput(input))
            .setPositiveButton(R.string.action_create) { _, _ ->
                val name = input.text.toString().trim()
                if (!isValidName(name)) {
                    Toast.makeText(this, R.string.editor_invalid_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val target = explorerDir.trimEnd('/') + "/" + name
                lifecycleScope.launch {
                    runFs(success = getString(R.string.editor_created, name)) {
                        if (isDirectory) fs.createDirectory(target) else fs.createFile(target)
                    }
                    refreshExplorer()
                    if (!isDirectory) openFile(target)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun wrapInput(input: EditText): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val frame = android.widget.FrameLayout(this)
        frame.setPadding(pad, 0, pad, 0)
        frame.addView(input)
        return frame
    }

    private fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            !name.contains('/') && !name.startsWith(".") && name.length <= 255

    // ------------------------------------------------------------ open / tabs

    private fun openFile(guestPath: String) {
        if (tabs.size >= MAX_OPEN_TABS) {
            Toast.makeText(this, R.string.editor_loaded_too_many, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { fs.read(guestPath) }
            }
            loaded.onFailure { e ->
                showError(e.message ?: getString(R.string.err_generic))
            }.onSuccess { file ->
                val existing = tabs.indexOfFirst { it.guestPath == file.guestPath }
                if (existing >= 0) {
                    setActiveTab(existing)
                    return@onSuccess
                }
                ensureLanguageEngineReady()
                val tab = EditorTab(
                    guestPath = file.guestPath,
                    title = file.guestPath.substringAfterLast('/'),
                    buffer = file.text,
                    savedLastModified = file.lastModifiedMs,
                    savedSize = file.sizeBytes,
                    dirty = false
                )
                tabs.add(tab)
                setActiveTab(tabs.size - 1)
                refreshExplorer()
            }
        }
    }

    private suspend fun ensureLanguageEngineReady() {
        runCatching { ensureLanguageEngine() }
            .onFailure { com.crossberry.noxs.shared.NoxsLog.w("NXEditor", "grammar engine: ${it.message}") }
    }

    private fun languageFor(path: String): Language {
        val scope = fs.scopeFor(path.substringAfterLast('/'))
        return if (scope == null) EmptyLanguage() else try {
            TextMateLanguage.create(scope, true)
        } catch (_: Exception) {
            EmptyLanguage()
        }
    }

    private fun setActiveTab(index: Int) {
        // capture the outgoing tab
        val outgoing = tabs.getOrNull(activeIndex)
        if (outgoing != null && index != activeIndex) {
            outgoing.buffer = binding.editorView.text.toString()
            val cursor = binding.editorView.cursor
            val left = cursor.left()
            val right = cursor.right()
            outgoing.cursorLeftLine = left.line
            outgoing.cursorLeftColumn = left.column
            outgoing.cursorRightLine = right.line
            outgoing.cursorRightColumn = right.column
        }
        activeIndex = index
        val tab = tabs.getOrNull(index)
        if (tab == null) {
            detachContentListener()
            binding.editorView.visibility = View.GONE
            binding.editorEmpty.visibility = View.VISIBLE
            binding.editorStatus.text = getString(R.string.editor_status_no_file)
            renderTabs()
            return
        }
        binding.editorView.visibility = View.VISIBLE
        binding.editorEmpty.visibility = View.GONE
        detachContentListener()
        suppressDirtyListener = true
        binding.editorView.setText(tab.buffer)
        suppressDirtyListener = false
        binding.editorView.setEditorLanguage(languageFor(tab.guestPath))
        attachContentListener()
        binding.editorView.setSelectionRegion(
            tab.cursorLeftLine, tab.cursorLeftColumn,
            tab.cursorRightLine, tab.cursorRightColumn
        )
        renderTabs()
        renderStatus()
        checkExternalChange(tab, showBanner = true)
    }

    private fun attachContentListener() {
        attachedContent = binding.editorView.text
        attachedContent?.addContentListener(dirtyListener)
    }

    private fun detachContentListener() {
        attachedContent?.removeContentListener(dirtyListener)
        attachedContent = null
    }

    private fun onContentModified() {
        if (suppressDirtyListener) return
        val tab = tabs.getOrNull(activeIndex) ?: return
        if (!tab.dirty) {
            tab.dirty = true
            renderTabs()
        }
        renderStatus()
    }

    private fun closeTabsFor(guestPath: String, force: Boolean) {
        val index = tabs.indexOfFirst { it.guestPath == guestPath }
        if (index < 0) return
        val tab = tabs[index]
        if (tab.dirty && !force) {
            confirmCloseTab(index)
            return
        }
        tabs.removeAt(index)
        if (activeIndex == index) {
            activeIndex = -1
            setActiveTab(if (tabs.isEmpty()) -1 else index.coerceAtMost(tabs.size - 1))
        } else if (activeIndex > index) {
            activeIndex--
        }
        renderTabs()
    }

    private fun confirmCloseTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        AlertDialog.Builder(this)
            .setTitle(tab.title)
            .setMessage(R.string.editor_discard_changes_msg)
            .setPositiveButton(R.string.action_save) { _, _ ->
                lifecycleScope.launch { saveTab(index); closeTabsFor(tab.guestPath, force = true) }
            }
            .setNegativeButton(R.string.editor_discard) { _, _ ->
                closeTabsFor(tab.guestPath, force = true)
            }
            .setNeutralButton(R.string.action_cancel, null)
            .show()
    }

    // ------------------------------------------------------------ tab strip

    private fun renderTabs() {
        val row = binding.editorTabsRow
        row.removeAllViews()
        binding.editorTabsScroll.visibility = if (tabs.isEmpty()) View.GONE else View.VISIBLE
        val padH = resources.getDimensionPixelSize(R.dimen.editor_tab_padding_h)
        val padV = resources.getDimensionPixelSize(R.dimen.editor_tab_padding_v)
        tabs.forEachIndexed { index, tab ->
            val chip = android.widget.LinearLayout(this)
            chip.orientation = android.widget.LinearLayout.HORIZONTAL
            chip.setPadding(padH, padV, padH, padV)
            if (index == activeIndex) {
                chip.setBackgroundColor(getColor(R.color.noxs_elevated))
            } else {
                chip.setBackgroundColor(Color.TRANSPARENT)
            }
            val label = android.widget.TextView(this)
            label.text = if (tab.dirty) tab.title + " ●" else tab.title
            label.setTextColor(getColor(R.color.noxs_text))
            label.setTextSize(13f, android.util.TypedValue.COMPLEX_UNIT_SP)
            chip.addView(label)
            val close = android.widget.TextView(this)
            close.text = " ✕"
            close.setTextColor(getColor(R.color.noxs_text_dim))
            close.setTextSize(13f, android.util.TypedValue.COMPLEX_UNIT_SP)
            close.setOnClickListener { closeTabsFor(tab.guestPath, force = false) }
            chip.addView(close)
            chip.setOnClickListener { if (index != activeIndex) setActiveTab(index) }
            row.addView(chip)
        }
    }

    // ------------------------------------------------------------ save / reload

    private fun saveActive() {
        val index = activeIndex
        if (index < 0) return
        lifecycleScope.launch { saveTab(index) }
    }

    private suspend fun saveTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        if (index == activeIndex) tab.buffer = binding.editorView.text.toString()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                fs.write(
                    tab.guestPath, tab.buffer,
                    expectedLastModifiedMs = tab.savedLastModified, force = false
                )
                val host = fs.validateGuestPath(tab.guestPath)
                tab.savedLastModified = host.lastModified()
                tab.savedSize = host.length()
            }
        }
        result.onSuccess {
            tab.dirty = false
            renderTabs()
            renderStatus()
            showError(null)
            Toast.makeText(this, R.string.action_save, Toast.LENGTH_SHORT).show()
        }.onFailure { e ->
            val message = e.message ?: getString(R.string.err_generic)
            if (message.contains("changed on disk")) {
                confirmConflictSave(index)
            } else {
                showError(getString(R.string.editor_save_failed, message))
            }
        }
    }

    private fun confirmConflictSave(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        AlertDialog.Builder(this)
            .setTitle(tab.title)
            .setMessage(R.string.editor_file_changed_on_disk)
            .setPositiveButton(R.string.action_save) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { fs.write(tab.guestPath, tab.buffer, force = true) }
                    }.onSuccess {
                        tab.dirty = false
                        renderTabs(); renderStatus(); showError(null)
                    }.onFailure { e ->
                        showError(getString(R.string.editor_save_failed, e.message ?: ""))
                    }
                }
            }
            .setNeutralButton(R.string.editor_action_reload) { _, _ ->
                lifecycleScope.launch { reloadTab(index) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private suspend fun reloadTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        val loaded = withContext(Dispatchers.IO) { runCatching { fs.read(tab.guestPath) } }
        loaded.onSuccess { file ->
            tab.buffer = file.text
            tab.savedLastModified = file.lastModifiedMs
            tab.savedSize = file.sizeBytes
            tab.dirty = false
            if (index == activeIndex) setActiveTab(index) else renderTabs()
            showError(null)
        }.onFailure { e -> showError(e.message ?: getString(R.string.err_generic)) }
    }

    private fun checkExternalChange(tab: EditorTab, showBanner: Boolean) {
        lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) {
                runCatching {
                    val host = fs.validateGuestPath(tab.guestPath)
                    !host.isFile ||
                        host.lastModified() != tab.savedLastModified ||
                        host.length() != tab.savedSize
                }.getOrDefault(false)
            }
            if (!changed) return@launch
            if (tab.dirty && showBanner) {
                showError(getString(R.string.editor_file_changed_on_disk))
            } else if (!tab.dirty) {
                reloadTabSilently(tab)
            }
        }
    }

    private suspend fun reloadTabSilently(tab: EditorTab) {
        val loaded = withContext(Dispatchers.IO) { runCatching { fs.read(tab.guestPath) } }
        loaded.onSuccess { file ->
            tab.buffer = file.text
            tab.savedLastModified = file.lastModifiedMs
            tab.savedSize = file.sizeBytes
            if (tabs.indexOf(tab) == activeIndex) setActiveTab(activeIndex) else renderTabs()
        }
    }

    private fun refreshAll() {
        refreshExplorer()
        tabs.getOrNull(activeIndex)?.let { checkExternalChange(it, showBanner = true) }
    }

    // --------------------------------------------------------- unsaved guard

    private fun confirmDiscardOrSaveAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.editor_discard_changes_title)
            .setMessage(R.string.editor_discard_changes_msg)
            .setPositiveButton(R.string.editor_save_all) { _, _ ->
                lifecycleScope.launch {
                    val dirty = tabs.withIndex().filter { it.value.dirty }
                    var allOk = true
                    for ((index, _) in dirty) {
                        val before = tabs.getOrNull(index) ?: continue
                        if (index == activeIndex) before.buffer = binding.editorView.text.toString()
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                fs.write(before.guestPath, before.buffer, force = true)
                                true
                            }.getOrDefault(false)
                        }
                        if (ok) before.dirty = false else allOk = false
                    }
                    renderTabs()
                    if (allOk) {
                        Toast.makeText(this@EditorActivity, R.string.editor_saved_all, Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        showError(getString(R.string.editor_save_failed, getString(R.string.err_generic)))
                    }
                }
            }
            .setNegativeButton(R.string.editor_discard) { _, _ -> finish() }
            .setNeutralButton(R.string.action_cancel, null)
            .show()
    }

    // ------------------------------------------------------ open in terminal

    private fun openWorkspaceInTerminal() {
        if (openTerminalPending) return // debounce double taps
        if (!fs.isDirectory(workspace)) {
            Toast.makeText(this, R.string.editor_open_terminal_missing, Toast.LENGTH_SHORT).show()
            refreshExplorer()
            return
        }
        openTerminalPending = true
        binding.btnOpenTerminal.isEnabled = false
        binding.btnOpenTerminal.postDelayed({
            openTerminalPending = false
            binding.btnOpenTerminal.isEnabled = true
        }, 800)
        NoxsService.start(this)
        try {
            startActivity(TerminalActivity.openAt(this, workspace))
            Toast.makeText(
                this, getString(R.string.editor_session_created, workspace),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.editor_open_terminal_failed, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ------------------------------------------------------------- plumbing

    private fun showError(message: String?) {
        if (message == null) {
            binding.editorError.visibility = View.GONE
        } else {
            binding.editorError.text = message
            binding.editorError.visibility = View.VISIBLE
        }
    }

    private fun renderStatus() {
        val tab = tabs.getOrNull(activeIndex)
        binding.editorStatus.text = when {
            tab == null -> getString(R.string.editor_status_no_file)
            tab.dirty -> tab.guestPath + " · " + getString(R.string.editor_status_dirty)
            else -> tab.guestPath + " · " + getString(R.string.editor_status_saved)
        }
    }

    private suspend fun runFs(success: String, block: suspend () -> Unit) {
        try {
            block()
            Toast.makeText(this, success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            showError(e.message ?: getString(R.string.err_generic))
        }
    }

    /**
     * Registers the bundled TextMate grammars + theme (once). Mirrors
     * Squircle CE's LanguageInteractorImpl: assets file provider, recursive
     * embedded-language registration, darcula token theme.
     */
    private suspend fun ensureLanguageEngine() {
        if (engineReady) return
        withContext(Dispatchers.IO) {
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))

            val raw = assets.open("languages.json").use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
            val arr = JSONArray(raw)
            val byName = HashMap<String, GrammarEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val entry = GrammarEntry(
                    name = obj.getString("name"),
                    scopeName = obj.getString("scopeName"),
                    grammar = obj.getString("grammar"),
                    languageConfiguration = obj.optString("languageConfiguration", "")
                )
                val embedded = obj.optJSONObject("embeddedLanguages")
                if (embedded != null) {
                    for (key in embedded.keys()) {
                        entry.embedded[key] = embedded.getString(key)
                    }
                }
                byName[entry.name] = entry
            }
            val registered = HashSet<String>()
            fun register(entry: GrammarEntry) {
                if (!registered.add(entry.scopeName)) return
                if (GrammarRegistry.getInstance().findGrammar(entry.scopeName) != null) return
                val stream = FileProviderRegistry.getInstance().tryGetInputStream(entry.grammar)
                    ?: return
                val source = IGrammarSource.fromInputStream(stream, entry.grammar, Charsets.UTF_8)
                val definition = DefaultGrammarDefinition.withLanguageConfiguration(
                    source, entry.languageConfiguration, entry.name, entry.scopeName
                ).withEmbeddedLanguages(entry.embedded)
                GrammarRegistry.getInstance().loadGrammar(definition)
                for (name in entry.embedded.values) {
                    byName[name]?.let { register(it) }
                }
            }
            for (entry in byName.toSortedMap().values) register(entry)

            val themeStream = assets.open("themes/darcula.json")
            ThemeRegistry.getInstance().loadTheme(
                IThemeSource.fromInputStream(themeStream, "darcula.json", Charsets.UTF_8),
                true
            )
        }
        engineReady = true
    }

    companion object {
        private const val EXTRA_WORKSPACE = "noxs.extra.EDITOR_WORKSPACE"
        private const val MAX_OPEN_TABS = 12

        fun open(context: android.content.Context, workspace: String? = null) {
            val intent = android.content.Intent(context, EditorActivity::class.java)
            workspace?.let { intent.putExtra(EXTRA_WORKSPACE, it) }
            context.startActivity(intent)
        }
    }
}
