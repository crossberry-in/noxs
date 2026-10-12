/*
 * Noxs — original implementation.
 * Pure-JVM support logic for the code-server (VS Code Server) editor:
 * config parsing, machine-status parsing, port selection, workspace
 * resolution and the WebView navigation policy. No Android imports —
 * fully covered by CodeServerSupportTest.
 */
package com.crossberry.noxs.runtime

import java.net.URI

object CodeServerSupport {

    // ------------------------------------------------------------ config

    /** The subset of the code-server config.yaml that Noxs cares about. */
    data class ServerConfig(
        val address: String,
        val port: Int,
        val auth: String?,
        val password: String?
    )

    /**
     * Parses the code-server config.yaml Noxs generates (flat key: value
     * lines). Returns null when no usable bind-addr is present — the caller
     * treats that as "not configured". The password field is needed only to
     * authenticate the editor WebView; it is never logged.
     */
    fun parseConfig(yaml: String?): ServerConfig? {
        if (yaml.isNullOrBlank()) return null
        var address: String? = null
        var auth: String? = null
        var password: String? = null
        for (rawLine in yaml.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            when (key) {
                "bind-addr" -> address = value
                "auth" -> auth = value.takeIf { it.isNotEmpty() }
                "password" -> password = value.takeIf { it.isNotEmpty() }
            }
        }
        val addr = address ?: return null
        val port = addr.substringAfterLast(':', "").toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return ServerConfig(address = addr, port = port, auth = auth, password = password)
    }

    // ------------------------------------------------------- status json

    data class Status(
        val installed: Boolean,
        val version: String?,
        val running: Boolean,
        val pid: Long?,
        val port: Int?,
        val httpReady: Boolean
    )

    /**
     * Extracts a flat-JSON value ("true"/"false", number, null or a quoted
     * string) for [key]. Only used on machine output produced by
     * `noxs code status --json`; deliberately no JSON library dependency.
     */
    fun flatJsonGet(json: String, key: String): String? {
        val keyIdx = findJsonKey(json, key) ?: return null
        var i = keyIdx + key.length + 2   // skip "key"
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != ':') return null
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length) return null
        return when {
            json[i] == '"' -> {
                val sb = StringBuilder()
                i++
                while (i < json.length) {
                    val c = json[i]
                    when {
                        c == '\\' && i + 1 < json.length -> { sb.append(json[i + 1]); i += 2 }
                        c == '"' -> return sb.toString()
                        else -> { sb.append(c); i++ }
                    }
                }
                sb.toString()
            }
            else -> {
                val start = i
                while (i < json.length && json[i] != ',' && json[i] != '}') i++
                json.substring(start, i).trim().ifEmpty { null }
            }
        }
    }

    private fun findJsonKey(json: String, key: String): Int? {
        val token = "\"$key\""
        var from = 0
        while (true) {
            val idx = json.indexOf(token, from)
            if (idx < 0) return null
            // must be followed (after whitespace) by ':' to be a key, not a value
            var j = idx + token.length
            while (j < json.length && json[j].isWhitespace()) j++
            if (j < json.length && json[j] == ':') return idx
            from = idx + 1
        }
    }

    /** Parses `noxs code status --json` output; returns null on any surprise. */
    fun parseStatusJson(text: String?): Status? {
        if (text.isNullOrBlank()) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = text.substring(start, end + 1)
        val bool = { k: String ->
            when (flatJsonGet(json, k)) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
        val numLong = { k: String ->
            flatJsonGet(json, k)?.let { if (it == "null") null else it.toLongOrNull() }
        }
        val port = flatJsonGet(json, "port")?.let { if (it == "null") null else it.toIntOrNull() }
        val version = flatJsonGet(json, "version")?.takeIf { it.isNotEmpty() }
        return Status(
            installed = bool("installed") ?: return null,
            version = version,
            running = bool("running") ?: return null,
            pid = numLong("pid"),
            port = port,
            httpReady = bool("http_ready") ?: return null
        )
    }

    // --------------------------------------------------------- pid / proc

    /** True when a /proc/<pid>/cmdline payload belongs to our code-server. */
    fun looksLikeCodeServerCmdline(raw: ByteArray?): Boolean {
        if (raw == null || raw.isEmpty()) return false
        val text = String(raw).replace("\u0000", " ")
        return text.contains("code-server")
    }

    /** Strict PID-file parse: digits only, bounded length. */
    fun parsePidFile(text: String?): Long? {
        val t = text?.trim() ?: return null
        if (t.isEmpty() || t.length > 12) return null
        if (t.any { it < '0' || it > '9' }) return null
        return t.toLongOrNull()
    }

    // --------------------------------------------------------------- port

    /** Picks the first free port starting at [preferred]; null when exhausted. */
    fun choosePort(preferred: Int, isBusy: (Int) -> Boolean, maxAttempts: Int = 10): Int? {
        var port = preferred
        var attempts = 0
        while (attempts < maxAttempts) {
            if (!isBusy(port)) return port
            port++
            attempts++
        }
        return null
    }

    // -------------------------------------------------- navigation policy

    /**
     * Main-frame navigation policy for the editor WebView: only the local
     * code-server origin may be loaded. Everything else is blocked in the
     * editor (the caller may route safe external links to the Noxs web
     * window instead). VS Code's internal webview iframes do not pass
     * through here, so they keep working.
     */
    fun isAllowedEditorUrl(url: String?, serverPort: Int): Boolean {
        if (url.isNullOrBlank()) return false
        if (url == "about:blank") return true
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return false
        }
        if (uri.scheme != "http") return false
        val host = uri.host ?: return false
        if (host != "127.0.0.1" && host != "localhost") return false
        val port = if (uri.port == -1) 80 else uri.port
        return port == serverPort
    }

    /** True when the URL is code-server's login page (path /login). */
    fun isLoginPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return false
        }
        val path = uri.path ?: return false
        return path == "/login" || path == "/login/"
    }

    /**
     * JS injected into the login page. The password travels base64-encoded
     * so no escaping problem can leak or corrupt it; atob decodes it inside
     * the page. The script fills the password input and submits the form.
     * Only ever evaluated against the loopback code-server origin.
     */
    fun loginInjectScript(base64Password: String): String {
        val safe = base64Password.filter {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '='
        }
        return "(function(){" +
            "try{" +
            "var p=window.atob('" + safe + "');" +
            "var i=document.querySelector('input[type=password]');" +
            "if(i&&p){i.value=p;}" +
            "var f=(i&&i.form)?i.form:document.querySelector('form');" +
            "if(f&&typeof f.submit==='function'){f.submit();return 'submitted';}" +
            "return 'no-form';" +
            "}catch(e){return 'error';}})();"
    }

    // ---------------------------------------------------------- workspace

    /**
     * Workspace resolution policy (first match wins): requested extra →
     * active terminal session cwd (OSC 7) → session default home. A
     * candidate is only used when [isDirectory] accepts it; the function
     * never invents a path that was not verified through [isDirectory].
     */
    fun resolveWorkspace(
        requested: String?,
        sessionCwd: String?,
        fallbackHome: String?,
        isDirectory: (String) -> Boolean
    ): String? {
        val candidates = listOfNotNull(
            requested?.takeIf { it.isNotBlank() },
            sessionCwd?.takeIf { it.isNotBlank() },
            fallbackHome?.takeIf { it.isNotBlank() }
        )
        return candidates.firstOrNull(isDirectory)
    }

    /**
     * Defensive workspace validation for anything handed to the guest
     * (start --workspace, Open in Terminal): absolute guest path, bounded
     * length, no control characters, no traversal segments.
     */
    fun isValidGuestWorkspace(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        if (!path.startsWith("/")) return false
        if (path.length > 1024) return false
        if (path.any { it < ' ' || it == '\u007F' }) return false
        if (path.split('/').any { it == ".." }) return false
        return true
    }
}
