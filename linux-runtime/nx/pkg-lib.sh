# pkg-lib.sh — shared helpers for the NX package system.
# Sourced by nx; never executed directly. No side effects at source time.
# shellcheck shell=bash

# Re-entry guard: modules source this lib freely.
if [ -n "${NX_LIB_LOADED:-}" ]; then
    return 0
fi
NX_LIB_LOADED=1

msg()      { printf 'nx: %s\n' "$*"; }
err()      { printf 'nx: %s\n' "$*" >&2; }
die()      { err "$*"; exit 1; }
stage()    { printf '\033[1;36mnx:\033[0m %s\n' "$*"; }   # stage headline ($24 UX)

NX_RE_NAME='^[a-z0-9][a-z0-9._-]*$'
NX_RE_VERSION='^[0-9]+[.][0-9]+[.][0-9]+$'
NX_RE_REPO='^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$'
NX_RE_SHA='^[0-9a-fA-F]{64}$'

valid_name()    { printf '%s' "$1" | grep -qE "$NX_RE_NAME"; }
valid_version() { printf '%s' "$1" | grep -qE "$NX_RE_VERSION"; }
valid_repo()    { printf '%s' "$1" | grep -qE "$NX_RE_REPO"; }
valid_sha256()  { printf '%s' "$1" | grep -qE "$NX_RE_SHA"; }

# ---------------------------------------------------------------- toolchain

if [ "$(id -u)" = "0" ]; then
    NX_SUDO=""
elif command -v sudo >/dev/null 2>&1; then
    NX_SUDO="sudo"
else
    NX_SUDO=""
fi

detect_arch() {
    case "$(uname -m)" in
        aarch64|arm64)       echo "aarch64" ;;
        x86_64|amd64)        echo "x86_64" ;;
        armv7l|armv8l|armv7) echo "armv7" ;;
        *)                   echo "" ;;
    esac
}

# semver_gt A B -> return 0 when A > B (numeric MAJOR.MINOR.PATCH compare)
semver_gt() {
    local a1 a2 a3 b1 b2 b3
    IFS=. read -r a1 a2 a3 <<< "$1"
    IFS=. read -r b1 b2 b3 <<< "$2"
    nx_num() {
        local v="$1"
        # shellcheck disable=SC2295  # nested expansion is intentional
        v="${v#${v%%[!0]*}}"   # strip every leading zero
        [ -n "$v" ] || v=0
        printf '%s' "$v"
    }
    a1=$(nx_num "${a1:-0}"); a2=$(nx_num "${a2:-0}"); a3=$(nx_num "${a3:-0}")
    b1=$(nx_num "${b1:-0}"); b2=$(nx_num "${b2:-0}"); b3=$(nx_num "${b3:-0}")
    if   [ "$a1" -gt "$b1" ]; then return 0
    elif [ "$a1" -lt "$b1" ]; then return 1
    fi
    if   [ "$a2" -gt "$b2" ]; then return 0
    elif [ "$a2" -lt "$b2" ]; then return 1
    fi
    [ "$a3" -gt "$b3" ]
}

# ------------------------------------------------------------- network I/O

ensure_download_tools() {
    if command -v curl >/dev/null 2>&1 || command -v wget >/dev/null 2>&1; then
        return 0
    fi
    stage "Installing dependencies (download tools)..."
    if command -v apt-get >/dev/null 2>&1; then
        $NX_SUDO apt-get update -qq || die "apt-get update failed; install curl manually."
        $NX_SUDO apt-get install -y --no-install-recommends curl ca-certificates xz-utils \
            || die "Could not install curl. Install it manually: sudo apt install curl"
    elif command -v pacman >/dev/null 2>&1; then
        $NX_SUDO pacman -Sy --noconfirm curl ca-certificates xz \
            || die "Could not install curl. Install it manually: sudo pacman -S curl"
    else
        die "curl or wget is required for downloads, and no supported package manager was found."
    fi
    command -v curl >/dev/null 2>&1 || command -v wget >/dev/null 2>&1 \
        || die "No download tool available after installation."
}

# http_fetch URL OUT [RESUME] — streaming download with retries and progress.
http_fetch() {
    local url="$1" out="$2" resume="${3:-}" attempt rc
    local extra=()
    if [ "$resume" = "resume" ] && [ -s "$out" ]; then
        extra=(-C -)
    fi
    for attempt in 1 2 3; do
        if command -v curl >/dev/null 2>&1; then
            curl -fL --retry 2 --retry-delay 2 --connect-timeout 20 \
                --progress-bar "${extra[@]}" -o "$out" "$url"
            rc=$?
        elif command -v wget >/dev/null 2>&1; then
            if [ "${extra[*]:-}" != "" ]; then
                wget -c --progress=bar:force -O "$out" "$url"; rc=$?
            else
                wget --progress=bar:force -O "$out" "$url"; rc=$?
            fi
        else
            die "Neither curl nor wget is available."
        fi
        [ "$rc" -eq 0 ] && return 0
        err "Download failed (attempt $attempt/3)."
        sleep 2
    done
    return 1
}

# http_fetch_code URL OUT -> echoes "code:<http code>"; small metadata fetch.
http_fetch_code() {
    local url="$1" out="$2" rc=0 body=""
    if command -v curl >/dev/null 2>&1; then
        body=$(curl -fsSL --retry 2 --retry-delay 2 --connect-timeout 20 \
            -w '%{http_code}' -o "$out" "$url") && rc=0 || rc=$?
        if [ "$rc" -ne 0 ]; then
            case "$body" in
                404) echo "404"; return 0 ;;
                *)   return 1 ;;
            esac
        fi
        echo "200"
        return 0
    fi
    if command -v wget >/dev/null 2>&1; then
        if wget -q -O "$out" "$url"; then
            echo "200"; return 0
        fi
        # Distinguish 404 from transport failures where possible.
        if wget -q --spider "$url" 2>/dev/null; then
            echo "200"; return 0
        fi
        return 1
    fi
    die "Neither curl nor wget is available."
}

sha256_of() {
    sha256sum "$1" 2>/dev/null | awk '{print $1}'
}

# verify_sha256 FILE EXPECTED — fatal on any mismatch (never bypassed).
verify_sha256() {
    local actual expected="$2"
    [ -f "$1" ] || die "Package artifact is missing; checksum verification failed."
    valid_sha256 "$expected" || die "Invalid registry.json: checksum field is not a sha256 digest."
    actual=$(sha256_of "$1")
    [ "$actual" = "$expected" ] || die "Package checksum verification failed."
}

# --------------------------------------------------------- archive safety

# tar_members FILE — print archive members (verbose listing so symlink
# targets are visible; the metadata prefix is stripped).
tar_members() {
    tar -tvJf "$1" | sed -E 's/^([^ ]+[ ]+){5}//'
}

# check_tarball_members FILE — reject any member that could escape the
# install boundary: absolute paths, `..` traversal, or absolute symlink
# targets. Extraction itself happens at / so relative members stay inside
# the environment root.
check_tarball_members() {
    local member path target list unsafe=0
    list=$(tar_members "$1") || die "Package archive is unreadable."
    while IFS= read -r member; do
        [ -n "$member" ] || continue
        path="${member%% -> *}"
        case "$path" in
            /*) err "Package archive contains an absolute path: $path"; unsafe=1 ;;
            *..*) err "Package archive contains an unsafe path: $path"; unsafe=1 ;;
        esac
        case "$member" in
            *" -> "*)
                target="${member#* -> }"
                case "$target" in
                    /*) err "Package archive contains an absolute symlink: $member"; unsafe=1 ;;
                esac
                ;;
        esac
    done <<< "$list"
    [ "$unsafe" -eq 0 ] || die "Package archive is not safe to install."
}

# ---------------------------------------------------------- registry.json

# registry_parse FILE — strict line-based parser for the canonical
# registry.json layout written by the pkg.yml workflow. Sets:
#   RG_NAME RG_DESC RG_LATEST RG_COUNT
#   RG_VER_<i> RG_TAG_<i> RG_FILECOUNT_<i>
#   RG_FILE_<i>_<j> RG_SHA_<i>_<j>
# Anything that does not match the canonical layout is rejected as
# "Invalid registry.json." — metadata is data, never code.
registry_parse() {
    local file="$1" line key val vi=-1 fi=0 in_files=0
    [ -s "$file" ] || die "Invalid registry.json: file is empty."
    RG_NAME="" RG_DESC="" RG_LATEST="" RG_COUNT=0
    while IFS= read -r line; do
        case "$line" in
            '  "name": '*|'  "description": '*|'  "latest": '*)
                key="${line%%: *}"; key="${key// /}"; key="${key#\"}"; key="${key%\"}"
                val="${line#*: }"; val="${val%,}"; val="${val#\"}"; val="${val%\"}"
                case "$key" in
                    name)        RG_NAME="$val" ;;
                    description) RG_DESC="$val" ;;
                    latest)      RG_LATEST="$val" ;;
                esac
                ;;
            '    {'|'    },'|'    }')
                :  # version object boundaries (top level of versions[])
                ;;
            '      "version": '*)
                val="${line#*: }"; val="${val%,}"; val="${val#\"}"; val="${val%\"}"
                vi=$((vi + 1)); fi=0; in_files=0
                printf -v "RG_VER_$vi" '%s' "$val"
                printf -v "RG_FILECOUNT_$vi" '%s' "0"
                ;;
            '      "tag": '*)
                val="${line#*: }"; val="${val%,}"; val="${val#\"}"; val="${val%\"}"
                printf -v "RG_TAG_$vi" '%s' "$val"
                ;;
            '      "files": ['*)
                in_files=1
                ;;
            '        {')
                if [ "$in_files" -eq 1 ] && [ "$vi" -ge 0 ]; then
                    fi=$((fi + 1))
                    printf -v "RG_FILECOUNT_$vi" '%s' "$fi"
                    printf -v "RG_FILE_${vi}_$fi" '%s' ""
                    printf -v "RG_SHA_${vi}_$fi" '%s' ""
                fi
                ;;
            '          "name": '*)
                val="${line#*: }"; val="${val%,}"; val="${val#\"}"; val="${val%\"}"
                printf -v "RG_FILE_${vi}_$fi" '%s' "$val"
                ;;
            '          "sha256": '*)
                val="${line#*: }"; val="${val%,}"; val="${val#\"}"; val="${val%\"}"
                printf -v "RG_SHA_${vi}_$fi" '%s' "$val"
                ;;
        esac
    done < "$file"

    RG_COUNT=$((vi + 1))
    [ "$RG_COUNT" -gt 0 ] || die "Invalid registry.json: no versions found."
    valid_name "$RG_NAME" || die "Invalid registry.json: package name."
    valid_version "$RG_LATEST" || die "Invalid registry.json: latest version."
    local i j fc fvar svar f s tag_expected
    for ((i = 0; i < RG_COUNT; i++)); do
        fvar="RG_VER_$i"; s="${!fvar}"
        valid_version "$s" || die "Invalid registry.json: version entry $s."
        fvar="RG_VER_$i"
        tag_expected="$RG_NAME-v${!fvar}"
        fvar="RG_TAG_$i"; s="${!fvar}"
        [ "$s" = "$tag_expected" ] || die "Invalid registry.json: release tag does not match the package."
        fvar="RG_FILECOUNT_$i"; fc="${!fvar}"
        for ((j = 1; j <= fc; j++)); do
            fvar="RG_FILE_${i}_$j"; f="${!fvar}"
            svar="RG_SHA_${i}_$j"; s="${!svar}"
            [ -n "$f" ] || die "Invalid registry.json: asset name."
            valid_sha256 "$s" || die "Invalid registry.json: checksum for $f."
        done
    done
}

# registry_find_version V -> sets RV_INDEX, or returns 1 when absent.
registry_find_version() {
    local i vvar
    for ((i = 0; i < RG_COUNT; i++)); do
        vvar="RG_VER_$i"
        [ "${!vvar}" = "$1" ] && { RV_INDEX="$i"; return 0; }
    done
    return 1
}

# registry_pick_asset INDEX ARCH -> sets RA_NAME RA_SHA (arch first, then
# universal). Asset names are matched against locally-validated patterns;
# registry-provided names are never used in URLs directly.
registry_pick_asset() {
    local idx="$1" arch="$2" i fc j fvar svar name want
    fvar="RG_FILECOUNT_$idx"; fc="${!fvar}"
    for want in "$arch" "universal"; do
        for ((j = 1; j <= fc; j++)); do
            fvar="RG_FILE_${idx}_$j"; name="${!fvar}"
            case "$name" in
                *.nx.pkg.*."$want".tar.xz)
                    svar="RG_SHA_${idx}_$j"
                    # exported to the caller (install_one)
                    # shellcheck disable=SC2034
                    RA_NAME="$name"
                    # shellcheck disable=SC2034
                    RA_SHA="${!svar}"
                    return 0
                    ;;
            esac
        done
    done
    return 1
}

# registry_asset_url OWNER REPO TAG ASSET
registry_asset_url() {
    echo "https://github.com/$1/$2/releases/download/$3/$4"
}

# registry_raw_url OWNER REPO
registry_raw_url() {
    echo "https://raw.githubusercontent.com/$1/$2/HEAD/registry.json"
}

# registry_fetch OWNER REPO OUTFILE -> 0 on success; sets RF_CODE (200/404).
registry_fetch() {
    RF_CODE=$(http_fetch_code "$(registry_raw_url "$1" "$2")" "$3") || return 1
    [ "$RF_CODE" = "200" ]
}

# resolve_source SPEC — accept a package name (official noxs-pkg registry)
# or a "user/repo" pair for -git installs. NX_INSTALL_MODE selects the kind.
# Sets SS_OWNER SS_REPO SS_KIND SS_DISPLAY. Never trusts unvalidated input.
resolve_source() {
    local spec="$1"
    if [ "${NX_INSTALL_MODE:-}" = "git" ]; then
        valid_repo "$spec" || { err "Invalid repository: $spec (expected username/repo)"; return 2; }
        SS_OWNER="${spec%%/*}"
        SS_REPO="${spec#*/}"
        # exported to the caller
        # shellcheck disable=SC2034
        SS_KIND="git"
        # shellcheck disable=SC2034
        SS_DISPLAY="@$spec"
    else
        valid_name "$spec" || { err "Invalid package name: $spec"; return 2; }
        SS_OWNER="${NX_OFFICIAL_ORG:-noxs-pkg}"
        SS_REPO="$spec"
        # exported to the caller
        # shellcheck disable=SC2034
        SS_KIND="official"
        # shellcheck disable=SC2034
        SS_DISPLAY="$spec"
    fi
    return 0
}

# ----------------------------------------------------- installed manifests

# manifest_path NAME
manifest_path() {
    echo "${NX_STATE_DIR:-/var/lib/noxs-pkg}/installed/$1.json"
}

# manifest_exists NAME
manifest_exists() {
    [ -f "$(manifest_path "$1")" ]
}

# manifest_field FILE KEY — read one string field from a manifest/registry.
manifest_field() {
    sed -n "s/^  \"$2\": \"\(.*\)\",$/\1/p; s/^  \"$2\": \"\(.*\)\"$/\1/p" "$1" 2>/dev/null | head -1
}

# pkg_registry_info_cmd SPEC — `nx pkg info <name>` and the fallback path of
# `nx info <name>`: print metadata straight from the package registry.
pkg_registry_info_cmd() {
    [ $# -ge 1 ] || { err "usage: nx pkg info <package-name>"; return 2; }
    local spec="$1" owner repo url code tmp
    resolve_source "$spec" || return 2
    owner="$SS_OWNER"; repo="$SS_REPO"
    ensure_download_tools
    tmp="$(mktemp)"
    url=$(registry_raw_url "$owner" "$repo")
    code=$(http_fetch_code "$url" "$tmp") || {
        rm -f "$tmp"
        die "Could not reach the package registry for $owner/$repo."
    }
    if [ "$code" = "404" ]; then
        rm -f "$tmp"
        die "Package not found: $spec"
    fi
    registry_parse "$tmp"
    rm -f "$tmp"
    local i j fc fvar arch_seen archs="" f
    echo "Package:     $RG_NAME"
    [ -n "$RG_DESC" ] && echo "Description: $RG_DESC"
    echo "Latest:      $RG_LATEST"
    printf 'Versions:    '
    for ((i = 0; i < RG_COUNT; i++)); do
        fvar="RG_VER_$i"
        [ "$i" -eq 0 ] && printf '%s' "${!fvar}" || printf ', %s' "${!fvar}"
    done
    echo
    if registry_find_version "$RG_LATEST"; then
        fvar="RG_FILECOUNT_$RV_INDEX"; fc="${!fvar}"
        for ((j = 1; j <= fc; j++)); do
            fvar="RG_FILE_${RV_INDEX}_$j"; f="${!fvar}"
            case "$f" in
                *.tar.xz)
                    arch_seen="${f%.tar.xz}"
                    arch_seen="${arch_seen##*.}"
                    ;;
                *) continue ;;
            esac
            case " $archs" in *" $arch_seen "*) : ;; *) archs="$archs $arch_seen " ;; esac
        done
        echo "Architectures ($RG_LATEST):$archs"
    fi
    echo "Install:     nx install $RG_NAME@$RG_LATEST"
    return 0
}

# manifest_write OUT NAME VERSION ARCH SOURCE REGISTRY FILES...
manifest_write() {
    local out="$1" name="$2" ver="$3" arch="$4" src="$5" reg="$6"
    shift 6
    mkdir -p "${out%/*}" 2>/dev/null
    local now
    now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    {
        printf '{\n'
        printf '  "name": "%s",\n' "$name"
        printf '  "version": "%s",\n' "$ver"
        printf '  "architecture": "%s",\n' "$arch"
        printf '  "source": "%s",\n' "$src"
        printf '  "registry": "%s",\n' "$reg"
        printf '  "installedAt": "%s",\n' "$now"
        printf '  "files": ['
        local first=1 f
        for f in "$@"; do
            case "$f" in */|""|*..*|/*) continue ;; esac
            if [ "$first" -eq 1 ]; then
                printf '\n    "%s"' "$f"
                first=0
            else
                printf ',\n    "%s"' "$f"
            fi
        done
        if [ "$first" -eq 1 ]; then
            printf ']\n'
        else
            printf '\n  ]\n'
        fi
        printf '}\n'
    } > "$out"
}

# make_workdir — guarded temp dir; cleaned on exit/interrupt (never leaves
# corrupt temporary files behind).
make_workdir() {
    NX_WORK="$(mktemp -d "${TMPDIR:-/tmp}/nx-pkg.XXXXXX")" || die "Cannot create a temporary directory."
    # invoked via the traps below
    # shellcheck disable=SC2317,SC2329
    nx_cleanup() { rm -rf "${NX_WORK:-}"; }
    trap nx_cleanup EXIT
    trap 'nx_cleanup; exit 130' INT
    trap 'nx_cleanup; exit 143' TERM
}
