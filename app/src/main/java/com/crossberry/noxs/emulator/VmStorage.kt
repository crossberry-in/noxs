/*
 * Noxs — original implementation.
 * NoxOS VM storage layout and config (Noxs × NoxOS M2, integration spec
 * §5 "VM storage layout"). Everything lives in app-private storage under
 * the active environment base dir:
 *
 *   files/noxs/virtual-machines/noxos/
 *     vm.json           VM configuration (written before every boot)
 *     kernel/noxos      guest kernel ELF (provisioned from APK assets)
 *     kernel/version    asset version the kernel was provisioned from
 *     disks/            persistent virtio-blk images (M4 — created empty)
 *     logs/serial.log   guest boot/console log (QEMU -serial file:)
 *     logs/qemu.log     QEMU process stderr/stdout
 *
 * QEMU never sees Android paths: the VM directory is bind-mounted at
 * /noxs-vm inside the proot rootfs (ProotLauncher Options.extraBinds).
 */
package com.crossberry.noxs.emulator

import com.crossberry.noxs.runtime.NoxsPaths
import java.io.File

class VmStorage(paths: NoxsPaths) {

    /** App-private VM root: <env base>/virtual-machines */
    val vmRoot: File = File(paths.base, "virtual-machines")

    /** The single NoxOS VM directory (M2: one VM, per integration spec). */
    val noxosDir: File = File(vmRoot, "noxos")

    val kernelDir: File = File(noxosDir, "kernel")
    val kernelFile: File = File(kernelDir, "noxos")
    val kernelVersionFile: File = File(kernelDir, "version")
    val disksDir: File = File(noxosDir, "disks")
    val logsDir: File = File(noxosDir, "logs")
    val serialLogFile: File = File(logsDir, "serial.log")
    val qemuLogFile: File = File(logsDir, "qemu.log")
    val configFile: File = File(noxosDir, "vm.json")
    val qemuPidFile: File = File(noxosDir, "qemu.pid")

    /** Guest-visible path of the kernel inside the proot rootfs. */
    val guestKernelPath: String = "/noxs-vm/kernel/noxos"

    /** Guest-visible serial log path inside the proot rootfs. */
    val guestSerialLogPath: String = "/noxs-vm/logs/serial.log"

    /** Guest-visible QEMU -pidfile path inside the proot rootfs. */
    val guestQemuPidPath: String = "/noxs-vm/qemu.pid"

    fun ensureDirs(): Boolean =
        listOf(noxosDir, kernelDir, disksDir, logsDir)
            .all { it.isDirectory || it.mkdirs() }

    /**
     * Provisions the guest kernel from the APK assets into the VM dir.
     * Idempotent: re-copies only when the provisioned version differs from
     * [assetVersion]. Returns false when the copy fails.
     */
    fun provisionKernel(assetBytes: ByteArray, assetVersion: String): Boolean {
        if (!ensureDirs()) return false
        val current = if (kernelVersionFile.isFile) {
            try {
                kernelVersionFile.readText().trim()
            } catch (e: Exception) {
                ""
            }
        } else ""
        if (current == assetVersion && kernelFile.isFile && kernelFile.length() > 0L) {
            return true
        }
        return try {
            val tmp = File(kernelDir, "noxos.tmp")
            tmp.writeBytes(assetBytes)
            if (!tmp.renameTo(kernelFile)) {
                // renameTo can fail across file systems or on stale targets
                kernelFile.delete()
                if (!tmp.renameTo(kernelFile)) return false
            }
            tmp.delete()
            kernelVersionFile.writeText(assetVersion)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Tail of a log file, safe on missing files; at most [maxLines] lines. */
    fun logTail(file: File, maxLines: Int): List<String> = try {
        if (!file.isFile) {
            emptyList()
        } else {
            val bytes = file.length()
            val start = if (bytes > LOG_TAIL_WINDOW) bytes - LOG_TAIL_WINDOW else 0L
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray((bytes - start).toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }.lineSequence().takeLast(maxLines).toList()
        }
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        /** Kernel ELF bundled in the APK (M1-verified release build). */
        const val KERNEL_ASSET_PATH: String = "noxos/noxos-kernel-0.9.0"
        const val KERNEL_ASSET_VERSION: String = "0.9.0"
        const val VM_ID: String = "noxos"
        const val VM_NAME: String = "NoxOS"

        /** Bind target of the VM dir inside the proot rootfs. */
        const val GUEST_VM_BIND: String = "/noxs-vm"

        /** Log tail window in bytes (bounded reads, no unbounded slurps). */
        const val LOG_TAIL_WINDOW: Long = 262_144L
    }
}
