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

    def test_unverified_download_writes_nothing(self):
        with tempfile.TemporaryDirectory() as temp:
            destination = Path(temp) / 'sdk'
            with self.assertRaisesRegex(ValueError, 'checksum'):
                javafx_sdk.prepare(b'wrong archive', destination)
            self.assertFalse(destination.exists())

    def test_verified_archive_cannot_escape_destination(self):
        archive = self.archive({'../outside': b'no'})
        with tempfile.TemporaryDirectory() as temp, patch.object(javafx_sdk, 'SHA256', hashlib.sha256(archive).hexdigest()):
            with self.assertRaisesRegex(ValueError, 'path'):
                javafx_sdk.prepare(archive, Path(temp) / 'sdk')
            self.assertFalse((Path(temp) / 'outside').exists())

    def test_only_required_runtime_and_notices_are_extracted(self):
        entries = {f'{javafx_sdk.PREFIX}lib/javafx.{name}.jar': b'jar' for name in javafx_sdk.MODULES}
        entries.update({f'{javafx_sdk.PREFIX}lib/libglass.so': b'native', f'{javafx_sdk.PREFIX}legal/javafx.graphics/LICENSE': b'notice', f'{javafx_sdk.PREFIX}lib/javafx.web.jar': b'unneeded'})
        archive = self.archive(entries)
        with tempfile.TemporaryDirectory() as temp, patch.object(javafx_sdk, 'SHA256', hashlib.sha256(archive).hexdigest()):
            artifacts, native, legal = javafx_sdk.prepare(archive, Path(temp))
            self.assertEqual(3, len(list(artifacts.iterdir())))
            self.assertEqual(b'native', (native / 'libglass.so').read_bytes())
            self.assertEqual(b'notice', (legal / 'javafx.graphics/LICENSE').read_bytes())


if __name__ == '__main__':
    unittest.main()
