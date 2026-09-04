#!/usr/bin/env bash
# Guards against the bug class that shipped a white card under near-white text in dark mode:
# a view painted with a literal colour instead of going through Palette. Colours must come
# from Palette so both schemes stay in sync — PaletteTest checks the palette's contrast, but
# it cannot see a view that never asks the palette in the first place.
#
# Allowed exceptions are listed explicitly, each with a reason.
set -euo pipefail
cd "$(dirname "$0")/.."

# CameraScanActivity: a camera viewfinder is black in both schemes, and its overlay text is
#   white on that black — theming it would make the overlay unreadable over the preview.
# TextImageCodec / BitmapUtils: these render *images and QR codes*, whose colours must be
#   independent of the viewer's theme or a dark-mode user would produce dark-on-dark output.
# Palette / KeyboardUi: the definitions themselves.
ALLOW='CameraScanActivity\.kt|TextImageCodec\.kt|BitmapUtils\.kt|Palette\.kt|KeyboardUi\.kt'

hits=$(grep -rnE 'Color\.(WHITE|BLACK|GRAY|LTGRAY|DKGRAY)|Color\.rgb\(|Color\.parseColor|0xFF[0-9A-Fa-f]{6}\.toInt\(\)' \
       app/src/main/java/com/wentuyi/app/*.kt | grep -vE "$ALLOW" || true)

if [ -n "$hits" ]; then
  echo "✗ 发现绕过 Palette 的硬编码颜色（深色模式下会失效）："
  echo "$hits" | sed 's/^/    /'
  echo
  echo "  改用 Palette.* / KeyboardUi.COLOR_*；若确有理由保持固定色，"
  echo "  请把文件加进本脚本的 ALLOW 并写明原因。"
  exit 1
fi
echo "✓ UI 代码未发现绕过 Palette 的硬编码颜色"
