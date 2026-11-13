# Noxs UI/UX Design System

Noxs uses an Android-native, terminal-first interface. The design system is inspired by Apple's emphasis on clarity, accessibility, craft, and restraint, translated into Android and Material 3 patterns.

The full agent workflow is in [the Noxs Apple-inspired design skill](../.agents/skills/apple-design/SKILL.md), adapted from [dickwu/apple-design-skill](https://github.com/dickwu/apple-design-skill).

## Design thesis

**A focused Linux workspace that feels native to Android and stays out of the way of the terminal.**

The terminal is the primary workspace. Navigation, setup, editor, files, services, and settings support that workspace rather than competing with it.

## Existing foundation

The current theme is `Theme.Material3.Dark.NoActionBar`. Preserve and reuse the semantic resources in `app/src/main/res/values/colors.xml` and `themes.xml`.

| Role | Resource | Current value |
|---|---|---|
| Canvas | `noxs_background` | `#000000` |
| Main surface | `noxs_panel` | `#111111` |
| Navigation surface | `noxs_nav` | `#191919` |
| Elevated surface | `noxs_elevated` | `#222222` |
| Divider/border | `noxs_border` | `#303030` |
| Brand/action | `noxs_accent` | `#20D866` |
| Primary text | `noxs_text` | `#FFFFFF` |
| Secondary text | `noxs_text_dim` | `#AAAAAA` |
| Warning | `noxs_warning` | `#FFB020` |
| Error | `noxs_error` | `#FF5252` |

Treat these as the starting palette, not proof of accessibility. Verify foreground/background contrast for every text and icon state before changing or adding components.

## Component rules

- Use Material 3 components and Android navigation conventions.
- Keep navigation, toolbars, dialogs, menus, and input controls consistent across screens.
- Use 48dp minimum interactive targets where practical.
- Use `sp` for text and support system font scaling.
- Use monospace for terminal content and technical values only.
- Use semantic colors consistently; never rely on color alone to communicate state.
- Keep primary actions explicit and outcome-oriented.
- Show progress, empty, error, offline, and retry states where applicable.
- Keep terminal content opaque and readable. Reserve translucent/blurred treatment for a small number of floating functional controls.
- Support reduced motion and a no-blur fallback.

## Screen composition

1. **Context:** title and current location/session/path.
2. **Primary content:** one clear task or workspace.
3. **Primary action:** one visually clear action where the screen needs one.
4. **Secondary actions:** grouped by relevance and revealed progressively.
5. **Feedback:** status and recovery information close to the affected content.

## Quality checklist

- [ ] Screen purpose is clear without explanation.
- [ ] Existing Noxs tokens are reused; no unnecessary hard-coded values.
- [ ] Material 3 / Android interaction conventions are followed.
- [ ] Layout works at narrow widths, landscape, and larger font scales.
- [ ] Interactive targets are comfortably tappable and accessible.
- [ ] TalkBack labels and traversal order make sense.
- [ ] Contrast is measured for important text and states.
- [ ] Loading, success, empty, error, and retry states are considered.
- [ ] Destructive actions can be cancelled and are clearly named.
- [ ] Motion is brief, useful, and optional.
- [ ] Terminal input, selection, scrolling, and keyboard behavior remain intact.
- [ ] Relevant tests and build tasks pass, or unverified items are disclosed.

## Source and attribution

This design system is an original Noxs adaptation inspired by [Apple Design Skill](https://github.com/dickwu/apple-design-skill) and Apple's [Human Interface Guidelines](https://developer.apple.com/design/human-interface-guidelines/). It does not imply affiliation with or endorsement by Apple.
