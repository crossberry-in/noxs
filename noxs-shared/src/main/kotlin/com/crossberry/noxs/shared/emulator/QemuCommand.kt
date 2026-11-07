/*
 * Noxs — original implementation.
 * Pure QEMU command-line builder for the NoxOS guest VM (Noxs × NoxOS, M2).
 *
 * The machine flags and device set are the EXACT configuration verified
 * against qemu-system-aarch64 7.2.22 (M1 runtime compatibility gate:
 * Noxs tools/emulator/validate_runtime_gate.sh +
 * NoxOS tests/qemu_smoke.sh / qemu_graphics.sh / qemu_input.sh):
 *
 *   -M virt -cpu cortex-a53 -m <mem> -nic none
 *   -global virtio-mmio.force-legacy=off
 *   -device virtio-gpu-device,xres=720,yres=1280
 *   -device virtio-keyboard-device
 *   -device virtio-tablet-device
 *   -kernel <elf>
 *
 * M2 adds the Android-side display/log channels:
 *   -vnc 127.0.0.1:<display>   display over loopback only — never exposed
 *                              on any external interface (integration spec)
 *   -serial file:<path>        guest boot log for diagnostics
 *
 * TCG only: no -enable-kvm / -accel kvm anywhere on the Android path.
 */
package com.crossberry.noxs.shared.emulator

object QemuCommand {

    /** VNC display range probed by the manager (QEMU listens on port 5900+n). */
    const val VNC_DISPLAY_MIN: Int = 1
    const val VNC_DISPLAY_MAX: Int = 9
    const val VNC_PORT_BASE: Int = 5900

    /** Fully explicit VM configuration — no reliance on QEMU defaults. */
    data class Config(
        /** Kernel ELF path as seen INSIDE the proot rootfs (bind-mounted VM dir). */
        val kernelPath: String,
        /** Serial log path as seen inside the proot rootfs. */
        val serialLogPath: String,
        /** Guest-visible path for QEMU's -pidfile (real QEMU pid, API-safe:
         *  java.lang.Process.pid() needs API 26, minSdk is 24). */
        val pidFilePath: String,
        /** VNC display number (QEMU listens on 127.0.0.1:[5900+display]). */
        val vncDisplay: Int,
        /** Guest RAM in MiB (chosen by VmMemoryPolicy). */
        val memoryMb: Int,
        val displayWidth: Int = 720,
        val displayHeight: Int = 1280
    )

    /** Port QEMU will listen on for [display]. */
    fun vncPort(display: Int): Int = VNC_PORT_BASE + display

    /** True when [display] is in the probed manager range. */
    fun isValidDisplay(display: Int): Boolean =
        display in VNC_DISPLAY_MIN..VNC_DISPLAY_MAX

    /**
     * Builds the qemu-system-aarch64 argv. Pure function — unit tested.
     * The result is executed through ProotLauncher inside the Debian rootfs.
     */
    fun build(config: Config): List<String> = listOf(
        "qemu-system-aarch64",
        "-M", "virt",
        "-cpu", "cortex-a53",
        "-m", "${config.memoryMb}M",
        "-nic", "none",
        "-global", "virtio-mmio.force-legacy=off",
        "-device",
        "virtio-gpu-device,xres=${config.displayWidth},yres=${config.displayHeight}",
        "-device", "virtio-keyboard-device",
        "-device", "virtio-tablet-device",
        "-kernel", config.kernelPath,
        "-serial", "file:${config.serialLogPath}",
        "-pidfile", config.pidFilePath,
        "-vnc", "127.0.0.1:${config.vncDisplay}"
    )
}
