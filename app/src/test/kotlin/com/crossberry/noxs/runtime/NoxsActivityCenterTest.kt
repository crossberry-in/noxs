/*
 * Noxs — original implementation.
 * JVM tests for the Activity Center: lifecycle, smart-exit semantics,
 * safe-stop state machine, and metadata persistence.
 */
package com.crossberry.noxs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class NoxsActivityCenterTest {

    private class FakeSignaller : NoxsSignaller {
        val terminated = mutableListOf<Int>()
        val killed = mutableListOf<Int>()
        var alive = true

        override fun terminate(pid: Int) { terminated.add(pid) }
        override fun kill(pid: Int) { killed.add(pid) }
        override fun isAlive(pid: Int): Boolean = alive
    }

    private lateinit var signaller: FakeSignaller
    private lateinit var center: NoxsActivityCenter
    private lateinit var storage: File

    @Before
    fun setUp() {
        signaller = FakeSignaller()
        center = NoxsActivityCenter(signaller)
        storage = File.createTempFile("noxs-activities", ".tsv")
        storage.delete()
        center.attachStorage(storage)
    }

    // ---- lifecycle: start session -> exit -> session closes (spec 19) ----

    @Test
    fun `session lifecycle registers and completes`() {
        val record = center.register(
            title = "Terminal — shell-1",
            command = "proot · Ubuntu 24.04",
            sessionId = "shell-1",
            pid = 4242
        )
        assertEquals(NoxsActivityStatus.RUNNING, record.status)
        assertTrue(center.hasPersistentWork())

        center.markCompleted(record.activityId, exitCode = 0)
        val finished = center.find(record.activityId)!!
        assertEquals(NoxsActivityStatus.COMPLETED, finished.status)
        assertEquals(0, finished.exitCode)
        assertNotNull(finished.finishedAt)
        assertFalse(center.hasPersistentWork())
    }

    // ---- multiple sessions: exit A leaves B alive (spec 19) ----

    @Test
    fun `closing session A keeps session B running`() {
        val a = center.register(title = "Terminal — shell-1", command = "sh", sessionId = "shell-1", pid = 11)
        val b = center.register(title = "Terminal — shell-2", command = "sh", sessionId = "shell-2", pid = 22)

        center.markStopped(a.activityId)
        assertEquals(NoxsActivityStatus.STOPPED, center.find(a.activityId)!!.status)
        assertEquals(NoxsActivityStatus.RUNNING, center.find(b.activityId)!!.status)
        assertTrue(center.hasPersistentWork())
    }

    // ---- last shell exit + pinned service keeps Noxs alive (spec 10) ----

    @Test
    fun `pinned background service keeps Noxs alive after last shell`() {
        val shell = center.register(title = "Terminal — shell-1", command = "sh", sessionId = "shell-1")
        val codeserver = center.register(
            title = "code-server", command = "noxs code start",
            kind = NoxsActivityKind.CODESERVER, pid = 555, pinned = true
        )
        center.markStopped(shell.activityId)
        assertTrue(center.hasPersistentWork())

        center.markStopped(codeserver.activityId)
        assertFalse(center.hasPersistentWork())
    }

    // ---- safe stop: TERM, wait, KILL only when necessary (spec 5) ----

    @Test
    fun `requestStop signals then settles without kill`() {
        var handled: NoxsActivityRecord? = null
        center.stopHandler = { record ->
            handled = record
            center.markStopped(record.activityId)
        }
        val record = center.register(title = "npm run build", command = "npm run build", pid = 77)
        center.requestStop(record.activityId)

        assertEquals(record.activityId, handled?.activityId)
        assertEquals(NoxsActivityStatus.STOPPED, center.find(record.activityId)!!.status)
        assertTrue(signaller.terminated.isEmpty()) // handler owns the stop path
    }

    @Test
    fun `requestStop without handler falls back to TERM then KILL`() {
        val record = center.register(title = "python server.py", command = "python server.py", pid = 99)
        center.requestStop(record.activityId)
        assertEquals(listOf(99), signaller.terminated)
        assertEquals(listOf(99), signaller.killed)
        assertEquals(NoxsActivityStatus.STOPPED, center.find(record.activityId)!!.status)
    }

    @Test
    fun `requestStop on finished record is a no-op`() {
        val record = center.register(title = "done", command = "done", pid = 5)
        center.markCompleted(record.activityId, 0)
        center.requestStop(record.activityId)
        assertEquals(NoxsActivityStatus.COMPLETED, center.find(record.activityId)!!.status)
    }

    // ---- output summary + progress parsing (spec 1/3) ----

    @Test
    fun `output summary updates and parses progress from build output`() {
        val record = center.register(title = "npm run build", command = "npm run build")
        center.attachOutput(record.activityId, "building bundles…")
        center.attachOutput(record.activityId, "████████░░ 82%")

        val updated = center.find(record.activityId)!!
        assertEquals("████████░░ 82%", updated.outputSummary)
        assertEquals(82, updated.progress)

        val lines = center.recentOutput(record.activityId)
        assertEquals(listOf("building bundles…", "████████░░ 82%"), lines)
    }

    @Test
    fun `uptime formatting matches spec examples`() {
        assertEquals("00:14:32", NoxsActivityFormat.uptime(872L))
        assertEquals("00:00:41", NoxsActivityFormat.uptime(41L))
        assertEquals("3d 02:14:32", NoxsActivityFormat.uptime(3 * 86_400L + 2 * 3_600L + 14 * 60L + 32L))
    }

    // ---- persistence (spec 17) ----

    @Test
    fun `records survive persistence round trip`() {
        val record = center.register(
            title = "Terminal — shell-1", command = "proot · Ubuntu 24.04",
            sessionId = "shell-1", pid = 42, workingDirectory = "/home/noxs"
        )
        center.attachOutput(record.activityId, "npm run build")
        center.markCompleted(record.activityId, 0)

        val reloaded = NoxsActivityCenter(FakeSignaller()).also { it.attachStorage(storage) }
        val restored = reloaded.find(record.activityId)
        assertNotNull(restored)
        assertEquals("Terminal — shell-1", restored!!.title)
        assertEquals(NoxsActivityStatus.COMPLETED, restored.status)
        assertEquals(0, restored.exitCode)
        assertEquals("/home/noxs", restored.workingDirectory)
        assertEquals(100, restored.progress)
    }

    @Test
    fun `stale running records become failed after process death`() {
        center.register(title = "npm run build", command = "npm run build", pid = 7)
        // Simulate a fresh process loading persisted state with RUNNING work.
        val reloaded = NoxsActivityCenter(FakeSignaller()).also { it.attachStorage(storage) }
        val restored = reloaded.records.value.single()
        assertEquals(NoxsActivityStatus.FAILED, restored.status)
    }

    @Test
    fun `clearFinished keeps active work`() {
        val active = center.register(title = "Terminal — shell-1", command = "sh", sessionId = "shell-1")
        val done = center.register(title = "npm install", command = "npm install")
        center.markCompleted(done.activityId, 0)
        center.clearFinished()
        assertEquals(listOf(active.activityId), center.records.value.map { it.activityId })
        assertFalse(center.hasPersistentWork().not())
    }

    @Test
    fun `no persistent work with empty center`() {
        assertFalse(center.hasPersistentWork())
        assertNull(center.find("missing"))
    }
}
