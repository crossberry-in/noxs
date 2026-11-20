# Docker support — the honest model

Noxs never fakes container functionality. Every reported Docker state
corresponds to a real probe result inside the Ubuntu 24.04 LTS userspace.

## Two clearly separated capabilities

| Capability | What it is | How Noxs reports it |
|---|---|---|
| **Docker CLI** | The `docker` client binary installed inside Ubuntu (`apt install docker.io`) | `docker --version` executed for real |
| **Docker Engine** | A live dockerd/containerd backend able to run containers | `docker info` executed for real — success only when the daemon answers |

Both are probed on demand:

- CLI missing → `Docker CLI: not installed` + the real install command.
- CLI present, `docker info` fails → `Docker CLI: installed`,
  `Docker daemon: unavailable` plus the **real** error line
  (e.g. `Cannot connect to the Docker daemon at unix:///var/run/docker.sock`).
- CLI present, `docker info` succeeds → `Docker daemon: running` + the real
  storage driver.

The daemon can start in **compatibility mode** (`dockerd --iptables=false
--bridge=none`) when Android restricts the kernel networking controls; that
mode is reported honestly (`Mode: compatibility`), never hidden.

## Diagnostics: `nx docker doctor`

`nx docker doctor [--json]` runs the full offline diagnostic inside the
sandbox and prints PASS/WARN/FAIL rows:

- **RootFS integrity** — `/usr/bin/clear`, `/usr/bin/sh`, the FHS links
  (`/bin → usr/bin`, `/lib64`) and the aarch64 ELF loader chain
  (`/lib64/ld-linux-aarch64.so.1`), plus the Noxs `sudo`/`su` shims.
- **Docker CLI** — presence + real `--version` output.
- **Docker daemon** — real `docker info`, socket state, Noxs mode, and the
  classified failure reason from the actual daemon log (networking /
  cgroups / storage / kernel — all reported as *limitations*, never as
  "installation corrupted").
- **DNS + registry** — `getent hosts ports.ubuntu.com`,
  `getent hosts registry-1.docker.io` and a real HTTPS probe of
  `https://registry-1.docker.io/v2/` (401 is the healthy answer).
- **Kernel capabilities** — cgroup controllers, `unshare -m`, iptables/nft
  tooling. Missing capabilities are WARN rows (expected Android/proot
  restrictions), not failures.
- **Storage** — the real storage driver from `docker info` (`vfs` is a WARN
  with its explanation), `/var/lib/docker/tmp` presence.
- **BuildKit/buildx** — real `docker buildx version`, `docker buildx ls`
  drivers, and the honest proot limitation for the `docker-container`
  builder (see below).
- **Architecture** — `uname -m` plus binfmt_misc availability (cross-arch
  emulation) reported as facts.

`--json` emits ONE machine-readable object on stdout (the human rows move to
stderr) — the Android Diagnostics screen consumes exactly that output.

## Safe recovery: `nx docker repair --check`

- `nx docker repair --check` is a **dry run**: it lists what WOULD be
  repaired and changes nothing.
- `nx docker repair` applies only the safe guest-side repairs after explicit
  confirmation (`--yes` from the app counts as the explicit confirm):
  FHS/loader link restoration, `dpkg --configure -a` for interrupted
  packages, and `apt-get install --reinstall ncurses-bin` when
  `/usr/bin/clear` is missing.
- The Noxs `sudo`/`su` shims are app-owned: doctor points to
  **Diagnostics → Rootfs repair** in the Android app, which rewrites them
  from the same source the installer uses.
- **Docker storage, images and containers are never deleted** — not by the
  doctor, not by the repair, not by any app button.

## RootFS integrity — why system executables stopped vanishing

Earlier Noxs versions could end up with `/usr/bin/docker`,
`/usr/local/bin/sudo` or `/usr/bin/clear` answering **"No such file or
directory"**. Root causes (all fixed, with regression tests):

1. **In-place extraction** could be interrupted mid-archive, leaving
   truncated files and 0-byte placeholder FILES where symlinks belong — a
   0-byte `/lib64` or `/usr/bin/sh` makes every exec fail with ENOENT.
   Now: post-extract integrity gate (`RootfsIntegrity`), placeholder-free
   symlink fallback, and a session-start repair pass.
2. **`ensureHealthyRootfs` re-extracted the base archive over a live
   rootfs**, resetting the dpkg database under user-installed packages.
   Now: structural damage re-extracts; missing executables are restored by
   extracting ONLY those members from the cached archive.
3. **`configure()` failures were swallowed** before `installSuAndSudo` ran,
   leaving `/usr/local/bin/sudo` missing. Now the shims are verified and
   restored after every configure pass.
4. **NX packages could overwrite/remove system paths** (`tar -C /` members
   under `usr/bin/…`, `nx remove` scope `usr/*`). Now both the tarball
   member check and the removal scope refuse `bin/`, `sbin/`, `usr/bin/`,
   `usr/sbin/`, `lib/`, `lib64/`, `usr/lib/`, `usr/lib64/` — packages
   install under `usr/local/` or `opt/` (FHS).

## BuildKit and buildx under proot

The **`docker-container` builder cannot run inside proot**: it needs
privileged networking (bridge + iptables) that Android does not grant to
app processes. Doctor reports this as a WARN with the pointer to remote
builds — it is a runtime limitation, not a bug, and Noxs never deletes
Docker data to "fix" it.

### Optional remote-build workflow (GitHub Actions)

Build multi-arch or BuildKit-heavy images from a Noxs package project by
pushing a build context and letting GitHub Actions run the real builder.
Minimal workflow (`.github/workflows/image.yml` in your package repo):

```yaml
name: image
on:
  workflow_dispatch:
  push:
    branches: [main]
jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-qemu-action@v3   # arm64 + amd64
      - uses: docker/setup-buildx-action@v3 # real docker-container builder
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - uses: docker/build-push-action@v6
        with:
          context: .
          platforms: linux/amd64,linux/arm64
          push: true
          tags: ghcr.io/${{ github.repository }}:latest
```

From Noxs: `nx pkg init` already ships a `pkg.yml` release workflow of the
same shape; add the `image.yml` above for container images. The build runs
on GitHub's runners with full kernel features — the device only needs git
push (docs/PACKAGES.md).

## What Noxs will never do

- Fake `/var/run/docker.sock`
- Ship or impersonate a fake `dockerd`
- Report success for `docker run` that did not really exit 0
- Remove kernel/package locks to force the daemon
- Delete Docker storage, images or containers as part of a "repair"
- Claim full Docker support when the host kernel does not provide it

If a device/runtime provides the required kernel privileges, the probe
automatically reports `Docker daemon: running` and real container work
(`docker run --rm hello-world` via `noxs docker test`) becomes possible
through the same honest path.
