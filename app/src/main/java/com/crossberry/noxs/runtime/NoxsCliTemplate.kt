/*
 * Noxs — original implementation.
 * Canonical `noxs` CLI script installed into the rootfs at /usr/local/bin/noxs.
 * Mirrored at linux-runtime/launcher/noxs-cli — CI diff-checks both copies.
 */
package com.crossberry.noxs.runtime

object NoxsCliTemplate {

    val CLI = """#!/bin/bash
# noxs — Noxs CLI for the Debian userspace (original implementation)
# Usage:
#   noxs help
#   noxs info
#   noxs code install|start|stop|restart|status|logs
#   noxs docker install|start|stop|restart|status|info|test
#   noxs storage [status|setup|map|repair|reset|ls|mkdir|cat|get|put|rm|cp|mv]
#   noxs-service start|stop|restart|status [service]
#   noxs-socket list|test <path>
#   noxs-ps

set -u
# Paths are env-overridable so scripts/test-code-cli.sh can exercise the
# lifecycle in a sandbox without touching a real installation.
NOXS_RUN_DIR="${'$'}{NOXS_RUN_DIR:-/var/run/noxs}"
CODE_DIR="${'$'}{CODE_DIR:-/opt/code-server}"
CODE_DATA="${'$'}{CODE_DATA:-${'$'}HOME/.noxs/code}"
CODE_WORKSPACE="${'$'}{CODE_WORKSPACE:-${'$'}HOME}"
CODE_ADDR="${'$'}{CODE_ADDR:-127.0.0.1:8080}"   # local-only by default, never exposed
CODE_FALLBACK_VERSION="${'$'}{CODE_FALLBACK_VERSION:-4.89.1}"
CODE_REPO="${'$'}{CODE_REPO:-coder/code-server}"
CODE_CONF="${'$'}CODE_DATA/config.yaml"
CODE_LOG_DIR="${'$'}{NOXS_LOG_DIR:-/var/log/noxs}"
CODE_LOG="${'$'}CODE_LOG_DIR/code-server.log"
CODE_TMP="${'$'}{CODE_TMP:-/tmp/noxs-code}"
CODE_PID="${'$'}NOXS_RUN_DIR/host/code-server.pid"       # host bind → readable by the Noxs app
CODE_INSTALL_LOG="${'$'}NOXS_RUN_DIR/host/code-install.log"  # progress for the Noxs editor screen
NOXS_STORAGE_HOST="/var/run/noxs/host/storage"
NOXS_STORAGE_LAST_ID=""

msg()  { printf 'noxs: %s\n' "${'$'}*"; }
err()  { printf 'noxs: %s\n' "${'$'}*" >&2; }

# ---------------------------------------------------------------- noxs code
# code-server (VS Code in the browser) lifecycle. Local-only by design:
#   - binds to 127.0.0.1 only, never exposed to the network or a tunnel
#   - auth: password — generated once into ${'$'}CODE_CONF (mode 600), never
#     printed to stdout, never written to the log
#   - runs as an ordinary guest process (no systemd/init inside proot)
# The Noxs Android editor drives the same commands: it starts the server in
# --foreground mode under its own proot process and reads ${'$'}CODE_PID (which
# lives in the host bind) plus ${'$'}CODE_CONF to attach its WebView.

code_installed() {
    [ -x "${'$'}CODE_DIR/bin/code-server" ]
}

code_version_installed() {
    tr -cd '0-9A-Za-z.\-' < "${'$'}CODE_DIR/version" 2>/dev/null
}

code_running() {
    # PID-file based, with stale-metadata cleanup. PID reuse is rejected by
    # verifying the process really is our code-server binary.
    [ -f "${'$'}CODE_PID" ] || return 1
    local pid
    pid="${'$'}(cat "${'$'}CODE_PID" 2>/dev/null)"
    case "${'$'}pid" in
        ''|*[!0-9]*) rm -f "${'$'}CODE_PID"; return 1 ;;
    esac
    if kill -0 "${'$'}pid" 2>/dev/null && \
        tr -d '\0' < "/proc/${'$'}pid/cmdline" 2>/dev/null | grep -q 'code-server'; then
        return 0
    fi
    rm -f "${'$'}CODE_PID"   # stale process metadata (crash, reboot, PID reuse)
    return 1
}

code_bind_addr() {
    local addr
    addr="${'$'}(sed -n 's/^bind-addr:[[:space:]]*//p' "${'$'}CODE_CONF" 2>/dev/null)"
    if [ -n "${'$'}addr" ]; then
        printf '%s\n' "${'$'}addr"
    else
        printf '%s\n' "${'$'}CODE_ADDR"
    fi
}

code_port() {
    local addr
    addr="${'$'}(code_bind_addr)"
    printf '%s\n' "${'$'}{addr##*:}"
}

code_port_busy() {
    # true when ANY listener (any address) holds the TCP port
    local hex
    hex="${'$'}(printf '%04X' "${'$'}1" 2>/dev/null)" || return 0
    awk -v h="${'$'}hex" '
        NR > 1 && ${'$'}4 == "0A" {
            split(${'$'}2, a, ":")
            if (a[2] == h) found = 1
        }
        END { exit (found ? 0 : 1) }
    ' /proc/net/tcp /proc/net/tcp6 2>/dev/null
}

code_ensure_config() {
    # Generates the local-only server config once. The password is random,
    # never echoed, and the file is written with mode 600.
    [ -f "${'$'}CODE_CONF" ] && return 0
    mkdir -p "${'$'}CODE_DATA" || return 1
    local pass
    pass="${'$'}(head -c 24 /dev/urandom 2>/dev/null | base64 | tr -d '\n')" || return 1
    [ -n "${'$'}pass" ] || return 1
    (
        umask 077
        printf 'bind-addr: %s\nauth: password\ncert: false\npassword: %s\n' \
            "${'$'}CODE_ADDR" "${'$'}pass" > "${'$'}CODE_CONF"
    ) || return 1
    chmod 600 "${'$'}CODE_CONF" 2>/dev/null
    return 0
}

code_http_ok() {
    # Minimal HTTP probe over bash /dev/tcp — no curl dependency at runtime.
    # Any valid HTTP status line proves an HTTP server is listening.
    local host="${'$'}{1%%:*}" port="${'$'}{1##*:}"
    bash -c '
        exec 3<>"/dev/tcp/${'$'}1/${'$'}2" 2>/dev/null || exit 1
        printf "GET / HTTP/1.0\r\nHost: %s\r\n\r\n" "${'$'}1" >&3
        IFS= read -r line <&3 || exit 1
        case "${'$'}line" in HTTP/1.[01]*) exit 0 ;; *) exit 1 ;; esac
    ' code_http_ok "${'$'}host" "${'$'}port"
}

code_wait_ready() {
    local addr="${'$'}1" timeout="${'$'}{2:-45}" i
    for i in ${'$'}(seq 1 "${'$'}timeout"); do
        if code_http_ok "${'$'}addr"; then return 0; fi
        code_running || return 1
        sleep 1
    done
    return 1
}

code_resolve_port() {
    # If the configured port is held by something else, move to the next free
    # local port and persist the choice in the config file.
    local preferred port
    preferred="${'$'}(code_port)"
    case "${'$'}preferred" in
        ''|*[!0-9]*) preferred=8080 ;;
    esac
    port="${'$'}preferred"
    while code_port_busy "${'$'}port"; do
        port=${'$'}((port + 1))
        if [ ${'$'}((port - preferred)) -ge 10 ]; then
            err "no free local port in ${'$'}preferred..${'$'}((port - 1))"
            return 1
        fi
    done
    if [ "${'$'}port" != "${'$'}preferred" ]; then
        sed -i "s/^bind-addr:.*/bind-addr: 127.0.0.1:${'$'}port/" "${'$'}CODE_CONF" || return 1
        msg "port ${'$'}preferred is in use — code-server moved to 127.0.0.1:${'$'}port"
    fi
    return 0
}

code_start() {
    # usage: noxs code start [--foreground] [--workspace DIR]
    local fg=0 ws=""
    while [ ${'$'}# -gt 0 ]; do
        case "${'$'}1" in
            --foreground) fg=1 ;;
            --workspace)
                [ ${'$'}# -ge 2 ] || { err "--workspace needs a directory"; return 2; }
                ws="${'$'}2"; shift ;;
            --workspace=*) ws="${'$'}{1#--workspace=}" ;;
            -*) err "unknown option: ${'$'}1"; return 2 ;;
            *)  ws="${'$'}1" ;;
        esac
        shift
    done
    if ! code_installed; then
        err "code-server is not installed — run: noxs code install"
        return 1
    fi
    if code_running; then
        msg "code-server already running (pid ${'$'}(cat "${'$'}CODE_PID"))"
        code_status_json
        return 0
    fi
    [ -n "${'$'}ws" ] || ws="${'$'}CODE_WORKSPACE"
    case "${'$'}ws" in
        /*) : ;;
        *)  ws="${'$'}PWD/${'$'}ws" ;;
    esac
    if [ ! -d "${'$'}ws" ]; then
        err "workspace directory not found: ${'$'}ws"
        return 1
    fi
    if ! code_ensure_config; then
        err "cannot write the code-server config at ${'$'}CODE_CONF"
        return 1
    fi
    code_resolve_port || return 1
    mkdir -p "${'$'}(dirname "${'$'}CODE_PID")" "${'$'}CODE_LOG_DIR" "${'$'}CODE_DATA" || return 1
    local addr
    addr="${'$'}(code_bind_addr)"
    local args=(
        --config "${'$'}CODE_CONF"
        --user-data-dir "${'$'}CODE_DATA"
        --extensions-dir "${'$'}CODE_DATA/extensions"
        --disable-telemetry
        --disable-update-check
        "${'$'}ws"
    )
    if [ "${'$'}fg" = 1 ]; then
        # Managed mode: the Noxs Android editor runs this under its own proot
        # process and keeps it alive; the pid file lets any client verify or
        # stop the server. The config carries bind-addr/auth/password.
        printf '%s\n' "${'$'}${'$'}" > "${'$'}CODE_PID"
        exec "${'$'}CODE_DIR/bin/code-server" "${'$'}{args[@]}"
    fi
    nohup "${'$'}CODE_DIR/bin/code-server" "${'$'}{args[@]}" >> "${'$'}CODE_LOG" 2>&1 &
    echo "${'$'}!" > "${'$'}CODE_PID"
    if code_wait_ready "${'$'}addr" 45; then
        msg "code-server started on http://${'$'}addr (local-only, auth: password)"
    else
        err "code-server failed to start — inspect: noxs code logs"
        rm -f "${'$'}CODE_PID"
        return 1
    fi
}

code_stop() {
    if code_running; then
        local pid i
        pid="${'$'}(cat "${'$'}CODE_PID")"
        kill "${'$'}pid" 2>/dev/null
        for i in ${'$'}(seq 1 10); do
            kill -0 "${'$'}pid" 2>/dev/null || break
            sleep 1
        done
        if kill -0 "${'$'}pid" 2>/dev/null; then
            kill -9 "${'$'}pid" 2>/dev/null
        fi
        msg "code-server stopped"
    else
        msg "code-server not running"
    fi
    # also clear the legacy pid location used by older builds
    rm -f "${'$'}CODE_PID" "${'$'}NOXS_RUN_DIR/code-server.pid" 2>/dev/null
    return 0
}

code_status() {
    if code_running; then
        echo "code-server: running (pid ${'$'}(cat "${'$'}CODE_PID"), http://${'$'}(code_bind_addr), local-only)"
        return 0
    elif code_installed; then
        echo "code-server: installed, stopped"
        return 3
    else
        echo "code-server: not installed"
        return 3
    fi
}

code_status_json() {
    # Machine-readable status for the Noxs Android editor. Credentials are
    # never included. Flat JSON: string / number / boolean / null only.
    local installed=false running=false ready=false version="" pid="" port
    if code_installed; then
        installed=true
        version="${'$'}(code_version_installed)"
    fi
    if code_running; then
        running=true
        pid="${'$'}(cat "${'$'}CODE_PID")"
    fi
    port="${'$'}(code_port)"
    case "${'$'}port" in
        ''|*[!0-9]*) port=8080 ;;
    esac
    if [ "${'$'}running" = true ] && code_http_ok "127.0.0.1:${'$'}port"; then
        ready=true
    fi
    printf '{"installed":%s,"version":"%s","running":%s,"pid":%s,"port":%s,"http_ready":%s,"address":"127.0.0.1:%s"}\n' \
        "${'$'}installed" "${'$'}{version:-}" "${'$'}running" "${'$'}{pid:-null}" "${'$'}port" "${'$'}ready" "${'$'}port"
}

code_logs() {
    # usage: noxs code logs [LINES]
    local n="${'$'}{1:-100}"
    case "${'$'}n" in
        ''|*[!0-9]*) n=100 ;;
    esac
    if [ ! -f "${'$'}CODE_LOG" ]; then
        msg "no code-server log yet at ${'$'}CODE_LOG"
        return 0
    fi
    # credential hygiene: never echo auth material back into a terminal
    tail -n "${'$'}n" "${'$'}CODE_LOG" | grep -vi 'password' || true
}

code_ilog() {
    # install progress: stdout AND a log file the Noxs editor screen tails
    printf 'noxs: %s\n' "${'$'}*"
    printf '%s\n' "${'$'}*" >> "${'$'}CODE_INSTALL_LOG" 2>/dev/null
}

code_arch() {
    case "${'$'}(uname -m 2>/dev/null)" in
        aarch64|arm64) printf '%s\n' "linux-arm64" ;;
        x86_64|amd64)  printf '%s\n' "linux-amd64" ;;
        *) err "unsupported CPU architecture: ${'$'}(uname -m 2>/dev/null || echo unknown)" ;;
    esac
}

code_latest_version() {
    # Best effort: ask the GitHub API for the latest release tag. Any failure
    # is non-fatal (the caller falls back to the pinned version).
    local body tag=""
    if command -v curl >/dev/null 2>&1; then
        body="${'$'}(curl -fsSL --max-time 20 "https://api.github.com/repos/${'$'}CODE_REPO/releases/latest" 2>/dev/null)" || return 1
    elif command -v wget >/dev/null 2>&1; then
        body="${'$'}(wget -qO- -T 20 "https://api.github.com/repos/${'$'}CODE_REPO/releases/latest" 2>/dev/null)" || return 1
    else
        return 1
    fi
    tag="${'$'}(printf '%s\n' "${'$'}body" | \
        sed -n 's/.*"tag_name":[[:space:]]*"v\([0-9][0-9A-Za-z.\-]*\)".*/\1/p' | head -n 1)"
    [ -n "${'$'}tag" ] && printf '%s\n' "${'$'}tag"
}

code_fetch() {
    # ${'$'}1=url  ${'$'}2=output — download with retries; curl shows its progress bar
    local url="${'$'}1" out="${'$'}2"
    if command -v curl >/dev/null 2>&1; then
        curl -fL --retry 3 --retry-delay 2 --connect-timeout 15 --max-time 900 -o "${'$'}out" "${'$'}url"
    elif command -v wget >/dev/null 2>&1; then
        wget -q --tries=3 -O "${'$'}out" "${'$'}url"
    else
        err "neither curl nor wget is available — install one with: sudo apt install curl"
        return 127
    fi
}

code_verify() {
    # ${'$'}1=archive  ${'$'}2=version  ${'$'}3=asset name. Verifies sha256 against the
    # release SHASUMS.txt when upstream publishes one; otherwise records the
    # digest of the archive we actually received.
    local tar="${'$'}1" ver="${'$'}2" asset="${'$'}3" sums="${'$'}CODE_TMP/SHASUMS-${'$'}ver.txt" want got
    rm -f "${'$'}sums"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --max-time 30 -o "${'$'}sums" \
            "https://github.com/coder/code-server/releases/download/v${'$'}ver/SHASUMS.txt" 2>/dev/null
    elif command -v wget >/dev/null 2>&1; then
        wget -q -T 30 -O "${'$'}sums" \
            "https://github.com/coder/code-server/releases/download/v${'$'}ver/SHASUMS.txt" 2>/dev/null
    fi
    if [ -s "${'$'}sums" ] && command -v sha256sum >/dev/null 2>&1; then
        want="${'$'}(awk -v a="${'$'}asset" '{ f=${'$'}2; sub(/^\*/, "", f); if (f == a) print ${'$'}1 }' "${'$'}sums" | head -n 1)"
        if [ -n "${'$'}want" ]; then
            got="${'$'}(sha256sum "${'$'}tar" 2>/dev/null | awk '{print ${'$'}1}')"
            if [ "${'$'}got" = "${'$'}want" ]; then
                msg "checksum verified (sha256)"
                return 0
            fi
            err "checksum mismatch for ${'$'}asset"
            return 1
        fi
    fi
    if command -v sha256sum >/dev/null 2>&1; then
        msg "no upstream checksum entry found — recorded sha256: ${'$'}(sha256sum "${'$'}tar" 2>/dev/null | awk '{print ${'$'}1}')"
    else
        msg "no upstream checksum entry found and sha256sum unavailable — skipping verification"
    fi
    return 0
}

code_smoke() {
    # Detects an incompatible release up front instead of reporting success.
    local ver="${'$'}{1:-}" out
    if ! out="${'$'}("${'$'}CODE_DIR/bin/code-server" --version 2>&1)"; then
        err "smoke test failed:"
        printf '%s\n' "${'$'}out" | tail -n 3 | sed 's/^/  /' >&2
        return 1
    fi
    printf '%s\n' "${'$'}out" | head -n 1 | sed 's/^/  /'
    return 0
}

code_install() {
    # usage: noxs code install [--force] [--version X.Y.Z]
    local force=0 ver_arg=""
    while [ ${'$'}# -gt 0 ]; do
        case "${'$'}1" in
            --force) force=1 ;;
            --version)
                [ ${'$'}# -ge 2 ] || { err "--version needs a value"; return 2; }
                ver_arg="${'$'}2"; shift ;;
            *) err "usage: noxs code install [--force] [--version X.Y.Z]"; return 2 ;;
        esac
        shift
    done
    local arch
    arch="${'$'}(code_arch)" || return 1
    if ! grep -qiE 'debian|bookworm|bullseye' /etc/os-release 2>/dev/null; then
        err "this does not look like a Debian-based Noxs environment — aborting"
        return 1
    fi
    local ver=""
    if [ -n "${'$'}ver_arg" ]; then
        ver="${'$'}ver_arg"
    else
        ver="${'$'}(code_latest_version)" || ver=""
        [ -n "${'$'}ver" ] || ver="${'$'}CODE_FALLBACK_VERSION"
    fi
    case "${'$'}ver" in
        *[!0-9A-Za-z.\-]*)
            err "invalid version: refusing to install from an untrusted tag"
            return 1 ;;
    esac
    local cur=""
    cur="${'$'}(code_version_installed)"
    if [ "${'$'}force" != 1 ] && code_installed && [ "${'$'}cur" = "${'$'}ver" ]; then
        msg "code-server ${'$'}ver already installed at ${'$'}CODE_DIR"
        code_smoke "${'$'}ver" || return 1
        return 0
    fi
    # storage headroom: archive (~90 MB) + extracted tree (~450 MB)
    local free_kb
    free_kb="${'$'}(df -Pk /opt 2>/dev/null | awk 'NR==2 {print ${'$'}4}')"
    [ -n "${'$'}free_kb" ] || free_kb="${'$'}(df -Pk / 2>/dev/null | awk 'NR==2 {print ${'$'}4}')"
    if [ -n "${'$'}free_kb" ] && [ "${'$'}free_kb" -lt 700000 ] 2>/dev/null; then
        err "insufficient storage: ~700 MB free required, ${'$'}{free_kb} KB available"
        return 1
    fi
    mkdir -p "${'$'}CODE_TMP" "${'$'}(dirname "${'$'}CODE_PID")" "${'$'}CODE_LOG_DIR" || return 1
    rm -rf "${'$'}CODE_TMP"/install.* 2>/dev/null   # sweep interrupted installs
    local tmp="${'$'}CODE_TMP/install.${'$'}${'$'}"
    mkdir -p "${'$'}tmp" || return 1
    local asset="code-server-${'$'}ver-${'$'}arch.tar.gz"
    local url="https://github.com/coder/code-server/releases/download/v${'$'}ver/${'$'}asset"
    : > "${'$'}CODE_INSTALL_LOG" 2>/dev/null
    code_ilog "[1/5] downloading code-server ${'$'}ver for ${'$'}arch"
    if ! code_fetch "${'$'}url" "${'$'}tmp/${'$'}asset"; then
        rm -rf "${'$'}tmp"
        code_ilog "download failed — check network connectivity and retry"
        err "download failed — check network connectivity and retry"
        return 1
    fi
    code_ilog "[2/5] verifying checksum"
    if ! code_verify "${'$'}tmp/${'$'}asset" "${'$'}ver" "${'$'}asset"; then
        rm -rf "${'$'}tmp"
        err "checksum verification failed — archive discarded"
        return 1
    fi
    code_ilog "[3/5] extracting archive"
    if ! tar -xzf "${'$'}tmp/${'$'}asset" -C "${'$'}tmp" 2>/dev/null; then
        rm -rf "${'$'}tmp"
        code_ilog "archive is corrupted (interrupted download?) — retry the install"
        err "archive is corrupted (interrupted download?) — retry the install"
        return 1
    fi
    if [ ! -x "${'$'}tmp/code-server-${'$'}ver-${'$'}arch/bin/code-server" ]; then
        rm -rf "${'$'}tmp"
        err "unexpected archive layout — refusing to install"
        return 1
    fi
    code_ilog "[4/5] installing to ${'$'}CODE_DIR"
    rm -rf "${'$'}CODE_DIR.new"
    if ! mv "${'$'}tmp/code-server-${'$'}ver-${'$'}arch" "${'$'}CODE_DIR.new"; then
        rm -rf "${'$'}tmp"
        err "install staging failed"
        return 1
    fi
    rm -rf "${'$'}CODE_DIR"
    if ! mv "${'$'}CODE_DIR.new" "${'$'}CODE_DIR"; then
        rm -rf "${'$'}tmp"
        err "install failed at the final move"
        return 1
    fi
    printf '%s\n' "${'$'}ver" > "${'$'}CODE_DIR/version"
    rm -rf "${'$'}tmp"
    code_ilog "[5/5] smoke test"
    if ! code_smoke "${'$'}ver"; then
        code_ilog "this build cannot run in this environment"
        err "code-server ${'$'}ver cannot run in this environment (see messages above)"
        err "hint: sudo apt install libatomic1 libsecret-1-0 && noxs code install --force"
        return 1
    fi
    code_ilog "code-server ${'$'}ver installed"
    msg "code-server ${'$'}ver installed — start it with: noxs code start"
    return 0
}

# -------------------------------------------------------- noxs SAF storage

storage_cleanup() {
    local id="${'$'}1"
    rm -f "${'$'}NOXS_STORAGE_HOST/requests/${'$'}id" \
        "${'$'}NOXS_STORAGE_HOST/responses/${'$'}id" \
        "${'$'}NOXS_STORAGE_HOST/payloads/${'$'}id" \
        "${'$'}NOXS_STORAGE_HOST/response-payloads/${'$'}id"
}

storage_request() {
    local operation="${'$'}1" category="${'$'}{2:-}" path="${'$'}{3:-}" argument="${'$'}{4:-}"
    local payload_source="${'$'}{5:-}" payload_text="${'$'}{6:-}"
    local id request response first attempt
    NOXS_STORAGE_LAST_ID=""
    if [ ! -d "${'$'}NOXS_STORAGE_HOST/requests" ]; then
        err "Noxs storage bridge is unavailable; open the Noxs terminal and retry"
        return 1
    fi
    id="${'$'}${'$'}-${'$'}(date +%s)-${'$'}RANDOM-${'$'}RANDOM"
    request="${'$'}NOXS_STORAGE_HOST/requests/${'$'}id"
    response="${'$'}NOXS_STORAGE_HOST/responses/${'$'}id"
    storage_cleanup "${'$'}id"
    if [ -n "${'$'}payload_source" ]; then
        if [ ! -f "${'$'}payload_source" ]; then
            err "local upload file not found: ${'$'}payload_source"
            return 1
        fi
        if ! cp -- "${'$'}payload_source" "${'$'}NOXS_STORAGE_HOST/payloads/${'$'}id"; then
            storage_cleanup "${'$'}id"
            err "could not stage upload file"
            return 1
        fi
    elif [ "${'$'}operation" = "write" ]; then
        if ! printf '%s' "${'$'}payload_text" > "${'$'}NOXS_STORAGE_HOST/payloads/${'$'}id"; then
            storage_cleanup "${'$'}id"
            err "could not stage text payload"
            return 1
        fi
    fi
    printf '%s\n%s\n%s\n%s\n' "${'$'}operation" "${'$'}category" "${'$'}path" "${'$'}argument" > "${'$'}request" || {
        storage_cleanup "${'$'}id"
        err "could not send storage request"
        return 1
    }
    attempt=0
    while [ ! -f "${'$'}response" ] && [ "${'$'}attempt" -lt 240 ]; do
        sleep 0.5
        attempt=${'$'}((attempt + 1))
    done
    if [ ! -f "${'$'}response" ]; then
        storage_cleanup "${'$'}id"
        err "Noxs Android storage service did not respond; keep Noxs open and retry"
        return 1
    fi
    first="${'$'}(sed -n '1p' "${'$'}response" 2>/dev/null || true)"
    if [ "${'$'}first" = "OK" ]; then
        NOXS_STORAGE_LAST_ID="${'$'}id"
        tail -n +2 "${'$'}response"
        return 0
    fi
    tail -n +2 "${'$'}response" >&2
    storage_cleanup "${'$'}id"
    return 1
}

storage_usage() {
    cat <<'HELP'
Usage: noxs storage [command]
  status                         Show current user-granted folder access
  setup                          Choose a shared folder with Android's picker
  map <category>                 Choose a folder for a category
  repair                         Re-check persisted permissions and mappings
  reset                          Release Noxs mappings only (never deletes files)
  ls <category> [relative-path]  List documents inside a selected folder
  mkdir <category> <path>        Create a folder through the SAF provider
  cat <category> <path>          Read a document to the terminal
  get <category> <path> <local>  Copy a document into the Noxs Linux sandbox
  put <category> <path> <local>  Copy a sandbox file into the selected folder
  rm <category> <path>           Delete the selected document via Android's provider
  cp <category> <src> <dest>     Copy within one selected folder tree
  mv <category> <src> <dest>     Move within one selected folder tree

Categories: shared, downloads, dcim, pictures, music, movies
SAF access is command-based. It is not a POSIX mount; ordinary paths,
Bash redirection, and arbitrary Linux applications cannot access these folders.
HELP
}

storage_command() {
    local action="${'$'}{1:-status}" category path destination local_file
    if [ "${'$'}#" -gt 0 ]; then shift; fi
    case "${'$'}action" in
        help|--help|-h) storage_usage ;;
        ""|status)
            storage_request status
            ;;
        setup)
            storage_request setup shared
            ;;
        map)
            category="${'$'}{1:-}"
            if [ -z "${'$'}category" ]; then err "usage: noxs storage map <category>"; return 2; fi
            storage_request map "${'$'}category"
            ;;
        repair)
            storage_request repair
            ;;
        reset)
            storage_request reset
            ;;
        ls|list)
            category="${'$'}{1:-}"; path="${'$'}{2:-}"
            if [ -z "${'$'}category" ]; then err "usage: noxs storage ls <category> [relative-path]"; return 2; fi
            storage_request list "${'$'}category" "${'$'}path"
            ;;
        mkdir)
            category="${'$'}{1:-}"; path="${'$'}{2:-}"
            if [ -z "${'$'}category" ] || [ -z "${'$'}path" ]; then err "usage: noxs storage mkdir <category> <path>"; return 2; fi
            storage_request mkdir "${'$'}category" "${'$'}path"
            ;;
        cat)
            category="${'$'}{1:-}"; path="${'$'}{2:-}"
            if [ -z "${'$'}category" ] || [ -z "${'$'}path" ]; then err "usage: noxs storage cat <category> <path>"; return 2; fi
            if storage_request read "${'$'}category" "${'$'}path" >/dev/null; then
                local rc
                cat "${'$'}NOXS_STORAGE_HOST/response-payloads/${'$'}NOXS_STORAGE_LAST_ID"
                rc=${'$'}?
                storage_cleanup "${'$'}NOXS_STORAGE_LAST_ID"
                return "${'$'}rc"
            fi
            return 1
            ;;
        get)
            category="${'$'}{1:-}"; path="${'$'}{2:-}"; local_file="${'$'}{3:-}"
            if [ -z "${'$'}category" ] || [ -z "${'$'}path" ] || [ -z "${'$'}local_file" ]; then
                err "usage: noxs storage get <category> <path> <local-file>"; return 2
            fi
            if storage_request get "${'$'}category" "${'$'}path" >/dev/null; then
                if cp -- "${'$'}NOXS_STORAGE_HOST/response-payloads/${'$'}NOXS_STORAGE_LAST_ID" "${'$'}local_file"; then
                    storage_cleanup "${'$'}NOXS_STORAGE_LAST_ID"
                    msg "downloaded to the Noxs Linux sandbox"
                else
                    storage_cleanup "${'$'}NOXS_STORAGE_LAST_ID"
                    return 1
                fi
            else return 1; fi
            ;;
        put)
            category="${'$'}{1:-}"; path="${'$'}{2:-}"; local_file="${'$'}{3:-}"
            if [ -z "${'$'}category" ] || [ -z "${'$'}path" ] || [ -z "${'$'}local_file" ]; then
                err "usage: noxs storage put <category> <path> <local-file>"; return 2
            fi
            if storage_request put "${'$'}category" "${'$'}path" "" "${'$'}local_file"; then
                storage_cleanup "${'$'}NOXS_STORAGE_LAST_ID"
            else return 1; fi
            ;;
        write)
            category="${'$'}{1:-}"; path="${'$'}{2:-}"
            if [ -z "${'$'}category" ] || [ -z "${'$'}path" ]; then
                err "usage: noxs storage write <category> <path> <text>"; return 2
            fi
            shift 2
            if ! storage_request write "${'$'}category" "${'$'}path" "" "" "${'$'}*"; then return 1; fi
            storage_cleanup "${'$'}NOXS_STORAGE_LAST_ID"
            ;;
        rm|delete)
            category="${'$'}{1:-}"; path="${'$'}{2:-}"
            if [ -z "${'$'}category" ] || [ -z "${'$'}path" ]; then err "usage: noxs storage rm <category> <path>"; return 2; fi
            storage_request delete "${'$'}category" "${'$'}path"
            ;;
        cp|copy|mv|move)
            category="${'$'}{1:-}"; path="${'$'}{2:-}"; destination="${'$'}{3:-}"
            if [ -z "${'$'}category" ] || [ -z "${'$'}path" ] || [ -z "${'$'}destination" ]; then
                err "usage: noxs storage ${'$'}action <category> <source> <destination>"; return 2
            fi
            if [ "${'$'}action" = "cp" ] || [ "${'$'}action" = "copy" ]; then
                storage_request copy "${'$'}category" "${'$'}path" "${'$'}destination"
            else
                storage_request move "${'$'}category" "${'$'}path" "${'$'}destination"
            fi
            ;;
        *)
            err "unknown storage command: ${'$'}action"
            storage_usage >&2
            return 2
            ;;
    esac
}

# ---------------------------------------------------------------- noxs docker

DOCKER_STATE="/var/run/noxs/docker-state"
DOCKER_PID="/var/run/noxs/dockerd.pid"
DOCKER_LOG="/var/log/noxs/dockerd.log"

docker_installed() {
    [ -x /usr/bin/docker ]
}

docker_alive() {
    timeout 10 sudo docker info >/dev/null 2>&1
}

docker_state_value() {
    sed -n "s/^${'$'}1=//p" "${'$'}DOCKER_STATE" 2>/dev/null | head -1
}

docker_save_state() {
    # Persist ONLY safe runtime facts (no credentials, ever).
    mkdir -p /var/run/noxs
    local containers="${'$'}{4:-${'$'}(docker_state_value containers)}"
    {
        echo "state=${'$'}1"
        echo "mode=${'$'}2"
        echo "daemon_running=${'$'}3"
        [ -n "${'$'}containers" ] && echo "containers=${'$'}containers"
        echo "updated=${'$'}(date +%s)"
    } > "${'$'}DOCKER_STATE" 2>/dev/null || true
}

docker_classify_log() {
    # Real dockerd failure classification — Android/proot runtime limitations.
    if grep -qiE "failed to initialize nft|iptables.*(permission denied|operation not permitted)" "${'$'}DOCKER_LOG" 2>/dev/null; then
        echo networking
    elif grep -qiE "unable to find (memory|cpu|io|cpuset|pids) controller|cgroup.controllers.*permission denied" "${'$'}DOCKER_LOG" 2>/dev/null; then
        echo cgroups
    elif grep -qiE "overlay.*(permission denied|operation not permitted)|mount.*permission denied" "${'$'}DOCKER_LOG" 2>/dev/null; then
        echo storage
    else
        echo unknown
    fi
}

docker_wait_daemon() {
    for _ in ${'$'}(seq 1 15); do
        if docker_alive; then return 0; fi
        sleep 2
    done
    return 1
}

docker_start() {
    if ! docker_installed; then
        err "Docker is not installed — run: noxs docker install"
        return 1
    fi
    if docker_alive; then
        msg "Docker daemon is already running"
        return 0
    fi
    mkdir -p /var/run/noxs /var/log/noxs
    local mode_used="normal" rc=1
    if [ "${'$'}(docker_state_value mode)" != "compatibility" ]; then
        # One honest normal attempt (never repeatedly retried).
        nohup sudo /usr/bin/dockerd > "${'$'}DOCKER_LOG" 2>&1 &
        echo "${'$'}!" > "${'$'}DOCKER_PID"
        if docker_wait_daemon; then rc=0; fi
    fi
    if [ ${'$'}rc -ne 0 ]; then
        case "${'$'}(docker_classify_log)" in
            networking|cgroups|storage)
                msg "Android/proot restricts Docker ${'$'}(docker_classify_log) — retrying in Noxs compatibility mode"
                ;;
            *)
                msg "Default Docker startup failed — retrying in Noxs compatibility mode"
                ;;
        esac
        mode_used="compatibility"
        nohup sudo /usr/bin/dockerd --iptables=false --bridge=none > "${'$'}DOCKER_LOG" 2>&1 &
        echo "${'$'}!" > "${'$'}DOCKER_PID"
        if docker_wait_daemon; then rc=0; fi
    fi
    if [ ${'$'}rc -eq 0 ]; then
        if [ "${'$'}mode_used" = "compatibility" ]; then
            docker_save_state compatibility compatibility yes
            msg "Docker daemon started (Noxs compatibility mode)"
        else
            docker_save_state ready normal yes
            msg "Docker daemon started"
        fi
    else
        docker_save_state daemon_unavailable compatibility no
        err "Docker daemon could not start — a Noxs runtime limitation, not a broken installation"
        err "Last daemon messages:"
        tail -3 "${'$'}DOCKER_LOG" 2>/dev/null | sed 's/^/  /'
        return 1
    fi
}

docker_stop() {
    local pid
    pid="${'$'}(cat "${'$'}DOCKER_PID" 2>/dev/null)"
    if [ -z "${'$'}pid" ] || ! kill -0 "${'$'}pid" 2>/dev/null; then
        # The daemon may have been started by the Noxs app itself.
        pid="${'$'}(pgrep -o -x dockerd 2>/dev/null)"
    fi
    if [ -z "${'$'}pid" ] || ! kill -0 "${'$'}pid" 2>/dev/null; then
        msg "Docker daemon is not running"
        rm -f "${'$'}DOCKER_PID"
        docker_save_state stopped "${'$'}(docker_state_value mode)" no
        return 0
    fi
    kill "${'$'}pid" 2>/dev/null
    for _ in ${'$'}(seq 1 10); do
        kill -0 "${'$'}pid" 2>/dev/null || break
        sleep 1
    done
    kill -9 "${'$'}pid" 2>/dev/null || true
    rm -f "${'$'}DOCKER_PID"
    docker_save_state stopped "${'$'}(docker_state_value mode)" no
    msg "Docker daemon stopped"
}

docker_status() {
    if ! docker_installed; then
        echo "Docker CLI: not installed"
        echo "Docker daemon: stopped"
        echo "Storage: unavailable"
        echo "Networking: unavailable"
        echo "Containers: unsupported"
        echo "Mode: not installed"
        return 3
    fi
    echo "Docker CLI: installed"
    if docker_alive; then
        echo "Docker daemon: running"
        local driver mode containers
        driver="${'$'}(sudo docker info 2>/dev/null | sed -n 's/^ *Storage Driver: *//p' | head -1)"
        if [ -n "${'$'}driver" ]; then
            echo "Storage: available (${'$'}driver)"
        else
            echo "Storage: unavailable"
        fi
        mode="${'$'}(docker_state_value mode)"
        containers="${'$'}(docker_state_value containers)"
        if [ "${'$'}mode" = "compatibility" ]; then
            echo "Networking: limited"
            echo "Mode: compatibility"
        else
            echo "Networking: available"
            echo "Mode: normal"
        fi
        case "${'$'}containers" in
            supported|unsupported) echo "Containers: ${'$'}containers" ;;
            *) echo "Containers: untested (run: noxs docker test)" ;;
        esac
    else
        echo "Docker daemon: stopped"
        echo "Storage: unavailable"
        echo "Networking: unavailable"
        echo "Containers: untested"
        echo "Mode: stopped"
        return 3
    fi
}

docker_info() {
    if ! docker_installed; then
        err "Docker is not installed — run: noxs docker install"
        return 1
    fi
    exec sudo docker info
}

docker_test() {
    if ! docker_installed; then
        err "Docker is not installed — run: noxs docker install"
        return 1
    fi
    if ! docker_alive; then
        err "Docker daemon is not running — start it first: noxs docker start"
        return 1
    fi
    msg "Running the real container test (hello-world)…"
    if sudo docker run --rm hello-world; then
        docker_save_state "${'$'}(docker_state_value state)" "${'$'}(docker_state_value mode)" yes supported
        msg "Container execution: supported"
        return 0
    fi
    docker_save_state "${'$'}(docker_state_value state)" "${'$'}(docker_state_value mode)" yes unsupported
    err "Container execution: unsupported on this Android kernel (a Noxs runtime limitation)"
    return 1
}

docker_install() {
    msg "Installing Docker from Debian repositories…"
    export DEBIAN_FRONTEND=noninteractive
    if ! sudo apt-get update; then
        err "apt update failed — check the network connection"
        return 1
    fi
    if ! sudo apt-get install --yes --no-install-recommends docker.io; then
        err "Docker installation failed"
        docker_save_state install_failed "" no
        return 1
    fi
    docker --version
    msg "Docker installed. Start it with: noxs docker start"
}

# ---------------------------------------------------------------- noxs info

do_info() {
    local debian_version
    debian_version="${'$'}(head -1 /etc/debian_version 2>/dev/null || echo unknown)"
    echo "Noxs — Debian 12 (bookworm) userspace"
    echo "  Debian:      ${'$'}debian_version"
    echo "  environment: proot (Android device root is not available)"
    echo "  session:     ${'$'}{NOXS_SESSION_LABEL:-terminal}"
    echo "  account:     ${'$'}(id -un) (uid ${'$'}(id -u))"
    echo "  shell:       ${'$'}{SHELL:-/bin/bash}"
    echo "  architecture: ${'$'}(uname -m 2>/dev/null || echo unknown)"
    echo "  storage:     SAF document commands; no POSIX mount"
    echo "  sudo:        Noxs sandbox only"
    noxs-socket list 2>/dev/null | head -5 || true
}

case "${'$'}{1:-help}" in
    code)
        case "${'$'}{2:-status}" in
            install) shift 2; code_install "${'$'}@" ;;
            start)   shift 2; code_start "${'$'}@" ;;
            stop)    code_stop ;;
            restart) shift 2; code_stop; code_start "${'$'}@" ;;
            status)  if [ "${'$'}{3:-}" = "--json" ]; then code_status_json; else code_status; fi ;;
            logs)    shift 2; code_logs "${'$'}@" ;;
            *) err "usage: noxs code install|start|stop|restart|status [--json]|logs"; exit 2 ;;
        esac
        ;;
    storage)
        shift
        storage_command "${'$'}@"
        ;;
    docker)
        case "${'$'}{2:-status}" in
            install) docker_install ;;
            start)   docker_start ;;
            stop)    docker_stop ;;
            restart) docker_stop; docker_start ;;
            status)  docker_status ;;
            info)    docker_info ;;
            test)    docker_test ;;
            *) err "usage: noxs docker install|start|stop|restart|status|info|test"; exit 2 ;;
        esac
        ;;
    info) do_info ;;
    help|--help|-h)
        sed -n '2,11p' "${'$'}0"
        ;;
    *)
        # Forward legacy aliases so 'noxs-service start ssh' works as 'noxs service'
        err "unknown command: ${'$'}1 (try: noxs help)"
        exit 2
        ;;
esac
"""

    val STORAGE_SETUP = """#!/bin/sh
# Noxs SAF storage setup shortcut. Android presents the actual folder picker.
exec noxs storage setup
"""
}
