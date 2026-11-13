---
name: noxs-apple-design
description: Review and improve Noxs Android UI/UX using Apple's Human Interface Guidelines as design principles translated to native Android Material 3, Kotlin, and XML layouts. Use for UI audits, accessibility, Liquid Glass-inspired surfaces, navigation, typography, color, motion, dialogs, settings, and screen redesigns.
---

# Noxs Apple-inspired UI/UX skill

Use this skill whenever designing, reviewing, or changing Noxs UI. It is an Android adaptation inspired by [dickwu/apple-design-skill](https://github.com/dickwu/apple-design-skill) and Apple's Human Interface Guidelines. It is not an Apple platform port and does not make Noxs an iOS app.

## Product context

Noxs is an Android Linux environment and terminal application. Its interface should feel precise, calm, fast, technical, trustworthy, and distinctly Noxs. The terminal is a primary workspace, not a decorative card. Preserve the existing Noxs identity: near-black surfaces, restrained Noxs green accent, clear terminal typography, and functional density where the task requires it.

## First principles

Evaluate every screen against these eight questions:

1. **Purpose** — Is the screen's primary job immediately clear?
2. **Agency** — Can the user navigate, cancel, undo, retry, and recover?
3. **Responsibility** — Are permissions, network activity, destructive actions, and Linux sandbox boundaries explained honestly?
4. **Familiarity** — Do controls behave like Android controls and follow Material 3 conventions?
5. **Flexibility** — Does the layout adapt to small phones, tablets, landscape, font scaling, keyboard visibility, and accessibility settings?
6. **Simplicity** — Does every visible element earn its place?
7. **Craft** — Are alignment, spacing, states, copy, and transitions finished?
8. **Delight** — Is there a restrained signature moment that helps the task rather than decorating it?

## Platform and implementation rules

- Noxs is native Android, Kotlin, ViewBinding, XML layouts, and Material 3. Use the existing project architecture and dependencies. Do not introduce Jetpack Compose or a new UI framework unless explicitly requested.
- Translate Apple design principles into Android conventions. Prefer Material 3 components, Android system behaviors, edge-to-edge/safe insets, Android back navigation, and Android accessibility APIs. Do not copy iOS tab bars, navigation stacks, system dialogs, or gestures where Android has an established pattern.
- Inspect the existing Activity, XML layout, theme, color resources, drawable resources, and navigation flow before editing.
- Reuse existing Noxs theme tokens and styles. Do not scatter new hard-coded colors, dimensions, or text sizes through layouts.
- Keep UI separate from terminal/runtime/security logic. Never move Linux execution, permission checks, or environment state into view-only code.
- Do not claim a command, install, permission, or service succeeded until the runtime confirms it.
- Preserve terminal usability: selectable text, copy/paste, keyboard behavior, scrolling, pinch zoom, and readable monospace output.

## Noxs visual language

Use the existing tokens in `app/src/main/res/values/colors.xml` as the source of truth:

- Canvas: `noxs_background` (#000000)
- Main surface: `noxs_panel` (#111111)
- Navigation surface: `noxs_nav` (#191919)
- Elevated surface: `noxs_elevated` (#222222)
- Border: `noxs_border` (#303030)
- Brand/action accent: `noxs_accent` (#20D866)
- Primary text: `noxs_text` (#FFFFFF)
- Secondary text: `noxs_text_dim` (#AAAAAA)
- Warning/error: semantic warning and error tokens only

Keep green for primary actions, active selection, and positive status; do not use it as decoration on every component. Keep warning and error colors semantically distinct. If introducing light appearance, define a complete semantic light palette rather than inverting colors mechanically.

## Accessibility quality floor

- Support Android font scaling; avoid fixed-height text containers that clip enlarged text.
- Use scalable `sp` for text and `dp` for layout dimensions.
- Aim for 48dp touch targets for interactive controls, with sufficient separation.
- Provide meaningful content descriptions for icon-only actions; mark decorative icons as not important for accessibility.
- Maintain readable contrast. Verify actual color pairs rather than judging from screenshots. Aim for WCAG AA: 4.5:1 for normal text and 3:1 for large text and essential UI graphics.
- Never communicate state through color alone; pair color with text, icon, or shape.
- Preserve TalkBack traversal order, keyboard navigation where applicable, and visible focus states.
- Respect system animation and accessibility settings. Provide reduced-motion behavior for custom transitions.
- Test at the smallest supported width and the largest practical font scale.

## Layout and interaction

- Use a clear hierarchy: screen title, concise supporting context, primary task, then secondary actions.
- Prefer progressive disclosure over presenting every advanced setting at once.
- Keep primary actions easy to reach and label them with the result they cause (for example, "Start session", "Save changes", "Stop service").
- Use standard Android navigation patterns and Material 3 components for navigation, menus, sheets, dialogs, text fields, switches, and lists.
- Provide loading, success, empty, offline, error, and retry states where relevant.
- Destructive or irreversible actions require a clear explanation and a safe cancel path. Use undo when practical.
- Keep state across configuration changes and navigation where the existing architecture supports it.
- Avoid fixed screen widths and unnecessary fixed heights. Handle insets, landscape, split-screen, and keyboard resizing.

## Liquid Glass-inspired treatment

Use translucent or blurred materials sparingly, only for floating functional chrome such as a compact toolbar, floating action surface, or overlay controls. Do not place blur behind terminal output, dense lists, editor text, or primary content. Keep content surfaces opaque enough for legibility. Provide a solid fallback when blur is unsupported, disabled, or reduced-transparency preferences are active. Avoid nested glass layers, excessive glow, and large decorative gradients. Android Material 3 remains the interaction foundation.

## Typography and motion

- Use Android/system typography for navigation, controls, and utility labels.
- Use monospace only for terminal output, command previews, paths, and technical data.
- Maintain a small, consistent type scale and avoid tiny low-contrast metadata.
- Motion should communicate state or spatial continuity, be brief, interruptible, and never block the task. Avoid animation on frequently repeated terminal updates.
- Keep haptics optional and meaningful; never make them the only feedback.

## Noxs-specific screen checks

- **Terminal:** terminal remains the visual focus; toolbar chrome stays compact; terminal text remains selectable and legible; keyboard and shortcut row do not cover input; scroll position and session state remain understandable.
- **Setup:** show real progress and elapsed state; distinguish downloading, verifying, extracting, and ready; explain recoverable failures and resume behavior.
- **File browser/editor:** make current path and navigation context visible; preserve selection and scroll state; do not hide file operations in ambiguous icons.
- **Services/activity:** distinguish running, stopped, starting, failed, and unknown states with text plus semantic visuals; destructive stop actions are explicit.
- **Permissions/security:** explain what access is requested, why it is needed, and what Noxs can and cannot do. Never imply Android root access when operating inside the app sandbox.

## Review workflow

1. Identify the screen's purpose, audience, Android framework, and relevant user task.
2. Read the current screen code and all directly related layouts, theme resources, strings, and navigation code. Do not infer unseen UI from a filename.
3. Audit in this order: accessibility, Android conventions, visual hierarchy/craft, interaction states, and writing.
4. Create a compact change plan before editing: tokens, hierarchy, responsive behavior, signature detail, and motion.
5. Critique the plan for generic styling. Noxs should be recognizable through its terminal-first character and restrained green signal, not a generic glass dashboard.
6. Implement the smallest coherent set of changes. Preserve runtime behavior and existing architecture.
7. Run relevant unit tests, lint, and Android build when available. Report what was and was not verified.

## Review report

Use these sections when a review is requested:

- **Summary** — overall rating: Excellent / Good / Needs work / Critical issues; state the screen's purpose and signature.
- **Critical** — accessibility or usability failures that must be fixed.
- **Improvements** — findings tagged High, Medium, or Low, each with the problem, impact, and concrete Android fix.
- **Craft notes** — distinctiveness, hierarchy, restraint, and one thing to remove.
- **What works** — patterns worth preserving.
- **Platform notes** — Android/Material 3 and device-size considerations.

Cite upstream guideline file and heading when that reference has actually been read. Otherwise label recommendations as design judgment; never invent a guideline quotation.

## Upstream source

This local adaptation is inspired by the method and review structure of [Apple Design Skill](https://github.com/dickwu/apple-design-skill). The upstream project reproduces Apple's HIG text; this Noxs skill is an independently written Android adaptation and does not copy the full reference library. Consult the upstream repository and [Apple HIG](https://developer.apple.com/design/human-interface-guidelines/) for current source guidance.
