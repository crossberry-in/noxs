# Noxs Terminal UX

The 0.3.0 terminal/UX upgrade: live setup status, touch gestures, scroll
modes, search, session status and a full terminal settings surface — layered
on top of the unchanged Linux engine (proot + Debian 12, real PTY, real sudo).

Nothing in this document changes the security model, the filesystem layout,
the shell, sudo, session manager or the installer itself. This is a
presentation, interaction and settings layer.

## Setup — live long-running operations

While setup runs, every long operation is tracked by `SetupOpTracker`
(pure Kotlin, monotonic `System.nanoTime` clock — no wall-clock, no fakes):

```
[ Noxs ] ⠋ Checking for interrupted dpkg configuration  00:01
[ Noxs ] ⠙ Checking for interrupted dpkg configuration  00:02
...
[ Noxs ] ✓ Checking for interrupted dpkg configuration  00:18
```

* Spinner: braille frames `⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏`, ~10 updates/s, rewritten in place
  with `CR` + `EL` — no new lines are created.
* Elapsed: `MM:SS` under an hour, `HH:MM:SS` above (`00:04`, `00:59`,
  `01:02:14`), 1 update/s, monotonic — backgrounding never resets it.
* When an operation is unusually long (60 s) the console prints once:
  `Still working — this operation may take a little longer.` Long is not an
  error.
* Operation end bakes a `✓ <name> <elapsed>` (or `✗` on failure) record with
  the real elapsed time; failures keep the full real error text below it.
* The slim toolbar mirrors the state: `⠋ <operation>` + elapsed. No progress
  percentages anywhere unless they are real (download bytes, tar entries).
* Live lines are clamped to the terminal's REAL column count (`CR`+`EL`
  rewrites must never wrap — an overflowing line would spill one new row per
  tick and flood the console with the same status). Below 24 columns the
  console falls back to `frame elapsed` only; the full operation name stays
  visible in the toolbar.

Operations include: Checking repositories · Checking for interrupted dpkg
configuration · Refreshing signed Debian package metadata · Installing
certificates · Updating certificate bundle · Verifying certificates ·
Installing Debian archive keyring · Repairing pending dpkg configuration ·
Preparing workspace · Preparing secure connections · Creating Linux account ·
Installing required packages · Verifying filesystem · Extracting rootfs ·
Preparing environment.

The tick loop is owned by the visible Activity (`repeatOnLifecycle(STARTED)`),
cancelled on stop/destroy; the setup process itself never depends on UI.

## Terminal gestures

| Gesture | Action | Notes |
|---|---|---|
| Single finger drag | Scroll history | fling with physics |
| Long press | Text selection | draggable handles, edge auto-scroll, Share |
| Two fingers apart / together | Font size −/+ | pinch, 10–28 sp, snapped to 0.5 sp |
| Two fingers drag | Scroll history | optional setting, off by default |

Priority: selection handles > long-press selection > pinch > two-finger
scroll > normal scroll. A decided two-pointer gesture is sticky until every
finger lifts; pinch wins ties; touches never send shell input.

## Scroll modes

* **Normal** — standard terminal scrolling, no extras.
* **Smart** (default) — follow output while anchored; scrolling away pauses
  the follow and counts arrivals: `↓ 18 new lines`; tap the pill or scroll
  back to the bottom to resume following.
* **History Mirror** — always reports the distance to the newest row:
  `LIVE • 124 lines behind`, `LIVE` when anchored.

Alternate-screen programs (vim, top, htop, less) always stay live.

## Scrollback

Bounded ring buffer per session, independent from the shell: default
10,000 lines, configurable 1,000–50,000, resizable live
(`TerminalBuffer.resizeScrollback`) — shrinking keeps the newest rows.
Clearing scrollback never touches the screen, shell history, filesystem or
running processes.

## Search

`Search` opens the toolbar search field: case-insensitive match over the full
document (scrollback + screen), `n/m` counter, ↑/↓ navigation with wraparound,
current match highlighted brightest. Pure buffer reads — the shell input
stream is never written and running programs are never interrupted.

## Session status

Subtle status line in the header: `● RUNNING <label> · PID <n>` (output in
the last 10 s), `○ IDLE`, `✓ EXITED (code)`, `✗ FAILED (code)`.

## Terminal toolbar

`↑ ↓ Copy Paste Search A− 14 A+ ⋮` — compact, toggleable
(`terminal.showToolbar`). The `⋮` menu: clear scrollback, save/share output,
reset font size, terminal settings. Tap the size label to reset.

## Settings → Terminal

Grouped rows with descriptions and accessible controls; every value persists
to the existing `noxs_settings` file under `terminal.*` keys and applies
live:

* **Appearance** — font size (10–28 sp), font family, line spacing, letter
  spacing, cursor style (block/underline/bar), blink on/off + period
  (200–1500 ms), cursor width (1–4), padding (0–24 dp), opacity (50–100%),
  theme (Noxs Dark / Black / High contrast / Paper).
* **Interaction** — pinch zoom, two-finger scrolling, long-press selection,
  auto-scroll selection, haptic feedback, toolbar visibility, shortcut-key
  row visibility (`Show shortcut keys` — the ESC/TAB/CTRL/arrows bar above
  the keyboard).
* **Scrolling** — scroll mode, follow live output, new-output indicator,
  auto-follow while running, scrollback size.
* **Behavior** — confirm exit (off by default), keep sessions alive in
  background, restore previous sessions, auto-focus terminal, preserve
  scroll position.
* **Advanced** — reduce animations, smooth text rendering, debug details.
* **Reset** — removes ONLY `terminal.*` keys, with confirmation. The Linux
  filesystem, packages, shell history, sessions and user files are never
  touched.

### Persisted keys

`terminal.fontSize`, `terminal.fontFamily`, `terminal.lineSpacing`,
`terminal.letterSpacing`, `terminal.cursorStyle`, `terminal.cursorBlink`,
`terminal.cursorBlinkPeriod`, `terminal.cursorWidth`, `terminal.padding`,
`terminal.opacity`, `terminal.theme`, `terminal.pinchZoom`,
`terminal.twoFingerScroll`, `terminal.selectionEnabled`,
`terminal.autoScrollSelection`, `terminal.hapticFeedback`,
`terminal.showToolbar`, `terminal.showExtraKeys`, `terminal.scrollMode`,
`terminal.followLiveOutput`, `terminal.showNewOutputIndicator`,
`terminal.autoFollowWhileRunning`, `terminal.scrollbackLines`,
`terminal.confirmExit`, `terminal.keepAlive`, `terminal.restoreSessions`,
`terminal.autoFocusTerminal`, `terminal.preserveScrollPosition`,
`terminal.reduceAnimations`, `terminal.textAntialias`, `terminal.debugOverlay`.

Values are clamped at load and save; invalid stored values can never reach
the terminal.

## Full screen

Ctrl+F (or the toolbar menu's `Full screen` / `Exit full screen`) toggles an
immersive terminal: the status bar, session tabs, action toolbar AND the
shortcut-key row are hidden, system bars hide (swipe to reveal transiently),
and the terminal takes the full viewport. The state persists in
`terminal.startFullscreen` — the same key as the `Start in full screen`
setting — so rotation and process death restore the last chrome state.
Sessions are never restarted by the toggle; the shell keeps running.

## Performance notes

* Renderer reuses Paint objects; nothing is allocated per frame.
* Search highlights are binary-sliced per visible window.
* Spinner ticks at 10 Hz only while the setup console is visible; elapsed
  text updates at 1 Hz.
* Pinch applies font size in 0.5 sp steps so PTY resizes stay rare.
* Scrollback is a fixed-capacity ring: O(1) row lookup at any size.
* Preference writes are `apply()` (async), one per user action.
