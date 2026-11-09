# Noxs Built-in App Ecosystem

Status: implementation plan / first integration milestone
Target: Android host application (not the Ubuntu guest desktop)

## Goal

Provide a consistent, discoverable collection of Noxs system tools and optional apps. Keep host-side Android capabilities separate from Linux guest applications. Do not imply that Noxs is a replacement Android operating system.

## First milestone

1. App Center: searchable catalogue of built-in Noxs tools and optional integrations.
2. App Manager: list installed/available Noxs modules, open supported modules, show version and status, and uninstall only modules explicitly owned by Noxs.
3. Package Installer: connect to the existing NX package system; display package metadata, architecture, checksum verification, permissions, progress, and errors before/after installation.
4. Login & Profile Manager: manage profiles and credentials inside the Noxs Linux guest only. Never claim to control Android device login, lock screen, or Android user accounts.
5. Settings and System Monitor: expose Noxs settings and guest resource/session status.

## App catalogue

### Core
- Terminal (existing)
- File Manager (Noxs guest files; use Android Storage Access Framework for user-selected shared files)
- App Center
- App Manager
- Package Installer (NX package system)
- Login & Profile Manager (guest accounts only)
- Settings
- System Monitor
- Activity Center

### Productivity and media
- Web Browser (reuse the existing guarded Noxs browser)
- Code Editor (reuse the existing code-server integration)
- Text Editor
- Image Viewer (PNG, JPEG, WebP, SVG where supported)
- Video Player
- Music Player
- Archive Manager
- Calculator
- Notes
- Screenshot helper (Android-supported capture flows only)

### Advanced integrations
- Plugin Manager (use the existing Noxs permission and plugin bridge model)
- AI Assistant (reuse the existing Noxs AI agent and explicit approval flow)
- Download Manager
- Backup & Restore
- Network status
- Update Manager

## UX principles

- iOS-inspired translucent surfaces, restrained blur, rounded cards and icons.
- Respect Android system bars, display cutouts, font scaling, accessibility and reduced-motion settings.
- Search, category filters, app details, installed/available state, and clear empty/error/loading states.
- Use consistent navigation and motion; avoid decorative animation that delays actions.
- Dark and light themes with adequate contrast.

## Security boundaries

- No Android root requirement and no Android lock-screen/login replacement.
- Guest credentials remain in the guest environment; never store or log passwords in the host app.
- Package installation must use the existing NX verification pipeline; never execute an unverified download.
- Plugins request scoped permissions through the existing Permission Center.
- File access uses scoped Android APIs and explicit user selection; do not silently scan shared storage.
- Browser and editor keep their existing local-origin and WebView restrictions.
- Destructive operations require a clear confirmation and must identify whether they affect the guest or host.

## Integration order

1. Inventory existing activities, navigation, package adapters, Permission Center, browser, editor, and settings.
2. Implement the app catalogue data model and App Center screen using the current UI stack.
3. Connect App Manager actions only to capabilities that already exist.
4. Connect Package Installer to the NX package resolver and verified installer.
5. Add guest-only Login & Profile Manager after confirming the existing guest user-management API.
6. Add remaining tools incrementally with unit/UI tests.

## Acceptance criteria

- Catalogue entries accurately distinguish implemented, planned, and unavailable features.
- Every action either opens a real existing feature or gives a clear unavailable explanation; no fake success.
- Installer shows package identity, target architecture, checksum/verification state, progress, and failure details.
- Login/Profile screens clearly say they manage guest accounts, not Android device accounts.
- No new broad storage, root, accessibility, or device-admin permissions are added for convenience.
- Debug build, unit tests, lint, and repository validation pass before release.
