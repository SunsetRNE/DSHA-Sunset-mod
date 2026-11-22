#!/usr/bin/env python3
"""实跑 Rust prebuilt Gradle 配置/任务；不编译 Rust，不组装/签名/安装 APK。

CI 必须传同 run 的真实 --artifact-dir；本机可显式 --synthetic-fixture，仅证明
真实 Gradle 接线与拒绝路径，不构成 Android ELF 构建、加载或设备成功证据。
所有 Gradle 调用串行；本机只允许 dsha-gradle。独立 build 输出不会污染正式 off。
"""
from __future__ import annotations

import argparse
import copy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
import artifact  # noqa: E402


INIT_SCRIPT = r'''
gradle.beforeProject { project ->
    if (project.path == ':app') {
        project.layout.buildDirectory.set(new File(System.getenv('DSHA_RUST_GRADLE_CHECK_BUILD')))
    }
}
gradle.projectsEvaluated {
    def project = gradle.rootProject.project(':app')
    project.tasks.register('rustPrebuiltConsumerProbe') {
        dependsOn(project.tasks.named('prepareRustNative'))
        doLast {
            def jni = project.layout.buildDirectory.dir('rust-jniLibs').get().asFile.canonicalFile
            def assets = project.layout.buildDirectory.dir('generated/rustAssets').get().asFile.canonicalFile
            if (!project.android.sourceSets.main.jniLibs.srcDirs.collect { it.canonicalFile }.contains(jni))
                throw new GradleException('RUST_GRADLE_JNI_CONSUMER_PATH')
            if (!project.android.sourceSets.main.assets.srcDirs.collect { it.canonicalFile }.contains(assets))
                throw new GradleException('RUST_GRADLE_ASSET_CONSUMER_PATH')
            ['preBuild', 'mergeStandardDebugJniLibFolders', 'mergeStandardDebugNativeLibs',
                    'mergeStandardDebugAssets'].each { name ->
                def consumer = project.tasks.named(name).get()
                if (!consumer.taskDependencies.getDependencies(consumer).collect { it.path }
                        .contains(':app:prepareRustNative'))
                    throw new GradleException('RUST_GRADLE_TASK_DEPENDENCY: ' + name)
            }
            println('RUST_GRADLE_CONSUMER_PATHS_PASS')
        }
    }
}
'''


class CheckError(RuntimeError):
    pass


def synthetic_pair(directory: Path) -> None:
    """显式合成解析夹具；禁止 CI 使用，绝不作为可安装 Android 产物。"""
    if artifact.in_ci():
        raise CheckError('RUST_GRADLE_SYNTHETIC_FORBIDDEN_IN_CI')
    from test_apk_injection import synthetic_elf
    import build as rust_build
    lock = artifact.load_json(ROOT / 'ci/rust-toolchain.lock.json')
    data = synthetic_elf(sorted(lock['abi']['symbols']))
    info = artifact.describe_bytes(data, target='android', lock=lock, budget=artifact.load_budget(ROOT))
    receipt = rust_build.receipt(
        found=dict(rustc=lock['rustc']['version'], rustcCommit=lock['rustc']['commit'].ljust(40, '0'),
                   cargo=lock['cargo']['version'], host='x86_64-unknown-linux-gnu'),
        lock=lock, target='android', ndk=None, artifact={**info, 'reproducible': 'NOT_RUN'},
        reproducible=dict(status='NOT_RUN', differentDirectory=False, firstSha256=None, secondSha256=None),
        jobs=2, expectation={'status': 'NOT_RUN'})
    directory.mkdir()
    (directory / artifact.LIB_NAME).write_bytes(data)
    (directory / artifact.RECEIPT_NAME).write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')


def checked_report_output(output: Path, supplied: Path | None) -> Path:
    """任何 mkdir/mkdtemp/报告写入之前，拒绝报告和工作目录覆盖输入。"""
    output = artifact.checked_path(output.absolute())
    if supplied is not None:
        supplied = artifact.checked_path(supplied.absolute())
        if output == supplied or supplied in output.parents or output in supplied.parents:
            raise CheckError('RUST_GRADLE_REPORT_INPUT_OVERLAP')
    return output


class GradleCheck:
    def __init__(self, executable: str, output: Path, fixture: bool):
        self.output = artifact.checked_path(output.absolute())
        self.output.parent.mkdir(parents=True, exist_ok=True)
        self.work = Path(tempfile.mkdtemp(prefix='rust-gradle-check-', dir=self.output.parent))
        self.init = self.work / 'probe.init.gradle'
        self.init.write_text(INIT_SCRIPT, encoding='utf-8')
        self.executable = executable
        self.environment = dict(os.environ)
        self.fixture = fixture
        self.report = dict(schema=1, kind='dsha-rust-gradle-integration', status='RUNNING',
                           commit=artifact.current_commit(ROOT),
                           generatedAt=datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
                           artifactSource='SYNTHETIC_ELF_REAL_GRADLE' if fixture else 'SUPPLIED_ANDROID_ARTIFACT',
                           androidLoadTested=False, apkAssembled=False, cases=[])
        self.write_report()

    def write_report(self):
        artifact.write_regular(self.output, (json.dumps(self.report, ensure_ascii=False, indent=2)
                                             + '\n').encode('utf-8'))

    def run(self, name: str, *, mode: str | None = 'prebuilt', directory: Path | None = None,
            task: str = ':app:prepareRustNative', failure: str | None = None,
            marker: str | None = None, build: str | None = None):
        build_dir = self.work / (build or name)
        environment = dict(self.environment, DSHA_RUST_GRADLE_CHECK_BUILD=str(build_dir))
        command = [self.executable, '--console=plain', '--no-configuration-cache',
                   '--max-workers=2', '-I', str(self.init), task]
        if artifact.in_ci():
            command.append('--no-daemon')
        if mode is not None:
            command.append('-Pdsha.rust.mode=' + mode)
        if directory is not None:
            command.append('-Pdsha.rust.artifactDir=' + str(directory))
        log_path = self.work / (name + '.log')
        start = time.monotonic()
        with log_path.open('wb') as log:
            result = subprocess.run(command, cwd=ROOT, env=environment, stdout=log,
                                    stderr=subprocess.STDOUT, check=False, timeout=360)
        log = log_path.read_text(encoding='utf-8', errors='replace')
        accepted = ((result.returncode == 0 and 'BUILD SUCCESSFUL' in log) if failure is None
                    else result.returncode != 0 and re.search(failure, log) is not None)
        accepted = accepted and 'You cannot add Provider instances' not in log
        if marker is not None:
            accepted = accepted and marker in log
        if task == ':app:rustPrebuiltConsumerProbe' and failure is None:
            accepted = accepted and '> Task :app:prepareRustNative\n' in log
        self.report['cases'].append(dict(name=name, status='PASS' if accepted else 'FAIL',
                                         command=command, exitCode=result.returncode,
                                         expected='SUCCESS' if failure is None else failure,
                                         seconds=round(time.monotonic() - start, 3),
                                         log=str(log_path), logSha256=artifact.digest(log_path.read_bytes())))
        self.write_report()
        print('%s %s exit=%d' % ('PASS' if accepted else 'FAIL', name, result.returncode), flush=True)
        if not accepted:
            raise CheckError('RUST_GRADLE_CASE_FAILED: %s; log=%s' % (name, log_path))
        return build_dir

    @staticmethod
    def staged(build: Path):
        return (build / 'rust-jniLibs/arm64-v8a/libdsha_rs.so',
                build / 'generated/rustAssets/rust-native-receipt.json')

    def assert_staged(self, build: Path, library: bytes, receipt: bytes):
        staged = self.staged(build)
        if any(not path.is_file() for path in staged) or [p.read_bytes() for p in staged] != [library, receipt]:
            raise CheckError('RUST_GRADLE_STAGED_BYTES_CHANGED')

    def execute(self, supplied: Path | None):
        if self.fixture:
            supplied = self.work / 'synthetic-artifact'
            synthetic_pair(supplied)
        supplied = artifact.checked_path(supplied.absolute())
        library, receipt_bytes = artifact.artifact_files(supplied)
        receipt = artifact.parse_json(receipt_bytes)
        # CI 正控必须保留真实 builder 在不同源码目录的字节复现证据；不重新签发来源。
        if not self.fixture and (receipt['reproducible']['status'] != 'PASS'
                                 or receipt['reproducible']['differentDirectory'] is not True):
            raise CheckError('RUST_GRADLE_REPRODUCED_ARTIFACT_REQUIRED')
        self.report['artifact'] = dict(sha256=artifact.digest(library), size=len(library),
                                       receiptSha256=artifact.digest(receipt_bytes),
                                       provenance=receipt['provenance'], runId=receipt['runId'],
                                       runAttempt=receipt['runAttempt'], repository=receipt['repository'])
        self.write_report()
        empty = self.run('default-off', mode=None, marker='PASS_NO_RUST_ARTIFACT')
        if any(path.exists() for path in self.staged(empty)):
            raise CheckError('RUST_GRADLE_OFF_CREATED_ARTIFACT')
        self.run('explicit-off', mode='off', marker='PASS_NO_RUST_ARTIFACT')
        self.run('invalid-mode', mode='auto', task='help', failure='RUST_NATIVE_MODE:')
        self.run('missing-property', task='help', failure='RUST_ARTIFACT_DIRECTORY:')
        self.run('off-with-artifact', mode='off', directory=supplied, task='help', failure='RUST_DISABLED_INPUT:')
        self.run('missing-directory', directory=self.work / 'absent',
                 failure="doesn't exist|RUST_ARTIFACT_DIR_MISSING", marker=':app:prepareRustNative')
        configured = self.run('prebuilt-configure', directory=supplied, task='help')
        if any(path.exists() for path in self.staged(configured)):
            raise CheckError('RUST_GRADLE_CONFIGURATION_WROTE_ARTIFACT')
        build = self.run('prebuilt-stage', directory=supplied, task=':app:rustPrebuiltConsumerProbe',
                         marker='RUST_GRADLE_CONSUMER_PATHS_PASS', build='accepted-stage')
        self.assert_staged(build, library, receipt_bytes)
        self.run('prebuilt-recheck', directory=supplied, task=':app:rustPrebuiltConsumerProbe',
                 marker='RUST_GRADLE_CONSUMER_PATHS_PASS', build='accepted-stage')
        self.assert_staged(build, library, receipt_bytes)
        mutations = (
            ('bad-library', None, 'RUST_RECEIPT_ARTIFACT:'),
            ('wrong-commit', ('commit', '0' * 40), 'RUST_RECEIPT_IDENTITY: commit'),
            ('wrong-attempt', ('runAttempt', '999999' if receipt['runAttempt'] != '999999' else '999998'),
             'RUST_RECEIPT_IDENTITY: runAttempt'),
            ('wrong-run', ('runId', '999999' if receipt['runId'] != '999999' else '999998'),
             'RUST_RECEIPT_IDENTITY: runId'),
        )
        for name, change, failure in mutations:
            mutant = self.work / name
            mutant.mkdir()
            altered = copy.deepcopy(receipt)
            data = library
            if change is None:
                data = library[:-1] + bytes([library[-1] ^ 1])
            else:
                altered[change[0]] = change[1]
            (mutant / artifact.LIB_NAME).write_bytes(data)
            (mutant / artifact.RECEIPT_NAME).write_text(json.dumps(altered, indent=2) + '\n', encoding='utf-8')
            self.run(name, directory=mutant, failure=failure, marker=':app:prepareRustNative FAILED',
                     build='accepted-stage')
            # 失败不清掉或重写上次通过的字节；原始输入也保持原样。
            self.assert_staged(build, library, receipt_bytes)
        self.run('off-rejects-residue', mode='off', failure='RUST_OFF_RESIDUE:', build='accepted-stage')
        self.assert_staged(build, library, receipt_bytes)
        self.run('prebuilt-after-rejections', directory=supplied, task=':app:rustPrebuiltConsumerProbe',
                 marker='RUST_GRADLE_CONSUMER_PATHS_PASS', build='accepted-stage')
        self.assert_staged(build, library, receipt_bytes)
        if artifact.artifact_files(supplied) != (library, receipt_bytes):
            raise CheckError('RUST_GRADLE_INPUT_ARTIFACT_CHANGED')
        self.report['status'] = 'PASS_GRADLE_WITH_SYNTHETIC_ELF' if self.fixture else 'PASS_GRADLE_WITH_ANDROID_ARTIFACT'
        self.report['casesPassed'] = len(self.report['cases'])
        self.write_report()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    choice = parser.add_mutually_exclusive_group(required=True)
    choice.add_argument('--artifact-dir', type=Path)
    choice.add_argument('--synthetic-fixture', action='store_true')
    parser.add_argument('--gradle', default='dsha-gradle')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output = checked_report_output(args.output, args.artifact_dir)
    if not artifact.in_ci() and args.gradle != 'dsha-gradle':
        raise CheckError('RUST_GRADLE_LOCAL_WRAPPER_REQUIRED: 本机只允许 dsha-gradle')
    if not shutil.which(args.gradle):
        raise CheckError('RUST_GRADLE_EXECUTABLE_MISSING: ' + args.gradle)
    check = GradleCheck(args.gradle, args.output, args.synthetic_fixture)
    try:
        check.execute(args.artifact_dir)
    except (CheckError, artifact.GateError, OSError, subprocess.TimeoutExpired) as error:
        check.report['status'] = 'FAIL'
        check.report['failure'] = str(error)
        check.write_report()
        raise
    print(json.dumps(check.report, ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (CheckError, artifact.GateError, OSError, subprocess.TimeoutExpired) as error:
        print('FAIL ' + str(error), file=sys.stderr)
        raise SystemExit(1)
