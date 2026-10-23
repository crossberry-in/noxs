# Noxs Debian Mirror

The **Noxs Debian mirror** is a bounded, honest Debian 12 (bookworm) subset
mirror that accelerates `nx pkg` downloads. It is synced by a Rust
background worker and published to **https://noxs-mirror.netlify.app/**.

## What it is (and is not)

- **Upstream**: the official Debian archive
  (`https://ftp.debian.org/debian`), suite `bookworm`, component `main`,
  architectures `arm64` + `amd64`.
- **Scope**: a curated package set plus the full dependency closure
  computed from the upstream `Packages` index, bounded by a byte budget
  (600 MB by default). Anything over budget is skipped and reported —
  `api/mirror/status.json` sets `partial: true` whenever the mirror is not
  complete. The mirror never pretends to be a full Debian archive.
- **Not an apt sources replacement**: real devices keep their apt sources
  pointed at the official Debian repositories. The Noxs layer uses the
  mirror as a download accelerator for packages it has indexed and falls
  back to the official URL automatically. No signature bypass, ever.

## Integrity model

Every `.deb` on the mirror is downloaded from the upstream `Filename` path
and re-verified against the SHA-256 recorded in the upstream index before
it is published. The `nx pkg` installer additionally verifies each file
against the hash from the **signed** apt metadata (`apt-get --print-uris`)
and serves downloads from a sha256-keyed cache — an HTTP 200 response alone
is never treated as proof of authenticity.

## The worker

`noxs-mirror-sync` is a Rust binary (`ureq` + `flate2` + `sha2` +
`serde_json`, bounded thread pool, retries with backoff) that:

1. fetches and parses the upstream `Packages.gz` indexes per architecture,
2. computes the dependency closure of the curated list
   ([`packages.txt`](https://github.com/crossberry-in/noxs-mirror/blob/main/packages.txt)
   in the mirror repository) under the byte budget,
3. downloads the selected `.deb` files concurrently, verifying SHA-256,
4. publishes the pruned `Packages` index plus the static package API
   documents, and snapshots the official Noxs Git-registry metadata
   (best effort).

The GitHub Action in the mirror repository runs the worker on every push
and daily on a schedule, then deploys the result to Netlify through the
deploy API (zip upload + ready-state polling). There is no Node.js in the
pipeline.

## API (static JSON on Netlify)

| Spec endpoint                      | Published as                       |
|------------------------------------|------------------------------------|
| `GET /api/health`                  | `/api/health.json`                 |
| `GET /api/mirror/status`           | `/api/mirror/status.json`          |
| `GET /api/packages/search?q=<name>`| `/api/packages/index.json` — the CLI caches it with a TTL and filters locally, because static hosting cannot filter server-side |
| `GET /api/packages/<name>`         | `/api/packages/<name>.json`        |
| `GET /api/packages/<name>/versions`| `/api/packages/<name>/versions.json` |
| `GET /api/packages/<name>/download`| `/api/packages/<name>/download.json` (direct `.deb` URLs + SHA-256 per architecture) |

## CLI integration

`nx pkg` reads the mirror through these knobs:

| Variable                  | Default                              | Meaning                                  |
|---------------------------|--------------------------------------|------------------------------------------|
| `NOXS_PKG_MIRROR`         | `https://noxs-mirror.netlify.app`    | mirror base URL; `off` disables it       |
| `NOXS_PKG_REQUIRE_MIRROR` | `0`                                  | `1` fails hard with `MIRROR_UNAVAILABLE` when the mirror is down |
| `NOXS_PKG_CACHE_DIR`      | `~/.noxs/cache/pkg`                  | metadata + sha256-keyed `.deb` cache     |
| `NOXS_PKG_CACHE_TTL`      | `3600`                               | metadata cache lifetime in seconds       |

Run `nx pkg doctor` to check mirror reachability, cache writability, apt
lists, the CA bundle and the dpkg state in one pass.

## Source

The worker lives in the
[`crossberry-in/noxs-mirror`](https://github.com/crossberry-in/noxs-mirror)
repository.
