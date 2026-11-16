---
name: plugin-sdk
description: Plugin system & SDK
---

# Noxs skill: plugin-sdk

purpose: Work on plugin discovery, manifests, permissions, lifecycle.
when_to_use: NoxsNxPlugLib, PluginStore, plugins/ runtime.
required_context: Plugins declare explicit permission boundaries in their manifest; no unrestricted execution.
relevant_files:
  - app/src/main/java/com/crossberry/noxs/runtime/plugins/
  - app/src/main/java/com/crossberry/noxs/ui/PluginStoreActivity.kt
  - app/src/main/java/com/crossberry/noxs/runtime/NoxsNxPlugLib.kt
workflow:
  1. Validate manifests before install; keep compatibility checks.
  2. Lifecycle errors must be actionable (which permission, which step).
coding_rules: Manifest fields documented in reference/plugin-manifest-reference/.
security: Permission boundaries are enforced, not advisory; deny by default.
testing: plugin-related unit tests + store UI build.
documentation_updates: plugins/ + reference/plugin-manifest-reference/.
checklist:
  - manifest validation intact
  - boundaries enforced
