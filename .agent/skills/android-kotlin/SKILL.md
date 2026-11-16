---
name: android-kotlin
description: Kotlin/Android app layer changes
---

# Noxs skill: android-kotlin

purpose: Modify activities, services, views, ViewBinding layouts safely.
when_to_use: Changes in app/src/main/java/com/crossberry/noxs/ui or emulator-free app code.
required_context: UI uses ViewBinding + XML + Material 3 (never Compose).
relevant_files:
  - app/src/main/java/com/crossberry/noxs/ui/ (all activities)
  - app/src/main/res/layout/, values/ (strings, themes)
  - app/src/main/AndroidManifest.xml
workflow:
  1. Read the Activity + its layout XML + strings before changing a screen.
  2. Preserve runtime/security behavior (permission launches, bridge handling).
  3. Keep the terminal-first identity: near-black surfaces, restrained green accent.
  4. Accessibility: contrast, touch targets, font scaling, loading/error states.
coding_rules: Explicit null handling; no runCatching{}.let{} type-hiding; comments explain why.
security: Never add a permission without a use-at-request flow; document it in permissions.json.
testing: :app:testDebugUnitTest + :app:assembleDebug.
documentation_updates: manifest changes -> permissions.json + android/app-permissions/ docs.
checklist:
  - layout + strings inspected
  - no behavior regressions in permission flows
  - build green
