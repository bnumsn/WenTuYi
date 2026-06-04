#!/usr/bin/env bash
set -euo pipefail

DISPLAY_ID="${DISPLAY_ID:-:132}"
CLI_ZIP="${CLI_ZIP:-/tmp/desktop-cli.zip}"
CLI_DIR="${CLI_DIR:-/tmp/wentuyi-desktop-cli-thunderbird}"
CLI="$CLI_DIR/desktop-cli/bin/desktop-cli"
INSERT_SCRIPT="${INSERT_SCRIPT:-/tmp/wentuyi-insert.sh}"
PASSPHRASE="${WENTUYI_PASSPHRASE:-thunderbird-key}"
PROFILE="${PROFILE:-$(mktemp -d /tmp/wentuyi-thunderbird-profile.XXXXXX)}"
RESULT="${RESULT:-/tmp/wentuyi-thunderbird-body.txt}"

if ! command -v thunderbird >/dev/null 2>&1; then
    echo "missing thunderbird" >&2
    exit 2
fi
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

pkill -x thunderbird 2>/dev/null || true
pkill -x thunderbird-bin 2>/dev/null || true
sleep 1

rm -rf "$PROFILE"
mkdir -p "$PROFILE"
cat >"$PROFILE/user.js" <<'PREFS'
user_pref("mail.shell.checkDefaultClient", false);
user_pref("mail.provider.suppress_dialog_on_startup", true);
user_pref("app.normandy.first_run", false);
user_pref("browser.shell.checkDefaultBrowser", false);
PREFS

rm -f "$RESULT" /tmp/wentuyi-thunderbird-xvfb.log /tmp/wentuyi-thunderbird-openbox.log
Xvfb "$DISPLAY_ID" -screen 0 1400x900x24 >/tmp/wentuyi-thunderbird-xvfb.log 2>&1 &
XVFB_PID=$!
WM_PID=""
TB_PID=""

cleanup() {
    if [ -n "$TB_PID" ]; then kill "$TB_PID" 2>/dev/null || true; fi
    if [ -n "$WM_PID" ]; then kill "$WM_PID" 2>/dev/null || true; fi
    kill "$XVFB_PID" 2>/dev/null || true
}
trap cleanup EXIT

sleep 1
if command -v openbox >/dev/null 2>&1; then
    DISPLAY="$DISPLAY_ID" openbox >/tmp/wentuyi-thunderbird-openbox.log 2>&1 &
    WM_PID=$!
    sleep 0.6
fi

export DISPLAY="$DISPLAY_ID"
export WENTUYI_CLI="$CLI"
export WENTUYI_PASSPHRASE="$PASSPHRASE"
export MOZ_ENABLE_WAYLAND=0

thunderbird -new-instance -no-remote -profile "$PROFILE" -compose "to='test@example.invalid',subject='Wentuyi smoke',body='prefix  suffix'" >/tmp/wentuyi-thunderbird.stdout 2>/tmp/wentuyi-thunderbird.stderr &
TB_PID=$!

WIN=""
for _ in $(seq 1 240); do
    WIN=$(xdotool search --onlyvisible --name "Write:" 2>/dev/null | head -n1 || true)
    [ -n "$WIN" ] && break
    WIN=$(xdotool search --onlyvisible --name "Thunderbird" 2>/dev/null | head -n1 || true)
    [ -n "$WIN" ] && break
    sleep 0.25
done
[ -n "$WIN" ]

xdotool windowactivate "$WIN" 2>/dev/null || xdotool windowfocus "$WIN" 2>/dev/null || true
xdotool windowsize "$WIN" 1100 760 2>/dev/null || true
xdotool windowmove "$WIN" 80 60 2>/dev/null || true
sleep 1

# Click the message body area. Thunderbird focuses recipient/subject fields first.
xdotool mousemove 620 575 click 1
sleep 0.5
xdotool key ctrl+a BackSpace
sleep 0.2
"$INSERT_SCRIPT" --encrypt-text "thunderbird rich"
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
case "$BODY" in WTY3:*) ;; *) echo "unexpected thunderbird encrypted body: $BODY" >&2; exit 3;; esac
ENC="$BODY"

xdotool key BackSpace
sleep 0.2
"$INSERT_SCRIPT" --decrypt-text "$ENC"
sleep 0.5
DEC=$(copy_body)
printf '%s' "$DEC" >"$RESULT"
[ "$DEC" = "thunderbird rich" ] || { echo "unexpected thunderbird decrypted body: $DEC" >&2; exit 4; }

printf "linux-thunderbird-encrypted-prefix=%s\nlinux-thunderbird-decrypted=%s\n" "${ENC:0:5}" "$DEC"
