#!/bin/sh
# Noxs × NoxOS — QEMU runtime compatibility gate (M1).
#
# Reproduces, on a Debian-13 (or similar) development host, the exact
# runtime the Noxs Android integration will use inside its Debian 12
# proot environment: qemu-system-aarch64 from Debian bookworm, TCG only
# (no KVM anywhere on the Android path), booted against the NoxOS guest.
#
# Steps:
#   1. resolve + download the bookworm qemu-system-arm closure (amd64
#      build for the dev host; the arm64 manifest is the on-device set),
#   2. extract every .deb into an overlay root and run the binary through
#      an LD_LIBRARY_PATH wrapper,
#   3. PRUNE the overlay's base runtime libraries (libc6, libgcc-s1,
#      libstdc++6, ...) — the dev host glibc is newer; shadowing it with
#      bookworm's crashes QEMU with __fortify_fail. This pruning is a
#      DEV-HOST-ONLY step: inside Noxs proot the rootfs is a consistent
#      bookworm userspace and no pruning happens,
#   4. run the four serial-marker gates of the NoxOS tree (smoke,
#      graphics, input devices, device manager) + the release boot gate,
#   5. capture real QMP screendumps as visual evidence.
#
# Known QMP limitation (documented in docs/QEMU-ANDROID.md): bookworm's
# QEMU 7.2 rejects `input-send-event` with button=touch (added in QEMU
# 8.2), so the touch/gesture QMP injection gates of the NoxOS harness
# run on the QEMU 10.0.13 dev baseline instead. On-device touch input
# flows through VNC → virtio-tablet (M6/M7), which QEMU 7.2 fully
# supports.
#
# Usage: tools/emulator/validate_runtime_gate.sh [NOXOS_REPO_DIR]
set -eu

NOXOS_REPO="${1:-../NoxOS}"
WORK=/tmp/noxs-qemu-gate
QEMU_BIN="$WORK/qemu-system-aarch64"

cd "$(dirname "$0")"

echo "==> [1/5] resolving bookworm amd64 closure"
python3 resolve_qemu_closure.py --arch amd64 \
    --out qemu-deps-bookworm-amd64.json --download "$WORK/debs"
python3 resolve_qemu_closure.py --arch arm64 \
    --out qemu-deps-bookworm-arm64.json
test -f "$NOXOS_REPO/tests/qemu_smoke.sh" || { echo "NoxOS repo not found at $NOXOS_REPO"; exit 1; }

echo "==> [2/5] extracting .deb overlay"
rm -rf "$WORK/root"
for f in "$WORK"/debs/*.deb; do dpkg-deb -x "$f" "$WORK/root"; done

echo "==> [3/5] pruning dev-host base libs (dev-host-only step)"
mkdir -p "$WORK/base-libs-backup"
for pat in "libc.so.6*" "libcrypt.so.1*" "libgcc_s.so.1*" "libstdc++.so.6*" \
           "libm.so.6" "libpthread.so.0" "libdl.so.2" "librt.so.1" \
           "ld-linux-x86-64.so.2"; do
    for f in "$WORK"/root/usr/lib/x86_64-linux-gnu/$pat \
             "$WORK"/root/lib/x86_64-linux-gnu/$pat \
             "$WORK"/root/lib64/$pat; do
        [ -e "$f" ] && mv "$f" "$WORK/base-libs-backup/"
    done
done

cat > "$QEMU_BIN" <<EOF
#!/bin/sh
export LD_LIBRARY_PATH=$WORK/root/usr/lib/x86_64-linux-gnu:$WORK/root/lib/x86_64-linux-gnu\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}
export QEMU_MODULE_DIR=$WORK/root/usr/lib/x86_64-linux-gnu/qemu
exec $WORK/root/usr/bin/qemu-system-aarch64 "\$@"
EOF
chmod +x "$QEMU_BIN"
"$QEMU_BIN" --version | head -1

echo "==> [4/5] NoxOS serial-marker gates on bookworm QEMU"
cd "$NOXOS_REPO"
./tests/qemu_smoke.sh    "$QEMU_BIN" | tail -1
./tests/qemu_graphics.sh "$QEMU_BIN" | tail -1
./tests/qemu_input.sh    "$QEMU_BIN" | tail -1
./tests/qemu_devices.sh  "$QEMU_BIN" | tail -1
cargo build -p noxos-boot --features boot-image --release --target aarch64-unknown-none
KERNEL=target/aarch64-unknown-none/release/noxos ./tests/qemu_input.sh "$QEMU_BIN" | tail -1

echo "==> [5/5] real QMP screendumps (visual evidence)"
python3 scripts/capture_screenshots.py "$QEMU_BIN" | tail -1
python3 scripts/validate_screenshots.py docs/screenshots | tail -1

echo "GATE COMPLETE"
