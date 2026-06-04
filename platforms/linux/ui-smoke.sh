#!/usr/bin/env bash
set -euo pipefail

DISPLAY_ID="${DISPLAY_ID:-:118}"
CLI_ZIP="${CLI_ZIP:-/tmp/wentuyi-desktop-cli.zip}"
CLI_DIR="${CLI_DIR:-/tmp/wentuyi-desktop-cli}"
CLI="$CLI_DIR/desktop-cli/bin/desktop-cli"
ENGINE="${ENGINE:-/tmp/wentuyi_ibus.py}"
PASSPHRASE="${WENTUYI_PASSPHRASE:-ui-key}"
GTK_IM_MODULE_NAME="${GTK_IM_MODULE_NAME:-ibus}"
RESULT="${RESULT:-/tmp/wentuyi-ui-text.txt}"
APP="${APP:-/tmp/wentuyi_gtk_entry_test.py}"
COMPONENT="${COMPONENT:-/tmp/wentuyi-ui-test.xml}"
SYSTEM_COMPONENT="/usr/share/ibus/component/wentuyi-ui-test.xml"

if [ ! -x "$CLI" ]; then
    rm -rf "$CLI_DIR"
    mkdir -p "$CLI_DIR"
    unzip -q "$CLI_ZIP" -d "$CLI_DIR"
fi

if [ ! -f "$ENGINE" ]; then
    echo "missing IBus engine script: $ENGINE" >&2
    exit 2
fi

cat >"$COMPONENT" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<component>
  <name>org.freedesktop.IBus.Wentuyi</name>
  <description>Wentuyi UI test input</description>
  <exec>/usr/bin/env WENTUYI_CLI=$CLI WENTUYI_PASSPHRASE=$PASSPHRASE WENTUYI_IBUS_BUS_NAME=org.freedesktop.IBus.Wentuyi WENTUYI_IBUS_LOG=/tmp/wentuyi-ibus.log python3 $ENGINE --ibus</exec>
  <version>0.5.1</version>
  <author>Wentuyi</author>
  <license>MIT</license>
  <textdomain>ibus-wentuyi</textdomain>
  <engines>
    <engine>
      <name>wentuyi</name>
      <longname>Wentuyi UI Test</longname>
      <description>Wentuyi secure text input UI test</description>
      <language>zh</language>
      <license>MIT</license>
      <author>Wentuyi</author>
      <layout>us</layout>
      <rank>99</rank>
    </engine>
  </engines>
</component>
XML

cat >"$APP" <<PY
import gi
import pathlib
import signal

gi.require_version("Gtk", "3.0")
from gi.repository import Gtk, GLib

result = pathlib.Path("$RESULT")
window = Gtk.Window(title="Wentuyi UI Test")
window.set_default_size(760, 120)
entry = Gtk.Entry()
entry.set_name("wentuyi-entry")
entry.set_property("im-module", "$GTK_IM_MODULE_NAME")
entry.set_input_purpose(Gtk.InputPurpose.FREE_FORM)
entry.set_width_chars(100)
window.add(entry)
window.connect("destroy", Gtk.main_quit)

def dump():
    result.write_text(entry.get_text(), encoding="utf-8")
    return True

GLib.timeout_add(100, dump)
window.show_all()
entry.grab_focus()
signal.signal(signal.SIGTERM, lambda *_: Gtk.main_quit())
Gtk.main()
dump()
PY

sudo -n cp "$COMPONENT" "$SYSTEM_COMPONENT"
sudo -n chmod 0644 "$SYSTEM_COMPONENT"

rm -f "$RESULT" /tmp/wentuyi-ui-encrypted.txt /tmp/wentuyi-ibus-engines.txt /tmp/wentuyi-xvfb.log /tmp/wentuyi-ibus.log
Xvfb "$DISPLAY_ID" -screen 0 1024x768x24 >/tmp/wentuyi-xvfb.log 2>&1 &
XVFB_PID=$!
WM_PID=""

cleanup() {
    if [ -n "$WM_PID" ]; then kill "$WM_PID" 2>/dev/null || true; fi
    kill "$XVFB_PID" 2>/dev/null || true
    sudo -n rm -f "$SYSTEM_COMPONENT" 2>/dev/null || true
}
trap cleanup EXIT

sleep 1
if command -v openbox >/dev/null 2>&1; then
    DISPLAY="$DISPLAY_ID" openbox >/tmp/wentuyi-openbox.log 2>&1 &
    WM_PID=$!
    sleep 0.6
fi

DISPLAY_VALUE="$DISPLAY_ID" \
GTK_IM_MODULE_VALUE="$GTK_IM_MODULE_NAME" \
QT_IM_MODULE_VALUE=ibus \
XMODIFIERS_VALUE=@im=ibus \
WENTUYI_CLI_VALUE="$CLI" \
WENTUYI_PASSPHRASE_VALUE="$PASSPHRASE" \
RESULT_VALUE="$RESULT" \
APP_VALUE="$APP" \
dbus-run-session -- bash <<'INNER'
set -euo pipefail
export DISPLAY="$DISPLAY_VALUE"
export GTK_IM_MODULE="$GTK_IM_MODULE_VALUE"
export QT_IM_MODULE="$QT_IM_MODULE_VALUE"
export XMODIFIERS="$XMODIFIERS_VALUE"
export WENTUYI_CLI="$WENTUYI_CLI_VALUE"
export WENTUYI_PASSPHRASE="$WENTUYI_PASSPHRASE_VALUE"
RESULT="$RESULT_VALUE"
APP="$APP_VALUE"

ibus-daemon -drx -t refresh
for _ in $(seq 1 80); do
    ibus write-cache >/dev/null 2>&1 || true
    if ibus list-engine >/tmp/wentuyi-ibus-engines.txt 2>/tmp/wentuyi-ibus-list.err && \
        grep -i wentuyi /tmp/wentuyi-ibus-engines.txt >/dev/null; then
        break
    fi
    sleep 0.1
done
grep -i wentuyi /tmp/wentuyi-ibus-engines.txt >/dev/null
for _ in $(seq 1 40); do
    ibus engine wentuyi >/tmp/wentuyi-ibus-engine.err 2>&1 && break
    sleep 0.2
done
ibus engine | grep -i wentuyi >/dev/null

python3 "$APP" &
APP_PID=$!
trap 'kill "$APP_PID" 2>/dev/null || true' EXIT

WIN=""
for _ in $(seq 1 80); do
    WIN=$(xdotool search --onlyvisible --name "Wentuyi UI Test" 2>/dev/null | head -n1 || true)
    [ -n "$WIN" ] && break
    sleep 0.1
done
[ -n "$WIN" ]
xdotool windowactivate "$WIN" 2>/dev/null || xdotool windowfocus "$WIN" 2>/dev/null || true
sleep 0.3
for _ in $(seq 1 20); do
    ibus engine wentuyi >/tmp/wentuyi-ibus-engine.err 2>&1 && break
    sleep 0.1
done
ibus engine | grep -i wentuyi >/dev/null
sleep 0.2

xdotool type --delay 20 "plain ui"
xdotool key Return
sleep 0.5
PLAIN=$(cat "$RESULT")
[ "$PLAIN" = "plain ui" ]

xdotool key ctrl+a BackSpace
sleep 0.2
xdotool type --delay 20 "secret ui"
xdotool key ctrl+shift+e
ENC=""
for _ in $(seq 1 140); do
    sleep 0.1
    ENC=$(cat "$RESULT")
    case "$ENC" in WTY3:*) break;; esac
done
printf "%s" "$ENC" >/tmp/wentuyi-ui-encrypted.txt
case "$ENC" in WTY3:*) ;; *) echo "not encrypted: $ENC" >&2; exit 3;; esac

xdotool key ctrl+a BackSpace
sleep 0.2
xdotool type --delay 1 --clearmodifiers "$ENC"
xdotool key ctrl+shift+d
DEC=""
for _ in $(seq 1 140); do
    sleep 0.1
    DEC=$(cat "$RESULT")
    [ "$DEC" = "secret ui" ] && break
done
[ "$DEC" = "secret ui" ]

printf "linux-ui-plain=%s\nlinux-ui-encrypted-prefix=%s\nlinux-ui-decrypted=%s\n" "$PLAIN" "${ENC:0:5}" "$DEC"
INNER
