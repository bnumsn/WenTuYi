#!/usr/bin/env bash
set -euo pipefail

CLI="${WENTUYI_CLI:-desktop-cli}"
PASSPHRASE="${WENTUYI_PASSPHRASE:-}"
PASSPHRASE_FILE="${WENTUYI_PASSPHRASE_FILE:-$HOME/.config/wentuyi/passphrase}"
TYPE_DELAY="${WENTUYI_TYPE_DELAY:-1}"
# Optional contact. With --peer the CLI uses the WTY5 ratchet (or the WTY4 session key
# until a sending chain exists); without it, the legacy shared passphrase.
PEER="${WENTUYI_PEER:-}"
MODE=""
VALUE=""

usage() {
    cat >&2 <<'USAGE'
Usage: wentuyi-insert.sh [--peer NAME] (--text TEXT | --encrypt-text TEXT | --decrypt-text PAYLOAD | --self-test)

Types the result directly into the focused X11 input target with xdotool.
No clipboard is used. Pass "-" as the value to read it from stdin (keeps sensitive
text off this process' command line):  printf '%s' "$msg" | wentuyi-insert.sh --encrypt-text -
USAGE
}

while [ $# -gt 0 ]; do
    case "$1" in
        --peer) PEER="$2"; shift 2 ;;
        --text|--encrypt-text|--decrypt-text)
            [ $# -ge 2 ] || { usage; exit 2; }
            [ -z "$MODE" ] || { usage; exit 2; }
            MODE="${1#--}"
            VALUE="$2"
            shift 2
            ;;
        --self-test)
            [ -z "$MODE" ] || { usage; exit 2; }
            MODE="self-test"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage
            exit 2
            ;;
    esac
done

[ -n "$MODE" ] || { usage; exit 2; }

# A VALUE of "-" reads the text/payload from stdin, so sensitive content never appears in
# this wrapper's own argv (/proc/<pid>/cmdline, ps). Recommended for encrypt/decrypt.
if [ "$VALUE" = "-" ]; then
    VALUE="$(cat)"
fi

if [ "$MODE" = "self-test" ]; then
    command -v xdotool >/dev/null
    echo "direct-insert-self-test=available"
    exit 0
fi

# The shared passphrase is now optional: `send --peer` and `receive` read the profile
# under WENTUYI_HOME instead. Only export it when the user actually configured one, so a
# contact-only setup no longer dies with "Set WENTUYI_PASSPHRASE".
passphrase() {
    if [ -n "$PASSPHRASE" ]; then printf '%s' "$PASSPHRASE"; return; fi
    if [ -f "$PASSPHRASE_FILE" ]; then head -n 1 "$PASSPHRASE_FILE"; return; fi
    printf ''
}

# Runs the CLI with the passphrase in the environment (never argv) when there is one.
cli_env() {
    local pass
    pass="$(passphrase)"
    if [ -n "$pass" ]; then
        WENTUYI_PASSPHRASE="$pass" "$@"
    else
        "$@"
    fi
}

type_direct() {
    xdotool type --delay "$TYPE_DELAY" --clearmodifiers "$1"
}

case "$MODE" in
    text)
        type_direct "$VALUE"
        ;;
    encrypt-text)
        # Secret via env, text via stdin → neither appears in argv (/proc/<pid>/cmdline, ps).
        # `send` picks the protocol: WTY5 ratchet for a --peer when a sending chain
        # exists, else the WTY4 session key, else the shared passphrase. Doing that choice
        # here in shell is what kept this bridge stuck on shared-key-only.
        payload=$(printf '%s' "$VALUE" | cli_env "$CLI" send ${PEER:+--peer "$PEER"} --stdin)
        type_direct "$payload"
        ;;
    decrypt-text)
        # `receive` auto-detects WTY5 / WTY4-session / WTY4-passphrase and the sender.
        plain=$(printf '%s' "$VALUE" | cli_env "$CLI" receive --stdin 2>/dev/null)
        type_direct "$plain"
        ;;
esac
