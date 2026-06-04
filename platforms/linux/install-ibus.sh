#!/usr/bin/env bash
set -euo pipefail

PREFIX="${PREFIX:-/usr/local}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIBDIR="$PREFIX/lib/wentuyi"
COMPONENT_DIR="$PREFIX/share/ibus/component"

(cd "$ROOT" && ./gradlew :desktop-cli:installDist >/dev/null)

mkdir -p "$LIBDIR" "$LIBDIR/ibus" "$COMPONENT_DIR"
cp -R "$ROOT/desktop-cli/build/install/desktop-cli" "$LIBDIR/"
cp "$ROOT/platforms/linux/ibus/wentuyi_ibus.py" "$LIBDIR/ibus/wentuyi_ibus.py"
chmod +x "$LIBDIR/ibus/wentuyi_ibus.py"

cat >"$COMPONENT_DIR/wentuyi.xml" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<component>
  <name>org.freedesktop.IBus.Wentuyi</name>
  <description>Wentuyi secure text input</description>
  <exec>/usr/bin/env WENTUYI_CLI=$LIBDIR/desktop-cli/bin/desktop-cli python3 $LIBDIR/ibus/wentuyi_ibus.py --ibus</exec>
  <version>0.5.1</version>
  <author>Wentuyi</author>
  <license>MIT</license>
  <homepage>https://example.invalid/wentuyi</homepage>
  <textdomain>ibus-wentuyi</textdomain>
  <engines>
    <engine>
      <name>wentuyi</name>
      <longname>Wentuyi</longname>
      <description>Wentuyi secure text input</description>
      <language>zh</language>
      <license>MIT</license>
      <author>Wentuyi</author>
      <layout>us</layout>
      <rank>80</rank>
    </engine>
  </engines>
</component>
XML

"$LIBDIR/ibus/wentuyi_ibus.py" --self-test
printf 'installed=%s\n' "$COMPONENT_DIR/wentuyi.xml"
printf 'set passphrase with: mkdir -p ~/.config/wentuyi && chmod 700 ~/.config/wentuyi && printf %%s YOUR_KEY > ~/.config/wentuyi/passphrase && chmod 600 ~/.config/wentuyi/passphrase\n'
printf 'restart ibus with: ibus restart\n'
