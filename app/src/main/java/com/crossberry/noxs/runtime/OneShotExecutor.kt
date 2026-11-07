/*
 * Noxs — original implementation.
 * One-shot executor: runs non-interactive maintenance commands inside the
 * Ubuntu userspace (proot -0 → /bin/bash -c). Used by the manager screens.
 * All command construction is argv-based — no shell string interpolation of
 * untrusted values (ShellUtil.quote is applied where needed).
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.shared.NoxsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class OneShotExecutor(private val launcher: ProotLauncher) {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val success: Boolean get() = exitCode == 0
    }

    suspend fun run(cmd: List<String>, asRoot: Boolean = true, timeoutSec: Long = 60): Result =
        withContext(Dispatchers.IO) {
            val argv = launcher.oneShotArgv(cmd, asRoot = asRoot)
            try {
                val pb = ProcessBuilder(argv).redirectErrorStream(false)
                launcher.applyEnvTo(pb, if (asRoot) mapOf("NOXS_ROOT_LOGIN" to "1") else emptyMap())
                val p = pb.start()
                val out = p.inputStream.bufferedReader().use { it.readText() }
                val err = p.errorStream.bufferedReader().use { it.readText() }
                val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
                if (!finished) {
                    p.destroyForcibly()
                    Result(124, out, "timeout after ${timeoutSec}s")
                } else {
                    Result(p.exitValue(), out, err)
                }
            } catch (e: Exception) {
                NoxsLog.e("OneShot", "cmd failed: ${cmd.firstOrNull()}", e)
                Result(126, "", e.message ?: e.javaClass.simpleName)
            }
        }

    suspend fun runShell(script: String, asRoot: Boolean = true, timeoutSec: Long = 60): Result =
        run(listOf("/bin/bash", "-c", script), asRoot, timeoutSec)
}
