# Ubuntu-First Architecture — Noxs × Ubuntu 24.04 LTS

This document maps every layer of the Noxs Android application to its
Ubuntu 24.04 LTS (Noble Numbat) role, and records where Debian was removed.

## 1. Layer map

| Layer | Component | Ubuntu role |
|-------|-----------|-------------|
| Android app | Kotlin app (minSdk 24, targetSdk 34) | Unchanged — hosts all managers |
| Bootstrap planner | `NoxsInstaller`, `InstallerPlan` | Downloads + verifies the pinned ubuntu-base rootfs (staged mirrors, SHA-256, TarGuard) |
| Rootfs pinning | `bootstrap/<arch>/bootstrap.manifest` | Official cdimage.ubuntu.com artifacts for arm64 (arm64-v8a), armhf (armeabi-v7a), amd64 (x86_64) |
| Extraction | `RootfsExtractor` (TarGuard) | Unchanged — generic tar safety |
| Userspace config | `RootfsConfigurator` | Writes profile.d, motd, DNS, `noxs.sources` (arch-aware), `noxs` user, su/sudo wrappers, CLI installers |
| APT bootstrap | `NoxsAptBootstrapper`, `AptBootstrapPolicy` | Signed HTTP → CA + `ubuntu-keyring` install → verified HTTPS on ports.ubuntu.com / archive.ubuntu.com |
| APT repair | `NoxsAptSetup`, `nx cert-fix` | Same repair chain for existing installs (Ubuntu sources self-heal) |
| Sessions | `NoxsSessionManager`, `ProotLauncher` | proot -0 fake-root into Ubuntu; `noxs@ubuntu:~$` prompt for non-root sessions |
| Terminal | terminal-emulator module | Unchanged |
| Package system | `nx pkg` (`NoxsNxPkgManager` + mirrors) | `pkgm_detect_os` → `ubuntu`; installs through NATIVE apt; doctor validates Ubuntu identity/repos/keyring |
| CLI | `noxs` (`NoxsCliTemplate`), `nx` (`NoxsNxTemplate`) | `noxs status`, `noxs update`, `noxs ubuntu status`; Ubuntu identity in `noxs info`/docker |
| Environment registry | `NoxsEnvironmentManager`, `UbuntuProvider` | ONE provider (`ubuntu`/`noble`); legacy Debian imports are read-only migration sources |
| Migration | `NoxsMigrationManager` | Marker classification + user-data backup plan (spec §9) |
| Documents provider | `NoxsDocumentsProvider` | Root summary "Ubuntu 24.04 guest files" |
| NoxOS emulator | `NoxOsEmulatorManager` | `qemu-system-arm` apt-installed from Ubuntu noble (QEMU 8.2.x); VNC/TCP flow unchanged |
| Code server / Docker / VPN / Plugin store | nx templates | Unchanged functionality; docker.io now from Ubuntu repos |

## 2. Rootfs pinning and integrity

```
scripts/build-rootfs.sh
  ├─ arch map: arm64-v8a→arm64, armeabi-v7a→armhf, x86_64→amd64
  ├─ download ubuntu-base-24.04.5-base-<arch>.tar.gz (cdimage.ubuntu.com)
  ├─ download official SHA256SUMS
  ├─ compute local SHA-256
  └─ REFUSE unless local == official     ← double verification
```

Pinned values (2026-11-07, verified by download + cross-check):

| arch | sha256 | bytes |
|------|--------|-------|
| arm64 | `a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2` | 29,936,675 |
| armhf | `4fcee4d278f1c5232e085a021a85e4c6cef3853557a88d98ff380b5e5d5841bb` | 27,120,839 |
| amd64 | `e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86` | 30,028,293 |

Release APKs embed a pre-built offline bundle (`rootfs.bin`, built by
`scripts/build-offline-rootfs.sh` on `ubuntu:24.04`) so first launch works
offline; the network bootstrap is the fallback.

## 3. APT source generation (arch-aware)

`RootfsConfigurator.aptSources(useHttps, arch)`:

- `x86_64` → `archive.ubuntu.com/ubuntu` + `security.ubuntu.com/ubuntu`
- otherwise → `ports.ubuntu.com/ubuntu-ports` (both main and security pockets)

Suites `noble noble-updates noble-backports` + `noble-security`; components
`main restricted universe multiverse`; `Signed-By`
`/usr/share/keyrings/ubuntu-archive-keyring.gpg` (ships inside the genuine
ubuntu-base image; the `ubuntu-keyring` package is installed there — verified
from the extracted rootfs).

`nx cert-fix` regenerates the same definition with RUNTIME arch detection
(`dpkg --print-architecture`), so self-heal never writes a wrong host.

## 4. What "no Debian" means concretely

- No Debian rootfs URL, SHA or manifest anywhere (`scripts/build-rootfs.sh`
  has no Debian path; `packages/repositories/bookworm.sources` removed).
- No Debian apt sources in the guest: `configureAptSources` deletes every
  `.list`/`.sources` file before writing `noxs.sources`.
- No Debian/Ubuntu selector: `ProviderRegistry.createAll()` returns exactly
  one provider (`ubuntu`). The UI never offers a distribution choice.
- No fake Ubuntu: the rootfs IS Ubuntu; `os-release` is untouched.
- Kept intentionally (correct on Ubuntu, not "Debian retention"):
  - `DEBIAN_FRONTEND` / `DEBIAN_PRIORITY` environment variables — apt's own
    API names, identical on Ubuntu.
  - `pkgm_detect_os` keeps a `debian` branch + the `debian-tor` service-user
    fallback — dead code on a Ubuntu-only guest but harmless; the doctor
    FAILs on non-Ubuntu os-release by design.
  - Dev-host CI verification tooling (`tools/emulator/qemu-deps-bookworm-*.json`,
    `resolve_qemu_closure.py`) pins the M1 QEMU-gate container — it is
    verification infrastructure, NOT the app environment. The app guest now
    apt-installs QEMU 8.2 from noble; the M1 flag set (`-M virt`, virtio-mmio,
    virtio-gpu) is stable across 7.2 → 8.2 but the noble binary itself is not
    yet device-verified (see UBUNTU-TEST-REPORT.md).

## 5. Init / systemd

Ubuntu's systemd is NOT used: proot runs no init. Startup is
`/etc/profile.d/*.sh` + the Noxs service manager (`noxs-service`) +
`noxs-links.sh` FHS repair. `noxs-setup-storage` wires SAF access. Resource
quotas come from `/etc/noxs/resources.conf` (ulimit in profile).

## 6. Storage of truth (CI parity)

Kotlin templates are the single source of truth; CI regenerates/diff-checks
the canonical mirrors:

```
NoxsCliTemplate.CLI        → linux-runtime/launcher/noxs-cli      (sync-cli.sh)
NoxsNx*.kt (60 mirrors)    → linux-runtime/nx/*                   (sync_nx.py)
RootfsConfigurator strings ↔ linux-runtime/rootfs/overlay/*
```

`scripts/test.sh --manifests --sync-check --scripts --nx` must stay green
(all four verified locally for this migration).

## 7. Failure surfaces (honest)

- Manifest SHA mismatch → install aborts (no unsigned rootfs ever extracts).
- `apt-get update` failure during bootstrap is NON-FATAL by design: the shell
  still opens; `NoxsAptSetup` + `nx cert-fix` keep repairing in the background.
- Legacy Debian markers never trigger an automatic reinstall; migration is
  always explicit.
