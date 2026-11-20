#!/bin/sh
# Recreate FHS compatibility symlinks inside the Noxs sandbox (canonical copy,
# mirror of NoxsInstaller.FHS_LINKS_SCRIPT — CI diff-checks the pair).
# App storage cannot always create symlinks during extraction; this runs on
# first in-sandbox login via /etc/profile.d/noxs.sh.
#
# A previous extraction bug could leave 0-byte placeholder FILES at symlink
# paths; `[ -e ... ]` treats those as existing, which is why the old checks
# silently kept them and every ELF resolving through the path failed with
# ENOENT (the "No such file or directory" reports for system binaries).
# Only symlinks count as healthy now: placeholders are removed — never real
# directories or files with real content.
fixlink() {
    p="$1" t="$2"
    if [ -L "$p" ] && [ -e "$p" ]; then return 0; fi
    if [ -d "$p" ] && [ ! -L "$p" ]; then return 0; fi   # real dir — keep
    if [ -e "$p" ] || [ -L "$p" ]; then
        [ -f "$p" ] && [ -s "$p" ] && return 0           # real file with content — keep
        rm -f "$p" 2>/dev/null || return 0
    fi
    ln -s "$t" "$p" 2>/dev/null || true
}
fixlink /bin usr/bin
fixlink /sbin usr/sbin
fixlink /lib usr/lib
if [ -e usr/lib64 ]; then fixlink /lib64 usr/lib64; fi
# aarch64 ELF interpreter chain — the classic silent-ENOENT source.
# Ubuntu usr-merge layout: /lib64 -> usr/lib64, and the interpreter inside
# usr/lib64 points back with ../lib/aarch64-linux-gnu/...
if [ -f /usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1 ]; then
    fixlink /usr/lib/ld-linux-aarch64.so.1 aarch64-linux-gnu/ld-linux-aarch64.so.1
    if [ -e usr/lib64 ]; then
        fixlink /usr/lib64/ld-linux-aarch64.so.1 ../lib/aarch64-linux-gnu/ld-linux-aarch64.so.1
    fi
fi
fixlink /usr/bin/sh dash
fixlink /var/run /run
exit 0
