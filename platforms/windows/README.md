# Wentuyi Windows

Windows currently ships protocol tooling plus two desktop insertion experiments, not a native TSF DLL yet. The shared JVM `desktop-cli` owns the cryptography. The clipboard hotkey bridge is kept only as a plain-text compatibility prototype; rich text must use direct insertion or a native TSF IME path.

## Files

- `wentuyi-cli.ps1`: CLI wrapper. It runs `desktop-cli` directly through Java, avoiding `.bat` argument parsing problems with `WTYID1|...` identity strings.
- `wentuyi-insert.ps1`: direct Unicode insertion helper. It sends generated text to the current caret/selection with `SendInput`, without reading or writing the clipboard.
- `wentuyi-hotkey.ps1`: global hotkey bridge for a logged-in desktop session.
- `install-hotkey.ps1`: optional installer for passphrase config and logon startup task.
- `test-local.ps1`: local protocol + hotkey self-test.
- `test-package.ps1`: zip-package smoke test for a machine that only has the CLI zip and these scripts.
- `ui-smoke.ps1`: interactive desktop smoke test that starts the hotkey bridge, opens Notepad, encrypts selected text with `Ctrl+Alt+E`, then decrypts it with `Ctrl+Alt+D`.
- `ui-rich-smoke.ps1`: RichTextBox smoke test for a logged-in desktop session. It verifies the same global hotkey path against a real WinForms rich text control and records formatting/debug evidence.
- `ui-direct-insert-smoke.ps1`: RichTextBox smoke test for direct insertion. It passes the target control HWND to `wentuyi-insert.ps1`, avoiding clipboard and foreground-keyboard simulation.

## Runtime

Use either a system Java 17+ runtime or place a portable JRE zip named `jre-windows.zip` next to these scripts. `test-package.ps1` and `install-hotkey.ps1` can unpack that zip and set `JAVA_HOME` for the CLI process.

## Package smoke

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\test-package.ps1
```

The remote test machine has been verified with:

```text
windows-ok=windows hello
windows-session-ok=windows session
hotkey-self-test=windows hotkey
direct-insert-self-test=available
```

## Direct insertion

For generated text that already comes from Wentuyi's own UI, insert directly into the active rich text position instead of using copy/paste:

```powershell
$env:WENTUYI_PASSPHRASE = 'YOUR_KEY'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\wentuyi-insert.ps1 -EncryptText 'hello'
```

**Secret handling.** The shared passphrase is read from `WENTUYI_PASSPHRASE` (or the passphrase
file) and forwarded to `desktop-cli` via the environment + stdin, never on its command line —
verified on real PowerShell 5.1. The **hotkey** path (clipboard/selection → encrypt) keeps the
plaintext in PowerShell variables only, so nothing sensitive hits a process command line. The
direct `-EncryptText '<plaintext>'` form above, however, does put that one message on
`wentuyi-insert.ps1`'s own command line (visible to same-user/admin via Process Explorer / WMI);
prefer the hotkey for content you don't want there. (A `-`/stdin form was tried but PowerShell
can't reliably pipe stdin into a param-block script, so it was removed rather than ship a
hang-prone path.)

When a native UI/IME owns the target control handle, pass `-TargetHwnd` to replace the target selection directly. This is the tested rich text primitive. Reading arbitrary selected host text still requires TSF; the clipboard bridge below is not the rich text target.

Verified RichTextBox direct-insert output:

```text
windows-direct-rich-encrypted-prefix=WTY3:
windows-direct-rich-decrypted=direct rich
windows-direct-rich-tags=preserved
```

## Hotkeys

For an interactive desktop session, the legacy clipboard bridge can be used for plain text controls:

```powershell
$env:WENTUYI_PASSPHRASE = 'YOUR_KEY'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\wentuyi-hotkey.ps1
```

- `Ctrl+Alt+E`: copy current selection, encrypt it, paste the `WTY3:` payload.
- `Ctrl+Alt+D`: copy current selection, decrypt it, paste plaintext.

Do not use this bridge as the final rich text implementation. It depends on host Ctrl+C/Ctrl+V behavior and fails in real RichTextBox testing.

Optional startup registration:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\install-hotkey.ps1 -Passphrase 'YOUR_KEY' -RegisterStartup
```

## UI smoke

The UI smoke must run inside a logged-in desktop session because it uses Notepad, clipboard, and registered global hotkeys:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\ui-smoke.ps1
```

Verified output:

```text
windows-ui-encrypted-prefix=WTY3:
windows-ui-decrypted=windows ui
```

## Rich text UI smoke

```powershell
powershell.exe -STA -NoProfile -ExecutionPolicy Bypass -File .\ui-rich-smoke.ps1
```

Current verified result: this test reaches the active desktop session and the hotkey bridge receives `Ctrl+Alt+E`, but RichTextBox selection copy returns an empty clipboard. The bridge therefore cannot replace the selected rich text yet.

Observed evidence:

```text
hotkey-message id=1
transform-start mode=encrypt
clipboard-read mode=encrypt length=0
hotkey-error No selected or clipboard text
```
