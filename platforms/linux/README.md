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

密钥与明文不经子进程命令行：桥接把口令通过 `WENTUYI_PASSPHRASE` 环境变量传给 `desktop-cli`、明文经 stdin（`--stdin`），避免出现在 world-readable 的 `/proc/<pid>/cmdline` / `ps`。`--passphrase` 仅作显式回退。

Add `Wentuyi` from the IBus input method preferences.

## IBus keys

- Type printable ASCII to build the preedit buffer.
- `Enter`: commit the preedit buffer as plain text.
- `Ctrl+Shift+E`: encrypt the preedit buffer and commit the `WTY3:` payload.
- `Ctrl+Shift+D`: decrypt a `WTY3:` preedit buffer and commit plaintext.
- `Esc`: clear preedit.

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

The rich smoke starts Xvfb + openbox, opens a GTK `TextView` with styled prefix/suffix text, and uses `wentuyi-insert.sh` to directly type encrypted text at the current rich-text cursor. It then decrypts the inserted `WTY3:` payload back to plaintext. No clipboard is used.

Current verified result:

```text
linux-rich-encrypted-prefix=WTY3:
linux-rich-decrypted=direct rich
linux-rich-tags=preserved
```

Note: GTK `TextView` did not consistently route synthetic `xdotool` key events through the IBus engine in the Xvfb test session. The no-clipboard rich-text path is therefore covered by the direct insert helper; ordinary IBus text input remains covered by `ui-smoke.sh`.
