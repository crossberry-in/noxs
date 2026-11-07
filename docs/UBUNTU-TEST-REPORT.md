# Ubuntu-First Test Report

**Date**: 2026-11-07
**Scope**: Ubuntu-first migration (Debian → Ubuntu 24.04 LTS only).
**Honesty rule**: everything below is labeled by where it actually ran.
**No Android-device verification has been performed for this migration.**

## 1. Environment classes

| Class | Where | What |
|-------|-------|------|
| DEV-HOST (this sandbox) | Debian-based Linux container, amd64, JDK 21 + standalone Kotlin 2.2.21 | Static verification, JVM unit tests, real rootfs download/inspect, shell suites |
| CI (GitHub Actions) | `ubuntu-latest` runners | Runs after push: Gradle unit tests, rootfs validation suites, shellcheck, Rust core |
| ANDROID DEVICE | Physical arm64 phone | **NOT PERFORMED** — pending the M9 automation milestone |

## 2. Verified on DEV-HOST (real evidence)

### 2.1 Genuine Ubuntu rootfs (real download + inspect)

- Downloaded `ubuntu-base-24.04.5-base-arm64.tar.gz` (29,936,675 bytes) from
  `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/` — **SHA-256
  `a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2` matches
  the official `SHA256SUMS`** (cross-checked, not just self-computed).
- Same procedure for `amd64`
  (`e77b6f10…47a0c86`, 30,028,293 bytes) and `armhf`
  (`4fcee4d2…d5841bb`, 27,120,839 bytes) — both match official sums.
- Extracted the arm64 rootfs and verified from the REAL files:
  - `/etc/os-release`: `ID=ubuntu`, `VERSION_ID=24.04`,
    `VERSION_CODENAME=noble`, `PRETTY_NAME="Ubuntu 24.04.5 LTS"`
  - `/usr/share/keyrings/ubuntu-archive-keyring.gpg` present (OpenPGP RSA-4096)
  - `ubuntu-keyring` dpkg status `install ok installed`
  - stock `ubuntu.sources` uses `ports.ubuntu.com/ubuntu-ports` (arm64) /
    `archive.ubuntu.com` (amd64) — matches the app's generated sources
  - `etc/resolv.conf` ships empty → app DNS write path required (present)
  - `/bin -> usr/bin` merged-usr symlink present; extracted size ≈ 96 MB
- amd64 stock sources confirmed `archive.ubuntu.com/ubuntu` +
  `security.ubuntu.com/ubuntu` → app's x86_64 sources branch is correct.

### 2.2 Repo validation suites (repo tooling, this sandbox)

- `scripts/test.sh --manifests` → PASS (all 3 Ubuntu manifests schema-valid)
- `scripts/test.sh --sync-check` → PASS (noxs-cli + 60 nx mirrors match their
  Kotlin templates; manifest copies identical)
- `scripts/test.sh --scripts` → PASS (bash -n over all shell tooling;
  shellcheck not installed locally — CI runs it)
- `scripts/test_nx_functions.sh` → **PASS** end-to-end, including
  `nx pkg doctor` with the new Ubuntu identity/repository/keyring checks
  (fixture: `os.ubuntu24` + `noble.sources` + keyring fixture)

### 2.3 JVM unit tests (CI-parity harness)

Standalone Kotlin 2.2.21 + junit 4.13.2 + org.json 20240303 +
kotlinx-coroutines 1.8.1 (same versions as CI). Compiled set: noxs-shared +
runtime + environments (+model/providers) + emulator (non-UI) + templates,
with minimal Android stubs (Context/AssetManager/Build/Process/R).

**Result: OK (154 tests)** — including the updated contracts:

- `RootfsAptSourcesTest` — noble/ports.ubuntu.com/arm64, archive.ubuntu.com
  for x86_64, ubuntu-archive-keyring, no `debian.org`/`bookworm` output
- `AptBootstrapCommandsTest` / `AptRetryLoopTest` / `AptRetryPolicyTest` /
  `ConnectivityCheckTest` — ubuntu probe hosts, `ubuntu-keyring` package argv
- `InstallerPlanTest` — cdimage URL uniqueness, github-raw mirror behavior
- `SetupConsoleTextTest` / `SetupProgressSanitizerTest` / `SetupOpTrackerTest`
  — "Ubuntu 24.04 LTS" console texts
- `NoxsPkgManagerTest` — `ubuntu-official` source labels
- `NoxsCertFixTest` — `ubuntu-keyring` + noble security suite
- `NoxsMigrationManagerTest` (new) — install classification, honest backup
  planning, path containment
- `ProviderRegistryTest` — exactly one `ubuntu` provider, no `debian` id,
  pinned download estimate, noble variant
- `NoxsWelcomeTest` — Ubuntu stub os-release render; hermetic NO_COLOR
- `SetupProgressTrackerTest` — emulator setup percent tracker (from M2)
- Plus the pre-existing suites that must not regress (dpkg repair security,
  URL guard, cert-fix, package system, activity center, etc.)

### 2.4 Generator ⇄ mirror parity for changed sources

- `scripts/sync-cli.sh` regenerated `linux-runtime/launcher/noxs-cli` from
  `NoxsCliTemplate` (Ubuntu identity + `noxs status`/`update`/`ubuntu status`)
- `scripts/sync_nx.py --write` regenerated 60 mirrors; `--check` PASS

## 3. Pending CI verification (after push, not yet run)

- Gradle `app:testDebugUnitTest` full suite (including Android-bound tests
  that cannot run in the standalone harness: SessionManager, SocketServer,
  DockerSetup, SetupSession console, PermissionCenter, WebWindow, DocumentsProvider)
- `rootfs-validation` job (shellcheck over changed shell files)
- Rust core job (no changes; expected green)
- Android build job (APK assembly with the new manifests/strings)

## 4. NOT verified — requires an Android device (honest gaps)

Per the feature-classification rule this migration is
**Implemented with limitations** until the following run on a real arm64 device:

1. Fresh-install E2E: APK install → welcome → Ubuntu rootfs download/verify →
   extraction → `noxs@ubuntu:~$` prompt (spec acceptance #1–#3).
2. Real APT on device: `apt-get update` against ports.ubuntu.com over proot;
   `apt-get install -y tree`; `tree --version` (spec acceptance #4–#5).
3. `ubuntu-base` runtime behavior under proot on Android: glibc/localed
   assumptions, `dpkg --print-architecture`, DNS with the app's resolv.conf.
4. Legacy-Debian migration flow on device: marker detection on a real
   pre-upgrade install, backup tar through proot, Ubuntu coexistence.
5. NoxOS emulator with noble's QEMU 8.2: the M1 gate verified the flag set on
   QEMU 7.2.22; the noble binary must be re-verified on device (VNC handshake,
   boot to framebuffer).
6. Offline bundle build in CI (`build-offline-rootfs.sh` on `ubuntu:24.04`) —
   CI-only path; first release APK with the Ubuntu bundle will prove it.

## 5. Summary

| Area | Status |
|------|--------|
| Rootfs source + integrity (3 arches) | **Verified on dev-host (real download, official SHA256SUMS)** |
| App generators (sources/user/prompt/CLI/nx/doctor) | **Verified (154 JVM tests + repo suites)** |
| Canonical mirror parity | **Verified (sync-check)** |
| CI pipelines | Pending push (Gradle + shellcheck + release jobs) |
| Android device behavior | **NOT verified — explicitly pending M9** |
| Debian removal from app install path | **Complete (manifests, configurator, bootstrapper, providers, UI, CLI, docs)** |
