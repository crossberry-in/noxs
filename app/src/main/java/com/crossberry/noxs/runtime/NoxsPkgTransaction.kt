/*
 * Noxs — original implementation.
 * Package-manager transaction guard.
 *
 * Serializes every Noxs-managed apt/dpkg operation (first-time setup, the
 * package manager screen, background security bootstrap, user-initiated
 * repair). dpkg's real locks still serialize the actual processes;
 * this guard adds an app-level single-flight policy so Noxs never starts a
 * second transaction while one is active, and so in-shell users get a clear
 * message instead of an opaque dpkg lock error.
 *
 * The guard NEVER deletes or overrides dpkg lock files.
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.shared.NoxsLog
import java.io.File

object NoxsPkgTransaction {

    const val BUSY_MESSAGE: String =
        "Noxs package manager is busy. Another apt/dpkg operation is currently running."

    /** Flag visible inside the sandbox while a Noxs transaction runs. */
    const val FLAG_NAME: String = "pkg-tx"

    @Volatile
    private var owner: String? = null

    /** Returns true when the caller now owns the single apt/dpkg slot. */
    @Synchronized
    fun acquire(ownerLabel: String): Boolean {
        if (owner != null) {
            NoxsLog.i("PkgTx", "busy: '$ownerLabel' blocked by '${owner}'")
            return false
        }
        owner = ownerLabel
        NoxsLog.i("PkgTx", "acquired by '$ownerLabel'")
        return true
    }

    @Synchronized
    fun release(ownerLabel: String) {
        if (owner == ownerLabel) owner = null
    }

    @Synchronized
    fun currentOwner(): String? = owner

    /**
     * Arms the in-sandbox flag (/run/noxs/pkg-tx) so interactive shells see
     * an active transaction. Always clear it in a finally block.
     */
    fun armFlag(paths: NoxsPaths, ownerLabel: String): File? = runCatching {
        paths.rootfsNoxsRun.mkdirs()
        File(paths.rootfsNoxsRun, FLAG_NAME).apply { writeText(ownerLabel) }
    }.getOrNull()

    fun clearFlag(paths: NoxsPaths) {
        runCatching { File(paths.rootfsNoxsRun, FLAG_NAME).delete() }
    }

    /** Runs [block] while holding the transaction slot and the sandbox flag. */
    fun <T> withTransaction(paths: NoxsPaths, ownerLabel: String, block: () -> T): T? {
        if (!acquire(ownerLabel)) return null
        armFlag(paths, ownerLabel)
        try {
            return block()
        } finally {
            clearFlag(paths)
            release(ownerLabel)
        }
    }
}
