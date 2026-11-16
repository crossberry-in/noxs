---
name: documentation
description: Noxs documentation (app-side metadata)
---

# Noxs skill: documentation

purpose: Keep the metadata registry and app-side docs accurate.
when_to_use: Any command/feature/permission/API change; docs coverage work.
required_context: app/src/main/resources/documentation/ is the single source of truth; scripts/doc_sync.py validates it.
relevant_files:
  - app/src/main/resources/documentation/commands.json
  - app/src/main/resources/documentation/features.json
  - app/src/main/resources/documentation/permissions.json
  - app/src/main/resources/documentation/apis.json
  - scripts/doc_sync.py
workflow:
  1. Update the metadata for the changed surface.
  2. python3 scripts/doc_sync.py check --sitemap <downloaded sitemap> (network verify).
  3. python3 scripts/doc_sync.py affected <files> -> update nxos-docs pages.
coding_rules: Statuses limited to implemented/experimental/planned; routes verified live.
security: Never document unreleased features as stable; never publish API keys.
testing: DocumentationMetadataTest.
documentation_updates: THE TASK ITSELF.
checklist:
  - metadata consistent
  - coverage report clean
