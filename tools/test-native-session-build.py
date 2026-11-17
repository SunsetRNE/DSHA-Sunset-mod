"""Exercise reproducibility comparisons without writing shipped native assets."""
import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('native_session_build',
                                             Path(__file__).parent / 'native-session/build.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class NativeSessionBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.ndk = self.root / 'ndk'
        self.ndk.mkdir()
        (self.ndk / 'source.properties').write_text('Pkg.Revision = ' + builder.NDK_VERSION)
        self.output = self.root / 'libdsha-session.so'
        self.output.write_bytes(b'shipped')
        self.commands = []

    def compile(self, command, **options):
        self.commands.append(command)
        if '-o' in command:
            Path(command[command.index('-o') + 1]).write_bytes(b'shipped')

    def test_read_only_check_uses_locked_api_alignment_and_exact_comparison(self):
        with patch.object(builder.platform, 'system', return_value='Linux'), \
                patch.object(builder.subprocess, 'run', side_effect=self.compile):
            builder.build(self.ndk, self.output, check=True)
        self.assertEqual(b'shipped', self.output.read_bytes())
        self.assertIn('--target=aarch64-linux-android23', self.commands[0])
        self.assertIn('-Wl,-z,max-page-size=16384', self.commands[0])
        self.assertIn('-Wl,-z,common-page-size=4096', self.commands[0])
        self.assertNotEqual(self.output, Path(self.commands[0][-1]))
        self.assertFalse(Path(self.commands[0][-1]).exists())

    def test_mismatch_preserves_original_and_rejects_missing_original(self):
        self.output.write_bytes(b'changed')
        with patch.object(builder.platform, 'system', return_value='Linux'), \
                patch.object(builder.subprocess, 'run', side_effect=self.compile):
            with self.assertRaisesRegex(ValueError, 'REBUILD_MISMATCH'):
                builder.build(self.ndk, self.output, check=True)
            self.assertEqual(b'changed', self.output.read_bytes())
            self.output.unlink()
            with self.assertRaisesRegex(ValueError, 'REBUILD_MISMATCH'):
                builder.build(self.ndk, self.output, check=True)
        self.assertFalse(self.output.exists())

    def test_wrong_ndk_never_starts_compiler(self):
        (self.ndk / 'source.properties').write_text('Pkg.Revision = 27.0.0')
        with patch.object(builder.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'NDK_VERSION'):
                builder.build(self.ndk, self.output, check=True)
        run.assert_not_called()


def setUpModule():
    # 所有 subprocess.run 必须由用例 mock；任何漏网的进程创建都直接失败。
    global process_guard
    process_guard = patch.object(subprocess, 'Popen', side_effect=AssertionError('REAL_PROCESS_FORBIDDEN'))
    process_guard.start()


def tearDownModule():
    process_guard.stop()


def synthetic_elf(payload=b'CODE'):
    """仅供解析测试的字节夹具，不是原生构建或运行回执。"""
    names = b'\0.shstrtab\0.text\0.bss\0'
    content = bytearray(160 + 4 * 64)
    struct.pack_into('<16sHHIQQQIHHHHHH', content, 0,
                     b'\x7fELF\x02\x01' + bytes(10), 3, 183, 1, 0x4000, 64, 160,
                     0, 64, 56, 1, 64, 4, 1)
    struct.pack_into('<IIQQQQQQ', content, 64, 1, 5, 120, 0x4000, 0x4000, 4, 12, 0x4000)
    content[120:124] = payload
    content[124:124 + len(names)] = names
    struct.pack_into('<IIQQQQIIQQ', content, 224, 1, 3, 0, 0, 124, len(names), 0, 0, 1, 0)
    struct.pack_into('<IIQQQQIIQQ', content, 288, 11, 1, 6, 0x4000, 120, 4, 0, 0, 4, 0)
    struct.pack_into('<IIQQQQIIQQ', content, 352, 17, 8, 3, 0x4004, 4096, 8, 0, 0, 4, 0)
    return bytes(content)


class NativeSessionDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        def start(patcher):
            self.addCleanup(patcher.stop)
            return patcher.start()
        self.start_patch = start
        start(patch.object(builder, 'ROOT', self.root))
        start(patch.object(builder.platform, 'system', return_value='Linux'))
        self.ndk = self.root / 'ndk'
        self.ndk.mkdir()
        (self.ndk / 'source.properties').write_text('Pkg.Revision = ' + builder.NDK_VERSION)
        lock = self.root / 'ci/toolchain.lock.json'
        lock.parent.mkdir()
        lock.write_text('{}')
        self.tools = self.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
        self.tools.mkdir(parents=True)
        for name in ('clang', 'ld.lld', 'llvm-strip', 'llvm-readelf'):
            (self.tools / name).write_bytes(b'MOCK-NOT-EXECUTABLE')
        library = self.tools.parent / 'sysroot/usr/lib/aarch64-linux-android/23'
        library.mkdir(parents=True)
        for name in ('crtbegin_dynamic.o', 'crtend_android.o', 'libc.so', 'libdl.so'):
            (library / name).write_bytes(b'MOCK-LINK-INPUT')
        builtins = self.tools.parent / 'lib/clang/17/lib/linux/libclang_rt.builtins-aarch64-android.a'
        builtins.parent.mkdir(parents=True)
        builtins.write_bytes(b'MOCK-BUILTINS')
        self.output = self.root / 'libdsha-session.so'
        self.original = synthetic_elf()
        self.output.write_bytes(self.original)
        self.candidate = synthetic_elf(b'DIFF')
        self.evidence = self.root / 'app/build/native-session-repro-test'
        self.calls = []
        self.run = start(patch.object(builder.subprocess, 'run', side_effect=self.fake_run))

    def fake_run(self, argv, **options):
        self.calls.append((argv, options))
        if '-o' in argv:
            Path(argv[-1]).write_bytes(self.candidate)
        if 'stdout' in options:
            options['stdout'].write(b'mock stdout\n')
            options['stderr'].write(b'mock stderr\n')
        return subprocess.CompletedProcess(argv, 0)

    def mismatch(self):
        with self.assertRaisesRegex(ValueError, '^NATIVE_SESSION_REBUILD_MISMATCH$'):
            builder.build(self.ndk, self.output, check=True, evidence_dir=self.evidence)
        return json.loads((self.evidence / 'report.json').read_text())

    def test_mismatch_keeps_compared_bytes_after_temp_cleanup(self):
        report = self.mismatch()
        self.assertEqual('COMPLETE', report['status'])
        self.assertEqual(self.original, self.output.read_bytes())
        self.assertEqual(self.original, (self.evidence / 'expected.elf').read_bytes())
        self.assertEqual(self.candidate, (self.evidence / 'candidate.elf').read_bytes())
        self.assertFalse(Path(self.calls[0][0][-1]).exists())
        self.assertEqual(120, report['firstDifference']['offset'])
        self.assertEqual(builder.byte_identity(self.candidate)['sha256'], report['files']['candidate']['sha256'])
        self.assertTrue(report['comparisonPerformed'])
        self.assertFalse(report['toolchainInventoryComplete'])
        self.assertIn('unknown', report['implicitInputs'])
        self.assertEqual(['compile', 'strip', 'readelf'], [item['stage'] for item in report['commands'][:3]])
        self.assertTrue(all(item['returncode'] == 0 and item['cwd'] == os.getcwd() for item in report['commands']))
        self.assertTrue(all(item['stdout'] == 'mock stdout\n' for item in report['commands']))
        self.assertEqual(7, len(self.calls))

    def test_success_does_not_create_or_collect_failure_evidence(self):
        self.candidate = self.original
        with patch.object(builder, 'save_evidence') as save:
            digest = builder.build(self.ndk, self.output, check=True, evidence_dir=self.evidence)
        save.assert_not_called()
        self.assertFalse(self.evidence.exists())
        self.assertEqual(builder.byte_identity(self.original)['sha256'], digest)
        self.assertEqual(3, self.run.call_count)

    def test_default_write_and_check_keep_exact_argv_and_options(self):
        builder.build(self.ndk, self.output)
        self.assertEqual(self.candidate, self.output.read_bytes())
        self.assertTrue(all(options == {'check': True} for _, options in self.calls))
        argv = self.calls[0][0]
        self.assertEqual([str(self.tools / 'clang'), '--target=aarch64-linux-android23',
            '-std=c11', '-D_GNU_SOURCE', '-O2', '-fPIE', '-pie', '-fstack-protector-strong',
            '-D_FORTIFY_SOURCE=2', '-ffile-prefix-map=' + str(self.root) + '=/src',
            '-Wl,-z,relro', '-Wl,-z,now', '-Wl,-z,max-page-size=16384',
            '-Wl,-z,common-page-size=4096', str(Path(builder.__file__).with_name('session-launcher.c')),
            '-o', argv[-1]], argv)
        self.assertEqual([str(self.tools / 'llvm-strip'), '--strip-unneeded', argv[-1]], self.calls[1][0])
        self.assertEqual([str(self.tools / 'llvm-readelf'), '--program-headers', '--wide', argv[-1]], self.calls[2][0])
        self.calls.clear()
        builder.build(self.ndk, self.output, check=True)
        self.assertTrue(all(options == {'check': True} for _, options in self.calls))

    def test_length_mismatch_records_eof_difference(self):
        self.candidate = self.original + b'X'
        report = self.mismatch()
        self.assertEqual({'offset': len(self.original), 'expectedByte': None, 'candidateByte': 88}, report['firstDifference'])

    def test_missing_original_still_fails_and_records_unavailable(self):
        self.output.unlink()
        report = self.mismatch()
        self.assertFalse(self.output.exists())
        self.assertFalse(report['files']['expected']['available'])
        self.assertEqual('INCOMPLETE', report['status'])
        self.assertIsNone(report['firstDifference'])

    def test_snapshot_is_not_reread_during_diagnostics(self):
        save = builder.save_evidence
        def mutate_then_save(*args):
            self.output.write_bytes(b'changed-after-comparison')
            Path(self.calls[0][0][-1]).write_bytes(b'changed-candidate-after-comparison')
            return save(*args)
        with patch.object(builder, 'save_evidence', side_effect=mutate_then_save):
            report = self.mismatch()
        self.assertEqual(self.original, (self.evidence / 'expected.elf').read_bytes())
        self.assertEqual(self.candidate, (self.evidence / 'candidate.elf').read_bytes())
        self.assertEqual('comparison-snapshot', report['files']['candidate']['basis'])

    def test_each_build_command_failure_keeps_original_exception(self):
        for index, stage in enumerate(('compile', 'strip', 'readelf')):
            with self.subTest(stage=stage):
                self.evidence = self.root / ('app/build/failure-' + stage)
                self.calls.clear()
                failure = subprocess.CalledProcessError(19, ['mock-' + stage])
                def fail(argv, **options):
                    if len(self.calls) == index:
                        self.calls.append((argv, options))
                        raise failure
                    return self.fake_run(argv, **options)
                self.run.side_effect = fail
                with self.assertRaises(subprocess.CalledProcessError) as caught:
                    builder.build(self.ndk, self.output, check=True, evidence_dir=self.evidence)
                self.assertIs(failure, caught.exception)
                report = json.loads((self.evidence / 'report.json').read_text())
                self.assertFalse(report['comparisonPerformed'])
                self.assertEqual(stage, report['failure']['stage'])
                self.assertEqual(19, report['commands'][index]['returncode'])
                self.assertEqual(self.original, self.output.read_bytes())
                self.assertFalse(Path(self.calls[0][0][-1]).exists())

    def test_diagnostic_errors_and_timeout_do_not_change_mismatch(self):
        for error in (subprocess.CalledProcessError(7, ['mock-readelf']), subprocess.TimeoutExpired(['mock-readelf'], 30)):
            with self.subTest(error=type(error).__name__):
                self.evidence = self.root / ('app/build/' + type(error).__name__)
                self.calls.clear()
                def fail(argv, **options):
                    if 'timeout' in options:
                        raise error
                    return self.fake_run(argv, **options)
                self.run.side_effect = fail
                report = self.mismatch()
                self.assertEqual('INCOMPLETE', report['status'])
                self.assertEqual(4, len(report['errors']))

    def test_write_evidence_failure_leaves_early_manifest_and_mismatch(self):
        original_open = Path.open
        def fail_copy(path, *args, **kwargs):
            if path.name == 'candidate.elf':
                early = json.loads((self.evidence / 'report.json').read_text())
                self.assertEqual('INCOMPLETE', early['status'])
                raise OSError('mock storage failure')
            return original_open(path, *args, **kwargs)
        with patch.object(Path, 'open', fail_copy):
            report = self.mismatch()
        self.assertEqual('INCOMPLETE', report['status'])
        self.assertIn({'item': 'candidate-copy', 'error': 'OSError'}, report['errors'])
        self.assertEqual(self.original, self.output.read_bytes())

    def test_total_diagnostics_failure_cannot_replace_original_exception(self):
        with patch.object(builder, 'save_evidence', side_effect=OSError('mock manifest failure')):
            with self.assertRaisesRegex(ValueError, '^NATIVE_SESSION_REBUILD_MISMATCH$'):
                builder.build(self.ndk, self.output, check=True, evidence_dir=self.evidence)
        self.assertEqual(self.original, self.output.read_bytes())

    def test_closed_stderr_after_evidence_failure_preserves_compile_exception(self):
        failure = subprocess.CalledProcessError(19, ['mock-compile'])
        self.run.side_effect = failure
        stderr = io.StringIO()
        stderr.close()
        with patch.object(builder, 'save_evidence', side_effect=OSError('mock manifest failure')) as save, \
                patch.object(builder.sys, 'stderr', stderr):
            with self.assertRaises(subprocess.CalledProcessError) as caught:
                builder.build(self.ndk, self.output, check=True, evidence_dir=self.evidence)
        self.assertIs(failure, caught.exception)
        self.assertIs(failure, save.call_args.args[10])
        self.assertEqual(1, self.run.call_count)
        self.assertEqual(self.original, self.output.read_bytes())
        self.assertFalse(self.evidence.exists())

    def test_closed_stderr_after_evidence_failure_preserves_mismatch_exception(self):
        stderr = io.StringIO()
        stderr.close()
        with patch.object(builder, 'save_evidence', side_effect=OSError('mock manifest failure')) as save, \
                patch.object(builder.sys, 'stderr', stderr):
            with self.assertRaises(ValueError) as caught:
                builder.build(self.ndk, self.output, check=True, evidence_dir=self.evidence)
        failure = save.call_args.args[10]
        self.assertIs(failure, caught.exception)
        self.assertEqual('NATIVE_SESSION_REBUILD_MISMATCH', str(failure))
        self.assertEqual(3, self.run.call_count)
        self.assertEqual(self.original, self.output.read_bytes())
        self.assertFalse(self.evidence.exists())

    def test_diagnostic_warning_does_not_swallow_base_exception(self):
        interrupt = KeyboardInterrupt('mock warning interrupt')
        class InterruptedStderr:
            def write(self, text):
                raise interrupt
        with patch.object(builder, 'save_evidence', side_effect=OSError('mock manifest failure')), \
                patch.object(builder.sys, 'stderr', InterruptedStderr()):
            with self.assertRaises(KeyboardInterrupt) as caught:
                builder.build(self.ndk, self.output, check=True, evidence_dir=self.evidence)
        self.assertIs(interrupt, caught.exception)
        self.assertEqual(self.original, self.output.read_bytes())

    def test_wrong_ndk_and_write_evidence_fail_before_tools(self):
        with self.assertRaisesRegex(ValueError, 'EVIDENCE_REQUIRES_CHECK'):
            builder.build(self.ndk, self.output, evidence_dir=self.evidence)
        (self.ndk / 'source.properties').write_text('Pkg.Revision = wrong')
        with self.assertRaisesRegex(ValueError, 'NDK_VERSION'):
            builder.build(self.ndk, self.output, check=True, evidence_dir=self.evidence)
        self.run.assert_not_called()
        self.assertFalse(self.evidence.exists())

    def test_invalid_existing_and_symlink_paths_never_overwrite(self):
        existing = self.root / 'app/build/existing'
        existing.mkdir(parents=True)
        sentinel = existing / 'keep'
        sentinel.write_bytes(b'keep')
        linked = self.root / 'app/build/linked'
        linked.symlink_to(existing, target_is_directory=True)
        outside = self.root / 'outside'
        for path in (existing, outside, self.root / 'app/build', self.root / 'app/build/../escape', linked, linked / 'new'):
            with self.subTest(path=str(path)), self.assertRaisesRegex(ValueError, 'EVIDENCE_'):
                builder.build(self.ndk, self.output, check=True, evidence_dir=path)
        self.run.assert_not_called()
        self.assertEqual(b'keep', sentinel.read_bytes())
        self.assertEqual([sentinel], list(existing.iterdir()))

    def test_overlapping_missing_output_and_evidence_are_rejected_before_tools(self):
        cases = (('evidence-inside-output', 'reference.elf', 'reference.elf/diagnostics'),
                 ('output-inside-evidence', 'diagnostics/reference.elf', 'diagnostics'),
                 ('equal', 'same.elf', 'same.elf'))
        for label, output, evidence in cases:
            with self.subTest(relation=label):
                self.run.reset_mock()
                base = self.root / 'app/build' / label
                output = base / output
                evidence = base / evidence
                self.assertFalse(output.exists())
                self.assertFalse(evidence.exists())
                with self.assertRaises(ValueError) as caught:
                    builder.build(self.ndk, output, check=True, evidence_dir=evidence)
                self.assertEqual({'failure': 'NATIVE_SESSION_EVIDENCE_PATH', 'toolsCalled': False,
                                  'outputExists': False, 'evidenceExists': False, 'baseExists': False},
                                 {'failure': str(caught.exception), 'toolsCalled': self.run.called,
                                  'outputExists': output.exists(), 'evidenceExists': evidence.exists(),
                                  'baseExists': base.exists()})
                self.run.assert_not_called()

    def test_directory_created_after_validation_is_not_reused(self):
        def create_then_run(argv, **options):
            if '-o' in argv:
                self.evidence.mkdir(parents=True)
                (self.evidence / 'keep').write_bytes(b'keep')
            return self.fake_run(argv, **options)
        self.run.side_effect = create_then_run
        with self.assertRaisesRegex(ValueError, '^NATIVE_SESSION_REBUILD_MISMATCH$'):
            builder.build(self.ndk, self.output, check=True, evidence_dir=self.evidence)
        self.assertEqual(['keep'], [item.name for item in self.evidence.iterdir()])

    def test_allowlisted_ci_metadata_and_missing_input_are_explicit(self):
        (self.tools / 'ld.lld').unlink()
        with patch.dict(os.environ, {'GITHUB_SHA': 'mock-sha', 'GITHUB_RUN_ID': '42',
                'GITHUB_RUN_ATTEMPT': '3', 'GITHUB_REPOSITORY': 'mock/repo',
                'GITHUB_TOKEN': 'DO-NOT-COPY', 'ASSET_URL': 'https://example.invalid/?signature=SECRET'}, clear=True):
            report = self.mismatch()
        self.assertEqual(4, len(report['ci']))
        text = (self.evidence / 'report.json').read_text()
        self.assertNotIn('DO-NOT-COPY', text)
        self.assertNotIn('signature=', text)
        self.assertEqual('INCOMPLETE', report['status'])
        self.assertTrue(any(item['path'].endswith('ld.lld') and not item['available'] for item in report['inputs']))

    def test_limited_command_output_is_explicitly_incomplete(self):
        def verbose(argv, **options):
            result = self.fake_run(argv, **options)
            options['stdout'].write(b'x' * (builder.LOG_LIMIT + 10))
            return result
        self.run.side_effect = verbose
        report = self.mismatch()
        self.assertEqual('INCOMPLETE', report['status'])
        self.assertTrue(report['commands'][0]['stdoutTruncated'])
        self.assertEqual(builder.LOG_LIMIT, len(report['commands'][0]['stdout']))

    def test_elf_sections_loads_nobits_and_bounds(self):
        info = builder.elf_regions(self.original)
        self.assertEqual(builder.byte_identity(b'CODE'), info['loadSegments'][0]['fileBytes'])
        self.assertEqual(builder.byte_identity(b'CODE'), info['sections'][2]['fileBytes'])
        self.assertIsNone(info['sections'][3]['fileBytes'])
        malformed = [b'not elf', self.original[:100]]
        for offset, fmt, value in ((32, '<Q', 99999), (58, '<H', 63), (60, '<H', 5000),
                                   (62, '<H', 99), (288, '<I', 9999), (312, '<Q', 99999), (96, '<Q', 99999)):
            data = bytearray(self.original)
            struct.pack_into(fmt, data, offset, value)
            malformed.append(bytes(data))
        for data in malformed:
            with self.subTest(length=len(data)), self.assertRaises(ValueError):
                builder.elf_regions(data)

    def test_success_survives_optional_capture_setup_failure(self):
        self.candidate = self.original
        with patch.object(builder.tempfile, 'TemporaryFile', side_effect=OSError('mock capture failure')):
            builder.build(self.ndk, self.output, check=True, evidence_dir=self.evidence)
        self.assertEqual(3, self.run.call_count)
        self.assertFalse(self.evidence.exists())
        self.assertEqual(self.original, self.output.read_bytes())
        self.assertTrue(all(options == {'check': True} for _, options in self.calls))

    def test_capture_failure_is_incomplete_without_replaying_commands(self):
        with patch.object(builder.tempfile, 'TemporaryFile', side_effect=OSError('mock capture failure')):
            report = self.mismatch()
        self.assertEqual('INCOMPLETE', report['status'])
        self.assertEqual(7, self.run.call_count)
        self.assertTrue(all(item['captureUnavailable'] == 'OSError' for item in report['commands']))

    def test_snapshot_read_failure_is_explicit_and_preserves_original_error(self):
        read = Path.read_bytes
        failure = OSError('mock unreadable candidate')
        def fail_candidate(path):
            if path.name == 'libdsha-session.so' and path != self.output:
                raise failure
            return read(path)
        with patch.object(Path, 'read_bytes', fail_candidate):
            with self.assertRaises(OSError) as caught:
                builder.build(self.ndk, self.output, check=True, evidence_dir=self.evidence)
        self.assertIs(failure, caught.exception)
        report = json.loads((self.evidence / 'report.json').read_text())
        self.assertEqual('candidate-read', report['failure']['stage'])
        self.assertEqual('INCOMPLETE', report['status'])
        self.assertIn({'item': 'candidate-snapshot', 'error': 'OSError'}, report['errors'])

    def test_cli_forwards_explicit_check_evidence_path(self):
        with patch.object(builder.sys, 'argv', ['build.py', '--ndk', str(self.ndk), '--check',
                '--evidence-dir', 'app/build/unique']), patch.object(builder, 'build') as build:
            builder.main()
        build.assert_called_once_with(self.ndk.resolve(),
            (self.root / 'app/src/main/jniLibs/arm64-v8a/libdsha-session.so').resolve(),
            check=True, evidence_dir=Path('app/build/unique'))
        self.run.assert_not_called()

    def test_malformed_elf_is_incomplete_and_cannot_bypass_comparison(self):
        self.candidate = b'not-an-elf'
        report = self.mismatch()
        self.assertEqual('INCOMPLETE', report['status'])
        self.assertIn({'item': 'candidate-elf', 'error': 'ValueError'}, report['errors'])
        self.assertEqual(self.original, self.output.read_bytes())

    def test_capture_read_and_close_failures_preserve_process_outcome(self):
        class BrokenCapture(io.BytesIO):
            def read(self, *args):
                raise OSError('mock read error')
            def close(self):
                super().close()
                raise OSError('mock close error')
        records = []
        argv = ['mock-not-executed']
        with patch.object(builder.tempfile, 'TemporaryFile', side_effect=BrokenCapture):
            builder.run_recorded(argv, 'test-success', records)
            failure = subprocess.CalledProcessError(33, argv)
            self.run.side_effect = failure
            with self.assertRaises(subprocess.CalledProcessError) as caught:
                builder.run_recorded(argv, 'test-failure', records)
        self.assertIs(failure, caught.exception)
        self.assertEqual([0, 33], [item['returncode'] for item in records])
        self.assertTrue(all(item['stdoutUnavailable'] == 'OSError' and item['captureCloseError'] == 'OSError'
                            for item in records))
        self.assertEqual(2, self.run.call_count)

    def test_process_creation_guard_remains_active(self):
        with self.assertRaisesRegex(AssertionError, 'REAL_PROCESS_FORBIDDEN'):
            subprocess.Popen(['never-executed'])

    def windows_tools(self):
        # 只构造不可执行的工具链夹具，真实进程仍由模块级 Popen 守卫阻断。
        tools = self.ndk / 'toolchains/llvm/prebuilt/windows-x86_64/bin'
        tools.mkdir(parents=True)
        for name in ('clang', 'ld.lld', 'llvm-strip', 'llvm-readelf'):
            (tools / (name + '.exe')).write_bytes(b'MOCK-NOT-EXECUTABLE')
        library = tools.parent / 'sysroot/usr/lib/aarch64-linux-android/23'
        library.mkdir(parents=True)
        for name in ('crtbegin_dynamic.o', 'crtend_android.o', 'libc.so', 'libdl.so'):
            (library / name).write_bytes(b'MOCK-LINK-INPUT')
        builtins = tools.parent / 'lib/clang/17/lib/linux/libclang_rt.builtins-aarch64-android.a'
        builtins.parent.mkdir(parents=True)
        builtins.write_bytes(b'MOCK-BUILTINS')
        return tools

    def test_windows_check_keeps_exact_argv_alignment_and_original_bytes(self):
        tools = self.windows_tools()
        self.candidate = self.original
        with patch.object(builder.platform, 'system', return_value='Windows'):
            digest = builder.build(self.ndk, self.output, check=True, evidence_dir=self.evidence)
        self.assertEqual(builder.byte_identity(self.original)['sha256'], digest)
        self.assertEqual(self.original, self.output.read_bytes())
        self.assertFalse(self.evidence.exists())
        self.assertEqual(3, self.run.call_count)
        argv = self.calls[0][0]
        self.assertEqual([str(tools / 'clang.exe'), '--target=aarch64-linux-android23',
            '-std=c11', '-D_GNU_SOURCE', '-O2', '-fPIE', '-pie', '-fstack-protector-strong',
            '-D_FORTIFY_SOURCE=2', '-ffile-prefix-map=' + str(self.root) + '=/src',
            '-Wl,-z,relro', '-Wl,-z,now', '-Wl,-z,max-page-size=16384',
            '-Wl,-z,common-page-size=4096', str(Path(builder.__file__).with_name('session-launcher.c')),
            '-o', argv[-1]], argv)
        self.assertEqual([str(tools / 'llvm-strip.exe'), '--strip-unneeded', argv[-1]], self.calls[1][0])
        self.assertEqual([str(tools / 'llvm-readelf.exe'), '--program-headers', '--wide', argv[-1]], self.calls[2][0])
        self.assertTrue(all(options['check'] and set(options) == {'check', 'stdout', 'stderr'}
                            for _, options in self.calls))
        self.assertNotEqual(self.output, Path(argv[-1]))
        self.assertFalse(Path(argv[-1]).exists())

    def test_windows_mismatch_keeps_header_trailing_bytes_and_original_evidence(self):
        tools = self.windows_tools()
        header = bytearray(self.original)
        header[48] = 1  # ELF flags；代码与 LOAD 字节不变也必须拒绝整文件差异。
        for label, candidate, offset in (('header', bytes(header), 48),
                ('trailing', self.original + b'compiler comment', len(self.original))):
            with self.subTest(difference=label), patch.object(builder.platform, 'system', return_value='Windows'):
                self.candidate = candidate
                self.evidence = self.root / ('app/build/windows-' + label)
                self.calls.clear()
                report = self.mismatch()
            self.assertEqual('COMPLETE', report['status'])
            self.assertEqual('Windows', report['host']['system'])
            self.assertEqual(self.original, self.output.read_bytes())
            self.assertEqual(self.original, (self.evidence / 'expected.elf').read_bytes())
            self.assertEqual(candidate, (self.evidence / 'candidate.elf').read_bytes())
            self.assertEqual(offset, report['firstDifference']['offset'])
            self.assertEqual(builder.byte_identity(candidate)['sha256'], report['files']['candidate']['sha256'])
            self.assertEqual([str(tools / name) for name in ('clang.exe', 'llvm-strip.exe', 'llvm-readelf.exe')],
                             [argv[0] for argv, _ in self.calls[:3]])
            self.assertTrue(all(argv[0] == str(tools / 'llvm-readelf.exe') for argv, _ in self.calls[3:]))
            self.assertEqual(7, len(self.calls))
            self.assertFalse(Path(self.calls[0][0][-1]).exists())

    def test_windows_tool_failures_keep_original_exception_and_bytes(self):
        tools = self.windows_tools()
        for index, stage in enumerate(('compile', 'strip', 'readelf')):
            with self.subTest(stage=stage), patch.object(builder.platform, 'system', return_value='Windows'):
                self.evidence = self.root / ('app/build/windows-failure-' + stage)
                self.calls.clear()
                failure = subprocess.CalledProcessError(19, ['mock-' + stage])
                def fail(argv, **options):
                    if len(self.calls) == index:
                        self.calls.append((argv, options))
                        raise failure
                    return self.fake_run(argv, **options)
                self.run.side_effect = fail
                with self.assertRaises(subprocess.CalledProcessError) as caught:
                    builder.build(self.ndk, self.output, check=True, evidence_dir=self.evidence)
                self.assertIs(failure, caught.exception)
                report = json.loads((self.evidence / 'report.json').read_text())
                self.assertFalse(report['comparisonPerformed'])
                self.assertEqual(stage, report['failure']['stage'])
                self.assertEqual(19, report['commands'][index]['returncode'])
                self.assertEqual(self.original, self.output.read_bytes())
                self.assertTrue(all(Path(argv[0]).parent == tools and argv[0].endswith('.exe')
                                    for argv, _ in self.calls))
                self.assertFalse(Path(self.calls[0][0][-1]).exists())

    def test_ci_seam_is_in_native_job_and_preserves_failure_dependencies(self):
        workflow = Path(__file__).resolve().parents[1] / '.github/workflows/ci-package.yml'
        text = workflow.read_text()
        self.assertIn('  descriptor-assets:\n', text)
        assets = text.split('  descriptor-assets:\n', 1)[1].split('  rust-repro:\n', 1)[0]
        native = text.split('  native-repro:\n', 1)[1]
        package = text.split('  package:\n', 1)[1].split('  native-repro:\n', 1)[0]
        for command in ('python3 tools/prepare-runtime-descriptor.py --check',
                        'python3 tools/generate-third-party-notices.py --check',
                        'python3 tools/check-third-party-notices.py --archive app/src/main/assets/dsh-runtime.bin'):
            self.assertIn(command, assets)
        self.assertIn('runs-on: ubuntu-24.04', assets)
        self.assertIn('needs: inputs', assets)
        self.assertIn('uses: ./.github/actions/setup-toolchain', assets)
        self.assertIn('uses: ./.github/actions/restore-handoff', assets)
        self.assertIn('artifact: ${{ needs.inputs.outputs.artifact }}', assets)
        self.assertNotIn('android-sdk', assets)
        self.assertNotIn('native-session/build.py', assets)
        self.assertIn('runs-on: windows-2022', native)
        self.assertIn('actions/setup-python@ece7cb06caefa5fff74198d8649806c4678c61a1', native)
        self.assertIn("python-version: '3.12'", native)
        self.assertIn('shell: python -B {0}', native)
        command = 'code = run("build-check", [sys.executable, "-B", "tools/native-session/build.py",\n' \
                  '                         "--ndk", str(ndk), "--check", "--evidence-dir", str(failure_evidence)], 300)'
        self.assertIn(command, native)
        self.assertIn('if: always()', native)
        self.assertIn('uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9', native)
        self.assertIn('path: app/build/native-session-repro-${{ github.run_id }}-${{ github.run_attempt }}', native)
        self.assertIn('if-no-files-found: error', native)
        self.assertIn('successful check creates no builder-failure evidence', native)
        self.assertIn('needs: [inputs, descriptor-assets, native-repro, rust-repro, rust-native]', package)
        self.assertNotRegex(package, r'(?m)^    if:')
        self.assertNotIn('continue-on-error', text)
        self.assertLess(native.index(command), native.index('uses: actions/upload-artifact@'))
        self.assertNotIn('windows-native-producer-probe:', text)
        self.assertNotIn('DIAGNOSTIC_ONLY', native)
        self.assertIn('"scope": "native-session-byte-reproduction"', native)
        self.assertIn('"historicalProducer": "unknown", "packageVerified": False', native)
        self.assertIn('"implicitInputs": "unknown:', native)
        self.assertIn('"toolchainInventoryComplete": False', native)
        for fragment in ('ci/toolchain.lock.json', 'revision = lock["ndk"]', 'sdkmanager.bat',
                         'match[1] != revision', 'prebuilt/windows-x86_64',
                         '"gitHeadBefore"', '"gitHeadAfter"', '"gitBlobSha256"',
                         '"frozenInputsUnchanged"', 'receipt["knownInputGaps"]',
                         'if (not receipt["frozenInputsUnchanged"] or receipt["errors"]) and code == 0:',
                         'if code == 0:\n                      code = 1', 'sys.exit(code)'):
            self.assertIn(fragment, native)
        for forbidden in ('needs:', './.github/actions/', 'build.ps1', '--output', 'continue-on-error'):
            self.assertNotIn(forbidden, native)
        self.assertEqual(1, text.count('code = run("build-check",'))
        self.assertIn('uses: ./.github/actions/android-sdk\n        with:\n          ndk: \'true\'', package)


if __name__ == '__main__':
    unittest.main()
