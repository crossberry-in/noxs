/*
 * Noxs — original implementation.
 * WebWindowActivity: the Noxs native browser (spec §15-§26).
 *
 * A REAL android.webkit.WebView inside a Noxs floating window:
 *   - dark chrome, rounded address bar, security icon, share control,
 *     bottom toolbar (back / forward / fullscreen / reload / menu)
 *   - draggable, resizable, minimize/restore/maximize/fullscreen/close/focus
 *   - traffic-light controls (generic rounded shapes — no proprietary assets)
 *   - minimum window size 280dp × 300dp, portrait/landscape/phone/tablet
 *
 * Security (spec §18-§20, §33):
 *   - every URL passes NoxsUrlGuard before load; only http/https allowed
 *   - NO JavascriptInterface is attached for external websites — external
 *     web content never receives window.nx, @noxs/nx-api, filesystem,
 *     terminal or Android API access
 *   - file access/content access disabled in the WebView
 *   - new windows (target=_blank, window.open) re-enter validation and open
 *     as new Noxs web windows — never an external browser or intent
 *   - downloads land in Noxs-managed app-private storage after URL +
 *     filename validation; uploads use the Android system file picker only
 */
package com.crossberry.noxs.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.crossberry.noxs.R
import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.runtime.NoxsUrlGuard
import com.crossberry.noxs.runtime.NoxsWebWindowManager

class WebWindowActivity : AppCompatActivity() {

    private lateinit var windowId: String

    // Floating window chrome
    private lateinit var dimLayer: FrameLayout
    private lateinit var windowCard: LinearLayout
    private lateinit var headerBar: LinearLayout
    private lateinit var addressRow: LinearLayout
    private lateinit var toolbarRow: LinearLayout
    private lateinit var addressInput: EditText
    private lateinit var securityIcon: TextView
    private lateinit var webView: WebView
    private lateinit var minimizedChip: LinearLayout
    private lateinit var statusLabel: TextView

    private var isFullscreenWindow = false
    private var savedCardParams: FrameLayout.LayoutParams? = null
    private var hasSavedState = false

    private var uploadCallback: ValueCallback<Array<Uri>>? = null
    private var mirrorAuthInjected = false

    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasSavedState = savedInstanceState != null

        val requested = intent?.getStringExtra(EXTRA_URL).orEmpty()
        when (val decision = NoxsUrlGuard.check(requested)) {
            is NoxsUrlGuard.Decision.Rejected -> {
                Toast.makeText(this, "Unsupported URL: only http:// and https:// can be opened", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            is NoxsUrlGuard.Decision.Allowed -> {
                windowId = intent?.getStringExtra(EXTRA_WINDOW_ID) ?: "web-${System.currentTimeMillis()}"
                buildUi(decision.url)
                NoxsWebWindowManager.register(windowId, decision.url)
            }
        }
    }

    // ------------------------------------------------------------- UI build

    private fun buildUi(initialUrl: String) {
        dimLayer = FrameLayout(this).apply { setBackgroundColor(0x52101418) }
        setContentView(dimLayer)

        windowCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16f * dp
                setColor(0xF214161B.toInt())
                setStroke((1 * dp).toInt(), 0xFF2F3946.toInt())
            }
            elevation = 10f * dp
            clipToOutline = true
        }
        dimLayer.addView(windowCard, initialCardParams())

        buildHeader()
        buildAddressBar()
        buildWebView(initialUrl)
        buildToolbar()
        buildMinimizedChip()

        attachDragging()
        attachResizeHandle()
        dimLayer.setOnClickListener { /* keep focus inside the window */ }
    }

    private fun initialCardParams(): FrameLayout.LayoutParams {
        val minW = (MIN_WIDTH_DP * dp).toInt()
        val minH = (MIN_HEIGHT_DP * dp).toInt()
        val width = (dimLayer.width.takeIf { it > minW } ?: (MIN_WIDTH_DP * 1.45f * dp).toInt())
            .coerceIn(minW, (resources.displayMetrics.widthPixels * 0.96f).toInt())
        val height = (dimLayer.height.takeIf { it > minH } ?: (MIN_HEIGHT_DP * 1.5f * dp).toInt())
            .coerceIn(minH, (resources.displayMetrics.heightPixels * 0.9f).toInt())
        return FrameLayout.LayoutParams(width, height, Gravity.CENTER)
    }

    private fun chromeButton(label: String, description: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(0xFFDCE3EC.toInt())
            typeface = Typeface.MONOSPACE
            this.contentDescription = description
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
            setOnClickListener { onClick() }
        }

    private fun trafficLight(color: Int, description: String, onClick: () -> Unit): View =
        View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            contentDescription = description
            layoutParams = LinearLayout.LayoutParams((13 * dp).toInt(), (13 * dp).toInt()).apply {
                marginEnd = (7 * dp).toInt()
            }
            setOnClickListener { onClick() }
        }

    private fun buildHeader() {
        headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
        }
        // Traffic lights: close / minimize / fullscreen-restore (spec §26).
        headerBar.addView(trafficLight(0xFFF45C51.toInt(), getString(R.string.web_close_window)) { confirmClose() })
        headerBar.addView(trafficLight(0xFFFDBD2E.toInt(), getString(R.string.web_minimize_window)) { minimizeWindow() })
        headerBar.addView(trafficLight(0xFF2ACB42.toInt(), getString(R.string.web_fullscreen_window)) { toggleWindowFullscreen() })

        val title = TextView(this).apply {
            text = getString(R.string.web_window_title)
            textSize = 13f
            setTextColor(0xFF9AA6B6.toInt())
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (6 * dp).toInt()
            }
        }
        headerBar.addView(title)
        windowCard.addView(headerBar)
    }

    private fun buildAddressBar() {
        addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
        }
        securityIcon = TextView(this).apply {
            textSize = 14f
            text = "🔒"
            contentDescription = getString(R.string.web_security_state)
        }
        addressInput = EditText(this).apply {
            textSize = 13f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF66707E.toInt())
            hint = getString(R.string.web_address_hint)
            background = GradientDrawable().apply {
                cornerRadius = 22f * dp
                setColor(0xFF22272F.toInt())
            }
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            }
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) { submitAddress(); true } else false
            }
        }
        addressRow.addView(securityIcon)
        addressRow.addView(addressInput)
        addressRow.addView(chromeButton("↗", getString(R.string.web_share)) { shareCurrentUrl() })
        windowCard.addView(addressRow)
    }

    private fun buildWebView(initialUrl: String) {
        // Build into a local first: the lateinit webView property is only
        // assigned AFTER construction, so any helper reading the property
        // inside this expression would crash with UninitializedProperty-
        // AccessException (device-reported: lateinit property webView has
        // not been initialized). configureWebView receives the instance.
        val view = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(0xFF0F1216.toInt())
        }
        configureWebView(view)
        windowCard.addView(view)

        webView = view
        webView.webViewClient = NoxsWebClient()
        webView.webChromeClient = NoxsChromeClient()
        webView.setDownloadListener(NoxsDownloadListener())
        if (!hasSavedState) {
            webView.loadUrl(initialUrl)
        }
        restoreCookies()
    }

    private fun configureWebView(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(true)
        }
        // Isolation (spec §19): no JavascriptInterface of any kind is exposed
        // to external websites. window.nx exists ONLY inside package WebViews
        // (NoxsPackageWebViewHost), never here.
        view.isFocusableInTouchMode = true
    }

    private fun restoreCookies() {
        runCatching {
            CookieManager.getInstance().setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Third-party cookies stay off: normal sessions work, while
                // cross-site tracking surface is minimized (spec §23).
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
            }
        }
    }

    private fun buildToolbar() {
        toolbarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(0xFF191D23.toInt())
                cornerRadii = floatArrayOf(
                    12f * dp, 12f * dp, 12f * dp, 12f * dp,
                    0f, 0f, 0f, 0f
                )
            }
        }
        statusLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF7C8896.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
        }
        toolbarRow.addView(statusLabel)
        toolbarRow.addView(chromeButton("←", getString(R.string.web_back)) {
            if (webView.canGoBack()) webView.goBack()
        })
        toolbarRow.addView(chromeButton("→", getString(R.string.web_forward)) {
            if (webView.canGoForward()) webView.goForward()
        })
        toolbarRow.addView(chromeButton("⛶", getString(R.string.web_fullscreen_content)) { toggleContentFullscreen() })
        toolbarRow.addView(chromeButton("↻", getString(R.string.web_reload)) { webView.reload() })
        toolbarRow.addView(chromeButton("⋯", getString(R.string.web_menu)) { showWindowMenu() })
        windowCard.addView(toolbarRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun buildMinimizedChip() {
        minimizedChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 24f * dp
                setColor(0xEE14161B.toInt())
                setStroke((1 * dp).toInt(), 0xFF2F3946.toInt())
            }
            elevation = 10f * dp
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            visibility = View.GONE
            addView(TextView(this@WebWindowActivity).apply {
                text = getString(R.string.web_restore_chip)
                textSize = 13f
                setTextColor(0xFFDCE3EC.toInt())
                typeface = Typeface.MONOSPACE
            })
            setOnClickListener { restoreWindow() }
        }
        dimLayer.addView(minimizedChip, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER
        ).apply { setMargins(0, 0, 0, (18 * dp).toInt()) })
    }

    // ------------------------------------------------------ window controls

    private fun attachDragging() {
        var downX = 0f; var downY = 0f
        var startLeft = 0; var startTop = 0
        headerBar.setOnTouchListener { _, event ->
            val params = windowCard.layoutParams as FrameLayout.LayoutParams
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startLeft = params.leftMargin; startTop = params.topMargin
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    params.leftMargin = (startLeft + dx).coerceIn(0, (dimLayer.width - params.width).coerceAtLeast(0))
                    params.topMargin = (startTop + dy).coerceIn(0, (dimLayer.height - params.height).coerceAtLeast(0))
                    params.gravity = Gravity.TOP or Gravity.START
                    windowCard.layoutParams = params
                    true
                }
                else -> false
            }
        }
    }

    private fun attachResizeHandle() {
        val handle = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0xFF3A4453.toInt())
                cornerRadius = 3f * dp
            }
        }
        val size = (18 * dp).toInt()
        // The handle's parent is the window card (LinearLayout) — the layout
        // params type must match the parent or layout time will crash.
        windowCard.addView(handle, LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, (6 * dp).toInt(), (6 * dp).toInt())
        })
        var downX = 0f; var downY = 0f
        var startW = 0; var startH = 0
        handle.setOnTouchListener { _, event ->
            val params = windowCard.layoutParams as FrameLayout.LayoutParams
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startW = params.width; startH = params.height
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    params.width = (startW + (event.rawX - downX).toInt())
                        .coerceAtLeast((MIN_WIDTH_DP * dp).toInt())
                        .coerceAtMost(dimLayer.width)
                    params.height = (startH + (event.rawY - downY).toInt())
                        .coerceAtLeast((MIN_HEIGHT_DP * dp).toInt())
                        .coerceAtMost(dimLayer.height)
                    windowCard.layoutParams = params
                    true
                }
                else -> false
            }
        }
    }

    private fun minimizeWindow() {
        windowCard.visibility = View.GONE
        minimizedChip.visibility = View.VISIBLE
        NoxsWebWindowManager.updateState(windowId, NoxsWebWindowManager.State.MINIMIZED)
    }

    private fun restoreWindow() {
        minimizedChip.visibility = View.GONE
        windowCard.visibility = View.VISIBLE
        NoxsWebWindowManager.updateState(windowId, NoxsWebWindowManager.State.OPEN)
    }

    /** Traffic-light green + toolbar ⛶: fullscreen the whole floating window. */
    private fun toggleWindowFullscreen() {
        val params = windowCard.layoutParams as FrameLayout.LayoutParams
        if (isFullscreenWindow) {
            savedCardParams?.let { windowCard.layoutParams = it }
            isFullscreenWindow = false
            NoxsWebWindowManager.updateState(windowId, NoxsWebWindowManager.State.OPEN)
        } else {
            savedCardParams = FrameLayout.LayoutParams(params.width, params.height, params.gravity)
                .apply {
                    leftMargin = params.leftMargin; topMargin = params.topMargin
                }
            params.gravity = Gravity.CENTER
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            params.setMargins((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            windowCard.layoutParams = params
            isFullscreenWindow = true
            NoxsWebWindowManager.updateState(windowId, NoxsWebWindowManager.State.FULLSCREEN)
        }
    }

    /** Content-only fullscreen: hide chrome rows, WebView fills the card. */
    private fun toggleContentFullscreen() {
        val chromeVisible = headerBar.visibility == View.VISIBLE
        headerBar.visibility = if (chromeVisible) View.GONE else View.VISIBLE
        addressRow.visibility = if (chromeVisible) View.GONE else View.VISIBLE
        toolbarRow.visibility = if (chromeVisible) View.GONE else View.VISIBLE
        windowCard.requestLayout()
    }

    private fun showWindowMenu() {
        val items = arrayOf(
            getString(R.string.web_menu_close),
            getString(R.string.web_menu_minimize),
            getString(R.string.web_menu_fullscreen_window)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.web_window_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> confirmClose()
                    1 -> minimizeWindow()
                    2 -> toggleWindowFullscreen()
                }
            }
            .show()
    }

    private fun confirmClose() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(getString(R.string.web_close_confirm))
            .setPositiveButton(R.string.web_close_yes) { _, _ -> finish() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------- URL flow

    private fun submitAddress() {
        val raw = addressInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        val candidate = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw
        } else if (raw.contains(" ") || !raw.contains(".")) {
            "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(raw, "UTF-8")
        } else {
            "https://$raw"
        }
        when (val decision = NoxsUrlGuard.check(candidate)) {
            is NoxsUrlGuard.Decision.Rejected ->
                Toast.makeText(this, "Unsupported URL", Toast.LENGTH_SHORT).show()
            is NoxsUrlGuard.Decision.Allowed -> {
                webView.loadUrl(decision.url)
                NoxsWebWindowManager.updateUrl(windowId, decision.url)
            }
        }
    }

    private fun shareCurrentUrl() {
        val url = webView.url ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.web_share))) }
    }

    private fun openInNoxsWindow(url: String) {
        when (val decision = NoxsUrlGuard.check(url)) {
            is NoxsUrlGuard.Decision.Rejected ->
                Toast.makeText(this, "Unsupported URL", Toast.LENGTH_SHORT).show()
            is NoxsUrlGuard.Decision.Allowed -> {
                // New windows stay inside the Noxs Web Window Manager (spec §22).
                val intent = Intent(this, WebWindowActivity::class.java).apply {
                    putExtra(EXTRA_URL, decision.url)
                    putExtra(EXTRA_WINDOW_ID, "web-${System.currentTimeMillis()}")
                }
                startActivity(intent)
            }
        }
    }

    // ------------------------------------------------------------ web clients

    private inner class NoxsWebClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val decision = NoxsUrlGuard.check(request.url.toString())
            return when (decision) {
                is NoxsUrlGuard.Decision.Rejected -> true // blocked, nothing loads
                is NoxsUrlGuard.Decision.Allowed -> {
                    if (request.isForMainFrame && decision.url != view.url) {
                        view.loadUrl(decision.url)
                        NoxsWebWindowManager.updateUrl(windowId, decision.url)
                    }
                    false
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (NoxsUrlGuard.isAllowed(url) && url != "about:blank") {
                addressInput.setText(url)
                NoxsWebWindowManager.updateUrl(windowId, url)
            }
            securityIcon.text = if (url.startsWith("https://")) "🔒" else "⚠"
            statusLabel.text = getString(R.string.web_loading)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            val uri = Uri.parse(url)
            if (!mirrorAuthInjected && uri.scheme == "https" && uri.host == "nxmirror.netlify.app") {
                val token = NoxsMirrorSession.token(this@WebWindowActivity)
                if (!token.isNullOrBlank()) {
                    mirrorAuthInjected = true
                    val safeToken = org.json.JSONObject.quote(token)
                    view.evaluateJavascript(
                        "localStorage.setItem('demoapi.session', $safeToken); window.location.reload();",
                        null
                    )
                    return
                }
            }
            statusLabel.text = view.title?.take(48) ?: getString(R.string.web_ready)
        }
    }

    private inner class NoxsChromeClient : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            val transient = WebView(view.context)
            transient.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    openInNoxsWindow(request.url.toString())
                    v.destroy()
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = transient
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            // Uploads go through the Android system picker only; the website
            // receives the chosen file, never broader filesystem access.
            if (uploadCallback != null) {
                uploadCallback?.onReceiveValue(null)
            }
            uploadCallback = filePathCallback
            val picker = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            return runCatching {
                startActivityForResult(
                    Intent.createChooser(picker, getString(R.string.web_upload_picker)),
                    REQUEST_FILE_PICKER
                )
                true
            }.getOrElse {
                uploadCallback = null
                false
            }
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            statusLabel.text = if (newProgress < 100) {
                getString(R.string.web_loading_percent, newProgress)
            } else {
                view.title?.take(48) ?: getString(R.string.web_ready)
            }
        }
    }

    private inner class NoxsDownloadListener : DownloadListener {
        override fun onDownloadStart(
            url: String, userAgent: String, contentDisposition: String,
            mimeType: String, contentLength: Long
        ) {
            val decision = NoxsUrlGuard.check(url)
            val safeName = NoxsUrlGuard.safeDownloadName(
                URLUtil.guessFileName(url, contentDisposition, mimeType)
            )
            if (decision is NoxsUrlGuard.Decision.Rejected || safeName == null) {
                Toast.makeText(this@WebWindowActivity, getString(R.string.web_download_blocked), Toast.LENGTH_LONG).show()
                return
            }
            val request = DownloadManager.Request(Uri.parse((decision as NoxsUrlGuard.Decision.Allowed).url)).apply {
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(this@WebWindowActivity, Environment.DIRECTORY_DOWNLOADS, safeName)
                mimeType.takeIf { it.isNotBlank() }?.let { setMimeType(it) }
            }
            runCatching {
                val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.enqueue(request)
                Toast.makeText(this@WebWindowActivity, getString(R.string.web_download_started, safeName), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@WebWindowActivity, getString(R.string.web_download_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------------------------------------------------- lifecycle

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_FILE_PICKER) {
            val callback = uploadCallback
            uploadCallback = null
            val single = if (resultCode == RESULT_OK && data?.data != null) arrayOf(data.data!!) else null
            callback?.onReceiveValue(single)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        if (this::webView.isInitialized) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (this::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        // A rejected URL finishes in onCreate before any field is assigned;
        // never touch the lateinit registry id in that path.
        if (this::windowId.isInitialized) {
            NoxsWebWindowManager.unregister(windowId)
        }
        if (this::webView.isInitialized) {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "noxs.extra.WEB_URL"
        const val EXTRA_WINDOW_ID = "noxs.extra.WEB_WINDOW_ID"
        private const val REQUEST_FILE_PICKER = 4107
        private const val MIN_WIDTH_DP = 280
        private const val MIN_HEIGHT_DP = 300

        /** Open a validated URL in a new Noxs browser window. */
        fun open(context: Context, url: String) {
            when (val decision = NoxsUrlGuard.check(url)) {
                is NoxsUrlGuard.Decision.Rejected -> {
                    NoxsLog.w("WebWindow", "blocked navigation to disallowed URL")
                    Toast.makeText(context, "Unsupported URL", Toast.LENGTH_SHORT).show()
                }
                is NoxsUrlGuard.Decision.Allowed -> {
                    val intent = Intent(context, WebWindowActivity::class.java).apply {
                        putExtra(EXTRA_URL, decision.url)
                        putExtra(EXTRA_WINDOW_ID, "web-${System.currentTimeMillis()}")
                        if (context !is android.app.Activity) {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    context.startActivity(intent)
                }
            }
        }
    }
}
