import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import javafx_sdk


class JavafxSdkTest(unittest.TestCase):
    def archive(self, entries):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
        return stream.getvalue()

    def notices(self):
        return {f'{javafx_sdk.PREFIX}legal/javafx.{module}/{notice}': b'notice'
                for module in javafx_sdk.MODULES for notice in javafx_sdk.REQUIRED_NOTICES}

    def test_all_non_adapter_platforms_extract_only_matching_legal(self):
        for platform in set(javafx_sdk.TARGETS) - {'linux-arm64'}:
            entries = self.notices()
            entries[f'{javafx_sdk.PREFIX}legal/javafx.graphics/jpeg_fx.md'] = b'component'
            entries[f'{javafx_sdk.PREFIX}lib/javafx.base.jar'] = b'not copied'
            entries[f'{javafx_sdk.PREFIX}lib/libglass.so'] = b'not copied'
            archive = self.archive(entries)
            slug = javafx_sdk.TARGETS[platform][0]
            with self.subTest(platform=platform), tempfile.TemporaryDirectory() as temp, patch.dict(javafx_sdk.TARGETS, {platform: (slug, hashlib.sha256(archive).hexdigest())}):
                artifacts, native, legal = javafx_sdk.prepare(archive, Path(temp), platform)
                self.assertIsNone(artifacts)
                self.assertIsNone(native)
                self.assertEqual({'legal'}, {p.name for p in Path(temp).iterdir()})
                self.assertEqual(b'component', (legal / 'javafx.graphics/jpeg_fx.md').read_bytes())
                self.assertIn(f'_{slug}_bin-sdk.zip', (legal / 'SDK_SOURCE.txt').read_text())

    def test_missing_required_notices_fail_even_with_other_files(self):
        for notice in javafx_sdk.REQUIRED_NOTICES:
            entries = self.notices()
            del entries[f'{javafx_sdk.PREFIX}legal/javafx.controls/{notice}']
            archive = self.archive(entries)
            with self.subTest(notice=notice), tempfile.TemporaryDirectory() as temp, patch.dict(javafx_sdk.TARGETS, {'windows-x64': ('windows', hashlib.sha256(archive).hexdigest())}):
                with self.assertRaisesRegex(ValueError, 'legal notices'):
                    javafx_sdk.prepare(archive, Path(temp), 'windows-x64')

    def test_unverified_download_writes_nothing(self):
        with tempfile.TemporaryDirectory() as temp:
            destination = Path(temp) / 'sdk'
            with self.assertRaisesRegex(ValueError, 'checksum'):
                javafx_sdk.prepare(b'wrong archive', destination)
            self.assertFalse(destination.exists())

    def test_verified_archive_cannot_escape_destination(self):
        archive = self.archive({'../outside': b'no'})
        with tempfile.TemporaryDirectory() as temp, patch.dict(javafx_sdk.TARGETS, {'linux-arm64': ('linux-aarch64', hashlib.sha256(archive).hexdigest())}):
            with self.assertRaisesRegex(ValueError, 'path'):
                javafx_sdk.prepare(archive, Path(temp) / 'sdk')
            self.assertFalse((Path(temp) / 'outside').exists())

    def test_only_required_runtime_and_notices_are_extracted(self):
        entries = {f'{javafx_sdk.PREFIX}lib/javafx.{name}.jar': b'jar' for name in javafx_sdk.MODULES}
        entries.update({f'{javafx_sdk.PREFIX}lib/libglass.so': b'native', f'{javafx_sdk.PREFIX}lib/javafx.web.jar': b'unneeded'})
        entries.update(self.notices())
        archive = self.archive(entries)
        with tempfile.TemporaryDirectory() as temp, patch.dict(javafx_sdk.TARGETS, {'linux-arm64': ('linux-aarch64', hashlib.sha256(archive).hexdigest())}):
            artifacts, native, legal = javafx_sdk.prepare(archive, Path(temp))
            self.assertEqual(3, len(list(artifacts.iterdir())))
            self.assertEqual(b'native', (native / 'libglass.so').read_bytes())
            self.assertEqual(b'notice', (legal / 'javafx.graphics/LICENSE').read_bytes())


if __name__ == '__main__':
    unittest.main()
