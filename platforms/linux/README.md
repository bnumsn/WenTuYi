# Wentuyi Linux

Linux provides two entry points:

- `platforms/linux/wentuyi-cli`: thin wrapper around `desktop-cli`.
- `platforms/linux/ibus`: IBus engine that commits normal preedit text and can encrypt/decrypt the current preedit buffer.
- `platforms/linux/wentuyi-insert.sh`: direct X11 insert helper that types text / encrypted text / decrypted text into the focused target with `xdotool`, without using the clipboard.

## IBus install

```bash
sudo platforms/linux/install-ibus.sh
```

Configure the shared passphrase used by the IBus engine:

```bash
mkdir -p ~/.config/wentuyi
chmod 700 ~/.config/wentuyi
printf '%s' 'YOUR_KEY' > ~/.config/wentuyi/passphrase
chmod 600 ~/.config/wentuyi/passphrase
ibus restart
```

密钥与明文不经子进程命令行：桥接把口令通过 `WENTUYI_PASSPHRASE` 环境变量传给 `desktop-cli`、明文经 stdin（`--stdin`），解密结果也通过 stdin 交给 `xdotool type --file -`，避免出现在 world-readable 的 `/proc/<pid>/cmdline` / `ps`。`--passphrase` 仅作显式回退。

Add `Wentuyi` from the IBus input method preferences.

## IBus keys

- Type printable ASCII to build the preedit buffer.
- `Enter`: commit the preedit buffer as plain text.
- `Ctrl+Shift+E`: encrypt the preedit buffer with profile `send` and commit the payload.
- `Ctrl+Shift+D`: auto-detect the preedit payload with profile `receive` and commit plaintext.
- `Esc`: clear preedit.

## Contacts and message text

The bridges use `WENTUYI_HOME` (default `~/.config/wentuyi`) and select a contact through
`WENTUYI_PEER` or `--peer NAME` on the shell scripts. Add it using
`desktop-cli peer-add --name bob --peer-qr 'WTYID1...'`, compare every group of the full
256-bit code over a trusted channel, then run
`desktop-cli peer-verify --peer bob --code 'FULL CODE'`. Existing contacts require this
verification again after upgrading; an old 8-digit code is rejected. Sending to an unverified
contact is blocked. Changing either identity invalidates verification. Verified contacts
use WTY5 after a sending chain is established; before that the responder can send WTY4
session encryption. Without a contact, the shared-passphrase path remains available.

Pass `-` as the text value to keep plaintext off the shell script's own command line:

```bash
printf '%s' "$message" | platforms/linux/wentuyi-insert.sh --encrypt-text -
printf '%s' "$payload" | platforms/linux/wentuyi-insert.sh --decrypt-text -
```

The stdin paths preserve boundary spaces, tabs, CRLF, and final newlines. CLI decryption
outputs exact plaintext without an added newline. Direct insertion requires an `xdotool`
version with `type --file -` support.

## Bridge regressions

```bash
PYTHONDONTWRITEBYTECODE=1 python3 platforms/linux/test-insert.py
PYTHONDONTWRITEBYTECODE=1 WENTUYI_TEST_CLI="$PWD/desktop-cli/build/install/desktop-cli/bin/desktop-cli" \
  python3 -m unittest discover -s platforms/linux/ibus -p 'test_*.py'
```

The shell test runs the actual wrappers with a recording xdotool and checks its
`/proc/self/cmdline` and stdin. The IBus test also runs a real JVM round trip when
`WENTUYI_TEST_CLI` is set. Neither requires a desktop session.

## Smoke test

```bash
platforms/linux/test-remote.sh user@192.168.10.16
```

This test builds the CLI, uploads it to the Linux host, runs passphrase and X25519 session-key round trips, and runs the IBus plus direct-insert self-tests without requiring a GUI session.

## UI smoke test

```bash
scp desktop-cli/build/distributions/desktop-cli.zip platforms/linux/ibus/wentuyi_ibus.py platforms/linux/ui-smoke.sh user@192.168.10.16:/tmp/
ssh user@192.168.10.16 'chmod +x /tmp/ui-smoke.sh /tmp/wentuyi_ibus.py && /tmp/ui-smoke.sh'
```

The UI smoke starts Xvfb, registers the Wentuyi IBus component, opens a GTK text field, and verifies plain commit, `Ctrl+Shift+E` encryption, and `Ctrl+Shift+D` decryption from the text field contents.

## Rich text UI smoke

```bash
scp desktop-cli/build/distributions/desktop-cli.zip platforms/linux/wentuyi-insert.sh platforms/linux/ui-rich-smoke.sh user@192.168.10.16:/tmp/
ssh user@192.168.10.16 'chmod +x /tmp/ui-rich-smoke.sh /tmp/wentuyi-insert.sh && /tmp/ui-rich-smoke.sh'
```

The rich smoke starts Xvfb + openbox, opens a GTK `TextView` with styled prefix/suffix text, and uses `wentuyi-insert.sh` to directly type encrypted text at the current rich-text cursor. It then decrypts the inserted `WTY4:` payload back to plaintext. No clipboard is used.

Current verified result:

```text
linux-rich-encrypted-prefix=WTY4:
linux-rich-decrypted=direct rich
linux-rich-tags=preserved
```

Note: GTK `TextView` did not consistently route synthetic `xdotool` key events through the IBus engine in the Xvfb test session. The no-clipboard rich-text path is therefore covered by the direct insert helper; ordinary IBus text input remains covered by `ui-smoke.sh`.
