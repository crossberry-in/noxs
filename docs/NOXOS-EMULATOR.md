# NoxOS Emulator (Noxs × NoxOS integration)

**Status: M1 complete + M2 core implemented (sidebar entry → auto-boot →
VNC display window).** The QEMU command set and the RFB display path are
verified on real Debian bookworm QEMU 7.2.22
(`tools/emulator/verify_vnc_path.py`, `tools/emulator/logs/vnc-display-path.png`).
On-device (Android) verification is M9; everything below follows the
four-level honesty classification — nothing is called "working" without
test evidence.

Noxs (the Android host) will run the real NoxOS guest operating system
(https://github.com/crossberry-in/NoxOS) inside a QEMU virtual machine.
Noxs keeps its existing Debian terminal functionality untouched; the
emulator is an isolated subsystem inside the same app.

## Architecture (target)

```
Android Application
|
+-- Noxs UI (sidebar: "NoxOS" → emulator window, auto-boot)     M2 ✅ (device-test pending)
+-- EmulatorManager (VM lifecycle, process owner)               M2 ✅ (device-test pending)
+-- Display Bridge (VNC localhost, raw + resize)                M6 ✅ protocol-verified
+-- Touch and keyboard (pointer + keysyms, sticky Ctrl)         M7 ✅ protocol-verified
+-- QEMU Runtime (qemu-system-aarch64, TCG, bookworm)           M1 ✅
+-- Virtual Machine Manager (config.json, memory policy)        M2 ✅ (JVM-tested)
+-- Serial console UI (serial.log captured; viewer = M5)        M5 ◐ partial
+-- Virtual Storage (SAF import M3, disks M4)                   planned
+-- Background lifecycle (Activity Center)                      planned (M8)
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
| M2 | Emulator process manager (EmulatorManager) | **implemented with limitations** — CI compile + JVM tests pending push; device run pending M9 | `NoxOsEmulatorManager.kt`, `VmStorage.kt`, `VmConfigJson.kt` |
| M3 | NoxOS image import (SAF, validation) | planned | — |
| M4 | Virtual disk persistence | planned | — |
| M5 | Serial console UI | **partial** — serial.log captured by the VM; viewer UI pending | `logs/serial.log`, log tail in the emulator window |
| M6 | Graphical display (VNC localhost) | **implemented with limitations** — RFB 3.8 path verified on real QEMU 7.2.22; Android rendering compile pending CI | `VncClient.kt`, `verify_vnc_path.py`, `logs/vnc-display-path.png` |
| M7 | Touch and keyboard | **implemented with limitations** — pointer/keysym protocol verified; device UX pending M9 | `EmulatorActivity.kt`, `Keysym.kt` |
| M8 | Background lifecycle (Activity Center) | planned | — |
| M9 | Automated Android tests | planned | — |
| M10 | Documentation and release | planned | — |

Feature classification follows the NoxOS honesty policy: every status
above is either *implemented and tested*, *implemented with
limitations*, *experimental*, or *planned / not implemented* — never
"working" without test evidence.

## Related docs

- `docs/QEMU-ANDROID.md` — M1 runtime compatibility study + the M2
  integration section (this milestone's evidence).
- `tools/emulator/verify_vnc_path.py` — M2 display-bridge verification
  harness (mirrors the Kotlin RFB client byte-for-byte).
- `docs/VM-STORAGE.md`, `docs/EMULATOR-DISPLAY.md`,
  `docs/ANDROID-PERMISSIONS.md` — added with their milestones (M4, M6,
  M8).

## M2 quick facts

- Sidebar: **NoxOS** row in the terminal drawer → opens the emulator
  window → the VM boots automatically (dirs → `vm.json` → kernel asset
  → one-time apt install of `qemu-system-arm` → QEMU on a free
  127.0.0.1 VNC display → window connects).
- VM storage: `files/noxs/virtual-machines/noxos/` (`vm.json`,
  `kernel/`, `disks/`, `logs/serial.log`, `logs/qemu.log`); the VM dir
  is bind-mounted at `/noxs-vm` inside the proot rootfs — QEMU never
  sees an Android path.
- Guest RAM: 256/384/512 MiB by `/proc/meminfo` tier (`VmMemoryPolicy`).
- Display: VNC bound to 127.0.0.1 only, no auth (localhost-only per
  spec), raw encoding, guest framebuffer 720×1280.
