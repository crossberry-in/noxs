/*
 * Noxs — original implementation.
 * NoxsPluginRegistry: fetches and caches the Plugin Store registry.json and
 * implements the shared search used by BOTH the store UI and the
 * `nx plug search` CLI (single source of search logic).
 *
 * Fetching is isolated behind [Fetcher] so JVM tests cover search, caching
 * and rejection of broken payloads without any network. Only HTTPS URLs
 * are fetched; the cache is adopted atomically (tmp + rename).
 */
package com.crossberry.noxs.runtime.plugins

import com.crossberry.noxs.shared.NoxsLog
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class NoxsPluginRegistry(
    private val cacheDir: File,
    private val fetcher: NoxsPluginRegistry.Fetcher = HttpsFetcher(),
    private val minRefreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS
) {

    /** Download boundary — tests inject a fake. */
    fun interface Fetcher {
        fun get(url: String, timeoutMs: Int): ByteArray
    }

    class RegistryException(message: String) : Exception(message)

    private val cacheFile: File = File(cacheDir, "registry.json")
    private val fetchStamp: File = File(cacheDir, ".registry-last-fetch")
    private val lock = Any()

    var lastRefreshOk: Boolean = false
        private set

    /** Registry entries: fresh when possible, last-good cache otherwise.
    *
    * Refreshes are throttled to one network fetch per [minRefreshIntervalMs]
    * (configurable) so opening the store never hammers the registry; [force]
    * (the store's manual refresh) bypasses the throttle. When the network is
    * unavailable the cached registry is returned and [lastRefreshOk] is
    * false — the store shows the offline state. */
    fun entries(refresh: Boolean = true, force: Boolean = false): List<RegistryEntry> {
        synchronized(lock) {
            if (refresh) {
                val throttled = !force &&
                    runCatching { fetchStamp.lastModified() }
                        .getOrDefault(0L) > System.currentTimeMillis() - minRefreshIntervalMs
                if (!throttled) {
                    runCatching { refreshLocked() }
                        .onFailure {
                            NoxsLog.w("PluginRegistry", "registry refresh failed: ${it.javaClass.simpleName}")
                        }
                } else {
                    lastRefreshOk = true // served from the fresh-enough cache
                }
            }
            if (cacheFile.isFile) {
                return runCatching {
                    val (format, plugins) = PluginJson.parseRegistry(cacheFile.readText(Charsets.UTF_8))
                    if (format != PluginJson.REGISTRY_FORMAT) throw RegistryException("registry format $format")
                    plugins
                }.getOrElse { emptyList() }
            }
            return emptyList()
        }
    }

    fun entry(id: String): RegistryEntry? = entries(refresh = false).firstOrNull { it.id == id }

    /** Adopts registry text into the cache (atomic, validates first). */
    @Synchronized
    fun adopt(text: String) {
        val (format, _) = PluginJson.parseRegistry(text)
        require(format == PluginJson.REGISTRY_FORMAT) { "unsupported registry format $format" }
        cacheDir.mkdirs()
        val tmp = File(cacheDir, ".registry.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(cacheFile)) cacheFile.writeText(text, Charsets.UTF_8)
    }

    private fun refreshLocked() {
        val bytes = fetcher.get(REGISTRY_URL, FETCH_TIMEOUT_MS)
        if (bytes.isEmpty() || bytes.size > MAX_REGISTRY_BYTES) throw RegistryException("registry payload invalid")
        val text = String(bytes, Charsets.UTF_8)
        PluginJson.parseRegistry(text) // reject broken payloads before caching
        adopt(text)
        cacheDir.mkdirs()
        fetchStamp.writeText(System.currentTimeMillis().toString(), Charsets.UTF_8)
        lastRefreshOk = true
    }

    // -------------------------------------------------------------- search

    /** The shared store search (name, id, description, category, keywords). */
    fun search(entries: List<RegistryEntry>, query: String): List<RegistryEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries
        return entries.filter { entry ->
            entry.id.lowercase().contains(q) ||
                entry.name.lowercase().contains(q) ||
                entry.description.lowercase().contains(q) ||
                (entry.category?.lowercase()?.contains(q) ?: false) ||
                entry.keywords.any { it.lowercase().contains(q) }
        }
    }

    fun search(query: String): List<RegistryEntry> = search(entries(refresh = false), query)

    /** Categories present in the registry, sorted (store category filter). */
    fun categories(entries: List<RegistryEntry>): List<String> =
        entries.mapNotNull { it.category?.takeIf { c -> c.isNotBlank() } }
            .distinct().sorted()

    companion object {
        const val REGISTRY_URL =
            "https://raw.githubusercontent.com/web12-app/noxs-plugins/main/registry.json"
        const val FETCH_TIMEOUT_MS = 15_000
        const val MAX_REGISTRY_BYTES = 2L * 1024L * 1024L
        const val DEFAULT_REFRESH_INTERVAL_MS = 5L * 60L * 1000L
    }
}

/** HTTPS-only fetch helper used by the registry and the installer. */
object PluginHttp {

    fun get(url: String, timeoutMs: Int, maxBytes: Long, headers: Map<String, String> = emptyMap()): ByteArray {
        val normalized = url.trim()
        if (!normalized.startsWith("https://")) {
            throw IllegalArgumentException("only https:// URLs are fetched")
        }
        val connection = URL(normalized).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Noxs-PluginStore")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val bounded = BoundedSink(maxBytes)
            connection.inputStream.use { stream ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    bounded.write(chunk, 0, read)
                }
            }
            bounded.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private class BoundedSink(private val maxBytes: Long) {
        private val data = java.io.ByteArrayOutputStream()
        fun write(chunk: ByteArray, offset: Int, length: Int) {
            if (data.size() + length > maxBytes) throw IOException("download too large")
            data.write(chunk, offset, length)
        }
        fun toByteArray(): ByteArray = data.toByteArray()
    }
}

/** Default registry/artifact fetcher over HTTPS. */
class HttpsFetcher(
    private val timeoutMs: Int = NoxsPluginRegistry.FETCH_TIMEOUT_MS,
    private val maxBytes: Long = NoxsPluginRegistry.MAX_REGISTRY_BYTES
) : NoxsPluginRegistry.Fetcher {
    override fun get(url: String, timeoutMs: Int): ByteArray =
        PluginHttp.get(url, timeoutMs, maxBytes)
}

/** sha256 hex helper shared by installer and CLI responses. */
object PluginChecksum {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
