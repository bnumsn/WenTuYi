#!/usr/bin/env bash
set -euo pipefail

DISPLAY_ID="${DISPLAY_ID:-:145}"
CLI_ZIP="${CLI_ZIP:-/tmp/desktop-cli.zip}"
CLI_DIR="${CLI_DIR:-/tmp/wentuyi-desktop-cli-claws}"
CLI="$CLI_DIR/desktop-cli/bin/desktop-cli"
INSERT_SCRIPT="${INSERT_SCRIPT:-/tmp/wentuyi-insert.sh}"
PASSPHRASE="${WENTUYI_PASSPHRASE:-claws-key}"
CONFIG_DIR="${CONFIG_DIR:-$(mktemp -d /tmp/wentuyi-claws-config.XXXXXX)}"
MESSAGE="${MESSAGE:-/tmp/wentuyi-claws-message.txt}"
RESULT="${RESULT:-/tmp/wentuyi-claws-body.txt}"

command -v claws-mail >/dev/null
command -v xdotool >/dev/null
command -v xclip >/dev/null

if [ ! -x "$CLI" ]; then
    rm -rf "$CLI_DIR"
    mkdir -p "$CLI_DIR"
    unzip -q "$CLI_ZIP" -d "$CLI_DIR"
fi

if [ ! -x "$INSERT_SCRIPT" ]; then
    echo "missing direct insert script: $INSERT_SCRIPT" >&2
    exit 2
fi

cat >"$MESSAGE" <<'MSG'
To: test@example.invalid
Subject: Wentuyi Claws smoke

prefix  suffix
MSG

rm -f "$RESULT" /tmp/wentuyi-claws-xvfb.log /tmp/wentuyi-claws-openbox.log
Xvfb "$DISPLAY_ID" -screen 0 1400x900x24 >/tmp/wentuyi-claws-xvfb.log 2>&1 &
XVFB_PID=$!
WM_PID=""
CLPIDS=""

cleanup() {
    if [ -n "$CLPIDS" ]; then kill $CLPIDS 2>/dev/null || true; fi
    if [ -n "$WM_PID" ]; then kill "$WM_PID" 2>/dev/null || true; fi
    kill "$XVFB_PID" 2>/dev/null || true
}
trap cleanup EXIT

sleep 1
if command -v openbox >/dev/null 2>&1; then
    DISPLAY="$DISPLAY_ID" openbox >/tmp/wentuyi-claws-openbox.log 2>&1 &
    WM_PID=$!
    sleep 0.6
fi

export DISPLAY="$DISPLAY_ID"
export WENTUYI_CLI="$CLI"
export WENTUYI_PASSPHRASE="$PASSPHRASE"

claws-mail --alternate-config-dir "$CONFIG_DIR" --compose-from-file "$MESSAGE" >/tmp/wentuyi-claws-setup.stdout 2>/tmp/wentuyi-claws-setup.stderr &
CLPIDS="$CLPIDS $!"

WIZ=""
for _ in $(seq 1 80); do
    WIZ=$(xdotool search --onlyvisible --name "Claws Mail Setup Wizard" 2>/dev/null | head -n1 || true)
    [ -n "$WIZ" ] && break
    sleep 0.25
done
[ -n "$WIZ" ]
xdotool windowactivate "$WIZ" 2>/dev/null || xdotool windowfocus "$WIZ" 2>/dev/null || true
sleep 0.2
xdotool key Return
sleep 0.3
xdotool key Return
sleep 0.5
xdotool type --delay 1 kebang
sleep 0.3
xdotool key Return
sleep 0.5
xdotool key Return
sleep 0.5
xdotool key Return
sleep 0.5
eval "$(xdotool getwindowgeometry --shell "$WIZ")"
xdotool mousemove "$((X + 388))" "$((Y + 470))" click 1
for _ in $(seq 1 40); do
    [ -f "$CONFIG_DIR/clawsrc" ] && [ -f "$CONFIG_DIR/accountrc" ] && break
    sleep 0.25
done
[ -f "$CONFIG_DIR/clawsrc" ]
[ -f "$CONFIG_DIR/accountrc" ]

claws-mail --alternate-config-dir "$CONFIG_DIR" --compose-from-file "$MESSAGE" >/tmp/wentuyi-claws-compose.stdout 2>/tmp/wentuyi-claws-compose.stderr || true
sleep 2

WIN=""
for _ in $(seq 1 80); do
    WIN=$(xdotool search --onlyvisible --name "Compose message" 2>/dev/null | tail -n1 || true)
    [ -n "$WIN" ] && break
    sleep 0.25
done
[ -n "$WIN" ]

xdotool windowactivate "$WIN" 2>/dev/null || xdotool windowfocus "$WIN" 2>/dev/null || true
xdotool windowsize "$WIN" 1000 720 2>/dev/null || true
xdotool windowmove "$WIN" 100 80 2>/dev/null || true
sleep 0.5

xdotool mousemove 600 610 click 1
sleep 0.2
xdotool key ctrl+a BackSpace
sleep 0.2
"$INSERT_SCRIPT" --encrypt-text "claws mail rich"
sleep 0.5

copy_body() {
    xdotool key ctrl+a
    sleep 0.2
    xclip -selection clipboard /dev/null
    xdotool key ctrl+c
    for _ in $(seq 1 40); do
        sleep 0.1
        BODY=$(xclip -selection clipboard -o 2>/dev/null || true)
        if [ -n "$BODY" ]; then
            printf '%s' "$BODY"
            return 0
        fi
    done
    return 1
}

BODY=$(copy_body)
printf '%s' "$BODY" >"$RESULT"
case "$BODY" in WTY4:*) ;; *) echo "unexpected claws encrypted body: $BODY" >&2; exit 3;; esac
ENC="$BODY"

xdotool key BackSpace
sleep 0.2
"$INSERT_SCRIPT" --decrypt-text "$ENC"
sleep 0.5
DEC=$(copy_body)
printf '%s' "$DEC" >"$RESULT"
[ "$DEC" = "claws mail rich" ] || { echo "unexpected claws decrypted body: $DEC" >&2; exit 4; }

printf "linux-claws-encrypted-prefix=%s\nlinux-claws-decrypted=%s\n" "${ENC:0:5}" "$DEC"
