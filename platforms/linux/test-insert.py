#!/usr/bin/env python3
"""Run the real shell bridges with a recording xdotool, without requiring X11."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
INSERT = ROOT / "platforms/linux/wentuyi-insert.sh"
SEND = ROOT / "platforms/linux/wentuyi-send.sh"
TEXT = " \t中文 🦋\r\n第二行 \t\n\n"


class InsertSecurityTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="wentuyi-bridge-test-")
        self.work = Path(self.directory.name)
        xdotool = self.work / "xdotool"
        xdotool.write_text('''#!/usr/bin/env python3
import os, pathlib, sys
work = pathlib.Path(os.environ["WENTUYI_TEST_RECORD"])
(work / "xdotool.argv").write_bytes(pathlib.Path("/proc/self/cmdline").read_bytes())
(work / "typed").write_bytes(sys.stdin.buffer.read())
assert sys.argv[1:] == ["type", "--delay", "1", "--clearmodifiers", "--file", "-"]
''')
        xdotool.chmod(0o700)
        cli = self.work / "desktop-cli"
        cli.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
work = pathlib.Path(os.environ["WENTUYI_TEST_RECORD"])
(work / "cli.argv").write_bytes(pathlib.Path("/proc/self/cmdline").read_bytes())
(work / "cli.stdin").write_bytes(sys.stdin.buffer.read())
if sys.argv[1] == "receive":
    sys.stdout.write(os.environ["WENTUYI_TEST_PLAIN"])
else:
    sys.stdout.write("WTY4:recorded-payload\\n")
''')
        cli.chmod(0o700)
        self.env = dict(os.environ, PATH=f"{self.work}:{os.environ['PATH']}",
                        WENTUYI_CLI=str(cli), WENTUYI_TEST_RECORD=str(self.work),
                        WENTUYI_TEST_PLAIN=TEXT, WENTUYI_PASSPHRASE="bridge key",
                        WENTUYI_OUT_DIR=str(self.work / "out"), WENTUYI_PEER="")

    def tearDown(self):
        self.directory.cleanup()

    def run_bridge(self, script, args, value):
        result = subprocess.run(["bash", str(script), *args], input=value.encode("utf-8"),
                                capture_output=True, env=self.env, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr.decode("utf-8"))
        self.assertNotIn(TEXT.encode("utf-8"), (self.work / "xdotool.argv").read_bytes())
        return (self.work / "typed").read_bytes().decode("utf-8")

    def test_plain_insert_uses_stdin_and_preserves_boundary_whitespace(self):
        self.assertEqual(TEXT, self.run_bridge(INSERT, ["--text", "-"], TEXT))

    def test_decrypt_keeps_plaintext_out_of_process_arguments_and_preserves_all_bytes(self):
        self.assertEqual(TEXT, self.run_bridge(INSERT, ["--decrypt-text", "-"], "WTY4:example\n"))
        self.assertNotIn(TEXT.encode("utf-8"), (self.work / "cli.argv").read_bytes())

    def test_encrypt_preserves_stdin_and_does_not_type_protocol_newline(self):
        self.assertEqual("WTY4:recorded-payload", self.run_bridge(INSERT, ["--encrypt-text", "-"], TEXT))
        self.assertEqual(TEXT.encode("utf-8"), (self.work / "cli.stdin").read_bytes())
        self.assertNotIn(TEXT.encode("utf-8"), (self.work / "cli.argv").read_bytes())

    def test_send_focused_routes_plaintext_through_insert_stdin(self):
        self.assertEqual(TEXT, self.run_bridge(SEND, ["--app", "focused", "--text", "-"], TEXT))

    def test_send_focused_encryption_preserves_the_message(self):
        self.assertEqual("WTY4:recorded-payload", self.run_bridge(SEND, ["--encrypt-text", "-"], TEXT))
        self.assertEqual(TEXT.encode("utf-8"), (self.work / "cli.stdin").read_bytes())


if __name__ == "__main__":
    unittest.main()
