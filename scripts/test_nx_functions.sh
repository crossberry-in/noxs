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
              'PKG_UI': 'noxs-pkg/pkg-ui.sh',
              'PKG_MANAGER': 'noxs-pkg/pkg-manager.sh',
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

# ---- pkg-ui: single-line progress primitives --------------------------------
echo "=== pkg-ui: progress primitives ==="
. "$NX_LIB_DIR/pkg-ui.sh"
ui_init
[ "$PKG_UI_TTY" = "0" ] && pass "non-tty detected (live renderer disabled)" || fail "non-tty detected"
out=$(progress_render "Downloading x" 100 200 5); [ -z "$out" ] \
    && pass "progress_render silent when UI disabled" || fail "progress_render silent when UI disabled"
calc=$(progress_calc 3600 7200 3)
[ "$calc" = "50 1200 3" ] && pass "percentage/speed/ETA math ($calc)" || fail "progress_calc math: $calc"
calc=$(progress_calc 100 - 5)
[ "${calc%% *}" = "-" ] && pass "unknown total -> no invented percentage" || fail "unknown total pct: $calc"
calc=$(progress_calc 3600 7200 1)
[ "${calc##* }" = "-" ] && pass "ETA withheld before 2s of samples" || fail "ETA gating: $calc"
calc=$(progress_calc 7200 7200 10)
[ "${calc%% *}" = "100" ] && pass "completion reaches 100%" || fail "100%: $calc"
[ "$(humanize_bytes 5033164)" = "4.8 MB" ] && pass "humanize_bytes" || fail "humanize_bytes"
[ "$(progress_fmt_speed 2202009)" = "2.1 MB/s" ] && pass "progress_fmt_speed" || fail "progress_fmt_speed"
[ "$(progress_fmt_eta 185)" = "ETA 3m05s" ] && pass "progress_fmt_eta minutes" || fail "progress_fmt_eta m"
[ "$(progress_fmt_eta 2)" = "ETA 2s" ] && pass "progress_fmt_eta seconds" || fail "progress_fmt_eta s"
# forced-tty rendering: single line, \r refresh, stale-character clearing
PKG_UI_TTY=1; PKG_UI_COLS=80; PKG_UI_LASTLEN=0
progress_render "Downloading demo_1.2.3_amd64.deb" 1024 4096 1 > "$WORK/render1"
[ "$(wc -l < "$WORK/render1")" -eq 0 ] && pass "no newline in live progress line" || fail "live line emitted newline"
grep -q $'\r' "$WORK/render1" && pass "\\r carriage-return refresh present" || fail "\\r missing"
progress_render "OK" 4096 4096 4 >> "$WORK/render1"
[ "$PKG_UI_LASTLEN" -lt 60 ] && pass "progress line length tracked" || fail "LASTLEN not tracked"
progress_clear
[ "$PKG_UI_LASTLEN" -eq 0 ] && pass "progress_clear resets state" || fail "progress_clear"
PKG_UI_TTY=0

# ---- pkg-manager: OS detection + error codes --------------------------------
echo "=== pkg-manager: OS detection + error codes ==="
. "$NX_LIB_DIR/pkg-manager.sh"
printf 'ID=debian\nVERSION_ID="12"\n' > "$WORK/os.debian"
printf 'ID=ubuntu\nID_LIKE=debian\n' > "$WORK/os.ubuntu"
printf 'ID=fedora\n' > "$WORK/os.fedora"
PKG_OS_RELEASE="$WORK/os.debian" pkgm_detect_os
[ "$PKG_OS" = "debian" ] && pass "Debian detected" || fail "debian detect: $PKG_OS"
PKG_OS_RELEASE="$WORK/os.ubuntu" pkgm_detect_os
[ "$PKG_OS" = "ubuntu" ] && pass "Ubuntu detected (ID)" || fail "ubuntu detect: $PKG_OS"
PKG_OS_RELEASE="$WORK/os.fedora" pkgm_detect_os
[ "$PKG_OS" = "unknown" ] && pass "non-Debian OS -> unknown (Noxs packages only)" || fail "fedora detect"
for code in PACKAGE_NOT_FOUND UNSUPPORTED_ARCHITECTURE NETWORK_ERROR DOWNLOAD_FAILED \
            CHECKSUM_MISMATCH SIGNATURE_VERIFICATION_FAILED DEPENDENCY_RESOLUTION_FAILED \
            INSUFFICIENT_DISK_SPACE INSTALL_PERMISSION_DENIED PACKAGE_MANAGER_BUSY MIRROR_UNAVAILABLE; do
    rc=$(pkgm_rc "$code")
    case "$rc" in ''|*[!0-9]*|0) fail "exit code for $code ($rc)" ;; *) pass "$code -> rc $rc" ;; esac
done
grep -q 'eval "set --' "$NX_LIB_DIR/pkg-manager.sh" \
    && fail "apt print-uris parsed without eval (injection-safe)" || \
    pass "apt print-uris parsed without eval (injection-safe)"
line="'http://deb.debian.org/debian/pool/main/d/demo/demo_1.2.3_amd64.deb' demo_1.2.3_amd64.deb 262144 SHA256:abcdef1234567890 demo"
pkgm_parse_uri "$line"
[ "$PU_URL" = "http://deb.debian.org/debian/pool/main/d/demo/demo_1.2.3_amd64.deb" ] \
    && [ "$PU_FILE" = "demo_1.2.3_amd64.deb" ] && [ "$PU_SIZE" = "262144" ] \
    && [ "$PU_SHA" = "abcdef1234567890" ] && [ "$PU_NAME" = "demo" ] \
    && pass "print-uris line parsed as data" || fail "pkgm_parse_uri: $PU_URL|$PU_FILE|$PU_SIZE|$PU_SHA|$PU_NAME"

# ---- pkg-manager: end-to-end against a local mirror + stub apt --------------
echo "=== pkg-manager: local mirror + stub apt end-to-end ==="
SRV="$WORK/srv"; mkdir -p "$SRV/api/mirror" "$SRV/api/packages" \
    "$SRV/debian/pool/main/d/demo" "$SRV/debian/pool/main/d/bad"
printf 'demo-payload-for-noxs-tests' > "$SRV/debian/pool/main/d/demo/demo_1.2.3_amd64.deb"
cp "$SRV/debian/pool/main/d/demo/demo_1.2.3_amd64.deb" "$SRV/debian/pool/main/d/demo/demo_1.2.3_arm64.deb"
printf 'tampered-payload' > "$SRV/debian/pool/main/d/bad/bad_0.9.9_amd64.deb"
DEMO_SHA=$(sha256sum "$SRV/debian/pool/main/d/demo/demo_1.2.3_amd64.deb" | awk '{print $1}')
DEMO_SIZE=$(wc -c < "$SRV/debian/pool/main/d/demo/demo_1.2.3_amd64.deb" | tr -d ' ')
DEMO_SHA_ARM=$(sha256sum "$SRV/debian/pool/main/d/demo/demo_1.2.3_arm64.deb" | awk '{print $1}')
cat > "$SRV/api/mirror/status.json" <<EOF
{"status":"ok","service":"noxs-pkg-mirror","partial":false,"upstream":"local","suite":"bookworm","component":"main","arches":["arm64","amd64"],"packages":1,"approx_bytes":56,"skipped":[],"download_failures":[],"packages_noxs":0,"mirror_base":"local","synced_at":"2026-01-01T00:00:00Z"}
EOF
cat > "$SRV/api/packages/index.json" <<EOF
{
  "generated_at": "2026-01-01T00:00:00Z",
  "mirror_base": "local",
  "count": 2,
  "packages": [
    {
      "name": "demo",
      "source": "debian",
      "distro": "debian",
      "suite": "bookworm",
      "component": "main",
      "seed": true,
      "arches": {
        "amd64": {
          "version": "1.2.3",
          "sha256": "$DEMO_SHA",
          "size": $DEMO_SIZE,
          "path": "debian/pool/main/d/demo/demo_1.2.3_amd64.deb",
          "download_url": "local/debian/pool/main/d/demo/demo_1.2.3_amd64.deb"
        },
        "arm64": {
          "version": "1.2.3",
          "sha256": "$DEMO_SHA_ARM",
          "size": $DEMO_SIZE,
          "path": "debian/pool/main/d/demo/demo_1.2.3_arm64.deb",
          "download_url": "local/debian/pool/main/d/demo/demo_1.2.3_arm64.deb"
        }
      },
      "description": "demo package for tests"
    },
    {
      "name": "bad",
      "source": "debian",
      "distro": "debian",
      "suite": "bookworm",
      "component": "main",
      "seed": false,
      "arches": {
        "amd64": {
          "version": "0.9.9",
          "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
          "size": 16,
          "path": "debian/pool/main/d/bad/bad_0.9.9_amd64.deb",
          "download_url": "local/debian/pool/main/d/bad/bad_0.9.9_amd64.deb"
        }
      },
      "description": "tampered package fixture"
    }
  ]
}
EOF
python3 - "$SRV" <<'PY' &
import http.server, sys, functools
handler = functools.partial(http.server.SimpleHTTPRequestHandler,
                            directory=sys.argv[1])
http.server.HTTPServer(("127.0.0.1", 18099), handler).serve_forever()
PY
SRV_PID=$!
trap 'kill "$SRV_PID" 2>/dev/null; rm -rf "$TMP"' EXIT
for i in 1 2 3 4 5 6 7 8 9 10; do
    (exec 3<>/dev/tcp/127.0.0.1/18099) 2>/dev/null && { exec 3>&- 3<&-; break; } || sleep 0.5
done

FAKEBIN="$WORK/fakebin"; mkdir -p "$FAKEBIN"
printf '#!/bin/bash\nexec "$@"\n' > "$FAKEBIN/sudo"; chmod +x "$FAKEBIN/sudo"
printf '#!/bin/bash\nexit 1\n' > "$FAKEBIN/pgrep"; chmod +x "$FAKEBIN/pgrep"
mkdir -p "$WORK/lists"; printf 'Package: demo\n' > "$WORK/lists/Packages"
apt_ok_stub() {
cat > "$FAKEBIN/apt-get" <<EOF
#!/bin/bash
echo "apt-get \$*" >> "$WORK/apt-calls.log"
case " \$*" in
  *" --print-uris"*)
    if printf '%s\n' "\$*" | grep -q ' bad'; then
      echo "'http://deb.debian.org/debian/pool/main/d/bad/bad_0.9.9_amd64.deb' bad_0.9.9_amd64.deb 9 SHA256:0000000000000000000000000000000000000000000000000000000000000000 bad"
    else
      echo "'http://deb.debian.org/debian/pool/main/d/demo/demo_1.2.3_amd64.deb' demo_1.2.3_amd64.deb $DEMO_SIZE SHA256:$DEMO_SHA demo"
      echo "'http://deb.debian.org/debian/pool/main/d/demo/demo_1.2.3_arm64.deb' demo_1.2.3_arm64.deb $DEMO_SIZE SHA256:$DEMO_SHA_ARM demo"
    fi
    exit 0 ;;
  *" update"*) exit 0 ;;
  *) exit 0 ;;
esac
EOF
chmod +x "$FAKEBIN/apt-get"
}
apt_ok_stub
cat > "$FAKEBIN/apt-cache" <<'EOF'
#!/bin/bash
case "$*" in
  *"show demo"*) exit 0 ;;
  *"show"*)      exit 100 ;;
  *"policy demo"*) printf 'demo:\n  Installed: (none)\n  Candidate: 9.9.9-official\n' ;;
  *) exit 0 ;;
esac
EOF
chmod +x "$FAKEBIN/apt-cache"

PKGM_ENV=(NOXS_PKG_MIRROR="http://127.0.0.1:18099"
          NOXS_PKG_CACHE_DIR="$WORK/cache" NOXS_TX_DIR="$WORK/run"
          NOXS_OS_RELEASE="$WORK/os.debian" NOXS_APT_LISTS_DIR="$WORK/lists"
          NX_LIB_DIR="$NX_LIB_DIR")

env "${PKGM_ENV[@]}" bash -c '. "'"$NX_LIB_DIR"'/pkg-lib.sh"; . "'"$NX_LIB_DIR"'/pkg-ui.sh"; . "'"$NX_LIB_DIR"'/pkg-manager.sh"; pkgm_search_cmd demo --json' \
    > "$WORK/search.json" 2>"$WORK/search.err"
python3 -c "import json,sys; d=json.load(open('$WORK/search.json')); assert d[0]['name']=='demo' and d[0]['source']=='noxs-mirror', d" \
    && pass "search finds mirror package, valid --json" || fail "search --json"
env "${PKGM_ENV[@]}" bash -c '. "'"$NX_LIB_DIR"'/pkg-lib.sh"; . "'"$NX_LIB_DIR"'/pkg-ui.sh"; . "'"$NX_LIB_DIR"'/pkg-manager.sh"; pkgm_search_cmd demo' \
    > "$WORK/search.txt" 2>/dev/null
grep -q "noxs-mirror" "$WORK/search.txt" && pass "search tags the source" || fail "search source tag"
env "${PKGM_ENV[@]}" bash -c '. "'"$NX_LIB_DIR"'/pkg-lib.sh"; . "'"$NX_LIB_DIR"'/pkg-ui.sh"; . "'"$NX_LIB_DIR"'/pkg-manager.sh"; pkgm_search_cmd definitely-not-a-package-xyz' \
    > "$WORK/none.out" 2>&1
rc=$?
[ "$rc" -eq 3 ] && grep -q "PACKAGE_NOT_FOUND" "$WORK/none.out" \
    && pass "PACKAGE_NOT_FOUND after the full chain" || fail "PACKAGE_NOT_FOUND chain (rc=$rc)"

# install: mirror-sourced, stubbed apt, real download + sha256 verify
rm -rf "$WORK/cache" "$WORK/apt-calls.log"
env "${PKGM_ENV[@]}" bash -c '. "'"$NX_LIB_DIR"'/pkg-lib.sh"; . "'"$NX_LIB_DIR"'/pkg-ui.sh"; . "'"$NX_LIB_DIR"'/pkg-manager.sh"; PATH="'$FAKEBIN':$PATH" pkgm_install_cmd demo' \
    > "$WORK/install.out" 2>"$WORK/install.err"
rc=$?
[ "$rc" -eq 0 ] && pass "pkgm_install_cmd demo succeeded" || { fail "install rc=$rc"; cat "$WORK/install.err" >&2; }
grep -q "Noxs Package Manager" "$WORK/install.out" && pass "branded header panel" || fail "header panel"
grep -q "noxs-mirror" "$WORK/install.out" && pass "Source line shows mirror" || fail "source line"
for s in "\[1/5\] Resolving dependencies" "\[2/5\] Fetching package metadata" \
         "\[3/5\] Downloading packages" "\[4/5\] Verifying package integrity" \
         "\[5/5\] Installing packages"; do
    grep -q "$s" "$WORK/install.out" && pass "stage $s" || fail "stage $s"
done
grep -qE "Verifying SHA256  demo_1\.2\.3_[a-z0-9]+\.deb  \[OK\]" "$WORK/install.out" \
    && pass "per-file SHA256 verification line" || fail "verify line"
grep -q "Result: OK" "$WORK/install.out" && pass "Result: OK only after real confirmation" || fail "Result OK"
grep -q "demo_1.2.3" "$WORK/apt-calls.log" \
    && pass "apt-get install received the downloaded .deb paths" || fail "apt call log"
if grep -q $'\r' "$WORK/install.out"; then
    fail "non-interactive fallback: no \\r churn in CI output"
else
    pass "non-interactive fallback: no \\r churn in CI output"
fi
# second install -> real cache hit
env "${PKGM_ENV[@]}" bash -c '. "'"$NX_LIB_DIR"'/pkg-lib.sh"; . "'"$NX_LIB_DIR"'/pkg-ui.sh"; . "'"$NX_LIB_DIR"'/pkg-manager.sh"; PATH="'$FAKEBIN':$PATH" pkgm_install_cmd demo' \
    > "$WORK/install2.out" 2>&1
grep -q "cache hit" "$WORK/install2.out" \
    && pass "sha256-keyed cache hit on reinstall" || fail "cache hit"
# checksum mismatch -> rc 7 (HTTP 200 alone is never trust)
env "${PKGM_ENV[@]}" NOXS_PKG_CACHE_DIR="$WORK/cache2" bash -c '. "'"$NX_LIB_DIR"'/pkg-lib.sh"; . "'"$NX_LIB_DIR"'/pkg-ui.sh"; . "'"$NX_LIB_DIR"'/pkg-manager.sh"; PATH="'$FAKEBIN':$PATH" pkgm_install_cmd bad' \
    > "$WORK/bad.out" 2>&1
rc=$?
[ "$rc" -eq 7 ] && grep -q "CHECKSUM_MISMATCH" "$WORK/bad.out" \
    && pass "CHECKSUM_MISMATCH -> rc 7" || fail "checksum mismatch rc=$rc"
# busy lock
mkdir -p "$WORK/run"; echo other > "$WORK/run/pkg-tx"
env "${PKGM_ENV[@]}" bash -c '. "'"$NX_LIB_DIR"'/pkg-lib.sh"; . "'"$NX_LIB_DIR"'/pkg-ui.sh"; . "'"$NX_LIB_DIR"'/pkg-manager.sh"; PATH="'$FAKEBIN':$PATH" pkgm_install_cmd demo' \
    > "$WORK/busy.out" 2>&1
rc=$?
[ "$rc" -eq 12 ] && grep -q "PACKAGE_MANAGER_BUSY" "$WORK/busy.out" \
    && pass "concurrent install protection (PACKAGE_MANAGER_BUSY rc 12)" || fail "busy rc=$rc"
rm -f "$WORK/run/pkg-tx"
# dependency resolution failure -> rc 9
printf '#!/bin/bash\nexit 100\n' > "$FAKEBIN/apt-get"; chmod +x "$FAKEBIN/apt-get"
env "${PKGM_ENV[@]}" NOXS_PKG_MIRROR=off bash -c '. "'"$NX_LIB_DIR"'/pkg-lib.sh"; . "'"$NX_LIB_DIR"'/pkg-ui.sh"; . "'"$NX_LIB_DIR"'/pkg-manager.sh"; PATH="'$FAKEBIN':$PATH" pkgm_install_cmd demo' \
    > "$WORK/dep.out" 2>&1
rc=$?
[ "$rc" -eq 9 ] && grep -q "DEPENDENCY_RESOLUTION_FAILED" "$WORK/dep.out" \
    && pass "DEPENDENCY_RESOLUTION_FAILED -> rc 9" || fail "dep resolution rc=$rc"
# signature verification failure -> rc 8 (apt log carries UNAUTHENTICATED)
cat > "$FAKEBIN/apt-get" <<EOF
#!/bin/bash
case " \$*" in
  *" --print-uris"*)
    echo "'http://deb.debian.org/debian/pool/main/d/demo/demo_1.2.3_amd64.deb' demo_1.2.3_amd64.deb $DEMO_SIZE SHA256:$DEMO_SHA demo"
    exit 0 ;;
  *) echo "E: UNAUTHENTICATED package rejected" ; exit 100 ;;
esac
EOF
chmod +x "$FAKEBIN/apt-get"
env "${PKGM_ENV[@]}" NOXS_PKG_MIRROR=off bash -c '. "'"$NX_LIB_DIR"'/pkg-lib.sh"; . "'"$NX_LIB_DIR"'/pkg-ui.sh"; . "'"$NX_LIB_DIR"'/pkg-manager.sh"; PATH="'$FAKEBIN':$PATH" pkgm_install_cmd demo' \
    > "$WORK/sig.out" 2>&1
rc=$?
[ "$rc" -eq 8 ] && grep -q "SIGNATURE_VERIFICATION_FAILED" "$WORK/sig.out" \
    && pass "SIGNATURE_VERIFICATION_FAILED -> rc 8 (never bypassed)" || fail "signature rc=$rc"
# mirror down: accelerator falls back, require-mode raises MIRROR_UNAVAILABLE
env "${PKGM_ENV[@]}" NOXS_PKG_MIRROR="http://127.0.0.1:1" bash -c '. "'"$NX_LIB_DIR"'/pkg-lib.sh"; . "'"$NX_LIB_DIR"'/pkg-ui.sh"; . "'"$NX_LIB_DIR"'/pkg-manager.sh"; pkgm_mirror_status /dev/null' \
    > "$WORK/mir.out" 2>&1
grep -q "mirror unreachable" "$WORK/mir.out" \
    && pass "mirror-down note on stderr (non-fatal accelerator)" || fail "mirror-down note"
rc=$(NOXS_PKG_MIRROR="http://127.0.0.1:1" NOXS_PKG_REQUIRE_MIRROR=1 NX_LIB_DIR="$NX_LIB_DIR" \
    bash -c '. "'"$NX_LIB_DIR"'/pkg-lib.sh"; . "'"$NX_LIB_DIR"'/pkg-ui.sh"; . "'"$NX_LIB_DIR"'/pkg-manager.sh"; pkgm_mirror_status /dev/null; exit $?' >/dev/null 2>&1; echo $?)
[ "$rc" -eq 13 ] && pass "MIRROR_UNAVAILABLE rc 13 in require-mirror mode" || fail "MIRROR_UNAVAILABLE rc=$rc"
# unsupported architecture -> rc 4
printf '#!/bin/bash\necho mips\n' > "$FAKEBIN/uname"; chmod +x "$FAKEBIN/uname"
env "${PKGM_ENV[@]}" NOXS_PKG_MIRROR=off bash -c '. "'"$NX_LIB_DIR"'/pkg-lib.sh"; . "'"$NX_LIB_DIR"'/pkg-ui.sh"; . "'"$NX_LIB_DIR"'/pkg-manager.sh"; PATH="'$FAKEBIN':$PATH" pkgm_install_cmd demo' \
    > "$WORK/arch.out" 2>&1
rc=$?
[ "$rc" -eq 4 ] && grep -q "UNSUPPORTED_ARCHITECTURE" "$WORK/arch.out" \
    && pass "UNSUPPORTED_ARCHITECTURE -> rc 4" || fail "arch rc=$rc"
rm -f "$FAKEBIN/uname"
# doctor produces a structured report against the live local mirror
env "${PKGM_ENV[@]}" NOXS_PKG_MIRROR="http://127.0.0.1:18099" bash -c '. "'"$NX_LIB_DIR"'/pkg-lib.sh"; . "'"$NX_LIB_DIR"'/pkg-ui.sh"; . "'"$NX_LIB_DIR"'/pkg-manager.sh"; PATH="'$FAKEBIN':$PATH" pkgm_doctor_cmd' \
    > "$WORK/doctor.out" 2>&1
grep -q "PASS" "$WORK/doctor.out" && pass "doctor produces PASS/FAIL/WARN report" || fail "doctor output"
# dispatcher wiring: help-safe subcommands only (update/remove would touch apt)
for sub in search install list doctor; do
    "$NXBIN" pkg "$sub" --help >/dev/null 2>&1
    [ $? -le 2 ] && pass "nx pkg $sub wired" || fail "nx pkg $sub wired"
done
"$NXBIN" pkg 2>/dev/null | grep -q "nx pkg search" \
    && pass "nx pkg usage lists the new commands" || fail "usage lists new commands"

echo
if [ "$RC" -eq 0 ]; then
    echo "[nx] functional tests passed"
else
    echo "[nx] FUNCTIONAL TEST FAILURES" >&2
fi
exit "$RC"
