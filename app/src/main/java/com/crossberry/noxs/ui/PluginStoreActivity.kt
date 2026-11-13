/*
 * Noxs — original implementation.
 * PluginStoreActivity: the Noxs Plugin Store. Search, filters (All /
 * Installed / Updates / Compatible / App update required / Incompatible /
 * Blocked), plugin cards (logo, name, description, version, SDK, category,
 * compatibility, action) and the entry point shared by the sidebar and
 * `nx plug`.
 *
 * Every state comes from NoxsPluginManager — the same service the CLI uses.
 * Nothing here hardcodes plugin metadata. The registry refreshes when the
 * store opens (throttled by NoxsPluginRegistry) and via the Refresh button;
 * offline the last-good cached registry is shown with an offline notice.
 * New plugins get a "New" badge (PluginSeenStore) until the user opens
 * their details page; updates show an "Update" badge only when the
 * installed version is genuinely older.
 */
package com.crossberry.noxs.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.crossberry.noxs.R
import com.crossberry.noxs.runtime.plugins.InstalledPlugin
import com.crossberry.noxs.runtime.plugins.NoxsPluginManager
import com.crossberry.noxs.runtime.plugins.NoxsPluginRuntime
import com.crossberry.noxs.runtime.plugins.PluginCompatState
import com.crossberry.noxs.runtime.plugins.RegistryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class PluginStoreActivity : AppCompatActivity() {

    enum class Filter { ALL, INSTALLED, UPDATES, COMPATIBLE, APP_UPDATE, INCOMPATIBLE, BLOCKED }

    companion object {
        /** Where "Update Noxs" sends the user when this build is too old for a plugin. */
        const val RELEASES_URL = "https://github.com/web12-app/noxs/releases/latest"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var manager: NoxsPluginManager? = null

    private lateinit var adapter: PluginCardAdapter
    private lateinit var statusView: TextView
    private lateinit var offlineView: TextView
    private lateinit var searchInput: EditText
    private lateinit var chipRow: LinearLayout
    private val chipViews = mutableMapOf<Filter, TextView>()

    private var filter: Filter = Filter.ALL
    private var query: String = ""
    private var cards: List<NoxsPluginManager.StoreCard> = emptyList()
    private var offline = false
    private val busyIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val dp: Float
        get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = pluginManager()
        buildUi()
        adapter = PluginCardAdapter(
            onCardClick = { card ->
                manager?.seenStore?.markSeen(listOf(card.entry.id))
                adapter.markSeen(card.entry.id)
                openDetails(card.entry.id)
            },
            onActionClick = { card -> runAction(card.entry.id) }
        )
        contentList.adapter = adapter
        contentList.layoutManager = LinearLayoutManager(this)
        wireChips()
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                applyFilter()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        load()
    }

    // --------------------------------------------------------------- data

    private fun load(force: Boolean = false) {
        statusView.text = getString(R.string.plugin_store_loading)
        statusView.visibility = TextView.VISIBLE
        offlineView.visibility = TextView.GONE
        scope.launch(Dispatchers.IO) {
            val mgr = manager
            val loaded = mgr?.let { m ->
                runCatching { m.storeCards(refresh = true, force = force) }.getOrDefault(emptyList())
            } ?: emptyList()
            val wasOffline = mgr != null && loaded.isNotEmpty() && !mgr.lastRefreshOk
            val seen = mgr?.seenStore?.seen() ?: emptySet()
            scope.launch {
                cards = loaded
                offline = wasOffline
                adapter.setSeen(seen)
                applyFilter()
                offlineView.visibility = if (offline) TextView.VISIBLE else TextView.GONE
            }
        }
    }

    private fun applyFilter() {
        val q = query.trim().lowercase()
        val visible = cards.filter { card ->
            val matchesQuery = q.isEmpty() ||
                card.entry.name.lowercase().contains(q) ||
                card.entry.id.lowercase().contains(q) ||
                card.entry.description.lowercase().contains(q) ||
                (card.entry.category?.lowercase()?.contains(q) ?: false) ||
                card.entry.keywords.any { it.lowercase().contains(q) }
            val state = card.compat.state
            val matchesFilter = when (filter) {
                Filter.ALL -> true
                Filter.INSTALLED -> card.installed != null
                Filter.UPDATES -> card.updateAvailable
                Filter.COMPATIBLE -> state == PluginCompatState.COMPATIBLE ||
                    state == PluginCompatState.PLUGIN_UPDATE_AVAILABLE ||
                    state == PluginCompatState.SDK_MISSING
                Filter.APP_UPDATE -> state == PluginCompatState.APP_UPDATE_REQUIRED
                Filter.INCOMPATIBLE -> state == PluginCompatState.SDK_INCOMPATIBLE
                Filter.BLOCKED -> state == PluginCompatState.BLOCKED
            }
            matchesQuery && matchesFilter
        }
        adapter.submit(visible, installedById(), busyIds)
        statusView.visibility = if (visible.isEmpty() && cards.isNotEmpty() || cards.isEmpty()) {
            statusView.text = when {
                cards.isEmpty() -> getString(R.string.plugin_store_empty)
                visible.isEmpty() && filter == Filter.UPDATES -> getString(R.string.plugin_store_no_updates)
                else -> getString(R.string.plugin_store_no_results)
            }
            TextView.VISIBLE
        } else {
            TextView.GONE
        }
    }

    private fun installedById(): Map<String, InstalledPlugin> =
        cards.mapNotNull { it.installed }.associateBy { it.meta.id }

    // ------------------------------------------------------------- actions

    /** One action per card: install / update / open / enable — by state. */
    private fun runAction(id: String) {
        val card = cards.firstOrNull { it.entry.id == id } ?: return
        val local = card.installed
        val mgr = manager ?: return
        when {
            busyIds.contains(id) -> return
            card.compat.state == PluginCompatState.APP_UPDATE_REQUIRED ||
                card.compat.state == PluginCompatState.SDK_INCOMPATIBLE ||
                card.compat.state == PluginCompatState.BLOCKED -> return // details explain why
            local == null -> install(id)
            card.updateAvailable -> install(id, update = true)
            !local.enabled -> {
                runCatching { mgr.enable(id) }
                    .onFailure { toast(getString(R.string.plugin_action_failed)) }
                    .onSuccess {
                        toast(getString(R.string.plugin_enabled_toast, local.meta.name))
                        load()
                    }
            }
            else -> openPlugin(local)
        }
    }

    private fun install(id: String, update: Boolean = false) {
        val mgr = manager ?: return
        busyIds.add(id)
        applyFilter()
        toast(getString(R.string.plugin_installing_toast))
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching { if (update) mgr.update(id) else mgr.install(id) }
            val runtime = outcome.getOrNull()?.let { installed -> mgr.sdkRuntimeJs(installed) }
            scope.launch {
                busyIds.remove(id)
                outcome
                    .onSuccess { installed ->
                        // Load the plugin right after a successful install,
                        // through its verified Noxs Plugin SDK bootstrap.
                        if (runtime?.js != null) {
                            NoxsPluginRuntime.activate(applicationContext, installed, runtime.js)
                            toast(getString(R.string.plugin_installed_toast, installed.meta.name))
                        } else {
                            toast(
                                runtime?.message
                                    ?: getString(R.string.plugin_installed_toast, installed.meta.name)
                            )
                        }
                    }
                    .onFailure { failure ->
                        toast(failure.message ?: getString(R.string.plugin_install_failed_toast))
                    }
                load()
            }
        }
    }

    private fun openPlugin(plugin: InstalledPlugin) {
        if (NoxsPluginRuntime.isActive(plugin.meta.id)) return
        busyIds.add(plugin.meta.id)
        scope.launch(Dispatchers.IO) {
            val runtime = manager?.sdkRuntimeJs(plugin)
            scope.launch {
                busyIds.remove(plugin.meta.id)
                val js = runtime?.js
                if (js != null) {
                    NoxsPluginRuntime.activate(applicationContext, plugin, js)
                    Toast.makeText(this@PluginStoreActivity, getString(R.string.plugin_opened_toast), Toast.LENGTH_SHORT).show()
                } else {
                    toast(
                        runtime?.message ?: getString(R.string.plugin_action_failed)
                    )
                }
            }
        }
    }

    private fun openDetails(id: String) {
        startActivity(Intent(this, PluginDetailsActivity::class.java)
            .putExtra(PluginDetailsActivity.EXTRA_PLUGIN_ID, id))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ UI

    private lateinit var contentList: RecyclerView

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B1016.toInt())
        }
        setContentView(root)

        // Header: "Plugins" + refresh + close.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.plugin_store_title)
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 19f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = getString(R.string.plugin_action_refresh)
            setTextColor(0xFF7CE8B8.toInt())
            textSize = 13f
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener { load(force = true) }
        })
        header.addView(TextView(this).apply {
            text = getString(R.string.action_close)
            setTextColor(0xFF9AA7B4.toInt())
            textSize = 13f
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener { finish() }
        })
        root.addView(header)

        // Search box.
        searchInput = EditText(this).apply {
            hint = getString(R.string.plugin_store_search_hint)
            setSingleLine(true)
            setTextColor(0xFFE6EDF3.toInt())
            setHintTextColor(0xFF5A6875.toInt())
            setBackgroundColor(0xFF151C24.toInt())
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        }
        root.addView(searchInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins((14 * dp).toInt(), (2 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt()) })

        // Filter chips (scrollable): All / Installed / Updates / Compatible /
        // App update required / Incompatible / Blocked.
        val chipScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
        }
        chipScroller.addView(chipRow)
        root.addView(chipScroller)

        // Offline notice (cached registry).
        offlineView = TextView(this).apply {
            text = getString(R.string.plugin_store_offline)
            setTextColor(0xFFF0C674.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt(), (2 * dp).toInt())
            visibility = TextView.GONE
        }
        root.addView(offlineView)

        // Status (loading / empty / no results).
        statusView = TextView(this).apply {
            setTextColor(0xFF8FA0AF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
        }
        root.addView(statusView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // List.
        contentList = RecyclerView(this).apply { setBackgroundColor(0x00000000) }
        root.addView(contentList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { setMargins(0, (4 * dp).toInt(), 0, 0) })
    }

    private fun wireChips() {
        listOf(
            Filter.ALL to R.string.plugin_filter_all,
            Filter.INSTALLED to R.string.plugin_filter_installed,
            Filter.UPDATES to R.string.plugin_filter_updates,
            Filter.COMPATIBLE to R.string.plugin_filter_compatible,
            Filter.APP_UPDATE to R.string.plugin_filter_app_update,
            Filter.INCOMPATIBLE to R.string.plugin_filter_incompatible,
            Filter.BLOCKED to R.string.plugin_filter_blocked
        ).forEach { (filterId, label) ->
            val chip = TextView(this).apply {
                text = getString(label)
                textSize = 13f
                setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins((4 * dp).toInt(), 0, (4 * dp).toInt(), 0) }
                setOnClickListener { selectFilter(filterId) }
            }
            chipViews[filterId] = chip
            chipRow.addView(chip)
        }
        selectFilter(Filter.ALL)
    }

    private fun selectFilter(newFilter: Filter) {
        filter = newFilter
        chipViews.forEach { (filterId, chip) ->
            val selected = filterId == filter
            chip.background = GradientDrawable().apply {
                cornerRadius = 14f * dp
                setColor(if (selected) 0xFF24313D.toInt() else 0x0015171B)
                setStroke(
                    (1 * dp).toInt(),
                    if (selected) 0xFF3A4A59.toInt() else 0x002F3946
                )
            }
            chip.setTextColor(if (selected) 0xFF7CE8B8.toInt() else 0xFF8FA0AF.toInt())
        }
        applyFilter()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun pluginManager(): NoxsPluginManager? = runCatching {
        val app = application as com.crossberry.noxs.NoxsApplication
        val paths = app.environments.activePaths()
        NoxsPluginManager(
            rootfsHome = paths.rootfsHomeNoxs,
            cacheDir = app.cacheDir,
            appVersion = appVersion(),
            guestBinDir = java.io.File(paths.rootfs, "usr/local/bin"),
            cloudCatalog = noxsPluginCloudCatalog(app, paths.rootfsHomeNoxs)
        )
    }.onFailure {
        com.crossberry.noxs.shared.NoxsLog.w("PluginStore", "manager unavailable: ${it.javaClass.simpleName}")
    }.getOrNull()

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "0.0.0"
}

/**
 * Store list adapter. Each card shows exactly what the registry entry says:
 * logo (fetched from logoUrl), name, badges (New / Update), description,
 * version + SDK + category, the compatibility line and a state-aware action
 * button (hidden when the plugin cannot run on this Noxs release).
 */
class PluginCardAdapter(
    private val onCardClick: (NoxsPluginManager.StoreCard) -> Unit,
    private val onActionClick: (NoxsPluginManager.StoreCard) -> Unit
) : RecyclerView.Adapter<PluginCardAdapter.Holder>() {

    private var items: List<NoxsPluginManager.StoreCard> = emptyList()
    private var installed: Map<String, InstalledPlugin> = emptyMap()
    private var busy: Set<String> = emptySet()
    private var seenIds: Set<String> = emptySet()
    private val logoCache = ConcurrentHashMap<String, android.graphics.Bitmap>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row)

    /** Ids already seen by the user — everything else shows a New badge. */
    fun setSeen(seen: Set<String>) {
        seenIds = seen
        notifyDataSetChanged()
    }

    fun markSeen(id: String) {
        if (id in seenIds) return
        seenIds = seenIds + id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val dp = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 1f, parent.resources.displayMetrics
        )
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12f * dp
                setColor(0xFF121922.toInt())
                setStroke((1 * dp).toInt(), 0xFF22303C.toInt())
            }
        }
        return Holder(row)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = items[position]
        val context = holder.row.context
        val dp = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 1f, context.resources.displayMetrics
        )
        val entry = card.entry
        holder.row.setOnClickListener { onCardClick(card) }

        holder.row.removeAllViews()

        // Logo: cached bitmap, fetched on demand, initial-letter fallback.
        val logo = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams((46 * dp).toInt(), (46 * dp).toInt()).apply {
                marginEnd = (12 * dp).toInt()
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                cornerRadius = 10f * dp
                setColor(0xFF0E141B.toInt())
            }
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
        }
        holder.row.addView(logo)
        bindLogo(logo, entry)

        // Name + badges / description / version + SDK + category / compat.
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Name row with New / Update badges.
        val nameRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameRow.addView(TextView(context).apply {
            text = entry.name
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 15f
        })
        val newBadgeState = card.compat.state == PluginCompatState.COMPATIBLE ||
            card.compat.state == PluginCompatState.PLUGIN_UPDATE_AVAILABLE ||
            card.compat.state == PluginCompatState.SDK_MISSING
        if (newBadgeState && !seenIds.contains(entry.id)) {
            nameRow.addView(badge(context, dp, context.getString(R.string.plugin_badge_new), 0xFF1F4D38.toInt(), 0xFF7CE8B8.toInt()))
        }
        if (card.updateAvailable) {
            nameRow.addView(badge(context, dp, context.getString(R.string.plugin_badge_update), 0xFF4D3A1F.toInt(), 0xFFF0C674.toInt()))
        }
        textColumn.addView(nameRow)

        // Cloud plugins are listed by owner + plugin name (spec: username /
        // plugin name); official registry entries carry author only when the
        // registry upsert declares one.
        entry.author?.takeIf { it.isNotBlank() }?.let { author ->
            textColumn.addView(TextView(context).apply {
                text = context.getString(R.string.plugin_card_by, author)
                setTextColor(0xFF6B7A88.toInt())
                textSize = 11f
            })
        }

        // Cloud cards carry no description until the details page parses the
        // artifact — hide the empty line instead of a blank row.
        if (entry.description.isNotBlank()) {
            textColumn.addView(TextView(context).apply {
                text = entry.description
                setTextColor(0xFF8FA0AF.toInt())
                textSize = 12f
                maxLines = 2
            })
        }
        val sdkLabel = entry.sdkVersion ?: "0.0.1"
        textColumn.addView(TextView(context).apply {
            text = "v${entry.version}" +
                context.getString(R.string.plugin_sdk_line, sdkLabel) +
                (entry.category?.let { "  •  $it" } ?: "")
            setTextColor(0xFF6B7A88.toInt())
            textSize = 11f
        })

        // Compatibility line — colored, with the reason when present.
        val compat = card.compat
        textColumn.addView(TextView(context).apply {
            text = compatText(context, card)
            setTextColor(
                when (compat.state) {
                    PluginCompatState.COMPATIBLE -> 0xFF7CE8B8.toInt()
                    PluginCompatState.PLUGIN_UPDATE_AVAILABLE -> 0xFFF0C674.toInt()
                    PluginCompatState.SDK_MISSING -> 0xFF8AB6E8.toInt()
                    PluginCompatState.APP_UPDATE_REQUIRED,
                    PluginCompatState.SDK_INCOMPATIBLE,
                    PluginCompatState.BLOCKED,
                    PluginCompatState.ERROR -> 0xFFF28B82.toInt()
                }
            )
            textSize = 11f
            maxLines = 2
        })
        holder.row.addView(textColumn)

        // Action button — hidden when this Noxs release cannot run the plugin.
        val actionBlocked = card.compat.state == PluginCompatState.APP_UPDATE_REQUIRED ||
            card.compat.state == PluginCompatState.SDK_INCOMPATIBLE ||
            card.compat.state == PluginCompatState.BLOCKED
        if (actionBlocked) {
            holder.row.addView(TextView(context).apply {
                text = context.getString(R.string.plugin_action_blocked)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
                setTextColor(0xFFF28B82.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (8 * dp).toInt() }
                // The gate can only be cleared by running a newer Noxs — send the
                // user to the release page instead of being a dead label.
                setOnClickListener {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(PluginStoreActivity.RELEASES_URL))
                        )
                    }.onFailure {
                        Toast.makeText(context, R.string.plugin_action_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            })
            return
        }
        val actionLabel = when {
            busy.contains(entry.id) -> context.getString(R.string.plugin_action_working)
            card.installed == null -> context.getString(R.string.plugin_action_install)
            card.updateAvailable -> context.getString(R.string.plugin_action_update)
            !card.installed.enabled -> context.getString(R.string.plugin_action_enable)
            else -> context.getString(R.string.plugin_action_open)
        }
        holder.row.addView(TextView(context).apply {
            text = actionLabel
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 10f * dp
                setColor(
                    when {
                        busy.contains(entry.id) -> 0xFF1A222C.toInt()
                        card.installed == null -> 0xFF1F4D38.toInt()
                        card.updateAvailable -> 0xFF4D3A1F.toInt()
                        else -> 0xFF1A222C.toInt()
                    }
                )
            }
            setTextColor(
                when {
                    card.updateAvailable -> 0xFFF0C674.toInt()
                    card.installed == null -> 0xFF7CE8B8.toInt()
                    else -> 0xFF9AA7B4.toInt()
                }
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * dp).toInt() }
            setOnClickListener { onActionClick(card) }
        })
    }

    private fun compatText(context: android.content.Context, card: NoxsPluginManager.StoreCard): String {
        val label = when (card.compat.state) {
            PluginCompatState.COMPATIBLE -> context.getString(R.string.plugin_compat_compatible)
            PluginCompatState.SDK_MISSING -> context.getString(R.string.plugin_compat_sdk_missing)
            PluginCompatState.SDK_INCOMPATIBLE -> context.getString(R.string.plugin_compat_sdk_incompatible)
            PluginCompatState.APP_UPDATE_REQUIRED -> context.getString(R.string.plugin_compat_app_update_required)
            PluginCompatState.PLUGIN_UPDATE_AVAILABLE -> context.getString(R.string.plugin_compat_update_available)
            PluginCompatState.BLOCKED -> context.getString(R.string.plugin_compat_blocked)
            PluginCompatState.ERROR -> context.getString(R.string.plugin_compat_error)
        }
        return card.compat.reason?.let { "$label — $it" } ?: label
    }

    private fun badge(
        context: android.content.Context,
        dp: Float,
        text: String,
        bgColor: Int,
        foreground: Int
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = 9f
        setTextColor(foreground)
        gravity = Gravity.CENTER
        setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 8f * dp
            setColor(bgColor)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = (6 * dp).toInt()
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    private fun bindLogo(view: ImageView, entry: RegistryEntry) {
        val url = entry.logoUrl ?: return
        logoCache[url]?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageResource(android.R.drawable.ic_menu_manage)
        if (!inFlight.add(url)) return
        Thread {
            val bitmap = runCatching {
                val bytes = com.crossberry.noxs.runtime.plugins.PluginHttp.get(
                    url, 10_000, 1L * 1024L * 1024L
                )
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            if (bitmap != null) logoCache[url] = bitmap
            inFlight.remove(url)
            view.post {
                logoCache[url]?.let { view.setImageBitmap(it) }
            }
        }.start()
    }

    fun submit(
        items: List<NoxsPluginManager.StoreCard>,
        installed: Map<String, InstalledPlugin>,
        busy: Set<String>
    ) {
        this.items = items
        this.installed = installed
        this.busy = busy
        notifyDataSetChanged()
    }
}
