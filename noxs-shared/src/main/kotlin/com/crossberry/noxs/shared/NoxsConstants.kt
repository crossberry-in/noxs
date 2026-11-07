/*
 * Noxs — original implementation.
 * Central constants describing paths *inside* the Noxs root filesystem and
 * Android-side runtime locations. All Android paths are app-private.
 */
package com.crossberry.noxs.shared

object NoxsConstants {

    const val APP_ID = "com.crossberry.noxs"
    const val APP_NAME = "Noxs"
    const val VERSION_NAME = "1.0.0"
    const val VERSION_CODE = 1L

    /** Name of the default Linux user created inside the Noxs rootfs. */
    const val DEFAULT_USER = "noxs"
    const val DEFAULT_USER_HOME = "/home/noxs"
    const val DEFAULT_ROOT_HOME = "/root"
    const val DEFAULT_SHELL = "/bin/bash"

    /** Default root filesystem release bootstrapped by Noxs (Ubuntu-first). */
    const val DEFAULT_UBUNTU_RELEASE = "noble"
    const val DEFAULT_UBUNTU_VERSION_ID = "24.04"
    const val DEFAULT_UBUNTU_CODENAME = "Noble Numbat"

    /** Ubuntu APT repository hosts. arm64/armhf live on the ports mirror;
     *  amd64 (x86_64 app builds / emulators) uses the main archive. */
    const val UBUNTU_PORTS_HOST = "ports.ubuntu.com"
    const val UBUNTU_ARCHIVE_HOST = "archive.ubuntu.com"
    const val UBUNTU_SECURITY_HOST = "security.ubuntu.com"

    /** Runtime state directories inside the rootfs (FHS). */
    const val RUN_DIR = "/run"
    const val VAR_RUN_DIR = "/var/run"
    const val NOXS_RUN_DIR = "/var/run/noxs"
    const val HOST_RUN_DIR = "/var/run/noxs/host"
    const val NOXS_LOG_DIR = "/var/log/noxs"
    const val NOXS_SERVICE_DIR = "/etc/noxs/services"

    /**
     * Unix domain socket exposed by the Android host process. It physically
     * lives in app-private storage and is bind-mounted into the rootfs at
     * [HOST_RUN_DIR], so Debian-side processes can talk to the app through
     * unix:///var/run/noxs/host/&lt;socket&gt; without leaving the sandbox.
     */
    const val CONTROL_SOCKET_NAME = "noxs.sock"

    /** Terminal default. */
    const val TERM_VALUE = "xterm-256color"
    const val DEFAULT_LANG = "C.UTF-8"

    /** Cap on extracted rootfs size (bytes) — 6 GiB, protects against extraction bombs. */
    const val MAX_ROOTFS_BYTES: Long = 6L * 1024 * 1024 * 1024

    /** Default scrollback lines. */
    const val DEFAULT_SCROLLBACK = 10000

    // ---- Android-side (app-private) relative paths under filesDir ----
    const val ANDROID_BASE_DIR = "noxs"
    const val ANDROID_ROOTFS_DIR = "rootfs"
    const val ANDROID_BIN_DIR = "bin"          // proot binary lives here
    const val ANDROID_RUN_DIR = "run"          // unix sockets (host side)
    const val ANDROID_CACHE_DIR = "cache"      // cached bootstrap archives
    const val ANDROID_LOGS_DIR = "logs"
    const val ANDROID_TMP_DIR = "tmp"

    /** Marker file written after a successful bootstrap. */
    const val INSTALL_COMPLETE_MARKER = ".noxs-installed-v1"
}
