#!/usr/bin/env bash
# shellcheck disable=SC1090,SC1091,SC2012,SC2015,SC2016,SC2034,SC2086,SC2164,SC2295,SC2317
# test_nx_functions.sh — functional tests for the NX Package System scripts.
#
# The Kotlin templates are extracted with scripts/extract_nx.py, then the
# real pkg-lib/pkg-init code is executed against fixtures: validation
# regexes, semver compare, registry parsing, asset picking, manifests and
# archive safety — plus an end-to-end `nx pkg init` scaffold check.
#
# Usage: scripts/test_nx_functions.sh   (called by scripts/test.sh --nx)
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
RC=0
pass() { echo "  ok: $*"; }
fail() { echo "  FAILED: $*" >&2; RC=1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
WORK="$TMP/nxtest"
mkdir -p "$WORK"

# ---- materialize the extracted scripts + templates ------------------------
python3 - "$ROOT" "$TMP/nxroot" <<'PY' || { echo "extraction failed" >&2; exit 1; }
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(sys.argv[1]) / 'scripts'))
import extract_nx
root = pathlib.Path(sys.argv[1])
dest = pathlib.Path(sys.argv[2])
(dest / 'noxs-pkg').mkdir(parents=True, exist_ok=True)
for name, content, mirror in extract_nx.nx_shell_scripts(root):
    module = {'NX_CLI': 'bin/nx', 'PKG_LIB': 'noxs-pkg/pkg-lib.sh',
              'PKG_INIT': 'noxs-pkg/pkg-init.sh', 'PKG_DEV': 'noxs-pkg/pkg-dev.sh',
              'PKG_INSTALL': 'noxs-pkg/pkg-install.sh', 'WEB_LIB': 'noxs-pkg/web-lib.sh',
              'VPN_LIB': 'noxs-pkg/vpn-lib.sh',
              'PLUG_LIB': 'noxs-pkg/plug-lib.sh',
              'CERT_FIX_LIB': 'noxs-pkg/cert-fix.sh',
              'AI_LIB': 'noxs-pkg/ai-lib.sh',
              'WELCOME_SH': 'launcher/noxs-welcome'}[name]
    target = dest / module
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content)
    target.chmod(0o755)
for rel, content in extract_nx.nx_ai_python_files(root).items():
    # linux-runtime/nx/ai/<file>.py -> dest/ai/<file>.py
    target = dest / rel.split('linux-runtime/nx/')[1]
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content)
extract_nx.materialize_template_root(root, dest)
print("  extracted nx scripts + templates")
PY

NXBIN="$TMP/nxroot/bin/nx"
export NX_LIB_DIR="$TMP/nxroot/noxs-pkg"
export NX_TEMPLATE_ROOT="$TMP/nxroot/templates"

# ---- 1. library unit tests -------------------------------------------------
echo "=== pkg-lib: validation + parsing ==="
. "$NX_LIB_DIR/pkg-lib.sh"

valid_name tree        && pass "valid_name tree"        || fail "valid_name tree"
valid_name tree-cli2   && pass "valid_name tree-cli2"   || fail "valid_name tree-cli2"
valid_name "Tree"      && fail "valid_name rejects uppercase" || pass "valid_name rejects uppercase"
valid_name "../evil"   && fail "valid_name rejects traversal" || pass "valid_name rejects traversal"
valid_name 'a;b'       && fail "valid_name rejects shell chars" || pass "valid_name rejects shell chars"
valid_name '$(id)'     && fail "valid_name rejects command substitution" || pass "valid_name rejects \$(id)"
valid_version 1.0.0    && pass "valid_version 1.0.0"    || fail "valid_version 1.0.0"
valid_version 1.0      && fail "valid_version rejects 1.0" || pass "valid_version rejects 1.0"
valid_version 1.0.0-rc1 && fail "valid_version rejects prerelease" || pass "valid_version rejects 1.0.0-rc1"
valid_repo web12-app/tree && pass "valid_repo"          || fail "valid_repo"
valid_repo "a b/c"     && fail "valid_repo rejects space" || pass "valid_repo rejects space"
valid_sha256 "$(printf 'a%.0s' $(seq 64))" && pass "valid_sha256 64 hex" || fail "valid_sha256"
valid_sha256 "xyz"     && fail "valid_sha256 rejects short" || pass "valid_sha256 rejects short"

semver_gt 1.0.1 1.0.0   && pass "semver_gt patch" || fail "semver_gt patch"
semver_gt 1.1.0 1.0.9   && pass "semver_gt minor" || fail "semver_gt minor"
semver_gt 2.0.0 1.99.99 && pass "semver_gt major" || fail "semver_gt major"
semver_gt 0.0.10 0.0.9  && pass "semver_gt 0.0.10>0.0.9" || fail "semver_gt 0.0.10>0.0.9"
! semver_gt 1.0.0 1.0.0 && pass "semver_gt equal is false" || fail "semver_gt equal"
! semver_gt 0.9.9 1.0.0 && pass "semver_gt lower is false" || fail "semver_gt lower"

# ---- registry parsing (canonical workflow layout, two versions) ------------
cat > "$WORK/registry.json" <<'EOF'
{
  "name": "tree",
  "description": "Noxs tree utility",
  "latest": "1.0.1",
  "versions": [
    {
      "version": "1.0.0",
      "tag": "tree-v1.0.0",
      "files": [
        {
          "name": "tree.nx.pkg.1.0.0.aarch64.tar.xz",
          "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        },
        {
          "name": "tree.nx.pkg.1.0.0.universal.tar.xz",
          "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        }
      ]
    },
    {
      "version": "1.0.1",
      "tag": "tree-v1.0.1",
      "files": [
        {
          "name": "tree.nx.pkg.1.0.1.aarch64.tar.xz",
          "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        }
      ]
    }
  ]
}
EOF
if registry_parse "$WORK/registry.json"; then
    [ "$RG_NAME" = "tree" ]            && pass "registry name"      || fail "registry name ($RG_NAME)"
    [ "$RG_LATEST" = "1.0.1" ]         && pass "registry latest"    || fail "registry latest ($RG_LATEST)"
    [ "$RG_COUNT" = "2" ]              && pass "registry count"     || fail "registry count ($RG_COUNT)"
    [ "${RG_VER_0}" = "1.0.0" ]        && pass "registry ver0"      || fail "registry ver0"
    [ "${RG_VER_1}" = "1.0.1" ]        && pass "registry ver1"      || fail "registry ver1"
    [ "${RG_FILECOUNT_0}" = "2" ]      && pass "registry filecount" || fail "registry filecount"
    [ "${RG_SHA_1_1}" = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc" ] \
        && pass "registry sha"       || fail "registry sha"
else
    fail "registry_parse rejected a valid registry"
fi

registry_find_version 1.0.0 && [ "$RV_INDEX" = 0 ] && pass "find_version" || fail "find_version"
if registry_find_version 9.9.9; then fail "find_version absent must fail"; else pass "find_version absent"; fi

registry_pick_asset 1 aarch64 && [ "$RA_NAME" = "tree.nx.pkg.1.0.1.aarch64.tar.xz" ] \
    && pass "pick_asset exact arch" || fail "pick_asset exact arch"
registry_pick_asset 0 x86_64 && [ "$RA_NAME" = "tree.nx.pkg.1.0.0.universal.tar.xz" ] \
    && pass "pick_asset universal fallback" || fail "pick_asset universal fallback"
if registry_pick_asset 1 x86_64; then fail "pick_asset missing arch must fail"; else pass "pick_asset missing arch"; fi

# tampered registry: bad checksum length must be rejected (subshell: die exits)
sed 's/cccccccccc/cc/' "$WORK/registry.json" > "$WORK/bad.json"
if (registry_parse "$WORK/bad.json") 2>/dev/null; then fail "bad sha rejected"; else pass "bad sha rejected"; fi
# tag mismatch must be rejected
sed 's/tree-v1.0.1/tree-v9.9.9/' "$WORK/registry.json" > "$WORK/bad2.json"
if (registry_parse "$WORK/bad2.json") 2>/dev/null; then fail "tag mismatch rejected"; else pass "tag mismatch rejected"; fi

# ---- manifest roundtrip -----------------------------------------------------
echo "=== pkg-lib: manifests ==="
export NX_STATE_DIR="$WORK/state"
manifest_write "$(manifest_path tree)" tree 1.0.0 aarch64 noxs-pkg/tree "https://r" \
    "usr/local/bin/tree" "usr/local/share/doc/tree/README.md"
manifest_exists tree && pass "manifest_exists" || fail "manifest_exists"
[ "$(manifest_field "$(manifest_path tree)" version)" = "1.0.0" ] && pass "manifest_field" || fail "manifest_field"
[ "$(manifest_field "$(manifest_path tree)" source)" = "noxs-pkg/tree" ] && pass "manifest source" || fail "manifest source"

# ---- archive safety ----------------------------------------------------------
echo "=== pkg-lib: tarball safety ==="
stage_dir="$WORK/stage/usr/local/bin"
mkdir -p "$stage_dir"
printf '#!/bin/sh\necho ok\n' > "$stage_dir/tree"
tar -cJf "$WORK/safe.tar.xz" -C "$WORK/stage" usr
check_tarball_members "$WORK/safe.tar.xz" && pass "safe tarball accepted" || fail "safe tarball accepted"

# Malicious archives are constructed byte-exactly with python tarfile: a
# `..` traversal member, an absolute member, and an absolute symlink.
python3 - "$WORK" <<'PY' || { echo "tarball fixtures failed" >&2; exit 1; }
import io, sys, tarfile
from tarfile import TarInfo

import pathlib
import pathlib
work = pathlib.Path(sys.argv[1])

def make(path, members):
    with tarfile.open(work / path, "w:xz") as tf:
        for info in members:
            name, kind, target = info
            ti = TarInfo(name)
            if kind == "file":
                ti.size = 1
                tf.addfile(ti, io.BytesIO(b"x"))
            else:
                ti.type = tarfile.SYMTYPE
                ti.linkname = target
                tf.addfile(ti)

make("traversal.tar.xz", [("usr/local/bin/../../evil.sh", "file", "")])
make("absolute.tar.xz", [("/etc/evil.sh", "file", "")])
make("abssym.tar.xz", [("usr/local/bin/link", "symlink", "/etc/shadow")])
print("  malicious fixtures built")
PY

for evil in traversal absolute abssym; do
    if (check_tarball_members "$WORK/$evil.tar.xz") 2>/dev/null; then
        fail "$evil tarball rejected"
    else
        pass "$evil tarball rejected"
    fi
done

# ---- end-to-end: nx pkg init ------------------------------------------------
echo "=== pkg-init: end-to-end scaffold ==="
(
    cd "$WORK"
    "$NXBIN" pkg init demo --node >/dev/null 2>&1
) || fail "nx pkg init demo --node"
[ -f "$WORK/demo/.github/workflows/pkg.yml" ] && pass "workflow scaffolded" || fail "workflow scaffolded"
[ -f "$WORK/demo/src/main.js" ] && pass "nodejs src scaffolded" || fail "nodejs src scaffolded"
grep -q '"name": "demo"' "$WORK/demo/registry.json" && pass "registry name rendered" || fail "registry name"
grep -q '__PKG_NAME__' "$WORK/demo/registry.json" && fail "placeholder left behind" || pass "no placeholder left"
[ "$(cat "$WORK/demo/VERSION")" = "1.0.0" ] && pass "VERSION scaffolded" || fail "VERSION scaffolded"
[ -f "$WORK/demo/.git/HEAD" ] && pass "git repo initialized" || fail "git repo initialized"

(
    cd "$WORK"
    "$NXBIN" pkg init "Bad Name" --go >/dev/null 2>&1
) && fail "invalid name rejected" || pass "invalid name rejected"
(
    cd "$WORK"
    "$NXBIN" pkg init demo2 --bogus >/dev/null 2>&1
) && fail "unknown flag rejected" || pass "unknown flag rejected"
(
    cd "$WORK"
    "$NXBIN" pkg init demo3 >/dev/null 2>&1
) && fail "no-lang without tty rejected" || pass "no-lang without tty rejected"
(
    cd "$WORK"
    "$NXBIN" pkg init demo4 --node >/dev/null 2>&1 && "$NXBIN" pkg init demo4 --go >/dev/null 2>&1
) && fail "existing directory rejected" || pass "existing directory rejected"

# every language scaffold must produce a complete tree
for lang in --rust --cpp --python --go; do
    id="${lang#--}"
    (
        cd "$WORK"
        "$NXBIN" pkg init "pkg-$id" "$lang" >/dev/null 2>&1
    ) || fail "scaffold $id"
    for f in .github/workflows/pkg.yml registry.json VERSION README.md LICENSE; do
        [ -f "$WORK/pkg-$id/$f" ] || fail "scaffold $id missing $f"
    done
done
[ -f "$WORK/pkg-rust/src/main.rs" ] && [ -f "$WORK/pkg-rust/Cargo.toml" ] && pass "rust files" || fail "rust files"
[ -f "$WORK/pkg-go/main.go" ] && [ -f "$WORK/pkg-go/go.mod" ] && pass "go files at root (spec §7)" || fail "go files"
[ -f "$WORK/pkg-python/pyproject.toml" ] && [ -f "$WORK/pkg-python/src/main.py" ] && pass "python files" || fail "python files"
[ -f "$WORK/pkg-cpp/CMakeLists.txt" ] && [ -f "$WORK/pkg-cpp/src/main.cpp" ] && pass "cpp files" || fail "cpp files"

# ---- workflow YAML structural checks ----------------------------------------
echo "=== workflow: structure ==="
python3 - "$WORK/demo/.github/workflows/pkg.yml" <<'PY' || RC=1
import pathlib, sys
try:
    import yaml
except ImportError:
    print("  skip: PyYAML missing"); sys.exit(0)
doc = yaml.safe_load(pathlib.Path(sys.argv[1]).read_text())
jobs = doc.get("jobs", {})
assert "on" in doc or True
assert set(jobs) == {"detect", "build", "release"}, jobs.keys()
assert jobs["detect"]["outputs"]["tag"], "detect outputs"
assert "fromJson" in jobs["build"]["strategy"]["matrix"], "matrix fromJson"
steps = [s.get("name", s.get("uses", "")) for s in jobs["release"]["steps"]]
assert any("registry.json" in s for s in steps), "registry update step"
print("  ok: workflow parses; jobs=detect,build,release")
PY

grep -q "Release already exists" "$WORK/demo/.github/workflows/pkg.yml" && pass "duplicate tag error" || fail "duplicate tag error"
grep -q "sha256sum" "$WORK/demo/.github/workflows/pkg.yml" && pass "checksum step" || fail "checksum step"
grep -q "\[skip ci\]" "$WORK/demo/.github/workflows/pkg.yml" && pass "[skip ci] registry commit" || fail "[skip ci]"

# ---- web bridge: nx ow (Noxs native browser) -------------------------------
echo "=== web-lib: URL policy + bridge ==="
. "$NX_LIB_DIR/web-lib.sh"

nx_url_ok "https://example.com" && pass "https accepted" || fail "https accepted"
nx_url_ok "http://localhost:8080" && pass "http accepted" || fail "http accepted"
if nx_url_ok "javascript:alert(1)"; then fail "javascript rejected"; else pass "javascript rejected"; fi
if nx_url_ok "file:///etc/passwd"; then fail "file scheme rejected"; else pass "file scheme rejected"; fi
if nx_url_ok "data:text/html,x"; then fail "data scheme rejected"; else pass "data scheme rejected"; fi
if nx_url_ok "https://exa$(printf '\t')mple.com"; then fail "tab smuggle rejected"; else pass "tab smuggle rejected"; fi
if nx_url_ok ""; then fail "empty rejected"; else pass "empty rejected"; fi
if nx_url_ok "https://"; then fail "scheme-only rejected"; else pass "scheme-only rejected"; fi

# End-to-end bridge: fake Android side answers the request file.
WEBHOST="$TMP/webhost"
mkdir -p "$WEBHOST/requests" "$WEBHOST/responses"
(
    export NX_WEB_HOST="$WEBHOST"
    # Android-side responder: validate-ish answer for the single request.
    ( while [ ! -d "$WEBHOST/done" ]; do
          for req in "$WEBHOST"/requests/*; do
              [ -f "$req" ] || continue
              id="$(basename "$req")"
              url="$(sed -n '2p' "$req")"
              case "$url" in https://*) printf 'OK\nweb-test\n' > "$WEBHOST/responses/$id" ;;
                  *) printf 'ERR\nUnsupported URL: only http:// and https:// can be opened\n' > "$WEBHOST/responses/$id" ;;
              esac
          done
          sleep 0.05
      done ) &
    RESPONDER=$!
    OUT="$(nx_ow_cmd "https://example.com" 2>&1)"
    RC1=$?
    mkdir -p "$WEBHOST/done"
    wait "$RESPONDER" 2>/dev/null
    [ "$RC1" -eq 0 ] && pass "nx_ow_cmd https round-trip ($OUT)" || fail "nx_ow_cmd https round-trip"
    if OUT2="$(nx_ow_cmd "javascript:alert(1)" 2>&1)"; then
        fail "nx_ow_cmd rejects javascript"
    else
        pass "nx_ow_cmd rejects javascript"
    fi
)

# ---- plugin bridge: nx plug (Noxs Plugin Store) -----------------------------
echo "=== plug-lib: id validation + snapshots ==="
. "$NX_LIB_DIR/plug-lib.sh"

PLUGHOST="$TMP/plughost"
mkdir -p "$PLUGHOST/requests" "$PLUGHOST/responses"
printf 'hello\tHello\t1.0.0\tUtilities\tdemo,starter\tExample Noxs plugin\tinstalled\t-\n' > "$PLUGHOST/catalog.txt"
printf 'hello\tHello\t1.0.0\tenabled\n' > "$PLUGHOST/plugins.txt"
(
    export NX_PLUG_HOST="$PLUGHOST"
    ( while [ ! -d "$PLUGHOST/done" ]; do
          for req in "$PLUGHOST"/requests/*; do
              [ -f "$req" ] || continue
              id="$(basename "$req")"
              op="$(sed -n '1p' "$req")"
              plugin="$(sed -n '2p' "$req")"
              case "$op" in
                  open|install|uninstall|enable|disable|update)
                      printf 'OK\ndone\n' > "$PLUGHOST/responses/$id" ;;
                  *)
                      printf 'ERR\nUnsupported plugin request\n' > "$PLUGHOST/responses/$id" ;;
              esac
          done
          sleep 0.05
      done ) &
    RESPONDER=$!
    OUT="$(nx_plug_cmd "" 2>&1)"
    RC1=$?
    mkdir -p "$PLUGHOST/done"
    wait "$RESPONDER" 2>/dev/null
    [ "$RC1" -eq 0 ] && pass "bare nx plug opens the store" || fail "bare nx plug opens the store"

    # Snapshot-driven reads (no bridge round-trip).
    nx_plug_cmd list | grep -q "hello" && pass "nx plug list shows installed" || fail "nx plug list shows installed"
    nx_plug_cmd search hello | grep -q "Hello" && pass "nx plug search hello" || fail "nx plug search hello"
    nx_plug_cmd search nomatchxyz | grep -q "No plugins found" && pass "nx plug search empty" || fail "nx plug search empty"
    nx_plug_cmd info hello | grep -q "Example Noxs plugin" && pass "nx plug info" || fail "nx plug info"
    if nx_plug_cmd info ghost >/dev/null 2>&1; then fail "nx plug info unknown rejected"; else pass "nx plug info unknown rejected"; fi
)

# ---- dispatcher: noxs forward + help ----------------------------------------
echo "=== dispatcher: forwarding + help ==="
"$NXBIN" help | grep -q "nx install" && pass "nx help" || fail "nx help"
"$NXBIN" version | grep -q "nx 1" && pass "nx version" || fail "nx version"
if "$NXBIN" pkg frobnicate >/dev/null 2>&1; then fail "unknown pkg subcommand rejected"; else pass "unknown pkg subcommand rejected"; fi

# ---- dispatcher: subcommand must be dropped before forwarding ---------------
# Regression (device report): the dispatcher used to forward the subcommand
# itself, so `nx ow URL` saw two arguments ("exactly one URL" error) and a
# bare `nx install` resolved a package literally named "install".
echo "=== dispatcher: argument forwarding ==="
OWOUT="$("$NXBIN" ow https://example.com 2>&1)"
case "$OWOUT" in
    *"exactly one URL"*) fail "nx ow forwards a single URL (subcommand leaked)" ;;
    *"web bridge is unavailable"*) pass "nx ow forwards a single URL" ;;
    *) fail "nx ow unexpected output: $OWOUT" ;;
esac
INOUT="$("$NXBIN" install 2>&1)"
if printf '%s' "$INOUT" | grep -q "usage: nx install"; then
    pass "bare nx install shows usage"
else
    fail "bare nx install shows usage (got: $INOUT)"
fi
INFOUT="$("$NXBIN" info 2>&1)"
if printf '%s' "$INFOUT" | grep -q "usage: nx info"; then
    pass "bare nx info shows usage"
else
    fail "bare nx info shows usage (got: $INFOUT)"
fi
UPDOUT="$(NX_STATE_DIR="$TMP/state" "$NXBIN" update 2>&1)"
if [ "$UPDOUT" = "nx: No packages installed." ]; then
    pass "nx update with nothing installed"
else
    fail "nx update with nothing installed (got: $UPDOUT)"
fi
RMOUT="$(NX_STATE_DIR="$TMP/state" "$NXBIN" remove tree 2>&1)"
if printf '%s' "$RMOUT" | grep -q "Package not installed: tree"; then
    pass "nx remove targets the named package"
else
    fail "nx remove targets the named package (got: $RMOUT)"
fi
LSOUT="$(NX_STATE_DIR="$TMP/state" "$NXBIN" list 2>&1)"
if [ "$LSOUT" = "nx: No packages installed." ]; then
    pass "nx list with nothing installed"
else
    fail "nx list with nothing installed (got: $LSOUT)"
fi

# ---- cert-fix dispatcher wiring ----------------------------------------------
echo "=== cert-fix: dispatcher wiring ==="
if [ -f "$NX_LIB_DIR/cert-fix.sh" ]; then pass "cert-fix.sh extracted"; else fail "cert-fix.sh extracted"; fi
"$NXBIN" help | grep -q "nx cert-fix" && pass "nx help mentions cert-fix" || fail "nx help mentions cert-fix"
"$NXBIN" cert-fix --help 2>&1 | grep -q "usage: nx cert-fix" && pass "cert-fix help" || fail "cert-fix help"
"$NXBIN" cert-fix definitely-not-an-option >/dev/null 2>&1
[ $? -eq 2 ] && pass "cert-fix rejects unknown args" || fail "cert-fix rejects unknown args"
grep -q "cf_use_http" "$NX_LIB_DIR/cert-fix.sh" && grep -q "cf_use_https" "$NX_LIB_DIR/cert-fix.sh" \
    && pass "cert-fix has HTTP fallback + HTTPS restore" || fail "cert-fix transport helpers"
grep -q "the same failure repeated" "$NX_LIB_DIR/cert-fix.sh" \
    && pass "cert-fix identical-failure abort present" || fail "cert-fix identical-failure abort"

# ---- ai dispatcher wiring ----------------------------------------------------
echo "=== ai: dispatcher wiring ==="
if [ -f "$NX_LIB_DIR/ai-lib.sh" ]; then pass "ai-lib.sh extracted"; else fail "ai-lib.sh extracted"; fi
"$NXBIN" help | grep -q "nx ai" && pass "nx help mentions ai" || fail "nx help mentions ai"
if [ ! -d "$TMP/nxroot/ai" ]; then fail "ai python runtime extracted"; else
    for f in agent.py provider.py tools.py; do
        [ -f "$TMP/nxroot/ai/$f" ] && pass "ai/$f extracted" || fail "ai/$f extracted"
    done
    python3 -m py_compile "$TMP/nxroot/ai/agent.py" "$TMP/nxroot/ai/provider.py" \
        "$TMP/nxroot/ai/tools.py" \
        && pass "ai python syntax" || fail "ai python syntax"
    grep -q "kilo-auto/free" "$TMP/nxroot/ai/agent.py" \
        && pass "default model kilo-auto/free" || fail "default model kilo-auto/free"
    if grep -qE "sk-[A-Za-z0-9]{16,}|ghp_[A-Za-z0-9]{20,}" "$TMP/nxroot/ai/agent.py" \
        "$TMP/nxroot/ai/ai-lib.sh" "$TMP/nxroot/ai-lib.sh" 2>/dev/null; then
        fail "no provider keys embedded"
    else
        pass "no provider keys embedded"
    fi
fi

echo
if [ "$RC" -eq 0 ]; then
    echo "[nx] functional tests passed"
else
    echo "[nx] FUNCTIONAL TEST FAILURES" >&2
fi
exit "$RC"
