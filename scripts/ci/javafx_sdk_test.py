import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import javafx_sdk


class JavafxSdkTest(unittest.TestCase):
    def test_pinned_sdk_urls_include_architecture(self):
        for platform in javafx_sdk.TARGETS:
            url, checksum = javafx_sdk.sdk_source(platform)
            architecture = 'aarch64' if platform.endswith('arm64') else 'x64'
            self.assertIn('-' + architecture + '_bin-sdk.zip', url)
            self.assertEqual(64, len(checksum))

    def archive(self, entries):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
        return stream.getvalue()

    def notices(self):
        return {f'{javafx_sdk.PREFIX}legal/javafx.{module}/{notice}': b'notice'
                for module in javafx_sdk.MODULES for notice in javafx_sdk.REQUIRED_NOTICES}

    NATIVES = {
        'linux': ('lib', ['libglass.so', 'libglassgtk3.so', 'libprism_es2.so', 'libjavafx_font_pango.so', 'libdecora_sse.so'],
                  ['libjfxwebkit.so', 'libgstreamer-lite.so', 'libavplugin-54.so', 'libfxplugins.so', 'glass.dll']),
        'windows': ('bin', ['glass.dll', 'prism_d3d.dll', 'javafx_iio.dll', 'msvcp140_1.dll', 'vcruntime140.dll', 'ucrtbase.dll', 'api-ms-win-crt-heap-l1-1-0.dll'],
                    ['jfxwebkit.dll', 'gstreamer-lite.dll', 'glib-lite.dll', 'jfxmedia.dll', 'fxplugins.dll', 'libglass.so']),
        'macos': ('lib', ['libglass.dylib', 'libprism_common.dylib', 'libjavafx_font.dylib'],
                  ['libjfxmedia_avf.dylib', 'libglib-lite.dylib', 'libjfxwebkit.dylib', 'libglass.so']),
    }

    def sdk_entries(self, platform):
        folder, wanted, unwanted = self.NATIVES[platform.split('-')[0]]
        entries = self.notices()
        entries[f'{javafx_sdk.PREFIX}legal/javafx.graphics/jpeg_fx.md'] = b'component'
        entries[f'{javafx_sdk.PREFIX}legal/javafx.web/LICENSE'] = b'not copied'
        entries.update({f'{javafx_sdk.PREFIX}lib/javafx.{name}.jar': self.archive({'Module.class': b'classes'}) for name in javafx_sdk.MODULES})
        entries[f'{javafx_sdk.PREFIX}lib/javafx.web.jar'] = b'not copied'
        entries.update({f'{javafx_sdk.PREFIX}{folder}/{name}': name.encode() for name in wanted + unwanted})
        return entries, wanted

    def test_every_platform_extracts_class_jars_graphics_natives_and_notices(self):
        for platform in javafx_sdk.TARGETS:
            entries, wanted = self.sdk_entries(platform)
            archive = self.archive(entries)
            slug, _, classifier = javafx_sdk.TARGETS[platform]
            with self.subTest(platform=platform), tempfile.TemporaryDirectory() as temp, patch.dict(javafx_sdk.TARGETS, {platform: (slug, hashlib.sha256(archive).hexdigest(), classifier)}):
                artifacts, native, legal = javafx_sdk.prepare(archive, Path(temp), platform)
                self.assertEqual({f'javafx-{name}-25.0.1-{classifier}.jar' for name in javafx_sdk.MODULES}, {p.name for p in artifacts.iterdir()})
                self.assertEqual(set(wanted), {p.name for p in native.iterdir()})
                self.assertEqual(b'component', (legal / 'javafx.graphics/jpeg_fx.md').read_bytes())
                self.assertFalse((legal / 'javafx.web').exists())
                self.assertIn(f'_{slug}_bin-sdk.zip', (legal / 'SDK_SOURCE.txt').read_text())

    def test_classifiers_match_gradle_maven_classifiers(self):
        self.assertEqual({'linux-x64': 'linux', 'linux-arm64': 'linux-aarch64', 'windows-x64': 'win', 'macos-x64': 'mac', 'macos-arm64': 'mac-aarch64'},
                         {platform: target[2] for platform, target in javafx_sdk.TARGETS.items()})

    def test_class_jar_embedding_natives_is_rejected(self):
        entries, _ = self.sdk_entries('linux-x64')
        entries[f'{javafx_sdk.PREFIX}lib/javafx.graphics.jar'] = self.archive({'libglass.so': b'native'})
        archive = self.archive(entries)
        with tempfile.TemporaryDirectory() as temp, patch.dict(javafx_sdk.TARGETS, {'linux-x64': ('linux-x64', hashlib.sha256(archive).hexdigest(), 'linux')}):
            with self.assertRaisesRegex(ValueError, 'embeds native'):
                javafx_sdk.prepare(archive, Path(temp), 'linux-x64')

    def test_missing_glass_library_fails(self):
        entries, _ = self.sdk_entries('windows-x64')
        del entries[f'{javafx_sdk.PREFIX}bin/glass.dll']
        archive = self.archive(entries)
        with tempfile.TemporaryDirectory() as temp, patch.dict(javafx_sdk.TARGETS, {'windows-x64': ('windows-x64', hashlib.sha256(archive).hexdigest(), 'win')}):
            with self.assertRaisesRegex(ValueError, 'absent'):
                javafx_sdk.prepare(archive, Path(temp), 'windows-x64')

    def test_missing_required_notices_fail_even_with_other_files(self):
        for notice in javafx_sdk.REQUIRED_NOTICES:
            entries, _ = self.sdk_entries('windows-x64')
            del entries[f'{javafx_sdk.PREFIX}legal/javafx.controls/{notice}']
            archive = self.archive(entries)
            with self.subTest(notice=notice), tempfile.TemporaryDirectory() as temp, patch.dict(javafx_sdk.TARGETS, {'windows-x64': ('windows-x64', hashlib.sha256(archive).hexdigest(), 'win')}):
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
        with tempfile.TemporaryDirectory() as temp, patch.dict(javafx_sdk.TARGETS, {'linux-arm64': ('linux-aarch64', hashlib.sha256(archive).hexdigest(), 'linux-aarch64')}):
            with self.assertRaisesRegex(ValueError, 'path'):
                javafx_sdk.prepare(archive, Path(temp) / 'sdk')
            self.assertFalse((Path(temp) / 'outside').exists())

    def test_only_required_runtime_and_notices_are_extracted(self):
        entries = {f'{javafx_sdk.PREFIX}lib/javafx.{name}.jar': self.archive({'Module.class': b'classes'}) for name in javafx_sdk.MODULES}
        entries.update({f'{javafx_sdk.PREFIX}lib/libglass.so': b'native', f'{javafx_sdk.PREFIX}lib/javafx.web.jar': b'unneeded'})
        entries.update(self.notices())
        archive = self.archive(entries)
        with tempfile.TemporaryDirectory() as temp, patch.dict(javafx_sdk.TARGETS, {'linux-arm64': ('linux-aarch64', hashlib.sha256(archive).hexdigest(), 'linux-aarch64')}):
            artifacts, native, legal = javafx_sdk.prepare(archive, Path(temp))
            self.assertEqual(3, len(list(artifacts.iterdir())))
            self.assertEqual(b'native', (native / 'libglass.so').read_bytes())
            self.assertEqual(b'notice', (legal / 'javafx.graphics/LICENSE').read_bytes())


if __name__ == '__main__':
    unittest.main()
