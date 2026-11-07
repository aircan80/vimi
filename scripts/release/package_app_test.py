from pathlib import Path
import tempfile
import unittest
import zipfile

import package_app
from package_app import check_native_loading, require_class_only_javafx


class ClassOnlyJavafxTest(unittest.TestCase):
    def stage(self, root, graphics_entries):
        staging = root / 'staging'
        staging.mkdir()
        for module, entries in (('base', ['Base.class']), ('graphics', graphics_entries), ('controls', ['Control.class'])):
            with zipfile.ZipFile(staging / f'javafx-{module}-25.0.1-linux.jar', 'w') as jar:
                for entry in entries:
                    jar.writestr(entry, b'x')
        native = root / 'native'
        native.mkdir()
        (native / 'libglass.so').write_bytes(b'native')
        return staging, native

    def test_class_only_jars_with_image_natives_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            staging, native = self.stage(Path(temp), ['Glass.class'])
            require_class_only_javafx(staging, native, 'linux')

    def test_maven_platform_jar_with_embedded_natives_fails(self):
        for library in ('libglass.so', 'glass.dll', 'libglass.dylib'):
            with self.subTest(library=library), tempfile.TemporaryDirectory() as temp:
                staging, native = self.stage(Path(temp), ['Glass.class', library])
                with self.assertRaisesRegex(ValueError, 'openjfx/cache'):
                    require_class_only_javafx(staging, native, 'linux')

    def test_missing_platform_glass_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            staging, native = self.stage(Path(temp), ['Glass.class'])
            with self.assertRaisesRegex(ValueError, 'glass'):
                require_class_only_javafx(staging, native, 'windows')


class NativeLoadingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.image = self.root / 'VibeMiner'
        self.natives = self.image / 'lib/app/native/javafx'
        self.natives.mkdir(parents=True)
        self.cache = self.root / 'cache'

    def tearDown(self):
        self.temp.cleanup()

    def log(self, *lines):
        return '\n'.join(['Picked up JAVA_TOOL_OPTIONS: -Djavafx.verbose=true', *lines, 'Loaded 12 world tiles from cache']) + '\n'

    def test_natives_from_image_library_path_pass(self):
        log = self.log(f'Loaded {self.natives / "libprism_es2.so"} from java.library.path',
                       f'Loaded {self.natives / "libglass.so"} from java.library.path')
        self.assertEqual(2, len(check_native_loading(log, self.image, self.cache, True)))

    def test_resource_extraction_fails(self):
        for line in ('Loaded library /libglass.so from resource', 'Unpacked library /libprism_common.so from resource'):
            with self.subTest(line=line), self.assertRaisesRegex(ValueError, 'resources'):
                check_native_loading(self.log(line, f'Loaded {self.natives / "libglass.so"} from java.library.path'), self.image, self.cache, True)

    def test_created_cache_directory_fails_even_without_log(self):
        self.cache.mkdir()
        with self.assertRaisesRegex(ValueError, 'cache directory'):
            check_native_loading('', self.image, self.cache, False)

    def test_natives_outside_image_fail(self):
        outside = self.root / 'VibeMinerOther/libglass.so'
        with self.assertRaisesRegex(ValueError, 'outside the app image'):
            check_native_loading(self.log(f'Loaded {outside} from java.library.path'), self.image, self.cache, True)

    def test_ui_smoke_without_verbose_loads_fails(self):
        with self.assertRaisesRegex(ValueError, 'no JavaFX native'):
            check_native_loading(self.log(), self.image, self.cache, True)

    def test_ui_smoke_without_glass_fails(self):
        with self.assertRaisesRegex(ValueError, 'glass'):
            check_native_loading(self.log(f'Loaded {self.natives / "libprism_sw.so"} from java.library.path'), self.image, self.cache, True)

    def test_system_load_library_fallback_fails(self):
        log = self.log(f'Loaded {self.natives / "libglass.so"} from java.library.path', 'System.loadLibrary(prism_es2) succeeded')
        with self.assertRaisesRegex(ValueError, 'System.loadLibrary'):
            check_native_loading(log, self.image, self.cache, True)

    def test_backend_smoke_needs_only_absent_cache(self):
        self.assertEqual([], check_native_loading('', self.image, self.cache, False))


class LinuxPackageMetadataTest(unittest.TestCase):
    def test_control_template_pins_allowlisted_depends(self):
        with tempfile.TemporaryDirectory() as temp:
            resources = Path(temp) / 'resources'
            package_app.write_deb_control_template(resources)
            control = (resources / 'control').read_text(encoding='utf-8')
        depends = next(line for line in control.splitlines() if line.startswith('Depends: '))[len('Depends: '):]
        self.assertEqual(package_app.DEB_DEPENDS, package_app.parse_depends(depends))
        self.assertNotIn('PACKAGE_DEFAULT_DEPENDENCIES', control)
        self.assertNotIn('PACKAGE_CUSTOM_DEPENDENCIES', control)
        for placeholder in ('APPLICATION_PACKAGE', 'APPLICATION_VERSION_WITH_RELEASE', 'APPLICATION_ARCH', 'APPLICATION_INSTALLED_SIZE'):
            self.assertIn(placeholder, control)

    def test_allowlist_has_no_distribution_specific_names(self):
        for entry in package_app.DEB_DEPENDS:
            choices = entry.split(' | ')
            self.assertNotIn('libjpeg-turbo8', choices)
            if choices[0].endswith('t64'):
                self.assertEqual(choices[0][:-3], choices[-1], entry)

    def test_depends_parsing_normalizes_alternatives(self):
        self.assertEqual(('libc6', 'libasound2t64 | libasound2'), package_app.parse_depends('libc6,  libasound2t64|libasound2 '))


class JvmOptionsTest(unittest.TestCase):
    def test_packaged_app_uses_the_shared_low_pause_options(self):
        options = package_app.jvm_options()
        self.assertIn('-XX:+UseZGC', options)
        self.assertTrue(all(option.startswith('-') for option in options))

    def test_comments_and_blank_lines_are_ignored(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'options.txt'
            path.write_text('# note\n\n  -Xa  \n#-Xb\n-Xc # trailing\n-Dx=a#b\n', encoding='utf-8')
            self.assertEqual(['-Xa', '-Xc', '-Dx=a#b'], package_app.jvm_options(path))

    def test_smoke_report_must_show_zgc(self):
        package_app.require_collectors({'collectors': 'ZGC Minor Cycles,ZGC Major Cycles,ZGC Minor Pauses'})
        with self.assertRaisesRegex(ValueError, 'not running ZGC'):
            package_app.require_collectors({'collectors': 'G1 Young Generation,G1 Old Generation'})
        with self.assertRaisesRegex(ValueError, 'not running ZGC'):
            package_app.require_collectors({})

    def test_a_byte_order_mark_is_ignored(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'options.txt'
            path.write_bytes('-Xa\n'.encode('utf-8-sig'))
            self.assertEqual(['-Xa'], package_app.jvm_options(path))


class ImageCheckTest(unittest.TestCase):
    """--image-only and full packaging share check_image, so both verify macOS signatures."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.image = Path(self.temp.name) / 'VibeMiner.app'
        self.natives = self.image / 'Contents/app/native'
        (self.natives / 'javafx').mkdir(parents=True)
        (self.natives / 'javafx/libglass.dylib').write_bytes(b'native')
        (self.natives / 'libsqlite3.dylib').write_bytes(b'native')
        self.report = {'version': 'v0.1.0-alpha.1', 'collectors': 'ZGC Minor Cycles,ZGC Major Cycles'}
        self.commands = []
        self.failure = None
        original = package_app.run
        self.addCleanup(setattr, package_app, 'run', original)
        package_app.run = self.run_command

    def tearDown(self):
        self.temp.cleanup()

    def run_command(self, args, **options):
        self.commands.append(args)
        if self.failure:
            raise self.failure

    def check(self, system='macos', report=None, tag='v0.1.0-alpha.1'):
        package_app.check_image(self.image, system, report or self.report, tag)

    def test_macos_image_verifies_the_seal_and_every_native(self):
        self.check()
        self.assertEqual(['codesign', '--verify', '--deep', '--strict', '--verbose=2', self.image], self.commands[0])
        self.assertEqual([['codesign', '--verify', '--strict', self.natives / 'javafx/libglass.dylib'],
                          ['codesign', '--verify', '--strict', self.natives / 'libsqlite3.dylib']], self.commands[1:])

    def test_broken_signature_fails_the_check(self):
        self.failure = package_app.subprocess.CalledProcessError(1, ['codesign'])
        with self.assertRaises(package_app.subprocess.CalledProcessError):
            self.check()
        self.assertEqual(1, len(self.commands))

    def test_macos_image_without_bundled_glass_fails(self):
        (self.natives / 'javafx/libglass.dylib').unlink()
        with self.assertRaisesRegex(ValueError, 'glass'):
            self.check()

    def test_other_systems_run_no_codesign(self):
        for system in ('linux', 'windows'):
            with self.subTest(system=system):
                self.check(system)
                self.assertEqual([], self.commands)

    def test_version_and_collector_checks_still_apply(self):
        with self.assertRaisesRegex(ValueError, 'differs from requested tag'):
            self.check('linux', tag='v0.1.0-alpha.2')
        with self.assertRaisesRegex(ValueError, 'not running ZGC'):
            self.check('linux', report={'version': 'v0.1.0-alpha.1'})
        self.assertEqual([], self.commands)


if __name__ == '__main__':
    unittest.main()
