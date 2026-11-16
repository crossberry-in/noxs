---
name: android-permissions
description: Android permission catalog
---

# Noxs skill: android-permissions

purpose: Add or audit Android permissions and their user-facing flows.
when_to_use: Manifest changes, NoxsAndroidPermissions, PermissionCenter.
required_context: Permissions are requested at use time, never at startup; catalog mirrors the manifest.
relevant_files:
  - app/src/main/AndroidManifest.xml
  - app/src/main/java/com/crossberry/noxs/runtime/NoxsAndroidPermissions.kt
  - app/src/main/resources/documentation/permissions.json
workflow:
  1. Manifest edit -> permissions.json entry in the same change.
  2. DocumentationMetadataTest enforces manifest <-> permissions.json sync.
coding_rules: No SMS permission (system composer flow); MANAGE_EXTERNAL_STORAGE only for the opt-in mount.
security: Least privilege; every runtime permission has a user-visible trigger.
testing: DocumentationMetadataTest permission mirror test.
documentation_updates: android/app-permissions/ + specific capability pages.
checklist:
  - permissions.json updated
  - tests green
