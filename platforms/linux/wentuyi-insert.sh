#!/usr/bin/env bash
set -euo pipefail

CLI="${WENTUYI_CLI:-desktop-cli}"
PASSPHRASE="${WENTUYI_PASSPHRASE:-}"
PASSPHRASE_FILE="${WENTUYI_PASSPHRASE_FILE:-$HOME/.config/wentuyi/passphrase}"
TYPE_DELAY="${WENTUYI_TYPE_DELAY:-1}"
MODE=""
VALUE=""

usage() {
    cat >&2 <<'USAGE'
Usage: wentuyi-insert.sh (--text TEXT | --encrypt-text TEXT | --decrypt-text PAYLOAD | --self-test)

Types the result directly into the focused X11 input target with xdotool.
No clipboard is used. Pass "-" as the value to read it from stdin (keeps sensitive
text off this process' command line):  printf '%s' "$msg" | wentuyi-insert.sh --encrypt-text -
USAGE
}

while [ $# -gt 0 ]; do
    case "$1" in
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

passphrase() {
    if [ -n "$PASSPHRASE" ]; then
        printf '%s' "$PASSPHRASE"
        return
    fi
    if [ -f "$PASSPHRASE_FILE" ]; then
        head -n 1 "$PASSPHRASE_FILE"
        return
    fi
    echo "Set WENTUYI_PASSPHRASE or create $PASSPHRASE_FILE" >&2
    exit 2
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
        payload=$(printf '%s' "$VALUE" | WENTUYI_PASSPHRASE="$(passphrase)" "$CLI" encrypt-text --stdin)
        type_direct "$payload"
        ;;
    decrypt-text)
        plain=$(printf '%s' "$VALUE" | WENTUYI_PASSPHRASE="$(passphrase)" "$CLI" decrypt-text --stdin)
        type_direct "$plain"
        ;;
esac
