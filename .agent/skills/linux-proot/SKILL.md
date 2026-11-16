---
name: linux-proot
description: proot launch chain and binds
---

# Noxs skill: linux-proot

purpose: Understand and modify how the Ubuntu userspace is launched.
when_to_use: ProotLauncher, binds, /var/run/noxs host bind, guest process model.
required_context: proot -0 fakes uid via ptrace; guest inherits Android supplementary GIDs.
relevant_files:
  - app/src/main/java/com/crossberry/noxs/runtime/ProotLauncher.kt
  - app/src/main/java/com/crossberry/noxs/runtime/NoxsPaths.kt
  - linux-runtime/launcher/noxs-launch.sh (reference argv)
  - app/src/test/kotlin/com/crossberry/noxs/runtime/ProotLauncherTest.kt
workflow:
  1. buildArgv() is unit-tested — extend the test for any argv change.
  2. Binds are opt-in only (NoxsStorageMount.guestBinds is the decision point).
coding_rules: Never add silent binds; storage binds flow through NoxsStorageMount.
security: /sdcard bind requires BOTH the Android all-files grant and the user toggle.
testing: ProotLauncherTest + NoxsStorageMountTest green.
documentation_updates: architecture/architecture/, architecture/bootstrap/.
checklist:
  - argv changes covered by tests
  - no new default binds
