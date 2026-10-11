/*
 * Noxs — original implementation.
 * JVM tests for OSC 7 working-directory reporting: the Noxs shell integration
 * emits the cwd on every prompt, the emulator parses file:// payloads
 * (percent-encoded, with or without authority) plus bare absolute paths, and
 * rejects anything that is not a plausible absolute directory path.
 */
package com.crossberry.noxs.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Osc7WorkingDirectoryTest {

    private lateinit var emu: TerminalEmulator
    private var reported: MutableList<String> = mutableListOf()

    @Before
    fun setup() {
        reported = mutableListOf()
        emu = TerminalEmulator(object : TerminalEmulator.Client {
            override fun onScreenChanged() {}
            override fun onTitleChanged(title: String) {}
            override fun onBell() {}
            override fun onResize(cols: Int, rows: Int) {}
            override fun onReply(data: ByteArray) {}
            override fun onWorkingDirectoryChanged(dir: String) {
                reported.add(dir)
            }
        }, cols = 30, rows = 4, scrollbackLines = 100)
    }

    private fun write(s: String) = emu.write(s.toByteArray(Charsets.UTF_8))

    private fun osc7(payload: String) {
        // BEL-terminated and ST-terminated variants must both parse.
        write("\u001b]7;$payload\u0007")
    }

    @Test
    fun `file URL with empty authority is parsed`() {
        osc7("file:///home/noxs")
        assertEquals("/home/noxs", emu.currentWorkingDirectory)
        assertEquals(listOf("/home/noxs"), reported)
    }

    @Test
    fun `file URL with authority strips the host`() {
        osc7("file://noxs-host/home/noxs/projects")
        assertEquals("/home/noxs/projects", emu.currentWorkingDirectory)
    }

    @Test
    fun `ST terminated payload parses`() {
        write("\u001b]7;file:///home/noxs\u001b\\")
        assertEquals("/home/noxs", emu.currentWorkingDirectory)
    }

    @Test
    fun `bare absolute path is accepted`() {
        osc7("/tmp/build dir")
        assertEquals("/tmp/build dir", emu.currentWorkingDirectory)
    }

    @Test
    fun `percent-encoded spaces decode`() {
        osc7("file:///home/noxs/my%20projects/demo")
        assertEquals("/home/noxs/my projects/demo", emu.currentWorkingDirectory)
    }

    @Test
    fun `percent-encoded UTF-8 multibyte decodes`() {
        // /home/noxs/пример (Cyrillic)
        osc7("file:///home/noxs/%D0%BF%D1%80%D0%B8%D0%BC%D0%B5%D1%80")
        assertEquals("/home/noxs/пример", emu.currentWorkingDirectory)
    }

    @Test
    fun `relative payload is rejected`() {
        osc7("home/noxs")
        assertNull(emu.currentWorkingDirectory)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `file URL without any slash after authority is rejected`() {
        osc7("file://localhost")
        assertNull(emu.currentWorkingDirectory)
    }

    @Test
    fun `control characters are rejected`() {
        // percent-encoded SOH (0x01) — decodes to a control char after parsing
        osc7("file:///home/noxs/%01evil")
        assertNull(emu.currentWorkingDirectory)
    }

    @Test
    fun `oversized payload is rejected`() {
        osc7("file:///" + "a".repeat(2048))
        assertNull(emu.currentWorkingDirectory)
    }

    @Test
    fun `repeated identical reports do not re-notify`() {
        osc7("file:///home/noxs")
        osc7("file:///home/noxs")
        assertEquals(1, reported.size)
    }

    @Test
    fun `directory changes re-notify`() {
        osc7("file:///home/noxs")
        osc7("file:///tmp")
        assertEquals(listOf("/home/noxs", "/tmp"), reported)
    }

    @Test
    fun `title handling still works alongside osc 7`() {
        write("\u001b]0;my title\u0007")
        osc7("file:///home/noxs")
        assertEquals("my title", emu.title)
        assertEquals("/home/noxs", emu.currentWorkingDirectory)
    }

    @Test
    fun `malformed percent escape stays literal`() {
        osc7("file:///home/noxs/100%done")
        assertEquals("/home/noxs/100%done", emu.currentWorkingDirectory)
    }
}
