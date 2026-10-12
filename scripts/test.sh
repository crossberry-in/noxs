#!/usr/bin/env bash
# shellcheck disable=SC1090,SC1091,SC2012,SC2034,SC2086,SC2164,SC2295
# scripts/test.sh — Noxs validation suite (original project tooling).
#   --unit          JVM unit tests (checksums, tar-guard, emulator, launcher)
#   --manifests     validate bootstrap manifests + schema shape
#   --sync-check    diff canonical linux-runtime files vs embedded app copies
#   --scripts       shellcheck + bash -n over all shell tooling
#   --nx            functional tests for the NX package system scripts
#   --ai            offline functional tests for the Noxs AI Agent runtime
#   --code          sandboxed functional tests for the `noxs code` lifecycle
#   --all           everything (default)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
MODE="${1:---all}"
RC=0

step() { echo; echo "=== $1 ==="; }

fail() { echo "FAILED: $*" >&2; RC=1; }

# ---- unit -----------------------------------------------------------------
if [ "$MODE" = "--all" ] || [ "$MODE" = "--unit" ]; then
    step "JVM unit tests"
    ./gradlew -q noxs-shared:test terminal-emulator:test app:testDebugUnitTest || fail "unit tests"
fi

# ---- manifests ------------------------------------------------------------
if [ "$MODE" = "--all" ] || [ "$MODE" = "--manifests" ]; then
    step "bootstrap manifests"
    if command -v python3 >/dev/null 2>&1; then
        python3 - <<'PY' || fail "manifest validation"
import json, pathlib, sys
root = pathlib.Path('.')
ok = True
for p in sorted(root.glob('app/src/main/assets/bootstrap/*/bootstrap.manifest')):
    try:
        m = json.loads(p.read_text())
        assert m['schema'] == 1, 'schema'
        assert m['arch'] in ('arm64-v8a', 'armeabi-v7a', 'x86_64'), 'arch'
        for key in ('rootfs', 'proot'):
            if key not in m:
                # proot is optional since it ships in the APK as jniLibs
                if key == 'proot':
                    continue
                assert False, f'{key} missing'
            assert m[key]['url'].startswith('https://'), f'{key}.url must be https'
            sha = m[key].get('sha256', '')
            assert sha == '' or (len(sha) == 64 and all(c in '0123456789abcdef' for c in sha)), f'{key}.sha256'
        print(f"  OK {p}")
    except Exception as e:
        print(f"  BAD {p}: {e}"); ok = False
sys.exit(0 if ok else 1)
PY
    else
        echo "  python3 not found — skipped (CI includes it)"
    fi
fi

# ---- sync check (canonical vs embedded copies) ----------------------------
if [ "$MODE" = "--all" ] || [ "$MODE" = "--sync-check" ]; then
    step "canonical ↔ embedded sync"
    diff -u linux-runtime/bootstrap/arm64-v8a/bootstrap.manifest \
            app/src/main/assets/bootstrap/arm64-v8a/bootstrap.manifest || fail "manifest copy differs"
    diff -u linux-runtime/rootfs/overlay/etc/profile.d/noxs.sh \
            <(sed -n '/NOXS_PROFILE = \"\"\"/,/\"\"\".trimIndent()/p' \
                app/src/main/java/com/crossberry/noxs/runtime/RootfsConfigurator.kt \
              | sed '1d;$d') >/dev/null 2>&1 \
        || echo "  note: profile.sh is generated with template escapes; visual diff only"
    # The noxs CLI must match its Kotlin template (after un-escaping ${'$'})
    python3 - <<'PY' || fail "noxs CLI copy differs"
import pathlib, re, sys
kt = pathlib.Path('app/src/main/java/com/crossberry/noxs/runtime/NoxsCliTemplate.kt').read_text()
m = re.search(r'val CLI = """\n?(.*?)"""', kt, re.S)
if not m:
    print('  template markers not found'); sys.exit(1)
embedded = m.group(1).replace("${'$'}", "$")
canonical = pathlib.Path('linux-runtime/launcher/noxs-cli').read_text()
m2 = re.search(r'val STORAGE_SETUP = """\n?(.*?)"""', kt, re.S)
if not m2:
    print('  storage setup template markers not found'); sys.exit(1)
storage_setup = m2.group(1).replace("${'$'}", "$")
canonical_setup = pathlib.Path('linux-runtime/launcher/noxs-setup-storage').read_text()
if embedded != canonical or storage_setup != canonical_setup:
    print('  DIFF: run scripts/sync-cli.sh to regenerate the canonical copies')
    sys.exit(1)
print('  OK noxs-cli and noxs-setup-storage match their templates')
PY
    # NX package system: every mirror must match its Kotlin template
    python3 scripts/sync_nx.py --check || fail "nx mirror sync"
fi

# ---- shell tooling --------------------------------------------------------
if [ "$MODE" = "--all" ] || [ "$MODE" = "--scripts" ]; then
    step "shell checks"
    SHELLS="$(ls scripts/*.sh linux-runtime/launcher/noxs-launch.sh linux-runtime/launcher/noxs-cli linux-runtime/launcher/noxs-setup-storage linux-runtime/launcher/nx linux-runtime/nx/*.sh linux-runtime/service-manager/noxs-service linux-runtime/socket-manager/noxs-socket linux-runtime/process-manager/noxs-ps nx-installer/*.sh 2>/dev/null)"
    for f in $SHELLS; do
        bash -n "$f" || fail "syntax: $f"
    done
    echo "  bash -n OK for all shell files"
    if command -v shellcheck >/dev/null 2>&1; then
        shellcheck -x $SHELLS || fail "shellcheck"
    else
        echo "  shellcheck not installed — skipped locally (CI runs it)"
    fi
fi

# ---- nx package system ----------------------------------------------------
if [ "$MODE" = "--all" ] || [ "$MODE" = "--nx" ]; then
    step "nx package system functional tests"
    bash scripts/test_nx_functions.sh || fail "nx functional tests"
    step "nx vpn (Tor over PRoot) offline tests"
    bash scripts/test_nx_vpn.sh || fail "nx vpn tests"
fi

# ---- ai agent -------------------------------------------------------------
if [ "$MODE" = "--all" ] || [ "$MODE" = "--ai" ]; then
    step "Noxs AI Agent functional tests (offline, fake provider)"
    if command -v python3 >/dev/null 2>&1; then
        python3 scripts/test_nx_ai.py || fail "ai functional tests"
    else
        fail "python3 is required for the AI agent tests"
    fi
fi

# ---- code-server CLI ------------------------------------------------------
if [ "$MODE" = "--all" ] || [ "$MODE" = "--code" ]; then
    step "code-server CLI lifecycle tests (sandboxed)"
    if command -v python3 >/dev/null 2>&1; then
        bash scripts/test-code-cli.sh || fail "code CLI tests"
    else
        fail "python3 is required for the code CLI tests"
    fi
fi

echo
if [ "$RC" -eq 0 ]; then
    echo "[noxs] all validations passed ✔"
else
    echo "[noxs] VALIDATION FAILURES — see above" >&2
fi
exit "$RC"
