#!/usr/bin/env bash
set -euo pipefail

HOST="${1:-user@192.168.10.16}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

(cd "$ROOT" && ./gradlew :desktop-cli:distZip >/dev/null)
scp "$ROOT/desktop-cli/build/distributions/desktop-cli.zip" "$HOST:/tmp/wentuyi-desktop-cli.zip" >/dev/null
scp "$ROOT/platforms/linux/ibus/wentuyi_ibus.py" "$HOST:/tmp/wentuyi_ibus.py" >/dev/null
scp "$ROOT/platforms/linux/wentuyi-insert.sh" "$HOST:/tmp/wentuyi-insert.sh" >/dev/null
ssh "$HOST" 'set -euo pipefail
chmod +x /tmp/wentuyi-insert.sh
rm -rf /tmp/wentuyi-desktop-cli
mkdir -p /tmp/wentuyi-desktop-cli
unzip -q /tmp/wentuyi-desktop-cli.zip -d /tmp/wentuyi-desktop-cli
CLI=/tmp/wentuyi-desktop-cli/desktop-cli/bin/desktop-cli
PAYLOAD=$($CLI encrypt-text --passphrase test-key remote linux)
PLAIN=$($CLI decrypt-text --passphrase test-key "$PAYLOAD")
test "$PLAIN" = "remote linux"
A=$($CLI gen-identity --name linux-a)
B=$($CLI gen-identity --name linux-b)
A_BACKUP=$(printf "%s\n" "$A" | grep "^backup=" | sed "s/^backup=//")
B_BACKUP=$(printf "%s\n" "$B" | grep "^backup=" | sed "s/^backup=//")
A_QR=$(printf "%s\n" "$A" | grep "^identityQr=" | sed "s/^identityQr=//")
B_QR=$(printf "%s\n" "$B" | grep "^identityQr=" | sed "s/^identityQr=//")
SESSION_PAYLOAD=$($CLI session-encrypt --backup "$A_BACKUP" --peer-qr "$B_QR" remote session)
SESSION_PLAIN=$($CLI session-decrypt --backup "$B_BACKUP" --peer-qr "$A_QR" "$SESSION_PAYLOAD")
test "$SESSION_PLAIN" = "remote session"
WENTUYI_CLI="$CLI" python3 /tmp/wentuyi_ibus.py --self-test
WENTUYI_CLI="$CLI" /tmp/wentuyi-insert.sh --self-test
printf "linux-ok=%s\nlinux-session-ok=%s\n" "$PLAIN" "$SESSION_PLAIN"
'
