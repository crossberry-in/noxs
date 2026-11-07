/*
 * Noxs — original implementation.
 * Builds and runs proot command lines that launch the Ubuntu 24.04 userspace.
 *
 * Layering (docs/ARCHITECTURE.md):
 *   Android host → Noxs app sandbox (app-private storage) → Noxs runtime
 *   (proot + libnoxs-pty) → Ubuntu 24.04 LTS Noble userspace (noxs user)
 *
 * proot -0 fakes uid 0 via ptrace so Ubuntu tooling (apt, sudo, su, dpkg)
 * behaves like on a real computer, entirely inside the app sandbox.
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.BuildConfig
import com.crossberry.noxs.shared.NoxsConstants
import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.shared.ShellUtil
import java.io.File

class ProotLauncher(private val paths: NoxsPaths, private val resources: NoxsResources) {

    /** Optional binds the user enabled (all default-off; sandbox-first). */
    data class Options(
        val loginAsRoot: Boolean = false,
        val workDir: String? = null,
        val extraBinds: List<Pair<String, String>> = emptyList()
    )

    /**
     * Builds the argv to launch a login shell (or one-shot [oneShotCmd]) inside
     * the Ubuntu userspace. Pure function — unit tested.
     */
    fun buildArgv(options: Options, oneShotCmd: List<String>? = null): List<String> {
        require(paths.prootBinary.isFile) { "proot binary is missing — run setup first" }
        val rootfsPath = paths.rootfs.absolutePath

        val argv = mutableListOf(
            paths.prootBinary.absolutePath,
            "--kill-on-exit",
            // Android app storage blocks hard-link creation. PRoot emulates guest
            // hard links with symlinks so dpkg can atomically back up its status DB.
            "--link2symlink",
            "-0", // fake root (ptrace-level); never touches Android's real uid
            "-r", rootfsPath
        )

        // Runtime state: app-private run dir → /var/run/noxs/host (unix sockets)
        argv += "-b"
        argv += "${paths.run.absolutePath}:${NoxsConstants.HOST_RUN_DIR}"

        // Dev/proc/sys come from the host read-only where possible.
        argv += "-b"
        argv += "/dev"
        argv += "-b"
        argv += "/proc"
        argv += "-b"
        argv += "/sys"

        for ((host, guest) in options.extraBinds) {
            require(ShellUtil.isSafePathToken(host) && ShellUtil.isSafePathToken(guest)) {
                "Unsafe bind path"
            }
            argv += "-b"
            argv += "$host:$guest"
        }

        // Resource limits are applied inside the sandbox by noxs-resource.
        argv += "-b"
        argv += "${resources.confFile.absolutePath}:/etc/noxs/resources.conf"

        val workDir = options.workDir
            ?: if (options.loginAsRoot) NoxsConstants.DEFAULT_ROOT_HOME else NoxsConstants.DEFAULT_USER_HOME
        argv += "-w"
        argv += workDir

        if (options.loginAsRoot) {
            argv += "/bin/bash"
            argv += "--login"
        } else {
            // su as (faked) uid 0 needs no password; drops to the noxs user.
            argv += "/bin/su"
            argv += "-s"
            argv += "/bin/bash"
            argv += "-l"
            argv += NoxsConstants.DEFAULT_USER
        }

        if (oneShotCmd != null) {
            // Both the fake-root Bash login and the noxs su wrapper need an
            // explicit command string; appending argv directly after `bash
            // --login` makes Bash treat the first executable as a script path.
            argv += "-c"
            argv += ShellUtil.commandLine(oneShotCmd)
        }
        return argv
    }

    fun buildEnv(extra: Map<String, String> = emptyMap()): Array<String> {
        val env = linkedMapOf(
            "PATH" to "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            "HOME" to if (extra["NOXS_ROOT_LOGIN"] == "1") NoxsConstants.DEFAULT_ROOT_HOME else NoxsConstants.DEFAULT_USER_HOME,
            "USER" to NoxsConstants.DEFAULT_USER,
            "LOGNAME" to NoxsConstants.DEFAULT_USER,
            "TERM" to NoxsConstants.TERM_VALUE,
            "LANG" to NoxsConstants.DEFAULT_LANG,
            "NOXS" to "1",
            "NOXS_VERSION" to BuildConfig.VERSION_NAME,
            "PROOT_NO_SECCOMP" to "1", // maximizes device compatibility
            "PROOT_IGNORE_MISSING_BINDINGS" to "1",
            "PROOT_TMP_DIR" to paths.tmp.absolutePath,
            "TMPDIR" to "/tmp"
        )
        // Bundled proot deps (libtalloc.so, libandroid-shmem.so, libproot-loader.so)
        // live next to libproot.so in nativeLibraryDir; the dynamic linker and proot
        // need these env vars to resolve them.
        env["PROOT_LOADER"] = paths.prootLoaderBinary.absolutePath
        env["PROOT_LOADER_32"] = paths.prootLoader32Binary.absolutePath
        paths.nativeLibDir?.let { dir ->
            env["LD_LIBRARY_PATH"] = dir.absolutePath
        }
        env.putAll(extra)
        return env.map { "${it.key}=${it.value}" }.toTypedArray()
    }

    /** Applies [buildEnv] to a [ProcessBuilder] for one-shot commands. */
    fun applyEnvTo(pb: ProcessBuilder, extra: Map<String, String> = emptyMap()) {
        val map = pb.environment()
        for (kv in buildEnv(extra)) {
            val idx = kv.indexOf('=')
            if (idx > 0) map[kv.substring(0, idx)] = kv.substring(idx + 1)
        }
    }

    /** Standard user session command (used by SessionManager). */
    fun sessionArgv(loginAsRoot: Boolean): List<String> =
        buildArgv(Options(loginAsRoot = loginAsRoot))

    /** Non-interactive one-shot for app maintenance commands (dpkg-query etc.). */
    fun oneShotArgv(cmd: List<String>, asRoot: Boolean = true): List<String> =
        buildArgv(Options(loginAsRoot = asRoot), oneShotCmd = cmd)
}
