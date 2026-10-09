"""Exercise real codecs and a real Git index in disposable repositories."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
MAGICK = shutil.which("magick") or "convert"
IDENTIFY = [MAGICK, "identify"] if Path(MAGICK).name == "magick" else ["identify"]


class ImageHookTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="tolgraven-image-hook-test-")
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        for name in ("scripts/media/images.clj", "scripts/media/staged.clj", ".githooks/pre-commit"):
            target = self.repo / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / name, target)
        self.run_command("git", "init", "-q")
        self.run_command("git", "config", "user.email", "test@example.invalid")
        self.run_command("git", "config", "user.name", "Image hook test")
        self.run_command("bb", "--classpath", "scripts", "-e", "(require '[media.staged :as staged]) (staged/install!)")
        self.image = self.repo / "resources/public/img/a space\nname.PNG"
        self.image.parent.mkdir(parents=True)

    def run_command(self, *args, check=True):
        return subprocess.run(args, cwd=self.repo, check=check, capture_output=True)

    def create_image(self, color="red"):
        self.run_command(MAGICK, "-size", "16x16", f"xc:{color}", str(self.image))

    def stage_image(self):
        self.run_command("git", "add", "--", str(self.image))

    def test_commit_generates_decodable_variants_from_staged_bytes(self):
        self.create_image()
        staged = self.image.read_bytes()
        self.stage_image()
        self.create_image("blue")
        unstaged = self.image.read_bytes()
        self.run_command("git", "commit", "-qm", "Add image")
        relative = str(self.image.relative_to(self.repo))
        self.assertEqual(staged, self.run_command("git", "show", f"HEAD:{relative}").stdout)
        self.assertEqual(unstaged, self.image.read_bytes())
        for extension in (".webp", ".avif"):
            variant = self.image.with_suffix(extension)
            self.assertEqual(variant.read_bytes(), self.run_command(
                "git", "show", f"HEAD:{Path(relative).with_suffix(extension)}").stdout)
            pixel = self.run_command(MAGICK, str(variant), "-format", "%[fx:r>b]", "info:").stdout
            self.assertEqual(b"1", pixel)
            self.assertEqual(b"16 16", self.run_command(*IDENTIFY, "-format", "%w %h", str(variant)).stdout)

    def test_failure_preserves_index_and_does_not_publish_partial_variants(self):
        self.image.write_bytes(b"invalid image")
        self.stage_image()
        before = self.run_command("git", "write-tree").stdout
        result = self.run_command(".githooks/pre-commit", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, self.run_command("git", "write-tree").stdout)
        self.assertFalse(self.image.with_suffix(".webp").exists())
        self.assertFalse(self.image.with_suffix(".avif").exists())

    def test_avif_failure_does_not_stage_successful_webp(self):
        self.create_image()
        self.stage_image()
        before = self.run_command("git", "write-tree").stdout
        tools = self.repo / "failing-tools"
        tools.mkdir()
        encoder = tools / "magick"
        encoder.write_text("#!/bin/sh\nexit 1\n")
        encoder.chmod(0o755)
        result = subprocess.run([".githooks/pre-commit"], cwd=self.repo,
                                env={**os.environ, "PATH": str(tools) + os.pathsep + os.environ["PATH"]},
                                capture_output=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, self.run_command("git", "write-tree").stdout)
        self.assertFalse(self.image.with_suffix(".webp").exists())

    def test_ambiguous_original_names_leave_index_unchanged(self):
        self.create_image()
        other = self.image.with_suffix(".jpg")
        self.run_command(MAGICK, str(self.image), str(other))
        self.stage_image()
        self.run_command("git", "add", "--", str(other))
        before = self.run_command("git", "write-tree").stdout
        result = self.run_command(".githooks/pre-commit", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn(b"Multiple staged originals", result.stderr)
        self.assertEqual(before, self.run_command("git", "write-tree").stdout)

    def test_preserves_unstaged_generated_edits(self):
        self.create_image()
        self.stage_image()
        self.run_command("git", "commit", "-qm", "Initial image")
        variant = self.image.with_suffix(".webp")
        variant.write_bytes(b"manual edit")
        self.create_image("blue")
        self.stage_image()
        before = self.run_command("git", "write-tree").stdout
        result = self.run_command(".githooks/pre-commit", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn(b"Preserving unstaged", result.stderr)
        self.assertEqual(b"manual edit", variant.read_bytes())
        self.assertEqual(before, self.run_command("git", "write-tree").stdout)

    def test_excludes_icons_and_nonpublic_images(self):
        for name in ("resources/public/favicon.png", "doc/example.png"):
            target = self.repo / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b"not decoded")
            self.run_command("git", "add", "--", name)
        self.run_command("git", "commit", "-qm", "No eligible images")
        self.assertEqual(2, len(self.run_command("git", "ls-tree", "-r", "--name-only", "HEAD").stdout.splitlines()))

    def test_does_not_replace_custom_hook_path(self):
        self.run_command("git", "config", "core.hooksPath", "custom-hooks")
        result = self.run_command("bb", "--classpath", "scripts", "-e", "(require '[media.staged :as staged]) (staged/install!)", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b"custom-hooks", self.run_command("git", "config", "core.hooksPath").stdout.strip())

    def test_modified_original_refreshes_existing_variants(self):
        self.create_image()
        self.stage_image()
        self.run_command("git", "commit", "-qm", "Initial image")
        self.create_image("blue")
        self.stage_image()
        self.run_command("git", "commit", "-qm", "Replace image")
        for extension in (".webp", ".avif"):
            variant = self.image.with_suffix(extension)
            self.assertEqual(b"1", self.run_command(
                MAGICK, str(variant), "-format", "%[fx:b>r]", "info:").stdout)
        self.assertEqual(b"", self.run_command("git", "status", "--porcelain", "--", "resources").stdout)


if __name__ == "__main__":
    unittest.main()
