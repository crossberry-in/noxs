package com.crossberry.noxs.devstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectGeneratorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun createsKotlinProjectWithGradleAndStarterSource() {
        val home = temporary.newFolder("home")
        val result = ProjectGenerator(home).create("HelloKotlin", ProjectLanguage.KOTLIN)

        assertEquals("/home/noxs/Projects/HelloKotlin", result.guestPath)
        assertTrue(java.io.File(result.directory, "settings.gradle.kts").isFile)
        assertTrue(java.io.File(result.directory, "build.gradle.kts").readText().contains("MainKt"))
        assertTrue(java.io.File(result.directory, "src/main/kotlin/Main.kt").readText().contains("fun main()"))
        assertTrue(java.io.File(result.directory, "README.md").readText().contains("gradle run"))
    }

    @Test
    fun createsJavaMozhiAndRustEntryPoints() {
        val home = temporary.newFolder("multi-home")
        val generator = ProjectGenerator(home)

        val java = generator.create("JavaDemo", ProjectLanguage.JAVA)
        assertTrue(java.io.File(java.directory, "src/main/java/Main.java").isFile)

        val mozhi = generator.create("MozhiDemo", ProjectLanguage.MOZHI)
        assertTrue(java.io.File(mozhi.directory, "src/main.mz").readText().contains("print("))

        val rust = generator.create("RustDemo", ProjectLanguage.RUST)
        assertTrue(java.io.File(rust.directory, "Cargo.toml").isFile)
        assertTrue(java.io.File(rust.directory, "src/main.rs").readText().contains("fn main()"))
    }

    @Test
    fun rejectsTraversalAndInvalidNames() {
        val home = temporary.newFolder("safe-home")
        val generator = ProjectGenerator(home)

        val traversal = runCatching { generator.create("../outside", ProjectLanguage.RUST) }
        assertTrue(traversal.isFailure)
        assertFalse(java.io.File(home.parentFile, "outside").exists())

        val invalid = runCatching { generator.create("bad name", ProjectLanguage.JAVA) }
        assertTrue(invalid.isFailure)
    }

    @Test
    fun refusesToOverwriteExistingProject() {
        val home = temporary.newFolder("existing-home")
        val generator = ProjectGenerator(home)
        generator.create("Existing", ProjectLanguage.MOZHI)

        val second = runCatching { generator.create("Existing", ProjectLanguage.MOZHI) }
        assertTrue(second.isFailure)
    }
}
