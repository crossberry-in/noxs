/*
 * Noxs — original implementation.
 * VM configuration model + JSON serialization (Noxs × NoxOS M2, spec §4
 * EmulatorManager API: config.json per VM). org.json is the Android
 * framework JSON API; unit tests use the plain org.json artifact.
 */
package com.crossberry.noxs.emulator

import org.json.JSONException
import org.json.JSONObject

data class VmConfig(
    val vmId: String = VmStorage.VM_ID,
    val name: String = VmStorage.VM_NAME,
    val arch: String = "aarch64",
    val machine: String = "virt",
    val cpu: String = "cortex-a53",
    val memoryMb: Int,
    val displayWidth: Int = 720,
    val displayHeight: Int = 1280,
    val vncBind: String = "127.0.0.1",
    val kernelAssetVersion: String,
    val qemuRuntime: String = "bookworm (Debian 12)",
    val createdAtMs: Long,
    val lastBootAtMs: Long? = null
)

object VmConfigJson {

    fun write(config: VmConfig): String = JSONObject().apply {
        put("vmId", config.vmId)
        put("name", config.name)
        put("arch", config.arch)
        put("machine", config.machine)
        put("cpu", config.cpu)
        put("memoryMb", config.memoryMb)
        put("display", JSONObject().apply {
            put("width", config.displayWidth)
            put("height", config.displayHeight)
        })
        put("vncBind", config.vncBind)
        put("kernelAssetVersion", config.kernelAssetVersion)
        put("qemuRuntime", config.qemuRuntime)
        put("createdAtMs", config.createdAtMs)
        put("lastBootAtMs", config.lastBootAtMs ?: JSONObject.NULL)
    }.toString(2)

    /**
     * Strict parse: a corrupted vm.json yields null instead of throwing —
     * it must never block a boot (it is re-written on every start anyway).
     *
     * Policy (opt* accessors are deliberately NOT used for typed reads —
     * they silently coerce wrong-typed values to defaults, which would
     * mask corruption):
     *   - field absent (or JSON null) → documented default
     *   - field present with the right JSON type → value
     *   - field present with a wrong type → null (corrupt file)
     */
    fun parse(text: String): VmConfig? = try {
        val obj = JSONObject(text)
        val display: JSONObject = when {
            !obj.has("display") || obj.isNull("display") -> JSONObject()
            else -> obj.optJSONObject("display")
                ?: throw JSONException("display: wrong type")
        }
        VmConfig(
            vmId = obj.strictString("vmId", VmStorage.VM_ID),
            name = obj.strictString("name", VmStorage.VM_NAME),
            arch = obj.strictString("arch", "aarch64"),
            machine = obj.strictString("machine", "virt"),
            cpu = obj.strictString("cpu", "cortex-a53"),
            memoryMb = obj.strictInt("memoryMb", 512),
            displayWidth = display.strictInt("width", 720),
            displayHeight = display.strictInt("height", 1280),
            vncBind = obj.strictString("vncBind", "127.0.0.1"),
            kernelAssetVersion = obj.strictString("kernelAssetVersion", ""),
            qemuRuntime = obj.strictString("qemuRuntime", "bookworm (Debian 12)"),
            createdAtMs = obj.strictLong("createdAtMs", 0L),
            lastBootAtMs = obj.strictLong("lastBootAtMs", 0L).takeIf { it > 0 }
        )
    } catch (e: Exception) {
        null
    }

    /**
     * Typed strict accessors shared by the parse above. Absent keys and
     * JSON null both fall back to [default]; wrong-typed values throw
     * JSONException, which parse() converts to null. Numeric reads accept
     * Int and Long JSON numbers (org.json stores the smallest fitting
     * integer type), never strings.
     */
    private fun JSONObject.strictString(key: String, default: String): String {
        if (!has(key)) return default
        return when (val v = opt(key)) {
            null, JSONObject.NULL -> default
            is String -> v
            else -> throw JSONException("$key: wrong type")
        }
    }

    private fun JSONObject.strictInt(key: String, default: Int): Int {
        if (!has(key)) return default
        return when (val v = opt(key)) {
            null, JSONObject.NULL -> default
            is Int -> v
            is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt()
                       else throw JSONException("$key: out of int range")
            else -> throw JSONException("$key: wrong type")
        }
    }

    private fun JSONObject.strictLong(key: String, default: Long): Long {
        if (!has(key)) return default
        return when (val v = opt(key)) {
            null, JSONObject.NULL -> default
            is Int -> v.toLong()
            is Long -> v
            else -> throw JSONException("$key: wrong type")
        }
    }
}
