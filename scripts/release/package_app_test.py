from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
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
        recommends = next(line for line in control.splitlines() if line.startswith('Recommends: '))[len('Recommends: '):]
        self.assertEqual(package_app.DEB_RECOMMENDS, package_app.parse_depends(recommends))
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


def elf(needed, soname=None, wide=True, order='<', base=0x400000):
    """Minimal dynamic ELF: one PT_LOAD mapping the file at base, one PT_DYNAMIC."""
    strings, offsets = b'\0', {}
    for name in [soname, *needed]:
        if name and name not in offsets:
            offsets[name] = len(strings)
            strings += name.encode() + b'\0'
    header_size, phentsize = (64, 56) if wide else (52, 32)
    strtab = header_size + 2 * phentsize
    dynamic = strtab + len(strings) + (-(strtab + len(strings)) % 8)
    entries = [(1, offsets[name]) for name in needed] + ([(14, offsets[soname])] if soname else []) + [(5, base + strtab), (0, 0)]
    dyn = order + ('qQ' if wide else 'iI')
    body = b''.join(struct.pack(dyn, tag, value) for tag, value in entries)
    size = dynamic + len(body)
    ident = b'\x7fELF' + bytes([2 if wide else 1, 1 if order == '<' else 2, 1]) + bytes(9)
    if wide:
        head = ident + struct.pack(order + 'HHIQQQIHHHHHH', 3, 62, 1, 0, header_size, 0, 0, header_size, phentsize, 2, 0, 0, 0)
        phdrs = struct.pack(order + 'IIQQQQQQ', 1, 5, 0, base, base, size, size, 0x1000)
        phdrs += struct.pack(order + 'IIQQQQQQ', 2, 6, dynamic, base + dynamic, base + dynamic, len(body), len(body), 8)
    else:
        head = ident + struct.pack(order + 'HHIIIIIHHHHHH', 3, 40, 1, 0, header_size, 0, 0, header_size, phentsize, 2, 0, 0, 0)
        phdrs = struct.pack(order + 'IIIIIIII', 1, 0, base, base, size, size, 5, 0x1000)
        phdrs += struct.pack(order + 'IIIIIIII', 2, dynamic, base + dynamic, base + dynamic, len(body), len(body), 6, 4)
    data = head + phdrs + strings
    return data + bytes(dynamic - len(data)) + body


class ElfDynamicTest(unittest.TestCase):
    def test_reads_soname_and_needed_in_every_elf_class_and_byte_order(self):
        for wide in (True, False):
            for order in ('<', '>'):
                with self.subTest(wide=wide, order=order):
                    data = elf(['libharfbuzz.so.0', 'libc.so.6'], 'libglass.so', wide, order)
                    self.assertEqual(('libglass.so', ['libharfbuzz.so.0', 'libc.so.6']), package_app.elf_dynamic(data))

    def test_executable_without_soname(self):
        self.assertEqual((None, ['libjli.so']), package_app.elf_dynamic(elf(['libjli.so'])))

    @unittest.skipUnless(sys.platform.startswith('linux') and shutil.which('readelf'), 'needs Linux readelf')
    def test_matches_readelf_on_the_running_interpreter(self):
        binary = Path(sys.executable).resolve()
        listing = subprocess.check_output(['readelf', '-d', str(binary)], text=True)
        expected = re.findall(r'\(NEEDED\).*\[(.+)\]', listing)
        self.assertTrue(expected)
        self.assertEqual(expected, package_app.elf_dynamic(binary.read_bytes())[1])

    def test_non_elf_is_ignored(self):
        self.assertIsNone(package_app.elf_dynamic(b'PK\x03\x04' + bytes(60)))


class DebSonameMappingTest(unittest.TestCase):
    def test_bundled_and_mapped_libraries_resolve_to_declared_packages(self):
        elves = [('lib/libfontmanager.so', elf(['libfreetype.so', 'libjava.so', 'libc.so.6'], 'libfontmanager.so')),
                 ('lib/libfreetype.so', elf(['libc.so.6'], 'libfreetype.so')),
                 ('lib/libjava.so', elf(['libc.so.6'])),
                 ('lib/app/native/javafx/libglass.so', elf(['libharfbuzz.so.0', 'libX11.so.6'], 'libglass.so'))]
        self.assertEqual({'libc6', 'libharfbuzz0b', 'libx11-6'}, package_app.required_deb_packages(elves))

    def test_unmapped_soname_fails_naming_library_and_file(self):
        elves = [('lib/app/native/javafx/libglass.so', elf(['libwayland-client.so.0', 'libc.so.6'], 'libglass.so'))]
        with self.assertRaisesRegex(ValueError, r'libwayland-client\.so\.0 \(needed by lib/app/native/javafx/libglass\.so\)'):
            package_app.required_deb_packages(elves)

    def test_every_mapped_package_is_declared(self):
        self.assertLessEqual(set(package_app.DEB_SONAME_PACKAGES.values()), set(package_app.DEB_DEPENDS))
        self.assertIn('libharfbuzz0b', package_app.DEB_DEPENDS)
        self.assertEqual(('libvulkan1',), package_app.DEB_RECOMMENDS)

    def test_image_scan_reads_files_and_jar_entries(self):
        with tempfile.TemporaryDirectory() as temp:
            image = Path(temp) / 'VibeMiner'
            (image / 'lib/app').mkdir(parents=True)
            (image / 'lib/libjava.so').write_bytes(elf(['libc.so.6']))
            (image / 'lib/modules').write_bytes(b'not an elf')
            with zipfile.ZipFile(image / 'lib/app/lwjgl-bgfx-natives-linux.jar', 'w') as jar:
                jar.writestr('linux/x64/org/lwjgl/bgfx/libbgfx.so', elf(['libstdc++.so.6', 'libgcc_s.so.1']))
                jar.writestr('org/lwjgl/bgfx/BGFX.class', b'\xca\xfe\xba\xbe')
            locations = [location for location, _ in package_app.image_elves(image)]
            self.assertEqual(['lib/app/lwjgl-bgfx-natives-linux.jar!/linux/x64/org/lwjgl/bgfx/libbgfx.so', 'lib/libjava.so'], locations)
            self.assertEqual({'libc6', 'libstdc++6', 'libgcc-s1'}, package_app.required_deb_packages(package_app.image_elves(image)))


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


if __name__ == '__main__':
    unittest.main()
