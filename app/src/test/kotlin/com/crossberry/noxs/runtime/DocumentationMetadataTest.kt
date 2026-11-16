/*
 * Noxs — original implementation.
 * Documentation single-source-of-truth tests. These tests are the automated
 * drift detection required by the Noxs documentation workflow: they verify
 * that the metadata registry, the CLI help text, the docs deep-link mapping
 * and the Android manifest all agree — and that every documented command has
 * a documentation destination.
 */
package com.crossberry.noxs.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DocumentationMetadataTest {

    private fun moduleFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        val found = candidates.firstOrNull { it.isFile }
            ?: error("Cannot locate $relative (looked in ${candidates.map { it.absolutePath }})")
        return found
    }

    private fun loadMeta(name: String): JSONObject =
        JSONObject(moduleFile("src/main/resources/documentation/$name").readText())

    private val commands = loadMeta("commands.json")
    private val features = loadMeta("features.json")
    private val permissions = loadMeta("permissions.json")
    private val apis = loadMeta("apis.json")

    private fun entries(obj: JSONObject, key: String): List<JSONObject> {
        val array = obj.getJSONArray(key)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private val statusValues = setOf("implemented", "experimental", "planned")

    // ----------------------------------------------------- command registry

    @Test
    fun `every command record has the required fields`() {
        entries(commands, "commands").forEach { command ->
            listOf(
                "name", "category", "description", "usage", "examples",
                "implementation_status", "platform_support", "documentation_route"
            ).forEach { field ->
                assertTrue(
                    "command '${command.optString("name")}' missing '$field'",
                    command.has(field)
                )
            }
            assertTrue(
                "command '${command.optString("name")}' has invalid status",
                command.getString("implementation_status") in statusValues
            )
            assertTrue(
                "command '${command.optString("name")}' has no examples",
                command.getJSONArray("examples").length() > 0
            )
        }
    }

    @Test
    fun `command names are unique`() {
        val names = entries(commands, "commands").map { it.getString("name") }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `every noxs dispatch command is documented and vice versa`() {
        val labels = dispatchLabels()
        assertTrue("dispatch discovery failed", labels.isNotEmpty())
        val blob = commands.toString()
        labels.forEach { label ->
            assertTrue(
                "dispatch command 'noxs $label' is not covered by commands.json",
                Regex("noxs ${Regex.escape(label)}[\" \\[|/,]").containsMatchIn(blob)
            )
        }
        val helpers = setOf("noxs-setup-storage", "noxs-service", "noxs-socket", "noxs-ps")
        entries(commands, "commands").forEach { command ->
            val parts = command.getString("name").split(" ")
            if (parts.first() == "noxs" && parts.size > 1 && parts.first() !in helpers) {
                val sub = parts[1].substringBefore("[")
                assertTrue(
                    "commands.json documents 'noxs $sub' which is not a dispatch command",
                    sub == "help" || sub in labels
                )
            }
        }
    }

    private fun dispatchLabels(): Set<String> {
        val cli = NoxsCliTemplate.CLI.replace("\${'$'}", "$")
        val start = cli.indexOf("case \"\${1:-help}\" in")
        assertTrue("dispatch block not found", start >= 0)
        val block = cli.substring(start)
        val end = block.indexOf("\n    *)")
        assertTrue("dispatch terminator not found", end > 0)
        val labels = mutableSetOf<String>()
        block.substring(0, end).lines().forEach { line ->
            // Top-level labels are at exactly 4-space indentation.
            if (Regex("^    \\S").containsMatchIn(line)) {
                line.trim().split(")", "[", " ")[0].split("|").forEach { token ->
                    if (Regex("[a-z][a-z0-9-]*").matches(token.trim())) {
                        labels.add(token.trim())
                    }
                }
            }
        }
        labels.remove("help")
        return labels
    }

    // ------------------------------------------------------ docs deep links

    @Test
    fun `docs map base url is the real documentation site`() {
        assertEquals("https://crossberry-in.github.io/nxos-docs/", NoxsDocsMap.BASE)
    }

    @Test
    fun `every docs route is well-formed`() {
        NoxsDocsMap.ROUTES.values.forEach { path ->
            assertTrue("route '$path' must be lowercase relative path", Regex("[a-z0-9/-]+/").matches(path))
            assertTrue("route '$path' must not start with /", !path.startsWith("/"))
        }
    }

    @Test
    fun `docs map resolves topics and aliases`() {
        assertEquals(NoxsDocsMap.BASE, NoxsDocsMap.url(null))
        assertEquals(NoxsDocsMap.BASE + "files/storage-access/", NoxsDocsMap.url("storage"))
        assertEquals(NoxsDocsMap.BASE + "environments/kali-installation/", NoxsDocsMap.url("kali"))
        assertEquals(NoxsDocsMap.BASE + "packages/package-manager/", NoxsDocsMap.url("pkg"))
        assertEquals(null, NoxsDocsMap.url("does-not-exist"))
    }

    @Test
    fun `cli docs_url mapping mirrors NoxsDocsMap exactly`() {
        val cli = NoxsCliTemplate.CLI.replace("\${'$'}", "$")
        NoxsDocsMap.ROUTES.forEach { (topic, path) ->
            assertTrue(
                "CLI docs_url is missing topic '$topic' (path '$path')",
                cli.contains("'%s/$path'")
            )
        }
    }

    @Test
    fun `help registry covers the required detailed topics`() {
        val cli = NoxsCliTemplate.CLI.replace("\${'$'}", "$")
        listOf(
            "storage", "setup-storage", "device", "code", "pkg", "plug",
            "cert-fix", "ai", "docs"
        ).forEach { topic ->
            assertTrue(
                "noxs help is missing required topic '$topic'",
                Regex("\n {8}$topic[)|]").containsMatchIn(cli)
            )
        }
    }

    @Test
    fun `main help mentions the documentation base url`() {
        assertTrue(NoxsCliTemplate.CLI.contains("https://crossberry-in.github.io/nxos-docs/"))
    }

    // ------------------------------------------------------- feature status

    @Test
    fun `feature statuses are valid and kali is honest`() {
        val features = entries(features, "features")
        features.forEach { feature ->
            assertTrue(
                "feature '${feature.optString("id")}' has invalid status",
                feature.getString("status") in statusValues
            )
        }
        val kali = features.first { it.getString("id") == "kali-environment" }
        assertEquals("Kali has no provider implementation — it must stay planned", "planned", kali.getString("status"))
        // Ubuntu is the only installable environment.
        val installable = features.filter { it.getString("status") != "planned" }
            .map { it.optJSONArray("platform_support")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList() }
            .flatten()
            .toSet()
        assertEquals("Only Ubuntu environments are installable today", setOf("ubuntu"), installable)
    }

    // ------------------------------------------------------ permission mirror

    @Test
    fun `permissions registry mirrors the manifest`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        val declared = Regex("android.permission\\.([A-Z_]+)").findAll(manifest)
            .map { it.groupValues[1] }.toSet()
        val registry = permissions.toString()
        declared.forEach { permission ->
            assertTrue("permissions.json is missing '$permission'", registry.contains(permission))
        }
    }

    // ------------------------------------------------------------------ apis

    @Test
    fun `api registry lists the real integration surfaces`() {
        val ids = entries(apis, "apis").map { it.getString("id") }.toSet()
        assertTrue(ids.containsAll(setOf("noxs-android-bridge", "noxs-storage-bridge", "nx-api-sdk")))
    }
}
