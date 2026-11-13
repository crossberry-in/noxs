# ai-lib.sh — nx ai entry (Noxs AI Agent Terminal)
# shellcheck shell=bash disable=SC2153
# Sourced by the nx dispatcher (NX_LIB_DIR comes from the dispatcher).

NX_AI_DIR="${NX_AI_DIR:-/usr/local/lib/noxs/ai}"

nx_ai_usage() {
    cat <<'EOF'
nx ai — Noxs AI Agent Terminal

Interactive:

    nx ai                    start the agent REPL (nx@ai>)
                             exit with Ctrl+D, exit or quit

One-shot:

    nx ai "check node version"
                             run one task, print the answer, exit

Options:
    --model <model>    override the configured model (default: kilo-auto/free)
    --no-stream        disable streaming responses
    --yes              auto-approve CONFIRM-level tools (one-shot only)
    --config <path>    use an alternative config file

Inside the agent: /help shows agent commands (/tools, /model, /status, ...).

The AI never receives provider keys, and never gains permissions the Noxs
Permission Center does not grant. Sensitive actions always ask: Allow? [y/N]
EOF
}

# Ensure python3 (agent runtime). Same honest pattern as the package system:
# real progress, real failures, nothing silent.
nx_ai_ensure_runtime() {
    if command -v python3 >/dev/null 2>&1; then
        return 0
    fi
    if [ "$(id -u)" = "0" ]; then NX_AI_SUDO=""; else NX_AI_SUDO="sudo"; fi
    printf 'nx: Installing dependencies... (python3)\n'
    if command -v apt-get >/dev/null 2>&1; then
        $NX_AI_SUDO apt-get update -qq || true
        $NX_AI_SUDO apt-get install -y --no-install-recommends python3 || {
            nx_err "Could not install python3. Install it manually: sudo apt install python3"
            return 1
        }
    elif command -v pacman >/dev/null 2>&1; then
        $NX_AI_SUDO pacman -Sy --noconfirm python || {
            nx_err "Could not install python. Install it manually: sudo pacman -S python"
            return 1
        }
    else
        nx_err "python3 is required for nx ai, and no supported package manager was found."
        return 1
    fi
    command -v python3 >/dev/null 2>&1
}

nx_ai_cmd() {
    if [ $# -gt 0 ]; then
        case "$1" in
            -h|--help|help)
                nx_ai_usage
                return 0
                ;;
        esac
    fi
    if [ ! -f "$NX_AI_DIR/agent.py" ]; then
        nx_err "Noxs AI runtime is not installed (missing $NX_AI_DIR/agent.py)"
        return 1
    fi
    if ! nx_ai_ensure_runtime; then
        return 1
    fi
    # Minimal, safe environment for the agent: it builds its own restricted
    # environment for tool subprocesses and never inherits secrets.
    export NOXS_AI_LIB_DIR="$NX_LIB_DIR"
    exec python3 "$NX_AI_DIR/agent.py" "$@"
}
