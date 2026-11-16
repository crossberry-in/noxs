# NX Android Integration

Noxs Android Integration turns the Noxs terminal into a first-class citizen of
the Android device — the same way Termux exposes Android features, but with
Noxs' strict, permission-first security model. Everything here is OPTIONAL:
nothing is granted at startup, every capability asks through Android's own
runtime permission UI or Settings, and every grant is revocable.

## Architecture

```
Noxs Terminal (guest CLI)                Noxs Android app (host)
─────────────────────────                ─────────────────────────
nx <cmd> == noxs <cmd>
      |
      v
/usr/local/bin/noxs                      TerminalActivity (foreground)
  storage_command (SAF categories)  <->    storage observer  -> NoxsStorageBridge
  document|sms|device|... commands  <->    bridge observer   -> NoxsAndroidBridgeHost
      |                                          |
      |  request/response files                  v
      +----------------------> /var/run/noxs/host/{storage,android}/
                                               |
                                               v
                                Android framework APIs (SAF, Photo Picker,
                                Camera, SMS manager, clipboard, notifications,
                                connectivity, battery, storage stats)
```

- **Transport**: file-based request/response pairs under
  `/var/run/noxs/host/android` (bound into the guest from app-private
  storage). Requests are `operation` + 5 positional argument lines plus an
  optional payload file; responses are `OK\n<text>` or `ERR\n<text>` plus an
  optional response payload. Ids are validated (`[A-Za-z0-9._-]{1,120}`) and
  the control directories refuse symlinks.
- **UI operations** (pickers, camera, confirmations) are executed by the
  foreground TerminalActivity — the bridge never starts activities from the
  background.
- **Pure logic** (`NoxsAndroidBridge` parse/classify/report builders,
  `NoxsStorageMount` bind decision, `NoxsAndroidPermissions` grant mapping)
  is JVM-tested; the Android shells around them are thin.

## Commands (nx == noxs)

| Command | What it does | Android permission |
| --- | --- | --- |
| `nx files` | Opens the Noxs file browser (guest files) | none |
| `nx document open` | Pick an Android document → open in an Android app | none (SAF picker) |
| `nx document import [dir] [--type MIME]` | Multi-select import into `~/<dir>` with progress + cancel | none (SAF picker) |
| `nx document export <file> [name] [mime]` | Save a Noxs file anywhere through the system save dialog | none |
| `nx document gallery [dest]` | Android **Photo Picker** → file in the guest home | none (photo picker is permissionless) |
| `nx document picker [dest]` | Pick one document into the guest home | none |
| `nx sms status` | Capability summary — Noxs holds NO SMS permission | none |
| `nx sms send <num> <msg>` | In-app confirm dialog, then YOUR SMS app opens with the message prefilled — you press send | none (system SMS app) |
| `nx sms compose <num> <msg>` | System SMS app — final approval happens there | none |
| `nx sms contacts` | Contact picker (returns the name, no numbers reading) | none (ACTION_PICK) |
| `nx device info/battery/storage` | Non-identifying device report | none |
| `nx notification status/permission/post` | Post a user-authorized notification | POST_NOTIFICATIONS (33+) |
| `nx share <file>` / `nx share -t <text>` | Android Sharesheet | none |
| `nx camera [dest]` | Native camera app; capture handed back as a file | CAMERA (declared → runtime grant) |
| `nx clipboard get/set` | Android clipboard (foreground-only, per platform policy) | none |
| `nx network status` | Transport/metered/validation summary — no identifiers | none |
| `nx storage permission/list/status` | Permission, capacity, mapping overview | — |
| `nx storage grant [--direct]` | SAF folder picker; `--direct` opens Android's All-files-access screen | user-driven |
| `nx storage mount` / `unmount` | Opt in/out of the direct `/sdcard` bind | see below |
| `nx storage revoke` | Release all Noxs SAF mappings | — |

## Shared storage inside the guest

Two independent, honest modes:

1. **SAF mode (default, always available)** — the user picks folders
   (`noxs-setup-storage` / `nx storage grant`); access goes through
   `nx storage get/put/ls/...` commands. Never a POSIX path; never an
   all-files grant.
2. **Direct mount (opt-in)** — `nx storage mount` +
   Android's user-granted *All files access* (`nx storage grant --direct`).
   Both are required. `ProotLauncher` then binds
   `/storage/emulated/0 -> /sdcard` for **new sessions**, and the CLI creates
   the Termux-compatible links `~/storage/shared`, `~/storage/downloads`,
   `~/storage/dcim`, `~/storage/pictures`, `~/storage/music`,
   `~/storage/movies`. The toggle persists across terminal restarts and can
   be revoked from Noxs or from Android Settings at any time.

Creating `/sdcard` inside Ubuntu never grants Android storage access — only
the two user actions above do, and Noxs reports exactly which one is missing.

## Security model

- Explicit permission requests, one capability at a time; nothing at startup.
- No unrestricted private app-data access — SAF grants are the only external
  file channel by default.
- SMS: single message, explicit confirmation dialog showing recipient +
  message, compose fallback through the system SMS app. No message reading,
  no automatic sending, no bulk sending.
- Device/network reports are summaries only — no SSID, IMSI, phone numbers
  or credentials, ever.
- File paths are validated; guest-facing names are sanitized; bridge ids and
  control directories are symlink-hardened.
- Revocation: Android Settings (permissions), `nx storage revoke`,
  `nx storage unmount`, and the Storage dashboard toggles.

## Testing checklist (real device, Android 13/14/15)

1. Storage permission request — `nx storage permission` after a fresh install.
2. Folder selection — `nx storage grant` → picker → `nx storage ls downloads`.
3. Gallery selection — `nx document gallery` (Photo Picker on 13+, fallback on older).
4. File import/export — `nx document import demo` and `nx document export ~/hello.txt`.
5. Ubuntu storage access — `nx storage mount` + All-files grant → `ls /sdcard`.
6. Kali/legacy environment access — same flow in the legacy environment.
7. Terminal restart persistence — kill the app, reopen: mount still applied,
   SAF mappings still usable (`nx storage status`).
8. SMS via your own SMS app — `nx sms send` confirms in-app, then opens the
   messaging app with the message prefilled; you press send there. Noxs holds
   no SMS permission at all.
9. Android 13–15 compatibility — media permissions path (33+), legacy path
   (32-), POST_NOTIFICATIONS behavior on both sides of API 33.
10. Permission revocation — revoke in Android Settings → `nx storage status`
    and `nx device storage` report honestly, commands degrade gracefully.

Run `scripts/test.sh --all` (manifests, sync-check, shell tooling, JVM unit
tests) and the CI android-ci workflow before any release.
