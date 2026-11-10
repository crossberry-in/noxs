# Docker support — the honest model

Noxs never fakes container functionality. Every reported Docker state
corresponds to a real probe result inside the Debian 12 userspace.

## Two clearly separated capabilities

| Capability | What it is | How Noxs reports it |
|---|---|---|
| **Docker CLI** | The `docker` client binary installed inside Debian (`apt install docker.io docker-cli`) | `docker --version` executed for real |
| **Docker Engine** | A live dockerd/containerd backend able to run containers | `docker info` executed for real — success only when the daemon answers |

Both are probed on demand (Diagnostics screen → "docker (live probe)"):

- CLI missing → `Docker CLI: UNAVAILABLE` + the real install command.
- CLI present, `docker info` fails → `Docker CLI: AVAILABLE`,
  `Docker Engine: UNAVAILABLE` plus the **real** error line
  (e.g. `Cannot connect to the Docker daemon at unix:///var/run/docker.sock`).
- CLI present, `docker info` succeeds → `Docker Engine: AVAILABLE`.

## Why Engine is usually UNAVAILABLE on Android

Docker Engine requires Linux kernel features that a stock Android
environment does not grant to app processes: cgroup hierarchy writes,
network namespaces/bridges, mount namespaces, overlayfs backing and
iptables hooks. Noxs runs Debian under proot (syscall translation, no
kernel privileges), so on non-rooted devices the Engine genuinely cannot
start. Noxs states this as a capability limitation instead of pretending.

## What Noxs will never do

- Fake `/var/run/docker.sock`
- Ship or impersonate a fake `dockerd`
- Report success for `docker run` that did not really exit 0
- Remove kernel/package locks to force the daemon
- Repeatedly relaunch a daemon the environment cannot host

If a future device/runtime provides the required kernel privileges, the
probe automatically reports `Docker Engine: AVAILABLE` and real container
work (`docker run --rm hello-world`) becomes possible through the same
Debian CLI — no Noxs changes required to detect it.

## Package-manager safety around Docker installs

`apt install docker.io` is a normal Debian transaction and is covered by
the Noxs package-manager guard (`/etc/profile.d/noxs-pkg-guard.sh` +
app-side `NoxsPkgTransaction`): only one apt/dpkg transaction runs at a
time, concurrent attempts get a clear "package manager is busy" message,
and dpkg locks are never deleted. Interrupted operations recover through
`dpkg --configure -a` / `apt --fix-broken install` — executed by Noxs only
when no package process is running (Setup console → Repair).
