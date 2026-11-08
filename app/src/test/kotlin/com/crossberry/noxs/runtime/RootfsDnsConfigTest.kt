package com.crossberry.noxs.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * /etc/resolv.conf is Noxs-owned at setup time: bootstrap tarballs produced
 * on cloud CI (GitHub Actions runners are Azure VMs) can bake the
 * cloud-internal resolver 168.63.129.16 into it. That address only answers
 * inside the Azure VNet, so on a phone every DNS lookup hangs and apt never
 * connects ("0% [Connecting to ...]", "E: Unable to locate package <x>") —
 * reproduced on a real aarch64 device. The configurator must therefore
 * ALWAYS write the sandbox defaults instead of keeping whatever the tarball
 * shipped.
 */
class RootfsDnsConfigTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test fun `cloud-internal resolver baked into the tarball is replaced`() {
        val rootfs = temporary.newFolder("rootfs")
        val resolv = File(rootfs, "etc/resolv.conf")
        resolv.parentFile.mkdirs()
        resolv.writeText("# baked by the build runner\nnameserver 168.63.129.16\n")

        RootfsConfigurator.configureDns(rootfs)

        val text = resolv.readText()
        assertTrue(text.contains("nameserver 1.1.1.1"))
        assertTrue(text.contains("nameserver 8.8.8.8"))
        assertFalse("Azure-internal resolver must never survive setup", text.contains("168.63.129.16"))
    }

    @Test fun `dangling systemd stub symlink does not break or bypass configuration`() {
        val rootfs = temporary.newFolder("rootfs")
        val resolv = File(rootfs, "etc/resolv.conf")
        resolv.parentFile.mkdirs()
        val stubTarget = File(rootfs, "run/systemd/resolve/stub-resolv.conf")
        Files.createSymbolicLink(resolv.toPath(), stubTarget.toPath())

        RootfsConfigurator.configureDns(rootfs)

        assertTrue(Files.isRegularFile(resolv.toPath()))
        assertFalse(Files.isSymbolicLink(resolv.toPath()))
        assertTrue(resolv.readText().contains("nameserver 1.1.1.1"))
        assertFalse(
            "the write must land on /etc/resolv.conf itself, never through the link",
            stubTarget.exists()
        )
    }

    @Test fun `a pre-existing valid-looking file is still owned by Noxs at setup`() {
        val rootfs = temporary.newFolder("rootfs")
        val resolv = File(rootfs, "etc/resolv.conf")
        resolv.parentFile.mkdirs()
        resolv.writeText("nameserver 9.9.9.9\n")

        RootfsConfigurator.configureDns(rootfs)

        val text = resolv.readText()
        assertTrue(text.contains("nameserver 1.1.1.1"))
        assertFalse(text.contains("9.9.9.9"))
    }
}
