"""Validate all native artifacts and publish an explicitly marked GitHub prerelease."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

PLATFORMS = {"linux-x64", "linux-arm64", "windows-x64", "macos-x64", "macos-arm64"}
SUFFIXES = {"linux": {".tar.gz", ".deb", ".rpm"}, "windows": {".zip", ".msi"}, "macos": {".zip", ".dmg"}}
TAG = re.compile(r"v\d+\.\d+\.\d+-(?:alpha|beta|rc)\.[1-9]\d*\Z")


def validate(directory, tag, commit):
    if not TAG.fullmatch(tag) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("A prerelease tag and full source commit are required")
    manifests = list(directory.rglob("manifest-*.json"))
    seen, assets = set(), []
    for path in manifests:
        data = json.loads(path.read_text(encoding="utf-8"))
        platform = data.get("platform")
        if platform not in PLATFORMS or platform in seen:
            raise ValueError("Unknown or duplicate native platform")
        seen.add(platform)
        smoke = data.get("smoke", {})
        if data.get("tag") != tag or data.get("commit") != commit or smoke.get("version") != tag or smoke.get("status") != "ok" or smoke.get("ui") is not True:
            raise ValueError("Source, version or packaged GUI smoke check mismatch")
        expected = {f"VibeMiner-{tag}-{platform}{suffix}" for suffix in SUFFIXES[platform.split('-')[0]]}
        files = data.get("files", [])
        if len(files) != len(expected) or {item.get("name") for item in files} != expected:
            raise ValueError("Native installer or portable archive is missing")
        for item in files:
            asset = path.parent / item["name"]
            if asset.is_symlink() or not asset.is_file() or asset.stat().st_size != item.get("bytes"):
                raise ValueError("Artifact size or path mismatch")
            with asset.open("rb") as stream:
                actual = hashlib.file_digest(stream, "sha256").hexdigest()
            if actual != item.get("sha256"):
                raise ValueError("Artifact checksum mismatch")
            assets.append(asset)
        assets.append(path)
    if seen != PLATFORMS:
        raise ValueError("Every supported native platform must pass before publication")
    checksums = directory / "SHA256SUMS"
    lines = []
    for asset in sorted(assets, key=lambda item: item.name):
        with asset.open("rb") as stream:
            lines.append(f"{hashlib.file_digest(stream, 'sha256').hexdigest()}  {asset.name}")
    checksums.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return assets + [checksums]


def gh(*args):
    return subprocess.run(["gh", *map(str, args)], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    assets = validate(args.directory, args.tag, args.commit)
    if not args.notes.is_file():
        raise ValueError("Reviewed release notes are required")
    if not args.publish:
        print(f"Validated {len(assets)} prerelease artifacts; publication disabled")
        return
    # Creation fails if the tag already has a release; never replace published binaries.
    gh("release", "create", args.tag, "--verify-tag", "--draft", "--prerelease", "--latest=false", "--title", f"VibeMiner {args.tag}", "--notes-file", args.notes)
    gh("release", "upload", args.tag, *assets)
    gh("release", "edit", args.tag, "--draft=false", "--prerelease", "--latest=false")


if __name__ == "__main__":
    main()
