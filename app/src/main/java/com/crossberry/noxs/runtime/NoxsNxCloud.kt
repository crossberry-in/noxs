package com.crossberry.noxs.runtime

/**
 * Noxs Mirror account + cloud publishing CLI.
 * Installed as /usr/local/bin/nxc; credentials are stored per Linux user
 * with owner-only permissions and never written into the package repository.
 */
object NoxsNxCloud {
    val CLI = """#!/usr/bin/env bash
set -Eeuo pipefail

API_BASE="§{NOXS_MIRROR_API:-https://nxmirror.netlify.app/api}"
CONFIG_DIR="§{XDG_CONFIG_HOME:-§HOME/.config}/noxs-mirror"
TOKEN_FILE="§CONFIG_DIR/token"
umask 077

fail() { printf 'nxc: %s\n' "§*" >&2; exit 1; }
need_tools() { command -v curl >/dev/null 2>&1 || fail "curl is required"; command -v python3 >/dev/null 2>&1 || fail "python3 is required"; }
get_token() { [ -s "§TOKEN_FILE" ] || fail "Not logged in. Run: nxc login"; cat "§TOKEN_FILE"; }
api() {
  local method="§1" path="§2" data="§{3:-}" token="§{4:-}"
  local args=(-sS -X "§method" "§API_BASE/§path" -H 'Accept: application/json')
  [ -n "§token" ] && args+=(-H "Authorization: Bearer §token")
  [ -n "§data" ] && args+=(-H 'Content-Type: application/json' --data-binary "@§data")
  curl "§{args[@]}"
}
read_credentials() {
  local username password
  read -r -p "NXMirror username: " username
  read -r -s -p "Password: " password; printf '\n'
  [ -n "§username" ] && [ -n "§password" ] || fail "Username and password are required"
  printf '%s' "§username" > "§CONFIG_DIR/.username"
  python3 -c 'import json,sys; print(json.dumps({"username":sys.argv[1],"password":sys.argv[2]}))' "§username" "§password" > "§CONFIG_DIR/request.json"
}
auth() {
  need_tools; mkdir -p "§CONFIG_DIR"; chmod 700 "§CONFIG_DIR"
  read_credentials
  local route="§1" response token
  response="§(api POST "auth/§route" "§CONFIG_DIR/request.json")" || fail "Account API is unavailable"
  token="§(printf '%s' "§response" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("token","")); print(d.get("error",""),file=sys.stderr)' 2>"§CONFIG_DIR/error.txt" | head -n1)"
  if [ -z "§token" ]; then cat "§CONFIG_DIR/error.txt" >&2; rm -f "§CONFIG_DIR/request.json" "§CONFIG_DIR/error.txt"; fail "Authentication failed"; fi
  printf '%s' "§token" > "§TOKEN_FILE"; chmod 600 "§TOKEN_FILE"
  rm -f "§CONFIG_DIR/request.json" "§CONFIG_DIR/error.txt"
  printf 'Logged in to NXMirror.\n'
  whoami
}
whoami() {
  local token response
  token="§(get_token)"
  response="§(api GET auth/me "" "§token")" || fail "Could not reach NXMirror"
  printf '%s\n' "§response" | python3 -c 'import json,sys; d=json.load(sys.stdin); u=d.get("user") or {}; print("NXMirror account: "+u.get("username","unknown")); print("Account ID: "+u.get("id",""))' || fail "Session expired. Run: nxc login"
}
publish_file() {
  local type="§1" name="§2" version="§3" file="§4" token size sha payload response
  need_tools; token="§(get_token)"
  [ -f "§file" ] || fail "Artifact not found: §file"
  size="§(wc -c < "§file" | tr -d ' ')"
  [ "§size" -le 4194304 ] || fail "Direct CLI upload limit is 4 MiB. Use a smaller artifact."
  sha="§(sha256sum "§file" | awk '{print §1}')"
  mkdir -p "§CONFIG_DIR"
  python3 - "§type" "§name" "§version" "§(basename "§file")" "§file" "§sha" > "§CONFIG_DIR/publish.json" <<'PY'
import base64, hashlib, json, pathlib, sys
kind, name, version, filename, path, sha = sys.argv[1:]
raw = pathlib.Path(path).read_bytes()
if hashlib.sha256(raw).hexdigest() != sha: raise SystemExit("artifact changed during upload")
print(json.dumps({"type":kind,"name":name,"version":version,"filename":filename,"sha256":sha,"base64":base64.b64encode(raw).decode("ascii")},separators=(",",":")))
PY
  printf 'Uploading %s %s (%s bytes)...\n' "§name" "§version" "§size"
  response="§(api POST cloud/publish "§CONFIG_DIR/publish.json" "§token")" || fail "Cloud upload request failed"
  rm -f "§CONFIG_DIR/publish.json"
  printf '%s\n' "§response" | python3 -c 'import json,sys; d=json.load(sys.stdin); a=d.get("artifact"); print("Published: "+a["name"]+" "+a["version"]+"\nDownload: "+a["downloadUrl"]+"\nSHA-256: "+a["sha256"]) if d.get("success") and a else (print(d.get("error","Publish failed"),file=sys.stderr),sys.exit(1))'
}
pkg_deploy() {
  local dir name version artifact
  dir="§{1:-.}"
  [ -d "§dir" ] || fail "Package directory not found"
  cd "§dir"
  name="§(basename "§PWD" | tr '[:upper:]' '[:lower:]')"
  [ -f VERSION ] || fail "VERSION file is missing"
  version="§(tr -d '[:space:]' < VERSION)"
  if ! compgen -G "dist/*.nx.pkg.§version.*.tar.xz" >/dev/null; then nx pkg release; fi
  artifact="§(find dist -maxdepth 1 -type f -name "*.nx.pkg.§version.*.tar.xz" | sort | head -n1)"
  [ -n "§artifact" ] || fail "No package artifact found in dist/"
  publish_file package "§name" "§version" "§artifact"
}
plugin_init() {
  local id="§{1:-}"
  [[ "§id" =~ ^[a-z0-9][a-z0-9._-]{1,63}§ ]] || fail "Usage: nxc plugin init <plugin-id>"
  [ ! -e "§id" ] || fail "Path already exists: §id"
  mkdir -p "§id"
  cat > "§id/plugin.json" <<EOF
{
  "id": "§id",
  "name": "§id",
  "version": "1.0.0",
  "description": "A Noxs plugin",
  "main": "plugin.js",
  "permissions": ["ui"],
  "minimumNoxsVersion": "0.16.0",
  "sdkVersion": "0.0.2"
}
EOF
  cat > "§id/plugin.js" <<'EOF'
(function () {
  "use strict";
  window.__NOXS_PLUGIN__ = {
    activate: function (noxs) {
      noxs.log("Plugin activated");
      noxs.ui.toast("Noxs plugin is ready");
    }
  };
})();
EOF
  printf '# %s\n\nNoxs plugin.\n' "§id" > "§id/README.md"
  printf 'Created plugin scaffold: %s\nNext: edit plugin.js, then nxc plugin deploy %s\n' "§id" "§id"
}
plugin_deploy() {
  local dir id version artifact
  dir="§{1:-.}"; [ -d "§dir" ] || fail "Plugin directory not found"
  cd "§dir"; [ -f plugin.json ] || fail "plugin.json is missing"
  id="§(python3 -c 'import json;print(json.load(open("plugin.json"))["id"])')"
  version="§(python3 -c 'import json;print(json.load(open("plugin.json"))["version"])')"
  [[ "§id" =~ ^[a-z0-9][a-z0-9._-]{1,63}§ ]] || fail "Invalid plugin id"
  [[ "§version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([+-][A-Za-z0-9.-]+)?§ ]] || fail "Invalid plugin version"
  mkdir -p dist
  artifact="dist/§id-§version.noxs-plugin"
  tar -czf "§artifact" -- plugin.json plugin.js README.md
  publish_file plugin "§id" "§version" "§artifact"
}
usage() {
  cat <<'EOF'
nxc — Noxs Mirror cloud client
  nxc register                 Create an NXMirror account
  nxc login                    Login to NXMirror
  nxc whoami                   Show the signed-in account
  nxc logout                   Remove this device's session
  nxc pkg deploy [directory]   Build and publish a .nx.pkg artifact
  nxc plugin init <id>         Create a plugin project
  nxc plugin deploy [directory] Package and publish a Noxs plugin
  nxc packages                 List your cloud packages
  nxc plugins                  List your cloud plugins
EOF
}
case "§{1:-help}" in
  register) auth register ;;
  login) auth login ;;
  whoami) whoami ;;
  logout) rm -f "§TOKEN_FILE"; printf 'Logged out.\n' ;;
  pkg) [ "§{2:-}" = deploy ] || fail "Usage: nxc pkg deploy [directory]"; pkg_deploy "§{3:-.}" ;;
  plugin)
    case "§{2:-}" in
      init) plugin_init "§{3:-}" ;;
      deploy) plugin_deploy "§{3:-.}" ;;
      *) fail "Usage: nxc plugin init <id> | deploy [directory]" ;;
    esac ;;
  packages|plugins)
    token="§(get_token)"
    route=packages; [ "§1" = plugins ] && route=plugins
    api GET "cloud/§route" "" "§token" | python3 -m json.tool ;;
  -h|--help|help) usage ;;
  *) usage; exit 2 ;;
esac
""".replace('§', ')
}