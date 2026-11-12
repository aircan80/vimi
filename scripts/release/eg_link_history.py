#!/usr/bin/env python3
"""Builds the bundled EG Link NPC display history from the upstream repository's git history.

Run by hand before a release, never at build time:

    python3 scripts/release/eg_link_history.py

It makes a blobless clone of https://github.com/JIVESCORP/eg-link-output-wowf in a temporary
directory (or reads --repo), walks the commits of the last --days days, and reads every
data/<product>/<build>/creature_details.<locale>.json each commit holds. Per NPC it keeps the
display list from the newest client build; between commits of the same build the newest commit
wins. Records without displays are skipped. Each NPC keeps the build, commit and collection date
it came from.

The output is deterministic for the same commits: a sorted UTF-8 text file (about 120 KB for
5,000 NPCs, so it stays readable in review), and a NOTICE.md with the CC BY 4.0 attribution, the
commit range and the file's SHA-256.
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import hashlib
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

UPSTREAM = "https://github.com/JIVESCORP/eg-link-output-wowf"
FORMAT = "vimi-eg-link-npc-history-v1"
ATTRIBUTION = "Data from Efficient Games (https://efficient.games), licensed under CC BY 4.0."
DEFAULT_OUT = Path(__file__).resolve().parents[2] / (
    "eg-link/src/main/resources/com/vibeminer/eglink/history"
)
BUILD = re.compile(r"\d+\.\d+\.\d+\.\d+")
MAX_DISPLAYS = 64


@dataclasses.dataclass(frozen=True)
class Commit:
    sha: str
    committed_at: str  # ISO-8601 UTC
    collected: dict  # build -> lastCollectedOn of the creature_details dataset, or ""
    files: dict  # build -> parsed creature_details JSON


@dataclasses.dataclass(frozen=True)
class Source:
    build: str
    commit: str
    committed_at: str
    collected_on: str


def build_key(version: str) -> tuple:
    return tuple(int(part) for part in version.split("."))


def displays_of(record) -> list | None:
    """The validated display triples of one creature record, or None when it lists none.

    The bounds are the application's (EgLinkTypes.CreatureDisplay): a record outside them is
    skipped whole, as the application refuses it.
    """
    if not isinstance(record, dict):
        return None
    listed = record.get("displays")
    if not isinstance(listed, list) or not listed or len(listed) > MAX_DISPLAYS:
        return None
    result = []
    for display in listed:
        if not isinstance(display, dict):
            return None
        display_id, probability = display.get("displayId"), display.get("probability")
        scale = display.get("scale", 1.0)
        if (
            isinstance(display_id, bool)
            or not isinstance(display_id, int)
            or not 0 < display_id <= 0xFFFFFFFF
            or isinstance(probability, bool)
            or not isinstance(probability, (int, float))
            or not 0 <= probability <= 1_000_000
            or isinstance(scale, bool)
            or not isinstance(scale, (int, float))
            or not 0 < scale <= 64
        ):
            return None
        result.append((display_id, float(probability), float(scale)))
    return result


def merge(commits: list) -> tuple:
    """Per NPC, the displays of the newest build, then the newest commit, and their sources."""
    best = {}  # npc -> (rank, displays, Source)
    for order, commit in enumerate(sorted(commits, key=lambda c: (c.committed_at, c.sha))):
        for build, content in commit.files.items():
            creatures = content.get("creatures") if isinstance(content, dict) else None
            if not isinstance(creatures, dict):
                continue
            rank = (build_key(build), commit.committed_at, order)
            source = Source(build, commit.sha, commit.committed_at, commit.collected.get(build, ""))
            for key, record in creatures.items():
                if not key.isdigit() or not 0 < int(key) <= 0xFFFFFFFF:
                    continue
                displays = displays_of(record)
                if displays is None:
                    continue
                npc = int(key)
                held = best.get(npc)
                if held is None or rank > held[0]:
                    best[npc] = (rank, displays, source)
    return {npc: (value[1], value[2]) for npc, value in best.items()}


def number(value: float) -> str:
    return repr(float(value))


def render(merged: dict, product: str, locale: str, commits: list) -> bytes:
    """The decoded resource: a header, the sources, then one line per NPC, all sorted."""
    sources = sorted(
        {source for _, source in merged.values()},
        key=lambda s: (build_key(s.build), s.committed_at, s.commit),
    )
    index = {source: n for n, source in enumerate(sources)}
    ordered = sorted(commits, key=lambda c: (c.committed_at, c.sha))
    lines = [
        f"#format\t{FORMAT}",
        f"#upstream\t{UPSTREAM}",
        f"#product\t{product}",
        f"#locale\t{locale}",
        f"#range\t{ordered[0].sha}\t{ordered[-1].sha}" if ordered else "#range\t\t",
        f"#npcs\t{len(merged)}",
    ]
    for source in sources:
        lines.append(
            f"#source\t{index[source]}\t{source.build}\t{source.commit}\t"
            f"{source.committed_at}\t{source.collected_on}"
        )
    for npc in sorted(merged):
        displays, source = merged[npc]
        listed = ",".join(f"{d}:{number(p)}:{number(s)}" for d, p, s in displays)
        lines.append(f"{npc}\t{index[source]}\t{listed}")
    return ("\n".join(lines) + "\n").encode("utf-8")


def notice(decoded: bytes, product: str, locale: str, commits: list, merged: dict) -> str:
    ordered = sorted(commits, key=lambda c: (c.committed_at, c.sha))
    builds = sorted({source.build for _, source in merged.values()}, key=build_key)
    first, last = (ordered[0], ordered[-1]) if ordered else (None, None)
    return "\n".join(
        [
            "# EG Link NPC display history",
            "",
            ATTRIBUTION,
            "License: https://creativecommons.org/licenses/by/4.0/ . Blizzard Entertainment",
            "retains ownership of the game content the observations describe.",
            "",
            f"Source: {UPSTREAM}",
            f"Product {product}, dataset creature_details.{locale}, builds {', '.join(builds)}.",
            f"Commits: {len(ordered)}, from {first.sha} ({first.committed_at})"
            f" to {last.sha} ({last.committed_at})."
            if ordered
            else "Commits: none.",
            "",
            "Changes made: only each NPC's display list (display ID, probability, scale) is kept,",
            "from the newest client build that records the NPC (the newest commit within a",
            "build), with that build, commit and collection date. Every other field is dropped.",
            "",
            f"npc-displays.txt is {len(decoded)} bytes,"
            f" SHA-256 {hashlib.sha256(decoded).hexdigest()}; {len(merged)} NPCs.",
            "Generated by scripts/release/eg_link_history.py.",
            "",
        ]
    )


def git(repo: Path, *args: str) -> bytes:
    return subprocess.run(
        ["git", "-C", str(repo), *args], check=True, capture_output=True
    ).stdout


def read_commits(repo: Path, days: int, product: str, locale: str) -> list:
    since = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=days)).strftime("%Y-%m-%d")
    listed = git(repo, "log", f"--since={since}", "--format=%H %cI", "HEAD").decode().split("\n")
    parsed = {}  # blob -> JSON, shared by commits that keep a file unchanged
    commits = []
    name = f"creature_details.{locale}.json"
    for line in filter(None, listed):
        sha, stamp = line.split(" ")
        committed = (
            dt.datetime.fromisoformat(stamp).astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        )
        tree = git(repo, "ls-tree", "-r", sha, f"data/{product}").decode()
        files = {}
        for entry in filter(None, tree.split("\n")):
            meta, path = entry.split("\t", 1)
            parts = path.split("/")
            if len(parts) != 4 or parts[3] != name or not BUILD.fullmatch(parts[2]):
                continue
            blob = meta.split()[2]
            if blob not in parsed:
                parsed[blob] = json.loads(git(repo, "cat-file", "blob", blob))
            files[parts[2]] = parsed[blob]
        if not files:
            continue
        collected = {}
        try:
            index = json.loads(git(repo, "show", f"{sha}:data/index.json"))
            for build, datasets in index.get("products", {}).get(product, {}).items():
                info = datasets.get(f"creature_details.{locale}", {})
                collected[build] = str(info.get("lastCollectedOn", ""))
        except (subprocess.CalledProcessError, ValueError, AttributeError):
            pass
        commits.append(Commit(sha, committed, collected, files))
    return commits


def write(out: Path, decoded: bytes, text: str) -> None:
    out.mkdir(parents=True, exist_ok=True)
    (out / "npc-displays.txt").write_bytes(decoded)
    (out / "NOTICE.md").write_text(text, encoding="utf-8")


def main(argv: list | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--repo", type=Path, help="an existing clone instead of a fresh one")
    parser.add_argument("--days", type=int, default=60)
    parser.add_argument("--product", default="wowf")
    parser.add_argument("--locale", default="enUS")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args(argv)
    with tempfile.TemporaryDirectory(prefix="eg-link-history-", ignore_cleanup_errors=True) as temporary:
        repo = args.repo
        if repo is None:
            repo = Path(temporary) / "repo"
            subprocess.run(
                ["git", "clone", "--quiet", "--filter=blob:none", "--no-checkout", UPSTREAM, str(repo)],
                check=True,
            )
        commits = read_commits(repo, args.days, args.product, args.locale)
    if not commits:
        print("No creature_details in the requested window", file=sys.stderr)
        return 1
    merged = merge(commits)
    decoded = render(merged, args.product, args.locale, commits)
    write(args.out, decoded, notice(decoded, args.product, args.locale, commits, merged))
    newest = max(commits, key=lambda c: (c.committed_at, c.sha))
    latest = max(newest.files, key=build_key)
    single = sum(
        1
        for record in newest.files[latest].get("creatures", {}).values()
        if displays_of(record) is not None
    )
    print(
        f"{len(merged)} NPCs with displays from {len(commits)} commits;"
        f" the latest single snapshot ({args.product} {latest} at {newest.sha[:12]}) has {single}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
