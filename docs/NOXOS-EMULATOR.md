# NoxOS Emulator (Noxs × NoxOS integration)

**Status: M1 of M10 complete — QEMU runtime compatibility verified on the
Debian baseline.** The Android-device milestone (M2+) is not started yet.

Noxs (the Android host) will run the real NoxOS guest operating system
(https://github.com/crossberry-in/NoxOS) inside a QEMU virtual machine.
Noxs keeps its existing Debian terminal functionality untouched; the
emulator is an isolated subsystem inside the same app.

## Architecture (target)

```
Android Application
|
+-- Noxs UI (sidebar: "NoxOS Emulator" + VM dashboard)          M2/M7
+-- EmulatorManager (VM lifecycle, process owner)               M2
+-- QEMU Runtime (qemu-system-aarch64, TCG, bookworm)           M1 ✅
+-- Virtual Machine Manager (config, limits, timeouts)          M2/M8
+-- Virtual Storage (app-private VM disks)                      M3/M4
+-- Display Bridge (serial console → VNC localhost)             M5/M6
+-- Serial Console (boot log, guest log)                        M5
+-- NoxOS Guest (aarch64 kernel, virtio devices)                verified
```

Isolation rules: the VM lives entirely in app-private storage
(`files/noxs/virtual-machines/…`), QEMU never sees Android filesystem
paths, the display bridge binds to localhost only, and no root /
bootloader / raw-flash access is required or requested.

## Repository audit (2026-11-07)

**Noxs (host).** Debian 12 (bookworm) userspace under proot,
ARM64-first, targetSdk 34 / minSdk 24. Guest binaries execute through
the bundled proot loader chain (`libproot.so` + `PROOT_LOADER` from
`nativeLibraryDir` — the only exec-permitted location on Android 10+),
so an apt-installed `qemu-system-aarch64` inside the rootfs uses the
exact same execution path as every other Debian package already in use
(bash, apt, gcc). App-private layout roots at `filesDir/noxs/`
(`NoxsPaths.kt`), Android↔Debian IPC runs over unix sockets under
`/var/run/noxs/host`, and the Activity Center + foreground service
(`NoxsService.kt`, `NoxsActivityCenter.kt`) already provide the
background-lifecycle architecture the VM will plug into.

**NoxOS (guest).** v0.9.0 — a no_std Rust kernel for AArch64
(~20,700 lines) with verified boot (EL1 entry, DTB walk, frame
allocator, W^X identity map, MMU), GICv2 + generic timer, syscall
boundary with capability checks, IPC v2, DeviceManager over the real
DTB, virtio-mmio v2 transport, virtio-gpu (720×1280 scanout),
virtio-blk (single-sector), virtio-keyboard/tablet input, a touch
stream + gesture recognizer (tap/double-tap/long-press/swipe/drag), a
4-window manager with derived focus and an interactive shell (lock /
home / drawer / control center / terminal / settings / files /
multitask) — all QEMU-verified with automated QMP input gates. For the
emulator integration this means: boot, serial console, VirtIO device
discovery, framebuffer, keyboard, touch and persistent virtio-blk
storage are guest features that already exist and are test-covered.
Guest limitations relevant to the VM design: no SMP bring-up
(single-CPU guest), no ACPI shutdown handling (graceful poweroff needs
a guest-side feature or a monitored reset), no VFS (virtio-blk records
only).

## Milestones

| # | Milestone | Status | Evidence |
|---|---|---|---|
| M1 | QEMU runtime compatibility | **done (host-verified)** | `docs/QEMU-ANDROID.md`, `tools/emulator/` |
| M2 | Emulator process manager (EmulatorManager) | planned | — |
| M3 | NoxOS image import (SAF, validation) | planned | — |
| M4 | Virtual disk persistence | planned | — |
| M5 | Serial console UI | planned | — |
| M6 | Graphical display (VNC localhost) | planned | — |
| M7 | Touch and keyboard | planned | — |
| M8 | Background lifecycle (Activity Center) | planned | — |
| M9 | Automated Android tests | planned | — |
| M10 | Documentation and release | planned | — |

Feature classification follows the NoxOS honesty policy: every status
above is either *implemented and tested*, *implemented with
limitations*, *experimental*, or *planned / not implemented* — never
"working" without test evidence.

## Related docs

- `docs/QEMU-ANDROID.md` — the M1 runtime compatibility study (this
  milestone's evidence).
- `docs/VM-STORAGE.md`, `docs/EMULATOR-DISPLAY.md`,
  `docs/ANDROID-PERMISSIONS.md` — added with their milestones (M4, M6,
  M8).
