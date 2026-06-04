#!/usr/bin/env bash
set -euo pipefail

CLI="${WENTUYI_CLI:-desktop-cli}"
INSERT_SCRIPT="${WENTUYI_INSERT_SCRIPT:-$(dirname "$0")/wentuyi-insert.sh}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PASSPHRASE="${WENTUYI_PASSPHRASE:-}"
PASSPHRASE_FILE="${WENTUYI_PASSPHRASE_FILE:-$HOME/.config/wentuyi/passphrase}"
APP="${WENTUYI_SEND_APP:-focused}"
TO="test@example.invalid"
SUBJECT="Wentuyi direct send"
OUT_DIR="${WENTUYI_OUT_DIR:-$(mktemp -d /tmp/wentuyi-send.XXXXXX)}"
CLAWS_CONFIG_DIR="${WENTUYI_CLAWS_CONFIG_DIR:-}"
MODE=""
VALUE=""

usage() {
    cat >&2 <<'USAGE'
Usage: wentuyi-send.sh [--app focused|claws-mail|thunderbird] [--to ADDRESS] [--subject TEXT]
                       (--text TEXT | --encrypt-text TEXT | --plain-image TEXT | --encrypted-qr TEXT)

Direct delivery, no clipboard:
  focused      text/encrypt-text is typed into the focused X11 target with wentuyi-insert.sh.
  claws-mail   opens a compose window and directly attaches generated PNGs or writes body text.
  thunderbird  opens a compose window and directly attaches generated PNGs or writes body text.
USAGE
}

while [ $# -gt 0 ]; do
    case "$1" in
        --app) APP="$2"; shift 2 ;;
        --to) TO="$2"; shift 2 ;;
        --subject) SUBJECT="$2"; shift 2 ;;
        --out-dir) OUT_DIR="$2"; shift 2 ;;
        --text|--encrypt-text|--plain-image|--encrypted-qr)
            [ -z "$MODE" ] || { usage; exit 2; }
            MODE="${1#--}"
            VALUE="$2"
            shift 2
            ;;
        -h|--help) usage; exit 0 ;;
        *) usage; exit 2 ;;
    esac
done

[ -n "$MODE" ] || { usage; exit 2; }
mkdir -p "$OUT_DIR"

if ! command -v "$CLI" >/dev/null 2>&1 && [ ! -x "$CLI" ]; then
    ZIP=""
    [ -f "$SCRIPT_DIR/desktop-cli.zip" ] && ZIP="$SCRIPT_DIR/desktop-cli.zip"
    [ -z "$ZIP" ] && [ -f "$SCRIPT_DIR/wentuyi-desktop-cli.zip" ] && ZIP="$SCRIPT_DIR/wentuyi-desktop-cli.zip"
    [ -z "$ZIP" ] && [ -f /tmp/desktop-cli.zip ] && ZIP=/tmp/desktop-cli.zip
    if [ -n "$ZIP" ]; then
        CLI_DIR="${WENTUYI_CLI_DIR:-/tmp/wentuyi-desktop-cli-send}"
        rm -rf "$CLI_DIR"
        mkdir -p "$CLI_DIR"
        unzip -q "$ZIP" -d "$CLI_DIR"
        CLI="$CLI_DIR/desktop-cli/bin/desktop-cli"
    fi
fi

passphrase() {
    if [ -n "$PASSPHRASE" ]; then printf '%s' "$PASSPHRASE"; return; fi
    if [ -f "$PASSPHRASE_FILE" ]; then head -n 1 "$PASSPHRASE_FILE"; return; fi
    echo "Set WENTUYI_PASSPHRASE or create $PASSPHRASE_FILE" >&2
    exit 2
}

uri_for() {
    local path
    path=$(readlink -f "$1")
    printf 'file://%s' "$path"
}

compose_file() {
    local body="$1"
    local file="$OUT_DIR/message.eml"
    {
        printf 'To: %s\n' "$TO"
        printf 'Subject: %s\n' "$SUBJECT"
        printf '\n%s\n' "$body"
    } >"$file"
    printf '%s' "$file"
}

send_text() {
    local body="$1"
    case "$APP" in
        focused)
            [ -x "$INSERT_SCRIPT" ] || { echo "missing insert script: $INSERT_SCRIPT" >&2; exit 2; }
            "$INSERT_SCRIPT" --text "$body"
            ;;
        claws-mail)
            claws_cmd=(claws-mail)
            if [ -n "$CLAWS_CONFIG_DIR" ]; then claws_cmd+=(--alternate-config-dir "$CLAWS_CONFIG_DIR"); fi
            "${claws_cmd[@]}" --compose-from-file "$(compose_file "$body")" >/tmp/wentuyi-claws-send.stdout 2>/tmp/wentuyi-claws-send.stderr &
            ;;
        thunderbird)
            thunderbird -compose "to='$TO',subject='$SUBJECT',body='$body'" >/tmp/wentuyi-thunderbird-send.stdout 2>/tmp/wentuyi-thunderbird-send.stderr &
            ;;
        *) echo "unsupported app for text: $APP" >&2; exit 2 ;;
    esac
}

send_files() {
    local files=("$@")
    [ "${#files[@]}" -gt 0 ] || { echo "no generated files" >&2; exit 3; }
    case "$APP" in
        claws-mail)
            claws_cmd=(claws-mail)
            if [ -n "$CLAWS_CONFIG_DIR" ]; then claws_cmd+=(--alternate-config-dir "$CLAWS_CONFIG_DIR"); fi
            "${claws_cmd[@]}" --compose-from-file "$(compose_file "")" --attach "${files[@]}" >/tmp/wentuyi-claws-send.stdout 2>/tmp/wentuyi-claws-send.stderr &
            ;;
        thunderbird)
            local attachments=""
            local file
            for file in "${files[@]}"; do
                if [ -n "$attachments" ]; then attachments="$attachments,"; fi
                attachments="$attachments$(uri_for "$file")"
            done
            thunderbird -compose "to='$TO',subject='$SUBJECT',attachment='$attachments'" >/tmp/wentuyi-thunderbird-send.stdout 2>/tmp/wentuyi-thunderbird-send.stderr &
            ;;
        focused)
            echo "focused app image direct-send is not generic on desktop; use --app claws-mail or --app thunderbird" >&2
            exit 2
            ;;
        *) echo "unsupported app for image send: $APP" >&2; exit 2 ;;
    esac
    printf '%s\n' "${files[@]}"
}

case "$MODE" in
    text)
        send_text "$VALUE"
        ;;
    encrypt-text)
        payload=$("$CLI" encrypt-text --passphrase "$(passphrase)" "$VALUE")
        send_text "$payload"
        ;;
    plain-image)
        out="$OUT_DIR/wentuyi-plain.png"
        "$CLI" plain-image --out "$out" "$VALUE" >/dev/null
        send_files "$out"
        ;;
    encrypted-qr)
        mapfile -t files < <("$CLI" encrypted-qr --passphrase "$(passphrase)" --out-dir "$OUT_DIR" --prefix wentuyi-qr "$VALUE")
        send_files "${files[@]}"
        ;;
esac
