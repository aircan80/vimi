"""Merge, rendering and git walking of the bundled EG Link NPC display history."""

import json
import os
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
    def test_each_build_keeps_its_newest_commit(self):
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
        self.assertEqual([(2029, 1.0, 1.0)], merged[(639, "1.60.1.70862")][0])
        self.assertEqual("a" * 40, merged[(639, "1.60.1.70862")][1].commit)
        # Same build in both commits: the newest commit wins.
        self.assertEqual([(11, 1.0, 1.0)], merged[(1, "1.60.1.70862")][0])
        self.assertEqual("2026-11-02", merged[(1, "1.60.1.70862")][1].collected_on)
        # Each build of an NPC keeps its own line.
        self.assertEqual([(20, 1.0, 1.0)], merged[(2, "1.60.1.70862")][0])
        self.assertEqual([(21, 1.0, 1.0)], merged[(2, "1.60.1.70888")][0])
        self.assertEqual(3, history.npcs(merged))

    def test_builds_order_numerically_not_as_text(self):
        self.assertLess(history.build_key("1.60.1.9999"), history.build_key("1.60.1.70862"))
        self.assertGreater("1.60.1.9999", "1.60.1.70862")  # the order text would give
        commit = history.Commit(
            "c" * 40,
            "2026-11-03T00:00:00Z",
            {},
            {
                "1.60.1.70862": details(**{"5": shown(2)}),
                "1.60.1.9999": details(**{"5": shown(1)}),
            },
        )
        merged = history.merge([commit])
        lines = history.render(merged, "wowf", "enUS", [commit]).decode().splitlines()
        sources = [line.split("\t")[2] for line in lines if line.startswith("#source")]
        self.assertEqual(["1.60.1.9999", "1.60.1.70862"], sources)
        self.assertEqual(["5\t0\t1:1.0:1.0", "5\t1\t2:1.0:1.0"], lines[-2:])

    def test_records_without_valid_displays_are_skipped(self):
        new = history.Commit(
            "b" * 40,
            "2026-11-02T00:00:00Z",
            {},
            {"1.60.1.2": details(**{"5": {"displays": []}, "6": {"displays": [{"displayId": 0, "probability": 1}]}})},
        )
        self.assertEqual({}, history.merge([new]))

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
        self.assertEqual("#format\tvimi-eg-link-npc-history-v2", lines[0])
        self.assertIn("#npcs\t2", lines)
        self.assertIn("#entries\t2", lines)
        self.assertEqual(["3\t0\t987:1.0:1.0,988:1.0:1.0", "30\t0\t382:1.0:1.0"], lines[-2:])
        notice = history.notice(text.encode(), "wowf", "enUS", [commit], merged)
        self.assertIn("CC BY 4.0", notice)
        self.assertIn("SHA-256 ", notice)


class GitWalkTest(unittest.TestCase):
    def test_reads_every_build_of_every_commit_in_the_window(self):
        # Git marks objects read-only, which Windows refuses to delete.
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temporary:
            repo = Path(temporary)

            def run(*args, date=None):
                env = dict(os.environ)
                if date:
                    env.update(GIT_AUTHOR_DATE=date, GIT_COMMITTER_DATE=date)
                subprocess.run(
                    ["git", "-C", str(repo), *args], check=True, capture_output=True, env=env
                )

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
            run("commit", "-q", "-m", "first", date="2026-11-01T00:00:00Z")
            other = repo / "data" / "wowf" / "1.60.1.70888"
            other.mkdir()
            (other / "creature_details.enUS.json").write_text(json.dumps(details(**{"1": shown(5)})))
            run("add", "-A")
            run("commit", "-q", "-m", "second", date="2026-11-02T00:00:00Z")
            commits = history.read_commits(repo, 60, "wowf", "enUS", "2000-01-01")
            self.assertEqual(2, len(commits))
            merged = history.merge(commits)
            self.assertEqual("2026-11-10", merged[(639, "1.60.1.70862")][1].collected_on)
            self.assertIn((1, "1.60.1.70888"), merged)
            # A window that ends before the second commit leaves it out.
            self.assertEqual(
                1,
                len(
                    history.read_commits(
                        repo, 60, "wowf", "enUS", "2000-01-01", "2026-11-01T12:00:00Z"
                    )
                ),
            )


class WindowTest(unittest.TestCase):
    def test_until_leaves_out_a_commit_backdated_after_it(self):
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temporary:
            repo = Path(temporary)

            def commit(npc, date):
                env = dict(os.environ, GIT_AUTHOR_DATE=date, GIT_COMMITTER_DATE=date)
                folder = repo / "data" / "wowf" / "1.60.1.70862"
                folder.mkdir(parents=True, exist_ok=True)
                (folder / "creature_details.enUS.json").write_text(json.dumps(details(**{npc: shown(1)})))
                for args in (["add", "-A"], ["commit", "-q", "-m", npc]):
                    subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True, env=env)

            subprocess.run(["git", "-C", str(repo), "init", "-q"], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.name", "test"], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.invalid"], check=True)
            commit("1", "2026-10-01T00:00:00Z")
            commit("2", "2026-10-03T00:00:00Z")
            inside = history.read_commits(repo, 60, "wowf", "enUS", "2026-09-01", "2026-10-02T00:00:00Z")
            self.assertEqual(["2026-10-01T00:00:00Z"], [c.committed_at for c in inside])
            self.assertEqual({(1, "1.60.1.70862")}, set(history.merge(inside)))
            both = history.read_commits(repo, 60, "wowf", "enUS", "2026-09-01", "2026-10-04T00:00:00Z")
            self.assertEqual(2, len(both))
            text = history.notice(b"x\n", "wowf", "enUS", inside, history.merge(inside),
                                  "--since 2026-09-01 --until 2026-10-02T00:00:00Z")
            self.assertIn("Window: --since 2026-09-01 --until 2026-10-02T00:00:00Z.", text)


if __name__ == "__main__":
    unittest.main()
