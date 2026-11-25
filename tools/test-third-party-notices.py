"""Exercise the actual archive/declaration gate with isolated npm and JNI fixtures."""
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile
from proroot_distribution import LICENSE_NAME, prepare_inputs, vendor_pins, verify_apk

spec = importlib.util.spec_from_file_location('third_party_notices',
                                             Path(__file__).with_name('check-third-party-notices.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ThirdPartyNoticesTest(unittest.TestCase):
    def test_vendor_inputs_restore_offline_and_retry_without_the_apk(self):
        originals = gate.ROOT / 'app/src/main/jniLibs/arm64-v8a'
        notice = gate.ROOT / 'app/src/main/assets/licenses' / LICENSE_NAME
        target = self.root / 'checkout'
        with zipfile.ZipFile(self.archive, 'w') as apk:
            for name in vendor_pins():
                apk.write(originals/name, 'lib/arm64-v8a/' + name)
            apk.write(notice, 'assets/licenses/' + LICENSE_NAME)
        self.assertEqual(5, prepare_inputs(target, apk_path=self.archive))
        self.assertEqual(0, prepare_inputs(target))
        (target/'libproroot.so').write_bytes(b'partial')
        with self.assertRaisesRegex(ValueError, 'Missing or changed'):
            prepare_inputs(target)
        self.assertEqual(1, prepare_inputs(target, apk_path=self.archive))
        self.assertEqual((originals/'libproroot.so').read_bytes(), (target/'libproroot.so').read_bytes())

    def test_invalid_vendor_source_keeps_existing_inputs(self):
        originals = gate.ROOT / 'app/src/main/jniLibs/arm64-v8a'
        notice = gate.ROOT / 'app/src/main/assets/licenses' / LICENSE_NAME
        target = self.root / 'checkout'
        target.mkdir()
        existing = target/'libproroot.so'
        existing.write_bytes(b'existing')
        with zipfile.ZipFile(self.archive, 'w') as apk:
            for name in vendor_pins():
                apk.write(originals/name, 'lib/arm64-v8a/' + name)
            apk.writestr('assets/licenses/' + LICENSE_NAME, notice.read_bytes()[:-1])
        with self.assertRaisesRegex(ValueError, 'APK_PROROOT_BYTES'):
            prepare_inputs(target, apk_path=self.archive)
        self.assertEqual([existing], list(target.iterdir()))
        self.assertEqual(b'existing', existing.read_bytes())

    def test_proroot_final_apk_requires_exact_five_originals_and_full_notice(self):
        files = {'lib/arm64-v8a/' + path.name: path.read_bytes()
                 for path in (gate.ROOT / 'app/src/main/jniLibs/arm64-v8a').glob('libproroot*.so')}
        license_member = 'assets/licenses/' + LICENSE_NAME
        files[license_member] = (gate.ROOT / 'app/src/main/assets/licenses' / LICENSE_NAME).read_bytes()

        def verify(entries):
            data = io.BytesIO()
            with zipfile.ZipFile(data, 'w') as archive:
                for name, contents in entries.items():
                    archive.writestr(name, contents)
            with zipfile.ZipFile(data) as archive:
                return verify_apk(archive)

        self.assertEqual(6, len(verify(files)))
        for name in files:
            with self.subTest(name=name):
                with self.assertRaisesRegex(ValueError, 'APK_PROROOT_BYTES'):
                    verify({**files, name: files[name][:-1]})
                with self.assertRaisesRegex(ValueError, 'APK_PROROOT_MEMBERS'):
                    verify({key: content for key, content in files.items() if key != name})
        with self.assertRaisesRegex(ValueError, 'APK_PROROOT_MEMBERS'):
            verify({**files, 'lib/x86/libproroot.so': b'extra member'})

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.archive = self.root / 'runtime.bin'
        self.lock = {'packages': {'node_modules/@example/native': {
            'version': '1.2.3', 'license': 'MIT AND MPL-2.0'}}}

    def archive_package(self, license_name='MIT AND MPL-2.0', version='1.2.3'):
        content = json.dumps({'name': '@example/native', 'version': version,
                              'license': license_name}).encode()
        with tarfile.open(self.archive, 'w:gz') as output:
            member = tarfile.TarInfo('usr/node_modules/@example/native/package.json')
            member.size = len(content)
            output.addfile(member, io.BytesIO(content))

    def test_actual_restricted_identity_requires_notice_and_exact_declaration(self):
        self.archive_package()
        result = gate.inventory(self.archive, self.lock, '`node_modules/@example/native`')
        self.assertEqual('MIT AND MPL-2.0', result[0]['declaredLicense'])
        with self.assertRaisesRegex(ValueError, 'RESTRICTED_NOTICE'):
            gate.inventory(self.archive, self.lock, 'wrapper alone is MIT')
        for license_name, version in [('MIT', '1.2.3'), ('MIT AND MPL-2.0', '2.0.0')]:
            self.archive_package(license_name, version)
            with self.subTest(license_name=license_name), self.assertRaisesRegex(ValueError, 'PACKAGE_DECLARATION'):
                gate.inventory(self.archive, self.lock, '`node_modules/@example/native`')

    def test_new_native_file_requires_an_explicit_reviewed_notice(self):
        native = self.root / 'app/src/main/jniLibs/arm64-v8a'
        native.mkdir(parents=True)
        (native / 'libdsha-session.so').write_bytes(b'fixture')
        self.assertEqual(1, len(gate.verify_native_members(self.root)))
        (native / 'unknown.so').write_bytes(b'new')
        with self.assertRaisesRegex(ValueError, 'NEW_NATIVE'):
            gate.verify_native_members(self.root)

    def test_gradle_coordinates_scan_build_logic_runtime_dependencies(self):
        (self.root / 'app').mkdir()
        (self.root / 'app/build.gradle').write_text(
            "dependencies {\n    implementation 'a:b:1'\n}\n", encoding='utf-8')
        logic = self.root / 'build-logic'
        logic.mkdir()
        (logic / 'android.gradle').write_text(
            "dependencies {\n    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.0.4'\n"
            "    testImplementation 'org.robolectric:robolectric:4.15.1'\n}\n", encoding='utf-8')
        self.assertEqual(
            ['a:b:1', 'com.android.tools:desugar_jdk_libs:2.0.4'],
            gate.generator.gradle_coordinates(self.root))

    def test_rust_vendor_license_bytes_are_bound_to_the_vendor_tree(self):
        gate.generator.verify_rust_licenses()
        lock_dir = self.root / 'ci'
        lock_dir.mkdir()
        (lock_dir / 'rust-toolchain.lock.json').write_text(json.dumps({
            'dependencies': [{'name': 'demo', 'version': '1.0.0', 'licenseFiles': ['LICENSE']}],
            'vendor': {'bytes': 0, 'files': 0}}), encoding='utf-8')
        vendor = self.root / 'rust/vendor/demo-1.0.0'
        vendor.mkdir(parents=True)
        (vendor / 'LICENSE').write_bytes(b'fixture license')
        target = self.root / 'app/src/main/assets/licenses/rust/demo-1.0.0/LICENSE'
        target.parent.mkdir(parents=True)
        target.write_bytes(b'fixture license')
        gate.generator.verify_rust_licenses(self.root)
        target.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'RUST_LICENSE_BYTES'):
            gate.generator.verify_rust_licenses(self.root)

    def test_source_license_bytes_and_required_missing_file_are_rejected(self):
        gate.verify_licenses()
        licenses = self.root / 'app/src/main/assets/licenses'
        licenses.mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, 'LICENSE_MISSING'):
            gate.verify_licenses(self.root)


if __name__ == '__main__':
    unittest.main()
