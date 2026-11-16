---
name: ai-integration
description: NX AI integration
---

# Noxs skill: ai-integration

purpose: Improve AI provider/model/config handling safely.
when_to_use: NoxsNxAiAgent, NoxsNxAiLib, ai-lib.sh, AI configuration pages.
required_context: Provider config is user-managed; the CLI never prints keys.
relevant_files:
  - app/src/main/java/com/crossberry/noxs/runtime/NoxsNxAiAgent.kt
  - app/src/main/java/com/crossberry/noxs/runtime/NoxsNxAiLib.kt
  - scripts/test_nx_ai.py
workflow:
  1. Error messages must not echo keys or endpoints with credentials.
  2. Only providers that are actually implemented may be documented.
coding_rules: Keys live in user config files with restrictive modes; never in logs.
security: Tool execution keeps the same permission boundaries as plugins.
testing: scripts/test_nx_ai.py + NoxsNxAiTest.
documentation_updates: ai/ pages (nx-ai, configuration, providers, api-keys, troubleshooting).
checklist:
  - no key leakage
  - provider claims accurate
