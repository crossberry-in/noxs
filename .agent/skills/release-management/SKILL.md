---
name: release-management
description: Release process
---

# Noxs skill: release-management

purpose: Prepare and verify releases without inventing status.
when_to_use: Release tasks — ONLY when the user explicitly requests a release/tag.
required_context: Tags are created ONLY on explicit request; Play Protect requires no SEND_SMS permission (system SMS composer is used).
relevant_files:
  - .github/workflows/installer.yml
  - .github/workflows/android-ci.yml
  - nx-installer/
workflow:
  1. Distinguish implemented -> tested -> merged -> released; never conflate.
  2. Verify the APK artifact exists in the Actions run before reporting.
  3. Changelog entries follow Added/Changed/Fixed/Removed/Security/Breaking.
coding_rules: No release claims without a verified tag + artifacts.
security: Release builds keep the no-SMS-permission guarantee.
testing: Full unit suite + assembleRelease before tagging.
documentation_updates: nxos-docs changelog/ page.
checklist:
  - explicit user request recorded
  - artifacts verified
