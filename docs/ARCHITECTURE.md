# Noxs — Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│ Android host (Linux kernel, SELinux enforcing)                   │
│  Noxs never modifies the system, never bypasses the sandbox.     │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │ Noxs application sandbox (app UID, app-private storage)    │  │
│  │                                                            │  │
│  │  app module (Kotlin)                                       │  │
│  │   ├── ui/            15 screens (terminal + 14 managers)   │  │
│  │   ├── runtime/       NoxsInstaller · ProotLauncher ·       │  │
│  │   │                  NoxsSessionManager · NoxsService ·    │  │
│  │   │                  NoxsSocketServer · NoxsResources      │  │
│  │   └── cpp/noxs-pty.c forkpty/execve bridge (JNI)           │  │
│  │                                                            │  │
│  │  terminal-view    TerminalView · renderer · extra keys     │  │
│  │  terminal-emulator clean-room VT/xterm engine (pure Kotlin)│  │
│  │  noxs-shared      security core: TarGuard, Checksum,       │  │
│  │                   PasswdDb, SocketPathValidator, quotas    │  │
│  │                                                            │  │
│  │  filesDir/noxs/  {rootfs/ bin/proot run/ cache/ tmp/}      │  │
│  │  ┌──────────────────────────────────────────────────────┐  │  │
│  │  │ Noxs runtime: proot -0 -r rootfs (ptrace-based       │  │  │
│  │  │ path virtualization — no namespaces, no root)        │  │  │
│  │  │  ┌────────────────────────────────────────────────┐  │  │  │
│  │  │  │ Debian 12 Bookworm userspace (arm64)           │  │  │  │
│  │  │  │  user noxs (/home/noxs) + password sudo        │  │  │  │
│  │  │  │  apt · bash · git · ssh · unix sockets         │  │  │  │
│  │  │  │  /run and /var/run — real FHS runtime state    │  │  │  │
│  │  │  │  code-server (Editor, 127.0.0.1 only)          │  │  │  │
│  │  │  └────────────────────────────────────────────────┘  │  │  │
│  │  └──────────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

## Modules

| Gradle module       | Responsibility                                                        |
|---------------------|-----------------------------------------------------------------------|
| `:app`              | Android app: UI, installer, proot launcher, services, native PTY      |
| `:terminal-emulator`| VT100/VT220/xterm-subset parser + buffer + session (pure JVM, tested) |
| `:terminal-view`    | Android rendering/input View, extra-keys bar                          |
| `:noxs-shared`      | Security core used by both sides (extraction, checksums, users, quota)|

## Key flows

**First launch** — `WelcomeActivity` → `SetupActivity` → `NoxsInstaller` runs the
11 documented steps (arch → storage → manifest → proot → rootfs → safe extract →
configure → user+password → /run init → apt init → first shell).

**Terminal session** — `NoxsSessionManager.createSession` → `ProotLauncher`
argv (unit tested) → `NativePty.create` (forkpty) → proot → `su -l noxs` →
bash reads `/etc/profile.d/noxs.sh` (env + quotas + FHS links).

Every PRoot invocation includes `--link2symlink`: Android app-private storage can deny
native hard-link creation, while dpkg uses a hard-link operation when saving its
status backup. PRoot emulates guest hard links inside the sandbox; this does not
grant Android root or change host files outside Noxs.

**Manager screens** — run one-shot commands inside the same sandbox via
`OneShotExecutor` (`proot … /bin/bash -c 'dpkg-query …'`) and parse results.

## Design rules

1. The emulator/parser is JVM-pure and unit tested against real sequences.
2. Every security-sensitive byte path (download → checksum → extract) is in
   `noxs-shared` with dedicated tests (traversal, bombs, special entries).
3. All external values in command lines go through `ShellUtil.quote`.
4. Capability detection reports honestly (Security/Diagnostics screens).
