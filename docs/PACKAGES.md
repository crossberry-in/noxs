# NX Package System

The NX Package System is Noxs's Git-based package ecosystem. Every package
is an independent Git repository (for example `github.com/noxs-pkg/tree`);
GitHub Actions builds it on every push, publishes versioned `.nx.pkg`
release artifacts, and keeps the package's `registry.json` up to date. Noxs
users install packages with `nx install`.

All commands below run inside the Noxs terminal (any environment). `nx`
also forwards every `noxs` command, so `nx docker install` and
`noxs docker install` are equivalent.

## For users

```bash
nx install tree                    # latest version from noxs-pkg/tree
nx install tree@1.0.0              # an exact version
nx install -git @web12-app/tree    # any GitHub repository with a registry.json
nx list                            # what is installed
nx info tree                       # details (installed + latest)
nx update tree                     # update one package (or all: nx update)
nx remove tree                     # remove (asks for confirmation)
```

Install flow: resolve `registry.json` → pick the latest (or requested)
version → detect the device architecture (`aarch64`, `x86_64`, `armv7`)
→ select a compatible release asset (a `universal` asset is used when the
package is architecture-independent) → download (streamed, retried,
resumable) → verify the SHA-256 checksum against `registry.json` **and**
the published `.sha256` sidecar → check every archive member for path
traversal / absolute paths / absolute symlinks → extract at the
environment root → record an installed-package manifest.

Corrupted or mismatched artifacts are never installed. Metadata is parsed
as data and is never executed. Removal only touches files recorded in the
package manifest (under `usr/` or `opt/`) — it never removes Noxs itself.

## For package authors

### 1. Create the package

```bash
nx pkg init tree
```

shows the interactive language selector (arrow keys + Enter):

```
Noxs Package Initializer

Select package language:

❯ Node.js
  Rust
  C++
  Python
  Go
```

or pick non-interactively: `nx pkg init tree --node` (aliases: `--nodejs`,
`--rust`, `--cpp`, `--c++`, `--python`, `--py`, `--go`).

The scaffold:

```
tree/
├── .github/workflows/pkg.yml   # build + release automation (multi-arch)
├── src/main.{js,rs,cpp,py}     # or main.go + go.mod at the root (Go)
├── package.json | Cargo.toml | CMakeLists.txt | pyproject.toml | go.mod
├── registry.json               # release metadata, updated automatically
├── VERSION                     # 1.0.0 — semantic version, read by CI
├── README.md
└── LICENSE
```

### 2. Implement + build locally

```bash
nx pkg build       # runs npm / cargo / cmake / pip / go build
nx pkg release     # pre-flight: validates VERSION, registry.json, source,
                   # build and packaging; checks the release tag is free
```

### 3. Publish

```bash
git add .
git commit -m "release: tree 1.0.0"
git push
```

GitHub Actions then runs `pkg.yml`:

```
checkout → detect language → validate VERSION → reject duplicate tags
        → multi-architecture build → package + sha256
        → tag <name>-v<version> → GitHub Release with .nx.pkg assets
        → update registry.json (preserve versions, bump latest)
        → commit registry.json [skip ci]
```

Architectures: Go/Rust/C++ build `x86_64`, `aarch64` and `armv7` (Go natively,
Rust/C++ via cross toolchains); Node.js/Python packages publish a single
`universal` artifact. Artifacts are named
`<name>.nx.pkg.<version>.<arch>.tar.xz` plus a `.sha256` sidecar.

Invalid versions are rejected, existing tags are never overwritten
(`Release already exists: <name>-v<version>`), and the workflow fails
cleanly when a build tool is missing.

## Registry format

`registry.json` is maintained by the release workflow — you never edit
release metadata by hand:

```json
{
  "name": "tree",
  "description": "Noxs tree utility",
  "latest": "1.0.1",
  "versions": [
    {
      "version": "1.0.0",
      "tag": "tree-v1.0.0",
      "files": [
        {
          "name": "tree.nx.pkg.1.0.0.aarch64.tar.xz",
          "sha256": "…"
        }
      ]
    }
  ]
}
```

## Security model

- Downloads use HTTPS only; checksums are verified before extraction and
  can never be bypassed.
- Package names, versions, repository ids and asset names are strictly
  validated before they are used in URLs or paths; registry fields are
  never evaluated as shell code.
- Archives are member-checked before extraction: absolute paths, `..`
  traversal and absolute symlink targets are rejected.
- Temporary files live in guarded temp directories that are cleaned on
  exit, interrupt and termination — a cancelled install never leaves
  corrupt state behind.
- Errors are user-facing ("Package not found.", "No compatible release
  found."); stack traces and internal paths are never printed.

## Extending

Languages are template directories under
`/usr/local/share/noxs-pkg/templates/<language>/` with a `template.conf`
(language id, display name, supported architectures, build system) and a
`files/` tree. Adding a language later means adding a directory — the
package manager does not need to change.

## Official packages (nx-pkg/)

The `nx-pkg/` directory of this repository holds the sources of the
official noxs-pkg packages — each subdirectory is published as its own
public Git repository under `github.com/noxs-pkg/<name>`:

| package   | language | what it does                    |
|-----------|----------|---------------------------------|
| `tree`    | C++      | directory tree printer          |
| `nxinfo`  | Node.js  | Noxs environment info           |
| `nxfetch` | Python   | compact system summary          |

Each carries the full release automation (`.github/workflows/pkg.yml`,
`registry.json`, `VERSION`) generated from the canonical Noxs templates, so
`nx install tree` resolves, verifies and installs straight from that
repository's releases. See `nx-pkg/README.md`.

## Distro packages — `nx pkg` (Debian / Ubuntu)

`nx pkg` is the distro-aware front end on top of the Git package system:

```bash
nx pkg search <name> [--json]   # cache -> Noxs API -> distro metadata -> registry
nx pkg info <name> [--json]     # merged view of every source + installed state
nx pkg install <name> [-y]      # Noxs installer UI with live single-line progress
nx pkg update [--full]          # refresh indexes; --full runs the upgrade
nx pkg list                     # installed Debian + Noxs packages
nx pkg remove <name> [-y]       # remove via apt or the Noxs manifest
nx pkg doctor                   # health check for the whole package layer
```

On Debian and Ubuntu guests dependency resolution, download and actual
installation stay inside the **native apt engine**: `apt-get --print-uris`
supplies the file list, sizes and SHA-256 hashes straight from the signed
repository metadata; every downloaded `.deb` is re-verified against that
hash before `apt-get install` runs. Nothing is ever installed unauthenticated
and the Debian package database is never replaced. `nx install <name>`
delegates to the same flow when the name is not a Git-registry package
(disable with `NOXS_INSTALL_FALLBACK=0`).

Install progress renders ONE terminal line —
`Downloading curl_…arm64.deb  67%  4.8/7.2 MB  2.1 MB/s  ETA 2s` —
computed from real bytes and elapsed time (unknown sizes show bytes and
speed, never an invented percentage); non-interactive terminals and CI get
a plain multi-line transcript, `--json` keeps stdout machine-readable, and
diagnostics go to stderr. Stable error codes: `PACKAGE_NOT_FOUND`,
`UNSUPPORTED_ARCHITECTURE`, `NETWORK_ERROR`, `DOWNLOAD_FAILED`,
`CHECKSUM_MISMATCH`, `SIGNATURE_VERIFICATION_FAILED`,
`DEPENDENCY_RESOLUTION_FAILED`, `INSUFFICIENT_DISK_SPACE`,
`INSTALL_PERMISSION_DENIED`, `PACKAGE_MANAGER_BUSY`, `MIRROR_UNAVAILABLE`.

Downloads prefer the [Noxs mirror](MIRROR.md) (sha256-keyed cache in
`~/.noxs/cache/pkg`, cache hits reported), fall back to the official
repository URL automatically, and never bypass signature verification.
Concurrent package operations are locked out via the single-flight
transaction flag (`PACKAGE_MANAGER_BUSY`).
