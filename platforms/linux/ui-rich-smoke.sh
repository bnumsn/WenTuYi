#!/usr/bin/env bash
set -euo pipefail

DISPLAY_ID="${DISPLAY_ID:-:129}"
CLI_ZIP="${CLI_ZIP:-/tmp/wentuyi-desktop-cli.zip}"
CLI_DIR="${CLI_DIR:-/tmp/wentuyi-desktop-cli}"
CLI="$CLI_DIR/desktop-cli/bin/desktop-cli"
INSERT_SCRIPT="${INSERT_SCRIPT:-/tmp/wentuyi-insert.sh}"
PASSPHRASE="${WENTUYI_PASSPHRASE:-rich-key}"
RESULT="${RESULT:-/tmp/wentuyi-rich-ui-text.txt}"
TAG_RESULT="${TAG_RESULT:-/tmp/wentuyi-rich-ui-tags.txt}"
APP="${APP:-/tmp/wentuyi_gtk_rich_test.py}"

if [ ! -x "$CLI" ]; then
    rm -rf "$CLI_DIR"
    mkdir -p "$CLI_DIR"
    unzip -q "$CLI_ZIP" -d "$CLI_DIR"
fi

if [ ! -x "$INSERT_SCRIPT" ]; then
    echo "missing direct insert script: $INSERT_SCRIPT" >&2
    exit 2
fi

cat >"$APP" <<PY
import gi
import pathlib
import signal

gi.require_version("Gtk", "3.0")
from gi.repository import Gtk, GLib, Pango

result = pathlib.Path("$RESULT")
tag_result = pathlib.Path("$TAG_RESULT")
window = Gtk.Window(title="Wentuyi Rich UI Test")
window.set_default_size(840, 260)
view = Gtk.TextView()
view.set_name("wentuyi-rich-text")
view.set_can_focus(True)
view.set_editable(True)
view.set_cursor_visible(True)
view.set_wrap_mode(Gtk.WrapMode.WORD_CHAR)
buf = view.get_buffer()
prefix_tag = buf.create_tag("wentuyi-prefix", foreground="darkgreen", weight=Pango.Weight.BOLD)
suffix_tag = buf.create_tag("wentuyi-suffix", foreground="darkblue", style=Pango.Style.ITALIC)

start = buf.get_start_iter()
buf.insert_with_tags(start, "prefix ", prefix_tag)
end = buf.get_end_iter()
buf.insert_with_tags(end, " suffix", suffix_tag)
cursor = buf.get_iter_at_offset(len("prefix "))
buf.place_cursor(cursor)

window.add(view)
window.connect("destroy", Gtk.main_quit)

def tag_names_at(offset):
    it = buf.get_iter_at_offset(offset)
    return sorted([t.get_property("name") for t in it.get_tags()])

def dump():
    start = buf.get_start_iter()
    end = buf.get_end_iter()
    result.write_text(buf.get_text(start, end, True), encoding="utf-8")
    tags = {
        "prefix_tag": "wentuyi-prefix" in tag_names_at(1),
        "suffix_tag": "wentuyi-suffix" in tag_names_at(max(buf.get_char_count() - 2, 0)),
    }
    tag_result.write_text("\n".join(f"{k}={str(v).lower()}" for k, v in tags.items()), encoding="utf-8")
    return True

def focus_view():
    window.present()
    view.grab_focus()
    cursor = buf.get_iter_at_offset(len("prefix "))
    buf.place_cursor(cursor)
    return False

GLib.idle_add(focus_view)
GLib.timeout_add(100, dump)
GLib.timeout_add(250, focus_view)
window.show_all()
signal.signal(signal.SIGTERM, lambda *_: Gtk.main_quit())
Gtk.main()
dump()
PY

rm -f "$RESULT" "$TAG_RESULT" /tmp/wentuyi-rich-ui-encrypted.txt /tmp/wentuyi-rich-xvfb.log
Xvfb "$DISPLAY_ID" -screen 0 1280x800x24 >/tmp/wentuyi-rich-xvfb.log 2>&1 &
XVFB_PID=$!
WM_PID=""

cleanup() {
    if [ -n "$WM_PID" ]; then kill "$WM_PID" 2>/dev/null || true; fi
    kill "$XVFB_PID" 2>/dev/null || true
}
trap cleanup EXIT

sleep 1
if command -v openbox >/dev/null 2>&1; then
    DISPLAY="$DISPLAY_ID" openbox >/tmp/wentuyi-rich-openbox.log 2>&1 &
    WM_PID=$!
    sleep 0.6
fi

export DISPLAY="$DISPLAY_ID"
export WENTUYI_CLI="$CLI"
export WENTUYI_PASSPHRASE="$PASSPHRASE"

python3 "$APP" &
APP_PID=$!
trap 'kill "$APP_PID" 2>/dev/null || true; cleanup' EXIT

WIN=""
for _ in $(seq 1 80); do
    WIN=$(xdotool search --onlyvisible --name "Wentuyi Rich UI Test" 2>/dev/null | head -n1 || true)
    [ -n "$WIN" ] && break
    sleep 0.1
done
[ -n "$WIN" ]
xdotool windowactivate "$WIN" 2>/dev/null || xdotool windowfocus "$WIN" 2>/dev/null || true
sleep 0.3

"$INSERT_SCRIPT" --encrypt-text "direct rich"
TEXT=""
for _ in $(seq 1 160); do
    sleep 0.1
    TEXT=$(cat "$RESULT")
    case "$TEXT" in "prefix WTY3:"*" suffix") break;; esac
done
case "$TEXT" in "prefix WTY3:"*" suffix") ;; *) echo "unexpected rich encrypted text: $TEXT" >&2; exit 3;; esac
grep -q '^prefix_tag=true$' "$TAG_RESULT"
grep -q '^suffix_tag=true$' "$TAG_RESULT"
ENC=${TEXT#prefix }
ENC=${ENC% suffix}
printf "%s" "$ENC" >/tmp/wentuyi-rich-ui-encrypted.txt

xdotool key ctrl+a BackSpace
sleep 0.2
"$INSERT_SCRIPT" --decrypt-text "$ENC"
DEC=""
for _ in $(seq 1 160); do
    sleep 0.1
    DEC=$(cat "$RESULT")
    [ "$DEC" = "direct rich" ] && break
done
[ "$DEC" = "direct rich" ]

printf "linux-rich-encrypted-prefix=%s\nlinux-rich-decrypted=%s\nlinux-rich-tags=preserved\n" "${ENC:0:5}" "$DEC"
