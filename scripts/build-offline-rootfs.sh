#!/usr/bin/env bash
# Build Debian 12 Bookworm ARM64 with the Noxs offline package set.
# The archive is generated in CI and embedded in the APK; it is not committed.
#
# The APK bundles the archive as rootfs.bin (NOT rootfs.tar.gz): aapt/AAPT2
# decompresses .gz-suffixed assets and strips the suffix at packaging time,
# so a rootfs.tar.gz asset would arrive in the APK as an uncompressed
# rootfs.tar the installer cannot find or verify. A neutral extension keeps
# the gzip bytes intact. Tar/gzip are run reproducibly (sorted names, fixed
# mtimes, no gzip timestamp) so identical inputs give identical SHA-256
# across CI runs — APKs built earlier can still verify the published asset.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCH="${NOXS_ARCH:-arm64-v8a}"
if [[ "$ARCH" != "arm64-v8a" ]]; then
    echo "[noxs] offline bundle currently supports arm64-v8a only (got $ARCH)" >&2
    exit 2
fi
command -v docker >/dev/null 2>&1 || {
    echo "[noxs] Docker is required; CI enables ARM64 QEMU before running this script" >&2
    exit 1
}

ASSET_DIR="$ROOT/app/src/main/assets/bootstrap/$ARCH"
MANIFEST_DIR="$ROOT/linux-runtime/bootstrap/$ARCH"
mkdir -p "$ASSET_DIR" "$MANIFEST_DIR"
ASSET_DIR="$(cd "$ASSET_DIR" && pwd)"

echo "[noxs] building Debian 12 Bookworm ARM64 offline rootfs"
docker run --rm --platform linux/arm64 \
    -v "$ASSET_DIR:/noxs-out" \
    debian:12-slim bash -euc '
        export DEBIAN_FRONTEND=noninteractive
        apt-get update
        apt-get install -y --no-install-recommends \
            apt ca-certificates sudo passwd procps locales tzdata \
            nano curl git \
            python3 python3-pip python3-venv \
            gcc g++ make libc6-dev \
            nodejs \
            sqlite3 libsqlite3-dev \
            bash coreutils findutils grep sed gawk \
            tar gzip xz-utils zstd
        apt-get clean
        rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*
        mkdir -p /home/noxs /run /tmp /dev /proc /sys
        chmod 1777 /tmp
        # proc/sysfs/devtmpfs mutate while being read, so tar must never walk
        # them ("file changed as we read it" -> exit 1). Exclude the mount
        # points from the walk, then append empty ones from a plain staging
        # directory so the archive still carries /dev /proc /sys for proot.
        rm -f /noxs-out/rootfs.tar /noxs-out/rootfs.bin
        tar --numeric-owner --xattrs --acls \
            --sort=name --mtime='UTC 2026-01-01' --owner=0 --group=0 \
            --exclude=./noxs-out \
            --exclude=./dev --exclude=./proc --exclude=./sys \
            --exclude=./run/* --exclude=./tmp/* \
            --exclude=./var/cache/apt/archives/* \
            --exclude=./var/lib/apt/lists/* \
            -cf /noxs-out/rootfs.tar -C / .
        mkdir -p /tmp/noxs-mounts/dev /tmp/noxs-mounts/proc /tmp/noxs-mounts/sys
        tar --numeric-owner --mtime='UTC 2026-01-01' -rf /noxs-out/rootfs.tar \
            -C /tmp/noxs-mounts ./dev ./proc ./sys
        rm -rf /tmp/noxs-mounts
        # gzip -n: no mtime/name in the header (byte-reproducible output)
        gzip -n -f /noxs-out/rootfs.tar
        # rootfs.bin = the gzip stream under an aapt-safe name (see header)
        mv /noxs-out/rootfs.tar.gz /noxs-out/rootfs.bin
        chown "$(stat -c %u:%g /noxs-out)" /noxs-out/rootfs.bin
    '

ARCHIVE="$ASSET_DIR/rootfs.bin"
test -s "$ARCHIVE"
gzip -t "$ARCHIVE"
ROOTFS_SHA="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
ROOTFS_SIZE="$(stat -c%s "$ARCHIVE")"
ROOTFS_URL="https://github.com/crossberry-in/noxs/releases/latest/download/noxs-debian-bookworm-arm64.tar.gz"

cat > "$ASSET_DIR/bootstrap.manifest" <<EOF
{
  "schema": 1,
  "arch": "$ARCH",
  "rootfs": {
    "url": "$ROOTFS_URL",
    "sha256": "$ROOTFS_SHA",
    "format": "tar.gz",
    "sizeBytes": $ROOTFS_SIZE
  },
  "generatedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "source": "scripts/build-offline-rootfs.sh",
  "offline": {
    "bundledAsset": "bootstrap/$ARCH/rootfs.bin",
    "packages": [
      "nano", "curl", "git", "python3", "python3-pip",
      "gcc", "g++", "make", "nodejs", "sqlite3"
    ]
  }
}
EOF
cp "$ASSET_DIR/bootstrap.manifest" "$MANIFEST_DIR/bootstrap.manifest"
chmod 0644 "$ARCHIVE" "$ASSET_DIR/bootstrap.manifest" "$MANIFEST_DIR/bootstrap.manifest"

echo "[noxs] offline rootfs ready (bundled as rootfs.bin)"
echo "[noxs] size: $ROOTFS_SIZE bytes"
echo "[noxs] sha256: $ROOTFS_SHA"
