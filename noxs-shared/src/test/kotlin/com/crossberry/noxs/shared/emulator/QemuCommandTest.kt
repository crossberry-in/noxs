/*
 * Noxs — original implementation.
 * Unit tests for the QEMU command builder (Noxs × NoxOS M2). The built argv
 * must stay byte-identical to the M1-verified device set plus the M2
 * loopback-VNC and serial-log channels.
 */
package com.crossberry.noxs.shared.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QemuCommandTest {

    private val config = QemuCommand.Config(
        kernelPath = "/noxs-vm/kernel/noxos",
        serialLogPath = "/noxs-vm/logs/serial.log",
        pidFilePath = "/noxs-vm/qemu.pid",
        vncDisplay = 1,
        memoryMb = 512
    )

    @Test
    fun `argv matches the M1 verified device set plus VNC and serial channels`() {
        val argv = QemuCommand.build(config)
        assertEquals(
            listOf(
                "qemu-system-aarch64",
                "-M", "virt",
                "-cpu", "cortex-a53",
                "-m", "512M",
                "-nic", "none",
                "-global", "virtio-mmio.force-legacy=off",
                "-device", "virtio-gpu-device,xres=720,yres=1280",
                "-device", "virtio-keyboard-device",
                "-device", "virtio-tablet-device",
                "-kernel", "/noxs-vm/kernel/noxos",
                "-serial", "file:/noxs-vm/logs/serial.log",
                "-pidfile", "/noxs-vm/qemu.pid",
                "-vnc", "127.0.0.1:1"
            ),
            argv
        )
    }

    @Test
    fun `vnc is strictly loopback`() {
        val argv = QemuCommand.build(config)
        val idx = argv.indexOf("-vnc")
        val target = argv[idx + 1]
        assertTrue("VNC must bind 127.0.0.1, got $target", target.startsWith("127.0.0.1:"))
    }

    @Test
    fun `no KVM or accel flags ever`() {
        val argv = QemuCommand.build(config)
        assertFalse(argv.contains("-enable-kvm"))
        assertFalse(argv.contains("-accel"))
        assertFalse(argv.contains("kvm"))
    }

    @Test
    fun `display range and port math`() {
        assertEquals(5901, QemuCommand.vncPort(1))
        assertEquals(5909, QemuCommand.vncPort(9))
        assertTrue(QemuCommand.isValidDisplay(1))
        assertTrue(QemuCommand.isValidDisplay(9))
        assertFalse(QemuCommand.isValidDisplay(0))
        assertFalse(QemuCommand.isValidDisplay(10))
    }

    @Test
    fun `memory and display sizes are parameterized`() {
        val small = QemuCommand.build(config.copy(memoryMb = 256, displayWidth = 640, displayHeight = 480))
        assertTrue(small.contains("-m"))
        assertTrue(small.contains("256M"))
        assertTrue(small.contains("virtio-gpu-device,xres=640,yres=480"))
    }
}
