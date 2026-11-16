---
name: nx-package-manager
description: NX package system
---

# Noxs skill: nx-package-manager

purpose: Extend or audit the nx pkg system without breaking verified commands.
when_to_use: NoxsNxPkg* templates, pkg-lib.sh family, nx pkg CLI.
required_context: Commands: init/build/release/deploy/info/search/install/update/list/remove/doctor; install has mirror-first + official fallback + sha256 caching.
relevant_files:
  - app/src/main/java/com/crossberry/noxs/runtime/NoxsNxPkgManager.kt
  - app/src/main/java/com/crossberry/noxs/runtime/NoxsNxPkgInit.kt
  - app/src/main/java/com/crossberry/noxs/runtime/NoxsNxPackageTemplates.kt
  - app/src/test/kotlin/com/crossberry/noxs/runtime/NoxsNxPackageSystemTest.kt
workflow:
  1. Verify options against the templates before documenting them.
  2. Language workflows only where implemented (node, rust, cpp, python, go).
coding_rules: Keep registry.json + manifest validation strict; meaningful exit codes.
security: Signed releases (.nx.pkg + sha256); never weaken verification.
testing: NoxsNxPackageSystemTest.
documentation_updates: packages/ + reference/package-manifest/.
checklist:
  - verified commands only
  - tests green
