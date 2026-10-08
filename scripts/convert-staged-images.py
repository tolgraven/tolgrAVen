#!/usr/bin/env python3
"""Convert index blobs, preserving unstaged originals and generated-file edits."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def git(*args, **kwargs):
    return subprocess.run(["git", *args], check=True, stdout=subprocess.PIPE, **kwargs).stdout


def main():
    root = Path(os.fsdecode(git("rev-parse", "--show-toplevel").strip()))
    os.chdir(root)
    paths = git("diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z").split(b"\0")
    images = [os.fsdecode(p) for p in paths if p.startswith(b"resources/public/")
              and Path(os.fsdecode(p)).suffix.lower() in {".jpg", ".jpeg", ".png"}
              and not any(icon in os.fsdecode(p) for icon in
                          ("favicon", "android-chrome", "apple-touch-icon", "mstile"))]
    if not images:
        return
    outputs = {}
    with tempfile.TemporaryDirectory(prefix="tolgraven-staged-images-") as scratch:
        for number, image in enumerate(images):
            entry = git("ls-files", "--stage", "-z", "--", image)
            if not entry.startswith(b"100644 ") and not entry.startswith(b"100755 "):
                raise ValueError(f"Image must be a regular file: {image}")
            source = Path(scratch) / str(number) / Path(image).name
            source.parent.mkdir()
            source.write_bytes(git("show", f":{image}"))
            subprocess.run(["bash", str(root / "scripts/convert-images.sh"), "--force", "--", str(source)], check=True)
            for extension in (".webp", ".avif"):
                target = Path(image).with_suffix(extension)
                if target in outputs:
                    raise ValueError(f"Multiple staged originals share a variant: {target}")
                data = source.with_suffix(extension).read_bytes()
                previous = subprocess.run(["git", "show", f":{target}"], stdout=subprocess.PIPE,
                                          stderr=subprocess.DEVNULL)
                if target.is_symlink() or (target.exists() and target.read_bytes() != data
                                          and (previous.returncode or target.read_bytes() != previous.stdout)):
                    raise ValueError(f"Preserving unstaged variant edits: stage or move {target} first")
                outputs[target] = data
        entries = []
        for target, data in outputs.items():
            oid = git("hash-object", "-w", "--stdin", input=data).strip()
            entries.append(b"100644 " + oid + b"\t" + os.fsencode(target) + b"\0")
        git("update-index", "-z", "--index-info", input=b"".join(entries))
        for target, data in outputs.items():
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    print(f"Staged {len(outputs)} image variants from {len(images)} staged originals.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Image conversion failed: {error}", file=sys.stderr)
        sys.exit(1)
