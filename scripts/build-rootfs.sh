#!/usr/bin/env bash
# shellcheck disable=SC1090,SC1091,SC2012,SC2034,SC2086,SC2164,SC2295
# scripts/build-rootfs.sh — pin the Noxs Ubuntu 24.04 LTS rootfs (original).
#
# Produces:
#   linux-runtime/bootstrap/<arch>/bootstrap.manifest   (URL + SHA-256 pinned)
#   linux-runtime/bootstrap/<arch>/rootfs.tar.gz       (optional offline bundle)
#
# The same artifacts are mirrored into app/src/main/assets/bootstrap/<arch>/.
#
# Rootfs source: the OFFICIAL Ubuntu base images (cdimage.ubuntu.com,
# ubuntu-base <point-release>-base-<arch>.tar.gz — the genuine Ubuntu 24.04
# LTS "Noble Numbat" ARM64 userspace, no re-root, no os-release edits).
# Integrity is DOUBLE-verified: the archive's SHA-256 is computed locally AND
# cross-checked against the official SHA256SUMS file published next to it.
#
# Debian is NOT used anywhere in the Noxs environment pipeline (Ubuntu-first
# spec): this script has no Debian download path and no fallback.
#
# proot is NO LONGER downloaded: it ships inside the APK as jniLibs
# (app/src/main/jniLibs/<abi>/libproot.so) because Android 10+ (W^X / SELinux)
# denies execve() from app data storage. See scripts/stage-proot-jnilibs.py.
#
# Modes:
#   --pin-only       download + checksum-pin (default; no artifact kept in repo)
#   --bundle         also stage the rootfs tarball for offline app bundles
#
# Environment overrides:
#   NOXS_ARCH           arm64-v8a (default) | armeabi-v7a | x86_64
#   NOXS_UBUNTU_POINT   point release to pin (default 24.04.5)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCH="${NOXS_ARCH:-arm64-v8a}"
MODE="${1:---pin-only}"
UBUNTU_POINT="${NOXS_UBUNTU_POINT:-24.04.5}"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

# Noxs app ABI -> Ubuntu cdimage architecture name.
case "$ARCH" in
    arm64-v8a)  UBUNTU_ARCH="arm64" ;;
    armeabi-v7a) UBUNTU_ARCH="armhf" ;;
    x86_64)     UBUNTU_ARCH="amd64" ;;
    *) echo "unsupported arch: $ARCH" >&2; exit 1 ;;
esac

BASE="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release"
ROOTFS_URL="$BASE/ubuntu-base-${UBUNTU_POINT}-base-${UBUNTU_ARCH}.tar.gz"
SUMS_URL="$BASE/SHA256SUMS"

command -v curl >/dev/null 2>&1 || { echo "curl required" >&2; exit 1; }
command -v sha256sum >/dev/null 2>&1 || { echo "sha256sum required" >&2; exit 1; }

echo "[noxs] arch:      $ARCH (Ubuntu $UBUNTU_ARCH)"
echo "[noxs] release:   Ubuntu 24.04 LTS ($UBUNTU_POINT) — Noble Numbat"
echo "[noxs] fetching rootfs for checksum pinning…"
curl -fSL --retry 3 -o "$STAGE/rootfs.tar.gz" "$ROOTFS_URL"
curl -fsSL --retry 3 -o "$STAGE/SHA256SUMS" "$SUMS_URL"

ROOTFS_SHA="$(sha256sum "$STAGE/rootfs.tar.gz" | awk '{print $1}')"
ROOTFS_SIZE="$(stat -c%s "$STAGE/rootfs.tar.gz" 2>/dev/null || stat -f%z "$STAGE/rootfs.tar.gz")"

# Cross-check against the OFFICIAL SHA256SUMS (not just our own computation).
OFFICIAL_SHA="$(grep "ubuntu-base-${UBUNTU_POINT}-base-${UBUNTU_ARCH}.tar.gz" "$STAGE/SHA256SUMS" | awk '{print $1}')"
if [ -z "$OFFICIAL_SHA" ]; then
    echo "[noxs] FATAL: $UBUNTU_POINT/$UBUNTU_ARCH not listed in the official SHA256SUMS" >&2
    exit 1
fi
if [ "$ROOTFS_SHA" != "$OFFICIAL_SHA" ]; then
    echo "[noxs] FATAL: SHA-256 mismatch — local $ROOTFS_SHA, official $OFFICIAL_SHA" >&2
    exit 1
fi
echo "[noxs] rootfs sha256: $ROOTFS_SHA (matches official SHA256SUMS) (${ROOTFS_SIZE} bytes)"

MANIFEST="$(cat <<EOF
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
  "source": "scripts/build-rootfs.sh"
}
EOF
)"

for DEST in \
    "$ROOT/linux-runtime/bootstrap/$ARCH/bootstrap.manifest" \
    "$ROOT/app/src/main/assets/bootstrap/$ARCH/bootstrap.manifest"; do
    mkdir -p "$(dirname "$DEST")"
    printf '%s\n' "$MANIFEST" > "$DEST"
done

if [ "$MODE" = "--bundle" ]; then
    mkdir -p "$ROOT/linux-runtime/bootstrap/$ARCH"
    cp "$STAGE/rootfs.tar.gz" "$ROOT/linux-runtime/bootstrap/$ARCH/rootfs.tar.gz"
    echo "[noxs] offline bundle staged: linux-runtime/bootstrap/$ARCH/rootfs.tar.gz"
fi

echo "[noxs] manifests written (linux-runtime + app assets)."
