---
name: rust-native
description: Rust/native component work
---

# Noxs skill: rust-native

purpose: Work on the native layer (libnoxs core, PTY bridge) where already implemented.
when_to_use: Changes under rust/ or JNI bindings in NoxsNativeCore.
required_context: Native code is intentionally minimal; prefer Kotlin-side fixes.
relevant_files:
  - rust/ (Cargo.toml, src/)
  - app/src/main/java/com/crossberry/noxs/runtime/NoxsNativeCore.kt
workflow:
  1. cargo check / cargo clippy before committing.
  2. Keep the JNI surface stable; version any protocol change.
coding_rules: No unsafe additions without a comment justifying invariants.
security: Native code must never bypass app-private storage boundaries.
testing: cargo test (if tests exist) + gradle build.
documentation_updates: architecture/rust-core/ when behavior changes.
checklist:
  - cargo check clean
  - JNI surface unchanged or documented
