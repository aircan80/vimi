import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from package_app import version
from publish_prerelease import PLATFORMS, SUFFIXES, validate


class VersionTest(unittest.TestCase):
    def test_channel_versions_are_ordered_and_numeric(self):
        self.assertEqual('1.1.1', version('v0.1.0-alpha.1'))
        self.assertEqual('1.1.301', version('v0.1.0-beta.1'))
        self.assertEqual('1.1.601', version('v0.1.0-rc.1'))
        self.assertEqual('1.1.1001', version('v0.1.1-alpha.1'))

    def test_stable_and_invalid_installer_versions_are_rejected(self):
        for tag in ['v1.0.0', 'v01.0.0-alpha.1', 'v0.1.0-alpha.0', 'v0.1.0-alpha.300', 'v255.0.0-alpha.1', 'v0.0.66-rc.1']:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                version(tag)


class PublicationTest(unittest.TestCase):
    tag = 'v0.1.0-alpha.1'
    commit = 'a' * 40

    def fixture(self, root):
        for platform in PLATFORMS:
            folder = root / platform
            folder.mkdir()
            files = []
            for suffix in SUFFIXES[platform.split('-')[0]]:
                name = f'VibeMiner-{self.tag}-{platform}{suffix}'
                payload = name.encode()
                (folder / name).write_bytes(payload)
                files.append({'name': name, 'bytes': len(payload), 'sha256': hashlib.sha256(payload).hexdigest()})
            manifest = {'tag': self.tag, 'commit': self.commit, 'platform': platform, 'smoke': {'version': self.tag, 'status': 'ok', 'ui': True}, 'files': files}
            (folder / f'manifest-{platform}.json').write_text(json.dumps(manifest))

    def test_all_five_platforms_produce_complete_checksums(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            assets = validate(root, self.tag, self.commit)
            self.assertEqual(18, len(assets))
            self.assertEqual(17, len((root / 'SHA256SUMS').read_text().splitlines()))

    def test_missing_platform_prevents_publication(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            next(root.rglob('manifest-linux-x64.json')).unlink()
            with self.assertRaisesRegex(ValueError, 'Every supported'):
                validate(root, self.tag, self.commit)

    def test_tampered_asset_prevents_publication(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            next(root.rglob('*.msi')).write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError, 'Artifact'):
                validate(root, self.tag, self.commit)

    def test_failed_ui_or_wrong_commit_prevents_publication(self):
        for field, value in [('commit', 'b' * 40), ('smoke', {'status': 'ok', 'version': self.tag, 'ui': False})]:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                self.fixture(root)
                path = next(root.rglob('manifest-*.json'))
                data = json.loads(path.read_text())
                data[field] = value
                path.write_text(json.dumps(data))
                with self.assertRaisesRegex(ValueError, 'mismatch'):
                    validate(root, self.tag, self.commit)


if __name__ == '__main__':
    unittest.main()
