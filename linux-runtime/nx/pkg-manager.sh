#!/bin/bash
# pkg-manager.sh — nx pkg search / info / install / update / list / remove / doctor.
# Sourced by nx after pkg-lib.sh (msg/err/die/http_fetch/registry helpers).
# shellcheck shell=bash

# Overridable for tests; production defaults mirror the real device paths.
PKG_MIRROR_BASE="${NOXS_PKG_MIRROR:-https://noxs-mirror.netlify.app}"
PKG_CACHE_TTL="${NOXS_PKG_CACHE_TTL:-3600}"
PKG_CACHE_DIR="${NOXS_PKG_CACHE_DIR:-${HOME:-/root}/.noxs/cache/pkg}"
PKG_TX_DIR="${NOXS_TX_DIR:-/run/noxs}"
PKG_OS_RELEASE="${NOXS_OS_RELEASE:-/etc/os-release}"
PKG_MIRROR_LIVE=0

# ------------------------------------------------------------- error codes

pkgm_error() { # CODE MESSAGE [HINT] — diagnostics go to stderr, always.
    printf 'nx pkg: %s: %s\n' "$1" "$2" >&2
    [ -n "${3:-}" ] && printf 'hint: %s\n' "$3" >&2
    return 0
}

pkgm_rc() { # CODE -> numeric exit code
    case "$1" in
        PACKAGE_NOT_FOUND)              echo 3 ;;
        UNSUPPORTED_ARCHITECTURE)       echo 4 ;;
        NETWORK_ERROR)                  echo 5 ;;
        DOWNLOAD_FAILED)                echo 6 ;;
        CHECKSUM_MISMATCH)              echo 7 ;;
        SIGNATURE_VERIFICATION_FAILED)  echo 8 ;;
        DEPENDENCY_RESOLUTION_FAILED)   echo 9 ;;
        INSUFFICIENT_DISK_SPACE)        echo 10 ;;
        INSTALL_PERMISSION_DENIED)      echo 11 ;;
        PACKAGE_MANAGER_BUSY)           echo 12 ;;
        MIRROR_UNAVAILABLE)             echo 13 ;;
        *)                              echo 1 ;;
    esac
}

# --------------------------------------------------------- OS / PM detect

pkgm_detect_os() { # sets PKG_OS (debian|ubuntu|unknown), PKG_PM (apt-get|apt|"")
    PKG_OS="unknown"; PKG_PM=""
    if [ -f "$PKG_OS_RELEASE" ]; then
        local id like
        id=$(sed -n 's/^ID=//p' "$PKG_OS_RELEASE" | tr -d '"')
        like=$(sed -n 's/^ID_LIKE=//p' "$PKG_OS_RELEASE" | tr -d '"')
        case "$id" in
            debian) PKG_OS="debian" ;;
            ubuntu) PKG_OS="ubuntu" ;;
            *) case "$like" in
                   *debian*)  PKG_OS="debian" ;;
                   *ubuntu*)  PKG_OS="ubuntu" ;;
               esac ;;
        esac
    fi
    if command -v apt-get >/dev/null 2>&1; then PKG_PM="apt-get"
    elif command -v apt >/dev/null 2>&1; then   PKG_PM="apt"
    fi
    return 0
}

pkgm_is_distro() { # true when a Debian-family PM actually drives this guest
    [ "$PKG_OS" = "debian" ] || [ "$PKG_OS" = "ubuntu" ]
    [ "$PKG_PM" != "" ] && return 0
    return 1
}

pkgm_lists_ok() { ls "${NOXS_APT_LISTS_DIR:-/var/lib/apt/lists}"/*Packages* >/dev/null 2>&1; }

# pkgm_deb_arch — Noxs arch name -> Debian architecture name (the mirror
# index and apt both speak Debian naming).
pkgm_deb_arch() {
    case "$(detect_arch 2>/dev/null)" in
        aarch64) echo "arm64" ;;
        x86_64)  echo "amd64" ;;
        armv7)   echo "armhf" ;;
        *)       echo "" ;;
    esac
}

# ---------------------------------------------------------- single flight

pkgm_busy_check() {
    if [ -f "$PKG_TX_DIR/pkg-tx" ] \
        || pgrep -x dpkg >/dev/null 2>&1 \
        || pgrep -x apt-get >/dev/null 2>&1 \
        || pgrep -x apt >/dev/null 2>&1; then
        pkgm_error PACKAGE_MANAGER_BUSY "another package operation is running" \
            "wait for it to finish, then retry"
        return 1
    fi
    return 0
}

pkgm_lock_arm() {     PKG_M_OWNS_TX=0
    if mkdir -p "$PKG_TX_DIR" 2>/dev/null \
        && echo "nx pkg-manager" > "$PKG_TX_DIR/pkg-tx" 2>/dev/null; then
        PKG_M_OWNS_TX=1
    fi
    return 0
}

pkgm_lock_release() {
    [ "${PKG_M_OWNS_TX:-0}" = "1" ] && rm -f "$PKG_TX_DIR/pkg-tx" 2>/dev/null
    return 0
}

# ------------------------------------------------------------ mirror client

# pkgm_mirror_status OUT — probe GET /api/mirror/status. Non-fatal by
# design: the mirror is an accelerator. MIRROR_UNAVAILABLE is raised only
# when NOXS_PKG_REQUIRE_MIRROR=1 (or the mirror is explicitly required).
pkgm_mirror_status() {
    PKG_MIRROR_LIVE=0
    [ "$PKG_MIRROR_BASE" = "off" ] && return 1
    local out="$1"
    if http_fetch_code "$PKG_MIRROR_BASE/api/mirror/status.json" "$out" >/dev/null 2>&1 \
        && grep -q '"status"' "$out" 2>/dev/null; then
        PKG_MIRROR_LIVE=1
        return 0
    fi
    if [ "${NOXS_PKG_REQUIRE_MIRROR:-0}" = "1" ]; then
        pkgm_error MIRROR_UNAVAILABLE "Noxs package mirror is unreachable: $PKG_MIRROR_BASE" \
            "retry later, or unset NOXS_PKG_REQUIRE_MIRROR to use official sources"
        return "$(pkgm_rc MIRROR_UNAVAILABLE)"
    fi
    printf 'nx pkg: mirror unreachable — using official sources\n' >&2
    return 1
}

# pkgm_index_tsv — print the mirror index as flat TSV:
#   name <TAB> seed <TAB> arch <TAB> version <TAB> sha256 <TAB> size <TAB>
#   download_url <TAB> description
# Cached with a TTL (PKG_CACHE_TTL); stale copy used when refresh fails.
pkgm_index_tsv() {
    local f="$PKG_CACHE_DIR/mirror-index.json"
    mkdir -p "$PKG_CACHE_DIR" 2>/dev/null || return 1
    local fresh=0
    if [ -s "$f" ]; then
        local age now_ts f_ts
        now_ts=$(date +%s); f_ts=$(stat -c %Y "$f" 2>/dev/null || echo 0)
        age=$((now_ts - f_ts))
        [ "$age" -lt "$PKG_CACHE_TTL" ] && fresh=1
    fi
    if [ "$fresh" != "1" ]; then
        local tmp="$f.tmp$$"
        if http_fetch_code "$PKG_MIRROR_BASE/api/packages/index.json" "$tmp" 2>/dev/null | grep -q '^200' \
            && [ -s "$tmp" ]; then
            mv "$tmp" "$f"
        else
            rm -f "$tmp"
            [ -s "$f" ] || return 1      # no fresh copy and no stale copy
        fi
    fi
    awk '
        function val(line, key,   v) {
            v = line
            sub("^.*\"" key "\": *\"", "", v)
            sub("\".*$", "", v)
            return v
        }
        function flush() {
            if (name != "" && arch != "" && version != "" && sha != "")
                printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
                    name, seed, arch, version, sha, size, url, desc
            arch = ""; version = ""; sha = ""; size = ""; url = ""
        }
        # Key-based matching (indent-agnostic) against OUR generated index:
        # field names are unambiguous, and noxs entries never set an arch,
        # so the flush guard drops them.
        /"name": /                 { flush(); name = val($0, "name"); seed = 0; desc = "" }
        /"seed": true/             { seed = 1 }
        /"arm64": \{/              { flush(); arch = "arm64" }
        /"amd64": \{/              { flush(); arch = "amd64" }
        /"version": /              { if (arch != "") version = val($0, "version") }
        /"sha256": /               { if (arch != "") sha = val($0, "sha256") }
        /"size": /                 { if (arch != "") { v = $0; sub("^.*\"size\": *", "", v); sub("[^0-9].*$", "", v); size = v } }
        /"download_url": /         { if (arch != "") url = val($0, "download_url") }
        /"description": /          { desc = val($0, "description") }
        END { flush() }
    ' "$f"
}

# pkgm_mirror_url OFFICIAL_URL -> rewritten mirror URL (or official + rc 1)
pkgm_mirror_url() {
    local p="${1#*debian/}"
    [ "$p" = "$1" ] && { echo "$1"; return 1; }
    echo "$PKG_MIRROR_BASE/debian/$p"
}

# ------------------------------------------------------------- disk space

pkgm_disk_ok() { # NEED_BYTES — true when / has enough free (unknown = allow)
    local avail
    avail=$(df -Pk / 2>/dev/null | awk 'NR==2{print "$4 * 1024"}')
    case "$avail" in ''|*[!0-9]*) return 0 ;; esac
    [ "$avail" -ge "$1" ]
}

# --------------------------------------------------------- download engine

# pkgm_cache_get SHA OUT — copy a cached download (sha256-keyed) into OUT;
# echoes "hit" or "miss" so the caller can report real cache behavior.
pkgm_cache_get() {
    local f="$PKG_CACHE_DIR/deb/$1.deb"
    if [ -s "$f" ]; then
        cp -f "$f" "$2" 2>/dev/null && { echo hit; return 0; }
    fi
    echo miss
    return 0
}

# pkgm_cache_put SHA FILE — copy INTO the cache. Copy, never move: the
# apt invocation still needs the file inside NX_WORK.
pkgm_cache_put() {
    mkdir -p "$PKG_CACHE_DIR/deb" 2>/dev/null || return 0
    local dst="$PKG_CACHE_DIR/deb/$1.deb"
    [ -s "$dst" ] || cp -f "$2" "$dst" 2>/dev/null || return 0
    return 0
}

# pkgm_fetch URL OUT TOTAL LABEL — streaming download with live single-line
# progress (tty) or a silent foreground fetch (non-interactive). Two
# bounded attempts; the second resumes (-C -). Never prints per-chunk logs.
pkgm_fetch() {
    local url="$1" out="$2" total="$3" label="$4"
    local t0 attempt rc pid errf have
    t0=$(date +%s)
    errf="$out.err"
    if [ "$PKG_UI_TTY" != "1" ]; then
        for attempt in 1 2; do
            if command -v curl >/dev/null 2>&1; then
                extra=""
                [ "$attempt" = "2" ] && [ -s "$out" ] && extra="-C -"
                # shellcheck disable=SC2086
                curl -fsSL --retry 2 --retry-delay 2 --connect-timeout 20 $extra -o "$out" "$url" && return 0
            else
                extra=""
                [ "$attempt" = "2" ] && [ -s "$out" ] && extra="-c"
                # shellcheck disable=SC2086
                wget -q $extra -O "$out" "$url" && return 0
            fi
            sleep 2
        done
        return 1
    fi
    for attempt in 1 2; do
        extra=""
        [ "$attempt" = "2" ] && [ -s "$out" ] && extra="-C -"
        : > "$errf"
        # shellcheck disable=SC2086
        if command -v curl >/dev/null 2>&1; then
            curl -fsSL --retry 2 --retry-delay 2 --connect-timeout 20 $extra -o "$out" "$url" 2>"$errf" &
        else
            wget -q $extra -O "$out" "$url" 2>"$errf" &
        fi
        pid=$!
        while kill -0 "$pid" 2>/dev/null; do
            sleep 0.3
            have=0
            [ -f "$out" ] && have=$(wc -c < "$out" 2>/dev/null || echo 0)
            progress_render "$label" "$have" "$total" "$(( $(date +%s) - t0 ))"
        done
        wait "$pid"; rc=$?
        progress_clear
        [ "$rc" -eq 0 ] && { rm -f "$errf"; return 0; }
        sleep 2
    done
    [ -s "$errf" ] && { printf 'nx pkg: download error: ' >&2; head -n 1 "$errf" >&2; }
    rm -f "$errf"
    return 1
}

# pkgm_parse_uri LINE -> PU_URL PU_FILE PU_SIZE PU_SHA PU_NAME
# apt-get --print-uris emits:  'URL' FILENAME SIZE SHA256:HASH PACKAGE
# Parsed as data (no eval — the index is untrusted input for splitting).
pkgm_parse_uri() {
    local rest="${1#\'}"
    PU_URL="${rest%%\'*}"          # up to the FIRST closing quote
    rest="${rest#*\'}"; rest="${rest# }"
    PU_FILE="${rest%% *}"
    rest="${rest#* }"; PU_SIZE="${rest%% *}"
    rest="${rest#* }"; PU_SHA="${rest%% *}"
    rest="${rest#* }"; PU_NAME="${rest%% *}"
    PU_SHA="${PU_SHA#SHA256:}"
    return 0
}

# ------------------------------------------------------------ search chain

pkgm_search_cmd() {
    ui_init
    local q=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --json)  NOXS_PKG_JSON=1 ;;
            -h|--help) printf 'usage: nx pkg search <name> [--json]\n'; return 0 ;;
            *) if [ -z "$q" ]; then q="$1"; else err "unexpected argument: $1"; return 2; fi ;;
        esac
        shift
    done
    [ -n "$q" ] || { err "usage: nx pkg search <name>"; return 2; }

    local tsv rows="" printed=0 row name seed arch version sha size url desc src
    local arch_now; arch_now=$(pkgm_deb_arch 2>/dev/null || echo "")

    # The JSON array opens ONCE, before any source loop; every row adds
    # its own comma separator (printed counter). Text mode prints the
    # column header before the first row instead.
    [ "${NOXS_PKG_JSON:-0}" = "1" ] && printf '['

    # 1 + 2 — local cache (installed manifests) + Noxs package API index.
    if tsv=$(pkgm_index_tsv); then
        while IFS=$'\t' read -r name seed arch version sha size url desc; do
            [ -n "$name" ] || continue
            # exact name match OR case-insensitive substring on name+description
            if [ "$name" != "$q" ]; then
                printf '%s %s' "$name" "$desc" | grep -qi -- "$q" || continue
            fi
            if [ "$printed" -eq 0 ] && [ "${NOXS_PKG_JSON:-0}" != "1" ]; then
                printf '%-22s %-16s %-24s %-8s %s\n' "NAME" "SOURCE" "VERSION" "ARCH" "DESCRIPTION"
            fi
            if [ "${NOXS_PKG_JSON:-0}" = "1" ]; then
                [ "$printed" -gt 0 ] && printf ','
                printf '{"name":"%s","source":"noxs-mirror","version":"%s","arch":"%s","sha256":"%s","size":%s,"seed":%s}\n' \
                    "$name" "$version" "$arch" "$sha" "$size" "$seed"
            else
                printf '%-22s %-16s %-24s %-8s %s\n' "$name" "noxs-mirror" "$version" "$arch" "$desc"
            fi
            printed=$((printed + 1))
        done < <(printf '%s\n' "$tsv")
    fi

    # local installed manifests (Noxs packages already on the device)
    for f in "${NX_STATE_DIR:-/var/lib/noxs-pkg}"/installed/*.json; do
        [ -f "$f" ] || continue
        name=$(sed -n 's/.*"name" *: *"\([^"]*\)".*/\1/p' "$f" | head -n 1)
        case "$name" in "$q")
            version=$(sed -n 's/.*"version" *: *"\([^"]*\)".*/\1/p' "$f" | head -n 1)
            if [ "${NOXS_PKG_JSON:-0}" = "1" ]; then
                [ "$printed" -gt 0 ] && printf ','
                printf '{"name":"%s","source":"local-cache","version":"%s"}\n' "$name" "$version"
            else
                [ "$printed" -eq 0 ] && printf '%-22s %-16s %-24s %-8s %s\n' "NAME" "SOURCE" "VERSION" "ARCH" "DESCRIPTION"
                printf '%-22s %-16s %-24s %-8s %s\n' "$name" "local-cache" "$version" "-" "installed (Noxs package)"
            fi
            printed=$((printed + 1))
            ;; esac
    done

    # 3 — distro official metadata (no download; uses existing apt lists)
    if pkgm_detect_os && pkgm_is_distro && pkgm_lists_ok; then
        rows=$(apt-cache search --names-only "^$q\$" 2>/dev/null | head -n 5)
        [ -n "$rows" ] || rows=$(apt-cache search "$q" 2>/dev/null | head -n 5)
        while IFS= read -r row; do
            [ -n "$row" ] || continue
            name="${row%% - *}"; desc="${row#* - }"
            version=$(apt-cache policy "$name" 2>/dev/null | awk '/Candidate:/{print $2; exit}')
            if [ "${NOXS_PKG_JSON:-0}" = "1" ]; then
                [ "$printed" -gt 0 ] && printf ','
                printf '{"name":"%s","source":"debian-official","version":"%s"}\n' "$name" "$version"
            else
                [ "$printed" -eq 0 ] && printf '%-22s %-16s %-24s %-8s %s\n' "NAME" "SOURCE" "VERSION" "ARCH" "DESCRIPTION"
                printf '%-22s %-16s %-24s %-8s %s\n' "$name" "debian-official" "$version" "$arch_now" "$desc"
            fi
            printed=$((printed + 1))
        done <<EOF
$rows
EOF
    fi

    # 4 — Noxs registry (official Git packages; metadata only)
    local regdir regjson=""
    regdir=$(mktemp -d "${TMPDIR:-/tmp}/nx-search.XXXXXX" 2>/dev/null) || regdir=""
    if [ -n "$regdir" ]; then
        regjson="$regdir/registry.json"
        if registry_fetch "${NX_OFFICIAL_ORG:-noxs-pkg}" "$q" "$regjson" 2>/dev/null; then
            version=$(sed -n 's/.*"latest" *: *"\([^"]*\)".*/\1/p' "$regjson" | head -n 1)
            if [ "${NOXS_PKG_JSON:-0}" = "1" ]; then
                [ "$printed" -gt 0 ] && printf ','
                printf '{"name":"%s","source":"noxs-registry","version":"%s"}\n' "$q" "$version"
            else
                [ "$printed" -eq 0 ] && printf '%-22s %-16s %-24s %-8s %s\n' "NAME" "SOURCE" "VERSION" "ARCH" "DESCRIPTION"
                printf '%-22s %-16s %-24s %-8s %s\n' "$q" "noxs-registry" "$version" "$arch_now" "Noxs Git package"
            fi
            printed=$((printed + 1))
        fi
        rm -rf "$regdir"
    fi

    # 5 — nothing found anywhere
    if [ "$printed" -eq 0 ]; then
        if [ "${NOXS_PKG_JSON:-0}" = "1" ]; then
            printf ']\n'                     # empty, valid JSON array
        fi
        pkgm_error PACKAGE_NOT_FOUND "no package matches '$q'" \
            "try: nx pkg update (refresh indexes), or a different name"
        return "$(pkgm_rc PACKAGE_NOT_FOUND)"
    fi
    [ "${NOXS_PKG_JSON:-0}" = "1" ] && printf ']\n'
    return 0
}

# ------------------------------------------------------------------- info

pkgm_info_cmd() {
    ui_init
    local name="${1:-}"
    [ "${1:-}" = "--json" ] && { NOXS_PKG_JSON=1; name="${2:-}"; }
    [ -n "$name" ] || { err "usage: nx pkg info <name> [--json]"; return 2; }

    local arch debarch ver="" sha="" size="" url="" desc="" src="unknown"
    arch=$(detect_arch 2>/dev/null || echo "")
    debarch=$(pkgm_deb_arch 2>/dev/null || echo "")
    local tsv row
    if tsv=$(pkgm_index_tsv 2>/dev/null); then
        row=$(printf '%s\n' "$tsv" | awk -F'\t' -v n="$name" -v a="$debarch" '$1==n && $3==a {print; exit}')
        if [ -n "$row" ]; then
            IFS=$'\t' read -r _ _ _ ver sha size url desc <<< "$row"
            src="noxs-mirror"
        fi
    fi
    if pkgm_detect_os && pkgm_is_distro && [ "$src" = "unknown" ]; then
        if apt-cache show "$name" >/dev/null 2>&1; then
            src="debian-official"
            ver=$(apt-cache policy "$name" 2>/dev/null | awk '/Candidate:/{print $2; exit}')
        fi
    fi
    local installed=""
    if manifest_exists "$name" 2>/dev/null; then
        installed=$(sed -n 's/.*"version" *: *"\([^"]*\)".*/\1/p' "$(manifest_path "$name")" | head -n 1)
        src="noxs-registry"
        [ -z "$ver" ] && ver="$installed"
    fi
    if [ "$src" = "unknown" ]; then
        pkgm_error PACKAGE_NOT_FOUND "$name"; return "$(pkgm_rc PACKAGE_NOT_FOUND)"
    fi
    if [ "${NOXS_PKG_JSON:-0}" = "1" ]; then
        printf '{"name":"%s","source":"%s","version":"%s","installed":"%s","arch":"%s","sha256":"%s","size":%s}\n' \
            "$name" "$src" "$ver" "$installed" "$arch" "$sha" "${size:-0}"
        return 0
    fi
    ui_header "$name" "$src" "$ver" "$arch"
    [ -n "$desc" ] && printf '  %-8s: %s\n' "Summary" "$desc"
    [ -n "$installed" ] && printf '  %-8s: %s\n' "Installed" "$installed"
    [ -n "$size" ] && printf '  %-8s: %s\n' "Size" "$(humanize_bytes "$size")"
    [ -n "$sha" ] && printf '  %-8s: %s\n' "SHA256" "${sha:0:16}..."
    return 0
}

# ---------------------------------------------------------------- install

pkgm_install_cmd() {
    ui_init
    local name="" assume=0
    while [ $# -gt 0 ]; do
        case "$1" in
            -y|--yes) assume=1 ;;
            --json)   NOXS_PKG_JSON=1; ui_init ;;
            -h|--help) printf 'usage: nx pkg install <name> [-y] [--json]\n'; return 0 ;;
            *) if [ -z "$name" ]; then name="$1"; else err "unexpected argument: $1"; return 2; fi ;;
        esac
        shift
    done
    [ -n "$name" ] || { err "usage: nx pkg install <name>"; return 2; }

    local arch debarch
    arch=$(detect_arch 2>/dev/null) || arch=""
    debarch=$(pkgm_deb_arch 2>/dev/null) || debarch=""
    [ -n "$arch" ] || {
        pkgm_error UNSUPPORTED_ARCHITECTURE "unsupported architecture: $(uname -m)"
        return "$(pkgm_rc UNSUPPORTED_ARCHITECTURE)"
    }

    # Mirror probe (accelerator; never fatal unless REQUIRE is set)
    local st; st=$(mktemp)
    pkgm_mirror_status "$st"; local mirror_rc=$?
    rm -f "$st"
    [ "$mirror_rc" -eq 0 ] || [ "${NOXS_PKG_REQUIRE_MIRROR:-0}" != "1" ] || return "$(pkgm_rc MIRROR_UNAVAILABLE)"

    # Resolve the source by priority: mirror index -> distro -> registry.
    local src="" ver="" sha="" size="" murl="" row tsv
    if [ "$PKG_MIRROR_LIVE" = "1" ] && tsv=$(pkgm_index_tsv); then
        row=$(printf '%s\n' "$tsv" | awk -F'\t' -v n="$name" -v a="$debarch" '$1==n && $3==a {print; exit}')
        if [ -n "$row" ]; then
            IFS=$'\t' read -r _ _ _ ver sha size murl _ <<< "$row"
            src="noxs-mirror"
        fi
    fi
    if [ -z "$src" ]; then
        pkgm_detect_os
        if pkgm_is_distro && apt-cache show "$name" >/dev/null 2>&1; then
            src="debian-official"
            ver=$(apt-cache policy "$name" 2>/dev/null | awk '/Candidate:/{print $2; exit}')
        fi
    fi
    if [ -z "$src" ]; then
        local regtmp; regtmp=$(mktemp)
        if registry_fetch "${NX_OFFICIAL_ORG:-noxs-pkg}" "$name" "$regtmp" 2>/dev/null; then
            src="noxs-registry"
            ver=$(sed -n 's/.*"latest" *: *"\([^"]*\)".*/\1/p' "$regtmp" | head -n 1)
        fi
        rm -f "$regtmp"
    fi
    if [ -z "$src" ]; then
        pkgm_error PACKAGE_NOT_FOUND "no installable package '$name' for $arch" \
            "try: nx pkg search $name, nx pkg update, or nx cert-fix"
        return "$(pkgm_rc PACKAGE_NOT_FOUND)"
    fi

    pkgm_busy_check || return "$(pkgm_rc PACKAGE_MANAGER_BUSY)"
    pkgm_lock_arm

    ui_header "$name" "$src" "${ver:-unknown}" "$arch"

    local rc=0
    case "$src" in
        noxs-registry)
            # Delegate to the existing custom installer (sha256 + tar safety
            # + manifest); its stage output is the real transcript.
            ui_stage 1 5 "Resolving dependencies"
            ui_step "Noxs package registry: $NX_OFFICIAL_ORG/$name"
            ui_stage 2 5 "Fetching package metadata"
            ui_stage 3 5 "Downloading packages"
            ui_stage 4 5 "Verifying package integrity"
            ui_stage 5 5 "Installing packages"
            # shellcheck source=/dev/null
            . "$NX_LIB_DIR/pkg-install.sh"
            pkg_install_cmd "$name" || rc=$?
            if [ "$rc" -eq 0 ]; then
                ui_result OK "$name ${ver:-latest} installed (Noxs package registry)"
            else
                ui_result FAILED "$name — the Noxs installer reported an error"
            fi
            ;;
        *)
            pkgm_install_debian "$name" "$src" "$ver" || rc=$?
            ;;
    esac
    pkgm_lock_release
    return "$rc"
}

pkgm_install_debian() { # NAME SRC VER — native APT path, real progress.
    local name="$1" src="$2" ver="$3"
    local arch; arch=$(detect_arch)

    make_workdir   # NX_WORK + cleanup trap from pkg-lib.sh

    # [1/5] resolve — apt resolves the FULL dependency set; no signature
    # bypass: --print-uris reads the signed repository metadata.
    ui_stage 1 5 "Resolving dependencies"
    if ! pkgm_lists_ok; then
        ui_step "APT package lists are empty — updating once (bounded)"
        $NX_SUDO env DEBIAN_FRONTEND=noninteractive apt-get update -qq 2>/dev/null || true
        pkgm_lists_ok || {
            pkgm_error NETWORK_ERROR "apt-get update could not populate the package lists" \
                "check the network, then run: nx cert-fix (repairs APT + CA certificates)"
            return "$(pkgm_rc NETWORK_ERROR)"
        }
    fi
    local uris
    if ! uris=$(apt-get --print-uris -qq -y install "$name" 2>"$NX_WORK/resolve.err"); then
        head -n 3 "$NX_WORK/resolve.err" >&2
        pkgm_error DEPENDENCY_RESOLUTION_FAILED "apt could not resolve '$name'" \
            "try: nx pkg update && nx pkg install $name — or: sudo apt --fix-broken install"
        return "$(pkgm_rc DEPENDENCY_RESOLUTION_FAILED)"
    fi

    local -a U_URL=() U_FILE=() U_SIZE=() U_SHA=() U_NAME=()
    local line total_bytes=0 n=0
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        case "$line" in "'"*) ;; *) continue ;; esac
        pkgm_parse_uri "$line"
        if [ -z "$PU_URL" ] || [ -z "$PU_SHA" ]; then continue; fi
        valid_sha256 "$PU_SHA" || continue
        U_URL+=("$PU_URL"); U_FILE+=("$PU_FILE"); U_SIZE+=("${PU_SIZE:-0}")
        U_SHA+=("$PU_SHA"); U_NAME+=("$PU_NAME")
        total_bytes=$((total_bytes + ${PU_SIZE:-0}))
        n=$((n + 1))
    done <<EOF
$uris
EOF
    [ "$n" -gt 0 ] || {
        pkgm_error DEPENDENCY_RESOLUTION_FAILED "apt reported no download URIs for '$name'"
        return "$(pkgm_rc DEPENDENCY_RESOLUTION_FAILED)"
    }

    pkgm_disk_ok "$((total_bytes + total_bytes / 5))" || {
        pkgm_error INSUFFICIENT_DISK_SPACE \
            "need ~$(humanize_bytes "$total_bytes") for $n package(s); free space on / is $(df -Pk / 2>/dev/null | awk 'NR==2{printf "%.0f MB", $4*1024/1048576}')MB" \
            "free up storage (noxs storage status) and retry"
        return "$(pkgm_rc INSUFFICIENT_DISK_SPACE)"
    }

    # [2/5] metadata
    ui_stage 2 5 "Fetching package metadata"
    if [ "$PKG_MIRROR_LIVE" = "1" ]; then
        ui_step "Noxs mirror live: $PKG_MIRROR_BASE (apt metadata stays the signed official index)"
    else
        ui_step "Source: official signed APT repository metadata"
    fi

    # [3/5] download — mirror first, official fallback, sha256-keyed cache
    ui_stage 3 5 "Downloading packages"
    local k=0 hits=0 from_mirror=0 from_official=0 dest try_url hit
    while [ "$k" -lt "$n" ]; do
        k=$((k + 1))
        dest="$NX_WORK/${U_FILE[$((k-1))]}"
        hit=$(pkgm_cache_get "${U_SHA[$((k-1))]}" "$dest")
        if [ "$hit" = "hit" ]; then
            hits=$((hits + 1))
            progress_render "Downloading ${U_FILE[$((k-1))]}  [$k/$n]  cache hit" \
                "${U_SIZE[$((k-1))]}" "${U_SIZE[$((k-1))]}" 3
            progress_clear
            continue
        fi
        try_url="${U_URL[$((k-1))]}"
        if [ "$PKG_MIRROR_LIVE" = "1" ]; then
            murl=$(pkgm_mirror_url "$try_url") && try_url="$murl"
        fi
        if ! pkgm_fetch "$try_url" "$dest" "${U_SIZE[$((k-1))]}" \
                "Downloading ${U_FILE[$((k-1))]}  [$k/$n]"; then
            if [ "$try_url" != "${U_URL[$((k-1))]}" ]; then
                ui_step "mirror miss — official fallback for ${U_FILE[$((k-1))]}"
                pkgm_fetch "${U_URL[$((k-1))]}" "$dest" "${U_SIZE[$((k-1))]}" \
                    "Downloading ${U_FILE[$((k-1))]}  [$k/$n]" || {
                    ui_progress_done
                    pkgm_error DOWNLOAD_FAILED "${U_FILE[$((k-1))]}" "Retryable: yes"
                    return "$(pkgm_rc DOWNLOAD_FAILED)"
                }
                from_official=$((from_official + 1))
            else
                ui_progress_done
                pkgm_error DOWNLOAD_FAILED "${U_FILE[$((k-1))]}" "Retryable: yes"
                return "$(pkgm_rc DOWNLOAD_FAILED)"
            fi
        else
            [ "$try_url" != "${U_URL[$((k-1))]}" ] && from_mirror=$((from_mirror + 1))
        fi
    done
    ui_progress_done
    [ "$((hits + from_mirror))" -gt 0 ] && \
        ui_step "downloads: $n total — $hits cache hit(s), $from_mirror via Noxs mirror, $from_official official"

    # [4/5] verify — SHA-256 from the SIGNED apt metadata, never skipped
    ui_stage 4 5 "Verifying package integrity"
    k=0
    while [ "$k" -lt "$n" ]; do
        k=$((k + 1))
        dest="$NX_WORK/${U_FILE[$((k-1))]}"
        local actual; actual=$(sha256_of "$dest")
        if [ "$actual" != "${U_SHA[$((k-1))]}" ]; then
            rm -f "$dest" "$PKG_CACHE_DIR/deb/${U_SHA[$((k-1))]}.deb"
            pkgm_error CHECKSUM_MISMATCH "${U_FILE[$((k-1))]}" \
                "expected ${U_SHA[$((k-1))]}, got ${actual:-none} — file removed"
            return "$(pkgm_rc CHECKSUM_MISMATCH)"
        fi
        ui_step "Verifying SHA256  ${U_FILE[$((k-1))]}  [OK]"
        pkgm_cache_put "${U_SHA[$((k-1))]}" "$dest"
    done

    # [5/5] install — native apt does the real installation; dpkg database
    # consistency and the repository trust chain are preserved.
    ui_stage 5 5 "Installing packages"
    local -a args=()
    k=0
    while [ "$k" -lt "$n" ]; do
        args+=("$NX_WORK/${U_FILE[$((k-1))]}")
        k=$((k + 1))
    done
    $NX_SUDO env LC_ALL=C DEBIAN_FRONTEND=noninteractive apt-get -y install "${args[@]}" \
        > "$NX_WORK/apt.log" 2>&1 &
    local apid=$! k2=0 el0
    el0=$(date +%s)
    if [ "$PKG_UI_TTY" = "1" ]; then
        while kill -0 "$apid" 2>/dev/null; do
            sleep 0.5
            k2=$(grep -c '^Setting up' "$NX_WORK/apt.log" 2>/dev/null || echo 0)
            progress_render "Installing packages  $k2/$n" "$k2" "$n" "$(( $(date +%s) - el0 ))"
        done
        progress_clear
    fi
    wait "$apid"; rc=$?
    if [ "$rc" -ne 0 ]; then
        grep -qiE 'unauthenticated|NO_PUBKEY|signature' "$NX_WORK/apt.log" 2>/dev/null && {
            pkgm_error SIGNATURE_VERIFICATION_FAILED "apt rejected a package for '$name'" \
                "run: nx pkg update && nx cert-fix — never disable signature checks"
            return "$(pkgm_rc SIGNATURE_VERIFICATION_FAILED)"
        }
        tail -n 3 "$NX_WORK/apt.log" >&2
        pkgm_error DEPENDENCY_RESOLUTION_FAILED "apt failed to install '$name' (exit $rc)" \
            "try: sudo apt --fix-broken install, then retry"
        return "$(pkgm_rc DEPENDENCY_RESOLUTION_FAILED)"
    fi

    local src_note="official Debian repositories"
    [ "$from_mirror" -gt 0 ] && src_note="Noxs mirror ($from_mirror file(s)) + official fallback"
    [ "$hits" -gt 0 ] && [ "$from_mirror" -eq 0 ] && src_note="sha256-keyed cache ($hits hit(s))"
    ui_result OK "$name $ver $arch — $n package(s) installed via $src_note"
    return 0
}

# ----------------------------------------------------------------- update

pkgm_update_cmd() {
    ui_init
    local full=0
    [ "${1:-}" = "--full" ] && full=1
    pkgm_detect_os
    if pkgm_is_distro; then
        pkgm_busy_check || return "$(pkgm_rc PACKAGE_MANAGER_BUSY)"
        ui_stage 1 1 "Updating package indexes"
        pkgm_lock_arm
        if ! $NX_SUDO env DEBIAN_FRONTEND=noninteractive apt-get update -q; then
            pkgm_lock_release
            pkgm_error NETWORK_ERROR "apt-get update failed" "check the network or run: nx cert-fix"
            return "$(pkgm_rc NETWORK_ERROR)"
        fi
        pkgm_lock_release
        local up; up=$(apt list --upgradable 2>/dev/null | grep -c 'upgradable from')
        ui_result OK "indexes updated — $up package(s) upgradable"
        [ "$up" -gt 0 ] && ui_step "run: nx pkg update --full  (or: sudo apt full-upgrade)"
        if [ "$full" = "1" ]; then
            pkgm_busy_check || return "$(pkgm_rc PACKAGE_MANAGER_BUSY)"
            pkgm_lock_arm
            $NX_SUDO env DEBIAN_FRONTEND=noninteractive apt-get -y -q full-upgrade
            local rc=$?
            pkgm_lock_release
            [ "$rc" -eq 0 ] && ui_result OK "full upgrade completed"
            return "$rc"
        fi
    else
        ui_stage 1 1 "Updating Noxs package manifests"
        # shellcheck source=/dev/null
        . "$NX_LIB_DIR/pkg-install.sh"
        pkg_update_cmd || return $?
    fi
    return 0
}

# ------------------------------------------------------------------- list

pkgm_list_cmd() {
    ui_init
    local printed=0 name ver archn
    if [ "${NOXS_PKG_JSON:-0}" = "1" ]; then printf '['; fi
    if pkgm_detect_os && pkgm_is_distro && command -v dpkg-query >/dev/null 2>&1; then
        while IFS=$'\t' read -r name ver archn; do
            [ -n "$name" ] || continue
            case "$name" in *:*) name="${name%%:*}" ;; esac
            if [ "${NOXS_PKG_JSON:-0}" = "1" ]; then
                [ "$printed" -gt 0 ] && printf ','
                printf '{"name":"%s","source":"debian","version":"%s","arch":"%s"}\n' "$name" "$ver" "$archn"
            else
                [ "$printed" -eq 0 ] && printf '%-28s %-10s %-24s %s\n' "NAME" "SOURCE" "VERSION" "ARCH"
                printf '%-28s %-10s %-24s %s\n' "$name" "debian" "$ver" "$archn"
            fi
            printed=$((printed + 1))
        done < <(dpkg-query -W -f='${binary:Package}\t${Version}\t${Architecture}\n' 2>/dev/null)
    fi
    local f
    for f in "${NX_STATE_DIR:-/var/lib/noxs-pkg}"/installed/*.json; do
        [ -f "$f" ] || continue
        name=$(sed -n 's/.*"name" *: *"\([^"]*\)".*/\1/p' "$f" | head -n 1)
        ver=$(sed -n 's/.*"version" *: *"\([^"]*\)".*/\1/p' "$f" | head -n 1)
        archn=$(sed -n 's/.*"arch" *: *"\([^"]*\)".*/\1/p' "$f" | head -n 1)
        [ -n "$name" ] || continue
        if [ "${NOXS_PKG_JSON:-0}" = "1" ]; then
            [ "$printed" -gt 0 ] && printf ','
            printf '{"name":"%s","source":"noxs","version":"%s","arch":"%s"}\n' "$name" "$ver" "$archn"
        else
            [ "$printed" -eq 0 ] && printf '%-28s %-10s %-24s %s\n' "NAME" "SOURCE" "VERSION" "ARCH"
            printf '%-28s %-10s %-24s %s\n' "$name" "noxs" "$ver" "${archn:--}"
        fi
        printed=$((printed + 1))
    done
    if [ "${NOXS_PKG_JSON:-0}" = "1" ]; then
        printf ']\n'
    elif [ "$printed" -eq 0 ]; then
        msg "no packages installed"
    fi
    return 0
}

# ----------------------------------------------------------------- remove

pkgm_remove_cmd() {
    ui_init
    local name="${1:-}" assume=0
    while [ $# -gt 0 ]; do
        case "$1" in
            -y|--yes) assume=1 ;;
            *) name="$1" ;;
        esac
        shift
    done
    [ -n "$name" ] || { err "usage: nx pkg remove <name> [-y]"; return 2; }
    pkgm_detect_os
    if pkgm_is_distro && dpkg-query -W "$name" >/dev/null 2>&1; then
        pkgm_busy_check || return "$(pkgm_rc PACKAGE_MANAGER_BUSY)"
        if [ "$assume" != "1" ] && [ "$PKG_UI_TTY" = "1" ]; then
            printf 'Remove Debian package %s? [y/N] ' "$name"
            local a; read -r a
            case "$a" in y|Y|yes) ;; *) msg "aborted"; return 0 ;; esac
        fi
        pkgm_lock_arm
        $NX_SUDO env DEBIAN_FRONTEND=noninteractive apt-get -y -qq remove "$name"
        local rc=$?
        pkgm_lock_release
        [ "$rc" -eq 0 ] && ui_result OK "$name removed (Debian package database updated by apt)"
        return "$rc"
    fi
    if manifest_exists "$name" 2>/dev/null; then
        # shellcheck source=/dev/null
        . "$NX_LIB_DIR/pkg-install.sh"
        pkg_remove_cmd "$name"
        return $?
    fi
    pkgm_error PACKAGE_NOT_FOUND "$name is not installed (checked dpkg + Noxs manifests)"
    return "$(pkgm_rc PACKAGE_NOT_FOUND)"
}

# ----------------------------------------------------------------- doctor

pkgm_doctor_cmd() {
    ui_init
    local fails=0 warns=0 line
    say() { printf '%s\n' "$*"; }
    pkgm_detect_os
    if pkgm_is_distro; then
        say "PASS  OS: $PKG_OS, package manager: $PKG_PM"
    elif [ "$PKG_OS" = "unknown" ]; then
        say "WARN  OS not recognized ($PKG_OS_RELEASE) — only Noxs-registry packages are supported"
        warns=$((warns + 1))
    else
        say "PASS  OS: $PKG_OS (no native apt — Noxs packages only)"
    fi
    if pkgm_is_distro; then
        if pkgm_lists_ok; then
            say "PASS  APT package lists populated"
        else
            say "FAIL  APT package lists empty — run: nx pkg update"
            fails=$((fails + 1))
        fi
        if [ -s /etc/ssl/certs/ca-certificates.crt ]; then
            say "PASS  CA certificate bundle present"
        else
            say "FAIL  CA certificate bundle missing — run: nx cert-fix"
            fails=$((fails + 1))
        fi
        if dpkg --audit 2>/dev/null | grep -q .; then
            say "WARN  dpkg reports pending configuration — run: sudo dpkg --configure -a"
            warns=$((warns + 1))
        else
            say "PASS  dpkg database consistent"
        fi
    fi
    if command -v curl >/dev/null 2>&1 || command -v wget >/dev/null 2>&1; then
        say "PASS  download tool available (curl/wget)"
    else
        say "FAIL  neither curl nor wget available — run: nx cert-fix"
        fails=$((fails + 1))
    fi
    local avail; avail=$(df -Pk / 2>/dev/null | awk 'NR==2{print $4*1024}')
    case "$avail" in
        ''|*[!0-9]*) say "WARN  free disk space unknown" ;;
        *) if [ "$avail" -ge 104857600 ]; then
               say "PASS  free disk space: $(humanize_bytes "$avail")"
           else
               say "WARN  free disk space low: $(humanize_bytes "$avail")"
               warns=$((warns + 1))
           fi ;;
    esac
    local st; st=$(mktemp)
    if pkgm_mirror_status "$st" >/dev/null 2>&1 && [ -s "$st" ]; then
        say "PASS  Noxs mirror reachable: $PKG_MIRROR_BASE"
    else
        if [ "$PKG_MIRROR_BASE" = "off" ]; then
            say "PASS  mirror disabled (NOXS_PKG_MIRROR=off) — official sources only"
        else
            say "WARN  Noxs mirror unreachable — official sources will be used"
            warns=$((warns + 1))
        fi
    fi
    rm -f "$st"
    if [ -f "$PKG_TX_DIR/pkg-tx" ]; then
        say "WARN  package transaction flag present ($PKG_TX_DIR/pkg-tx) — a package operation may be running"
        warns=$((warns + 1))
    fi
    if mkdir -p "$PKG_CACHE_DIR" 2>/dev/null; then
        say "PASS  package cache writable: $PKG_CACHE_DIR"
    else
        say "WARN  package cache not writable: $PKG_CACHE_DIR (cache disabled)"
        warns=$((warns + 1))
    fi
    if [ "$fails" -gt 0 ]; then
        ui_result "FAIL" "$fails problem(s), $warns warning(s) — fix the FAIL lines above"
        return 1
    fi
    ui_result OK "$warns warning(s), no problems"
    return 0
}
