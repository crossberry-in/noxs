# Noxs bootstrap system

![First-launch bootstrap flow](assets/images/bootstrap-flow.svg)

## What gets installed

| Artifact        | Source (default)                                             | Size     |
|-----------------|--------------------------------------------------------------|----------|
| Debian 12 rootfs| `debuerreotype/docker-debian-artifacts` (official Debian CI), pinned to an immutable commit SHA under `<suite>/oci/blobs/rootfs.tar.gz` | ~44–49 MB compressed |
| proot + deps    | **Bundled in the APK** as `jniLibs/<abi>/{libproot,libtalloc,libandroid-shmem}.so` (bionic-linked proot 5.1.107.96 build) | ~300 KB |

The rootfs is verified with SHA-256 before use. Release builds **pin** digests
via `scripts/build-rootfs.sh`; development builds may run unpinned (the
installer computes and shows the digest, then pins it trust-on-first-use).

proot is NOT downloaded at runtime: Android 10+ (SELinux W^X) denies
`execve()` on files inside app data storage, so the binaries ship inside the
APK as `lib*.so` and the OS extracts them into `nativeLibraryDir` — the only
location where app executables may run. `ProotLauncher` execs
`<nativeLibraryDir>/libproot.so` with `LD_LIBRARY_PATH` pointing at the same
directory so `libtalloc.so` / `libandroid-shmem.so` resolve.

## First-launch flow (SetupActivity)

```
Noxs logo → "Linux environment setup"
  → architecture detection (arm64-v8a priority, then x86_64, then armv7)
  → storage initialization (filesDir/noxs/{rootfs,bin,run,cache,logs,tmp})
  → bootstrap manifest load (assets/bootstrap/<arch>/bootstrap.manifest)
  → proot presence check  (bundled jniLibs → nativeLibraryDir; no network)
  → rootfs download       → SHA-256 verify → stage → rename (3 retries)
  → safe extraction       (TarGuard: traversal/absolute/symlink/device/bomb defense)
  → Debian configuration  (FHS dirs, profile.d, apt, DNS, motd, noxs CLI)
  → noxs user creation    (passwd/group/shadow + sudo group)
  → password collection   (wizard → chpasswd stdin; never stored)
  → /run + /var/run init  (runtime state dirs inside the rootfs)
  → signed APT bootstrap  (narrow dpkg repair; temporary signed Debian HTTP sources; install/verify CA certificates; switch to one HTTPS Bookworm source file; require HTTPS `apt-get update`, `apt-cache` metadata, and a clean `dpkg --audit`)
  → first shell
```

## Package-manager safety

Only ONE apt/dpkg transaction may run at a time:

- App-side: `NoxsPkgTransaction` serializes every Noxs-managed operation
  (setup, background security bootstrap, package screen, Repair).
- In-shell: `/etc/profile.d/noxs-pkg-guard.sh` wraps `apt`/`apt-get` with a
  real busy check (Noxs transaction flag in `/run/noxs/pkg-tx` + running
  dpkg/apt processes) and prints "Noxs package manager is busy." — it never
  tells the user to delete locks, and locks are never removed by Noxs.
- Interrupted operations: Setup console → **Repair** runs `dpkg --configure -a`
  + authenticated dependency repair + CA re-verification through the signed
  APT bootstrapper, only after the guard confirms nothing else is running.

## Non-blocking APT security bootstrap

The signed APT/CA/TLS bootstrap never blocks the shell:

- During setup, a failed APT step is **non-fatal by design**: setup still
  completes (rootfs + user + sudo are what a shell needs) and the user reaches
  an active prompt immediately.
- On every service start, `NoxsAptSetup` runs the same signed bootstrap in the
  background until the `apt-bootstrap-v1.ready` marker exists (idempotent,
  guarded by `PROCESS_LOCK` and a per-process atomic). The terminal is never
  gated on it — no "Preparing Debian packages" state exists anymore.
- If the background repair fails, the terminal shows one informational toast
  and the repair is retried automatically on the next launch. APT metadata
  remains usable over signed HTTP in the meantime.

## Interruption safety

- Downloads use a `.part` staging file; the final name only appears after
  checksum verification, so power loss can never leave a half-verified artifact.
- Extraction is restartable: an existing complete marker short-circuits setup;
  a broken rootfs is detected by the missing marker and can be reset from
  Storage → "Reset Linux environment".
- Cached archives under `filesDir/noxs/cache` are reused across retries.

## Offline-first

After the one-time bootstrap, normal operation (terminal, sockets, services,
code-server) needs no network. `apt install` naturally needs Debian mirrors.
For fully offline distribution, build with `scripts/build-rootfs.sh --bundle`
and ship the staged tarball inside the APK assets.

## ARM64 optimization

- Priority arch: `arm64-v8a` (manifest listed first, ABI filter in Gradle).
- proot static build for aarch64; `PROOT_NO_SECCOMP=1` for broad kernel support.
- Minimal default apt set avoids unnecessary package churn; APT recommends are
  disabled (`70noxs` conf) to cut install size.
