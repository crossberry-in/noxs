package com.crossberry.noxs.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupProgressSanitizerTest {
    @Test fun `internal URLs and diagnostics never reach setup panel`() {
        listOf(
            "rootfs url=https://example.invalid/rootfs.tar.gz",
            "proot bundled: libproot.so (247488 bytes)",
            "arch=arm64-v8a",
            "extracted files=5238 dirs=778 links=639",
            "dpkg: error creating new backup file '/var/lib/dpkg/status-old': Permission denied",
            "password set for noxs"
        ).forEach { assertNull(SetupProgressSanitizer.message(it)) }
    }

    @Test fun `package and download activity maps to general friendly status`() {
        assertEquals(
            "Preparing secure connections",
            SetupProgressSanitizer.message("Refreshing signed Ubuntu package metadata over HTTP")
        )
        assertEquals(
            "Downloading your Linux environment",
            SetupProgressSanitizer.message("Downloading rootfs archive")
        )
        assertEquals(
            "Checking downloaded files",
            SetupProgressSanitizer.message("Verifying SHA-256")
        )
    }
}
