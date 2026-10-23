/*
 * Noxs — original implementation.
 * Canonical `nx` CLI script installed into the rootfs at /usr/local/bin/nx.
 * Mirrored at linux-runtime/launcher/nx — CI sync-checks both copies
 * (scripts/test.sh --sync-check via scripts/sync_nx.py).
 *
 * Encoding convention (same idea as NoxsCliTemplate's ${'$'} escapes): inside
 * the raw string a literal bash `$` is written as `§` and expanded by
 * .replace('§', '$'). This keeps the embedded shell scripts readable and
 * avoids Kotlin string interpolation.
 */
package com.crossberry.noxs.runtime

object NoxsNxTemplate {

    val NX_CLI = """#!/bin/bash
# nx — Noxs package CLI + noxs command forwarder (original implementation)
#
# Package commands (NX Package System):
#   nx pkg init <name> [--node|--rust|--cpp|--python|--go]
#   nx pkg build
#   nx pkg release
#   nx pkg info <name>
#   nx pkg search <name>       Search: cache -> Noxs API -> distro -> registry
#   nx pkg install <name>      Branded installer w/ live single-line progress
#   nx pkg update [--full]     Refresh package indexes (apt + Noxs)
#   nx pkg list                Installed packages (Debian + Noxs)
#   nx pkg remove <name>       Remove a Debian or Noxs package
#   nx pkg doctor              Health check for the package layer
#   nx install <name>[@version]
#   nx install -git @user/repo[@version]
#   nx list
#   nx info <installed-package>
#   nx update [package]
#   nx remove <package>
#   nx ow <url>          Open a URL in the Noxs floating browser window
#   nx ai [task]         Noxs AI Agent Terminal (interactive or one-shot)
#   nx vpn <cmd>         Tor over PRoot (VPN mode)
#   nx plug <cmd>        Noxs Plugin Store (open / search / install / ...)
#   nx cert-fix          Repair APT sources, CA certificates, install curl/wget
#
# Every other command is forwarded to the noxs CLI, so all noxs commands
# are allowed through nx as well:
#   nx docker install     (== noxs docker install)
#   nx code start         (== noxs code start)
#   nx storage status     (== noxs storage status)

set -u
# Overridable for tests; /usr/local/lib/noxs-pkg on a real device.
NX_LIB_DIR="§{NX_LIB_DIR:-/usr/local/lib/noxs-pkg}"

nx_msg() { printf 'nx: %s\n' "§*"; }
nx_err() { printf 'nx: %s\n' "§*" >&2; }

nx_usage() {
    cat <<'EOF'
nx — Noxs package CLI

Package commands:
  nx pkg init <name> [--node|--nodejs|--rust|--cpp|--c++|--python|--py|--go]
                        Create a new package project from a language template
  nx pkg build          Build the package in the current directory
  nx pkg release        Pre-flight validation before publishing a release
  nx pkg info <name>    Package details (mirror + distro + registry + installed)
  nx pkg search <name>  Search: local cache, Noxs API, distro metadata, registry
  nx pkg install <name> Install with the Noxs installer UI and live progress
  nx pkg update [--full]
                        Refresh package indexes; --full runs the upgrade
  nx pkg list           Installed packages (Debian + Noxs)
  nx pkg remove <name>  Remove a Debian or Noxs package
  nx pkg doctor         Health check for the package layer

Install commands:
  nx install <name>[@version]          Install an official package
  nx install -git @user/repo[@version] Install from any GitHub repository
  nx list                              List installed packages
  nx info <package>                    Show details for an installed package
  nx update [package]                  Update installed packages
  nx remove <package>                  Remove an installed package

Web:
  nx ow <url>                          Open a URL in the Noxs browser window

VPN (Tor):
  nx vpn                              Install (if needed) + activate Tor
  nx vpn stop|status|test             Manage the Tor daemon
  nx vpn on|off                       Route new shells through Tor (global)

Plugin Store:
  nx plug                             Open the Noxs Plugin Store UI
  nx plug search <query>              Search plugins
  nx plug list                        Installed plugins
  nx plug info <id>                   Plugin metadata
  nx plug install|update|uninstall <id>
  nx plug enable|disable <id>

AI:
  nx ai                                Interactive Noxs AI Agent (nx@ai>)
  nx ai "task"                         One-shot task, then exit

Maintenance:
  nx cert-fix                          Repair APT + CA certificates; install
                                       curl and wget into the environment

All noxs commands are also allowed here (nx <command> == noxs <command>):
  nx docker install | nx code start | nx storage status | nx help

Run 'nx pkg', 'nx install' or 'nx ow' without arguments for
command-specific help.
EOF
}

nx_pkg_usage() {
    cat <<'EOF'
usage: nx pkg init <package-name> [--node|--rust|--cpp|--python|--go]
       nx pkg build
       nx pkg release
       nx pkg info <package-name>
       nx pkg search <name> [--json]
       nx pkg install <name> [-y] [--json]
       nx pkg update [--full]
       nx pkg list
       nx pkg remove <name> [-y]
       nx pkg doctor
EOF
}

nx_install_usage() {
    cat <<'EOF'
usage: nx install <package>[@version]
       nx install -git @username/repo[@version]

Official packages resolve from the noxs-pkg GitHub organization.
Git packages resolve from any public GitHub repository that ships a
registry.json and signed releases (.nx.pkg artifacts with sha256).
EOF
}

# shellcheck disable=SC2034  # read by pkg-lib.sh resolve_source
NX_OFFICIAL_ORG="noxs-pkg"
NX_VERSION="1.0.0"

case "§{1:-}" in
    pkg)
        shift                       # drop "pkg"
        sub="§{1:-}"
        [ $# -gt 0 ] && shift       # drop the subcommand, keep its arguments
        # Load the shared library, then the module that owns the subcommand.
        # shellcheck source=/dev/null
        . "§NX_LIB_DIR/pkg-lib.sh"
        case "§sub" in
            init)
                # shellcheck source=/dev/null
                . "§NX_LIB_DIR/pkg-init.sh"
                pkg_init_cmd "§@"
                ;;
            build)
                # shellcheck source=/dev/null
                . "§NX_LIB_DIR/pkg-dev.sh"
                pkg_build_cmd "§@"
                ;;
            release)
                # shellcheck source=/dev/null
                . "§NX_LIB_DIR/pkg-dev.sh"
                pkg_release_cmd "§@"
                ;;
            info)
                # `nx pkg info` — merged view: mirror index + distro policy
                # + installed manifest + Noxs registry.
                # shellcheck source=/dev/null
                . "§NX_LIB_DIR/pkg-ui.sh"
                # shellcheck source=/dev/null
                . "§NX_LIB_DIR/pkg-manager.sh"
                pkgm_info_cmd "§@"
                ;;
            search)
                # Search priority chain: local cache -> Noxs package API ->
                # distro official metadata -> Noxs registry. Metadata only.
                # shellcheck source=/dev/null
                . "§NX_LIB_DIR/pkg-ui.sh"
                # shellcheck source=/dev/null
                . "§NX_LIB_DIR/pkg-manager.sh"
                pkgm_search_cmd "§@"
                ;;
            install)
                # Noxs-branded installer UI with live single-line progress;
                # Debian/Ubuntu through native apt, Noxs packages through
                # the custom installer, mirror-first download with official
                # fallback and sha256-keyed caching.
                # shellcheck source=/dev/null
                . "§NX_LIB_DIR/pkg-ui.sh"
                # shellcheck source=/dev/null
                . "§NX_LIB_DIR/pkg-manager.sh"
                pkgm_install_cmd "§@"
                ;;
            update)
                # shellcheck source=/dev/null
                . "§NX_LIB_DIR/pkg-ui.sh"
                # shellcheck source=/dev/null
                . "§NX_LIB_DIR/pkg-manager.sh"
                pkgm_update_cmd "§@"
                ;;
            list)
                # shellcheck source=/dev/null
                . "§NX_LIB_DIR/pkg-ui.sh"
                # shellcheck source=/dev/null
                . "§NX_LIB_DIR/pkg-manager.sh"
                pkgm_list_cmd "§@"
                ;;
            remove)
                # shellcheck source=/dev/null
                . "§NX_LIB_DIR/pkg-ui.sh"
                # shellcheck source=/dev/null
                . "§NX_LIB_DIR/pkg-manager.sh"
                pkgm_remove_cmd "§@"
                ;;
            doctor)
                # shellcheck source=/dev/null
                . "§NX_LIB_DIR/pkg-ui.sh"
                # shellcheck source=/dev/null
                . "§NX_LIB_DIR/pkg-manager.sh"
                pkgm_doctor_cmd "§@"
                ;;
            ""|-h|--help|help)
                nx_pkg_usage
                ;;
            *)
                nx_err "Unknown nx pkg command: §sub"
                nx_pkg_usage
                exit 2
                ;;
        esac
        ;;
    ow)
        shift                       # drop "ow", forward only the URL
        # Noxs native browser window (real WebView on the Android side).
        # shellcheck source=/dev/null
        . "§NX_LIB_DIR/web-lib.sh"
        nx_ow_cmd "§@"
        ;;
    vpn)
        shift                       # drop "vpn", forward the subcommand
        # Tor over PRoot (VPN mode): install/activate/stop/status/test/on/off.
        # shellcheck source=/dev/null
        . "§NX_LIB_DIR/vpn-lib.sh"
        nx_vpn_cmd "§@"
        ;;
    plug)
        shift                       # drop "plug", forward the subcommand
        # Noxs Plugin Store: bare `nx plug` opens the store UI; the
        # subcommands use the same Plugin Manager as the Plugins screen.
        # shellcheck source=/dev/null
        . "§NX_LIB_DIR/plug-lib.sh"
        nx_plug_cmd "§@"
        ;;
    ai)
        shift                       # drop "ai", forward only the task/options
        # Noxs AI Agent Terminal (Python runtime on the guest side).
        # shellcheck source=/dev/null
        . "§NX_LIB_DIR/ai-lib.sh"
        nx_ai_cmd "§@"
        ;;
    cert-fix)
        shift                       # drop "cert-fix"
        # APT + CA certificate repair from inside the guest: signed-HTTP
        # fallback for apt-get update, CA bundle regeneration, curl/wget
        # provisioning. Helps installs whose app-side bootstrap never
        # completed — no app update or environment reinstall required.
        # shellcheck source=/dev/null
        . "§NX_LIB_DIR/cert-fix.sh"
        cert_fix_cmd "§@"
        ;;
    install)
        shift                       # drop "install" — it is NOT a package name
        # shellcheck source=/dev/null
        . "§NX_LIB_DIR/pkg-lib.sh"
        # shellcheck source=/dev/null
        . "§NX_LIB_DIR/pkg-install.sh"
        rc=0
        pkg_install_cmd "§@" || rc=$?
        if [ "§rc" -eq 77 ] && [ "§{NOXS_INSTALL_FALLBACK:-1}" != "0" ]; then
            # Git-registry lookup found no such package — fall through to
            # the distro/mirror installer (nx install curl == nx pkg install
            # curl). Disable with NOXS_INSTALL_FALLBACK=0.
            # shellcheck source=/dev/null
            . "§NX_LIB_DIR/pkg-ui.sh"
            # shellcheck source=/dev/null
            . "§NX_LIB_DIR/pkg-manager.sh"
            pkgm_install_cmd "§@"
        else
            [ "§rc" -ne 0 ] && exit "§rc"
        fi
        ;;
    list)
        shift                       # drop "list"
        # shellcheck source=/dev/null
        . "§NX_LIB_DIR/pkg-lib.sh"
        # shellcheck source=/dev/null
        . "§NX_LIB_DIR/pkg-install.sh"
        pkg_list_cmd "§@"
        ;;
    info)
        shift                       # drop "info" — the package name follows
        # shellcheck source=/dev/null
        . "§NX_LIB_DIR/pkg-lib.sh"
        # shellcheck source=/dev/null
        . "§NX_LIB_DIR/pkg-install.sh"
        pkg_info_cmd "§@"
        ;;
    update)
        shift                       # drop "update" — never treat it as a package
        # shellcheck source=/dev/null
        . "§NX_LIB_DIR/pkg-lib.sh"
        # shellcheck source=/dev/null
        . "§NX_LIB_DIR/pkg-install.sh"
        pkg_update_cmd "§@"
        ;;
    remove|uninstall)
        shift                       # drop "remove" — the package name follows
        # shellcheck source=/dev/null
        . "§NX_LIB_DIR/pkg-lib.sh"
        # shellcheck source=/dev/null
        . "§NX_LIB_DIR/pkg-install.sh"
        pkg_remove_cmd "§@"
        ;;
    version|--version)
        echo "nx §NX_VERSION (Noxs NX Package System)"
        ;;
    help|-h|--help)
        nx_usage
        ;;
    "")
        nx_usage
        ;;
    *)
        # Every other command is a noxs command — forward it untouched.
        # (nx docker install == noxs docker install, nx code start, ...)
        exec noxs "§@"
        ;;
esac
""".replace('§', '$')
}
