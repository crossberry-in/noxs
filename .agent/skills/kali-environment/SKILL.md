---
name: kali-environment
description: Kali Linux ARM64 (PLANNED — do not implement silently)
---

# Noxs skill: kali-environment

purpose: Record and verify the honest status of the Kali environment.
when_to_use: Any task mentioning Kali; documentation work about Kali.
required_context: Kali exists ONLY as an EnvironmentVariant model concept; there is no provider, no installer, no rootfs pipeline.
relevant_files:
  - app/src/main/java/com/crossberry/noxs/environments/model/EnvironmentModels.kt
  - app/src/main/resources/documentation/features.json (kali-environment: planned)
workflow:
  1. NEVER document Kali as installable or available.
  2. If asked to implement Kali: design a KaliProvider mirroring UbuntuProvider,
     add feature-flagged installer pipeline, and update features.json to
     experimental only when it actually installs.
coding_rules: Kali pages in nxos-docs must stay marked planned until real install works.
security: Same rootfs integrity requirements as Ubuntu would apply.
testing: features.json test asserts kali-environment stays 'planned' until real support exists.
documentation_updates: environments/kali-installation/ (planned status).
checklist:
  - Kali never presented as available
  - metadata status accurate
