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

## Editor components (downloaded at install time, since v0.15.0)

The built-in editor is [code-server](https://github.com/coder/code-server)
rendered in an Android WebView. It is NOT bundled with this repository or
with the APK:

- code-server — License: MIT (Coder). Downloaded by the guest
  `noxs code install` command directly from the official GitHub releases
  at install time, with sha256 verification against the release
  SHASUMS.txt when upstream publishes one.
- Visual Studio Code (the workbench code-server serves) — License: MIT
  (Microsoft). Served by code-server from the same install.

The former native editor (sora-editor engine plus Squircle CE grammar and
theme assets) was removed in v0.15.0 and is no longer distributed.
