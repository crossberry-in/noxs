# repositories/

Reference APT source list. `noble.sources` is the final Ubuntu 24.04 LTS
configuration installed by RootfsConfigurator as the sole
`/etc/apt/sources.list.d/noxs.sources` file (arm64/armhf form; amd64 builds
use archive.ubuntu.com + security.ubuntu.com instead — same suites). During
first setup, the same signed repositories are temporarily served over HTTP
only to install and verify `ca-certificates`; initialization then switches to
HTTPS and requires a successful authenticated `apt-get update`.

Noxs ships exactly ONE environment (Ubuntu 24.04 LTS, Noble Numbat) — no
distribution selector, no Debian fallback (Ubuntu-first spec). The old
`bookworm.sources` reference was removed with the Debian environment.
