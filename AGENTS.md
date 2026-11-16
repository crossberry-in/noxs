# Noxs — Agent Instructions (canonical)

Read this file before changing any code in this repository. The tool-specific
files (GEMINI.md, CLAUDE.md, .github/copilot-instructions.md,
.github/agents/noxs-agent.md) repeat these rules verbatim for their agents —
if they ever disagree with this file, this file wins.

## What Noxs is

Noxs (by CrossberryWeb) is an Android terminal application that provides a
genuine Linux environment on the device through proot. It is NOT a desktop OS,
NOT an emulator, and NOT the private NoxOS project (keep them strictly
separate — never describe NoxOS internals as Noxs features).

- Primary and only installable environment: **Ubuntu 24.04 LTS ARM64**
  (see `app/src/main/java/com/crossberry/noxs/environments/providers/BuiltinProviders.kt`:
  "no distribution selector and no Debian fallback").
- **Kali Linux ARM64 is PLANNED.** Only the variant model mentions it
  (`environments/model/EnvironmentModels.kt`). Never document Kali as
  installable.
- Legacy Debian installs of upgrading users are imported read-only — not a
  provider, not advertised.
- Storage model: SAF-first (per-category, command-based, never a POSIX mount).
  The direct /sdcard bind is an explicit OPT-IN (Android all-files access +
  `noxs storage mount`). Never present SAF folders as mounted directories.

## Architecture map

- `app/` — Kotlin Android app (ViewBinding + XML layouts + Material 3;
  do NOT introduce Jetpack Compose).
  - `ui/` — activities (TerminalActivity is the bridge host and shell owner)
  - `runtime/` — CLI templates (`NoxsCliTemplate`, `NoxsNxTemplate`),
    bridges (`NoxsStorageBridge`, `NoxsAndroidBridge`), storage
    (`NoxsStorageMount`, `NoxsDocumentsProvider`), package system
    (`NoxsNxPkg*`), installer (`NoxsInstaller`, `RootfsConfigurator`,
    `ProotLauncher`), URL policy (`NoxsUrlGuard`), docs mapping
    (`NoxsDocsMap`)
  - `environments/` — Ubuntu provider + environment manager
  - `emulator/` — separate NoxOS emulator screen (do not extend for Noxs)
- `linux-runtime/launcher/` — installed CLI scripts. `noxs-cli` and
  `noxs-setup-storage` are GENERATED from `NoxsCliTemplate.kt` by
  `scripts/sync-cli.sh` — never edit them directly.
- `rust/` — native components where already implemented.
- `app/src/main/resources/documentation/` — command/feature/permission/API
  metadata registry (single source of truth; consumed by tests and
  `scripts/doc_sync.py`).
- `scripts/` — build/test/doc-sync tooling.
- `.github/workflows/` — android-ci.yml (build/tests), installer.yml,
  docs.yml.

## Non-negotiable rules

1. Inspect the real implementation before documenting or changing anything.
   `NoxsCliTemplate.kt` and `NoxsNxTemplate.kt` are the authority for CLI
   behavior; the manifest is the authority for permissions.
2. Never invent commands, options, APIs, features, URLs or implementation
   statuses. Mark planned features as planned.
3. Ubuntu is the primary environment; Kali is planned; Noxs ≠ NoxOS.
4. Do not weaken Android security: no silent grants, no unnecessary
   all-files-access, no permission added without a use-at-request flow.
5. Never print, log, or commit API keys, tokens, or other credentials. Never
   expose private NoxOS source code.
6. Error messages must be truthful: report failures, suggest the next step,
   and link the relevant documentation page. Never fake success.
7. Keep generated files in sync: after editing `NoxsCliTemplate.kt`, run
   `bash scripts/sync-cli.sh` (CI diff-checks the launcher copies).

## Coding conventions

- Kotlin: ViewBinding, explicit null handling, no `runCatching { }.let {}`
  chains that hide types, comments explain WHY.
- Shell (templates): `set -u`, POSIX-safe constructs, every `$` written as
  `${'$'}` inside Kotlin raw strings, shellcheck-clean (CI enforces).
- Terminal output: plain, honest English; prompt identity stays
  `noxs@ubuntu:~$`.
- Help text: only real commands; every command family has a `noxs help
  <topic>` page and a `documentation_route` in commands.json.

## Documentation requirements (mandatory workflow)

Every functional change updates documentation in the same PR:

1. Update `app/src/main/resources/documentation/*.json` when commands,
   features, permissions or APIs change.
2. Run `python3 scripts/doc_sync.py check` — must PASS.
3. Run `python3 scripts/doc_sync.py affected <changed files>` to find the
   nxos-docs pages to review; update the relevant page in
   `crossberry-in/nxos-docs` (or open the generated docs-sync PR).
4. New CLI commands need: help page, commands.json entry, docs destination.
   A command without a verified documentation destination must not ship as
   stable (mark experimental and keep it out of the main help).
5. Never break existing nxos-docs routes.

## Build & test

- JVM unit tests: `./gradlew :app:testDebugUnitTest` (all must pass; the
  documentation metadata tests are part of the suite).
- Full build: `./gradlew :app:assembleDebug` (release signing only in CI).
- CLI sandbox checks: `bash scripts/sync-cli.sh && bash -n
  linux-runtime/launcher/noxs-cli && shellcheck linux-runtime/launcher/noxs-cli`
  and `bash scripts/test-code-cli.sh`.
- Report verification honestly. Never claim tests/builds passed without
  running them.

## Git workflow

- Meaningful conventional commit messages (`fix(storage): ...`,
  `docs(cli): ...`, `feat(bridge): ...`).
- Do not create release tags unless explicitly requested.
- Do not push to nxos-docs without a docs build (`mkdocs build --strict`).
- CI: android-ci.yml builds and tests every push; docs.yml deploys the docs
  site. Verify Actions results before reporting success.

## Definition of done

- [ ] Code inspected against real behavior (no assumptions).
- [ ] `./gradlew :app:testDebugUnitTest` green.
- [ ] `assembleDebug` green.
- [ ] `scripts/doc_sync.py check` PASS.
- [ ] Affected nxos-docs pages updated or flagged in the docs-sync PR.
- [ ] `sync-cli.sh` + shellcheck clean when CLI templates changed.
- [ ] No invented features; statuses accurate (implemented/experimental/planned).
- [ ] No secrets, no NoxOS internals, no broken links.
