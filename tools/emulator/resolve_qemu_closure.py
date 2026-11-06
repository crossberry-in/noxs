#!/usr/bin/env python3
"""
Noxs × NoxOS — QEMU runtime dependency-closure resolver (M1).

Resolves the full Debian Bookworm (12) package closure required to run
`qemu-system-aarch64` inside the Noxs Debian proot environment, straight
from the official package index — no guessing, no hand-written lists:

  1. download dists/bookworm/main/binary-<arch>/Packages.xz (+ binary-all
     for arch:all packages such as qemu-system-data),
  2. seed the closure with `qemu-system-arm` (the package that provides
     the `qemu-system-aarch64` binary on Debian),
  3. walk `Depends:` transitively. For each dependency group the FIRST
     alternative that exists as a real package in the index is selected
     (apt's preferred-candidate behaviour). Groups with no resolvable
     alternative are recorded under "unresolved" — the runtime gate
     refuses to PASS while that list is non-empty,
  4. emit a pinned manifest (versions, download sizes, SHA-256) that later
     milestones (image import, disk provisioning) consume.

Modes:
  (default)              resolve + write the JSON manifest (no downloads)
  --download DIR         additionally download + SHA-256-verify every .deb

The manifest is committed to the repository so the emulator package set
is reproducible and auditable (same security posture as the Noxs
SHA-256-pinned bootstrap).
"""

import argparse
import hashlib
import json
import lzma
import os
import sys
import urllib.request

BASE_URL = "https://deb.debian.org/debian"
SUITE = "bookworm"
SEED_PACKAGE = "qemu-system-arm"  # provides /usr/bin/qemu-system-aarch64

INDICES = ("main/binary-{arch}", "main/binary-all")


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "noxs-emulator-m1/1.0"})
    with urllib.request.urlopen(req, timeout=180) as resp:
        return resp.read()


def load_index(arch: str) -> dict:
    """Parse one Packages.xz index into {name: stanza} (first stanza wins)."""
    if arch == "all":
        idx = INDICES[1]
    else:
        idx = INDICES[0].format(arch=arch)
    url = f"{BASE_URL}/dists/{SUITE}/{idx}/Packages.xz"
    raw = lzma.decompress(fetch(url)).decode("utf-8", "replace")
    index = {}
    for stanza in raw.split("\n\n"):
        fields = {}
        key = None
        for line in stanza.splitlines():
            if line.startswith((" ", "\t")) and key:  # folded continuation
                fields[key] += "\n" + line.strip()
                continue
            if ":" in line:
                key, _, value = line.partition(":")
                fields[key.strip()] = value.strip()
        name = fields.get("Package")
        if name and name not in index:
            index[name] = fields
    return index


def dep_groups(depends: str):
    """'a (>= 1) | b, c' -> [['a', 'b'], ['c']], version constraints stripped."""
    if not depends:
        return []
    groups = []
    for group in depends.split(","):
        alts = []
        for alt in group.split("|"):
            name = alt.strip().split(" ")[0].strip()
            if name:
                alts.append(name)
        if alts:
            groups.append(alts)
    return groups


def resolve(index: dict, seed: str):
    """BFS closure over Depends with apt's first-preferred rule."""
    seen = {}
    unresolved = []
    queue = [seed]
    while queue:
        name = queue.pop(0)
        if name in seen:
            continue
        stanza = index.get(name)
        if stanza is None:
            unresolved.append(name)
            seen[name] = None
            continue
        seen[name] = stanza
        for group in dep_groups(stanza.get("Depends", "")):
            pick = next((a for a in group if a in index), None)
            if pick is None:
                unresolved.append(f"{name}: {' | '.join(group)}")
                continue
            if pick not in seen:
                queue.append(pick)
    packages = {n: s for n, s in seen.items() if s is not None}
    return packages, sorted(set(unresolved))


def download_all(packages: dict, dest: str) -> list:
    """Download every .deb into dest; SHA-256-verify against the index."""
    problems = []
    os.makedirs(dest, exist_ok=True)
    for name, stanza in sorted(packages.items()):
        rel = stanza["Filename"]
        out = os.path.join(dest, os.path.basename(rel))
        expected = stanza.get("SHA256", "")
        if os.path.isfile(out) and expected:
            with open(out, "rb") as fh:
                if hashlib.sha256(fh.read()).hexdigest() == expected:
                    continue  # cached + verified
        data = fetch(f"{BASE_URL}/{rel}")
        digest = hashlib.sha256(data).hexdigest()
        if expected and digest != expected:
            problems.append(f"{name}: sha256 mismatch {digest} != {expected}")
            continue
        with open(out, "wb") as fh:
            fh.write(data)
    return problems


def build_manifest(packages: dict, unresolved: list, arch: str) -> dict:
    entries = []
    total = 0
    for name, stanza in sorted(packages.items()):
        size = int(stanza.get("Size", "0"))
        total += size
        entries.append({
            "package": name,
            "version": stanza.get("Version", ""),
            "architecture": stanza.get("Architecture", arch),
            "filename": stanza.get("Filename", ""),
            "size_bytes": size,
            "sha256": stanza.get("SHA256", ""),
        })
    return {
        "suite": SUITE,
        "architecture": arch,
        "seed_package": SEED_PACKAGE,
        "binary": "/usr/bin/qemu-system-aarch64",
        "package_count": len(entries),
        "download_bytes_total": total,
        "unresolved": unresolved,
        "packages": entries,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="Noxs QEMU dependency-closure resolver (M1)")
    ap.add_argument("--arch", required=True, choices=["arm64", "amd64"])
    ap.add_argument("--out", required=True, help="output JSON manifest path")
    ap.add_argument("--download", help="optional .deb download directory")
    args = ap.parse_args()

    print(f"==> loading {SUITE} index ({args.arch} + all) ...")
    index = {}
    index.update(load_index(args.arch))
    index.update(load_index("all"))
    print(f"    {len(index)} packages in index")

    packages, unresolved = resolve(index, SEED_PACKAGE)
    total_mb = sum(int(s.get("Size", "0")) for s in packages.values()) / (1024 * 1024)
    print(f"==> closure: {len(packages)} packages, {total_mb:.1f} MiB download, "
          f"unresolved={len(unresolved)}")
    for u in unresolved:
        print(f"    UNRESOLVED: {u}")

    if args.download:
        print(f"==> downloading .deb set into {args.download} ...")
        problems = download_all(packages, args.download)
        for p in problems:
            print(f"    DOWNLOAD PROBLEM: {p}")
        if problems:
            unresolved = sorted(set(unresolved + problems))

    manifest = build_manifest(packages, unresolved, args.arch)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2)
        fh.write("\n")
    print(f"==> manifest written: {args.out}")

    gate = "PASS" if not manifest["unresolved"] else "FAIL"
    print(f"==> dependency-closure gate: {gate}")
    return 0 if gate == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
