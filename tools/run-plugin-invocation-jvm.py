#!/usr/bin/env python3
"""S3a 独立纯 JVM 验证：编译真实 Kotlin 源与测试；不代替 Gradle/Android 验收。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--gradle-cache', type=Path, required=True)
    args = parser.parse_args()
    java = (args.jdk / 'bin/java').resolve(strict=True)
    cache = args.gradle_cache.resolve(strict=True)

    def jar(group, name, version):
        selected = list((cache / group / name / version).glob(f'*/{name}-{version}.jar'))
        if len(selected) != 1:
            raise ValueError(f'JVM_FIXTURE_DEPENDENCY:{group}:{name}:{version}')
        return selected[0]

    compiler = jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.10')
    stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.10')
    script = jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.10')
    reflect = jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10')
    coroutines = jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0')
    annotations = jar('org.jetbrains', 'annotations', '13.0')
    junit = jar('junit', 'junit', '4.13.2')
    hamcrest = jar('org.hamcrest', 'hamcrest-core', '1.3')
    production = ROOT / 'app/src/main/java/com/deepseekharness/app/plugins'
    tests = ROOT / 'app/src/test/java/com/deepseekharness/app/plugins'
    sources = [production / (name + '.kt') for name in
               ('PluginInvocationContext', 'PluginPreparationProtocol', 'PluginGuestLockAdapter')]
    sources += [tests / 'PluginPreparationProtocolTest.kt', tests / 'PluginLockFixture.kt']
    directory = ROOT / 'app/build/plugin-lock-interop'
    directory.mkdir(parents=True, exist_ok=True)
    classes = Path(tempfile.mkdtemp(prefix='classes-', dir=directory))
    # 将已存在的依赖复制到短临时路径并核对字节；不改共享缓存或权限。
    # 深工作树的冗长 classpath 在本机漏读已有类，保留原失败日志，不将其计作编译通过。
    dependencies = Path(tempfile.mkdtemp(prefix='s3a-jvm-deps-'))
    def local(path):
        target = dependencies / path.name
        data = path.read_bytes()
        target.write_bytes(data)
        if hashlib.sha256(target.read_bytes()).digest() != hashlib.sha256(data).digest():
            raise ValueError('JVM_FIXTURE_DEPENDENCY_COPY')
        return target
    runtime_jars = [local(path) for path in (stdlib, junit, hamcrest)]
    compiler_jars = [local(path) for path in (coroutines, annotations, compiler, stdlib, script, reflect)]
    compiler_cp = ':'.join(map(str, compiler_jars))
    runtime_cp = ':'.join(map(str, runtime_jars))
    subprocess.run([str(java), '-Xmx512m', '-cp', compiler_cp,
                    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-jvm-target', '17',
                    '-no-stdlib', '-no-reflect', '-classpath', runtime_cp,
                    '-d', str(classes)] + list(map(str, sources)), check=True, timeout=120)
    classpath = str(classes) + ':' + runtime_cp
    result = subprocess.run([str(java), '-Xmx256m', '-cp', classpath, 'org.junit.runner.JUnitCore',
                             'com.deepseekharness.app.plugins.PluginPreparationProtocolTest'],
                            check=True, timeout=60, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print(result.stdout, end='')
    proof = {'scope': 'S3a-real-source-pure-JVM-not-Android', 'java': str(java),
             'classpath': classpath, 'junitOutput': result.stdout,
             'sources': {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
             'jars': {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in set(compiler_jars + runtime_jars)}}
    (directory / 'jvm-proof.json').write_text(json.dumps(proof, indent=2) + '\n')
    print('S3A_JVM_PROOF', directory / 'jvm-proof.json')


if __name__ == '__main__':
    main()
