# Noxs Developer Studio — Project Creator

Status: implementation specification
Target: Noxs Android host app + Noxs Linux guest workspace

## Repository boundary

`crossberry-in/NoxOS-App` is a public APK-release repository. Its README states that the NoxOS OS source is private and that the public repository contains signed preview APKs and checksums, not Android application source. Do not copy or assume unavailable source code from it. Use it only as a visual/product reference. Developer Studio belongs in the source repository `crossberry-in/noxs`.

## Goal

Add a built-in Developer Studio to Noxs so users can create, inspect, edit, build, and run projects using the Noxs Linux guest toolchain. It must work from the phone UI and persist projects in a user-selected Noxs workspace.

## Supported project types

| Template | Extension / language | Initial files | Build/run |
|---|---|---|---|
| Kotlin app/library | `.kt` | `settings.gradle.kts`, `build.gradle.kts`, `src/main/kotlin/Main.kt`, `README.md` | Gradle when JDK + Gradle are installed |
| Java app/library | `.java` | `settings.gradle`, `build.gradle`, `src/main/java/Main.java`, `README.md` | Gradle when JDK + Gradle are installed |
| Mozhi | `.mz` | `mozhi.toml`, `src/main.mz`, `README.md` | `mz run src/main.mz` when Mozhi CLI is installed |
| Rust | `.rs` | `Cargo.toml`, `src/main.rs`, `README.md` | `cargo run` when Rust toolchain is installed |

Do not label Kotlin/Java templates as Android APK projects unless Android Gradle Plugin, SDK, and required build tools are detected. Start with JVM console/library templates; add Android application templates as a separate validated template.

## UX flow

1. Open Developer Studio from the Noxs app drawer/App Center.
2. Choose New Project.
3. Select Kotlin, Java, Mozhi, or Rust.
4. Enter project name and choose a workspace folder.
5. Preview generated files and starter code.
6. Create project; reject path traversal, invalid names, and overwriting existing non-empty folders without explicit confirmation.
7. Open project in the editor; show Build and Run actions only when the required toolchain is detected.

## Project layout

Default workspace: `$HOME/Projects/<project-name>`.

Every project includes a README with language, template version, prerequisites, and exact build/run commands. Keep source files in conventional directories. Do not mix generated metadata with user source files.

## Starter code requirements

- Kotlin: a minimal `fun main()` program and a Gradle application configuration.
- Java: a minimal `public final class Main` with `main` and Gradle application configuration.
- Mozhi: a minimal valid `.mz` entry point based on the installed Mozhi version; verify syntax against the actual `mz` CLI before shipping.
- Rust: a minimal `fn main()` and valid Cargo manifest.

Template generation must use static, versioned templates. Escape project names and never interpolate raw user input into shell commands. Invoke tools using argument arrays or a safe process API, not shell string concatenation.

## Toolchain detection

Detect `java`, `javac`, `gradle`, `mz`, `rustc`, and `cargo` independently. Show installed version and missing prerequisites. Never auto-install packages without the user's explicit action. Build output must show exit code and preserve logs.

## Architecture

- `DeveloperStudioActivity`: project list and create-project entry point.
- `ProjectTemplate`: ID, display name, language, file map, required tools, template version.
- `ProjectGenerator`: validates name/path and writes files atomically under the selected workspace.
- `ToolchainDetector`: probes executables and versions in the Noxs guest environment.
- `ProjectRunner`: invokes allowlisted tool commands in the selected project directory.
- `ProjectWorkspace`: owns project path selection and editor handoff.

Keep project generation independent from UI so it can be unit-tested. Reuse the existing Noxs terminal/session bridge; do not create a second Linux runtime.

## Security and reliability

- Use app-private storage or Android's Storage Access Framework for user-selected folders.
- Canonicalize and validate all paths; reject writes outside the chosen workspace.
- Do not run generated code automatically on project creation.
- Build/run always requires an explicit user tap.
- Show a confirmation before destructive overwrite or delete operations.
- Handle missing toolchains and process cancellation without crashing the app.

## Acceptance criteria

- Kotlin, Java, Mozhi, and Rust templates create the documented files.
- Generated projects can be reopened after app restart.
- Search/listing does not scan outside the selected workspace.
- Toolchain status is accurate and does not claim builds succeeded without running them.
- Build/run output and exit status are visible to the user.
- Unit tests cover invalid names, path traversal, existing folders, template file maps, and missing toolchains.
