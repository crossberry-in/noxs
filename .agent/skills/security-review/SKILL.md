---
name: security-review
description: Security review pass
---

# Noxs skill: security-review

purpose: Review changes for security regressions before merge.
when_to_use: Before merging any change touching permissions, storage, bridge, network.
required_context: SAF URI grants, runtime permissions, bridge IPC validation, proot isolation limits.
relevant_files:
  - app/src/main/java/com/crossberry/noxs/runtime/NoxsUrlGuard.kt
  - app/src/main/java/com/crossberry/noxs/runtime/NoxsAndroidBridge.kt
  - app/src/main/java/com/crossberry/noxs/runtime/NoxsStorageBridge.kt
  - app/src/main/AndroidManifest.xml
workflow:
  1. Check: no new exported components; no weaker URI validation; no new default binds.
  2. Bridge request ids stay validated; control dirs refuse symlinks.
  3. proot gives app-sandbox isolation only — never claim hardware-virtualization
     or separate-device security in docs.
coding_rules: Deny by default; explicit allowlists; stable error codes without input echo.
security: THE TASK ITSELF.
testing: NoxsUrlGuardTest, NoxsAndroidBridgeTest, no-leak tests must stay green.
documentation_updates: architecture/security/ when the model changes.
checklist:
  - no-leak tests green
  - manifest diff audited
