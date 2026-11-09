package com.crossberry.noxs.devstudio

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID

class ProjectGenerator(private val guestHome: File) {
    data class CreatedProject(val name: String, val directory: File, val guestPath: String)

    fun create(name: String, language: ProjectLanguage): CreatedProject {
        require(NAME.matches(name)) {
            "Use 1–40 characters: start with a letter; then letters, numbers, _ or -."
        }
        require(guestHome.isDirectory) { "The Noxs Linux environment is not installed." }
        require(!Files.isSymbolicLink(guestHome.toPath())) { "Guest home cannot be a symbolic link." }

        val home = guestHome.canonicalFile
        val projects = File(home, "Projects")
        if (!projects.exists() && !projects.mkdirs()) error("Could not create the Projects workspace.")
        require(!Files.isSymbolicLink(projects.toPath())) { "Projects folder cannot be a symbolic link." }
        require(projects.canonicalFile.parentFile == home) { "Workspace path is outside Noxs home." }

        val target = File(projects, name)
        require(!target.exists()) { "A project named '$name' already exists." }
        val staging = File(projects, ".$name-\${UUID.randomUUID()}.tmp")
        try {
            check(staging.mkdir()) { "Could not create a temporary project folder." }
            val template = ProjectTemplates.forLanguage(language, name)
            template.files.forEach { (relativePath, contents) ->
                val destination = File(staging, relativePath)
                require(destination.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                    "Invalid template path."
                }
                val parent = destination.parentFile ?: error("Invalid template directory.")
                check(parent.isDirectory || parent.mkdirs()) { "Could not create template directory." }
                destination.writeText(contents, StandardCharsets.UTF_8)
            }

            val readme = """
                # $name

                Created with Noxs Developer Studio.

                - Language: \${language.title}
                - Run: \${language.runCommand}

                Install the required toolchain in Noxs before building. Project creation does not execute code.
            """.trimIndent() + "\n"
            File(staging, "README.md").writeText(readme, StandardCharsets.UTF_8)
            File(staging, "noxs.project").writeText(
                "name=$name\nlanguage=\${language.name.lowercase()}\ntemplateVersion=1\n",
                StandardCharsets.UTF_8
            )

            check(staging.renameTo(target)) { "Could not finalize the project folder." }
            return CreatedProject(name, target, "/home/noxs/Projects/$name")
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    companion object {
        private val NAME = Regex("[A-Za-z][A-Za-z0-9_-]{0,39}")
    }
}
