# Ubuntu Setup — Noxs Ubuntu 24.04 LTS (Ubuntu-first)

Noxs installs **exactly one** Linux distribution: **Ubuntu 24.04 LTS (Noble
Numbat) ARM64**. There is no Debian download path, no distribution selector
and no Debian fallback anywhere in the installation flow (Ubuntu-first spec).

## What a fresh install does

On a fresh application installation the first-run flow is fully automatic —
the user never types installation shell commands:

| # | Step | What happens |
|---|------|--------------|
| 1 | Welcome | Noxs welcome screen (`WelcomeActivity`) |
| 2 | Install info | Ubuntu 24.04 LTS branding, storage requirements, ARM64 check |
| 3 | Architecture detect | `NoxsCapabilitiesArch.detect()` → `arm64-v8a` (also `armeabi-v7a`/`x86_64`) |
| 4 | Storage check | `InstallerPlan.downloadReserveBytes` / `extractionReserveBytes` reserves |
| 5 | Rootfs acquisition | Official `ubuntu-base-24.04.5-base-arm64.tar.gz` from `cdimage.ubuntu.com`, staged download + SHA-256 verify |
| 6 | Integrity verify | Pinned SHA-256 (`a91d5a93…f05914f2`) must match, else install aborts |
| 7 | Safe extraction | TarGuard extraction into app-private storage (traversal/device/setuid defense) |
| 8 | Userspace config | DNS, hosts, hostname, locale (`C.UTF-8`), `noxs` user, `noxs@ubuntu` prompt, official APT sources |
| 9 | noxs user + password | `/home/noxs`, password collected in the setup console (never stored) |
| 10 | Runtime state | `/run`, `/var/run/noxs`, logs, resource quotas |
| 11 | APT bootstrap | Signed HTTP update → `ca-certificates` + `ubuntu-keyring` install → verified HTTPS update (non-fatal; background repair continues) |
| 12 | Terminal | Ubuntu launches through proot → `noxs@ubuntu:~$` |

## The rootfs (genuine Ubuntu, pinned)

- **Source**: `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz`
  — the official Ubuntu-built base image (the content behind `ubuntu:24.04`
  minimal images). No re-root, no `/etc/os-release` edits.
- **Integrity**: SHA-256 pinned in `app/src/main/assets/bootstrap/<arch>/bootstrap.manifest`
  AND cross-checked against the official `SHA256SUMS` file by
  `scripts/build-rootfs.sh` at pin time.
- **Verified facts** (dev-host, 2026-11-07): `PRETTY_NAME="Ubuntu 24.04.5
  LTS"`, `VERSION_CODENAME=noble`, `ID=ubuntu`; `ubuntu-keyring` package
  installed; `ubuntu-archive-keyring.gpg` present at
  `/usr/share/keyrings/`; `resolv.conf` ships empty (Noxs writes DNS);
  `/bin → usr/bin` merged-usr symlink present; extracted size ≈ 96 MB.

## APT repositories (official Ubuntu only)

`RootfsConfigurator.configureAptSources` removes the stock
`ubuntu.sources` and installs a single deb822 file,
`/etc/apt/sources.list.d/noxs.sources`:

- **arm64 / armhf**: `ports.ubuntu.com/ubuntu-ports` — suites
  `noble`, `noble-updates`, `noble-backports`, `noble-security`;
  components `main restricted universe multiverse`;
  `Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg`.
- **amd64 (x86_64 builds)**: `archive.ubuntu.com/ubuntu` +
  `security.ubuntu.com/ubuntu`, same suites/components.

The reference copy lives at `packages/repositories/noble.sources`; the overlay
copy at `linux-runtime/rootfs/overlay/etc/apt/sources.list`. CI diff-checks
the app-side generator against its embedded templates (`scripts/test.sh
--sync-check`).

During the one-time CA bootstrap the same repositories are temporarily served
over HTTP (metadata is GPG-authenticated, so transport downgrade is safe);
`NoxsAptBootstrapper` installs `ca-certificates` + `ubuntu-keyring`, regenerates
the CA bundle, then switches to HTTPS and requires a verified `apt-get update`.

## Default terminal identity

```
noxs@ubuntu:~$
```

- `HOME=/home/noxs`, `USER=noxs`, `LANG=C.UTF-8`
- `PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin`
- A regular `noxs` Linux user (uid from `PasswdDb`, `sudo` group member).
- `sudo` inside Noxs manages ONLY the sandbox environment — it is not Android
  device root, and proot's fake-root (`proot -0`) is not Android root either.

## CLI

```
noxs help            command list
noxs status          environment health summary
noxs update          apt metadata refresh + upgrade (native APT)
noxs info            session/account/storage facts
noxs ubuntu status   Ubuntu identity: os-release, codename, arch, repos,
                     keyring, package index
noxs storage …       SAF storage commands
noxs code …          code-server lifecycle
noxs docker …        Docker lifecycle (docker.io from Ubuntu repos)
nx pkg doctor        validates Ubuntu identity, repositories, keyring, dpkg
                     database, certificates, download tools, free storage
nx pkg update        APT lists refresh
nx install tree      install a package (e.g. tree) via native APT
```

## Existing (Debian) installations — migration

Upgrading users with an existing Debian 12 environment are NOT force-migrated:

1. `NoxsMigrationManager.classifyInstall` reads the install marker
   (`.noxs-installed-v1`): `suite=bookworm` (or no `distro=` key) → legacy
   Debian; `distro=ubuntu` / `suite=noble` → Ubuntu.
2. The legacy environment is imported read-only ("Debian 12 (legacy)") and
   stays fully usable — nothing is erased or reinstalled silently.
3. `NoxsMigrationManager.planBackup` scopes user data
   (`home/noxs`, `root`, `etc/noxs/resources.conf`) into a
   `debian-home-backup-<utc>.tar.gz` archive under app-private
   `files/noxs/migration/` via a proot one-shot tar.
4. Installing Ubuntu is an explicit user action (setup flow above); after
   migration the archive can be restored into `/home/noxs` with `tar -xzf`.

No Android system partition is touched and no Android root is required at any
point.
