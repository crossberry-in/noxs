# web-lib.sh — nx ow support (Noxs native browser bridge)
# shellcheck shell=bash
# Sourced by the nx dispatcher. Requires no external tools.

NX_WEB_HOST="${NX_WEB_HOST:-/var/run/noxs/host/web}"

# Self-contained messaging (the dispatcher usually defines these already).
type nx_err >/dev/null 2>&1 || nx_err() { printf 'nx: %s\n' "$*" >&2; }

nx_ow_usage() {
    cat <<'EOF'
usage: nx ow <url>

Open a website inside the Noxs floating browser window.

  nx ow https://example.com

Only http:// and https:// URLs are accepted. The site loads in a real
native web view managed by Noxs — never an external browser.
EOF
}

# Validate a URL for the web bridge. Prints nothing, returns 0 when safe.
nx_url_ok() {
    local url="$1"
    case "$url" in
        ""|*" "*) return 1 ;;
    esac
    case "$url" in
        http://*|https://*) ;;
        *) return 1 ;;
    esac
    # Control characters (tab, CR, ESC) never reach the Android side.
    case "$url" in
        *"$(printf '\t')"*|*"$(printf '\r')"*|*"$(printf '\033')"*) return 1 ;;
    esac
    # A scheme must be followed by a host component.
    local rest="${url#http://}"
    [ "$rest" = "$url" ] && rest="${url#https://}"
    [ -n "$rest" ] || return 1
    case "$rest" in
        /*) return 1 ;;
    esac
    return 0
}

nx_ow_cmd() {
    if [ $# -eq 0 ]; then
        nx_ow_usage
        return 2
    fi
    local url="$1"
    case "$url" in
        -h|--help|help)
            nx_ow_usage
            return 0
            ;;
    esac
    if [ $# -gt 1 ]; then
        nx_err "ow takes exactly one URL (quote it if it contains spaces)"
        return 2
    fi
    if ! nx_url_ok "$url"; then
        nx_err "Unsupported URL: only http:// and https:// can be opened"
        return 2
    fi
    if [ ! -d "$NX_WEB_HOST/requests" ]; then
        nx_err "Noxs web bridge is unavailable; start Noxs and open a terminal"
        return 1
    fi
    local id request response attempt
    id="$$-$(date +%s)-$RANDOM-$RANDOM"
    request="$NX_WEB_HOST/requests/$id"
    response="$NX_WEB_HOST/responses/$id"
    rm -f "$request" "$response"
    printf 'open\n%s\n' "$url" > "$request" || {
        nx_err "could not send web request"
        return 1
    }
    printf 'nx: Opening in the Noxs browser...\n'
    attempt=0
    while [ ! -f "$response" ] && [ "$attempt" -lt 120 ]; do
        sleep 0.5
        attempt=$((attempt + 1))
    done
    if [ ! -f "$response" ]; then
        rm -f "$request" "$response"
        nx_err "Noxs did not respond; keep Noxs running and retry"
        return 1
    fi
    local first
    first="$(sed -n '1p' "$response" 2>/dev/null || true)"
    if [ "$first" = "OK" ]; then
        rm -f "$request" "$response"
        return 0
    fi
    tail -n +2 "$response" >&2
    rm -f "$request" "$response"
    return 1
}
