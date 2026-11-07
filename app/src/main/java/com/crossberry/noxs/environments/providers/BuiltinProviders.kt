/*
 * Noxs — original implementation.
 * Builtin environment providers (spec §2-§8 of the Ubuntu-first spec).
 *
 * Ubuntu 24.04 LTS (Noble Numbat) is the one and only environment for fresh
 * installs: installed through the proven NoxsInstaller pipeline (staged
 * downloads, TarGuard extraction, signed APT bootstrap) from the pinned,
 * official cdimage.ubuntu.com ubuntu-base ARM64 artifact. There is no
 * distribution selector and no Debian fallback — the installer downloads and
 * installs ONLY the genuine Ubuntu 24.04 LTS ARM64 rootfs.
 *
 * Existing (pre-migration) Debian installations of upgrading users are NOT
 * providers: they are imported read-only as a legacy environment by
 * NoxsEnvironmentManager (spec §9 — never silently erased, user data kept).
 */
package com.crossberry.noxs.environments.providers

import com.crossberry.noxs.environments.DeviceProfile
import com.crossberry.noxs.environments.EnvironmentProvider
import com.crossberry.noxs.environments.model.EnvironmentCapability
import com.crossberry.noxs.environments.model.CompatibilityReport
import com.crossberry.noxs.environments.model.EnvironmentVariant

// ------------------------------------------------------------------ Ubuntu

/**
 * Ubuntu 24.04 LTS (Noble Numbat) — the one Noxs environment (Ubuntu-first
 * spec). Installation runs the proven NoxsInstaller pipeline (staged
 * downloads, TarGuard extraction, signed APT bootstrap) against the official
 * cdimage.ubuntu.com ubuntu-base artifact, then configures the official
 * Ubuntu repositories (ports.ubuntu.com on arm64, archive.ubuntu.com on
 * amd64) and the `noxs@ubuntu:~$` terminal identity.
 */
class UbuntuProvider : EnvironmentProvider {
    override val id = "ubuntu"
    override val displayName = "Ubuntu 24.04 LTS"
    override val description = "Genuine Ubuntu 24.04 LTS (Noble Numbat) userspace"
    override val recommended = true

    override suspend fun runInstall(ctx: com.crossberry.noxs.environments.InstallContext,
                                    variant: EnvironmentVariant): Boolean {
        val installer = com.crossberry.noxs.runtime.NoxsInstaller(
            ctx.context, ctx.paths, ctx.launcher, ctx.isCancelled
        ).apply {
            promptHostLabel = "ubuntu"
            distroBanner = "Noxs Ubuntu 24.04 LTS (noble)"
        }
        val progress = object : com.crossberry.noxs.runtime.NoxsInstaller.Progress {
            override fun onStep(step: Int, titleRes: Int, detail: String) {
                val state = when (step) {
                    5 -> com.crossberry.noxs.environments.model.SetupState.DOWNLOADING
                    6 -> com.crossberry.noxs.environments.model.SetupState.EXTRACTING
                    7, 9 -> com.crossberry.noxs.environments.model.SetupState.CONFIGURING
                    8 -> com.crossberry.noxs.environments.model.SetupState.CREATING_USER
                    10 -> com.crossberry.noxs.environments.model.SetupState.VERIFYING_ENVIRONMENT
                    else -> com.crossberry.noxs.environments.model.SetupState.PREPARING
                }
                val op = detail.ifBlank { ctx.context.getString(titleRes) }
                ctx.onStage(state, op)
            }
            override fun onProgressBytes(downloaded: Long, total: Long) =
                ctx.onDownloadProgress(downloaded, total)
            override fun onExtracted(entries: Long) = ctx.onExtractProgress(entries)
            override fun onLog(line: String) = ctx.onLog(line)
            override fun onPasswordRequired(): CharArray? = ctx.passwordProvider()
        }
        return when (val result = installer.install(progress)) {
            is com.crossberry.noxs.runtime.NoxsInstaller.InstallResult.Success -> true
            is com.crossberry.noxs.runtime.NoxsInstaller.InstallResult.Failure ->
                throw java.io.IOException(
                    ctx.context.getString(result.userMessageRes) + ": " + result.detail
                )
            com.crossberry.noxs.runtime.NoxsInstaller.InstallResult.Cancelled ->
                throw com.crossberry.noxs.environments.SetupCancelledException()
        }
    }

    override fun capabilities() = setOf(
        EnvironmentCapability.TERMINAL, EnvironmentCapability.FILES,
        EnvironmentCapability.PROCESSES, EnvironmentCapability.PACKAGE_MANAGER,
        EnvironmentCapability.SUDO, EnvironmentCapability.DOCKER,
        EnvironmentCapability.CODE_SERVER, EnvironmentCapability.BACKGROUND_TASKS
    )

    override fun variants(): List<EnvironmentVariant> = listOf(
        EnvironmentVariant(
            id = "noble",
            name = "Ubuntu 24.04 LTS (Noble Numbat)",
            description = "Genuine Ubuntu 24.04 LTS arm64 — the only Noxs environment",
            downloadBytesEstimate = 29_936_675L,
            extractedBytesEstimate = 101_000_000L,
            isDefault = true
        )
    )

    override fun checkCompatibility(device: DeviceProfile): CompatibilityReport =
        com.crossberry.noxs.environments.CompatibilityEngine.report(
            device = device,
            requiredStorageBytes = StorageRequirements.forVariant(29_936_675L, 101_000_000L)
        )
}

// NOTE: Noxs ships exactly ONE environment — Ubuntu 24.04 LTS (noble). There
// is no Debian/Ubuntu selector and no Debian fallback: the install pipeline
// downloads the pinned official Ubuntu base rootfs only. Legacy Debian
// installs of upgrading users are imported by NoxsEnvironmentManager as a
// read-only migration source (spec §9) — they are not providers.

// ------------------------------------------------------------------ registry

/** Central provider discovery (spec §2, §57). */
object ProviderRegistry {

    fun createAll(termuxInstalled: Boolean): List<EnvironmentProvider> = listOf(
        UbuntuProvider()
    )

    fun findById(providers: List<EnvironmentProvider>, id: String): EnvironmentProvider? =
        providers.firstOrNull { it.id == id }
}

object StorageRequirements {
    fun forVariant(downloadBytes: Long, extractedBytes: Long): Long =
        com.crossberry.noxs.environments.model.StoragePlan.compute(downloadBytes, extractedBytes).totalRequired
}
