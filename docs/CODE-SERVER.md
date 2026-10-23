# Noxs Editor — code-server (VS Code Server) integration

The Noxs **Editor** (sidebar entry) runs the real
[code-server](https://github.com/coder/code-server) — VS Code in the
browser — inside the Noxs Debian guest and renders it in an Android
WebView. It replaces the former native sora-editor/Squircle screen
(removed in v0.15.0).

This document covers the installation, lifecycle, security model,
workspace behavior and troubleshooting.

---

## 1. Installation

### Commands

```
noxs code install            # download + install the latest release
noxs code install --force    # re-install even if the same version exists
noxs code install --version 4.89.1   # pin a specific release
nx code install              # alias — same command set via nx
```

`noxs code` also exposes `start`, `stop`, `restart`, `status [--json]`
and `logs [lines]`; every command works identically through the existing
`nx` alias architecture (`nx code …` forwards to `noxs code …`).

### What install does

1. **Architecture detection** — `uname -m`:
   `aarch64|arm64 → linux-arm64`, `x86_64|amd64 → linux-amd64`; anything
   else fails with a clear error (code-server publishes ARM64/AMD64 only).
2. **Environment check** — requires a Debian-based guest (`/etc/os-release`)
   and either `curl` or `wget` (the app's bootstrap provides curl; if it is
   missing the error names the exact remediation).
3. **Version resolution** — the GitHub API for `coder/code-server`
   releases, falling back to a pinned release when the API is unreachable;
   the version string is validated against a strict charset before use.
4. **Download** — official release tarball from
   `github.com/coder/code-server/releases`, retried automatically;
   interrupted downloads leave only staging directories that the next
   attempt sweeps.
5. **Verification** — sha256 against the release `SHASUMS.txt` when
   upstream publishes one; otherwise the digest of the archive actually
   received is recorded. A failed checksum discards the archive.
6. **Extraction + atomic-ish swap** — the tarball must contain the
   expected layout (`bin/code-server`), or the install refuses.
7. **Smoke test** — `code-server --version` must actually run. An
   incompatible release (glibc/lib mismatch) fails here with a clear
   error instead of a false success.

The install lands in **persistent guest storage** (`/opt/code-server`
inside the rootfs, plus user data in `~/.noxs/code`) and is never
re-downloaded on every launch. No device root is required at any point.

### Runtime requirements

- Android ARM64 (aarch64) or x86_64 device
- A set-up Noxs Debian 12 environment
- ~700 MB free storage for the download + extracted tree
- Network access for the one-time download (GitHub releases)

---

## 2. Server lifecycle

### Commands

```
noxs code start [--workspace DIR]   # start in the background (terminal use)
noxs code start --foreground ...    # managed mode (used by the editor)
noxs code stop                      # graceful SIGTERM, then SIGKILL
noxs code restart [--workspace DIR]
noxs code status [--json]           # human text, or flat JSON for tooling
noxs code logs [lines]              # credential lines are filtered out
```

### Process model

The Noxs guest has no init system and no systemd. code-server runs as an
**ordinary guest process**:

- From a terminal, `noxs code start` nohups the server inside your
  session's proot tree and writes its PID to
  `/var/run/noxs/host/code-server.pid` (a host-visible bind).
- The Editor screen uses **managed mode**: `noxs code start --foreground`
  runs under a dedicated long-lived proot process owned by the Noxs
  `CodeServerManager`. One-shot execs die with proot's `--kill-on-exit`,
  which is exactly why the manager owns the wrapper process instead.
- The manager stops the server gracefully through the same guest CLI
  (`noxs code stop`), then reaps its own wrapper. Unrelated Node.js or
  guest processes are never touched — the PID is verified against
  `/proc/<pid>/cmdline` before any signal is sent.

### State, crash and conflict handling

- **Duplicate start prevention** — both the CLI and the manager detect a
  live server (PID file + `/proc` identity + HTTP probe) and reuse it.
- **Stale PID metadata** — after a crash, reboot or PID reuse, the
  metadata is recognized as stale, cleaned up, and reported honestly
  (`installed, stopped`), never as "running".
- **Port conflicts** — before every start the CLI checks
  `/proc/net/tcp(6)`; if the configured port is taken by another process
  the server moves to the next free port (up to +10) and the choice is
  persisted in the config.
- **Readiness** — "started" is only reported after an actual HTTP request
  against the loopback succeeds; the Android manager independently polls
  `http://127.0.0.1:<port>/` (any HTTP status counts) before opening the
  editor. Startup timeout: 60 s (managed) / 45 s (CLI), with the log tail
  surfaced on failure.
- **Crashes while running** — the editor surfaces a retryable failure
  state when the main frame can no longer be served.

### Background execution

The server runs while the Noxs app process runs; the existing Noxs
foreground service (`dataSync`) is what keeps Android from reclaiming it.
If Android kills the app, the server dies with it — the editor detects
this on return and offers a restart. No terminal session is kept alive
just for the editor.

---

## 3. Security model

- **Loopback only.** code-server binds to `127.0.0.1:<port>`
  (`bind-addr` in the config). It is never exposed to the LAN or the
  internet, and Noxs never opens tunnels.
- **Authentication always on.** `auth: password` with a random 24-byte
  base64 credential generated on first start. Authentication is never
  disabled for WebView convenience.
- **Credential storage.** The password lives in
  `~/.noxs/code/config.yaml` (mode `600`) inside the app-private rootfs —
  the same place upstream code-server stores its own config. It is never
  written to preferences, never echoed to stdout, and `noxs code logs`
  filters credential lines; the Android log tail does the same.
- **Cleartext policy.** The app manifest forbids cleartext HTTP via a
  network security config that permits it **only** for `127.0.0.1` and
  `localhost` (the guest shares the device network namespace, so this is
  precisely the editor server).
- **WebView navigation policy.** Main-frame navigation is restricted to
  the local server origin (`http://127.0.0.1:<port>`). External http(s)
  links open in the guarded Noxs web window (`NoxsUrlGuard` re-validates
  them); everything else (javascript:, file:, data:, intents, …) is
  blocked. No JavaScript bridge is exposed to the editor content.
- **No arbitrary shell.** Workspace paths reach the guest as quoted argv
  elements (`ShellUtil.commandLine`), never concatenated into shell
  strings; the guest CLI validates the workspace directory itself.

---

## 4. Workspace path behavior

The editor opens at the **active terminal session's current working
directory**, tracked live by the shell's OSC 7 reporting:

1. explicit workspace argument (if a caller resolved one),
2. the latest OSC 7 working directory of the first terminal session,
3. the session's default home (`/home/noxs`).

Every candidate is validated against the real guest filesystem before it
is used — a valid terminal directory is never silently replaced by
another path. Paths with spaces and Unicode are handled end to end
(they travel as argv, never through shell interpolation). Traversal
segments and control characters are rejected by
`CodeServerSupport.isValidGuestWorkspace`.

If the directory changes while the editor is open, the workspace stays
as opened; reopening the editor picks up the latest known directory.

---

## 5. Open in Terminal

The editor header has an **Open in Terminal** action. It creates a NEW
terminal session at the current workspace using the existing proot
launcher's working-directory support (`TerminalActivity.openAt` →
`proot -w <workspace>`), preserving all existing sessions. Double taps
are debounced; a missing workspace produces a clear message instead of a
broken session.

---

## 6. Editor screen

- Header: back, workspace directory (monospace), server status chip
  (checking / installing / starting / opening / ready / failed /
  stopped), Open in Terminal, Reload, and a menu with **Restart server**,
  **Stop server** and **Server log**.
- Loading states are real phases: *Checking installation → Installing
  (streamed progress lines) → Starting server → Opening workspace →
  Ready*; failures show the error plus the last server log lines and a
  **Retry** action.
- Back navigation first walks in-editor history; leaving the editor
  never discards VS Code state (hot-exit backups are kept server-side).
- On activity recreation the WebView reattaches to the still-running
  server and restores its navigation state.

### Known Android WebView limitations

- The system WebView (Chromium) renders the workbench; very old WebView
  versions may degrade VS Code's UI. Android 9+ system WebViews are fine.
- Some VS Code keyboard shortcuts (browser-level ones like `Ctrl+N`) are
  reserved by the OS or the WebView and cannot reach the editor.
- The soft keyboard resizes the editor via `adjustResize`; on devices
  with aggressive gesture navigation the resize can lag a frame.
- Extension compatibility follows code-server's own support matrix
  (Open VSX); marketplace-only extensions are not available.
- If Android kills the app process, the managed server dies with it (see
  §2); VS Code restores unsaved work through its hot-exit backups on the
  next start.

---

## 7. Persistence and recovery

Persisted (all inside app-private storage):

- installation tree + version marker (`/opt/code-server`)
- server config incl. credential (`~/.noxs/code/config.yaml`, mode 600)
- server user data / extensions (`~/.noxs/code`)
- PID metadata (`/var/run/noxs/host/code-server.pid`)
- install progress log (`/var/run/noxs/host/code-install.log`)

Never persisted: the terminal's sudo password (unchanged Noxs policy),
and no new secret is added to Android preferences.

Recovery paths: app upgrade (installs and configs are untouched), failed
or interrupted install (staging swept, retry re-downloads), partial
archive (rejected by layout check + smoke test), insufficient storage
(pre-checked), WebView reload (reloads the SPA), stale PID (detected and
cleaned). **User workspace files are never touched by any recovery.**

---

## 8. Troubleshooting

| Symptom | What to do |
| --- | --- |
| `noxs code install` says curl/wget missing | `sudo apt install curl` (or fix certificates with `nx cert-fix`, then retry) |
| Download fails | Check connectivity; retry — interrupted staging is swept automatically |
| `checksum mismatch` | The archive was corrupted in transit; retry the install |
| Smoke test fails after install | `sudo apt install libatomic1 libsecret-1-0`, then `noxs code install --force` |
| `port 8080 is in use` | Automatic: the server moves to the next free port (message is printed) |
| Editor shows *failed* | Read the log excerpt on screen or run `noxs code logs`; **Retry** restarts the flow |
| Login page keeps appearing | The config may predate an install; `noxs code stop && noxs code start` regenerates readiness (credential unchanged); if still stuck, delete `~/.noxs/code/config.yaml` and restart |
| Server unreachable after leaving the app | Android may have killed the app process; reopen the editor and retry |

---

## 9. Licenses and attribution

- **code-server** — MIT, © Coder. Downloaded at install time from the
  official releases; never bundled with the APK.
- **Visual Studio Code** (workbench served by code-server) — MIT,
  © Microsoft.
- The former native editor stack (sora-editor LGPL-2.1, Squircle CE
  grammar assets Apache-2.0) was removed in v0.15.0.

See [NOTICE.md](https://github.com/crossberry-in/noxs/blob/main/NOTICE.md) and Settings → About.
