package com.crossberry.noxs.environments

import com.crossberry.noxs.environments.model.CompatibilityLevel
import com.crossberry.noxs.environments.model.Environment
import com.crossberry.noxs.environments.model.EnvironmentCapability
import com.crossberry.noxs.environments.model.EnvironmentStatus
import com.crossberry.noxs.environments.model.EnvJson
import com.crossberry.noxs.environments.providers.ProviderRegistry
import com.crossberry.noxs.environments.providers.StorageRequirements
import com.crossberry.noxs.environments.providers.UbuntuProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider discovery for the SINGLE-environment Noxs: Ubuntu 24.04 LTS
 * (Noble Numbat) is the one and only provider (Ubuntu-first spec). There is
 * no Debian provider and no distribution selector — fresh installs download
 * and install ONLY the genuine Ubuntu rootfs.
 */
class ProviderRegistryTest {

    private fun device(abi: String = "arm64-v8a", storage: Long = 16L * 1024 * 1024 * 1024) =
        DeviceProfile(abi, listOf(abi), 29, 4L * 1024 * 1024 * 1024, storage, true)

    @Test fun `exactly one environment is discovered - ubuntu`() {
        val providers = ProviderRegistry.createAll(termuxInstalled = false)
        assertEquals(listOf("ubuntu"), providers.map { it.id })
    }

    @Test fun `no debian provider exists anywhere in the registry`() {
        val providers = ProviderRegistry.createAll(false)
        assertEquals(emptyList<String>(), providers.filter { it.id == "debian" }.map { it.id })
        assertEquals(null, ProviderRegistry.findById(providers, "debian"))
        // No distribution selector: the registry must not offer alternatives.
        assertTrue(providers.size == 1)
    }

    @Test fun `ubuntu is the recommended default and is supported on arm64`() {
        val ubuntu = ProviderRegistry.createAll(false).first { it.id == "ubuntu" }
        assertTrue(ubuntu.recommended)
        assertEquals("Ubuntu 24.04 LTS", ubuntu.displayName)
        assertEquals(CompatibilityLevel.SUPPORTED, ubuntu.checkCompatibility(device()).level)
    }

    @Test fun `ubuntu ships a single noble variant marked default`() {
        val variants = UbuntuProvider().variants()
        assertEquals(listOf("noble"), variants.map { it.id })
        assertTrue(variants.single().isDefault)
        // Pinned official cdimage ubuntu-base 24.04.5 arm64 archive size.
        assertEquals(29_936_675L, variants.single().downloadBytesEstimate)
        assertTrue(variants.single().extractedBytesEstimate > 0L)
    }

    @Test fun `ubuntu capabilities cover the full feature set`() {
        val caps = UbuntuProvider().capabilities()
        assertTrue(caps.contains(EnvironmentCapability.TERMINAL))
        assertTrue(caps.contains(EnvironmentCapability.SUDO))
        assertTrue(caps.contains(EnvironmentCapability.PACKAGE_MANAGER))
        assertTrue(caps.contains(EnvironmentCapability.CODE_SERVER))
    }

    @Test fun `findById still resolves the ubuntu provider`() {
        val providers = ProviderRegistry.createAll(false)
        assertEquals("ubuntu", ProviderRegistry.findById(providers, "ubuntu")?.id)
    }

    @Test fun `apt adapter is the only package manager translation`() {
        val apt = AptAdapter()
        assertEquals(
            listOf("/usr/bin/apt-get", "install", "--yes", "--no-install-recommends", "git"),
            apt.install("git")
        )
        assertEquals(null, PackageManagerAdapters.forKind(com.crossberry.noxs.environments.model.PackageManagerKind.PACMAN))
        assertEquals("python3", PackageManagerAdapters.optionalComponentPackage("Python", com.crossberry.noxs.environments.model.PackageManagerKind.APT))
    }

    @Test fun `storage requirement adds download temp extracted and margin`() {
        val required = StorageRequirements.forVariant(29_936_675L, 101_000_000L)
        assertTrue(required > 101_000_000L)
    }

    @Test fun `environment json round trip survives single-env registry`() {
        val env = Environment(
            id = "ubuntu", providerId = "ubuntu", displayName = "Ubuntu 24.04 LTS",
            version = "", architecture = "arm64-v8a", variant = "noble",
            status = EnvironmentStatus.READY, storagePath = "/data/noxs/environments/ubuntu",
            createdAt = 1L, updatedAt = 2L
        )
        val restored = Environment.fromJson(env.toJson())
        assertNotNull(restored)
        assertEquals(env, restored)
    }

    @Test fun `envjson writer escapes control characters`() {
        val text = EnvJson.write(mapOf("a" to "quote\" backslash\\ newline\n"))
        val parsed = EnvJson.readObject(text)
        assertEquals("quote\" backslash\\ newline\n", parsed["a"])
    }

    @Test fun `legacy debian id never collides with the ubuntu provider id`() {
        // The read-only migration import uses the "debian" id; the provider
        // registry must never offer it (no Debian download path exists).
        assertFalse("ubuntu" == "debian")
        assertEquals(null, ProviderRegistry.findById(ProviderRegistry.createAll(false), "debian"))
    }
}
