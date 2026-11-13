# pkg-install.sh — nx install / list / info / update / remove.
# Sourced by nx after pkg-lib.sh. shell=bash
# shellcheck shell=bash

pkg_install_usage() {
    cat <<'EOF'
usage: nx install <package>[@version]
       nx install -git @username/repo[@version]
EOF
}

# install_one OWNER REPO DISPLAY REQUESTED_VERSION
# REQUESTED_VERSION empty = latest. Performs the full $17 flow.
install_one() {
    local owner="$1" repo="$2" display="$3" requested="$4"
    local ver tag arch vindex asset expected url

    make_workdir
    stage "Resolving package..."
    if ! registry_fetch "$owner" "$repo" "$NX_WORK/registry.json"; then
        if [ "${RF_CODE:-}" = "404" ]; then
            # 77 = the package does not exist in the Git registry — the nx
            # dispatcher uses this to fall back to the distro/mirror
            # installer (nx install curl -> nx pkg install curl).
            err "Package not found in the Noxs registry: $display"
            return 77
        fi
        err "Could not reach the package registry. Check your connection and try again."
        return 78
    fi
    registry_parse "$NX_WORK/registry.json"
    [ "$RG_NAME" = "$repo" ] || die "Invalid registry.json: package name does not match the repository."

    if [ -n "$requested" ]; then
        registry_find_version "$requested" || die "Package version not found:
$display@$requested"
    else
        registry_find_version "$RG_LATEST" || die "Invalid registry.json: latest version is not listed."
    fi
    vindex="$RV_INDEX"
    local vvar="RG_VER_$vindex" tvar="RG_TAG_$vindex"
    ver="${!vvar}"
    tag="${!tvar}"

    arch=$(detect_arch)
    [ -n "$arch" ] || die "Unsupported architecture: $(uname -m)"
    registry_pick_asset "$vindex" "$arch" || die "No compatible release found for $display@$ver ($arch)."
    asset="$RA_NAME"
    expected="$RA_SHA"

    # Download (streaming, retried, resumable) from the deterministic
    # release URL built from validated fields only.
    stage "Downloading $repo $ver ($arch)..."
    url=$(registry_asset_url "$owner" "$repo" "$tag" "$asset")
    http_fetch "$url" "$NX_WORK/$asset" resume || die "Download failed."

    # The published sidecar (when present) must agree with registry.json.
    local side="$NX_WORK/$asset.sha256" code2 side_hash=""
    if code2=$(http_fetch_code "$url.sha256" "$side"); then
        if [ "$code2" = "200" ]; then
            side_hash=$(awk 'NR==1{print $1}' "$side" 2>/dev/null)
            [ -n "$side_hash" ] || die "Invalid registry.json: empty checksum sidecar."
            [ "$side_hash" = "$expected" ] || die "Invalid registry.json: checksum does not match the published sidecar."
        fi
    fi

    verify_sha256 "$NX_WORK/$asset" "$expected"   # fatal on any mismatch
    check_tarball_members "$NX_WORK/$asset"        # fatal on any escape risk

    # --- extraction (root only; the environment root is the install scope)
    stage "Installing $repo $ver..."
    if [ "$(id -u)" = "0" ]; then
        tar -xJf "$NX_WORK/$asset" -C / --no-same-owner || die "Package installation failed (extraction)."
    elif [ -n "$NX_SUDO" ]; then
        $NX_SUDO tar -xJf "$NX_WORK/$asset" -C / --no-same-owner || die "Package installation failed (extraction)."
    else
        die "Installing requires root privileges and sudo was not found. Try: su -c 'nx install $display'"
    fi

    # --- installed-package manifest (drives info/update/remove)
    local members manifest
    local flist=() f
    members=$(tar_members "$NX_WORK/$asset") || members=""
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        f="${f%% -> *}"
        case "$f" in */|""|*..*|/*) continue ;; esac
        flist+=("$f")
    done <<< "$members"
    manifest="$NX_WORK/$repo.json"
    manifest_write "$manifest" "$repo" "$ver" "$arch" "$owner/$repo" \
        "$(registry_raw_url "$owner" "$repo")" "${flist[@]}"
    if [ "$(id -u)" = "0" ]; then
        mkdir -p "$NX_STATE_DIR/installed" || die "Cannot record the installation."
        cp "$manifest" "$(manifest_path "$repo")" || die "Cannot record the installation."
    else
        $NX_SUDO mkdir -p "$NX_STATE_DIR/installed" || die "Cannot record the installation."
        $NX_SUDO cp "$manifest" "$(manifest_path "$repo")" || die "Cannot record the installation."
    fi

    if [ -x "/usr/local/bin/$repo" ]; then
        msg "Installed $repo $ver ($arch). Run: $repo"
    else
        msg "Installed $repo $ver ($arch)."
    fi
}

pkg_install_cmd() {
    local mode="official" spec="" ver=""
    if [ "${1:-}" = "-git" ]; then
        mode="git"
        shift
        [ $# -ge 1 ] || { err "usage: nx install -git @username/repo[@version]"; return 2; }
        spec="$1"
        spec="${spec#@}"                     # tolerate both @user/repo and user/repo
    else
        [ $# -ge 1 ] || { pkg_install_usage; return 2; }
        spec="$1"
    fi
    # Split off a pinned version (the last @).
    case "$spec" in
        *@*)
            ver="${spec##*@}"
            spec="${spec%@*}"
            ;;
    esac
    [ -n "$spec" ] || { err "Malformed package specification."; pkg_install_usage; return 2; }
    [ -z "$ver" ] || valid_version "$ver" || die "Invalid package version: $ver (expected MAJOR.MINOR.PATCH)"

    NX_INSTALL_MODE="$mode" resolve_source "$spec" || return 2
    ensure_download_tools
    install_one "$SS_OWNER" "$SS_REPO" "$SS_DISPLAY" "$ver"
}

pkg_list_cmd() {
    local dir="$NX_STATE_DIR/installed"
    [ -d "$dir" ] || { msg "No packages installed."; return 0; }
    local m name ver arch src found=0
    for m in "$dir"/*.json; do
        [ -f "$m" ] || continue
        if [ "$found" -eq 0 ]; then
            printf '%-18s %-10s %-10s %s\n' "PACKAGE" "VERSION" "ARCH" "SOURCE"
            found=1
        fi
        name=$(manifest_field "$m" name)
        ver=$(manifest_field "$m" version)
        arch=$(manifest_field "$m" architecture)
        src=$(manifest_field "$m" source)
        printf '%-18s %-10s %-10s %s\n' "$name" "${ver:-?}" "${arch:-?}" "${src:-?}"
    done
    [ "$found" -eq 1 ] || msg "No packages installed."
    return 0
}

pkg_info_cmd() {
    [ $# -ge 1 ] || { err "usage: nx info <package>"; return 2; }
    local name="$1" mpath ver arch src owner repo latest
    if manifest_exists "$name"; then
        mpath=$(manifest_path "$name")
        ver=$(manifest_field "$mpath" version)
        arch=$(manifest_field "$mpath" architecture)
        src=$(manifest_field "$mpath" source)
        latest="unknown (offline)"
        if command -v curl >/dev/null 2>&1 || command -v wget >/dev/null 2>&1; then
            owner="${src%%/*}"
            repo="${src#*/}"
            if [ -n "$repo" ] && [ "$owner" != "$src" ]; then
                local tmp
                tmp="$(mktemp)"
                if registry_fetch "$owner" "$repo" "$tmp"; then
                    if registry_parse "$tmp" 2>/dev/null; then
                        latest="$RG_LATEST"
                    fi
                fi
                rm -f "$tmp"
            fi
        fi
        echo "Package: $name"
        echo "Installed: ${ver:-?}"
        echo "Latest: $latest"
        echo "Architecture: ${arch:-?}"
        echo "Source: GitHub"
        return 0
    fi
    # Not installed: fall back to live registry metadata.
    pkg_registry_info_cmd "$name"
}

pkg_update_cmd() {
    local targets=() m name
    if [ $# -ge 1 ]; then
        targets=("$@")
    else
        local dir="$NX_STATE_DIR/installed"
        [ -d "$dir" ] || { msg "No packages installed."; return 0; }
        for m in "$dir"/*.json; do
            [ -f "$m" ] || continue
            name="${m##*/}"
            targets+=("${name%.json}")
        done
    fi
    [ ${#targets[@]} -gt 0 ] || { msg "No packages installed."; return 0; }

    ensure_download_tools
    local t ver src owner repo tmp rc=0
    for t in "${targets[@]}"; do
        if ! manifest_exists "$t"; then
            err "Package not installed: $t"
            rc=1
            continue
        fi
        ver=$(manifest_field "$(manifest_path "$t")" version)
        src=$(manifest_field "$(manifest_path "$t")" source)
        owner="${src%%/*}"
        repo="${src#*/}"
        if [ -z "$repo" ] || [ "$owner" = "$src" ]; then
            err "Cannot update $t (unknown source)."
            rc=1
            continue
        fi
        tmp="$(mktemp)"
        if ! registry_fetch "$owner" "$repo" "$tmp"; then
            err "Could not check $t for updates (registry unreachable)."
            rm -f "$tmp"
            rc=1
            continue
        fi
        if registry_parse "$tmp" 2>/dev/null; then
            if semver_gt "$RG_LATEST" "${ver:-0.0.0}"; then
                stage "Updating $t: $ver -> $RG_LATEST"
                install_one "$owner" "$repo" "$t" "$RG_LATEST" || rc=1
            else
                msg "$t ${ver:-?} is up to date."
            fi
        else
            err "Invalid registry.json for $t — update skipped."
            rm -f "$tmp"
            rc=1
            continue
        fi
        rm -f "$tmp"
    done
    return $rc
}

pkg_remove_cmd() {
    [ $# -ge 1 ] || { err "usage: nx remove <package>"; return 2; }
    local name="$1" mpath ver arch reply f files unsafe=0 rm_failed=0 d
    manifest_exists "$name" || die "Package not installed: $name"
    mpath=$(manifest_path "$name")
    ver=$(manifest_field "$mpath" version)
    arch=$(manifest_field "$mpath" architecture)

    if [ -t 0 ]; then
        printf 'nx: Remove %s %s? [y/N] ' "$name" "${ver:-}"
        IFS= read -r reply || reply=""
        case "$reply" in
            y|Y|yes|Yes) : ;;
            *) msg "Cancelled — $name was not removed."; return 0 ;;
        esac
    fi

    stage "Removing $name ${ver:-}..."
    files=$(sed -n 's/^    "\(.*\)",*$/\1/p' "$mpath")
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        case "$f" in
            /*|*..*) err "Manifest contains an unsafe path: $f"; unsafe=1 ;;
            usr/*|opt/*) : ;;
            *) err "Manifest contains a path outside the package scope: $f"; unsafe=1 ;;
        esac
    done <<< "$files"
    [ "$unsafe" -eq 0 ] || die "Removal aborted — the manifest failed the safety check."

    while IFS= read -r f; do
        [ -n "$f" ] || continue
        if [ "$(id -u)" = "0" ]; then
            rm -f -- "$f" || rm_failed=1
        elif [ -n "$NX_SUDO" ]; then
            $NX_SUDO rm -f -- "$f" || rm_failed=1
        else
            die "Removing requires root privileges and sudo was not found."
        fi
    done <<< "$files"
    if [ "$rm_failed" -ne 0 ]; then
        err "Some files could not be removed — the manifest entry was kept so you can retry."
        return 1
    fi

    # Prune directories that this package emptied (never usr, usr/local, opt).
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        d="$f"
        while :; do
            d="${d%/*}"
            case "$d" in
                ""|usr|opt|usr/local|*..*) break ;;
            esac
            if [ "$(id -u)" = "0" ]; then
                rmdir "$d" 2>/dev/null || break
            elif [ -n "$NX_SUDO" ]; then
                $NX_SUDO rmdir "$d" 2>/dev/null || break
            else
                break
            fi
        done
    done <<< "$files"

    if [ "$(id -u)" = "0" ]; then
        rm -f "$mpath"
    elif [ -n "$NX_SUDO" ]; then
        $NX_SUDO rm -f "$mpath"
    fi
    msg "Removed $name (was ${ver:-}). This does not remove Noxs itself."
    return 0
}
