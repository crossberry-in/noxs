#!/bin/sh
# cert-fix.sh — nx cert-fix: repair the APT package layer of this Noxs
# Linux environment (CA certificates, package lists, curl/wget) without
# reinstalling anything. Part of the Noxs NX Package System.
#
# The update step never trusts the exit code alone: apt exits 0 in warn
# mode even when index downloads failed (a fresh rootfs without
# ca-certificates cannot complete an HTTPS fetch, and the failure is
# easy to miss). This repair therefore mirrors the app-side bootstrap
# policy: strict apt error mode, a scan of the captured output for
# failed downloads and TLS errors, and a populated-lists check — only a
# proven update lets the package install run, otherwise the signed-HTTP
# fallback takes over.
#
# Repair steps:
#  1. refresh the already-installed CA bundle (best effort)
#  2. apt-get update — verified beyond the exit code; when HTTPS fails,
#     switch to signed HTTP — Ubuntu metadata stays GPG-authenticated
#     over plain HTTP, so this is safe
#  3. dpkg recovery for interrupted package configuration
#  4. install ca-certificates + ubuntu-keyring + curl + wget
#     (on failure: disk-space and dpkg-audit diagnostics in the TXT log)
#  5. regenerate the CA bundle (update-ca-certificates --fresh)
#  6. switch back to HTTPS, re-verify; keep signed HTTP only when TLS
#     genuinely cannot work on this network
#  7. verify curl/wget, the CA bundle, the package metadata and the lists
#
# Terminal output is a plain transcript — every line stays in the
# scrollback and is copy-paste friendly — plus one summary box drawn
# once at the end. No cursor save/restore redraws: when a terminal view
# scrolls during a multi-line redraw the frames overlap and garble.

CF_SOURCES="/etc/apt/sources.list.d/noxs.sources"
CF_LOG_DIR="${HOME:-/root}/.noxs/logs"
CF_SUM_WIDTH=62
CF_SUM_ROWS=7
CF_LOG=""

cf_hline() { printf '%*s' "$1" '' | tr ' ' '-'; }

cf_log() {
    cf_level="$1"
    shift
    cf_line="[$(date '+%H:%M:%S')] [$cf_level] $*"
    printf '%s\n' "$cf_line" >> "$CF_LOG"
    printf '%s\n' "$cf_line"
}

# Summary box: drawn once, at the natural scroll position, by the EXIT
# trap. No save/restore cursor games.
cf_summary_box() {
    cf_b_rows=0
    if [ -f "$CF_LOG" ]; then
        cf_b_rows=$(tail -n "$CF_SUM_ROWS" "$CF_LOG" | wc -l)
    fi
    printf '+-%s-+\n' "$(cf_hline "$CF_SUM_WIDTH")"
    printf '| %-*s |\n' "$CF_SUM_WIDTH" "NOXS - CERT-FIX RESULT"
    printf '+-%s-+\n' "$(cf_hline "$CF_SUM_WIDTH")"
    if [ -f "$CF_LOG" ]; then
        tail -n "$CF_SUM_ROWS" "$CF_LOG" | cut -c1-"$CF_SUM_WIDTH" | while IFS= read -r cf_b_line; do
            printf '| %-*s |\n' "$CF_SUM_WIDTH" "$cf_b_line"
        done
    fi
    while [ "$cf_b_rows" -lt "$CF_SUM_ROWS" ]; do
        printf '| %-*s |\n' "$CF_SUM_WIDTH" ''
        cf_b_rows=$((cf_b_rows + 1))
    done
    printf '+-%s-+\n' "$(cf_hline "$CF_SUM_WIDTH")"
    printf '| %-*s |\n' "$CF_SUM_WIDTH" "TXT: $CF_LOG"
    printf '+-%s-+\n' "$(cf_hline "$CF_SUM_WIDTH")"
}

cf_root() {
    if [ "$(id -u)" = "0" ]; then
        "$@"
    elif command -v sudo >/dev/null 2>&1; then
        sudo "$@"
    else
        cf_log ERROR "need root: run from a root shell or install sudo"
        return 127
    fi
}

cf_pkg_busy() {
    [ -f /run/noxs/pkg-tx ] && return 0
    pgrep -x dpkg >/dev/null 2>&1 && return 0
    pgrep -x apt-get >/dev/null 2>&1 && return 0
    pgrep -x apt >/dev/null 2>&1 && return 0
    return 1
}

# cf_try <label> <max-attempts> <command> [args...]
# Bounded, cancellable-by-identical-failure command runner. Full output
# lands in the TXT log; the error signature reaches the transcript.
cf_try() {
    cf_t_label="$1"
    cf_t_max="$2"
    shift 2
    cf_t_attempt=1
    cf_t_prev=''
    while [ "$cf_t_attempt" -le "$cf_t_max" ]; do
        cf_t_out="$(mktemp "$CF_LOG_DIR/try-XXXXXX")" || return 1
        cf_log INFO "$cf_t_label (attempt $cf_t_attempt/$cf_t_max)"
        cf_root "$@" > "$cf_t_out" 2>&1
        cf_t_rc=$?
        cat "$cf_t_out" >> "$CF_LOG"
        if [ "$cf_t_rc" -eq 0 ]; then
            rm -f "$cf_t_out"
            cf_log SUCCESS "$cf_t_label ok"
            return 0
        fi
        cf_t_sig="$(grep -E '^(Err:|E:|W:)' "$cf_t_out" | head -n 2 | tr '\n' ' ' | cut -c1-140)"
        if [ -z "$cf_t_sig" ]; then
            cf_t_sig="$(tail -n 2 "$cf_t_out" | tr '\n' ' ' | cut -c1-140)"
        fi
        cf_log ERROR "$cf_t_label failed (exit $cf_t_rc): $cf_t_sig"
        rm -f "$cf_t_out"
        if [ -n "$cf_t_prev" ] && [ "$cf_t_sig" = "$cf_t_prev" ]; then
            cf_log ERROR "$cf_t_label: the same failure repeated — further attempts cannot help"
            return 1
        fi
        cf_t_prev="$cf_t_sig"
        cf_t_attempt=$((cf_t_attempt + 1))
        if [ "$cf_t_attempt" -le "$cf_t_max" ]; then
            sleep 3
        fi
    done
    return 1
}

cf_lists_ok() { ls /var/lib/apt/lists/*Packages* >/dev/null 2>&1; }

# True when captured apt output reports failures although the command
# exited 0 (apt exits 0 in warn mode even when repositories fail).
# Mirrors the app-side PARTIAL_UPDATE_FAILURE / INSECURE_OR_TLS_FAILURE
# bootstrap policy.
cf_output_failed() {
    grep -Eqi '(^Err:|failed to fetch|some index files failed|could not resolve|temporary failure resolving|connection timed out|connection refused|certificate verification failed|certificate is not trusted|tls handshake|no_pubkey|is not signed|unauthenticated packages)' "$1"
}

cf_failure_sig() {
    cf_s_sig="$(grep -E '^(Err:|E:|W:)' "$1" | head -n 2 | tr '\n' ' ' | cut -c1-140)"
    if [ -z "$cf_s_sig" ]; then
        cf_s_sig="$(tail -n 2 "$1" | tr '\n' ' ' | cut -c1-140)"
    fi
    printf '%s' "$cf_s_sig"
}

# cf_apt_update <label> [max-attempts] — apt-get update with PROOF of
# success. A real success needs ALL of: exit 0, no failure signatures
# in the captured output, populated package lists. Anything less is a
# bounded, retryable failure that reports its real error signature.
cf_apt_update() {
    cf_u_label="$1"
    cf_u_max="${2:-2}"
    cf_u_attempt=1
    cf_u_prev=''
    while [ "$cf_u_attempt" -le "$cf_u_max" ]; do
        cf_u_out="$(mktemp "$CF_LOG_DIR/upd-XXXXXX")" || return 1
        cf_log INFO "$cf_u_label (attempt $cf_u_attempt/$cf_u_max)"
        cf_root /usr/bin/apt-get update \
            -o Acquire::Retries=1 \
            -o Acquire::http::Timeout=20 \
            -o Acquire::https::Timeout=20 \
            -o APT::Update::Error-Mode=any > "$cf_u_out" 2>&1
        cf_u_rc=$?
        cat "$cf_u_out" >> "$CF_LOG"
        if [ "$cf_u_rc" -eq 0 ] && cf_output_failed "$cf_u_out"; then
            cf_u_rc=1
            cf_log ERROR "$cf_u_label exited 0 but reported failed downloads"
        fi
        if [ "$cf_u_rc" -eq 0 ] && ! cf_lists_ok; then
            cf_u_rc=1
            cf_log ERROR "$cf_u_label exited 0 but the package lists are still empty"
        fi
        if [ "$cf_u_rc" -eq 0 ]; then
            rm -f "$cf_u_out"
            cf_log SUCCESS "$cf_u_label ok"
            return 0
        fi
        cf_u_sig="$(cf_failure_sig "$cf_u_out")"
        rm -f "$cf_u_out"
        cf_log ERROR "$cf_u_label failed: $cf_u_sig"
        if [ -n "$cf_u_prev" ] && [ "$cf_u_sig" = "$cf_u_prev" ]; then
            cf_log ERROR "$cf_u_label: the same failure repeated — further attempts cannot help"
            return 1
        fi
        cf_u_prev="$cf_u_sig"
        cf_u_attempt=$((cf_u_attempt + 1))
        if [ "$cf_u_attempt" -le "$cf_u_max" ]; then
            sleep 3
        fi
    done
    return 1
}

cf_transport() {
    [ -f "$CF_SOURCES" ] && sed -n 's/^URIs:[[:space:]]*//p' "$CF_SOURCES" | head -n 1
}

# Canonical Noxs APT definition — semantically identical to the app-side
# RootfsConfigurator.aptSources(useHttps = true): official Ubuntu repos with
# a runtime arch check (amd64 → archive.ubuntu.com + security.ubuntu.com;
# arm64/armhf → ports.ubuntu.com, which also serves the security pocket).
# Used to self-heal a missing or truncated noxs.sources instead of refusing
# to repair.
cf_write_sources() {
    cf_arch="$(dpkg --print-architecture 2>/dev/null || uname -m 2>/dev/null || echo arm64)"
    case "$cf_arch" in
        amd64|x86_64)
            cf_repo_main="https://archive.ubuntu.com/ubuntu"
            cf_repo_sec="https://security.ubuntu.com/ubuntu"
            ;;
        *)
            cf_repo_main="https://ports.ubuntu.com/ubuntu-ports"
            cf_repo_sec="https://ports.ubuntu.com/ubuntu-ports"
            ;;
    esac
    cf_root sh -c "cat > '$1'" <<CFEOF
Types: deb
URIs: ${cf_repo_main}
Suites: noble noble-updates noble-backports
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: ${cf_repo_sec}
Suites: noble-security
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
CFEOF
}

# Ubuntu metadata is GPG-authenticated regardless of transport, so the
# temporary HTTP fallback is safe; TLS is restored at the end.
cf_use_http() { cf_root sed -i 's|https://|http://|g' "$CF_SOURCES"; }
cf_use_https() { cf_root sed -i 's|http://|https://|g' "$CF_SOURCES"; }

# EXIT trap: restore nothing on screen (the transcript stays), release
# the transaction flag WE armed (never a flag owned by the app side)
# and close with the summary box.
cf_on_exit() {
    cf_e_rc=$?
    if [ "${cf_owns_tx:-0}" = "1" ]; then
        rm -f /run/noxs/pkg-tx 2>/dev/null
    fi
    if [ -t 1 ] && [ -n "$CF_LOG" ] && [ -f "$CF_LOG" ]; then
        cf_summary_box
    fi
    return "$cf_e_rc"
}

cert_fix_help() {
    cat <<'EOF'
usage: nx cert-fix

Repairs the APT package layer of this Noxs environment:
  1. refreshes the installed CA certificate bundle
  2. apt-get update — verified beyond the exit code, with a signed
     HTTP fallback when TLS or the index downloads fail
  3. repairs interrupted dpkg configuration
  4. installs ca-certificates, ubuntu-keyring, curl and wget
  5. regenerates the CA bundle and re-verifies HTTPS
  6. verifies curl/wget, the CA bundle and the package metadata

Use it when apt cannot find packages ("Unable to locate package ..."),
when downloads fail with certificate errors, when the Noxs sources file
is missing, or after a setup that kept failing. A full TXT log is
written to ~/.noxs/logs/.
EOF
}

cert_fix_cmd() {
    case "${1:-}" in
        -h|--help|help)
            cert_fix_help
            return 0
            ;;
        *)
            if [ "$#" -gt 0 ]; then
                printf 'nx cert-fix: unknown argument: %s\n' "$1" >&2
                cert_fix_help
                return 2
            fi
            ;;
    esac

    if ! mkdir -p "$CF_LOG_DIR" 2>/dev/null; then
        CF_LOG_DIR=/tmp
    fi
    CF_LOG="$CF_LOG_DIR/cert-fix-$(date +%Y%m%d-%H%M%S).txt"
    : > "$CF_LOG"
    [ -t 1 ] && printf '\n'
    # shellcheck disable=SC2064  # expansion at trap-set time is fine: CF_LOG is final
    trap 'cf_on_exit' EXIT

    cf_log INFO "Noxs APT + certificate repair started"
    cf_log INFO "full log: $CF_LOG"

    if ! command -v apt-get >/dev/null 2>&1; then
        cf_log ERROR "apt-get is not available in this environment"
        return 2
    fi
    if [ ! -f "$CF_SOURCES" ]; then
        cf_log WARN "$CF_SOURCES is missing — recreating the canonical Noxs sources"
        cf_root sh -c 'mkdir -p /etc/apt/sources.list.d' 2>/dev/null \
            || { cf_log ERROR "cannot create /etc/apt/sources.list.d"; return 3; }
        cf_write_sources "$CF_SOURCES" \
            || { cf_log ERROR "cannot recreate $CF_SOURCES"; return 3; }
        cf_log INFO "recreated $CF_SOURCES (HTTPS, GPG-signed)"
    fi
    if cf_pkg_busy; then
        cf_log ERROR "the Noxs package manager is busy — wait for the other apt/dpkg run and retry"
        return 1
    fi
    cf_owns_tx=0
    if cf_root sh -c 'mkdir -p /run/noxs && echo cert-fix > /run/noxs/pkg-tx' >/dev/null 2>&1; then
        cf_owns_tx=1
    fi

    # 1 — refresh whatever bundle already exists (best effort)
    if [ -x /usr/sbin/update-ca-certificates ]; then
        cf_log INFO "refreshing the existing CA bundle"
        cf_root /usr/sbin/update-ca-certificates >> "$CF_LOG" 2>&1 \
            || cf_log WARN "existing bundle refresh failed — continuing"
    else
        cf_log INFO "update-ca-certificates is not installed yet — skipping bundle refresh"
    fi

    # 2 — first update with the current transport (proof of success required)
    cf_t0="$(cf_transport)"
    cf_log INFO "repository transport: ${cf_t0:-unknown}"
    cf_apt_ok=1
    if ! cf_apt_update "apt-get update" 2; then
        cf_apt_ok=0
    fi

    # 3 — signed-HTTP fallback when the HTTPS transport failed
    cf_switched=0
    if [ "$cf_apt_ok" != 1 ] && printf '%s' "$cf_t0" | grep -q '^https://'; then
        cf_log WARN "HTTPS failed — switching to signed HTTP for the repair"
        if ! cf_use_http; then
            cf_log ERROR "cannot rewrite $CF_SOURCES"
            return 3
        fi
        cf_switched=1
        if cf_apt_update "apt-get update over signed HTTP" 2; then
            cf_apt_ok=1
        else
            cf_use_https >/dev/null 2>&1 || true
            cf_log ERROR "apt-get update failed even over HTTP — check the device network"
            return 4
        fi
    elif [ "$cf_apt_ok" != 1 ]; then
        cf_log WARN "apt-get update failed — continuing with the package repair anyway"
    fi

    # 4 — interrupted dpkg configuration (same preflight as the app side)
    cf_try "dpkg recovery" 1 /usr/bin/dpkg --configure -a \
        || cf_log WARN "dpkg recovery reported problems — continuing"

    # 5 — certificate stack + the standard fetch tools (curl, wget)
    cf_pkg_ok=1
    if ! cf_try "install ca-certificates, keyring, curl, wget" 2 \
        env DEBIAN_FRONTEND=noninteractive /usr/bin/apt-get install -y \
            --no-install-recommends ca-certificates ubuntu-keyring curl wget; then
        cf_pkg_ok=0
    fi
    if [ "$cf_pkg_ok" != 1 ]; then
        cf_log INFO "collecting diagnostics for the failed install (disk, dpkg audit)"
        {
            echo '--- df -h / ---'
            cf_root df -h / 2>&1
            echo '--- dpkg --audit ---'
            cf_root /usr/bin/dpkg --audit 2>&1
            echo '--- end diagnostics ---'
        } >> "$CF_LOG" 2>&1 || true
    fi

    # 6 — regenerate the CA bundle
    cf_bundle_ok=0
    if [ -x /usr/sbin/update-ca-certificates ]; then
        if cf_try "regenerate the CA bundle" 1 /usr/sbin/update-ca-certificates --fresh; then
            cf_bundle_ok=1
        fi
    fi
    if [ -s /etc/ssl/certs/ca-certificates.crt ]; then
        cf_log INFO "CA bundle present: /etc/ssl/certs/ca-certificates.crt"
    else
        cf_log WARN "CA bundle file is missing or empty"
    fi

    # 7 — restore HTTPS and re-verify
    if [ "$cf_switched" = 1 ]; then
        cf_use_http_restore=0
        cf_use_https || cf_use_http_restore=1
        if [ "$cf_use_http_restore" = 0 ] && cf_apt_update "apt-get update over HTTPS" 2; then
            cf_log SUCCESS "HTTPS repositories verified"
        else
            cf_log WARN "HTTPS still failing — keeping signed HTTP (GPG authentication unchanged)"
            cf_use_http || true
            cf_apt_update "apt-get update over signed HTTP" 1 || true
        fi
    fi

    # 8 — final verification
    cf_fail=0
    if command -v curl >/dev/null 2>&1; then
        cf_log INFO "curl: $(curl --version 2>/dev/null | head -n 1 | cut -c1-46)"
    else
        cf_log ERROR "curl is still missing"
        cf_fail=1
    fi
    if command -v wget >/dev/null 2>&1; then
        cf_log INFO "wget: $(wget --version 2>/dev/null | head -n 1 | cut -c1-46)"
    else
        cf_log ERROR "wget is still missing"
        cf_fail=1
    fi
    if [ "$cf_pkg_ok" != 1 ]; then
        cf_fail=1
    fi
    if [ "$cf_bundle_ok" != 1 ]; then
        cf_fail=1
    fi
    cf_policy="$(/usr/bin/apt-cache policy ca-certificates 2>/dev/null | grep 'Candidate:' | head -n 1)"
    case "$cf_policy" in
        *'(none)'*|'')
            cf_log ERROR "APT metadata verification failed (no candidate for ca-certificates)"
            cf_fail=1
            ;;
        *)
            cf_log INFO "APT metadata: $cf_policy"
            ;;
    esac
    if cf_lists_ok; then
        cf_log INFO "package lists are populated"
    else
        cf_log ERROR "package lists are still empty — apt-get update did not persist"
        cf_fail=1
    fi

    if [ "$cf_fail" = 0 ]; then
        cf_log SUCCESS "repair completed — apt, certificates and fetch tools verified"
    else
        cf_log ERROR "repair finished with problems — read $CF_LOG"
    fi
    return "$cf_fail"
}
