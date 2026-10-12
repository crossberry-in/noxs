#!/usr/bin/env bash
# shellcheck disable=SC2034
# scripts/test-code-cli.sh — functional tests for the `noxs code` lifecycle.
# Fully sandboxed: every path is env-overridden and code-server itself is a
# stub (a python http.server), so no real download or install happens here
# except the tiny network-failure test at the end.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="$ROOT/linux-runtime/launcher/noxs-cli"
NX="$ROOT/linux-runtime/launcher/nx"
RC=0

fail() { echo "FAILED: $*" >&2; RC=1; }
ok()   { echo "  ok: $*"; }

[ -f "$CLI" ] || { echo "noxs-cli not found at $CLI"; exit 1; }
if ! command -v python3 >/dev/null 2>&1; then
    echo "python3 is required for the code CLI tests"
    exit 1
fi

SBX="$(mktemp -d)"
cleanup() {
    # stub servers announce themselves as "code-server -m http.server ..."
    pkill -f '^code-server -m http.server' 2>/dev/null
    kill $(jobs -p) 2>/dev/null
    rm -rf "$SBX"
}
trap cleanup EXIT
mkdir -p "$SBX/run/host" "$SBX/log" "$SBX/home" "$SBX/opt" "$SBX/tmp" "$SBX/bin"

# The CLI reads all paths from env (env-overridable by design).
cli() {
    NOXS_RUN_DIR="$SBX/run" CODE_DIR="$SBX/opt/code-server" \
    CODE_DATA="$SBX/home/.noxs/code" NOXS_LOG_DIR="$SBX/log" \
    CODE_TMP="$SBX/tmp" HOME="$SBX/home" \
    bash "$CLI" code "$@"
}

# A stub code-server: serves plain HTTP on the configured loopback port so
# the CLI's readiness probe behaves like the real thing. The process keeps
# the "code-server" identity (exec -a) exactly like the real launcher does,
# so the CLI's PID-identity check accepts it.
fake_code_server() {
    mkdir -p "$SBX/opt/code-server/bin"
    cat > "$SBX/opt/code-server/bin/code-server" << 'STUB'
#!/usr/bin/env bash
conf=""
prev=""
for a in "$@"; do
    [ "$prev" = "--config" ] && conf="$a"
    prev="$a"
done
port="$(sed -n 's/^bind-addr: 127\.0\.0\.1://p' "$conf" 2>/dev/null)"
[ -n "$port" ] || port=9999
exec -a code-server python3 -m http.server "$port" --bind 127.0.0.1
STUB
    chmod +x "$SBX/opt/code-server/bin/code-server"
    printf '9.9.9\n' > "$SBX/opt/code-server/version"
}

json() { cli status --json; }
assert_field() { # value json-fragment
    echo "$2" | grep -q "\"$1\":$3" || { fail "$1 expected $3, got: $2"; return 1; }
    return 0
}

echo "=== noxs code: status (not installed)"
out="$(json)"
assert_field installed "$out" false || :
assert_field running "$out" false || :
ok "status --json reports not installed"

echo "=== noxs code: status (installed, stopped)"
fake_code_server
out="$(json)"
assert_field installed "$out" true || :
assert_field running "$out" false || :
assert_field http_ready "$out" false || :
ok "status --json reports installed, stopped"

echo "=== noxs code: start generates a locked config with auth"
mkdir -p "$SBX/home/ws"
out="$(cli start "$SBX/home/ws")" || fail "start failed: $out"
conf="$SBX/home/.noxs/code/config.yaml"
[ -f "$conf" ] || fail "config file missing"
grep -q '^auth: password' "$conf" || fail "auth must be password"
grep -q '^bind-addr: 127\.0\.0\.1:' "$conf" || fail "bind-addr must be loopback"
grep -q '^password: ' "$conf" || fail "password missing"
perm="$(stat -c %a "$conf" 2>/dev/null)"
[ "$perm" = "600" ] || fail "config must be mode 600, got: $perm"
ok "config generated (loopback bind, auth: password, mode 600)"

echo "=== noxs code: status reports running + http_ready"
out="$(json)"
assert_field running "$out" true || :
assert_field http_ready "$out" true || :
assert_field port "$out" 8080 || :
ok "server is running and answering on the loopback"

echo "=== noxs code: duplicate start is refused (reuses the server)"
out="$(cli start "$SBX/home/ws")" || fail "second start errored: $out"
echo "$out" | grep -q 'already running' || fail "expected already-running notice"
out2="$(json)"
assert_field port "$out2" 8080 || :
ok "duplicate start prevented"

echo "=== noxs code: stale pid metadata is cleaned up"
pid="$(json | sed -n 's/.*"pid":\([0-9]*\).*/\1/p')"
kill "$pid" 2>/dev/null
sleep 1
out="$(json)"
assert_field running "$out" false || :
[ ! -f "$SBX/run/host/code-server.pid" ] || fail "stale pid file not removed"
ok "crashed server detected via stale pid; metadata cleaned"

echo "=== noxs code: pid reuse is rejected (foreign process)"
sleep 30 &
foreign=$!
echo "$foreign" > "$SBX/run/host/code-server.pid"
out="$(json)"
assert_field running "$out" false || :
kill "$foreign" 2>/dev/null
wait "$foreign" 2>/dev/null
ok "pid pointing at a non-code-server process is not trusted"

echo "=== noxs code: port conflict moves the server to a free port"
if [ -f /proc/net/tcp ]; then
    python3 -m http.server 8080 --bind 127.0.0.1 >/dev/null 2>&1 &
    occupier=$!
    sleep 1
    out="$(cli start "$SBX/home/ws")" || fail "start with conflict failed: $out"
    grep -q 'moved to 127.0.0.1:8081' <<< "$out" || fail "expected port move, got: $out"
    out="$(json)"
    assert_field port "$out" 8081 || :
    kill "$occupier" 2>/dev/null
    wait "$occupier" 2>/dev/null
    cli stop > /dev/null
    ok "port conflict detected; server moved to 8081"
else
    echo "  skipped (no /proc/net/tcp)"
fi

echo "=== noxs code: restart keeps the server healthy"
cli start "$SBX/home/ws" > /dev/null || fail "start failed"
cli restart "$SBX/home/ws" > /dev/null || fail "restart failed"
out="$(json)"
assert_field running "$out" true || :
assert_field http_ready "$out" true || :
ok "restart produced a ready server"

echo "=== noxs code: stop terminates the real server process"
pid="$(json | sed -n 's/.*"pid":\([0-9]*\).*/\1/p')"
cli stop > /dev/null || fail "stop failed"
out="$(json)"
assert_field running "$out" false || :
kill -0 "$pid" 2>/dev/null && fail "server process $pid still alive after stop"
ok "stop terminated the server and cleared the pid file"

echo "=== noxs code: start validates the workspace"
cli start /definitely/not/a/dir > /dev/null 2>&1 && fail "invalid workspace accepted"
ok "nonexistent workspace rejected"

echo "=== noxs code: logs keep credentials out of the output"
printf 'INFO starting\npassword: hunter2-secret\nINFO ready\n' > "$SBX/log/code-server.log"
out="$(cli logs 5)"
echo "$out" | grep -q 'INFO ready' || fail "expected non-credential lines in logs"
echo "$out" | grep -q 'hunter2-secret' && fail "password leaked through noxs code logs"
ok "log output filtered"

echo "=== noxs code: install validates its arguments offline"
cli install --bogus-flag > /dev/null 2>&1 && fail "bogus install flag accepted"
out="$(cli install --version 999.0.0 2>&1)" && fail "bogus version should fail to download"
echo "$out" | grep -qiE 'download failed|checksum|not look like' || fail "unclear install error: $out"
ok "install argument + network failure paths produce clear errors"

echo "=== nx code: aliases onto noxs (existing alias architecture)"
printf '#!/bin/sh\nexec bash "%s" "$@"\n' "$CLI" > "$SBX/bin/noxs"
chmod +x "$SBX/bin/noxs"
out="$(NOXS_RUN_DIR="$SBX/run" CODE_DIR="$SBX/opt/code-server" \
    CODE_DATA="$SBX/home/.noxs/code" NOXS_LOG_DIR="$SBX/log" \
    CODE_TMP="$SBX/tmp" HOME="$SBX/home" PATH="$SBX/bin:$PATH" \
    bash "$NX" code status --json)" || fail "nx code failed: $out"
echo "$out" | grep -q '"installed":true' || fail "nx code output unexpected: $out"
ok "nx code status --json works through the noxs alias"

if [ "$RC" -eq 0 ]; then
    echo "code CLI tests: ALL PASSED"
else
    echo "code CLI tests: FAILURES (see above)"
fi
exit "$RC"
