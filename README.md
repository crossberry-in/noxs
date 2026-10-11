# Noxs

**A Debian-optimized Linux environment for Android — a small Linux computer in your pocket.**

Noxs runs a real **Debian 12 (Bookworm)** userspace — bash, APT, sudo, Unix
domain sockets under `/run` and `/var/run`, user accounts, services and the
full Linux filesystem hierarchy — inside the Android application sandbox,
using **proot** (no root required, no security bypass, ARM64-first).

```
$ whoami
noxs
$ pwd
/home/noxs
$ uname -a
Linux noxs 6.x.x-android13 #1 SMP aarch64 GNU/Linux
$ sudo apt install git          # password-protected, inside the sandbox
$ noxs code start               # code-server (VS Code in browser), 127.0.0.1:8080
```

## Highlights

- 🐧 **Real Debian 12 userspace** — official rootfs, APT package management,
  glibc binaries; nothing recompiled against bionic.
- 📁 **True FHS filesystem** — `/bin /etc /home/noxs /run /var/run /var/log …`
- 🔐 **Honest sudo** — Debian's real sudo, password-protected, scoped to the
  Noxs sandbox. Never Android root. Never a security bypass.
- 🔌 **Unix domain sockets** — `unix:///var/run/noxs/…` + Android↔Debian IPC.
- 🖥️ **Real terminal** — PTY (native `forkpty`), VT/xterm engine (clean-room,
  original), colors/Unicode/scrollback/resize, multiple sessions.
- 🧩 **code-server integration** — `noxs code install|start|stop|restart|status`,
  local-only by default.
- 📦 **NX Package System** — `nx pkg init` (Node.js/Rust/C++/Python/Go templates
  with automatic GitHub Actions releases), `nx install tree`, versioned
  `.nx.pkg` artifacts, sha256-verified multi-arch builds (docs/PACKAGES.md).
  Every `noxs` command also works as `nx <command>`.
- 🤖 **Noxs AI Agent Terminal** — `nx ai` opens an interactive `nx@ai>` agent
  that plans multi-tool work (terminal, files, packages, processes, services),
  runs independent tools in parallel, asks `Allow? [y/N]` for sensitive
  actions and stays inside the Noxs security boundary (docs/AI-AGENT.md).
- 🌐 **Noxs native browser** — `nx ow https://example.com` opens a real
  `android.webkit.WebView` inside a draggable Noxs floating window: URL
  validation, cookies, downloads, uploads, zero bridge exposure (docs/WEB-BROWSER.md).
- 🧠 **Noxs API + Permission Center** — `@noxs/nx-api` SDK behind the
  permission manager; packages are isolated, requests are validated,
  first-launch Permission Center with real Android state (docs/NX-API.md,
  docs/PERMISSION-CENTER.md).
- ⚙️ **Rust performance core** — `noxs-core` crate: SHA-256 verification,
  tar safety walker, dependency resolver, task engine; stable C ABI with
  pure-Kotlin fallback (docs/RUST-CORE.md).
- 🧰 **nx-installer** — verified setup installer: download → sha256 →
  staged install of the nx runtime from tagged releases (docs/NX-INSTALLER.md).
- 📊 **Resource quotas** — CPU/memory/process/session/storage limits that never
  override Android's own.
- 🛡️ **Sandbox-first security** — SHA-256-pinned bootstrap, path-traversal-safe
  extraction, argv-only execution, capability transparency.

## Project layout

```
noxs/
├── app/                    Android application (UI, runtime, native PTY)
│   └── src/main/cpp/       noxs-pty.c — forkpty/execve JNI bridge
├── terminal-emulator/      Clean-room VT/xterm engine (pure Kotlin, tested)
├── terminal-view/          Android terminal View + renderer + extra keys
├── noxs-shared/            Security core (checksums, TarGuard, users, quotas)
├── rust/                   noxs-core — Rust performance core (checksum, archive,
│                           tasks, security; stable FFI)
├── nx-pkg/                 Official package sources (tree · nxinfo · nxfetch)
├── nx-installer/           Verified setup installer for the nx runtime
├── linux-runtime/          bootstrap manifests, rootfs overlay, noxs CLI,
│                           nx package CLI + AI agent runtime,
│                           service/socket/process managers
├── sandbox/                sandbox docs (filesystem, users, permissions, sessions)
├── packages/               apt config, manifest schema, repository references
├── scripts/                bootstrap.sh · build-rootfs.sh · package.sh · test.sh
├── docs/                   ARCHITECTURE · BOOTSTRAP · ACTIVITY-CENTER · TERMINAL-UX · SECURITY ·
│                           TESTING · UNIX-SOCKETS · PACKAGES · NX-API · PERMISSION-CENTER ·
│                           WEB-BROWSER · RUST-CORE · NX-INSTALLER · AI-AGENT · FAQ
├── .github/workflows/      android-ci.yml (build + validate + rust + release)
│                           installer.yml (nx-installer release assets)
└── LICENSE, NOTICE.md      Apache-2.0
```

## Build

Requirements: JDK 17, Android SDK (platform 34) + NDK 26.3 + CMake 3.22.1.

```bash
scripts/bootstrap.sh          # verify toolchain, provision wrapper
./gradlew assembleDebug       # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest   # JVM tests (security core, VT engine, launcher)
scripts/test.sh               # full validation incl. manifest + sync checks
./gradlew assembleRelease     # set NOXS_KEYSTORE_* env vars for signing
```

CI builds everything automatically (see `.github/workflows/android-ci.yml`).

## First run

1. Open Noxs → **Set up Linux environment**
2. Set the `noxs` user password (used by `sudo` inside the sandbox)
3. Bootstrap downloads + verifies Debian 12 + proot (ARM64 prioritized)
4. Terminal opens: `noxs@android:~$`

Everything after bootstrap works offline.

## Background Activity Center

Sessions, commands, services and code-server keep running behind the
foreground service while you use other apps. A single live notification shows
what is running (with progress) and offers Open / Stop; the in-app Activity
Center and floating status panel give full control. `exit` closes only the
current shell — Noxs shuts down only when the last shell exits with no
background work left. Details: [docs/ACTIVITY-CENTER.md](docs/ACTIVITY-CENTER.md).

## Terminal UX

Real terminal behavior, real gestures: setup runs as live long-running
operations with an in-place spinner and a truthful monotonic elapsed timer;
pinch-to-zoom (10–28 sp), Smart/History scroll modes with a "↓ N new lines"
indicator, bounded resizable scrollback (10,000 lines default), full-document
search, a compact toolbar and a complete Settings → Terminal surface
(Appearance / Interaction / Scrolling / Behavior / Advanced). All settings
apply live — no shell restart. Details: [docs/TERMINAL-UX.md](docs/TERMINAL-UX.md).

## NXEditor — code editor

NXEditor is a built-in native code editor (sidebar → **Editor**) powered by
the same engine Squircle CE embeds: [sora-editor](https://github.com/Rosemoe/sora-editor)
(`io.github.rosemoe:editor`, pinned to Squircle's `0.24.3`) with TextMate
syntax highlighting for 24 languages (Python, JavaScript/TypeScript, Kotlin,
Java, C/C++, Go, Rust, Shell, YAML, Markdown, HTML/CSS and more).

- **Workspace = your terminal.** The editor opens at the active terminal
  session's working directory, tracked live via OSC 7 shell integration
  (package system v11 refreshes the shell profile of existing environments
  automatically). `Editor` in the sidebar opens the current directory —
  no fixed paths anywhere.
- **Real filesystem.** Read, save (with fsync), create, rename and delete
  files and folders directly in the Debian rootfs; saved files are visible to
  terminal commands immediately. All paths are validated and
  canonicalized natively (no shell concatenation, no traversal, symlink
  escapes rejected); outbound symlinks are never followed by deletes.
- **Tabs + safety.** Up to 12 open files with unsaved-change markers, an
  unsaved-changes guard on back navigation, external-change detection
  (mtime/size) with reload/overwrite choice, and a 2 MB per-file editor cap.
- **Open in Terminal.** One tap creates a NEW terminal session with `proot -w`
  at the current workspace — existing sessions are never disturbed.
- **Why native:** sora-editor is a native Android View and cannot render
  inside the WebView-based Plugin Window, so NXEditor ships as a built-in
  screen in the existing navigation (documented architectural decision).

Attribution: grammar/theme assets are curated from
[Squircle CE](https://github.com/massivemadness/Squircle-CE) (Apache-2.0);
the sora-editor engine is LGPL-2.1. Both are credited in Settings → About.

## Security posture

Noxs runs entirely inside Android's app sandbox. It does not modify the
system, does not bypass SELinux or verified boot, and `sudo` manages the Noxs
environment only. Details: [docs/SECURITY.md](docs/SECURITY.md) ·
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [NOTICE.md](NOTICE.md).

## License

Apache-2.0 — original implementation, no third-party branding
redistributed. Runtime artifacts (Debian rootfs, proot, code-server) are
fetched from their official sources under their own licenses. Bundled
editor components carry their own licenses: sora-editor (LGPL-2.1) and
Squircle CE grammar/theme assets (Apache-2.0) — see NOTICE.md.
