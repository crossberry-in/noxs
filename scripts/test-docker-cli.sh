#!/bin/bash
# test-docker-cli.sh — sandboxed functional tests for `nx docker
# doctor|status|repair` (docker-lib.sh).
#
# Everything is fixture-based and offline:
#   - NX_DOCKER_PREFIX points the guest-filesystem probes at a fixture tree
#   - docker/sudo/getent/curl/dpkg/apt-get are PATH stubs with controllable
#     behaviour (daemon up/down, DNS ok/fail, registry reachable/blocked)
#   - no real Docker, network or Android runtime is involved
#
# Environments that forbid symlink creation (some hardened CI sandboxes do)
# run the suite in fallback mode: fixture link states are approximated with
# real files/directories and symlink-restore assertions are skipped
# HONESTLY (marked SKIP) — GitHub CI runners exercise the full path.
#
# Run: bash scripts/test-docker-cli.sh     (also wired into scripts/test.sh)
set -u

PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0
failures=()

test_case() { echo "  CASE $1"; }

assert() { # assert DESCRIPTION CONDITION...
    local desc="$1"; shift
    if "$@"; then
        PASS_COUNT=$((PASS_COUNT + 1))
    else
        FAIL_COUNT=$((FAIL_COUNT + 1))
        failures+=("$desc")
        echo "    FAIL: $desc"
    fi
}

skip() {
    SKIP_COUNT=$((SKIP_COUNT + 1))
    echo "    SKIP: $1"
}

assert_contains() { # assert_contains DESCRIPTION HAYSTACK NEEDLE
    local desc="$1" haystack="$2" needle="$3"
    if printf '%s' "$haystack" | grep -qF -- "$needle"; then
        PASS_COUNT=$((PASS_COUNT + 1))
    else
        FAIL_COUNT=$((FAIL_COUNT + 1))
        failures+=("$desc (missing: $needle)")
        echo "    FAIL: $desc (missing: $needle)"
    fi
}

assert_not_contains() { # assert_not_contains DESCRIPTION HAYSTACK NEEDLE
    local desc="$1" haystack="$2" needle="$3"
    if printf '%s' "$haystack" | grep -qF -- "$needle"; then
        FAIL_COUNT=$((FAIL_COUNT + 1))
        failures+=("$desc (unexpected: $needle)")
        echo "    FAIL: $desc (unexpected: $needle)"
    else
        PASS_COUNT=$((PASS_COUNT + 1))
    fi
}

# --- symlink capability probe (some sandboxes forbid ln -s) ----------------
SYMLINK_PROBE="$(mktemp -d)/probe"
if ln -s probe-target "$SYMLINK_PROBE" 2>/dev/null; then
    SYMLINKS=1
else
    SYMLINKS=0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB="$REPO_ROOT/linux-runtime/nx/docker-lib.sh"

# --------------------------------------------------------- stubs
mkdir -p "$WORK/stub"
cat > "$WORK/stub/sudo" <<'STUB'
#!/bin/bash
exec "$@"
STUB
cat > "$WORK/stub/docker" <<'STUB'
#!/bin/bash
# controllable docker stub: NX_DOCKER_STUB_INFO_RC decides `docker info`
case "$1" in
    --version) echo "Docker version 27.3.1, build stubbed"; exit 0 ;;
    info)
        if [ "${NX_DOCKER_STUB_INFO_RC:-1}" = "0" ]; then
            echo " Storage Driver: ${NX_DOCKER_STUB_DRIVER:-overlay2}"
            exit 0
        fi
        echo "Cannot connect to the Docker daemon at unix:///var/run/docker.sock" >&2
        exit "${NX_DOCKER_STUB_INFO_RC:-1}"
        ;;
    buildx)
        case "$2" in
            version) if [ "${NX_DOCKER_STUB_BUILDX:-1}" = "0" ]; then echo "buildx 0.19.0 (stubbed)"; exit 0; else exit 1; fi ;;
            ls) echo "default   docker   default   default   running"; exit 0 ;;
        esac
        exit 1 ;;
esac
exit 1
STUB
cat > "$WORK/stub/getent" <<'STUB'
#!/bin/bash
# controllable resolver stub: NX_STUB_DNS_OK=1 resolves everything
if [ "${NX_STUB_DNS_OK:-1}" = "1" ]; then
    echo "203.0.113.10  $2"
    exit 0
fi
exit 2
STUB
cat > "$WORK/stub/curl" <<'STUB'
#!/bin/bash
# controllable registry stub: NX_STUB_REGISTRY_OK
if [ "${NX_STUB_REGISTRY_OK:-1}" = "1" ]; then
    echo "${NX_STUB_REGISTRY_CODE:-401}"
else
    echo "000"
fi
exit 0
STUB
cat > "$WORK/stub/dpkg" <<'STUB'
#!/bin/bash
# audit prints nothing = consistent database
exit 0
STUB
cat > "$WORK/stub/apt-get" <<'STUB'
#!/bin/bash
# reinstall stub: recreates /usr/bin/clear in the fixture prefix
if printf '%s' "$*" | grep -q ncurses-bin; then
    if [ -n "${NX_DOCKER_PREFIX:-}" ]; then
        mkdir -p "$NX_DOCKER_PREFIX/usr/bin"
        printf '#!/bin/sh\n' > "$NX_DOCKER_PREFIX/usr/bin/clear"
        chmod +x "$NX_DOCKER_PREFIX/usr/bin/clear"
    fi
    exit 0
fi
exit 1
STUB
chmod +x "$WORK/stub"/*
export PATH="$WORK/stub:$PATH"

# fixture builders -----------------------------------------------------------
# build_healthy_rootfs DIR — with symlinks: real Ubuntu usr-merge shape;
# without: real files/dirs that still satisfy the doctor's healthy checks.
build_healthy_rootfs() {
    local d="$1"
    mkdir -p "$d/usr/bin" "$d/usr/local/bin" "$d/usr/lib/aarch64-linux-gnu" "$d/usr/lib64" "$d/var/run"
    printf '#!/bin/sh\nclear\n' > "$d/usr/bin/clear"; chmod +x "$d/usr/bin/clear"
    printf 'dash binary\n' > "$d/usr/bin/dash"
    printf 'bash binary\n' > "$d/usr/bin/bash"
    printf 'loader\n' > "$d/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
    if [ "$SYMLINKS" = "1" ]; then
        ln -s dash "$d/usr/bin/sh"
        ln -s usr/bin "$d/bin"
        ln -s usr/lib64 "$d/lib64"
        ln -s aarch64-linux-gnu/ld-linux-aarch64.so.1 "$d/usr/lib/ld-linux-aarch64.so.1"
    else
        cp "$d/usr/bin/dash" "$d/usr/bin/sh"; chmod +x "$d/usr/bin/sh"
        mkdir -p "$d/bin" "$d/lib64"
        printf 'loader\n' > "$d/lib64/ld-linux-aarch64.so.1"
        printf 'loader\n' > "$d/usr/lib/ld-linux-aarch64.so.1"
    fi
    printf '#!/bin/sh\nexec "$@"\n' > "$d/usr/local/bin/sudo"; chmod +x "$d/usr/local/bin/sudo"
}

# build_broken_rootfs DIR — the historical poisons, no symlinks required:
# 0-byte placeholder at /bin, truncated clear, missing sh + loader links.
build_broken_rootfs() {
    local d="$1"
    mkdir -p "$d/usr/bin" "$d/usr/local/bin" "$d/usr/lib/aarch64-linux-gnu"
    printf 'dash binary\n' > "$d/usr/bin/dash"
    printf 'bash binary\n' > "$d/usr/bin/bash"
    printf 'loader\n' > "$d/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
    : > "$d/bin"
    : > "$d/usr/bin/clear"
}

# nx_msg / nx_err / stage are provided by the nx launcher in production;
# the standalone lib test defines the same contract (invoked indirectly by
# the sourced docker-lib.sh — invisible to per-file shellcheck).
# shellcheck disable=SC2329
nx_msg() { printf 'nx: %s\n' "$*"; }
# shellcheck disable=SC2329
nx_err() { printf 'nx: %s\n' "$*" >&2; }
# shellcheck disable=SC2329
stage() { :; }

# lib loading helper: fresh bash per case so source-time state is isolated
run_lib() { # run_lib <fixture-prefix> <function call...>
    local prefix="$1"; shift
    (
        export NX_DOCKER_PREFIX="$prefix"
        export NX_DOCKER_STATE="$WORK/docker-state"
        export NX_DOCKER_LOG="$WORK/dockerd.log"
        export NX_DOCKER_SOCK="$WORK/docker.sock"
        export NX_DOCKER_PROBE_TIMEOUT="2"
        # shellcheck source=/dev/null
        . "$LIB"
        "$@"
    )
}

# install_fixture_docker DIR — copy the controllable docker stub into a
# fixture as /usr/bin/docker (the real guest location the probes check).
install_fixture_docker() {
    mkdir -p "$1/usr/bin"
    cp "$WORK/stub/docker" "$1/usr/bin/docker"
    chmod +x "$1/usr/bin/docker"
}

echo "== nx docker doctor =="
test_case "healthy rootfs, no docker installed -> doctor exits 0"
build_healthy_rootfs "$WORK/healthy"
OUT=$(run_lib "$WORK/healthy" nx_docker_doctor_cmd 2>&1); RC=$?
assert "healthy doctor exit 0" test "$RC" -eq 0
assert_contains "healthy doctor clears clear row" "$OUT" "PASS  Rootfs /usr/bin/clear"
assert_contains "healthy doctor sudo row" "$OUT" "PASS  Rootfs /usr/local/bin/sudo"
assert_contains "docker-missing is a WARN, not a FAIL" "$OUT" "WARN  Docker CLI not installed"
assert_not_contains "no FAIL rows when healthy+no docker" "$OUT" "FAIL "

test_case "broken rootfs -> doctor exits 1 with FAIL rows"
build_broken_rootfs "$WORK/broken"
OUT=$(run_lib "$WORK/broken" nx_docker_doctor_cmd 2>&1); RC=$?
assert "broken doctor exit 1" test "$RC" -eq 1
assert_contains "clear FAIL" "$OUT" "FAIL  Rootfs /usr/bin/clear missing"
assert_contains "clear apt hint" "$OUT" "apt-get install --reinstall ncurses-bin"
assert_contains "/bin placeholder FAIL" "$OUT" "FAIL  Rootfs /bin link broken"
assert_contains "sudo shim FAIL" "$OUT" "FAIL  Rootfs /usr/local/bin/sudo missing"
assert_contains "sudo app-repair hint" "$OUT" "Diagnostics -> Rootfs repair"

test_case "daemon down with iptables log -> honest limitation WARN"
build_healthy_rootfs "$WORK/daemon"
cat > "$WORK/daemon/usr/bin/docker" <<'STUB'
#!/bin/bash
case "$1" in
    --version) echo "Docker version 27.3.1, build stubbed"; exit 0 ;;
    info) echo "Cannot connect to the Docker daemon at unix:///var/run/docker.sock" >&2; exit 1 ;;
esac
exit 1
STUB
chmod +x "$WORK/daemon/usr/bin/docker"
printf 'failed to initialize nft: permission denied\n' > "$WORK/dockerd.log"
OUT=$(run_lib "$WORK/daemon" nx_docker_doctor_cmd 2>&1); RC=$?
assert "daemon-down doctor exit still 0 (rootfs+dns healthy)" test "$RC" -eq 0
assert_contains "networking limitation wording" "$OUT" "networking restriction"
assert_contains "socket absent row" "$OUT" "Docker socket absent"

echo "== nx docker status =="
test_case "status without docker -> exit 3"
OUT=$(run_lib "$WORK/healthy" nx_docker_status_cmd 2>&1); RC=$?
assert "status no-docker exit 3" test "$RC" -eq 3
assert_contains "status not installed line" "$OUT" "Docker CLI: not installed"

test_case "status with running daemon -> exit 0 with storage + mode"
build_healthy_rootfs "$WORK/statusrun"
install_fixture_docker "$WORK/statusrun"
export NX_DOCKER_STUB_INFO_RC=0 NX_DOCKER_STUB_DRIVER=overlay2 NX_STUB_DNS_OK=1 NX_STUB_REGISTRY_OK=1
printf 'state=ready\nmode=compatibility\ncontainers=supported\n' > "$WORK/docker-state"
OUT=$(run_lib "$WORK/statusrun" nx_docker_status_cmd 2>&1); RC=$?
assert "status running exit 0" test "$RC" -eq 0
assert_contains "status daemon running" "$OUT" "Docker daemon: running"
assert_contains "status storage driver" "$OUT" "Storage: overlay2"
assert_contains "status compatibility mode" "$OUT" "Mode: compatibility"
unset NX_DOCKER_STUB_INFO_RC NX_DOCKER_STUB_DRIVER

echo "== nx docker repair =="
test_case "repair --check is a dry run: reports, exits 1, changes nothing"
build_broken_rootfs "$WORK/repair"
BEFORE=$(cd "$WORK/repair" && find . | sort | xargs stat -c '%n %s' 2>/dev/null)
OUT=$(run_lib "$WORK/repair" nx_docker_repair_cmd --check 2>&1); RC=$?
assert "repair --check exit 1 (repairs available)" test "$RC" -eq 1
assert_contains "dry-run banner" "$OUT" "dry run — nothing was changed"
assert_contains "bin repair listed" "$OUT" "restore /bin -> usr/bin"
assert_contains "clear reinstall listed" "$OUT" "reinstall ncurses-bin"
AFTER=$(cd "$WORK/repair" && find . | sort | xargs stat -c '%n %s' 2>/dev/null)
assert "repair --check modified nothing" test "$BEFORE" = "$AFTER"

test_case "repair --yes applies the structural fixes"
OUT=$(run_lib "$WORK/repair" nx_docker_repair_cmd --yes 2>&1); RC=$?
assert_contains "links restored message" "$OUT" "FHS/loader links checked and restored"
assert "clear restored via apt stub" test -x "$WORK/repair/usr/bin/clear"
assert_contains "storage untouched note" "$OUT" "Docker storage was not modified"
if [ "$SYMLINKS" = "1" ]; then
    assert "/bin restored as symlink" test -L "$WORK/repair/bin"
    assert "/usr/bin/sh restored" test -L "$WORK/repair/usr/bin/sh"
    OUT=$(run_lib "$WORK/repair" nx_docker_repair_cmd --check 2>&1); RC=$?
    assert "post-repair check exits 0" test "$RC" -eq 0
    assert_contains "nothing to repair message" "$OUT" "Nothing to repair"
else
    skip "symlink restore assertions (environment forbids ln -s)"
    assert "0-byte /bin placeholder removed" test ! -e "$WORK/repair/bin"
fi

test_case "repair refuses non-interactive run without --yes"
build_broken_rootfs "$WORK/refuse"
OUT=$(run_lib "$WORK/refuse" nx_docker_repair_cmd 2>&1 </dev/null); RC=$?
assert "refusal exit 1" test "$RC" -eq 1
assert_contains "refusal message" "$OUT" "Refusing to repair without --yes"
assert "0-byte /bin placeholder still present (not repaired)" test -f "$WORK/refuse/bin"

echo "== nx docker doctor --json =="
test_case "--json emits exactly one parseable JSON object on stdout"
build_healthy_rootfs "$WORK/json"
install_fixture_docker "$WORK/json"
export NX_DOCKER_STUB_INFO_RC=0 NX_STUB_DNS_OK=1 NX_STUB_REGISTRY_OK=1
JSON=$(run_lib "$WORK/json" nx_docker_doctor_cmd --json 2>/dev/null); RC=$?
assert "doctor --json exit 0" test "$RC" -eq 0
LINES=$(printf '%s\n' "$JSON" | wc -l)
assert "json is single line" test "$LINES" -eq 1
PARSE_OK=$(printf '%s' "$JSON" | python3 -c 'import json,sys; d=json.load(sys.stdin); print("ok" if all(k in d for k in ("rootfs","cli","daemon","mode","storage","dns","registry","buildkit","kernel","arch")) else "missing-keys")')
assert "json parses with all keys" test "$PARSE_OK" = "ok"
assert_contains "json daemon running" "$JSON" '"daemon":"running"'
assert_contains "json storage overlay2" "$JSON" '"storage":"overlay2"'

test_case "--json with broken rootfs -> rootfs=broken, exit 1"
build_broken_rootfs "$WORK/json2"
export NX_STUB_DNS_OK=0 NX_STUB_REGISTRY_OK=0
OUT=$(run_lib "$WORK/json2" nx_docker_doctor_cmd --json 2>/dev/null); RC=$?
assert "broken json exit 1" test "$RC" -eq 1
assert_contains "json rootfs broken" "$OUT" '"rootfs":"broken"'
assert_contains "json dns fail" "$OUT" '"dns":"fail"'
assert_contains "json registry unreachable" "$OUT" '"registry":"unreachable"'
unset NX_DOCKER_STUB_INFO_RC NX_STUB_DNS_OK NX_STUB_REGISTRY_OK

echo "== dispatch =="
test_case "unknown subcommand exits 2 with usage"
OUT=$(run_lib "$WORK/healthy" nx_docker_cmd frobnicate 2>&1); RC=$?
assert "unknown subcommand exit 2" test "$RC" -eq 2
assert_contains "usage shown" "$OUT" "Usage: nx docker"

test_case "nx usage advertises the docker diagnostics"
CLI="$REPO_ROOT/linux-runtime/launcher/nx"
assert_contains "nx usage has doctor" "$(cat "$CLI")" "nx docker doctor [--json]"
assert_contains "nx usage has repair" "$(cat "$CLI")" "nx docker repair [--check]"

echo
if [ "$FAIL_COUNT" -eq 0 ]; then
    echo "[noxs] docker CLI tests: $PASS_COUNT passed, 0 failed" \
        "$([ "$SKIP_COUNT" -gt 0 ] && echo "($SKIP_COUNT skipped: no symlink support)")"
    exit 0
fi
echo "[noxs] docker CLI tests: $PASS_COUNT passed, $FAIL_COUNT FAILED" >&2
for f in "${failures[@]}"; do echo "  - $f" >&2; done
exit 1
