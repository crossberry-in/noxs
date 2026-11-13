# plug-lib.sh — nx plug support (Noxs Plugin Store CLI)
# shellcheck shell=bash
# Sourced by the nx dispatcher. Needs only coreutils (awk/cut/grep/sed).

NX_PLUG_HOST="${NX_PLUG_HOST:-/var/run/noxs/host/plugin}"

# Self-contained messaging (the dispatcher usually defines these already).
type nx_err >/dev/null 2>&1 || nx_err() { printf 'nx: %s\n' "$*" >&2; }

nx_plug_usage() {
    cat <<'EOF'
usage: nx plug                     Open the Noxs Plugin Store
       nx plug list                List installed plugins
       nx plug search <query>      Search the Plugin Store registry
       nx plug info <plugin-id>    Show plugin metadata
       nx plug install <id>        Install a plugin
       nx plug update <id>         Update an installed plugin
       nx plug uninstall <id>      Remove an installed plugin
       nx plug enable <id>         Enable a disabled plugin
       nx plug disable <id>        Disable a plugin

Every command talks to the same Noxs Plugin Manager as the Plugins
screen (Noxs sidebar → Plugins). Installed plugins live in
~/.noxs/plugins/<id>/.
EOF
}

# ---------------------------------------------------------------- transport

# nx_plug_request <op> [arg] — prints the OK response body; returns 0 on
# OK, 1 on ERR (message on stderr), 2 when the bridge is unavailable.
nx_plug_request() {
    local op="${1:-}" a1="${2:-}"
    if [ ! -d "$NX_PLUG_HOST/requests" ]; then
        nx_err "Noxs plugin bridge is unavailable; start Noxs and open a terminal"
        return 2
    fi
    local id request response attempt first
    id="$$-$(date +%s)-$RANDOM-$RANDOM"
    request="$NX_PLUG_HOST/requests/$id"
    response="$NX_PLUG_HOST/responses/$id"
    rm -f "$request" "$response"
    {
        printf '%s\n' "$op"
        printf '%s\n' "$a1"
    } > "$request" || { nx_err "could not send the request"; return 2; }
    attempt=0
    while [ ! -f "$response" ] && [ "$attempt" -lt 240 ]; do
        sleep 0.5
        attempt=$((attempt + 1))
    done
    if [ ! -f "$response" ]; then
        rm -f "$request" "$response"
        nx_err "Noxs did not respond; keep Noxs running and retry"
        return 2
    fi
    first="$(sed -n '1p' "$response" 2>/dev/null || true)"
    if [ "$first" = "OK" ]; then
        tail -n +2 "$response"
        rm -f "$request" "$response"
        return 0
    fi
    tail -n +2 "$response" >&2
    rm -f "$request" "$response"
    return 1
}

# ---------------------------------------------------------------- snapshots

# Returns the catalog line for a plugin id (empty when unknown).
nx_plug_catalog_line() {
    local catalog="$NX_PLUG_HOST/catalog.txt"
    [ -f "$catalog" ] || return 0
    awk -F'\t' -v p="$1" '$1 == p {print; exit}' "$catalog" 2>/dev/null || true
}

# Returns the installed-plugins line for a plugin id (empty when unknown).
nx_plug_installed_line() {
    local installed="$NX_PLUG_HOST/plugins.txt"
    [ -f "$installed" ] || return 0
    awk -F'\t' -v p="$1" '$1 == p {print; exit}' "$installed" 2>/dev/null || true
}

nx_plug_bridge_ready() {
    [ -d "$NX_PLUG_HOST" ] && [ -f "$NX_PLUG_HOST/catalog.txt" ]
}

# ------------------------------------------------------------------ listing

nx_plug_list() {
    if ! nx_plug_bridge_ready; then
        nx_err "Noxs plugin bridge is unavailable; start Noxs and open a terminal"
        return 1
    fi
    local installed="$NX_PLUG_HOST/plugins.txt"
    if [ ! -s "$installed" ]; then
        printf 'No plugins installed yet.\n'
        printf 'Search the store with: nx plug search <query>\n'
        return 0
    fi
    printf 'Installed plugins:\n'
    local line id name version state marker
    while IFS= read -r line || [ -n "$line" ]; do
        [ -n "$line" ] || continue
        id="$(printf '%s' "$line" | cut -f1)"
        name="$(printf '%s' "$line" | cut -f2)"
        version="$(printf '%s' "$line" | cut -f3)"
        state="$(printf '%s' "$line" | cut -f4)"
        marker=" "
        [ "$state" = "disabled" ] && marker="x"
        printf ' %s %-16s %-10s v%-8s %s\n' "$marker" "$id" "$name" "$version" "$state"
    done < "$installed"
}

# ------------------------------------------------------------------ search

nx_plug_search() {
    if [ $# -gt 1 ]; then
        nx_err "search takes one query (quote it if it has spaces)"
        return 2
    fi
    if ! nx_plug_bridge_ready; then
        nx_err "Noxs plugin bridge is unavailable; start Noxs and open a terminal"
        return 1
    fi
    local query="${1:-}" catalog="$NX_PLUG_HOST/catalog.txt" count
    if [ -z "$query" ]; then
        count=$(grep -c . "$catalog" 2>/dev/null || printf '0')
        printf '%s plugin(s) in the store (nx plug search <query>):\n' "$count"
        cut -f1,2,3 "$catalog" | sed 's/^/  /'
        return 0
    fi
    # Shared store search: id, name, category, keywords, description.
    local hits
    hits=$(grep -i -F -e "$query" "$catalog" 2>/dev/null || true)
    if [ -z "$hits" ]; then
        printf 'No plugins found for: %s\n' "$query"
        return 0
    fi
    count=$(printf '%s\n' "$hits" | grep -c .)
    printf '%s plugin(s) found:\n' "$count"
    printf '%s\n' "$hits" | cut -f1,2,3 | sed 's/^/  /'
    printf '\nDetails: nx plug info <id>   Install: nx plug install <id>\n'
}

# -------------------------------------------------------------------- info

nx_plug_info() {
    case "${1:-}" in
        ""|-h|--help|help) nx_plug_usage; return 2 ;;
    esac
    if [ $# -gt 1 ]; then
        nx_err "info takes exactly one plugin id"
        return 2
    fi
    if ! nx_plug_bridge_ready; then
        nx_err "Noxs plugin bridge is unavailable; start Noxs and open a terminal"
        return 1
    fi
    local id="$1" line installed_line state sdk_version compat compat_label
    line="$(nx_plug_catalog_line "$id")"
    installed_line="$(nx_plug_installed_line "$id")"
    if [ -z "$line" ] && [ -z "$installed_line" ]; then
        nx_err "Unknown plugin: $id (try: nx plug search $id)"
        return 2
    fi
    if [ -n "$installed_line" ]; then
        state="$(printf '%s' "$installed_line" | cut -f4)"
    else
        state="available"
    fi
    printf 'Plugin:      %s\n' "$id"
    if [ -n "$line" ]; then
        printf 'Name:        %s\n' "$(printf '%s' "$line" | cut -f2)"
        printf 'Version:     %s\n' "$(printf '%s' "$line" | cut -f3)"
        printf 'Category:    %s\n' "$(printf '%s' "$line" | cut -f4)"
        printf 'Keywords:    %s\n' "$(printf '%s' "$line" | cut -f5)"
        printf 'Description: %s\n' "$(printf '%s' "$line" | cut -f6)"
        # Trailing Noxs Plugin SDK columns (9 = sdk version, 10 = compat state).
        sdk_version="$(printf '%s' "$line" | cut -f9)"
        compat="$(printf '%s' "$line" | cut -f10)"
        if [ -n "$sdk_version" ] && [ "$sdk_version" != "-" ]; then
            printf 'SDK:         Noxs Plugin SDK %s\n' "$sdk_version"
        fi
        case "$compat" in
            compatible) compat_label="Compatible" ;;
            sdk_missing) compat_label="Compatible (SDK downloads on install)" ;;
            sdk_incompatible) compat_label="Incompatible with this Noxs" ;;
            app_update_required) compat_label="Noxs update required" ;;
            plugin_update_available) compat_label="Compatible (update available)" ;;
            blocked) compat_label="Blocked — failed integrity checks" ;;
            error) compat_label="Error" ;;
            *) compat_label="" ;;
        esac
        if [ -n "$compat_label" ]; then
            printf 'Status:      %s\n' "$compat_label"
        fi
    fi
    printf 'State:       %s\n' "$state"
    printf 'Details:     Noxs sidebar → Plugins\n'
}

# ------------------------------------------------------------------ mutate

nx_plug_install_cmd() {
    case "${1:-}" in
        ""|-h|--help|help) nx_plug_usage; return 2 ;;
    esac
    if [ $# -gt 1 ]; then
        nx_err "install takes exactly one plugin id"
        return 2
    fi
    local id="$1"
    printf 'nx: Installing %s...\n' "$id"
    if nx_plug_request install "$id"; then
        printf 'nx: Installed %s — open it from the Plugins screen\n' "$id"
        return 0
    fi
    return 1
}

nx_plug_uninstall_cmd() {
    case "${1:-}" in
        ""|-h|--help|help) nx_plug_usage; return 2 ;;
    esac
    if [ $# -gt 1 ]; then
        nx_err "uninstall takes exactly one plugin id"
        return 2
    fi
    local id="$1" line name
    line="$(nx_plug_installed_line "$id")"
    if [ -z "$line" ]; then
        nx_err "Plugin '$id' is not installed (see: nx plug list)"
        return 2
    fi
    name="$(printf '%s' "$line" | cut -f2)"
    printf 'Remove plugin %s (%s) from this environment?\n' "$id" "$name"
    local ans
    read -r -p 'Continue? [y/N] ' ans || ans=""
    case "$ans" in
        y|Y|yes|YES|Yes) ;;
        *) printf 'nx: Cancelled\n'; return 1 ;;
    esac
    if nx_plug_request uninstall "$id"; then
        printf 'nx: Plugin %s removed.\n' "$id"
        return 0
    fi
    return 1
}

nx_plug_enable_cmd() {
    case "${1:-}" in
        ""|-h|--help|help) nx_plug_usage; return 2 ;;
    esac
    if [ $# -gt 1 ]; then
        nx_err "enable takes exactly one plugin id"
        return 2
    fi
    if nx_plug_request enable "$1"; then
        printf 'nx: Plugin %s enabled.\n' "$1"
        return 0
    fi
    return 1
}

nx_plug_disable_cmd() {
    case "${1:-}" in
        ""|-h|--help|help) nx_plug_usage; return 2 ;;
    esac
    if [ $# -gt 1 ]; then
        nx_err "disable takes exactly one plugin id"
        return 2
    fi
    if nx_plug_request disable "$1"; then
        printf 'nx: Plugin %s disabled.\n' "$1"
        return 0
    fi
    return 1
}

nx_plug_update_cmd() {
    case "${1:-}" in
        ""|-h|--help|help) nx_plug_usage; return 2 ;;
    esac
    if [ $# -gt 1 ]; then
        nx_err "update takes exactly one plugin id"
        return 2
    fi
    local id="$1"
    printf 'nx: Checking for updates for %s...\n' "$id"
    if nx_plug_request update "$id"; then
        printf 'nx: Plugin %s is up to date.\n' "$id"
        return 0
    fi
    return 1
}

# --------------------------------------------------------------- dispatcher

nx_plug_cmd() {
    local sub="${1:-}"
    case "$sub" in
        "")
            # Bare `nx plug` opens the Noxs Plugin Store UI.
            if nx_plug_request open; then
                printf 'nx: Opening the Noxs Plugin Store...\n'
            fi
            ;;
        -h|--help|help)
            nx_plug_usage
            ;;
        list|ls)
            shift
            nx_plug_list "$@"
            ;;
        search)
            shift
            nx_plug_search "$@"
            ;;
        info)
            shift
            nx_plug_info "$@"
            ;;
        install)
            shift
            nx_plug_install_cmd "$@"
            ;;
        update)
            shift
            nx_plug_update_cmd "$@"
            ;;
        uninstall|remove|rm)
            shift
            nx_plug_uninstall_cmd "$@"
            ;;
        enable)
            shift
            nx_plug_enable_cmd "$@"
            ;;
        disable)
            shift
            nx_plug_disable_cmd "$@"
            ;;
        *)
            nx_err "Unknown nx plug command: $sub"
            nx_plug_usage
            return 2
            ;;
    esac
}
