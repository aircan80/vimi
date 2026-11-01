import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import native_sqlite


class NativeSqliteSetupTest(unittest.TestCase):
    def test_archive_checksum_is_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with self.assertRaisesRegex(ValueError, "checksum"):
                native_sqlite.windows_dll(b"untrusted", directory)
            self.assertEqual([], list(directory.iterdir()))

    def test_extracts_only_named_library_and_never_archive_paths(self):
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as package:
            package.writestr("sqlite3.dll", b"synthetic dll")
            package.writestr("../unexpected", b"must not extract")
        data = archive.getvalue()
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(native_sqlite, "WINDOWS_SHA3", hashlib.sha3_256(data).hexdigest()):
                directory = Path(temporary) / "native"
                library = native_sqlite.windows_dll(data, directory)
                self.assertEqual(b"synthetic dll", library.read_bytes())
                self.assertEqual([library], list(directory.iterdir()))
                self.assertFalse((Path(temporary) / "unexpected").exists())
