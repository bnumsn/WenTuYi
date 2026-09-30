import os
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch

import wentuyi_ibus


class CliDiscoveryTest(unittest.TestCase):
    def test_unset_environment_never_adds_current_directory(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertNotIn(Path("."), wentuyi_ibus.default_cli_candidates())

    def test_discovery_skips_directories_and_non_executable_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            regular = root / "not-executable"
            regular.write_text("not a cli")
            executable = root / "desktop-cli"
            executable.write_text("#!/bin/sh\n")
            executable.chmod(0o700)
            with patch.dict(os.environ, {}, clear=True), patch.object(
                wentuyi_ibus, "default_cli_candidates", return_value=[root, regular, executable]
            ):
                self.assertEqual(str(executable), wentuyi_ibus.find_cli())

    def test_no_installed_candidate_uses_path_lookup(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(
            wentuyi_ibus, "default_cli_candidates", return_value=[]
        ):
            self.assertEqual("desktop-cli", wentuyi_ibus.find_cli())


class CliTextTest(unittest.TestCase):
    def test_receive_preserves_whitespace_and_crlf(self):
        text = " \t中文\r\n第二行 \t\n\n"
        result = subprocess.CompletedProcess([], 0, text.encode("utf-8"), b"")
        with patch.object(wentuyi_ibus.subprocess, "run", return_value=result) as run:
            self.assertEqual(text, wentuyi_ibus.run_cli(["receive"], stdin_text="WTY5:example"))
            self.assertEqual(b"WTY5:example", run.call_args.kwargs["input"])

    def test_send_forwards_exact_utf8_without_putting_plaintext_in_argv(self):
        text = " \t中文 🦋\r\n第二行 \t\n\n"
        result = subprocess.CompletedProcess([], 0, b"WTY4:example\n", b"")
        with patch.object(wentuyi_ibus.subprocess, "run", return_value=result) as run:
            self.assertEqual("WTY4:example", wentuyi_ibus.run_cli(["send"], stdin_text=text))
            self.assertEqual(text.encode("utf-8"), run.call_args.kwargs["input"])
            self.assertNotIn(text, run.call_args.args[0])

    @unittest.skipUnless(os.environ.get("WENTUYI_TEST_CLI"), "set WENTUYI_TEST_CLI to run the real JVM bridge")
    def test_real_cli_round_trip(self):
        text = " \t中文 🦋\r\n第二行 \t\n\n"
        with tempfile.TemporaryDirectory(prefix="wentuyi-ibus-cli-") as home, patch.dict(
            os.environ, {"WENTUYI_CLI": os.environ["WENTUYI_TEST_CLI"], "WENTUYI_HOME": home}
        ):
            payload = wentuyi_ibus.run_cli(["send"], passphrase="ibus-boundary-test", stdin_text=text)
            self.assertEqual(text, wentuyi_ibus.run_cli(["receive"], passphrase="ibus-boundary-test", stdin_text=payload))


if __name__ == "__main__":
    unittest.main()
