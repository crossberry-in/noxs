/*
 * Noxs — original implementation.
 * VM configuration model + JSON serialization (Noxs × NoxOS M2, spec §4
 * EmulatorManager API: config.json per VM). org.json is the Android
 * framework JSON API; unit tests use the plain org.json artifact.
 */
package com.crossberry.noxs.emulator

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
     * Strict-ish parse: returns null instead of throwing on malformed
     * content — a broken vm.json must never block a boot (it is re-written
     * on every start anyway).
     */
    fun parse(text: String): VmConfig? = try {
        val obj = JSONObject(text)
        val display = obj.optJSONObject("display")
        VmConfig(
            vmId = obj.optString("vmId", VmStorage.VM_ID),
            name = obj.optString("name", VmStorage.VM_NAME),
            arch = obj.optString("arch", "aarch64"),
            machine = obj.optString("machine", "virt"),
            cpu = obj.optString("cpu", "cortex-a53"),
            memoryMb = obj.optInt("memoryMb", 512),
            displayWidth = display?.optInt("width", 720) ?: 720,
            displayHeight = display?.optInt("height", 1280) ?: 1280,
            vncBind = obj.optString("vncBind", "127.0.0.1"),
            kernelAssetVersion = obj.optString("kernelAssetVersion", ""),
            qemuRuntime = obj.optString("qemuRuntime", "bookworm (Debian 12)"),
            createdAtMs = obj.optLong("createdAtMs", 0L),
            lastBootAtMs = if (obj.isNull("lastBootAtMs")) {
                null
            } else {
                obj.optLong("lastBootAtMs", 0L).takeIf { it > 0 }
            }
        )
    } catch (e: Exception) {
        null
    }
}
