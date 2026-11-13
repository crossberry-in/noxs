# pkg-init.sh — nx pkg init (scaffold new packages from templates).
# Sourced by nx after pkg-lib.sh. shell=bash
# shellcheck shell=bash

NX_TEMPLATE_ROOT="${NX_TEMPLATE_ROOT:-/usr/local/share/noxs-pkg/templates}"

pkg_init_usage() {
    cat <<'EOF'
usage: nx pkg init <package-name> [--node|--nodejs|--rust|--cpp|--c++|--python|--py|--go]

Creates ./<package-name> from a language template:
  .github/workflows/pkg.yml   build + release automation (multi-arch)
  registry.json               package metadata (updated automatically)
  VERSION                     semantic version read by the release workflow
  README.md, LICENSE          project docs

Interactive: run without a language flag to use the keyboard selector.
EOF
}

# choose_language -> sets NX_LANG_CHOICE. Keyboard-navigable selector.
choose_language() {
    local labels=("Node.js" "Rust" "C++" "Python" "Go")
    local ids=(nodejs rust cpp python go)
    local total=5 sel=0 key seq i
    local esc cr nl
    esc=$(printf '\x1b')
    cr=$(printf '\r')
    nl=$(printf '\n')
    trap 'printf "\033[?25h"' EXIT
    printf 'Noxs Package Initializer\n\nSelect package language:\n'
    printf '\033[?25l'   # hide cursor while the menu is live

    redraw_menu() {
        for ((i = 0; i < total; i++)); do
            if [ "$i" -eq "$sel" ]; then
                printf '\033[1;36m❯ %s\033[0m\033[K\r\n' "${labels[$i]}"
            else
                printf '  %s\033[K\r\n' "${labels[$i]}"
            fi
        done
        printf '\033[%dA' "$total"
    }

    redraw_menu
    while :; do
        IFS= read -rsn1 key || { NX_LANG_CHOICE=""; break; }
        case "$key" in
            "$esc")
                IFS= read -rsn2 -t 1 seq || continue
                case "$seq" in
                    "[A") sel=$(( (sel + total - 1) % total )); redraw_menu ;;
                    "[B") sel=$(( (sel + 1) % total )); redraw_menu ;;
                esac
                ;;
            ""|"$cr"|"$nl")
                NX_LANG_CHOICE="${ids[$sel]}"
                break
                ;;
            q|Q)
                NX_LANG_CHOICE=""
                break
                ;;
        esac
    done
    printf '\033[%dB\n' "$total"   # move past the menu before exiting
    trap - EXIT
    printf '\033[?25h'
    [ -n "$NX_LANG_CHOICE" ] || return 1
    return 0
}

pkg_init_cmd() {
    local name="" lang="" arg
    while [ $# -gt 0 ]; do
        arg="$1"
        case "$arg" in
            -h|--help) pkg_init_usage; return 0 ;;
            --node|--nodejs) lang="nodejs" ;;
            --rust) lang="rust" ;;
            --cpp|--c++) lang="cpp" ;;
            --python|--py) lang="python" ;;
            --go) lang="go" ;;
            -*)
                err "Unknown option: $arg"
                pkg_init_usage
                return 2
                ;;
            *)
                if [ -n "$name" ]; then
                    err "Only one package name is allowed (got '$name' and '$arg')"
                    return 2
                fi
                name="$arg"
                ;;
        esac
        shift
    done

    [ -n "$name" ] || { pkg_init_usage; return 2; }
    valid_name "$name" \
        || die "Invalid package name: $name (use lowercase letters, digits, '.', '-', '_'; start with a letter or digit)"
    [ -e "$name" ] && die "Directory already exists: $name"
    [ -d "$NX_TEMPLATE_ROOT" ] \
        || die "Noxs package templates are missing. Restart Noxs once so they install, then try again."

    if [ -z "$lang" ]; then
        if [ ! -t 0 ]; then
            die "No language selected and no terminal attached. Use one of: --node --rust --cpp --python --go"
        fi
        choose_language || die "Cancelled — no package was created."
        lang="$NX_LANG_CHOICE"
    fi
    case "$lang" in
        nodejs|rust|cpp|python|go) : ;;
        *) die "Unsupported language: $lang" ;;
    esac

    local template="$NX_TEMPLATE_ROOT/$lang/files"
    [ -d "$template" ] || die "Template not found for language: $lang"

    stage "Creating $name ($lang template)..."
    mkdir -p "$name" || die "Cannot create directory: $name"
    cp -a "$template/." "$name/" || { rm -rf "$name"; die "Template copy failed."; }

    # Render the package name placeholder. The name is strictly validated
    # above, so the sed replacement cannot inject metacharacters.
    local f
    while IFS= read -r f; do
        sed -i "s/__PKG_NAME__/$name/g" "$f"
    done < <(grep -rlI "__PKG_NAME__" "$name" 2>/dev/null)

    if command -v git >/dev/null 2>&1; then
        if [ ! -d "$name/.git" ]; then
            if (cd "$name" && git init -q 2>/dev/null); then
                msg "Initialized git repository in $name"
            else
                msg "note: git init failed — run it manually before publishing"
            fi
        fi
    else
        msg "note: git is not installed — install it before publishing (sudo apt install git)"
    fi

    msg "Created $name ($lang)."
    cat <<EOF

Next steps:
  1. Open $name/src and implement your package
  2. nx pkg build        # build it locally
  3. bump VERSION, then commit and push:
       git add . && git commit -m "release: $name \$(cat VERSION)" && git push
  4. GitHub Actions builds, tags and publishes $name.nx.pkg.<version>.*
     and updates registry.json automatically
  5. Anyone on Noxs can then run: nx install $name
EOF
    return 0
}
