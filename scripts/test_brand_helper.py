import base64
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


HELPER = Path(__file__).resolve().parents[1] / "app/src/main/assets/aicode/git-credential-aicode"


class BrandHelperTest(unittest.TestCase):
    def run_helper(self, root, **variables):
        environment = {
            "PATH": os.environ["PATH"],
            "HOME": str(root),
            **{key: str(value) for key, value in variables.items()},
        }
        return subprocess.run(
            ["sh", str(HELPER), "get"],
            input="protocol=https\nhost=example.invalid\n\n",
            text=True,
            capture_output=True,
            env=environment,
            timeout=3,
            check=True,
        ).stdout

    def seed(self, root):
        root.mkdir(parents=True)
        text = "https://test-user:synthetic-test-value@example.invalid\n"
        (root / "git-credentials").write_text(base64.b64encode(text.encode()).decode()[::-1])

    def test_new_primary_directory_is_used_by_default(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.seed(root / ".aicove")
            result = self.run_helper(root, AICOVE_CRED_NO_PROMPT=1)
            self.assertIn("username=test-user", result)
            self.assertIn("password=synthetic-test-value", result)

    def test_explicit_legacy_directory_remains_supported(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            legacy = root / "legacy"
            self.seed(legacy)
            result = self.run_helper(root, AICODE_DIR=legacy, AICODE_CRED_NO_PROMPT=1)
            self.assertIn("username=test-user", result)

    def test_new_directory_override_has_priority(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary = root / "primary"
            self.seed(primary)
            result = self.run_helper(root, AICOVE_DIR=primary, AICODE_DIR=root / "missing", AICOVE_CRED_NO_PROMPT=1)
            self.assertIn("username=test-user", result)

    def test_both_no_prompt_flags_prevent_ipc_waiting(self):
        for flag in ("AICOVE_CRED_NO_PROMPT", "AICODE_CRED_NO_PROMPT"):
            with self.subTest(flag=flag), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                (root / ".aicove").mkdir()
                self.assertEqual("", self.run_helper(root, **{flag: "1"}))
                self.assertEqual([], list((root / ".aicove").iterdir()))


if __name__ == "__main__":
    unittest.main()
