---
name: noxs-development
description: Noxs end-to-end development
---

# Noxs skill: noxs-development

purpose: Safely change anything in the Noxs Android terminal repository.
when_to_use: Any task that touches app behavior, CLI, storage, docs, or CI.
required_context: AGENTS.md (canonical rules), this skill, .agent/skills/ index.
relevant_files:
  - AGENTS.md
  - app/src/main/java/com/crossberry/noxs/runtime/ (core runtime)
  - linux-runtime/launcher/ (generated CLI scripts)
  - app/src/main/resources/documentation/ (metadata registry)
  - scripts/ (sync-cli.sh, doc_sync.py, test.sh)
workflow:
  1. Read AGENTS.md fully.
  2. Load the specific skill for the area you touch.
  3. Inspect the real implementation before editing.
  4. Implement, test, update metadata + docs, run doc_sync.py check.
  5. Build and report honestly.
coding_rules: See AGENTS.md 'Coding conventions' (Kotlin ViewBinding, shellcheck-clean templates).
security: Never weaken Android permission flows; never add silent grants.
testing: ./gradlew :app:testDebugUnitTest and :app:assembleDebug must pass.
documentation_updates: commands.json/features.json + affected nxos-docs pages.
checklist:
  - AGENTS.md read and followed
  - tests + build green
  - doc_sync.py check PASS
  - documentation updated or flagged
