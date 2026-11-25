#!/usr/bin/env python3
"""Rust host-JVM 混合源码发现/锁校验负控；不启动 JVM 或 native。"""
import importlib.util
from pathlib import Path
import json
import hashlib
import io
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("rust_host_probe", ROOT / "tools/rust/host-jvm-differential.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class HostJvmSourceTest(unittest.TestCase):
    def fixture(self, root):
        (root / "ci").mkdir()
        spec = json.loads((ROOT / "ci/source-layout.json").read_text())
        (root / "ci/source-layout.json").write_text(json.dumps(spec))
        for name in MODULE.BRIDGE_SOURCES:
            path = root / "app/src/main/java/com/deepseekharness/app" / (name + ".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("// source fixture\n")

    def test_java_and_kotlin_resolve_to_same_logical_class(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            before = MODULE.bridge_sources(root)
            original = before[1]
            candidate = original.with_suffix(".kt")
            original.rename(candidate)
            after = MODULE.bridge_sources(root)
            self.assertEqual(len(before), len(after))
            self.assertEqual(candidate, after[1])
            self.assertEqual("RustNative.java", after[0].name)

    def test_java_kotlin_duplicate_is_not_silently_selected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            source = MODULE.bridge_sources(root)[1]
            source.with_suffix(".kt").write_text("// duplicate\n")
            with self.assertRaisesRegex(MODULE.ProbeError, "RUST_BRIDGE_SOURCE_LAYOUT"):
                MODULE.bridge_sources(root)

    def test_missing_required_bridge_is_not_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            source = MODULE.bridge_sources(root)[0]
            source.rename(source.with_suffix(".missing"))
            with self.assertRaisesRegex(MODULE.ProbeError, "RUST_BRIDGE_SOURCE_LAYOUT"):
                MODULE.bridge_sources(root)

    def test_mixed_sources_require_explicit_compiler_without_download(self):
        with self.assertRaisesRegex(MODULE.ProbeError, "KOTLIN_COMPILER_REQUIRED"):
            MODULE.kotlin_libraries(None)

    def compiler_fixture(self, root, populate=True):
        (root / "gradle").mkdir()
        (root / "settings.gradle").write_text("gradle.ext.kotlinVersion = '2.2.10'\n")
        directory = root / "jars"
        if populate:
            directory.mkdir()
        components = []
        for group, name, version in MODULE.KOTLIN_PREPARER.COORDINATES:
            version = version or "2.2.10"
            filename = name + "-" + version + ".jar"
            data = filename.encode()
            if populate:
                (directory / filename).write_bytes(data)
            components.append('<component group="' + group + '" name="' + name +
                '" version="' + version + '"><artifact name="' + filename +
                '"><sha256 value="' + hashlib.sha256(data).hexdigest() + '"/></artifact></component>')
        (root / "gradle/verification-metadata.xml").write_text(
            '<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">'
            '<components>' + ''.join(components) + '</components></verification-metadata>')
        return directory

    def test_namespaced_verification_metadata_pins_each_compiler_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jars = self.compiler_fixture(root)
            self.assertEqual(7, len(MODULE.kotlin_libraries(jars, root)))

    def test_compiler_tamper_version_and_duplicate_are_rejected(self):
        for failure in ("tamper", "version", "duplicate"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                jars = self.compiler_fixture(root)
                target = jars / "kotlin-compiler-embeddable-2.2.10.jar"
                if failure == "tamper":
                    target.write_bytes(b"wrong")
                elif failure == "version":
                    target.rename(jars / "kotlin-compiler-embeddable-2.2.11.jar")
                else:
                    (jars / "kotlin-compiler-embeddable-2.2.11.jar").write_bytes(b"extra")
                with self.assertRaises(MODULE.ProbeError):
                    MODULE.kotlin_libraries(jars, root)

    def response(self, data, url):
        value = io.BytesIO(data)
        value.geturl = lambda: url
        return value

    def test_prepare_downloads_exact_seven_then_check_is_read_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jars = self.compiler_fixture(root, populate=False)
            requests = []
            def open_fixture(request, timeout):
                self.assertTrue(request.full_url.startswith(MODULE.KOTLIN_PREPARER.MAVEN))
                requests.append(request.full_url)
                return self.response(request.full_url.rsplit('/', 1)[-1].encode(), request.full_url)
            result = MODULE.KOTLIN_PREPARER.prepare(jars, root=root, opener=open_fixture)
            self.assertEqual(7, len(requests))
            self.assertEqual(7, len(result['files']))
            before = {p.name: p.stat().st_mtime_ns for p in jars.iterdir()}
            with patch.object(MODULE.KOTLIN_PREPARER.urllib.request, 'build_opener',
                              side_effect=AssertionError('check must not open network')):
                checked = MODULE.KOTLIN_PREPARER.prepare(jars, check=True, root=root)
            self.assertTrue(checked['checkOnly'])
            self.assertEqual(before, {p.name: p.stat().st_mtime_ns for p in jars.iterdir()})

    def test_check_missing_does_not_create_directory_or_download(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jars = self.compiler_fixture(root, populate=False)
            with self.assertRaises(MODULE.KOTLIN_PREPARER.PrepareError):
                MODULE.KOTLIN_PREPARER.prepare(jars, check=True, root=root)
            self.assertFalse(jars.exists())

    def test_existing_bad_jar_is_preserved_before_any_network(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jars = self.compiler_fixture(root)
            broken = jars / 'kotlin-stdlib-2.2.10.jar'
            broken.write_bytes(b'keep this evidence')
            with self.assertRaisesRegex(MODULE.KOTLIN_PREPARER.PrepareError, 'EXISTING_DIGEST'):
                MODULE.KOTLIN_PREPARER.prepare(jars, root=root,
                    opener=lambda *a, **kw: self.fail('no download before existing validation'))
            self.assertEqual(b'keep this evidence', broken.read_bytes())

    def test_download_tamper_or_foreign_redirect_never_publishes(self):
        for failure in ('digest', 'redirect'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                jars = self.compiler_fixture(root, populate=False)
                def bad_response(request, timeout):
                    data = b'wrong' if failure == 'digest' else request.full_url.rsplit('/', 1)[-1].encode()
                    url = request.full_url if failure == 'digest' else 'https://example.com/untrusted.jar'
                    return self.response(data, url)
                with self.assertRaises(MODULE.KOTLIN_PREPARER.PrepareError):
                    MODULE.KOTLIN_PREPARER.prepare(jars, root=root, opener=bad_response)
                self.assertEqual([], list(jars.iterdir()))

    def test_only_official_https_and_pinned_coordinates_are_allowed(self):
        for url in ('http://repo.maven.apache.org/maven2/x.jar', 'https://example.com/x.jar',
                    'https://user@repo.maven.apache.org/maven2/x.jar',
                    'https://repo.maven.apache.org/maven2/x.jar?token=x'):
            with self.subTest(url=url), self.assertRaises(MODULE.KOTLIN_PREPARER.PrepareError):
                MODULE.KOTLIN_PREPARER.allowed_url(url)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.compiler_fixture(root)
            lock = root / 'gradle/verification-metadata.xml'
            lock.write_text(lock.read_text().replace('group="org.jetbrains.kotlin"', 'group="untrusted"', 1))
            with self.assertRaisesRegex(MODULE.KOTLIN_PREPARER.PrepareError, 'COORDINATE_PIN'):
                MODULE.KOTLIN_PREPARER.locked_artifacts(root)

    def test_symlink_jar_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jars = self.compiler_fixture(root)
            target = jars / 'kotlin-stdlib-2.2.10.jar'
            saved = root / 'original.jar'
            target.rename(saved)
            try:
                target.symlink_to(saved)
            except OSError as error:
                if getattr(error, "winerror", None) == 1314:
                    self.skipTest("Windows fixture cannot create a symbolic link")
                raise
            with self.assertRaisesRegex(MODULE.KOTLIN_PREPARER.PrepareError, 'LINK'):
                MODULE.KOTLIN_PREPARER.prepare(jars, check=True, root=root)

    def test_unsupported_kgp_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.compiler_fixture(root)
            (root / 'settings.gradle').write_text("gradle.ext.kotlinVersion = '2.2.11'\n")
            with self.assertRaisesRegex(MODULE.KOTLIN_PREPARER.PrepareError, 'UNSUPPORTED_KGP'):
                MODULE.KOTLIN_PREPARER.locked_artifacts(root)

    def test_ci_prepares_and_checks_same_directory_before_probe(self):
        source = (ROOT / '.github/workflows/_node-rust.yml').read_text(encoding="utf-8")
        prepare = 'python3 tools/rust/prepare-kotlin-host.py --dir out/kotlin-host'
        check = prepare + ' --check'
        probe = 'python3 tools/rust/host-jvm-differential.py'
        self.assertLess(source.index(prepare), source.index(check))
        self.assertLess(source.index(check), source.index(probe))
        self.assertIn('--kotlin-libs out/kotlin-host', source)
        self.assertIn('python3 tools/test-rust-host-jvm-sources.py', source)

    def test_java_only_compile_remains_javac(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            commands = []
            with patch.object(MODULE, "run", side_effect=lambda command, **kw: commands.append(command) or ""):
                cp, evidence = MODULE.compile_sources(Path("/jdk"), [root / "Bridge.java"],
                    root / "classes", root, {}, None)
            self.assertEqual(1, len(commands))
            self.assertEqual(Path("/jdk") / "bin/javac", Path(commands[0][0]))
            self.assertEqual([".java"], evidence["languages"])
            self.assertIsNone(evidence["kotlin"])


if __name__ == "__main__":
    unittest.main()
