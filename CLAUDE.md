# CLAUDE.md — Noxs repository instructions (Claude)

Read `AGENTS.md` first — it is the canonical, binding rule set for this
repository. The rules below are the same rules restated for Claude; both
must be followed together.

## Project

Noxs by CrossberryWeb: Android terminal app providing Ubuntu 24.04 LTS
ARM64 userspace through proot. Kali Linux ARM64 is planned (not installable).
Noxs is NOT the private NoxOS project — never mix them.

## Before touching code

1. Inspect the real implementation (`NoxsCliTemplate.kt`, `NoxsNxTemplate.kt`,
   `NoxsAndroidBridge.kt`, `NoxsStorageBridge.kt`, `AndroidManifest.xml`).
2. Load the matching skill from `.agent/skills/` (e.g. `android-kotlin/`,
   `storage-integration/`, `cli-development/`) and follow its checklist.
3. Never invent commands, options, APIs, features or URLs. Verify against
   source; verify documentation routes against the deployed nxos-docs site.

## Hard rules

- Ubuntu-only installer today; Kali stays marked planned; legacy Debian is a
  read-only import.
- SAF-first storage: SAF folders are NEVER described as POSIX mounts. The
  /sdcard bind is a two-step opt-in (Android Settings + `noxs storage mount`).
- No silent permission grants; direct storage access stays optional; SAF stays
  the recommended path.
- No API keys, tokens or credentials in code, logs, tests or docs.
- Truthful CLI output: clear errors + next step + documentation URL; no fake
  success.

## Required loop for every change

1. Implement.
2. `./gradlew :app:testDebugUnitTest` — green required.
3. `./gradlew :app:assembleDebug` — green required.
4. If CLI templates changed: `bash scripts/sync-cli.sh`, `bash -n` +
   `shellcheck` on the regenerated launcher, `bash scripts/test-code-cli.sh`.
5. Update `app/src/main/resources/documentation/*.json`; then
   `python3 scripts/doc_sync.py check` — must PASS.
6. `python3 scripts/doc_sync.py affected <files>` → update the listed
   nxos-docs pages (same PR or flagged docs-sync PR).
7. Conventional commit message; no release tags unless explicitly requested;
   verify GitHub Actions before reporting success.

Definition of done lives at the bottom of `AGENTS.md`. An agent must not mark
a task complete while required documentation is missing.
