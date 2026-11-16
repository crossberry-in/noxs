---
name: testing-ci
description: Testing & CI
---

# Noxs skill: testing-ci

purpose: Run, extend and repair the test/CI matrix.
when_to_use: Test failures, CI red, new test coverage.
required_context: android-ci.yml builds + tests every push; unit suite is JVM-only.
relevant_files:
  - .github/workflows/android-ci.yml
  - scripts/test.sh, scripts/test-code-cli.sh, scripts/test_nx_functions.sh
  - app/src/test/kotlin/ (46+ suites)
workflow:
  1. Reproduce locally: ./gradlew :app:testDebugUnitTest.
  2. CLI changes: sync-cli.sh + bash -n + shellcheck + test-code-cli.sh.
  3. Never mark tests skipped to get green.
coding_rules: Tests are deterministic; no network-dependent assertions.
security: Tests must not embed real credentials.
testing: THE TASK ITSELF.
documentation_updates: architecture/testing/ when strategy changes.
checklist:
  - full suite green locally
  - CI green on push
