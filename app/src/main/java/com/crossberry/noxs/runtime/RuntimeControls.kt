/*
 * Noxs — original implementation.
 * Manager controls backing the UI screens: packages (apt), users, services,
 * processes, environment, storage. All execute through OneShotExecutor inside
 * the sandbox; results are parsed into display models.
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.shared.NoxsConstants
import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.shared.NoxsUser
import com.crossberry.noxs.shared.PasswdDb
import com.crossberry.noxs.shared.ServiceConfig
import com.crossberry.noxs.shared.ServiceDefinition
import com.crossberry.noxs.shared.ServiceStatus
import com.crossberry.noxs.shared.ShellUtil
import java.io.File

class PackageManagerControl(private val exec: OneShotExecutor) {

    data class Package(val name: String, val version: String, val section: String)

    suspend fun search(term: String): List<Package> {
        val safe = term.trim().take(64)
        if (safe.isEmpty() || safe.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it !in "-._+" }) {
            return emptyList()
        }
        val r = exec.runShell("apt-cache search --names-only ${ShellUtil.quote("^$safe")} 2>/dev/null | head -40")
        return r.stdout.lines().filter { it.contains(" - ") }.map {
            val (name, desc) = it.split(" - ", limit = 2)
            Package(name, "", desc)
        }
    }

    suspend fun installed(): List<Package> {
        val r = exec.runShell(
            "dpkg-query -W -f='${'$'}{Package}|${'$'}{Version}|${'$'}{Section}\\n' 2>/dev/null | head -300"
        )
        return r.stdout.lines().filter { '|' in it }.map {
            val f = it.split("|")
            Package(f.getOrElse(0) { "?" }, f.getOrElse(1) { "?" }, f.getOrElse(2) { "?" })
        }
    }

    /** Returns the apt command for the UI to run inside a terminal session. */
    fun installCommand(pkg: String): String {
        val safe = pkg.trim().take(64)
        require(safe.isNotEmpty() && safe.all { it.isLetterOrDigit() || it in "-._+" }) { "Invalid package name" }
        return "sudo apt install -y ${ShellUtil.quote(safe)}"
    }

    suspend fun update(): OneShotExecutor.Result {
        // Single-flight: never overlap with setup / repair / other screens.
        if (!NoxsPkgTransaction.acquire("packages-screen")) {
            return OneShotExecutor.Result(1, "", NoxsPkgTransaction.BUSY_MESSAGE)
        }
        return try {
            exec.runShell("apt-get update 2>&1 | tail -5", timeoutSec = 300)
        } finally {
            NoxsPkgTransaction.release("packages-screen")
        }
    }
}

class UserManagerControl(
    private val exec: OneShotExecutor,
    private val paths: NoxsPaths,
    private val launcher: ProotLauncher
) {

    fun listUsers(): List<NoxsUser> = try {
        PasswdDb.parsePasswd(File(paths.rootfsEtc, "passwd").readText())
    } catch (e: Exception) {
        emptyList()
    }

    fun isNoxsUser(user: NoxsUser): Boolean = user.name == NoxsConstants.DEFAULT_USER

    suspend fun createUser(name: String): Boolean {
        if (!PasswdDb.isValidUsername(name)) return false
        // useradd ships with Ubuntu too; it handles home, skel, group creation.
        val r = exec.runShell(
            "useradd -m -G sudo -s ${ShellUtil.quote(NoxsConstants.DEFAULT_SHELL)} ${ShellUtil.quote(name)}"
        )
        return r.success
    }

    suspend fun setPassword(name: String, password: CharArray): Boolean =
        setPasswordViaChpasswd(name, password)

    /**
     * Password change executed via chpasswd stdin (never on a command line).
     * Returns false when chpasswd is unavailable; UI offers in-terminal `passwd`.
     */
    suspend fun setPasswordViaChpasswd(name: String, password: CharArray): Boolean {
        if (!PasswdDb.isValidUsername(name)) return false
        return try {
            val argv = launcher.oneShotArgv(listOf("/usr/sbin/chpasswd"), asRoot = true)
            val pb = ProcessBuilder(argv)
            launcher.applyEnvTo(pb, mapOf("NOXS_ROOT_LOGIN" to "1"))
            val p = pb.start()
            p.outputStream.use {
                it.write("${name}:".toByteArray())
                it.write(String(password).toByteArray())
                it.write('\n'.code)
                it.flush()
            }
            val out = p.errorStream.bufferedReader().use { it.readText() }
            val ok = p.waitFor() == 0
            password.fill('0')
            if (!ok) NoxsLog.w("UserManager", "chpasswd failed: $out — use 'passwd' in terminal")
            ok
        } catch (e: Exception) {
            password.fill('0')
            NoxsLog.e("UserManager", "chpasswd unavailable", e)
            false
        }
    }

    suspend fun deleteUser(name: String): Boolean {
        if (!PasswdDb.isValidUsername(name) || name == NoxsConstants.DEFAULT_USER) return false
        return exec.runShell("userdel -r ${ShellUtil.quote(name)}").success
    }
}

class ServiceManagerControl(private val exec: OneShotExecutor, private val paths: NoxsPaths) {

    fun definitions(): List<ServiceDefinition> = ServiceConfig.loadAll(paths.rootfs.absolutePath)

    suspend fun statuses(): List<ServiceStatus> =
        definitions().map { def ->
            val r = exec.runShell("noxs-service status ${ShellUtil.quote(def.name)} 2>/dev/null")
            ServiceStatus(definition = def, running = r.success, pid = r.stdout.trim().filter { it.isDigit() }.toIntOrNull() ?: 0)
        }

    suspend fun control(action: String, name: String): OneShotExecutor.Result {
        require(action in setOf("start", "stop", "restart")) { "bad action" }
        require(ServiceConfig.isServiceNameSafe(name)) { "bad service name" }
        return exec.runShell("noxs-service ${'$'}action ${ShellUtil.quote(name)}", timeoutSec = 30)
    }
}

class ProcessInfoParser {

    data class Proc(
        val pid: Int,
        val ppid: Int,
        val user: String,
        val cpu: Double,
        val mem: Double,
        val state: String,
        val elapsed: String,
        val command: String
    )

    /** Parses `ps -eo pid,ppid,user,%cpu,%mem,stat,etime,comm,args --sort=-%cpu`. */
    fun parse(output: String): List<Proc> =
        output.lineSequence()
            .filter { it.isNotBlank() }
            .filter { !it.startsWith("  PID") && !it.startsWith("PID") }
            .mapNotNull { line ->
                val f = line.trim().split(Regex("\\s+"), limit = 8)
                if (f.size < 8) return@mapNotNull null
                Proc(
                    pid = f[0].toIntOrNull() ?: return@mapNotNull null,
                    ppid = f[1].toIntOrNull() ?: 0,
                    user = f[2],
                    cpu = f[3].toDoubleOrNull() ?: 0.0,
                    mem = f[4].toDoubleOrNull() ?: 0.0,
                    state = f[5],
                    elapsed = f[6],
                    command = f[7]
                )
            }.toList()
}

class ProcessManagerControl(private val exec: OneShotExecutor) {

    private val parser = ProcessInfoParser()

    suspend fun list(): List<ProcessInfoParser.Proc> {
        val r = exec.runShell(
            "ps -eo pid,ppid,user,%cpu,%mem,stat,etime,comm,args --sort=-%cpu 2>/dev/null | head -60"
        )
        return parser.parse(r.stdout)
    }

    suspend fun kill(pid: Int): Boolean {
        if (pid <= 1) return false // never PID 1 — that is the sandbox shell itself
        return exec.runShell("kill $pid 2>/dev/null").success
    }
}

class EnvManager(private val paths: NoxsPaths) {

    fun global(): Map<String, String> = try {
        File(paths.rootfsEtc, "environment").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && '=' in it }
            .associate {
                val (k, v) = it.split('=', limit = 2)
                k.trim() to v.trim()
            }
    } catch (e: Exception) { emptyMap() }

    fun setGlobal(key: String, value: String): Boolean {
        if (key.isEmpty() || key.any { !it.isLetterOrDigit() && it != '_' }) return false
        if (value.contains('\n')) return false
        val file = File(paths.rootfsEtc, "environment")
        val lines = file.readLines().filter { !it.startsWith("$key=") }.toMutableList()
        lines.add("$key=$value")
        file.writeText(lines.joinToString("\n") + "\n")
        return true
    }
}

class StorageManager(private val paths: NoxsPaths, private val resources: NoxsResources) {

    data class Report(
        val rootfsMb: Long,
        val aptCacheMb: Long,
        val logsMb: Long,
        val warnMb: Long
    )

    fun report(): Report = Report(
        rootfsMb = paths.dirSize(paths.rootfs) / (1024 * 1024),
        aptCacheMb = paths.dirSize(File(paths.rootfs, "var/cache/apt")) / (1024 * 1024),
        logsMb = paths.dirSize(paths.logs) / (1024 * 1024),
        warnMb = resources.load().storageWarnMb
    )

    fun clearAptCache(): Int {
        val dir = File(paths.rootfs, "var/cache/apt/archives")
        var n = 0
        dir.listFiles { f -> f.isFile && f.name.endsWith(".deb") }?.forEach {
            n += if (it.delete()) 1 else 0
        }
        return n
    }

    /** Full sandbox reset: removes the rootfs; the next launch re-runs setup. */
    fun resetSandbox(): Boolean {
        val deleted = paths.rootfs.deleteRecursively()
        if (deleted) {
            paths.installMarker.delete()
            paths.aptReadyMarker.delete()
        }
        return deleted
    }
}
