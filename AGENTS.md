# Noxs repository agent guidance

## UI/UX work

For every UI/UX review, redesign, or visual change, read `.agents/skills/apple-design/SKILL.md` first and follow it throughout the task.

Noxs is a native Android application built with Kotlin, ViewBinding, XML layouts, and Material 3. Use the existing implementation and dependencies; do not introduce Jetpack Compose or replace the UI architecture unless the user explicitly asks.

Keep the Noxs terminal-first identity: near-black layered surfaces, restrained green accent, readable system typography, and monospace terminal content. Translate Apple Human Interface Guidelines into native Android and Material 3 conventions rather than copying iOS controls.

Before changing a screen, inspect its Activity, layout XML, theme/color resources, strings, and navigation. Preserve runtime/security behavior. Validate accessibility, font scaling, touch targets, contrast, loading/error/empty states, and responsive layouts. Run relevant tests and an Android build when possible, and report verification honestly.
