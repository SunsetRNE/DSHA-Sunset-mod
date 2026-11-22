#!/usr/bin/env python3
"""Structural guard for native-dependency ownership/order, not ELF loading or Android execution."""
from pathlib import Path
import re
import sys
from verification_require import require

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/deepseekharness/app/runtime'


def method_body(source, signature):
    index = source.find(signature)
    require(index >= 0, 'NATIVE_DEPENDENCY_METHOD_MISSING:' + signature)
    start = source.index('{', index)
    depth = 0
    for end in range(start, len(source)):
        depth += (source[end] == '{') - (source[end] == '}')
        if depth == 0:
            return source[start:end + 1]
    raise ValueError('NATIVE_DEPENDENCY_METHOD_UNCLOSED:' + signature)


def native_preparation_body(bootstrap, kotlin=False):
    """native 准备只允许有一个实现；无参入口必须委派到带观测的 Consumer 重载。

    S0 把准备主体移进 Consumer 重载后，仅扫描「哪个方法体含 nativeFiles.prepare()」会
    放过无参入口被改成空实现的情况；而 StartupPipeline、DiagnosticRepository、
    PluginJournalRecovery 与 ColdBundleExtractor 仍走无参入口，native 库会静默不准备。

    T5 把 ProotBootstrap 迁移到 Kotlin 后，按 Kotlin 签名/无分号语句解析同一契约。
    """
    if kotlin:
        noarg = method_body(bootstrap, 'fun ensureRuntimeFiles()')
        observed = method_body(bootstrap, 'fun ensureRuntimeFiles(output: Consumer<String>?)')
        prepare = 'nativeFiles.prepare()'
        patches = 'ensureDshRuntimePatches(output)'
    else:
        noarg = method_body(bootstrap, 'public void ensureRuntimeFiles()')
        observed = method_body(bootstrap, 'public void ensureRuntimeFiles(java.util.function.Consumer<String> output)')
        prepare = 'nativeFiles.prepare();'
        patches = 'ensureDshRuntimePatches(output)'
    require(prepare in observed, 'BOOTSTRAP_NATIVE_OWNER_NOT_CALLED')
    require(prepare not in noarg, 'BOOTSTRAP_NATIVE_OWNER_AMBIGUOUS')
    require(re.search(r'ensureRuntimeFiles\s*\(\s*null\s*\)', noarg) is not None,
            'BOOTSTRAP_NATIVE_DELEGATION_MISSING')
    require(observed.index(prepare) < observed.index(patches),
            'BOOTSTRAP_NATIVE_PREPARATION_ORDER')
    return observed


def self_check_native_entry():
    """负控：空转发、双实现、无参即实现、顺序反转都必须被拒绝。"""
    good = ('class ProotBootstrap {\n'
            '  public void ensureRuntimeFiles() {\n    ensureRuntimeFiles(null);\n  }\n'
            '  public void ensureRuntimeFiles(java.util.function.Consumer<String> output) {\n'
            '    nativeFiles.prepare();\n    ensureDshRuntimePatches(output);\n  }\n}\n')
    noarg_impl = ('class ProotBootstrap {\n'
                  '  public void ensureRuntimeFiles() {\n    nativeFiles.prepare();\n'
                  '    ensureDshRuntimePatches();\n  }\n'
                  '  public void ensureRuntimeFiles(java.util.function.Consumer<String> output) {\n'
                  '    ensureRuntimeFiles();\n  }\n}\n')
    empty_delegate = ('class ProotBootstrap {\n'
                      '  public void ensureRuntimeFiles() {\n  }\n'
                      '  public void ensureRuntimeFiles(java.util.function.Consumer<String> output) {\n'
                      '    nativeFiles.prepare();\n    ensureDshRuntimePatches(output);\n  }\n}\n')
    double_impl = ('class ProotBootstrap {\n'
                   '  public void ensureRuntimeFiles() {\n    nativeFiles.prepare();\n  }\n'
                   '  public void ensureRuntimeFiles(java.util.function.Consumer<String> output) {\n'
                   '    nativeFiles.prepare();\n    ensureDshRuntimePatches(output);\n  }\n}\n')
    reversed_order = ('class ProotBootstrap {\n'
                      '  public void ensureRuntimeFiles() {\n    ensureRuntimeFiles(null);\n  }\n'
                      '  public void ensureRuntimeFiles(java.util.function.Consumer<String> output) {\n'
                      '    ensureDshRuntimePatches(output);\n    nativeFiles.prepare();\n  }\n}\n')
    native_preparation_body(good)
    for label, body in (('empty-delegate', empty_delegate), ('double-implementation', double_impl),
                        ('noarg-implementation', noarg_impl), ('reversed-order', reversed_order)):
        try:
            native_preparation_body(body)
        except RuntimeError:
            continue
        raise SystemExit('SELF_TEST_NATIVE_ENTRY_ACCEPTED: ' + label)


def validate(bootstrap, native, recovery):
    kotlin = 'fun ensureRuntimeFiles' in bootstrap
    ensure = native_preparation_body(bootstrap, kotlin)
    preparation = method_body(native, 'void prepare()')
    require(not re.search(r'runtime\(|getContainerRuntime|"proot"|"proroot"', preparation),
            'NATIVE_PREPARATION_GATED_BY_USER_RUNTIME')
    for source, target in [('libtalloc.so', 'libtalloc.so.2'),
                           ('libandroidshmem.so', 'libandroid-shmem.so')]:
        copy = r'copy\(find\("' + re.escape(source) + r'"\),\s*new File\(lib,\s*"' + re.escape(target) + r'"\)\);'
        require(len(re.findall(copy, preparation)) == 1, 'NATIVE_DEPENDENCY_COPY_MISSING:' + target)
    if kotlin:
        # Kotlin 迁移后用表达式体实现；直接匹配签名到 = recovery.run(...) 的委托。
        for signature in ('fun runRecoveryMaintenance(', 'fun runPersonalMaintenance('):
            pattern = re.escape(signature) + r'[\s\S]{0,500}?= recovery\.run\(command, onLine, timeoutMs\)'
            require(re.search(pattern, bootstrap) is not None,
                    'RECOVERY_TOOLCHAIN_ENTRY_NOT_DELEGATED:' + signature)
    else:
        for signature in ('public BoundedProcessRunner.Result runRecoveryMaintenance(',
                          'public BoundedProcessRunner.Result runPersonalMaintenance('):
            caller = method_body(bootstrap, signature)
            require('return recovery.run(command, onLine, timeoutMs);' in caller,
                    'RECOVERY_TOOLCHAIN_ENTRY_NOT_DELEGATED:' + signature)
    launch = method_body(recovery, 'BoundedProcessRunner.Result run(')
    require('runtime().id()' not in launch and 'getContainerRuntime' not in launch,
            'RECOVERY_LAUNCH_GATED_BY_USER_RUNTIME')
    anchors = ['nativeFiles.prepare();', 'new ContainerRuntime.Proot(', 'builder.start()']
    require(all(launch.count(anchor) == 1 for anchor in anchors), 'RECOVERY_LAUNCH_ANCHOR_COUNT')
    require([launch.index(anchor) for anchor in anchors] == sorted(launch.index(anchor) for anchor in anchors),
            'RECOVERY_NATIVE_PREPARATION_ORDER')


def main():
    if sys.flags.optimize:
        raise SystemExit('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    self_check_native_entry()
    validate(*(path.read_text(encoding='utf-8') for path in (
        JAVA / 'ProotBootstrap.kt', JAVA / 'RuntimeNativeFiles.java', JAVA / 'RecoveryToolchain.java')))
    print('PASS structure: native owner copies both proot libraries; recovery prepares them before Proot and exec')


if __name__ == '__main__':
    main()
