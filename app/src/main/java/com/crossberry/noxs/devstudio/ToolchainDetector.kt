package com.crossberry.noxs.devstudio

import java.io.File

data class ToolchainStatus(val command: String, val installed: Boolean, val candidates: List<String>)

class ToolchainDetector(private val rootfs: File) {
    private val locations = listOf("usr/bin", "usr/local/bin", "bin")

    fun detect(): List<ToolchainStatus> = listOf(
        "java", "javac", "gradle", "mz", "rustc", "cargo"
    ).map { command ->
        val candidates = locations.map { File(rootfs, "$it/$command") }
            .filter { it.isFile && it.canExecute() }
            .map { it.absolutePath }
        ToolchainStatus(command, candidates.isNotEmpty(), candidates)
    }
}
