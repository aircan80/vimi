"""Select the existing components conservatively, without executing changed code."""

import argparse
import re
import subprocess
from pathlib import PurePosixPath

DOCUMENT_FILES = {"LICENSE", "NOTICE", "COPYING"}
DOCUMENT_SUFFIXES = {".md", ".rst"}
DOCUMENT_ASSET_SUFFIXES = {".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp"}
ZERO_SHA = "0" * 40


def documentation_only(path):
    """Executable/build/workflow paths always win over documentation extensions."""
    parsed = PurePosixPath(path)
    if parsed.parts and parsed.parts[0] in {".github", "scripts", "config", "gradle"}:
        return False
    return (
        parsed.suffix.lower() in DOCUMENT_SUFFIXES
        or path in DOCUMENT_FILES
        or (path.startswith("docs/") and parsed.suffix.lower() in DOCUMENT_ASSET_SUFFIXES)
    )


def select(paths):
    # Only object-store exists today. Unknown paths deliberately select it as well.
    # Add component roots and their consumer graph here when the next component exists.
    return any(not documentation_only(path) for path in paths)


def changed_paths(base, head):
    if not re.fullmatch(r"[0-9a-fA-F]{40}", base) or not re.fullmatch(
        r"[0-9a-fA-F]{40}", head
    ):
        raise ValueError("Expected full commit identifiers")
    if base == ZERO_SHA:
        # A newly created branch has no before commit. Check its complete tree.
        output = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", "-z", head])
    else:
        output = subprocess.check_output(
            ["git", "diff", "--no-renames", "--name-only", "-z", base, head, "--"]
        )
    return [path.decode("utf-8", errors="surrogateescape") for path in output.split(b"\0") if path]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event", required=True)
    parser.add_argument("--base", default=ZERO_SHA)
    parser.add_argument("--head", required=True)
    args = parser.parse_args()
    if args.event == "workflow_dispatch":
        selected = True
    else:
        try:
            selected = select(changed_paths(args.base, args.head))
        except (subprocess.CalledProcessError, ValueError):
            # Force pushes can make the previous commit unavailable. Never skip silently.
            selected = True
    print(f"object_store={str(selected).lower()}")


if __name__ == "__main__":
    main()
