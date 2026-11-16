# GitHub Copilot instructions — Noxs

`AGENTS.md` is the canonical rule set for this repository; these instructions
restate it for GitHub Copilot. When in doubt, follow `AGENTS.md` and the
matching `.agent/skills/<name>/SKILL.md`.

## What this project is

- Noxs by CrossberryWeb: Android terminal app (Kotlin, ViewBinding, XML
  layouts, Material 3 — never Jetpack Compose) that runs Ubuntu 24.04 LTS
  ARM64 through proot.
- Kali Linux ARM64 is planned, not installable. Legacy Debian installs are a
  read-only import. Noxs is NOT the private NoxOS project.

## Non-negotiable rules

1. Do not invent commands, options, APIs, features, URLs or statuses. The
   CLI templates (`NoxsCliTemplate.kt`, `NoxsNxTemplate.kt`) and the
   manifest are the implementation authority.
2. Do not weaken Android security: no silent grants; all-files access is a
   user-approved Settings flow and stays optional; SAF stays recommended.
3. Do not commit or print credentials (API keys, tokens). Do not expose
   NoxOS internals.
4. CLI errors must be truthful with a next step and a documentation URL from
   `NoxsDocsMap.kt` / `commands.json`.
5. Keep generated CLI scripts in sync via `bash scripts/sync-cli.sh` — never
   hand-edit `linux-runtime/launcher/noxs-cli`.

## Definition of done (every task)

- `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug` pass.
- `python3 scripts/doc_sync.py check` passes after any command/feature/
  permission/API change.
- Affected documentation pages (see `python3 scripts/doc_sync.py affected`)
  are updated in crossberry-in/nxos-docs or flagged for the docs-sync PR.
- Conventional commit message; no release tags unless explicitly requested.
