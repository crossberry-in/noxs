/*
 * Noxs — original implementation.
 * Shared wiring for the NXMirror cloud plugin catalog used by the Plugin
 * Store and the plugin details page. The token comes from the encrypted app
 * session (Noxs account screen) and falls back to the guest CLI token file
 * that `nxc login` writes inside the active rootfs home. The token is never
 * logged and never leaves the app process.
 */
package com.crossberry.noxs.ui

import android.app.Application
import com.crossberry.noxs.runtime.plugins.NoxsPluginCloudCatalog
import java.io.File

internal fun noxsPluginCloudCatalog(app: Application, rootfsHome: File): NoxsPluginCloudCatalog =
    NoxsPluginCloudCatalog(
        cacheDir = File(app.cacheDir, "plugins"),
        tokenProvider = {
            NoxsMirrorSession.token(app)
                ?: runCatching {
                    File(rootfsHome, ".config/noxs-mirror/token")
                        .takeIf { it.isFile }
                        ?.readText(Charsets.UTF_8)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }.getOrNull()
        }
    )
