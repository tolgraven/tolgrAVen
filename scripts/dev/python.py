"""Prepare pinned local codec dependencies and optionally run a Python command."""
import os
from pathlib import Path
import subprocess
import sys
import venv

ROOT = Path(__file__).resolve().parents[2]
ENVIRONMENT = ROOT / ".local-wip/python"
PACKAGES = {"fonttools": "4.60.2", "Brotli": "1.2.0"}
PROBE = ("from importlib.metadata import version; from fontTools import subset; "
         "import brotli; " + "; ".join(
             f"assert version({name!r}) == {version!r}"
             for name, version in PACKAGES.items()))


def interpreter(directory):
    return directory / ("Scripts/python.exe" if os.name == "nt" else "bin/python3")


def ready(python):
    return python.is_file() and subprocess.run(
        [str(python), "-I", "-c", PROBE],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0


def prepare():
    python = interpreter(ENVIRONMENT)
    if not ready(python):
        print("Preparing local Python icon tools…", file=sys.stderr, flush=True)
        venv.EnvBuilder(with_pip=True).create(ENVIRONMENT)
        subprocess.run([str(python), "-I", "-m", "pip", "install",
                        "--disable-pip-version-check", "--no-deps",
                        *(f"{name}=={version}" for name, version in PACKAGES.items())],
                       check=True)
        subprocess.run([str(python), "-I", "-c", PROBE], check=True)
    return python


def main(args):
    python = prepare()
    if args:
        return subprocess.run([str(python), "-I", *args]).returncode
    print(f"Local Python icon tools ready: {python}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"Local Python setup failed: {error}. Install Python 3 with venv/pip "
              "support, then retry bb dev:setup.", file=sys.stderr)
        sys.exit(1)
