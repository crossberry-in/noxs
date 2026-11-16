---
name: ubuntu-environment
description: Ubuntu 24.04 LTS environment (the only installable env)
---

# Noxs skill: ubuntu-environment

purpose: Work on the Ubuntu installer, APT bootstrap, health checks.
when_to_use: NoxsInstaller, RootfsConfigurator, AptBootstrap, ubuntu status/update.
required_context: Ubuntu is the ONLY installable environment (BuiltinProviders).
relevant_files:
  - app/src/main/java/com/crossberry/noxs/environments/providers/BuiltinProviders.kt
  - app/src/main/java/com/crossberry/noxs/runtime/NoxsInstaller.kt
  - app/src/main/java/com/crossberry/noxs/runtime/RootfsConfigurator.kt
  - app/src/main/java/com/crossberry/noxs/runtime/NoxsAptSetup.kt
workflow:
  1. Keep the official Ubuntu repositories and keyring checks intact.
  2. nx cert-fix is the repair path — do not duplicate it in new code.
coding_rules: Never hardcode distro identity; read /etc/os-release.
security: Signed bootstrap artifacts only; never weaken certificate checks.
testing: RootfsAptSourcesTest, RootfsDnsConfigTest + full unit suite.
documentation_updates: environments/ubuntu-*/ and getting-started/installation/.
checklist:
  - os-release-driven identity preserved
  - tests green
