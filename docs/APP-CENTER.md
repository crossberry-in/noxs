# Noxs App Center

Status: first implementation contract
Parent plan: [APP-ECOSYSTEM.md](APP-ECOSYSTEM.md)
Target: Android host application

## Purpose

App Center is the discoverable catalogue for Noxs-owned host tools and optional integrations. It is not an Android app store and must not imply that catalogue cards are installed Android APKs. Linux guest packages remain managed by the NX package system.

## First screen

- Top app bar: App Center title, search action, and a compact profile/settings entry only when those destinations exist.
- Search field: filters title, summary, category, and keywords locally.
- Category chips: All, System, Productivity, Media, Developer, Integrations.
- Featured section: only entries whose capability is implemented and available on this build.
- Catalogue list/grid: icon, title, one-line description, category, and truthful state label.
- Empty state: explain that no entries match and provide a clear way to clear the search/filter.
- Error state: show retry only for data sources that can actually fail; the static built-in catalogue must not show a fake network error.

## Catalogue model

Each entry must have a stable ID and fields: id, title, summary, category, icon resource, availability, launchTarget, keywords, and source. Availability values are AVAILABLE, PLANNED, or UNAVAILABLE. Source values are BUILT_IN, NX_GUEST, or INTEGRATION.

Do not derive launch targets from display names. Use an explicit allowlisted destination map.

## Initial entries

| Entry | Source | Initial state | Action |
|---|---|---|---|
| Terminal | BUILT_IN | AVAILABLE only after source verification | Open existing terminal |
| Code Editor | BUILT_IN | AVAILABLE only if code-server screen exists | Open existing editor |
| Web Browser | BUILT_IN | AVAILABLE only if guarded browser exists | Open existing browser |
| File Manager | BUILT_IN | PLANNED until verified | Show details, no fake launch |
| App Manager | BUILT_IN | PLANNED | Show details |
| NX Package Installer | NX_GUEST | PLANNED until installer UI is integrated | Explain NX package workflow |
| Settings | BUILT_IN | AVAILABLE only if destination exists | Open existing settings |
| System Monitor | BUILT_IN | PLANNED | Show details |
| Plugin Manager | INTEGRATION | PLANNED until permission bridge is integrated | Show details |
| AI Assistant | INTEGRATION | PLANNED until AI screen is integrated | Show details |

Availability must be verified against the current source tree before marking an entry AVAILABLE.

## Interaction rules

1. Tapping an AVAILABLE card launches only its allowlisted existing destination.
2. Tapping a PLANNED or UNAVAILABLE card opens details that clearly state its status; it must not display an enabled Install/Open button.
3. Search and category filtering compose together and preserve the selected category while typing.
4. Back navigation restores the catalogue search and category state.
5. Use accessible labels, touch targets, font scaling, dark/light theme colors, and reduced-motion-aware transitions.
6. Do not add broad Android permissions for catalogue display.

## Visual direction

Use the existing Noxs design system rather than introducing a second UI framework. Prefer a calm iOS-inspired surface: rounded cards, restrained translucent surfaces where supported, clear hierarchy, and short transitions. Content and status labels must remain readable at high font scale and under reduced transparency.

## Suggested implementation slices

1. Inspect the existing Activity, navigation, theme, drawable, and layout conventions.
2. Add a pure Kotlin catalogue model and filtering function with unit tests.
3. Add the App Center screen using the repository's current Android UI stack.
4. Wire only verified existing destinations.
5. Add UI tests for search, category filters, status handling, and back-state restoration.
6. Run debug assembly, unit tests, lint, and repository validation.

## Acceptance criteria

- No planned feature is represented as installed or launchable.
- No card action can launch an arbitrary component or URL.
- Search is case-insensitive and matches title, summary, category, and keywords.
- Category filtering and search work together.
- The screen works in light/dark themes and with larger system font settings.
- Existing app navigation and terminal behavior are not regressed.
