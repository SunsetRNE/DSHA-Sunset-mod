#!/usr/bin/env python3
"""Rust host-JVM 混合源码发现/锁校验负控；不启动 JVM 或 native。"""
import importlib.util
from pathlib import Path
import json
import hashlib
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

    def compiler_fixture(self, root):
        (root / "gradle").mkdir()
        (root / "settings.gradle").write_text("gradle.ext.kotlinVersion = '2.2.10'\n")
        directory = root / "jars"
        directory.mkdir()
        names = [name + "-2.2.10.jar" for name in ["kotlin-compiler-embeddable", "kotlin-stdlib",
            "kotlin-script-runtime", "kotlin-daemon-embeddable"]]
        names += ["kotlin-reflect-1.6.10.jar", "kotlinx-coroutines-core-jvm-1.8.0.jar", "annotations-13.0.jar"]
        artifacts = []
        for name in names:
            data = name.encode()
            (directory / name).write_bytes(data)
            artifacts.append('<artifact name="' + name + '"><sha256 value="' +
                             hashlib.sha256(data).hexdigest() + '"/></artifact>')
        (root / "gradle/verification-metadata.xml").write_text(
            '<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">'
            '<components><component>' + ''.join(artifacts) + '</component></components></verification-metadata>')
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

    def test_java_only_compile_remains_javac(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            commands = []
            with patch.object(MODULE, "run", side_effect=lambda command, **kw: commands.append(command) or ""):
                cp, evidence = MODULE.compile_sources(Path("/jdk"), [root / "Bridge.java"],
                    root / "classes", root, {}, None)
            self.assertEqual(1, len(commands))
            self.assertEqual("/jdk/bin/javac", commands[0][0])
            self.assertEqual([".java"], evidence["languages"])
            self.assertIsNone(evidence["kotlin"])


if __name__ == "__main__":
    unittest.main()
