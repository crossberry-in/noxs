#!/usr/bin/env bash
# Build Debian 12 Bookworm ARM64 with the Noxs offline package set.
# The archive is generated in CI and embedded in the APK; it is not committed.
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
        tar --numeric-owner --xattrs --acls \
            --exclude=./noxs-out --exclude=./dev/\* \
            --exclude=./proc/\* --exclude=./sys/\* \
            --exclude=./run/\* --exclude=./tmp/\* \
            --exclude=./var/cache/apt/archives/\* \
            --exclude=./var/lib/apt/lists/\* \
            -czf /noxs-out/rootfs.tar.gz -C / .
    '

ARCHIVE="$ASSET_DIR/rootfs.tar.gz"
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
    "bundledAsset": "bootstrap/$ARCH/rootfs.tar.gz",
    "packages": [
      "nano", "curl", "git", "python3", "python3-pip",
      "gcc", "g++", "make", "nodejs", "sqlite3"
    ]
  }
}
EOF
cp "$ASSET_DIR/bootstrap.manifest" "$MANIFEST_DIR/bootstrap.manifest"
chmod 0644 "$ARCHIVE" "$ASSET_DIR/bootstrap.manifest" "$MANIFEST_DIR/bootstrap.manifest"

echo "[noxs] offline rootfs ready"
echo "[noxs] size: $ROOTFS_SIZE bytes"
echo "[noxs] sha256: $ROOTFS_SHA"
