package com.crossberry.noxs.devstudio

enum class ProjectLanguage(val title: String, val extension: String, val runCommand: String) {
    KOTLIN("Kotlin", ".kt", "gradle run"),
    JAVA("Java", ".java", "gradle run"),
    MOZHI("Mozhi", ".mz", "mz run src/main.mz"),
    RUST("Rust", ".rs", "cargo run")
}

data class ProjectTemplate(val language: ProjectLanguage, val files: Map<String, String>, val requiredTools: List<String>)

object ProjectTemplates {
    fun forLanguage(language: ProjectLanguage, projectName: String): ProjectTemplate {
        val safeName = projectName.replace(Regex("[^A-Za-z0-9_-]"), "")
        return when (language) {
            ProjectLanguage.KOTLIN -> ProjectTemplate(language, linkedMapOf(
                "settings.gradle.kts" to "rootProject.name = \"$safeName\"\n",
                "build.gradle.kts" to """
                    plugins {
                        kotlin("jvm") version "2.2.21"
                        application
                    }
                    repositories { mavenCentral() }
                    application { mainClass.set("MainKt") }
                """.trimIndent() + "\n",
                "src/main/kotlin/Main.kt" to """
                    fun main() {
                        println("Hello from Noxs + Kotlin!")
                    }
                """.trimIndent() + "\n"
            ), listOf("java", "gradle"))
            ProjectLanguage.JAVA -> ProjectTemplate(language, linkedMapOf(
                "settings.gradle" to "rootProject.name = '$safeName'\n",
                "build.gradle" to """
                    plugins { id 'application' }
                    repositories { mavenCentral() }
                    java {
                        toolchain { languageVersion = JavaLanguageVersion.of(17) }
                    }
                    application { mainClass = 'Main' }
                """.trimIndent() + "\n",
                "src/main/java/Main.java" to """
                    public final class Main {
                        public static void main(String[] args) {
                            System.out.println("Hello from Noxs + Java!");
                        }
                    }
                """.trimIndent() + "\n"
            ), listOf("java", "javac", "gradle"))
            ProjectLanguage.MOZHI -> ProjectTemplate(language, linkedMapOf(
                "mozhi.toml" to "name = \"$safeName\"\nversion = \"0.1.0\"\nentry = \"src/main.mz\"\n",
                "src/main.mz" to "print(\"Hello from Noxs + Mozhi!\")\n"
            ), listOf("mz"))
            ProjectLanguage.RUST -> ProjectTemplate(language, linkedMapOf(
                "Cargo.toml" to """
                    [package]
                    name = "$safeName"
                    version = "0.1.0"
                    edition = "2021"

                    [dependencies]
                """.trimIndent() + "\n",
                "src/main.rs" to """
                    fn main() {
                        println!("Hello from Noxs + Rust!");
                    }
                """.trimIndent() + "\n"
            ), listOf("cargo", "rustc"))
        }
    }
}
