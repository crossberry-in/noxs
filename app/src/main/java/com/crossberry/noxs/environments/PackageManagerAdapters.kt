/*
 * Noxs — original implementation.
 * PackageManagerAdapter (spec §28): generic package operations translated to
 * the environment's real package manager. UI code NEVER constructs raw
 * package-manager commands.
 *
 * Noxs ships exactly ONE environment — Ubuntu 24.04 LTS — so the only
 * adapter is
 * apt. The lookup still goes through [PackageManagerKind] so optional
 * component installs degrade honestly (null) instead of guessing.
 *
 * Pure command translation — unit tested on the JVM.
 */
package com.crossberry.noxs.environments

import com.crossberry.noxs.environments.model.PackageManagerKind

interface PackageManagerAdapter {
    val kind: PackageManagerKind
    val binary: String
    fun update(): List<String>
    fun install(vararg packages: String): List<String>
    fun remove(vararg packages: String): List<String>
    fun search(query: String): List<String>
    fun upgrade(): List<String>
}

class AptAdapter : PackageManagerAdapter {
    override val kind = PackageManagerKind.APT
    override val binary = "/usr/bin/apt-get"
    override fun update() = listOf(binary, "update", "--yes")
    override fun install(vararg packages: String) =
        listOf(binary, "install", "--yes", "--no-install-recommends") + packages
    override fun remove(vararg packages: String) = listOf(binary, "remove", "--yes") + packages
    override fun search(query: String) = listOf("/usr/bin/apt-cache", "search", query)
    override fun upgrade() = listOf(binary, "upgrade", "--yes")
}

object PackageManagerAdapters {
    fun forKind(kind: PackageManagerKind): PackageManagerAdapter? = when (kind) {
        PackageManagerKind.APT -> AptAdapter()
        PackageManagerKind.PACMAN, PackageManagerKind.PKG, PackageManagerKind.NONE -> null
    }

    /** Well-known optional components (spec §36) mapped to apt package names. */
    fun optionalComponentPackage(component: String, kind: PackageManagerKind): String? {
        if (kind != PackageManagerKind.APT) return null
        return when (component) {
            "Git" -> "git"
            "Python" -> "python3"
            "Node.js" -> "nodejs"
            "code-server" -> "code-server"
            else -> null
        }
    }
}
