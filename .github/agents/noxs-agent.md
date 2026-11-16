# Noxs Coding Agent — operating profile

This file defines how an autonomous coding agent operates on the Noxs
repository (used by repository automation and agent runners). `AGENTS.md`
remains the canonical rule set.

## Identity & scope

- Product: Noxs by CrossberryWeb — Android terminal app running Ubuntu
  24.04 LTS ARM64 through proot. Kali Linux ARM64 is planned. Noxs is not
  the private NoxOS project.
- The agent works on this repository and, through the docs-sync workflow,
  proposes documentation changes to `crossberry-in/nxos-docs` as pull
  requests. It never pushes documentation directly to main.

## Mandatory workflow (agent must follow every step)

STEP 1 — Read `AGENTS.md`.
STEP 2 — Read the relevant `.agent/skills/<name>/SKILL.md` files.
STEP 3 — Inspect the actual implementation before changing anything.
STEP 4 — Understand related components (bridges, templates, metadata).
STEP 5 — Implement the requested change; preserve existing behavior.
STEP 6 — Add or update tests (`:app:testDebugUnitTest`).
STEP 7 — Update `app/src/main/resources/documentation/*.json` metadata.
STEP 8 — Run `python3 scripts/doc_sync.py check` (must PASS) and
         `python3 scripts/doc_sync.py affected <changed files>`.
STEP 9 — Update affected nxos-docs pages via the docs-sync PR flow.
STEP 10 — Run `./gradlew :app:assembleDebug` and CLI checks
          (`sync-cli.sh`, shellcheck, `test-code-cli.sh`) when relevant.
STEP 11 — Prepare a Git diff summary and change report.
STEP 12 — Open a pull request; never mark complete while documentation is
          missing; never claim CI success without checking Actions.

## Security boundaries

- Never commit, log, or echo credentials. Never expose NoxOS internals.
- Never request or add Android permissions beyond the documented catalog
  (`app/src/main/resources/documentation/permissions.json`).
- Never bypass Android security restrictions or weaken SAF validation.
- Cross-repo sync uses a fine-grained token stored ONLY as an Actions
  secret (never in code, logs, or workflow files); workflows get minimum
  permissions (`contents: read`, per-job `pull-requests: write` where
  needed).

## Reporting

Every completed task reports: audit findings, changed files, test/build
results (real output), documentation pages updated, CI status with evidence.
