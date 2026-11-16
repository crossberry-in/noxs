---
name: storage-integration
description: SAF storage + direct mount integration
---

# Noxs skill: storage-integration

purpose: Change storage access without weakening Android security.
when_to_use: NoxsStorageBridge, NoxsStorageMount, storage CLI, StorageActivity.
required_context: SAF-first model — SAF grants are command-based, never POSIX. Direct /sdcard bind = Android all-files grant + explicit 'noxs storage mount' toggle, applies to NEW sessions only.
relevant_files:
  - app/src/main/java/com/crossberry/noxs/runtime/NoxsStorageBridge.kt
  - app/src/main/java/com/crossberry/noxs/runtime/NoxsStorageMount.kt
  - app/src/main/java/com/crossberry/noxs/ui/StorageActivity.kt
  - app/src/main/java/com/crossberry/noxs/ui/TerminalActivity.kt (picker + bridge)
workflow:
  1. Validate persisted URI permissions read+write independently.
  2. Make setup idempotent; never erase valid mappings when one check fails.
  3. Status output must be truthful per category (never assume unselected folders).
  4. Direct grant flow: app-specific Settings intent first, generic fallback, recheck on return.
coding_rules: Errors suggest the next step (noxs-setup-storage / noxs storage map <category>).
security: Never request all-files-access implicitly; SAF stays the recommended model.
testing: NoxsStorageMountTest; manual device checks for picker flows.
documentation_updates: files/storage-access/, android/external-sdcard/, troubleshooting/storage-errors/.
checklist:
  - per-category truthfulness preserved
  - no silent grants
  - docs updated
