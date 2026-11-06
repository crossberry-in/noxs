# QEMU on Android for NoxOS — runtime compatibility (M1)

**Status: implemented with limitations.** The complete bookworm
`qemu-system-aarch64` package closure is resolved, pinned and
SHA-256-verified, and the exact QEMU version the Noxs Debian 12 rootfs
ships was verified to boot and drive the NoxOS guest through all
serial-marker gates on a development host. **Nothing here has been
verified on an actual Android device yet** — that is M9's job, and no
device claim is made.

## Selected runtime

| Decision | Value | Why |
|---|---|---|
| Emulator | `qemu-system-aarch64` | the only full-system aarch64 emulator in Debian main; matches the NoxOS guest target |
| Source | Debian **bookworm** (`qemu-system-arm 1:7.2+dfsg-7+deb12u18+b3`, i.e. **QEMU 7.2**) | the Noxs rootfs *is* Debian 12; installing from its own suite keeps the rootfs consistent (no cross-suite pins) |
| Acceleration | **TCG only** | KVM is not assumed on Android proot (`/dev/kvm` is not guaranteed to exist or be permitted); software emulation is the portable baseline per the integration spec |
| Machine | `-M virt` | the verified NoxOS platform (`kernel/src/platform/qemu_virt.rs`) |
| Devices | `virtio-gpu-device (720×1280)`, `virtio-keyboard-device`, `virtio-tablet-device`, `virtio-blk-device`, `-nic none` | exactly the devices the NoxOS drivers implement; no network device until the guest implements one |
| CPU / RAM | `-cpu cortex-a53 -m 512M` (single vCPU) | the profile the NoxOS gates validate today; configurable CPU/RAM sizing with memory-pressure detection arrives with the EmulatorManager (M2) |

Delivered through the existing Noxs execution path: QEMU is installed
by `apt` into the rootfs like any other Debian package and runs behind
the same proot loader chain every guest binary already uses
(`docs/ARCHITECTURE.md`). No APK change, no jniLibs addition, no new
permission.

## Dependency closure (resolved from the official index)

Resolved with `tools/emulator/resolve_qemu_closure.py` against
`dists/bookworm/main/binary-<arch>/Packages.xz` (+ `binary-all`), seed
`qemu-system-arm`, apt's first-preferred-alternative rule, unresolved
dependencies treated as gate failure. Results:

| Arch | Packages | Download | Unresolved | Gate |
|---|---|---|---|---|
| arm64 (on-device set) | 101 | 36.7 MiB | 0 | **PASS** |
| amd64 (dev-host set) | 101 | 39.4 MiB | 0 | **PASS** |

Both manifests are committed (`tools/emulator/qemu-deps-bookworm-*.json`)
with version, size and SHA-256 per package — the emulator package set is
reproducible and auditable, matching the Noxs SHA-256-pinned bootstrap
posture. Note for M2+: the manifest is the *full* closure; base packages
(`libc6`, `libgcc-s1`, …) are already installed in the rootfs and apt
will treat them as no-ops.

## Runtime verification evidence (development host)

`tools/emulator/validate_runtime_gate.sh` extracts the amd64 bookworm
closure into an overlay, runs QEMU 7.2.22 through an `LD_LIBRARY_PATH`
wrapper and drives the NoxOS test suite against it. Results
(2026-11-07, TCG, x86-64 host → aarch64 guest):

| Gate | What it proves | Result on QEMU 7.2.22 |
|---|---|---|
| `tests/qemu_smoke.sh` | boot to EL1, DTB walk, frame allocator, W^X identity map, MMU on, GIC + timer ticks, scheduler demo | **PASS** |
| `tests/qemu_graphics.sh` | virtio-gpu bring-up, scanout, boot screen, 8-scene animated tour | **PASS** |
| `tests/qemu_input.sh` | virtio-keyboard + virtio-tablet negotiated, capability-classified, event queues live | **PASS** |
| `tests/qemu_devices.sh` | DTB device discovery, binding, capability grants, lifecycle, recovery | **PASS** |
| release boot gate | the same input bring-up on the `--release` binary | **PASS** |
| QMP screendumps | 9 real `screendump` captures on 7.2, all validated by the screenshot authenticity checker | **PASS** (`tools/emulator/logs/qemu72-*.png` committed) |

The same suite on the QEMU 10.0.13 development baseline additionally
passes the interactive QMP injection gates (keyboard typing into the
live terminal, touch taps driving scene switches and window lifecycle,
swipe/drag gestures, virtio-blk Noxs install + auto-boot persistence)
— those results belong to NoxOS v0.9 and are referenced, not
re-claimed, here.

## Known limitations (honest)

1. **QMP touch injection is unavailable on QEMU 7.2.** The
   `input-send-event` API rejects `button: "touch"` (that enum value
   was added in QEMU 8.2), so the NoxOS touch/gesture *automation*
   gates cannot run on the bookworm binary. This is a test-harness
   limitation only: on-device touch input will reach the guest through
   the VNC → virtio-tablet path (M6/M7), which QEMU 7.2 fully
   supports, and keyboard automation can use HMP `sendkey`. Options
   for M9: run the automated touch gates against a newer backported
   QEMU build in CI, or convert the touch gates to the VNC path.
2. **TCG performance.** Software emulation of a 720×1280 animated UI
   is functional but slow (the v0.7 measured budget of 3–4 ms/frame of
   *guest* rendering stretches to tens of ms of wall time under TCG).
   Measured Android-device numbers come with M9; no performance claim
   is made before then.
3. **Guest-side graceful shutdown is pending.** NoxOS currently has no
   ACPI/pm-state handling, so "shutdown" for M2 initially means
   terminating the QEMU process after a monitored grace period;
   guest-side clean poweroff is a NoxOS-kernel feature to be built
   separately (kept out of Noxs per the integration rule).
4. **Android device verification pending.** Every result above is
   dev-host evidence. M2+ gates must re-verify inside proot on an
   ARM64 emulator and a real device before any "works on Android"
   statement.
5. **Dev-host-only overlay pruning.** `validate_runtime_gate.sh`
   removes bookworm's `libc6/libgcc-s1/libstdc++6` from the overlay
   because the dev host glibc is newer and must not be shadowed (it
   crashes QEMU with `__fortify_fail` otherwise). Inside Noxs proot the
   rootfs is a self-consistent bookworm userspace, so this pruning does
   not apply on-device.

## Reproduce

```sh
# from the Noxs repository
tools/emulator/validate_runtime_gate.sh /path/to/NoxOS
```

Requires: `python3`, `dpkg-deb`, network access to `deb.debian.org`,
the NoxOS checkout with a working Rust toolchain for the release gate.
