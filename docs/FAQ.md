# FAQ

**Is Noxs based on another terminal app?**
No. Noxs is an original project (Apache-2.0) — clean-room architecture,
code, branding and assets. No third-party terminal code, branding, or
assets are used, and the engine differs too: Noxs runs an official
Debian 12 rootfs via proot instead of bionic-compiled packages in $PREFIX.

**Does Noxs root my phone?**
No, and it never tries. `sudo` controls the Noxs environment only — the
Security screen states this explicitly.

**Why proot instead of chroot?**
chroot requires root or special kernel privileges most devices don't grant
apps. proot achieves path virtualization via ptrace entirely in userland —
graceful by design, exactly the fallback the spec requires.

**Why is /var/run "real"?**
It is a real directory *inside the app-private rootfs*. proot maps `/var/run`
requests into `filesDir/noxs/rootfs/var/run`, so sockets behave normally while
Android's actual `/var/run` is never touched.

**Where does code-server come from?**
`noxs code install` points to the official MIT-licensed code-server release.
Noxs never bundles or redistributes VS Code or code-server binaries.

**Does it work offline?**
Yes, after the one-time bootstrap. Only package installation and code-server
download need the network.

**Which devices?**
Android 7.0+ (API 24). ARM64 is the priority target; x86_64 works for
emulators; armv7 is experimental.

**My server (python -m http.server, code-server, node …) stops responding
when I leave the app — why?**
The server runs inside Noxs; on Android it stays reachable only while the
Noxs foreground service is alive and the CPU is not dozing. Keep the Noxs
notification active, and enable Settings → Background → "Keep CPU awake
while running" — Noxs then holds a wake lock while anything is running so
`localhost:<port>` keeps working from the browser even on the lock screen.
If a process was killed anyway, the terminal shows
"[The process was killed by Android (SIGKILL)…]": on Android 12+ the
phantom-process monitor can terminate background child processes
regardless of what the app does. If you develop servers heavily, raise the
phantom limit once from a computer:
`adb shell device_config put activity_manager max_phantom_processes 2147483647`.
Also note that a server that binds and prints `0.0.0.0` is reachable at
`http://127.0.0.1:<port>` — Noxs rewrites links automatically.

**How do I install packages with `nx`?**
See docs/PACKAGES.md. Short version: `nx install <package>` for official
packages, `nx install -git @user/repo` for any GitHub repository, and
`nx pkg init` to create your own package with automatic GitHub Actions
releases. `nx` also accepts every `noxs` command (`nx docker install`).

**How do I reset everything?**
Storage → "Reset Linux environment", or clear the app's data from Android
Settings. Home files live inside the rootfs, so resets delete them — the
reset dialog warns first.
