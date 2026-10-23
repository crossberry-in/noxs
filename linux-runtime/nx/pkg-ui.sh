#!/bin/bash
# pkg-ui.sh — Noxs Package Manager UI primitives (single-line progress).
# shellcheck shell=bash

# Globals set by ui_init (all overridable for tests):
PKG_UI_TTY=0; PKG_UI_COLOR=0; PKG_UI_COLS=80; PKG_UI_LASTLEN=0

# ui_init — probe the terminal ONCE per command.
# JSON mode (--json / NOXS_PKG_JSON=1) and non-interactive stdout both
# disable the live line renderer entirely.
ui_init() {
    PKG_UI_TTY=0; PKG_UI_COLOR=0; PKG_UI_COLS=80; PKG_UI_LASTLEN=0
    [ "${NOXS_PKG_JSON:-0}" = "1" ] && return 0
    [ -t 1 ] || return 0
    case "${TERM:-}" in ""|dumb) return 0 ;; esac
    PKG_UI_TTY=1
    [ -z "${NO_COLOR:-}" ] && PKG_UI_COLOR=1
    local c=""
    c=$(tput cols 2>/dev/null) || c=""
    case "${COLUMNS:-}" in ''|*[!0-9]*) ;; *) PKG_UI_COLS="$COLUMNS" ;; esac
    case "$c" in ''|*[!0-9]*) ;; *) PKG_UI_COLS="$c" ;; esac
    [ "$PKG_UI_COLS" -ge 40 ] 2>/dev/null || PKG_UI_COLS=40
    [ "$PKG_UI_COLS" -le 500 ] 2>/dev/null || PKG_UI_COLS=500
    return 0
}

# ui_c COLOR TEXT — wrap in ANSI only when colors are enabled.
ui_c() {
    if [ "$PKG_UI_COLOR" = "1" ]; then
        case "$1" in
            green)  printf '\033[32m%s\033[0m' "$2" ;;
            bold)   printf '\033[1m%s\033[0m'  "$2" ;;
            dim)    printf '\033[2m%s\033[0m'  "$2" ;;
            yellow) printf '\033[33m%s\033[0m' "$2" ;;
            red)    printf '\033[31m%s\033[0m' "$2" ;;
            *)      printf '%s' "$2" ;;
        esac
    else
        printf '%s' "$2"
    fi
}

# humanize_bytes N — "512 B" / "4.8 MB" style, one decimal below GB.
humanize_bytes() {
    awk -v b="${1:-0}" 'BEGIN{
        if (b+0 < 1024)          printf "%d B", b;
        else if (b < 1048576)    printf "%.1f KB", b/1024;
        else if (b < 1073741824) printf "%.1f MB", b/1048576;
        else                     printf "%.2f GB", b/1073741824;
    }'
}

# progress_calc DONE TOTAL ELAPSED_SECONDS -> "PCT BYTES_PER_SEC ETA_SECONDS"
# Pure arithmetic on real values — "-" means "not computable" and is NEVER
# faked. Percentage requires a known total; ETA additionally requires a
# positive speed and >=2s of elapsed samples.
progress_calc() {
    awk -v d="$1" -v t="$2" -v e="$3" 'BEGIN{
        pct="-"; spd="-"; eta="-";
        if (e+0 < 0) e=0;
        if (e+0 > 0) spd = d/e;
        if (t+0 > 0 && d+0 <= t+0) { pct = int(d*100/t); if (pct > 100) pct = 100; }
        if (t+0 > 0 && spd+0 > 0 && e+0 >= 2 && d+0 <= t+0) {
            eta = int((t - d)/spd); if (eta < 0) eta = 0;
        }
        print pct, spd, eta;
    }'
}

# progress_fmt_speed BYTES_PER_SEC -> "2.1 MB/s"
progress_fmt_speed() {
    awk -v s="$1" 'BEGIN{
        if (s == "-" || s+0 <= 0) { print "-"; exit }
        if (s < 1024)          printf "%d B/s", s;
        else if (s < 1048576)  printf "%.1f KB/s", s/1024;
        else if (s < 1073741824) printf "%.1f MB/s", s/1048576;
        else                   printf "%.2f GB/s", s/1073741824;
    }'
}

# progress_fmt_eta SECONDS -> "ETA 2s" / "ETA 3m05s" / "-"
progress_fmt_eta() {
    case "$1" in
        ""|-|0) printf '%s' "-" ;;
        *[!0-9]*) printf '%s' "-" ;;
        *)
            if [ "$1" -ge 3600 ]; then
                printf 'ETA %dh%02dm' $(( $1 / 3600 )) $(( ($1 % 3600) / 60 ))
            elif [ "$1" -ge 60 ]; then
                printf 'ETA %dm%02ds' $(( $1 / 60 )) $(( $1 % 60 ))
            else
                printf 'ETA %ss' "$1"
            fi
            ;;
    esac
}

# progress_render LABEL DONE TOTAL ELAPSED — refresh ONE line via \r.
# Clearing: pads to the previous line length so shorter refreshes never
# leave stale characters. Width: clamped to PKG_UI_COLS. No-op when the
# UI is disabled (non-interactive / JSON mode / dumb terminal).
progress_render() {
    [ "$PKG_UI_TTY" = "1" ] || return 0
    local label="$1" done_b="$2" total_b="$3" elapsed="$4"
    local calc pct spd eta line pad
    calc=$(progress_calc "$done_b" "$total_b" "$elapsed")
    # word splitting on "pct spd eta" is intentional
    # shellcheck disable=SC2086
    set -- $calc
    pct="$1"; spd="$2"; eta="$3"
    line="$label"
    if [ "$pct" != "-" ]; then
        line="$line  ${pct}%"
    fi
    line="$line  $(humanize_bytes "${done_b:-0}")"
    if [ -n "$total_b" ] && [ "$total_b" != "-" ] && [ "$total_b" != "0" ]; then
        line="$line/$(humanize_bytes "$total_b")"
    fi
    if [ "$spd" != "-" ]; then
        line="$line  $(progress_fmt_speed "$spd")"
    fi
    local eta_s; eta_s=$(progress_fmt_eta "$eta")
    if [ "$eta_s" != "-" ]; then
        line="$line  $eta_s"
    fi
    # Clamp to the terminal width (labels are ASCII; byte length is safe).
    [ "${#line}" -le "$PKG_UI_COLS" ] || line="${line:0:$PKG_UI_COLS}"
    pad=0
    [ "$PKG_UI_LASTLEN" -gt "${#line}" ] && pad=$((PKG_UI_LASTLEN - ${#line}))
    printf '\r%s%*s' "$line" "$pad" ""
    PKG_UI_LASTLEN=${#line}
    return 0
}

# progress_clear — erase the live line entirely.
progress_clear() {
    [ "$PKG_UI_TTY" = "1" ] || return 0
    [ "$PKG_UI_LASTLEN" -gt 0 ] && printf '\r%*s\r' "$PKG_UI_LASTLEN" ""
    PKG_UI_LASTLEN=0
    return 0
}

# ui_progress_done — finish a live progress run with EXACTLY one newline.
ui_progress_done() {
    progress_clear
    [ "$PKG_UI_TTY" = "1" ] && printf '\n'
    return 0
}

# ui_stage N TOTAL LABEL — permanent stage header line ([1/5] ...).
# In live mode it clears the progress line first, so stages never stack
# on top of a half-drawn progress row.
ui_stage() {
    [ "${NOXS_PKG_JSON:-0}" = "1" ] && return 0
    local line
    line=$(printf '[%s/%s] %s' "$1" "$2" "$3")
    progress_clear
    printf '%s\n' "$(ui_c bold "$line")"
    return 0
}

# ui_step NOTE — permanent single detail line (verification results,
# cache hits, source decisions). Stays in the scrollback.
ui_step() {
    [ "${NOXS_PKG_JSON:-0}" = "1" ] && return 0
    printf '  %s\n' "$*"
    return 0
}

# ui_header — the Noxs Package Manager panel (REAL metadata only; the
# caller passes values it resolved from package metadata, never literals).
ui_header() { # PACKAGE SOURCE VERSION ARCH
    [ "${NOXS_PKG_JSON:-0}" = "1" ] && return 0
    printf '%s\n' "$(ui_c green 'Noxs Package Manager')"
    printf '%s\n' "$(ui_c dim '+--------------------------------------------------+')"
    printf '  %-8s: %s\n' "Package" "$1"
    printf '  %-8s: %s\n' "Source"  "$2"
    printf '  %-8s: %s\n' "Version" "$3"
    printf '  %-8s: %s\n' "Arch"    "$4"
    printf '%s\n' "$(ui_c dim '+--------------------------------------------------+')"
    return 0
}

# ui_result CODE MESSAGE — final machine-greppable result line.
ui_result() {
    [ "${NOXS_PKG_JSON:-0}" = "1" ] && return 0
    if [ "$1" = "OK" ]; then
        printf '%s\n' "$(ui_c green "Result: OK — $2")"
    else
        printf '%s\n' "$(ui_c red "Result: $1 — $2")" >&2
    fi
    return 0
}
