"""Merge, rendering and git walking of the bundled EG Link NPC display history."""

import json
from pathlib import Path
import subprocess
import tempfile
import unittest

import eg_link_history as history


def details(**creatures):
    return {"creatures": {str(npc): record for npc, record in creatures.items()}}


def shown(*displays):
    return {"displays": [{"displayId": d, "probability": 1.0, "scale": 1.0} for d in displays]}


class MergeTest(unittest.TestCase):
    def test_newest_build_wins_then_newest_commit(self):
        old = history.Commit(
            "a" * 40,
            "2026-11-01T00:00:00Z",
            {"1.60.1.70862": "2026-11-01"},
            {"1.60.1.70862": details(**{"639": shown(2029), "1": shown(10)})},
        )
        new = history.Commit(
            "b" * 40,
            "2026-11-02T00:00:00Z",
            {"1.60.1.70862": "2026-11-02", "1.60.1.70888": "2026-11-02"},
            {
                "1.60.1.70862": details(**{"1": shown(11), "2": shown(20)}),
                "1.60.1.70888": details(**{"2": shown(21)}),
            },
        )
        merged = history.merge([new, old])
        # Only the older 70862 commit knows NPC 639: kept, with that commit as its source.
        self.assertEqual([(2029, 1.0, 1.0)], merged[639][0])
        self.assertEqual("a" * 40, merged[639][1].commit)
        # Same build in both commits: the newest commit wins.
        self.assertEqual([(11, 1.0, 1.0)], merged[1][0])
        self.assertEqual("2026-11-02", merged[1][1].collected_on)
        # The newest build wins over an older build in the same commit.
        self.assertEqual([(21, 1.0, 1.0)], merged[2][0])
        self.assertEqual("1.60.1.70888", merged[2][1].build)

    def test_records_without_valid_displays_never_replace_older_ones(self):
        old = history.Commit("a" * 40, "2026-11-01T00:00:00Z", {}, {"1.60.1.1": details(**{"5": shown(7)})})
        new = history.Commit(
            "b" * 40,
            "2026-11-02T00:00:00Z",
            {},
            {"1.60.1.2": details(**{"5": {"displays": []}, "6": {"displays": [{"displayId": 0, "probability": 1}]}})},
        )
        merged = history.merge([old, new])
        self.assertEqual([(7, 1.0, 1.0)], merged[5][0])
        self.assertNotIn(6, merged)

    def test_rendering_is_sorted_and_deterministic(self):
        commit = history.Commit(
            "c" * 40,
            "2026-11-03T00:00:00Z",
            {"1.60.1.70862": "2026-11-03"},
            {"1.60.1.70862": details(**{"30": shown(382), "3": shown(987, 988)})},
        )
        merged = history.merge([commit])
        text = history.render(merged, "wowf", "enUS", [commit]).decode()
        self.assertEqual(text, history.render(merged, "wowf", "enUS", [commit]).decode())
        lines = text.splitlines()
        self.assertEqual("#format\tvimi-eg-link-npc-history-v1", lines[0])
        self.assertIn("#npcs\t2", lines)
        self.assertEqual(["3\t0\t987:1.0:1.0,988:1.0:1.0", "30\t0\t382:1.0:1.0"], lines[-2:])
        notice = history.notice(text.encode(), "wowf", "enUS", [commit], merged)
        self.assertIn("CC BY 4.0", notice)
        self.assertIn("SHA-256 ", notice)


class GitWalkTest(unittest.TestCase):
    def test_reads_every_build_of_every_commit_in_the_window(self):
        # Git marks objects read-only, which Windows refuses to delete.
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temporary:
            repo = Path(temporary)

            def run(*args):
                subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True)

            run("init", "-q")
            run("config", "user.name", "test")
            run("config", "user.email", "test@example.invalid")
            folder = repo / "data" / "wowf" / "1.60.1.70862"
            folder.mkdir(parents=True)
            (folder / "creature_details.enUS.json").write_text(json.dumps(details(**{"639": shown(2029)})))
            (repo / "data" / "index.json").write_text(
                json.dumps({"products": {"wowf": {"1.60.1.70862": {"creature_details.enUS": {"lastCollectedOn": "2026-11-10"}}}}})
            )
            run("add", "-A")
            run("commit", "-q", "-m", "first")
            other = repo / "data" / "wowf" / "1.60.1.70888"
            other.mkdir()
            (other / "creature_details.enUS.json").write_text(json.dumps(details(**{"1": shown(5)})))
            run("add", "-A")
            run("commit", "-q", "-m", "second")
            commits = history.read_commits(repo, 60, "wowf", "enUS")
            self.assertEqual(2, len(commits))
            merged = history.merge(commits)
            self.assertEqual("2026-11-10", merged[639][1].collected_on)
            self.assertEqual("1.60.1.70888", merged[1][1].build)


if __name__ == "__main__":
    unittest.main()
