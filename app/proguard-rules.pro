# Noxs ProGuard/R8 rules (original)

# Keep the JNI bridge names — they are called from C by exact symbol name.
-keep class com.crossberry.noxs.terminal.emulator.NativePty { *; }

# MiniJson reflection-free but keep the manifest model shapes for logging.
-keep class com.crossberry.noxs.shared.BootstrapManifest { *; }
-keep class com.crossberry.noxs.shared.BootstrapArtifact { *; }

# Coroutines
-dontwarn kotlinx.coroutines.**

# Remove verbose logging in release builds of the shared logger calls
# (NoxsLog is a ring buffer, intentionally kept for diagnostics).

# NXEditor (sora-editor + language-textmate): tm4e parses grammar/theme JSON
# via gson reflection — keep the model field names or release builds lose
# syntax highlighting and language configuration parsing.
-keep class org.eclipse.tm4e.** { *; }
-keep class io.github.rosemoe.sora.langs.textmate.** { *; }
-keep class io.github.rosemoe.sora.lang.analysis.** { *; }
-dontwarn org.eclipse.tm4e.**
-dontwarn io.github.rosemoe.sora.**
