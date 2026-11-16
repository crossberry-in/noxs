---
name: documentation-sync
description: Docs sync automation
---

# Noxs skill: documentation-sync

purpose: Run and extend the documentation synchronization pipeline.
when_to_use: CI docs-sync work; drift detection; coverage reports.
required_context: Pipeline = change detection -> component mapping -> metadata update -> validation -> docs PR (review, never auto-merge).
relevant_files:
  - scripts/doc_sync.py
  - .github/workflows/docs-sync.yml
  - nxos-docs: .github/workflows/docs-validate.yml
workflow:
  1. check: metadata + CLI coverage + docs-map alignment (+ optional sitemap).
  2. affected: map changed files to docs pages via AFFECTED_MAP.
  3. report: coverage numbers computed from the repo (never hardcoded).
coding_rules: Deterministic output; dependency-free Python; no network in check (sitemap is optional).
security: Cross-repo PRs use minimum-scope tokens from Actions secrets only.
testing: doc_sync.py check must PASS locally before CI.
documentation_updates: nxos-docs AGENTS.md documents the docs-repo side.
checklist:
  - check PASS
  - affected pages listed
