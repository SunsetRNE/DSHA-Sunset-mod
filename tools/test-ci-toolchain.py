#!/usr/bin/env python3
"""CI 工具链读取器跟随真实 applied convention，而不恢复重复 app 配置。"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ci_toolchain', ROOT / 'tools/ci-toolchain.py')
toolchain = importlib.util.module_from_spec(spec)
spec.loader.exec_module(toolchain)


class CiToolchainTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='a8-ci-toolchain-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ('ci/toolchain.lock.json', 'app/build.gradle', 'build.gradle',
                     'gradle/wrapper/gradle-wrapper.properties', 'build-logic/android.gradle'):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes((ROOT / name).read_bytes())

    def mutate(self, name, before, after):
        path = self.root / name
        text = path.read_text(encoding='utf-8')
        self.assertIn(before, text)
        path.write_text(text.replace(before, after, 1), encoding='utf-8')

    def test_current_shared_configuration_keeps_exact_ci_versions(self):
        expected = json.loads((self.root / 'ci/toolchain.lock.json').read_text())
        self.assertEqual(expected, toolchain.read(self.root))
        self.assertEqual('17', expected['java'])

    def test_shared_source_version_drift_is_rejected(self):
        self.mutate('build-logic/android.gradle', 'sourceCompatibility JavaVersion.VERSION_17',
                    'sourceCompatibility JavaVersion.VERSION_11')
        with self.assertRaisesRegex(ValueError, 'CI_TOOLCHAIN_JAVA_TARGET'):
            toolchain.read(self.root)

    def test_shared_target_version_drift_is_rejected(self):
        self.mutate('build-logic/android.gradle', 'targetCompatibility JavaVersion.VERSION_17',
                    'targetCompatibility JavaVersion.VERSION_11')
        with self.assertRaisesRegex(ValueError, 'CI_TOOLCHAIN_JAVA_TARGET'):
            toolchain.read(self.root)

    def test_removing_actual_app_convention_wiring_is_rejected(self):
        self.mutate('app/build.gradle', "apply from: rootProject.file('build-logic/android.gradle')", '')
        with self.assertRaisesRegex(ValueError, 'CI_TOOLCHAIN_SOURCE: android convention'):
            toolchain.read(self.root)

    def test_duplicate_app_java_owner_is_rejected(self):
        path = self.root / 'app/build.gradle'
        path.write_text(path.read_text() + '\nsourceCompatibility JavaVersion.VERSION_17\n')
        with self.assertRaisesRegex(ValueError, 'CI_TOOLCHAIN_DUPLICATE_JAVA_OWNER'):
            toolchain.read(self.root)

    def test_real_setup_cli_produces_field_and_github_outputs(self):
        command = [sys.executable, str(ROOT / 'tools/ci-toolchain.py')]
        result = subprocess.run(command + ['--field', 'java'], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('17', result.stdout.strip())
        output = self.root / 'github-output.txt'
        result = subprocess.run(command + ['--github-output'], capture_output=True, text=True,
                                env=dict(os.environ, GITHUB_OUTPUT=str(output)))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('java=17\n', output.read_text())
        self.assertEqual(len(toolchain.FIELDS), len(output.read_text().splitlines()))


if __name__ == '__main__':
    unittest.main(verbosity=2)
