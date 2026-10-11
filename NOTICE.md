# NOTICE

Product: Noxs — Debian-optimized Linux userspace environment for Android
Application ID: com.crossberry.noxs
License: Apache-2.0 (see LICENSE)

Noxs is an original implementation. It contains no source code, branding, or
assets from Termux or any other terminal application; other projects were used
only as conceptual/architectural references under principles unencumbered by
copyright.

The Debian root filesystem is downloaded at first launch from the official
debuerreotype artifacts (pinned by SHA-256) and remains under its upstream
licenses — it is not redistributed by this project:

- Debian 12 (bookworm) root filesystem — produced by the Debian project and
  the debuerreotype tooling; packages remain under their respective Debian
  licenses (see /usr/share/doc/<package>/copyright inside the rootfs).
- proot — GPL-2.0+ by the proot-me contributors; the Noxs APK redistributes
  the Termux-project build of proot 5.1.107.96 (with libtalloc 2.5.0 and
  libandroid-shmem 0.7) as `jniLibs`, because Android 10+ forbids executing
  binaries from app data storage. Corresponding source is available at
  https://github.com/proot-me/proot and https://github.com/termux/termux-packages.
- code-server — optional, user-installed, MIT (Coder); never bundled here.

Android, Debian, Ubuntu, VS Code and their marks belong to their respective
owners. Noxs is not affiliated with, endorsed by, or derived from Termux.

## NXEditor editor components (bundled, since v0.14.0)

- sora-editor (`io.github.rosemoe:editor` + `io.github.rosemoe:language-textmate`
  0.24.3) — the code editing engine Squircle CE embeds. License: LGPL-2.1.
  Source: https://github.com/Rosemoe/sora-editor. Used unmodified as a Maven
  dependency; it is not part of this repository's original code.
- TextMate grammar assets (24 languages) and the Darcula token theme,
  curated from Squircle CE's repository. License: Apache-2.0.
  Source: https://github.com/massivemadness/Squircle-CE
  (feature-editor/impl assets, grammar definitions). Included with
  attribution per the Apache-2.0 license.
