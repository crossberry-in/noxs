/*
 * Noxs — original implementation.
 * Bootstrap manifest model + parser. Uses a tiny internal JSON parser so the
 * security-relevant manifest logic is fully testable on the JVM without
 * Android stubs or external dependencies.
 *
 * Schema: packages/metadata/bootstrap.manifest.schema.json
 */
package com.crossberry.noxs.shared

data class BootstrapArtifact(
    val url: String,
    val sha256: String,
    val format: String = "",
    val sizeBytes: Long = 0
) {
    val isPinned: Boolean get() = sha256.isNotBlank()
}

data class BootstrapManifest(
    val arch: String,
    val rootfs: BootstrapArtifact,
    /**
     * proot is bundled in the APK (jniLibs → nativeLibraryDir) since Android
     * 10+ forbids exec from app data storage. The field is kept for future
     * use (e.g. upgrading via app-level downloads) but is optional now.
     */
    val proot: BootstrapArtifact? = null,
    val generatedAt: String = "",
    val source: String = "",
    /**
     * APK asset path of the bundled offline rootfs (manifest key
     * offline.bundledAsset), e.g. "bootstrap/arm64-v8a/rootfs.bin". Empty for
     * manifests generated without an offline bundle.
     */
    val offlineBundledAsset: String = ""
) {
    val isPinned: Boolean get() = rootfs.isPinned

    companion object {
        val SUPPORTED_ARCHS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

        fun parse(json: String): BootstrapManifest {
            val obj = try {
                MiniJson.parse(json) as? Map<*, *>
                    ?: throw IllegalArgumentException("Manifest root must be a JSON object")
            } catch (e: MiniJson.JsonException) {
                throw IllegalArgumentException("Invalid bootstrap manifest JSON: ${e.message}", e)
            }
            val schema = (obj["schema"] as? Number)?.toInt() ?: -1
            if (schema != 1) throw IllegalArgumentException("Unsupported manifest schema: $schema")
            val arch = obj["arch"] as? String ?: throw IllegalArgumentException("Missing arch")
            require(arch in SUPPORTED_ARCHS) { "Unsupported arch: $arch" }
            val rootfsObj = obj["rootfs"] as? Map<*, *> ?: throw IllegalArgumentException("Missing rootfs")
            val prootObj = obj["proot"] as? Map<*, *>
            val offlineObj = obj["offline"] as? Map<*, *>
            return BootstrapManifest(
                arch = arch,
                rootfs = artifact(rootfsObj, "rootfs"),
                proot = prootObj?.let { artifact(it, "proot") },
                generatedAt = obj["generatedAt"] as? String ?: "",
                source = obj["source"] as? String ?: "",
                offlineBundledAsset = offlineObj?.get("bundledAsset") as? String ?: ""
            )
        }

        private fun artifact(map: Map<*, *>, label: String): BootstrapArtifact {
            val url = map["url"] as? String ?: throw IllegalArgumentException("$label.url missing")
            require(url.startsWith("https://") || url.startsWith("file://")) {
                "$label.url must be https:// (or file:// for offline builds): $url"
            }
            return BootstrapArtifact(
                url = url,
                sha256 = (map["sha256"] as? String ?: "").trim().lowercase(),
                format = map["format"] as? String ?: "tar.xz",
                sizeBytes = (map["sizeBytes"] as? Number)?.toLong() ?: 0
            )
        }
    }
}

/**
 * Minimal JSON parser — objects, arrays, strings, numbers, booleans, null.
 * Enough for Noxs manifests and control-socket messages; deliberately strict.
 */
object MiniJson {

    class JsonException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.parseValue()
        p.skipWs()
        if (!p.eof()) throw JsonException("Trailing characters at ${p.pos}")
        return value
    }

    private class Parser(val text: String) {
        var pos = 0
        fun eof() = pos >= text.length
        fun skipWs() { while (!eof() && text[pos].isWhitespace()) pos++ }

        fun parseValue(): Any? {
            skipWs()
            if (eof()) throw JsonException("Unexpected end of input")
            return when (val c = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> if (c == '-' || c.isDigit()) parseNumber() else throw JsonException("Unexpected char '$c' at $pos")
            }
        }

        fun expect(word: String) {
            if (!text.startsWith(word, pos)) throw JsonException("Invalid literal at $pos")
            pos += word.length
        }

        fun parseObject(): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (!eof() && text[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (eof() || text[pos] != '"') throw JsonException("Expected key at $pos")
                val key = parseString()
                skipWs()
                if (eof() || text[pos] != ':') throw JsonException("Expected ':' at $pos")
                pos++
                map[key] = parseValue()
                skipWs()
                if (eof()) throw JsonException("Unterminated object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw JsonException("Expected ',' or '}' at $pos")
                }
            }
        }

        fun parseArray(): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (!eof() && text[pos] == ']') { pos++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                if (eof()) throw JsonException("Unterminated array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw JsonException("Expected ',' or ']' at $pos")
                }
            }
        }

        fun parseString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (eof()) throw JsonException("Unterminated string")
                when (val c = text[pos]) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> {
                        pos++
                        if (eof()) throw JsonException("Bad escape")
                        when (val e = text[pos]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 >= text.length) throw JsonException("Bad unicode escape")
                                val hex = text.substring(pos + 1, pos + 5)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw JsonException("Bad escape '\\$e'")
                        }
                        pos++
                    }
                    else -> { if (c.code < 0x20) throw JsonException("Control char in string"); sb.append(c); pos++ }
                }
            }
        }

        fun parseNumber(): Any {
            val start = pos
            if (!eof() && text[pos] == '-') pos++
            while (!eof() && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            val s = text.substring(start, pos)
            return s.toLongOrNull() ?: s.toDoubleOrNull() ?: throw JsonException("Invalid number '$s'")
        }
    }
}
