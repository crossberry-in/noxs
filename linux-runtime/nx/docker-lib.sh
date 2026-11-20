
# docker-lib.sh — nx docker doctor | status | repair (real probes only).
# Sourced by the nx dispatcher; runs INSIDE the Ubuntu sandbox.
# shellcheck shell=bash
NX_DOCKER_STATE="${NX_DOCKER_STATE:-/var/run/noxs/docker-state}"
NX_DOCKER_LOG="${NX_DOCKER_LOG:-/var/log/noxs/dockerd.log}"
NX_DOCKER_SOCK="${NX_DOCKER_SOCK:-/var/run/docker.sock}"
NX_DOCKER_REGISTRY="${NX_DOCKER_REGISTRY:-registry-1.docker.io}"
NX_DOCKER_PROBE_TIMEOUT="${NX_DOCKER_PROBE_TIMEOUT:-6}"
# Optional sandbox prefix for GUEST filesystem probes (functional tests set
# it to a fixture tree; production keeps the empty default = real guest /).
NX_DOCKER_PREFIX="${NX_DOCKER_PREFIX:-}"

dd_path() { printf '%s%s' "$NX_DOCKER_PREFIX" "$1"; }

nx_dd_say() { printf '%s\n' "$*"; }
# In --json mode human rows go to stderr so stdout stays parseable JSON.
NX_DD_JSON=0
nx_dd_row() { if [ "$NX_DD_JSON" = "1" ]; then printf '%-5s %s%s\n' "$1" "$2" "${3:+ — $3}" >&2; else printf '%-5s %s%s\n' "$1" "$2" "${3:+ — $3}"; fi; }

nx_dd_state_value() {
    sed -n "s/^$1=//p" "$NX_DOCKER_STATE" 2>/dev/null | head -1
}

nx_dd_docker_bin() {
    # Resolves the docker client: the guest's /usr/bin/docker when present,
    # otherwise a PATH lookup (functional tests inject a stub).
    local bin
    bin=$(dd_path /usr/bin/docker)
    if [ -x "$bin" ]; then
        printf '%s' "$bin"
    elif command -v docker >/dev/null 2>&1; then
        printf 'docker'
    fi
}

nx_dd_daemon_ok() {
    local bin
    bin=$(nx_dd_docker_bin) || return 1
    [ -n "$bin" ] || return 1
    timeout 15 sudo "$bin" info >/dev/null 2>&1
}

nx_dd_storage_driver() {
    sudo "$(nx_dd_docker_bin)" info 2>/dev/null | sed -n 's/^ *Storage Driver: *//p' | head -1
}

nx_dd_daemon_reason() {
    # Real failure reason: the last classified lines of the daemon log, or an
    # honest "no log" note. Never a fabricated message.
    local tail_lines
    tail_lines=$(tail -5 "$NX_DOCKER_LOG" 2>/dev/null | tr '\n' ' ' | cut -c1-200)
    if [ -n "$tail_lines" ]; then
        printf '%s' "$tail_lines"
    else
        printf '%s' "docker info failed (no daemon log at $NX_DOCKER_LOG)"
    fi
}

nx_dd_classify_log() {
    if grep -qiE 'failed to initialize nft|iptables.*(permission denied|operation not permitted)' "$NX_DOCKER_LOG" 2>/dev/null; then
        echo networking
    elif grep -qiE 'unable to find (memory|cpu|io|cpuset|pids) controller|cgroup.controllers.*permission denied' "$NX_DOCKER_LOG" 2>/dev/null; then
        echo cgroups
    elif grep -qiE 'overlay.*(permission denied|operation not permitted)|mount.*permission denied' "$NX_DOCKER_LOG" 2>/dev/null; then
        echo storage
    elif grep -qiE 'operation not permitted|permission denied|unshare.*failed|clone.*failed' "$NX_DOCKER_LOG" 2>/dev/null; then
        echo kernel
    else
        echo unknown
    fi
}

nx_dd_dns_ok() {
    getent hosts ports.ubuntu.com >/dev/null 2>&1
}

nx_dd_registry_ok() {
    # Two independent probes: DNS resolution + HTTPS reachability of the
    # registry auth endpoint (401 is the healthy "unauthorized" answer).
    getent hosts "$NX_DOCKER_REGISTRY" >/dev/null 2>&1 || return 1
    if command -v curl >/dev/null 2>&1; then
        local code
        code=$(curl -s -o /dev/null -w '%{http_code}' --max-time "$NX_DOCKER_PROBE_TIMEOUT" "https://$NX_DOCKER_REGISTRY/v2/" 2>/dev/null)
        case "$code" in 200|301|401) return 0 ;; esac
        return 1
    fi
    return 0
}

nx_dd_buildx_version() {
    sudo "$(nx_dd_docker_bin)" buildx version 2>/dev/null | head -1
}

nx_dd_binfmt_available() {
    [ -r /proc/sys/fs/binfmt_misc/status ] 2>/dev/null
}

nx_dd_cgroup_ok() {
    [ -r /sys/fs/cgroup/cgroup.controllers ] || [ -d /sys/fs/cgroup/memory ] || [ -d /sys/fs/cgroup/cpu ]
}

# Structural link health shared by doctor + repair: returns 0 when BROKEN.
# A path counts as broken only when it is missing, dangling, or a 0-byte
# placeholder file — never a real directory or a file with real content.
nx_dd_link_broken() {
    local p="$1" t="$2"
    if [ -L "$p" ] && [ -e "$p" ]; then return 1; fi
    if [ -d "$p" ] && [ ! -L "$p" ]; then return 1; fi
    if [ -f "$p" ] && [ -s "$p" ]; then return 1; fi
    [ -e "$t" ] || [ -d "$t" ] || return 1
    return 0
}

nx_dd_fixable_links() {
    # Prints the structural link repairs `nx docker repair` would apply.
    local ld
    ld=$(dd_path /usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1)
    nx_dd_link_broken "$(dd_path /bin)" "$(dd_path /usr/bin)" && echo "restore /bin -> usr/bin"
    nx_dd_link_broken "$(dd_path /sbin)" "$(dd_path /usr/sbin)" && echo "restore /sbin -> usr/sbin"
    nx_dd_link_broken "$(dd_path /lib)" "$(dd_path /usr/lib)" && echo "restore /lib -> usr/lib"
    if [ -e "$(dd_path /usr/lib64)" ]; then
        nx_dd_link_broken "$(dd_path /lib64)" "$(dd_path /usr/lib64)" && echo "restore /lib64 -> usr/lib64"
    fi
    if [ -f "$ld" ]; then
        nx_dd_link_broken "$(dd_path /usr/lib/ld-linux-aarch64.so.1)" "$ld" \
            && echo "restore /usr/lib/ld-linux-aarch64.so.1 (aarch64 ELF interpreter)"
        if [ -e "$(dd_path /usr/lib64)" ]; then
            nx_dd_link_broken "$(dd_path /usr/lib64/ld-linux-aarch64.so.1)" "$ld" \
                && echo "restore /usr/lib64/ld-linux-aarch64.so.1 (aarch64 ELF interpreter)"
        fi
    fi
    nx_dd_link_broken "$(dd_path /usr/bin/sh)" "$(dd_path /usr/bin/dash)" && echo "restore /usr/bin/sh -> dash"
}

nx_dd_apply_link() {
    local p="$1" t="$2"
    if [ -L "$p" ] && [ -e "$p" ]; then return 0; fi
    if [ -d "$p" ] && [ ! -L "$p" ]; then return 0; fi
    if [ -f "$p" ] && [ -s "$p" ]; then return 0; fi
    rm -f "$p" 2>/dev/null || return 0
    ln -s "$t" "$p" 2>/dev/null || true
}

# -------------------------------------------------------------- nx docker doctor

nx_docker_doctor_cmd() {
    local json=0
    case "${1:-}" in
        --json) json=1; NX_DD_JSON=1 ;;
        ""|--human) NX_DD_JSON=0 ;;
        *) nx_err "usage: nx docker doctor [--json]"; return 2 ;;
    esac

    # ---- rootfs executables ------------------------------------------------
    local rootfs_ok=1 rootfs_issues=""
    if [ -x "$(dd_path /usr/bin/clear)" ]; then
        nx_dd_row PASS "Rootfs /usr/bin/clear"
    else
        nx_dd_row FAIL "Rootfs /usr/bin/clear missing" "restore: sudo apt-get install --reinstall ncurses-bin"
        rootfs_ok=0; rootfs_issues="${rootfs_issues:+$rootfs_issues; }clear missing"
    fi
    if [ -L "$(dd_path /usr/bin/sh)" ] || [ -x "$(dd_path /usr/bin/sh)" ]; then
        nx_dd_row PASS "Rootfs /usr/bin/sh"
    else
        nx_dd_row FAIL "Rootfs /usr/bin/sh missing" "shell scripts fail with ENOENT — run: nx docker repair"
        rootfs_ok=0; rootfs_issues="${rootfs_issues:+$rootfs_issues; }/usr/bin/sh missing"
    fi
    if [ -L "$(dd_path /bin)" ] && [ -e "$(dd_path /bin)" ]; then
        nx_dd_row PASS "Rootfs FHS /bin -> usr/bin"
    elif [ -d "$(dd_path /bin)" ] && [ ! -L "$(dd_path /bin)" ]; then
        nx_dd_row WARN "Rootfs /bin is a real directory" "usr-merge expected; harmless unless binaries vanish"
    else
        nx_dd_row FAIL "Rootfs /bin link broken" "run: nx docker repair"
        rootfs_ok=0; rootfs_issues="${rootfs_issues:+$rootfs_issues; }/bin broken"
    fi
    if [ -e "$(dd_path /lib64/ld-linux-aarch64.so.1)" ] || [ -e "$(dd_path /usr/lib/ld-linux-aarch64.so.1)" ]; then
        nx_dd_row PASS "Rootfs loader chain (arm64 ELF interpreter resolves)"
    elif [ -f "$(dd_path /usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1)" ]; then
        nx_dd_row FAIL "Rootfs loader link missing (real loader exists)" "run: nx docker repair — every ELF exec fails with ENOENT"
        rootfs_ok=0; rootfs_issues="${rootfs_issues:+$rootfs_issues; }loader link missing"
    else
        nx_dd_row FAIL "Rootfs loader binary missing" "restore from the base image — reinstall the environment"
        rootfs_ok=0; rootfs_issues="${rootfs_issues:+$rootfs_issues; }loader binary missing"
    fi
    if [ -x "$(dd_path /usr/local/bin/sudo)" ]; then
        nx_dd_row PASS "Rootfs /usr/local/bin/sudo shim"
    else
        nx_dd_row FAIL "Rootfs /usr/local/bin/sudo missing" "repair from the Noxs app: Diagnostics -> Rootfs repair (app-owned shim)"
        rootfs_ok=0; rootfs_issues="${rootfs_issues:+$rootfs_issues; }sudo shim missing"
    fi

    # ---- docker CLI --------------------------------------------------------
    local cli_ok=0 cli_version=""
    if [ -x "$(dd_path /usr/bin/docker)" ]; then
        cli_ok=1
        cli_version=$("$(dd_path /usr/bin/docker)" --version 2>/dev/null | head -1)
        nx_dd_row PASS "Docker CLI" "$cli_version"
    else
        nx_dd_row WARN "Docker CLI not installed" "run: noxs docker install"
    fi

    # ---- daemon ------------------------------------------------------------
    local daemon_ok=0 daemon_reason="not installed" daemon_mode="" daemon_limitation=""
    if [ "$cli_ok" -eq 1 ]; then
        if nx_dd_daemon_ok; then
            daemon_ok=1; daemon_reason="docker info succeeded"
            nx_dd_row PASS "Docker daemon"
        else
            daemon_reason=$(nx_dd_daemon_reason)
            daemon_limitation=$(nx_dd_classify_log)
            case "$daemon_limitation" in
                networking) nx_dd_row WARN "Docker daemon unavailable" "networking restriction ($daemon_reason)" ;;
                cgroups)    nx_dd_row WARN "Docker daemon unavailable" "cgroup restriction ($daemon_reason)" ;;
                storage)    nx_dd_row WARN "Docker daemon unavailable" "storage restriction ($daemon_reason)" ;;
                kernel)     nx_dd_row WARN "Docker daemon unavailable" "kernel restriction ($daemon_reason)" ;;
                *)          nx_dd_row WARN "Docker daemon unavailable" "$daemon_reason" ;;
            esac
        fi
        if [ -S "$NX_DOCKER_SOCK" ]; then
            nx_dd_row PASS "Docker socket $NX_DOCKER_SOCK"
        else
            nx_dd_row WARN "Docker socket absent" "the daemon creates it on start (noxs docker start)"
        fi
        daemon_mode=$(nx_dd_state_value mode)
        [ -n "$daemon_mode" ] && nx_dd_row PASS "Noxs Docker mode: $daemon_mode"
    fi

    # ---- DNS + registry ----------------------------------------------------
    local dns_ok=0
    if nx_dd_dns_ok; then
        dns_ok=1
        nx_dd_row PASS "DNS (ports.ubuntu.com resolves)"
    else
        nx_dd_row FAIL "DNS resolution failed" "check resolv.conf / Settings DNS — run: nx cert-fix"
    fi
    if nx_dd_registry_ok; then
        nx_dd_row PASS "Docker registry ($NX_DOCKER_REGISTRY reachable)"
    else
        nx_dd_row WARN "Docker registry unreachable" "image pulls and BuildKit downloads will fail until network/DNS works"
    fi

    # ---- kernel capabilities ----------------------------------------------
    if nx_dd_cgroup_ok; then
        nx_dd_row PASS "Kernel cgroup controllers readable"
    else
        nx_dd_row WARN "Kernel cgroup controllers unavailable" "Android restricts them under proot — a runtime limitation"
    fi
    if command -v iptables >/dev/null 2>&1 || command -v nft >/dev/null 2>&1; then
        nx_dd_row PASS "iptables/nft tooling present"
    else
        nx_dd_row WARN "iptables/nft binaries absent" "daemon starts only in compatibility mode (--iptables=false --bridge=none)"
    fi
    if unshare -m true 2>/dev/null; then
        nx_dd_row PASS "Mount namespaces (unshare -m)"
    else
        nx_dd_row WARN "Mount namespaces restricted" "expected under proot — Noxs never claims full container support"
    fi

    # ---- storage -----------------------------------------------------------
    local storage_driver=""
    if [ "$daemon_ok" -eq 1 ]; then
        storage_driver=$(nx_dd_storage_driver)
        case "$storage_driver" in
            overlay2|fuse-overlayfs) nx_dd_row PASS "Storage driver $storage_driver" ;;
            vfs) nx_dd_row WARN "Storage driver vfs" "works everywhere but is slow — Android blocks overlay mounts" ;;
            "") nx_dd_row WARN "Storage driver unknown" "docker info did not report one" ;;
            *) nx_dd_row PASS "Storage driver $storage_driver" ;;
        esac
        if [ -d "$(dd_path /var/lib/docker/tmp)" ]; then
            nx_dd_row PASS "Docker build directory /var/lib/docker/tmp"
        else
            nx_dd_row WARN "/var/lib/docker/tmp missing" "dockerd recreates it on start — run: noxs docker start"
        fi
    else
        nx_dd_row WARN "Docker storage unverified" "daemon not running"
    fi

    # ---- BuildKit / buildx -------------------------------------------------
    local buildx_version=""
    if [ "$cli_ok" -eq 1 ]; then
        buildx_version=$(nx_dd_buildx_version)
        if [ -n "$buildx_version" ]; then
            nx_dd_row PASS "BuildKit/buildx" "$buildx_version"
            if sudo "$(nx_dd_docker_bin)" buildx ls 2>/dev/null | grep -q 'docker-container'; then
                nx_dd_row WARN "docker-container builder under proot" \
                    "needs privileged networking Android does not grant — use remote builds (docs/DOCKER.md, GitHub Actions)"
            fi
        else
            nx_dd_row WARN "buildx plugin absent" "classic builder only — apt install docker-buildx-plugin for BuildKit"
        fi
        if ! nx_dd_registry_ok; then
            nx_dd_row WARN "BuildKit image download blocked" "registry unreachable — builds that need buildkitd images will fail"
        fi
    fi

    # ---- architecture / emulation -----------------------------------------
    local arch
    arch=$(uname -m 2>/dev/null || echo unknown)
    nx_dd_row PASS "Architecture $arch (native builds)"
    if nx_dd_binfmt_available; then
        nx_dd_row PASS "Cross-arch emulation possible (binfmt_misc present)"
    else
        nx_dd_row WARN "Cross-arch emulation unavailable" "amd64/x86 image execution not possible on this device — build with GitHub Actions for other architectures"
    fi

    if [ "$json" -eq 1 ]; then
        nx_dd_json_emit
        [ "$rootfs_ok" -eq 1 ] && [ "$dns_ok" -eq 1 ] || return 1
        return 0
    fi
    nx_dd_say ""
    nx_dd_say "Limitations are expected Android/proot restrictions — they are NOT installation corruption."
    nx_dd_say "Repair paths: nx docker repair --check (dry run) / Diagnostics -> Rootfs repair (app)."
    # doctor exit code: 0 healthy, 1 at least one FAIL row (actionable problem)
    [ "$rootfs_ok" -eq 1 ] && [ "$dns_ok" -eq 1 ] || return 1
    return 0
}

# Machine-readable doctor summary for the Android Diagnostics screen.
# Flat JSON object, safe facts only (no credentials, no log bodies).
nx_dd_json_emit() {
    local rootfs="ok" daemon="stopped" storage="" buildkit="missing" kernel="ok" cli="missing"
    [ -x "$(dd_path /usr/bin/clear)" ] || rootfs="broken"
    if [ ! -e "$(dd_path /lib64/ld-linux-aarch64.so.1)" ] && [ ! -e "$(dd_path /usr/lib/ld-linux-aarch64.so.1)" ]; then
        rootfs="broken"
    fi
    [ -x "$(dd_path /usr/local/bin/sudo)" ] || rootfs="broken"
    if [ -x "$(dd_path /usr/bin/docker)" ]; then
        cli="installed"
        if nx_dd_daemon_ok; then
            daemon="running"
            storage=$(nx_dd_storage_driver)
        else
            daemon="stopped"
        fi
        if [ -n "$(nx_dd_buildx_version)" ]; then buildkit="available"; fi
    else
        daemon="not_installed"
    fi
    nx_dd_dns_ok || kernel="limited"
    nx_dd_cgroup_ok || kernel="limited"
    local arch
    arch=$(uname -m 2>/dev/null || echo unknown)
    printf '{"rootfs":"%s","cli":"%s","daemon":"%s","mode":"%s","storage":"%s","dns":"%s","registry":"%s","buildkit":"%s","kernel":"%s","arch":"%s"}\n' \
        "$rootfs" \
        "$cli" \
        "$daemon" \
        "$(nx_dd_state_value mode)" \
        "$storage" \
        "$( nx_dd_dns_ok && echo ok || echo fail )" \
        "$( nx_dd_registry_ok && echo reachable || echo unreachable )" \
        "$buildkit" \
        "$kernel" \
        "$arch"
}

# -------------------------------------------------------------- nx docker status

nx_docker_status_cmd() {
    local mode containers driver
    if [ ! -x "$(dd_path /usr/bin/docker)" ]; then
        nx_dd_say "Docker CLI: not installed"
        nx_dd_say "Docker daemon: stopped"
        if [ -x "$(dd_path /usr/bin/clear)" ] && [ -x "$(dd_path /usr/local/bin/sudo)" ]; then
            nx_dd_say "Rootfs: ok"
        else
            nx_dd_say "Rootfs: damaged — nx docker doctor"
        fi
        nx_dd_say "Hint: noxs docker install / nx docker doctor"
        return 3
    fi
    nx_dd_say "Docker CLI: installed ($("$(dd_path /usr/bin/docker)" --version 2>/dev/null | sed 's/^Docker version //' | cut -d, -f1))"
    if nx_dd_daemon_ok; then
        nx_dd_say "Docker daemon: running"
        driver=$(nx_dd_storage_driver)
        nx_dd_say "Storage: ${driver:-unknown}"
        mode=$(nx_dd_state_value mode)
        case "$mode" in
            compatibility) nx_dd_say "Mode: compatibility (no bridge networking)" ;;
            *) nx_dd_say "Mode: normal" ;;
        esac
        containers=$(nx_dd_state_value containers)
        case "$containers" in
            supported|unsupported) nx_dd_say "Containers: $containers" ;;
            *) nx_dd_say "Containers: untested (run: noxs docker test)" ;;
        esac
        if nx_dd_dns_ok; then
            nx_dd_say "DNS: ok"
        else
            nx_dd_say "DNS: FAILED — nx cert-fix"
        fi
        if [ -n "$(nx_dd_buildx_version)" ]; then
            nx_dd_say "BuildKit: available"
        else
            nx_dd_say "BuildKit: buildx plugin absent"
        fi
        return 0
    fi
    nx_dd_say "Docker daemon: stopped"
    nx_dd_say "Mode: stopped"
    if [ -x "$(dd_path /usr/bin/clear)" ] && [ -x "$(dd_path /usr/local/bin/sudo)" ]; then
        nx_dd_say "Rootfs: ok"
    else
        nx_dd_say "Rootfs: damaged — nx docker doctor"
    fi
    return 3
}

# -------------------------------------------------------------- nx docker repair

nx_docker_repair_cmd() {
    # DRY RUN ONLY by default: `--check` reports what WOULD be repaired.
    # Non-check mode applies the safe guest-side repairs after explicit
    # confirmation (--yes from the app UI counts as the explicit confirm).
    # Docker storage (images, containers, /var/lib/docker) is NEVER touched.
    local check=0 yes=0 arg item reply ld
    for arg in "$@"; do
        case "$arg" in
            --check) check=1 ;;
            --yes) yes=1 ;;
            *) nx_err "usage: nx docker repair [--check] [--yes]"; return 2 ;;
        esac
    done

    local pending=()
    while IFS= read -r item; do
        [ -n "$item" ] && pending+=("$item")
    done < <(nx_dd_fixable_links)

    if [ ! -x "$(dd_path /usr/bin/clear)" ]; then
        pending+=("reinstall ncurses-bin (restores /usr/bin/clear; needs network)")
    fi
    if command -v dpkg >/dev/null 2>&1 && dpkg --audit 2>/dev/null | grep -q .; then
        pending+=("finish interrupted package configuration (dpkg --configure -a)")
    fi
    if [ ! -x "$(dd_path /usr/local/bin/sudo)" ]; then
        nx_dd_row NOTE "sudo shim repair is app-owned" "open the Noxs app -> Diagnostics -> Rootfs repair"
    fi

    if [ "${#pending[@]}" -eq 0 ]; then
        nx_dd_say "Nothing to repair — rootfs structure is healthy."
        nx_dd_say "Docker storage, images and containers were not modified."
        return 0
    fi

    if [ "$check" -eq 1 ]; then
        nx_dd_say "Repairs available (dry run — nothing was changed):"
        for item in "${pending[@]}"; do nx_dd_say "  - $item"; done
        nx_dd_say "Apply with: nx docker repair  (asks for confirmation)"
        return 1
    fi

    if [ "$yes" -ne 1 ]; then
        if [ -t 0 ]; then
            printf 'nx: Apply %d repair(s)? They never touch Docker storage. [y/N] ' "${#pending[@]}"
            IFS= read -r reply || reply=""
            case "$reply" in y|Y|yes|Yes) : ;; *) nx_msg "Cancelled — nothing was changed."; return 0 ;; esac
        else
            nx_err "Refusing to repair without --yes in a non-interactive session."
            return 1
        fi
    fi

    local rc=0 applied=0
    # 1) FHS/loader links (structural, local, always safe)
    nx_dd_apply_link "$(dd_path /bin)" "$(dd_path /usr/bin)"
    nx_dd_apply_link "$(dd_path /sbin)" "$(dd_path /usr/sbin)"
    nx_dd_apply_link "$(dd_path /lib)" "$(dd_path /usr/lib)"
    [ -e "$(dd_path /usr/lib64)" ] && nx_dd_apply_link "$(dd_path /lib64)" "$(dd_path /usr/lib64)"
    ld=$(dd_path /usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1)
    if [ -f "$ld" ]; then
        nx_dd_apply_link "$(dd_path /usr/lib/ld-linux-aarch64.so.1)" "$ld"
        [ -e "$(dd_path /usr/lib64)" ] && nx_dd_apply_link "$(dd_path /usr/lib64/ld-linux-aarch64.so.1)" "$ld"
    fi
    nx_dd_apply_link "$(dd_path /usr/bin/sh)" "$(dd_path /usr/bin/dash)"
    nx_dd_say "FHS/loader links checked and restored where broken."
    applied=$((applied + 1))

    # 2) dpkg consistency
    if command -v dpkg >/dev/null 2>&1 && dpkg --audit 2>/dev/null | grep -q .; then
        if sudo dpkg --configure -a; then
            nx_dd_say "dpkg state repaired."
            applied=$((applied + 1))
        else
            nx_err "dpkg --configure -a failed — inspect manually."
            rc=1
        fi
    fi

    # 3) restore /usr/bin/clear from Ubuntu (explicit, network-dependent)
    if [ ! -x "$(dd_path /usr/bin/clear)" ]; then
        if sudo apt-get install --reinstall --yes ncurses-bin 2>&1 | tail -2; then
            if [ -x "$(dd_path /usr/bin/clear)" ]; then
                nx_dd_say "/usr/bin/clear restored."
                applied=$((applied + 1))
            else
                nx_err "/usr/bin/clear still missing — check network, retry later."
                rc=1
            fi
        else
            nx_err "ncurses-bin reinstall failed — /usr/bin/clear still missing."
            rc=1
        fi
    fi

    nx_dd_say "Repair pass finished ($applied repair(s) applied). Docker storage was not modified."
    return "$rc"
}

nx_docker_cmd() {
    case "${1:-}" in
        doctor) shift; nx_docker_doctor_cmd "$@" ;;
        status) shift; nx_docker_status_cmd "$@" ;;
        repair) shift; nx_docker_repair_cmd "$@" ;;
        install|start|stop|restart|info|test)
            # Docker lifecycle stays owned by the noxs CLI (single writer for
            # the daemon + state file) — forward untouched for parity.
            exec noxs docker "$@"
            ;;
        ""|-h|--help|help)
            nx_docker_usage
            ;;
        *)
            nx_err "unknown nx docker command: $1"
            nx_docker_usage
            exit 2
            ;;
    esac
}

nx_docker_usage() {
    cat <<'HELP'
Usage: nx docker [command]
  doctor [--json]      Full diagnostic: rootfs, CLI, daemon, DNS, registry,
                       kernel limits, storage driver, BuildKit, architecture
  status               Quick summary (daemon, storage, mode, BuildKit, DNS)
  repair [--check]     Dry-run repair report; without --check applies the
                       safe rootfs repairs after confirmation (never touches
                       Docker storage/images/containers)
  install|start|stop|restart|info|test
                       Forwarded to `noxs docker ...` (daemon lifecycle)
Docker inside proot is kernel-limited: container execution is reported
honestly by `noxs docker test` — never assumed.
HELP
}
