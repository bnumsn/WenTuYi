#!/usr/bin/env python3
"""Wentuyi IBus engine.

The engine intentionally delegates all cryptography to desktop-cli so Linux
uses the same JVM protocol implementation as Windows and the smoke tests.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess
import sys
import time
from typing import Iterable


def repo_root() -> Path:
    return Path(__file__).resolve().parents[3]


def default_cli_candidates() -> list[Path]:
    root = repo_root()
    return [
        Path(os.environ.get("WENTUYI_CLI", "")),
        root / "desktop-cli" / "build" / "install" / "desktop-cli" / "bin" / "desktop-cli",
        Path("/usr/local/lib/wentuyi/desktop-cli/bin/desktop-cli"),
        Path("/opt/wentuyi/desktop-cli/bin/desktop-cli"),
    ]


def find_cli() -> str:
    env = os.environ.get("WENTUYI_CLI")
    if env:
        return env
    for candidate in default_cli_candidates():
        if candidate and candidate.exists():
            return str(candidate)
    return "desktop-cli"


def run_cli(args: Iterable[str], passphrase: str = None, stdin_text: str = None) -> str:
    # Keep the shared passphrase and the plaintext off argv: pass the secret via the
    # environment (only same-uid/root can read /proc/<pid>/environ, vs world-readable
    # /proc/<pid>/cmdline) and the text via stdin (--stdin).
    env = os.environ.copy()
    cli_args = list(args)
    # Empty means "no shared key configured" — leave the variable unset rather than
    # exporting a blank one, so the CLI falls through to the profile cleanly.
    if passphrase:
        env["WENTUYI_PASSPHRASE"] = passphrase
    if stdin_text is not None and "--stdin" not in cli_args:
        cli_args.append("--stdin")
    proc = subprocess.run(
        [find_cli(), *cli_args],
        check=False,
        text=True,
        input=stdin_text,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if proc.returncode != 0:
        detail = proc.stderr.strip() or proc.stdout.strip() or f"exit {proc.returncode}"
        raise RuntimeError(detail)
    return proc.stdout.strip()


def debug_log(message: str) -> None:
    path = os.environ.get("WENTUYI_IBUS_LOG")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(f"{time.time():.3f} {message}\n")


def read_passphrase() -> str:
    """Shared key, or "" when none is configured.

    Optional since the engine moved to `send`/`receive`: those read the profile under
    WENTUYI_HOME, so a contact-only setup has no shared key and must not fail here.
    """
    value = os.environ.get("WENTUYI_PASSPHRASE", "").strip()
    if value:
        return value
    path = Path(os.environ.get("WENTUYI_PASSPHRASE_FILE", "~/.config/wentuyi/passphrase")).expanduser()
    if path.exists():
        value = path.read_text(encoding="utf-8").splitlines()[0].strip()
        if value:
            return value
    return ""


def read_peer() -> str:
    """Contact to encrypt to, from WENTUYI_PEER. Empty means the shared-key path."""
    return os.environ.get("WENTUYI_PEER", "").strip()


def self_test() -> int:
    payload = run_cli(["send"], passphrase="ibus-test", stdin_text="ibus smoke")
    plain = run_cli(["receive"], passphrase="ibus-test", stdin_text=payload)
    if plain != "ibus smoke":
        raise RuntimeError(f"unexpected plaintext: {plain}")
    print(f"ibus-self-test={plain}")
    return 0


def run_ibus() -> int:
    import gi

    gi.require_version("IBus", "1.0")
    from gi.repository import GLib, GObject, IBus

    IBus.init()

    class WentuyiEngine(IBus.Engine):
        __gtype_name__ = "WentuyiEngine"

        def __init__(self, connection, object_path):
            super().__init__(connection=connection, object_path=object_path)
            self._preedit = ""
            debug_log(f"engine-instance object_path={object_path}")

        def do_focus_out(self):
            debug_log("focus-out")
            self._commit_preedit()

        def do_reset(self):
            debug_log("reset")
            self._preedit = ""
            self._refresh_preedit()

        def do_process_key_event(self, keyval, keycode, state):
            debug_log(f"key keyval={keyval} keycode={keycode} state={state} preedit={self._preedit!r}")
            if state & IBus.ModifierType.RELEASE_MASK:
                return False
            ctrl = bool(state & IBus.ModifierType.CONTROL_MASK)
            shift = bool(state & IBus.ModifierType.SHIFT_MASK)
            alt = bool(state & IBus.ModifierType.MOD1_MASK)

            if ctrl and shift and keyval in (IBus.KEY_E, IBus.KEY_e):
                return self._transform_preedit("encrypt")
            if ctrl and shift and keyval in (IBus.KEY_D, IBus.KEY_d):
                return self._transform_preedit("decrypt")
            if keyval in (IBus.KEY_Return, IBus.KEY_KP_Enter):
                self._commit_preedit()
                return True
            if keyval == IBus.KEY_space and self._preedit:
                self._preedit += " "
                self._refresh_preedit()
                return True
            if keyval == IBus.KEY_BackSpace and self._preedit:
                self._preedit = self._preedit[:-1]
                self._refresh_preedit()
                return True
            if keyval == IBus.KEY_Escape and self._preedit:
                self._preedit = ""
                self._refresh_preedit()
                return True
            if not ctrl and not alt and 0x20 <= keyval <= 0x7E:
                self._preedit += chr(keyval)
                self._refresh_preedit()
                return True
            return False

        def _transform_preedit(self, mode: str) -> bool:
            debug_log(f"transform-start mode={mode} preedit={self._preedit!r}")
            if not self._preedit:
                self._aux("Wentuyi: empty preedit")
                debug_log(f"transform-empty mode={mode}")
                return True
            try:
                passphrase = read_passphrase()
                if mode == "encrypt":
                    # `send` picks the protocol: WTY5 ratchet for a peer once a sending
                    # chain exists, else the WTY4 session key, else the shared passphrase.
                    args = ["send"]
                    peer = read_peer()
                    if peer:
                        args += ["--peer", peer]
                    result = run_cli(args, passphrase=passphrase, stdin_text=self._preedit)
                else:
                    # `receive` auto-detects WTY5 / WTY4-session / WTY4-passphrase.
                    result = run_cli(["receive"], passphrase=passphrase, stdin_text=self._preedit)
            except Exception as exc:  # IBus engines should report, not crash.
                self._aux(f"Wentuyi: {exc}")
                debug_log(f"transform-error mode={mode} error={exc}")
                return True
            self._preedit = ""
            self._refresh_preedit()
            self.commit_text(IBus.Text.new_from_string(result))
            debug_log(f"transform-commit mode={mode} prefix={result[:5]!r}")
            return True

        def _commit_preedit(self):
            if self._preedit:
                self.commit_text(IBus.Text.new_from_string(self._preedit))
                self._preedit = ""
                self._refresh_preedit()

        def _refresh_preedit(self):
            self.update_preedit_text(
                IBus.Text.new_from_string(self._preedit),
                len(self._preedit),
                bool(self._preedit),
            )

        def _aux(self, message: str):
            self.update_auxiliary_text(IBus.Text.new_from_string(message), True)

    GObject.type_register(WentuyiEngine)
    bus = IBus.Bus()
    loop = GLib.MainLoop()
    bus.connect("disconnected", lambda _bus: loop.quit())
    factory = IBus.Factory.new(bus.get_connection())
    factory.add_engine("wentuyi", GObject.type_from_name("WentuyiEngine"))
    bus.request_name(os.environ.get("WENTUYI_IBUS_BUS_NAME", "org.freedesktop.IBus.Wentuyi"), 0)
    debug_log("engine-ready")

    component = IBus.Component.new(
        "org.freedesktop.IBus.Wentuyi",
        "Wentuyi",
        "0.6.0",
        "MIT",
        "Wentuyi",
        "https://example.invalid/wentuyi",
        "",
        "ibus-wentuyi",
    )
    component.add_engine(
        IBus.EngineDesc.new(
            "wentuyi",
            "Wentuyi",
            "Wentuyi secure text input",
            "zh",
            "MIT",
            "Wentuyi",
            "",
            "us",
        )
    )
    bus.register_component(component)
    loop.run()
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Wentuyi IBus engine")
    parser.add_argument("--self-test", action="store_true", help="run CLI smoke test without importing IBus")
    parser.add_argument("--ibus", action="store_true", help="run as an IBus engine")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    return run_ibus()


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except Exception as exc:
        print(f"wentuyi-ibus error: {exc}", file=sys.stderr)
        raise SystemExit(2)
