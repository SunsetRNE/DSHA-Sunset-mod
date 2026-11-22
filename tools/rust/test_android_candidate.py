#!/usr/bin/env python3
"""Rust Android 候选的主机静态回归；不构建 APK，不执行 Android/JNI/Gradle。"""
from __future__ import annotations

import ast
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/_node-test-packages.yml'
JAVA_PATH = 'app/src/androidTest/java/com/deepseekharness/app/util/RustNativeInstrumentation.java'
RUNNER = 'com.deepseekharness.app.util.RustNativeInstrumentation'


def steps(source):
    matches = list(re.finditer(r'^      - (?:name: (.+)|uses: (.+))$', source, re.M))
    return {match.group(1) or match.group(2): source[match.start():
            matches[index + 1].start() if index + 1 < len(matches) else len(source)]
            for index, match in enumerate(matches)}


def script(block):
    body = block.split('        run: |\n', 1)[1]
    return '\n'.join(line[10:] if line.startswith('          ') else line
                     for line in body.splitlines()) + '\n'


def python_blocks(shell):
    return re.findall(r"<<'PYCODE'\n(.*?)^PYCODE$", shell, re.M | re.S)


class CandidateWorkflow(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = WORKFLOW.read_text(encoding='utf-8')
        cls.blocks = steps(cls.source)

    def test_mode_defaults_and_real_shell_validation(self):
        self.assertRegex(self.source, r"(?s)      rust_native_mode:\n.*?        default: 'off'")
        self.assertRegex(self.source, r"(?s)      rust_artifact:\n.*?        default: ''")
        self.assertIn('RUST_NATIVE_MODE: ${{ inputs.rust_native_mode }}', self.source)
        validate = script(self.blocks['Validate requested variant'])
        for mode, artifact, accepted in [('off', '', True), ('off', 'other-run', False),
                                         ('prebuilt', 'same-run', True), ('prebuilt', '', False),
                                         ('auto', '', False)]:
            for variant in ('debug', 'dev'):
                with self.subTest(mode=mode, artifact=artifact, variant=variant):
                    env = dict(os.environ, VARIANT=variant, RELEASE_IDENTITY='mod',
                               RUST_NATIVE_MODE=mode, RUST_ARTIFACT=artifact,
                               UNSIGNED_ONLY='true', UNSIGNED_ARTIFACT_PREFIX='')
                    result = subprocess.run(['bash', '-c', validate], env=env, capture_output=True, text=True)
                    self.assertEqual(result.returncode == 0, accepted, result.stdout + result.stderr)
        env = dict(os.environ, VARIANT='debug', RELEASE_IDENTITY='mod', RUST_NATIVE_MODE='off',
                   RUST_ARTIFACT='', UNSIGNED_ONLY='true', UNSIGNED_ARTIFACT_PREFIX='handoff')
        self.assertNotEqual(subprocess.run(['bash', '-c', validate], env=env,
                                          capture_output=True).returncode, 0)

    def test_real_gradle_prebuilt_check_is_scoped_to_unsigned_debug_and_same_run_input(self):
        """只审查真实 Gradle 步骤的接线；本测试不调用 Gradle 或 Android。"""
        exercise = self.blocks['Exercise real Gradle Rust prebuilt consumption']
        condition = ("inputs.unsigned_only && inputs.unsigned_artifact_prefix == '' && "
                     "inputs.rust_native_mode == 'prebuilt' && matrix.variant == 'debug'")
        self.assertIn('if: ${{ ' + condition + ' }}', exercise)
        shell = script(exercise)
        self.assertIn('python3 -B tools/rust/check_gradle_prebuilt.py --gradle ./gradlew', shell)
        self.assertIn('--artifact-dir out/rust-native', shell)
        self.assertIn('--output out/rust-gradle-integration/report.json', shell)
        self.assertNotIn('--synthetic-fixture', shell)
        self.assertLess(self.source.index('name: Download same-run Rust native artifact'),
                        self.source.index('name: Exercise real Gradle Rust prebuilt consumption'))
        self.assertLess(self.source.index('name: Exercise real Gradle Rust prebuilt consumption'),
                        self.source.index('name: Build unsigned ${{ matrix.variant }} package'))
        upload = self.blocks['Upload Rust Gradle integration evidence']
        self.assertIn('if: ${{ always() && ' + condition + ' }}', upload)
        self.assertIn('out/rust-gradle-integration/report.json', upload)
        self.assertIn('out/rust-gradle-integration/**/*.log', upload)
        self.assertIn('if-no-files-found: error', upload)
        runner = (ROOT / 'tools/rust/check_gradle_prebuilt.py').read_text(encoding='utf-8')
        self.assertIn('androidLoadTested=False, apkAssembled=False', runner)
        candidate = self.blocks['Prepare Rust debug instrumentation candidate (not executed)']
        self.assertIn("'execution': 'NOT_RUN'", candidate)
        self.assertIn("'androidNativeLoadVerified': False", candidate)

    def test_prebuilt_download_cannot_select_another_run(self):
        block = self.blocks['Download same-run Rust native artifact']
        self.assertIn("if: ${{ inputs.rust_native_mode == 'prebuilt' }}", block)
        self.assertIn('name: ${{ inputs.rust_artifact }}', block)
        self.assertIn('path: out/rust-native', block)
        self.assertNotRegex(block, r'(?m)^\s+(run-id|github-token|repository):')
        self.assertRegex(block, r'actions/download-artifact@[0-9a-f]{40}')

    def test_debug_only_explicit_runner_and_dev_no_instrumentation(self):
        build = script(self.blocks['Build unsigned ${{ matrix.variant }} package'])
        self.assertIn('if: ${{ inputs.unsigned_artifact_prefix == \'\' }}',
                      self.blocks['Build unsigned ${{ matrix.variant }} package'])
        self.assertIn('rust_args=("-Pdsha.rust.mode=$RUST_NATIVE_MODE")', build)
        self.assertIn('rust_args+=(-Pdsha.rust.artifactDir=out/rust-native)', build)
        debug, dev = build.split('\nelse\n', 1)
        self.assertRegex(debug, r'(?s)if \[ "\$VARIANT" = debug \]; then.*'
                               r'if \[ "\$RUST_NATIVE_MODE" = prebuilt \]; then\n'
                               r'    tasks\+=\(:app:assembleStandardDebugAndroidTest\)\n'
                               r'    runner_args\+=\(-Pdsha.testRunner=' + re.escape(RUNNER) + r'\)')
        self.assertEqual(build.count('-Pdsha.testRunner='), 1)
        self.assertNotIn('AndroidTest', dev)
        self.assertNotIn('testRunner', dev)
        self.assertIn('"${rust_args[@]}"', dev)
        prepare = self.blocks['Prepare Rust debug instrumentation candidate (not executed)']
        self.assertIn("inputs.rust_native_mode == 'prebuilt' && matrix.variant == 'debug'", prepare)
        self.assertIn('inputs.unsigned_artifact_prefix ==', prepare)
        self.assertIn("instrumentation_identity(manifest) != (runner, target)", prepare)
        self.assertIn("'dump', 'xmltree'", prepare)
        self.assertIn("'--file', 'AndroidManifest.xml'", prepare)
        self.assertIn("package_match.group(1) != target + '.test'", prepare)

    def test_gate_runs_at_all_three_apk_boundaries(self):
        calls = []
        lines = self.source.splitlines()
        for index, line in enumerate(lines):
            if 'python3 tools/check-rust-native-apk.py' not in line:
                continue
            parts = [line]
            while parts[-1].rstrip().endswith('\\'):
                index += 1
                parts.append(lines[index])
            calls.append('\n'.join(parts))
        self.assertEqual(len(calls), 3)
        for call in calls:
            self.assertIn('--mode "$RUST_NATIVE_MODE"', call)
            for argument in ('--apk', '--commit "$GITHUB_SHA"', '--run-id "$GITHUB_RUN_ID"',
                             '--run-attempt "$GITHUB_RUN_ATTEMPT"', '--repository "$GITHUB_REPOSITORY"',
                             '--output'):
                self.assertIn(argument, call)
        for report in ('unsigned', 'pre-sign', 'signed'):
            self.assertTrue(any('out/rust-native-apk-' + report + '.json' in call for call in calls))
        sign = script(self.blocks['Sign ${{ matrix.variant }} APK and verify identity'])
        self.assertLess(sign.index('out/rust-native-apk-pre-sign.json'), sign.index('"$apksigner" sign'))
        self.assertLess(sign.index('"$apksigner" sign'), sign.index('out/rust-native-apk-signed.json'))
        self.assertIn('--receipt out/rust-native/rust-receipt.json', sign)
        self.assertNotIn('--rust-optional', self.source)

    def test_signing_isolation_cache_and_three_modes_are_preserved(self):
        self.assertIn("gradle-cache: ${{ (inputs.unsigned_only && 'true') || 'false' }}", self.source)
        self.assertNotIn('secrets.DSHA_', self.source)
        self.assertIn('persist-credentials: false', self.source)
        self.assertIn('permissions:\n  contents: read', self.source)
        cleanup = self.blocks['Remove test private key']
        self.assertIn('always() && !inputs.unsigned_only', cleanup)
        self.assertIn('key.unlink(missing_ok=True)', cleanup)
        sign = script(self.blocks['Sign ${{ matrix.variant }} APK and verify identity'])
        self.assertEqual(sign.count('"$apksigner" sign --ks "$TEST_KEY" --ks-key-alias sunset-test'), 2)
        probe_sign = sign[sign.index('if [ "$RUST_NATIVE_MODE" = prebuilt ] && [ "$VARIANT" = debug ]; then'):]
        self.assertIn('"$apksigner" sign', probe_sign)
        self.assertIn("certificates != [os.environ['TARGET_CERT']]", probe_sign)
        self.assertIn('SIGNED_RUST_INSTRUMENTATION_MANIFEST', probe_sign)
        self.assertIn('RUST_INSTRUMENTATION_SIGNED_PAYLOAD_CHANGED', probe_sign)

    def test_evidence_is_candidate_only_and_has_all_gate_reports(self):
        receipt = self.blocks['Write commit-bound build receipt']
        self.assertIn("'androidNativeLoadVerified': False", receipt)
        self.assertIn("'rustInstrumentation':", receipt)
        self.assertIn("'rustApkGates':", receipt)
        upload = self.blocks['Upload test package evidence']
        for item in ('out/rust-native-apk-*.json', 'out/rust-native/rust-receipt.json',
                     'steps.sign.outputs.instrumentation_apk', 'out/rust-instrumentation-candidate.json',
                     'out/rust-instrumentation-run.txt', 'out/instrumentation-signature.txt'):
            self.assertIn(item, upload)
        candidate = self.blocks['Upload Rust debug instrumentation APK candidate (not executed)']
        self.assertIn("!inputs.unsigned_only && inputs.rust_native_mode == 'prebuilt' && matrix.variant == 'debug'", candidate)
        sign_python = python_blocks(script(self.blocks['Sign ${{ matrix.variant }} APK and verify identity']))[-1]
        self.assertIn("'execution': 'NOT_RUN'", sign_python)
        self.assertIn("'androidNativeLoadVerified': False", sign_python)
        self.assertIn("['adb', 'shell', 'am', 'instrument', '-w', '-r'", sign_python)
        self.assertNotIn('subprocess.', sign_python)
        self.assertNotRegex(self.source, r'(?m)^\s+(?:adb|am)\s+(?:install|instrument|shell)\b')

    def test_all_shell_and_embedded_python_parse(self):
        count = 0
        for name, block in self.blocks.items():
            if '        run: |\n' not in block:
                continue
            shell = script(block)
            with self.subTest(step=name):
                result = subprocess.run(['bash', '-n'], input=shell, text=True, capture_output=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                for source in python_blocks(shell):
                    ast.parse(source, filename=name)
                    count += 1
        self.assertGreaterEqual(count, 7)

    def manifest_gates(self):
        sources = [python_blocks(script(self.blocks['Prepare Rust debug instrumentation candidate (not executed)']))[0],
                   python_blocks(script(self.blocks['Sign ${{ matrix.variant }} APK and verify identity']))[-1]]
        gates = []
        for source, failure in zip(sources, ('RUST_INSTRUMENTATION_MANIFEST', 'SIGNED_RUST_INSTRUMENTATION_MANIFEST')):
            tree = ast.parse(source)
            function = next(node for node in tree.body if isinstance(node, ast.FunctionDef)
                            and node.name == 'instrumentation_identity')
            validation = next(node for node in tree.body if isinstance(node, ast.If)
                              and any(isinstance(item, ast.Constant) and item.value == failure
                                      for item in ast.walk(node)))
            gates.append((function, compile(ast.Module(body=[function, validation], type_ignores=[]),
                                           '<workflow-manifest-gate>', 'exec')))
        return gates

    @staticmethod
    def manifest_fixture(runner=RUNNER, target='io.github.sunsetrne.dsha.debug'):
        namespace = 'http://schemas.android.com/apk/res/android:'
        return ('N: android=http://schemas.android.com/apk/res/android (line=1)\n'
                '  E: manifest (line=2)\n'
                '    A: package="io.github.sunsetrne.dsha.debug.test" (Raw: "io.github.sunsetrne.dsha.debug.test")\n'
                '      E: uses-sdk (line=3)\n'
                '        A: ' + namespace + 'minSdkVersion(0x0101020c)=(type 0x10)0x1e\n'
                '      E: instrumentation (line=4)\n'
                '        A: ' + namespace + 'name(0x01010003)="' + runner + '" (Raw: "' + runner + '")\n'
                '        A: ' + namespace + 'targetPackage(0x01010021)="' + target + '" (Raw: "' + target + '")\n'
                '      E: application (line=5)\n'
                '        A: ' + namespace + 'debuggable(0x0101000f)=(type 0x12)0xffffffff\n')

    def run_manifest_gates(self, manifest):
        match = re.search(r"name='([^']+)'", "name='io.github.sunsetrne.dsha.debug.test'")
        for _, code in self.manifest_gates():
            exec(code, dict(re=re, manifest=manifest, package_match=match, package=match,
                            runner=RUNNER, target='io.github.sunsetrne.dsha.debug'))

    def test_original_xmltree_parsers_match_and_accept_official_format(self):
        first, second = self.manifest_gates()
        self.assertEqual(ast.dump(first[0]), ast.dump(second[0]))
        self.run_manifest_gates(self.manifest_fixture())
        # PrettyPrint 可以没有 Raw 值；有 Raw 时必须与编译值相同。
        without_raw = re.sub(r' \(Raw: "[^"]*"\)', '', self.manifest_fixture())
        self.run_manifest_gates(without_raw)

    def test_original_manifest_gates_reject_wrong_or_ambiguous_instrumentation(self):
        correct = self.manifest_fixture()
        node = correct.split('      E: instrumentation (line=4)\n', 1)[1].split('      E: application', 1)[0]
        mutants = {
            'wrong-runner': self.manifest_fixture(runner='other.Runner'),
            'wrong-target': self.manifest_fixture(target='other.target'),
            'missing-target': '\n'.join(line for line in correct.splitlines() if 'targetPackage' not in line),
            'duplicate-node': correct + '      E: instrumentation (line=6)\n' + node,
            'nested-node': correct.replace('      E: instrumentation (line=4)',
                                            '      E: application (line=3)\n          E: instrumentation (line=4)'),
            'wrong-namespace': correct.replace('http://schemas.android.com/apk/res/android:name(', 'other:name('),
            'conflicting-raw': correct.replace('(Raw: "' + RUNNER + '")', '(Raw: "other.Runner")'),
            'duplicate-attribute': correct.replace('      E: application (line=5)',
                                                   next(line for line in correct.splitlines() if ':name(' in line)
                                                   + '\n      E: application (line=5)'),
            'extra-root': correct + '  E: manifest (line=7)\n',
        }
        for name, mutant in mutants.items():
            with self.subTest(mutant=name), self.assertRaisesRegex(SystemExit, 'RUST_INSTRUMENTATION_'):
                self.run_manifest_gates(mutant)

    def test_instrumentation_payload_hash_detects_modification_and_duplicates(self):
        source = python_blocks(script(self.blocks['Sign ${{ matrix.variant }} APK and verify identity']))[-1]
        function = next(node for node in ast.parse(source).body
                        if isinstance(node, ast.FunctionDef) and node.name == 'payload')
        namespace = {'hashlib': hashlib, 'zipfile': zipfile}
        exec(compile(ast.Module(body=[function], type_ignores=[]), '<payload-gate>', 'exec'), namespace)
        with tempfile.TemporaryDirectory(prefix='rust-candidate-payload-') as directory:
            paths = [Path(directory) / name for name in ('before.apk', 'signed.apk', 'changed.apk', 'duplicate.apk')]
            for index, path in enumerate(paths):
                with zipfile.ZipFile(path, 'w') as archive:
                    archive.writestr('classes.dex', b'changed' if index == 2 else b'known-bytecode')
                    archive.writestr('META-INF/CERT.RSA', str(index).encode())
                    if index == 3:
                        with __import__('warnings').catch_warnings():
                            __import__('warnings').simplefilter('ignore', UserWarning)
                            archive.writestr('classes.dex', b'duplicate')
            payload = namespace['payload']
            self.assertEqual(payload(paths[0]), payload(paths[1]))
            self.assertNotEqual(payload(paths[0]), payload(paths[2]))
            with self.assertRaisesRegex(SystemExit, 'RUST_INSTRUMENTATION_DUPLICATE_PAYLOAD'):
                payload(paths[3])

    def handoff(self, mode, variant, mutation=None):
        """执行工作流原始交接校验 Python，仅使用临时合成文件和公开 CI 身份。"""
        source = python_blocks(script(self.blocks['Verify prebuilt unsigned handoff binding']))[0]
        with tempfile.TemporaryDirectory(prefix='rust-candidate-handoff-') as directory:
            handoff = Path(directory)
            apk = handoff / 'target-unsigned.apk'
            apk.write_bytes(b'host-static-fixture')
            for name in ('output-metadata.json', 'apk-managed-assets.txt', 'rust-native-apk-unsigned.json'):
                (handoff / name).write_bytes(b'{}')
            env = dict(UNSIGNED_DIR=directory, GITHUB_SHA='a' * 40, GITHUB_RUN_ID='123',
                       GITHUB_RUN_ATTEMPT='2', GITHUB_REPOSITORY='owner/repo', VARIANT=variant,
                       RELEASE_IDENTITY='mod', RUST_NATIVE_MODE=mode)
            candidate = None
            if mode == 'prebuilt' and variant == 'debug':
                destination = handoff / 'instrumentation'
                destination.mkdir()
                probe = destination / 'rust-native-instrumentation.apk'
                probe.write_bytes(b'candidate-apk')
                candidate = {'execution': 'NOT_RUN', 'androidNativeLoadVerified': False,
                             'commit': env['GITHUB_SHA'], 'runId': '123', 'runAttempt': '2',
                             'repository': 'owner/repo', 'apkFile': probe.name,
                             'apkSize': probe.stat().st_size, 'apkSha256': hashlib.sha256(probe.read_bytes()).hexdigest()}
                (destination / 'candidate-receipt.json').write_text(json.dumps(candidate), encoding='utf-8')
            receipt = {'schema': 1, 'signed': False, 'commit': env['GITHUB_SHA'], 'runId': '123',
                       'runAttempt': '2', 'repository': 'owner/repo', 'variant': variant,
                       'releaseIdentity': 'mod', 'rustNativeMode': mode, 'apkFile': apk.name,
                       'apkSize': apk.stat().st_size, 'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
                       'rustGateSha256': hashlib.sha256((handoff / 'rust-native-apk-unsigned.json').read_bytes()).hexdigest(),
                       'instrumentationCandidate': candidate}
            if mutation:
                mutation(handoff, receipt)
            (handoff / 'unsigned-receipt.json').write_text(json.dumps(receipt), encoding='utf-8')
            with patch.dict(os.environ, env), contextlib.redirect_stdout(io.StringIO()):
                exec(compile(source, '<workflow-handoff-gate>', 'exec'), {'__name__': '__fixture__'})

    def test_handoff_accepts_all_modes_without_running_android(self):
        for mode in ('off', 'prebuilt'):
            for variant in ('debug', 'dev'):
                with self.subTest(mode=mode, variant=variant):
                    self.handoff(mode, variant)

    def test_handoff_rejects_wrong_run_and_mode(self):
        for field, replacement in [('runAttempt', '1'), ('commit', 'b' * 40),
                                   ('repository', 'other/repo'), ('rustNativeMode', 'off')]:
            with self.subTest(field=field), self.assertRaisesRegex(SystemExit, 'UNSIGNED_HANDOFF_NOT_THIS_RUN'):
                self.handoff('prebuilt', 'debug', lambda _, value: value.update({field: replacement}))

    def test_handoff_rejects_candidate_and_report_tampering(self):
        with self.assertRaisesRegex(SystemExit, 'UNSIGNED_INSTRUMENTATION_CANDIDATE_MISMATCH'):
            self.handoff('prebuilt', 'debug', lambda root, _: (root / 'instrumentation/rust-native-instrumentation.apk').write_bytes(b'tamper'))
        with self.assertRaisesRegex(SystemExit, 'UNSIGNED_HANDOFF_RUST_GATE_MISMATCH'):
            self.handoff('off', 'dev', lambda root, _: (root / 'rust-native-apk-unsigned.json').write_bytes(b'tamper'))
        with self.assertRaisesRegex(SystemExit, 'UNEXPECTED_INSTRUMENTATION_CANDIDATE'):
            self.handoff('prebuilt', 'dev', lambda root, _: (root / 'instrumentation').mkdir())


class InstrumentationSource(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = (ROOT / JAVA_PATH).read_text(encoding='utf-8')

    def test_self_contained_real_android_jni_without_fallback_and_restores_default(self):
        for text in ('extends Instrumentation', 'void onStart()', 'RustNative.ensureLoaded()',
                     'RustNative.status()', 'RustNative.statusCode()', 'RustNative.isAvailable()',
                     'RustNative.abiVersion()', 'abiVersion == 1', 'RustNative.ABI_VERSION == 1',
                     'RustNative.digest(descriptor.getFd()', 'ByteBuffer.allocateDirect(32)',
                     'ABC_SHA256.equals(hex(actual))', 'knownBytes.length',
                     'containsEntry(apk, RECEIPT)',
                     'require(initialEnabled, Failure.BACKENDS_NOT_DEFAULT_ON)',
                     'RustBackends.setEnabled(false)', 'RustBackends.setEnabled(initialEnabled)',
                     'rustBackendsDefaultExpectation', 'rustBackendsRestored',
                     'BACKENDS_RESTORE_FAILED', 'BACKENDS_PROBE_SWITCH_MUTATED'):
            self.assertIn(text, self.source)
        # 只有临时关闭与 finally 恢复；不得用 setEnabled(true) 改写 App 的默认决定。
        for forbidden in ('loadFromPath', 'RustBackends.setEnabled(true)', 'RustDigest.', 'Assume.',
                          'org.junit', 'androidx.', 'System.load(', 'skip', 'fallback'):
            self.assertNotIn(forbidden, self.source)
        self.assertIn('!RustBackends.enabled()', self.source)
        self.assertIn('rustBackendsEnabledBefore', self.source)
        self.assertIn('rustBackendsEnabledAfter', self.source)
        self.assertNotIn('BACKENDS_NOT_DEFAULT_OFF', self.source)
        self.assertEqual(self.source.count('RustBackends.setEnabled(false)'), 1)
        self.assertEqual(self.source.count('RustBackends.setEnabled(initialEnabled)'), 1)

    def test_receipt_schema_and_budget_match_artifact_contract(self):
        self.assertIn('receipt.getInt("schema") == 2', self.source)
        self.assertRegex(self.source, r'MAX_RECEIPT_BYTES\s*=\s*2\s*\*\s*1024\s*\*\s*1024')
        for text in ('"github-actions".equals(receipt.getString("provenance"))',
                     'assets/rust-native-receipt.json', 'receipt.getString("commit")',
                     'receipt.getString("runId")', 'receipt.getString("runAttempt")',
                     'receipt.getString("repository")', 'artifact.getString("sha256")',
                     'artifact.getLong("size")', 'artifact.getString("artifactFile")',
                     'aarch64-linux-android', 'uniqueEntry(apk, APK_LIBRARY',
                     'new File(application.nativeLibraryDir, LIBRARY)', 'expectedHash.equals(hash(input, expectedSize))',
                     '/proc/self/maps', 'expected.getPath().equals(fields[5])', 'executableMapping'):
            self.assertIn(text, self.source)
        self.assertGreaterEqual(self.source.count('expectedHash.equals(hash(input, expectedSize))'), 2)

    def test_fd_lifetime_owned_cleanup_and_typed_failure(self):
        self.assertRegex(self.source, r'(?s)try \(ParcelFileDescriptor descriptor =\s*'
                                     r'ParcelFileDescriptor.open\(ownedTemporary, ParcelFileDescriptor.MODE_READ_ONLY\)\)')
        for text in ('File.createTempFile("rust-native-owned-"', 'try (FileOutputStream file',
                     'file.write(knownBytes)', 'file.getFD().sync()', 'ownedTemporary.delete()',
                     'finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, output)',
                     'private enum Failure', 'NATIVE_LOAD_FAILED', 'NATIVE_DIGEST_ERROR', 'MAPPED_LIBRARY_UNVERIFIED'):
            self.assertIn(text, self.source)
        for forbidden in ('deleteOnExit', 'listFiles(', 'deleteRecursively', 'getMessage()',
                          'printStackTrace', 'getSharedPreferences(', 'executeShellCommand(', 'Runtime.getRuntime'):
            self.assertNotIn(forbidden, self.source)
        for text in ('Build.SUPPORTED_ABIS', 'Build.VERSION.SDK_INT', 'Build.FINGERPRINT',
                     'Os.sysconf(OsConstants._SC_PAGESIZE)', '"librarySha256"'):
            self.assertIn(text, self.source)

    def test_extended_probe_keeps_identity_and_requires_all_native_stages_before_pass(self):
        self.assertIn('output.putString("probe", "dsha-rust-android-jni-v1")', self.source)
        self.assertIn('output.putInt("probeEvidenceRevision", 2)', self.source)
        stages = ['requireMappedLibrary(installedLibrary)',
                  'output.putBoolean("nativeFdDigestVerified", true)',
                  'probeArchive(output)', 'probePlanView(output)', 'success = true']
        positions = [self.source.index(stage) for stage in stages]
        self.assertEqual(positions, sorted(positions))
        for forbidden in ('RustBackends.archiveSelection()', 'RustBackends.planSelection()',
                          'RustArchiveCodec.packStream(', 'StrictGzipInputStream.open(',
                          'GZIPOutputStream(', 'GZIPInputStream(', 'getDeclaredField(',
                          'getLocalizedMessage()', 'error.toString()', 'System.out', 'System.err'):
            self.assertNotIn(forbidden, self.source)
        self.assertEqual(self.source.count('File.createTempFile('), 1)
        self.assertEqual(self.source.count('ownedTemporary.delete()'), 1)

    def test_archive_roundtrip_uses_production_streams_and_checks_framing_and_eof(self):
        compact = re.sub(r'\s+', ' ', self.source)
        for text in ('new byte[RustArchiveCodec.CHUNK + 73]',
                     'new RustGzipOutputStream(target)', 'new RustGzipInputStream(source)',
                     'try (RustGzipOutputStream stream = encoder)',
                     'try (RustGzipInputStream stream = decoder)',
                     'RustArchiveCodec.HEADER_HEX.equals(hex(Arrays.copyOf(gzip, 10)))',
                     'trailer.getInt(gzip.length - 8) == (int) crc.getValue()',
                     'trailer.getInt(gzip.length - 4) == plaintext.length',
                     'requireArchivePayload(stream, plaintext)', 'stream.isFinished()',
                     'buffer[index] == expected[offset + index]', 'offset == expected.length',
                     'expectIOException(() -> encoder.write(1)',
                     'expectIOException(() -> decoder.read()'):
            self.assertIn(text, compact)
        for evidence in ('archiveRoundtripVerified', 'archiveHeaderTrailerVerified',
                         'archiveClosedStreamsRejected', 'archiveRoundtripCleanupVerified'):
            self.assertIn('output.putBoolean("' + evidence + '", true)', self.source)

    def test_malformed_archive_and_close_failures_are_observed_without_replay(self):
        compact = re.sub(r'\s+', ' ', self.source)
        for case in ('archiveBadHeader', 'archiveBadTrailer', 'archiveTruncated',
                     'archiveTrailingData', 'archiveTwoMembers', 'archiveConstructorCloseFailure'):
            self.assertIn('"' + case + '"', self.source)
        for text in ('probeArchiveReadFailure(output, gzip, plaintext)',
                     'require(!stream.isFinished(), Failure.NATIVE_ARCHIVE_REJECTION_MISSING)',
                     'RustArchiveCodec.GZIP_HEADER', 'RustArchiveCodec.GZIP_TRAILER',
                     'RustArchiveCodec.GZIP_TRAILING_DATA',
                     'RustNative.archiveUnpackWrite(handle, input) == gzip.length',
                     'RustNative.archiveUnpackRead(handle, ByteBuffer.allocateDirect(32))',
                     'code == nativeError', '!constructed[0] && source.closes == 1',
                     'error != source.closeFailure', 'error.getSuppressed()[0] == source.closeFailure',
                     '== writeTarget.writeFailure', '== closeTarget.closeFailure',
                     '== source.closeFailure', 'writeTarget.writes == attempts',
                     'writeTarget.closes == 1', 'output.putBoolean(name + "Rejected", true)',
                     'output.putBoolean(name + "CleanupVerified", true)'):
            self.assertIn(text, compact)
        for evidence in ('archiveReadFailureRejected', 'archiveReadFailureCleanupVerified',
                         'archiveSourceCloseFailureVerified', 'archiveTargetCloseFailureVerified',
                         'archiveFinishWriteFailureVerified', 'archiveFailureNoReplayVerified',
                         'archiveCloseFailureCleanupVerified'):
            self.assertIn('output.putBoolean("' + evidence + '", true)', self.source)

    def test_archive_cleanup_reacquires_all_slots_and_checks_frozen_stale_handles(self):
        compact = re.sub(r'\s+', ' ', self.source)
        self.assertIn('ARCHIVE_SESSIONS = 4', compact)
        for text in ('long[] handles = new long[ARCHIVE_SESSIONS]',
                     'extraPack == RustArchiveCodec.SESSION_LIMIT',
                     'extraUnpack == RustArchiveCodec.SESSION_LIMIT',
                     'handles[0], prefix, ByteBuffer.allocateDirect(RustArchiveCodec.packBound(1))',
                     'RustArchiveCodec.consumed(update) == 1',
                     'RustNative.archiveUnpackWrite(handles[2], prefix) == 1',
                     'RustArchiveCodec.unpackProduced(waiting) == 0',
                     'RustArchiveCodec.FLAG_NEED_INPUT',
                     'RustNative.archivePackAbort(handles[index])',
                     'RustNative.archiveUnpackAbort(handles[index])',
                     'released &= code == RustArchiveCodec.OK',
                     'requireArchiveHandleStale(handles[index], index < 2)',
                     'operation == RustArchiveCodec.STALE_HANDLE && abort == RustArchiveCodec.STALE_HANDLE',
                     'RustNative.archivePackFinish(handle, bytes) == RustArchiveCodec.STALE_HANDLE',
                     'code == (finished ? RustArchiveCodec.STALE_HANDLE : RustArchiveCodec.OK)',
                     'NATIVE_ARCHIVE_CLEANUP_FAILED'):
            self.assertIn(text, compact)
        self.assertGreaterEqual(self.source.count('requireArchiveSessionsFree(output)'), 7)
        self.assertIn('"archiveSessionCapacityChecks"', self.source)
        self.assertIn('"archiveNativeFinishStaleVerified"', self.source)
        self.assertIn('"archiveAbortCleanupVerified"', self.source)

    def test_plan_verdicts_compare_real_native_returns_and_complete_reason_sets(self):
        compact = re.sub(r'\s+', ' ', self.source)
        for text in ('PlanViewCodec.encode(', '.policyVersion(2)', '.rootCount(0)',
                     '.generatedAtMs(10)', '.expiresAtMs(20)', '.nowMs(20)',
                     'PlanViewValidator.REASON_FIELD_RANGE', 'PlanViewValidator.REASON_POLICY_VERSION',
                     'PlanViewValidator.REASON_EXPIRED', 'PlanViewValidator.REASON_STRUCTURE',
                     'new byte[PlanViewCodec.HEADER_BYTES - 1]',
                     'PlanViewValidator.Result reference = PlanViewValidator.validate(view)',
                     'ByteBuffer.allocateDirect(view.length)', 'ByteOrder.LITTLE_ENDIAN',
                     'for (int repeat = 0; repeat < 2; repeat++)',
                     'int code = RustNative.verifyPlan(input, view.length, reasons, PLAN_MAX_REASONS)',
                     'code == expectedCode', 'count == expectedReasons.length',
                     'actualReasons[index] = reasons.getInt(4 + 4 * index)',
                     'Arrays.equals(actualReasons, expectedReasons)',
                     'input.get(index) == view[index]',
                     'output.putString(name + "Reasons", Arrays.toString(actualReasons))'):
            self.assertIn(text, compact)
        self.assertLess(self.source.index('require(code == expectedCode,'),
                        self.source.index('int count = reasons.getInt(0)'))
        for case in ('planValid', 'planInvalid', 'planMalformed', 'planAfterErrors'):
            self.assertIn('"' + case + '"', self.source)

    def test_plan_jni_errors_preserve_output_and_input_then_allow_new_valid_verification(self):
        compact = re.sub(r'\s+', ' ', self.source)
        for case in ('planOutputBound', 'planHeapInput', 'planNullInput',
                     'planLengthError', 'planSmallReasons'):
            self.assertIn('"' + case + '"', self.source)
        for text in ('RustArchiveCodec.OUTPUT_BOUND', 'RustArchiveCodec.INVALID_ARGUMENT',
                     'reasons.put(index, PLAN_SENTINEL)',
                     'RustNative.verifyPlan(input, length, reasons, maxReasons)',
                     'code == expectedCode && code < 0',
                     'reasons.get(index) == PLAN_SENTINEL',
                     'input.get(index) == snapshot[index]', '"nativePlanCalls"'):
            self.assertIn(text, compact)
        error = self.source.split('private static void probePlanError(', 1)[1].split('private String binding(', 1)[0]
        self.assertNotIn('getInt(', error.replace('output.getInt("nativePlanCalls", 0)', ''))
        self.assertLess(self.source.index('"planSmallReasons"'), self.source.index('"planAfterErrors"'))
        for evidence in ('planErrorOutputUntouched', 'planInputUnchanged',
                         'planDeterministicRepeatsVerified'):
            self.assertIn('output.putBoolean("' + evidence + '", true)', self.source)

    def test_default_runner_unchanged_and_exact_format_inventory_entry(self):
        gradle = (ROOT / 'app/build.gradle').read_text(encoding='utf-8')
        self.assertRegex(gradle, r'''testInstrumentationRunner project.findProperty\('dsha.testRunner'\)\s*\?:\s*"com.deepseekharness.app.Rc13Instrumentation"''')
        manifest = json.loads((ROOT / 'tools/java-format-files.json').read_text(encoding='utf-8'))
        self.assertEqual(manifest['files'].count(JAVA_PATH), 1)


class GradleRunnerInputProtection(unittest.TestCase):
    def reject_overlap(self, relative_output):
        with tempfile.TemporaryDirectory(prefix='rust-gradle-report-overlap-') as directory:
            root = Path(directory)
            native = root / 'artifact'
            native.mkdir()
            (native / 'libdsha_rs.so').write_bytes(b'original library bytes, not a compiled ELF')
            (native / 'rust-receipt.json').write_bytes(b'{"original":"receipt bytes"}\n')

            def snapshot():
                return {path.relative_to(root).as_posix():
                        None if path.is_dir() else path.read_bytes() for path in root.rglob('*')}

            before = snapshot()
            result = subprocess.run(
                [sys.executable, '-B', str(ROOT / 'tools/rust/check_gradle_prebuilt.py'),
                 '--artifact-dir', str(native), '--output', str(root / relative_output),
                 '--gradle', str(root / 'gradle-must-never-execute')],
                cwd=ROOT, capture_output=True, text=True, check=False)
            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
            self.assertIn('RUST_GRADLE_REPORT_INPUT_OVERLAP', result.stderr)
            self.assertNotIn('RUST_GRADLE_EXECUTABLE_MISSING', result.stderr)
            self.assertEqual(before, snapshot())

    def test_report_cannot_replace_the_original_library_or_receipt(self):
        for name in ('libdsha_rs.so', 'rust-receipt.json'):
            with self.subTest(name=name):
                self.reject_overlap('artifact/' + name)

    def test_report_and_scratch_directory_cannot_be_nested_in_original_artifact(self):
        for name in ('artifact/report.json', 'artifact/nested/report.json', 'artifact'):
            with self.subTest(name=name):
                self.reject_overlap(name)

    def test_report_cannot_replace_an_ancestor_of_the_original_artifact(self):
        self.reject_overlap('.')


if __name__ == '__main__':
    if __import__('sys').flags.optimize:
        raise SystemExit('RUST_CANDIDATE_TEST_OPTIMIZATION_FORBIDDEN')
    unittest.main(verbosity=2)
