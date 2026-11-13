# vpn-lib.sh — nx vpn support (Noxs Tor over PRoot, VPN mode)
# shellcheck shell=bash
# Sourced by the nx dispatcher. Installs/activates the Tor daemon inside the
# Noxs Ubuntu environment, auto-tunes ConnLimit for PRoot file-descriptor
# limits, and can route NEW shells through the Tor SOCKS5 proxy ('nx vpn on').
#
# Overridable for offline tests:
#   NX_VPN_TORRC    tor config file      (default /etc/tor/torrc)
#   NX_VPN_PROFILE  global-proxy script  (default /etc/profile.d/noxs-tor-proxy.sh)
#   NX_VPN_PORT     SOCKS5 port          (default 9050)
#   NX_VPN_TOR_BIN  tor binary path      (default: discovered on PATH)
#   NX_VPN_FD_LIMIT fd limit for ConnLimit math (default: ulimit -n)
#   NX_VPN_WAIT     seconds to wait for the SOCKS5 listener (default 30)
#   NX_VPN_LOG      daemon start log     (default /var/log/tor/noxs-start.log)
#   NX_VPN_TEST_TIMEOUT  curl max-time for 'nx vpn test' (default 20)

NX_VPN_TORRC="${NX_VPN_TORRC:-/etc/tor/torrc}"
NX_VPN_PROFILE="${NX_VPN_PROFILE:-/etc/profile.d/noxs-tor-proxy.sh}"
NX_VPN_PORT="${NX_VPN_PORT:-9050}"
NX_VPN_SOCKS="127.0.0.1:$NX_VPN_PORT"
_VPN_TOR_DISCOVERED="$(command -v tor 2>/dev/null || true)"
TOR_BIN="${NX_VPN_TOR_BIN:-$_VPN_TOR_DISCOVERED}"

# Self-contained messaging (the dispatcher usually defines these already).
type nx_err >/dev/null 2>&1 || nx_err() { printf 'nx: %s\n' "$*" >&2; }
type nx_info >/dev/null 2>&1 || nx_info() { printf '%s\n' "$*"; }

nx_vpn_usage() {
    cat <<'EOF'
usage: nx vpn
       nx vpn start|stop|status|test|on|off

Tor (VPN mode) inside the Noxs Linux environment.

  nx vpn              Install (if needed) + activate Tor with PRoot-safe limits
  nx vpn start        Same as above
  nx vpn stop         Stop the Tor daemon
  nx vpn status       Show Tor state, the SOCKS5 port and the global proxy
  nx vpn test         Check real connectivity through Tor (exit IP)
  nx vpn on           Activate Tor AND route new shells through it (global)
  nx vpn off          Remove the global proxy and stop Tor

The SOCKS5 proxy is 127.0.0.1:9050 — point any tool at it, e.g.:
  curl --socks5-hostname 127.0.0.1:9050 https://check.torproject.org/api/ip

'nx vpn on' writes /etc/profile.d/noxs-tor-proxy.sh so NEW shells export
ALL_PROXY/HTTP_PROXY/HTTPS_PROXY through Tor. Already-open shells must run:
  . /etc/profile.d/noxs-tor-proxy.sh
Note: apt is not covered by the proxy environment; use torsocks to force
individual commands through Tor.
EOF
}

# Run a command as root: Noxs proot sessions are already root (id -u = 0).
# On real multi-user systems use passwordless sudo when it works; otherwise
# fall back to running directly (best effort inside proot-like sandboxes).
nx_vpn_as_root() {
    if [ "$(id -u)" = "0" ]; then
        "$@"
    elif command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1; then
        sudo "$@"
    else
        "$@"
    fi
}

nx_vpn_conn_limit() {
    # PRoot caps the process file-descriptor limit; a ConnLimit above it makes
    # Tor abort at startup. Keep a safety margin below the current limit.
    local fd limit
    if [ -n "${NX_VPN_FD_LIMIT:-}" ]; then
        fd="$NX_VPN_FD_LIMIT"
    else
        fd="$(ulimit -n 2>/dev/null || echo 512)"
    fi
    case "$fd" in ''|*[!0-9]*) fd=512 ;; esac
    if [ "$fd" -le 512 ]; then
        limit=$((fd - 112))
    else
        limit=$((fd - 64))
    fi
    if [ "$limit" -lt 128 ]; then limit=128; fi
    printf '%s\n' "$limit"
}

nx_vpn_port_open() {
    (exec 3<>"/dev/tcp/127.0.0.1/$NX_VPN_PORT") >/dev/null 2>&1
}

nx_vpn_tor_pid() {
    if command -v pgrep >/dev/null 2>&1; then
        pgrep -x tor 2>/dev/null | head -n 1 || true
    fi
}

nx_vpn_install() {
    if [ -n "$TOR_BIN" ] && [ -x "$TOR_BIN" ]; then
        return 0
    fi
    nx_info "[1/5] Installing Tor + curl via apt (this can take a minute)..."
    if ! nx_vpn_as_root apt-get update -qq; then
        nx_info "      apt-get update failed — trying to install anyway"
    fi
    if ! DEBIAN_FRONTEND=noninteractive nx_vpn_as_root apt-get install -y tor curl; then
        nx_err "apt-get install tor failed — check the network and retry 'nx vpn'"
        return 1
    fi
    TOR_BIN="$(command -v tor 2>/dev/null || true)"
    if [ -z "$TOR_BIN" ]; then
        nx_err "tor is still not on PATH after installation"
        return 1
    fi
}

nx_vpn_fix_dirs() {
    nx_vpn_as_root mkdir -p /var/lib/tor /var/log/tor 2>/dev/null || true
    if id debian-tor >/dev/null 2>&1; then
        nx_vpn_as_root chown -R debian-tor:debian-tor /var/lib/tor /var/log/tor 2>/dev/null || true
        nx_vpn_as_root chmod 700 /var/lib/tor 2>/dev/null || true
    fi
}

nx_vpn_apply_connlimit() {
    if [ ! -f "$NX_VPN_TORRC" ]; then
        nx_info "      creating $NX_VPN_TORRC"
        nx_vpn_as_root mkdir -p "$(dirname "$NX_VPN_TORRC")"
        nx_vpn_as_root touch "$NX_VPN_TORRC" || return 1
    fi
    local limit
    limit="$(nx_vpn_conn_limit)"
    # Drop any previous managed block, neutralize active ConnLimit lines,
    # then append the fresh Noxs-managed limit (idempotent on every run).
    nx_vpn_as_root sed -i -E \
        -e 's/^[[:space:]]*ConnLimit[[:space:]].*/# ConnLimit managed by Noxs/' \
        -e '/^# Noxs PRoot file-descriptor compatibility$/,+1d' \
        "$NX_VPN_TORRC" || return 1
    nx_vpn_as_root sh -c "printf '\n# Noxs PRoot file-descriptor compatibility\nConnLimit $limit\n' >> '$NX_VPN_TORRC'" || return 1
}

nx_vpn_verify() {
    if [ -z "$TOR_BIN" ] || [ ! -x "$TOR_BIN" ]; then
        nx_err "tor is not available — run 'nx vpn' once as root to install it"
        return 1
    fi
    if ! "$TOR_BIN" --verify-config -f "$NX_VPN_TORRC" >/dev/null 2>&1; then
        nx_err "Tor configuration is invalid ($NX_VPN_TORRC) — not starting"
        return 1
    fi
}

nx_vpn_stop_tor() {
    if command -v pkill >/dev/null 2>&1; then
        pkill -x tor 2>/dev/null || true
    fi
    sleep 1
}

nx_vpn_start_daemon() {
    local log="${NX_VPN_LOG:-/var/log/tor/noxs-start.log}"
    if id debian-tor >/dev/null 2>&1 && command -v runuser >/dev/null 2>&1 && [ "$(id -u)" = "0" ]; then
        # Prefer Ubuntu's dedicated Tor user when we can drop privileges.
        nohup nx_vpn_as_root runuser -u debian-tor -- "$TOR_BIN" -f "$NX_VPN_TORRC" >>"$log" 2>&1 &
    else
        nohup "$TOR_BIN" -f "$NX_VPN_TORRC" >>"$log" 2>&1 &
    fi
}

nx_vpn_wait_ready() {
    local i=0 deadline
    deadline="${NX_VPN_WAIT:-30}"
    while [ "$i" -lt "$deadline" ]; do
        if nx_vpn_port_open; then
            return 0
        fi
        i=$((i + 1))
        sleep 1
    done
    return 1
}

nx_vpn_activate() {
    nx_info "Noxs VPN (Tor) — activating"
    nx_vpn_install || return 1
    nx_info "[2/5] Preparing Tor directories + PRoot-safe ConnLimit..."
    nx_vpn_fix_dirs
    nx_vpn_apply_connlimit || { nx_err "could not write the ConnLimit override"; return 1; }
    nx_info "[3/5] Validating torrc..."
    nx_vpn_verify || return 1
    if nx_vpn_port_open; then
        nx_info "[4/5] Tor already listening on $NX_VPN_SOCKS"
    else
        nx_info "[4/5] Starting Tor..."
        nx_vpn_stop_tor
        nx_vpn_start_daemon
        if ! nx_vpn_wait_ready; then
            nx_err "Tor SOCKS5 listener did not become ready on $NX_VPN_SOCKS"
            nx_info "Check the daemon log, then retry:  tail -n 20 /var/log/tor/noxs-start.log"
            return 1
        fi
    fi
    nx_info "[5/5] Tor is READY"
    echo
    echo "  SOCKS5 : $NX_VPN_SOCKS"
    echo "  status : nx vpn status   |  nx vpn test"
    echo "  global : nx vpn on       (route new shells through Tor)"
    echo "  stop   : nx vpn off"
    echo
}

nx_vpn_status() {
    local pid
    pid="$(nx_vpn_tor_pid)"
    if nx_vpn_port_open; then
        if [ -n "$pid" ]; then
            echo "Tor: RUNNING (SOCKS5 $NX_VPN_SOCKS, pid $pid)"
        else
            echo "Tor: RUNNING (SOCKS5 $NX_VPN_SOCKS)"
        fi
        if [ -f "$NX_VPN_PROFILE" ]; then
            echo "Global proxy: ON ($NX_VPN_PROFILE)"
        else
            echo "Global proxy: off (run 'nx vpn on' to route new shells through Tor)"
        fi
        return 0
    fi
    if [ -n "$pid" ]; then
        echo "Tor: STARTING (pid $pid — SOCKS5 not ready yet)"
    else
        echo "Tor: STOPPED"
    fi
    if [ -f "$NX_VPN_PROFILE" ]; then
        echo "Global proxy: ON but Tor is down — run 'nx vpn off' to unproxy new shells"
    fi
    return 1
}

nx_vpn_test() {
    if ! command -v curl >/dev/null 2>&1; then
        nx_err "curl is required for the connectivity check (apt-get install curl)"
        return 1
    fi
    if ! nx_vpn_port_open; then
        nx_err "Tor is not listening on $NX_VPN_SOCKS — run 'nx vpn' first"
        return 1
    fi
    local out
    if out="$(curl --max-time "${NX_VPN_TEST_TIMEOUT:-20}" --socks5-hostname "$NX_VPN_SOCKS" -fsSL https://check.torproject.org/api/ip 2>/dev/null)"; then
        echo "$out"
        echo "Tor connection: OK"
    else
        nx_err "Tor is listening, but the external check did not succeed yet."
        nx_info "It can take Tor a minute to build its first circuits — retry:"
        nx_info "  curl --socks5-hostname $NX_VPN_SOCKS https://check.torproject.org/api/ip"
        return 1
    fi
}

nx_vpn_on() {
    nx_vpn_activate || return 1
    mkdir -p "$(dirname "$NX_VPN_PROFILE")" 2>/dev/null || return 1
    printf '%s\n' \
        "# Generated by 'nx vpn on' — route shell traffic through Tor." \
        "# Remove with 'nx vpn off'." \
        "export ALL_PROXY=socks5h://$NX_VPN_SOCKS" \
        "export HTTP_PROXY=socks5h://$NX_VPN_SOCKS" \
        "export HTTPS_PROXY=socks5h://$NX_VPN_SOCKS" \
        | nx_vpn_as_root tee "$NX_VPN_PROFILE" >/dev/null || return 1
    nx_vpn_as_root chmod 644 "$NX_VPN_PROFILE" 2>/dev/null || true
    echo
    nx_info "Global Tor proxy enabled for NEW shells ($NX_VPN_PROFILE)"
    nx_info "Already-open shells:  . $NX_VPN_PROFILE"
}

nx_vpn_stop_cmd() {
    nx_vpn_stop_tor
    nx_info "Tor stopped — traffic uses the normal network path again."
    if [ -f "$NX_VPN_PROFILE" ]; then
        nx_info "NOTE: the global proxy is still ON — run 'nx vpn off' to disable it."
    fi
}

nx_vpn_off() {
    if [ -f "$NX_VPN_PROFILE" ]; then
        nx_vpn_as_root rm -f "$NX_VPN_PROFILE"
        nx_info "Global Tor proxy disabled ($NX_VPN_PROFILE removed)"
    fi
    nx_vpn_stop_tor
    nx_info "Tor stopped — traffic uses the normal network path again."
}

nx_vpn_cmd() {
    local sub="${1:-}"
    if [ "$#" -gt 0 ]; then
        shift
    fi
    case "$sub" in
        ""|start)
            nx_vpn_activate
            ;;
        stop)
            nx_vpn_stop_cmd
            ;;
        status)
            nx_vpn_status
            ;;
        test)
            nx_vpn_test
            ;;
        on)
            nx_vpn_on
            ;;
        off)
            nx_vpn_off
            ;;
        -h|--help|help)
            nx_vpn_usage
            ;;
        *)
            nx_err "Unknown nx vpn command: $sub"
            nx_vpn_usage
            exit 2
            ;;
    esac
}
