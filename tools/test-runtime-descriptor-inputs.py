#!/usr/bin/env python3
"""Validate installer/identity inputs and the shared Gradle physical-path consumer."""
import re
import json
import subprocess
import sys
from pathlib import Path
from runtime_input_contract import load, launcher_paths
import importlib.util

ROOT = Path(__file__).resolve().parents[1]
spec = load(ROOT)
checker_spec = importlib.util.spec_from_file_location('descriptor_source_checker',
                                                     ROOT / 'tools/verify-runtime-descriptor-source.py')
checker = importlib.util.module_from_spec(checker_spec)
checker_spec.loader.exec_module(checker)
launchers = launcher_paths(ROOT, spec)
installer = (ROOT / 'app/src/main/java/com/deepseekharness/app/runtime/RuntimeTools.java').read_text(encoding='utf8')
if '"managed-runtime-inputs.json"' not in installer or 'manifest.getJSONArray("installs")' not in installer:
    raise SystemExit('RuntimeTools 未消费受管输入契约')
descriptor = json.loads((ROOT / 'app/src/main/assets/runtime-descriptor.json').read_text(encoding='utf8'))
identified = set(descriptor['inputs'])
reads = set(re.findall(r'(?:assetText|patchClientModule)\(context,\s*(?:rootfs,\s*)?"([^"]+)"', installer))
reads.discard('runtime-descriptor.json')
if reads - identified:
    raise SystemExit('实际补丁资产未进入运行时身份: ' + ','.join(sorted(reads - identified)))
builtin_registry = json.loads((ROOT / 'app/src/main/assets/builtin-plugins.json').read_text(encoding='utf8'))
builtin_names = {row['name'] for row in builtin_registry['plugins'] if not row.get('internal')}
installed_names = {row['asset'].split('/')[1] for row in spec['installs'] if row['asset'].startswith('builtin-plugins/')}
if builtin_names != installed_names:
    raise SystemExit('签名内置插件声明与安装表不同')
for row in spec['installs']:
    if row['asset'].startswith('builtin-plugins/'):
        _, name, suffix = row['asset'].split('/', 2)
        if row['target'] != 'root/dsha-' + name[4:] + '/' + suffix:
            raise SystemExit('内置插件安装目标与既有实体契约不同')
gradle = (ROOT / 'app/build.gradle').read_text(encoding='utf8')
task = gradle.split('def prepareRuntimeDescriptor = tasks.register("prepareRuntimeDescriptor", Exec) {', 1)[1].split('def verifyRuntimeDescriptorInputs', 1)[0]
for required in ['inputs.file(runtimeInputContractFile)', 'runtimeInputContract.assetFiles',
                 'runtimeInputContract.installs', 'runtimeInputContract.assetTrees',
                 'tools/runtime_input_contract.py', 'tools/dsh-runtime/package.json',
                 "inputs.property('runtimeLauncherIdentityPaths', runtimeLauncherFiles)",
                 'inputs.files(runtimeLauncherFiles.values().collect { rootProject.file(it) })',
                 'tools/source_layout.py', 'ci/source-layout.json']:
    if required not in task:
        raise SystemExit('Gradle 未消费完整输入契约: ' + required)
configuration = gradle.split('def runtimeLauncherFiles =', 1)[1].split('def prepareRuntimeDescriptor', 1)[0]
for required in ('providers.exec', 'tools/source_layout.py', "'--launcher-inputs'", '.standardOutput.asText.get()'):
    if required not in configuration:
        raise SystemExit('Gradle 未使用共享物理路径映射: ' + required)
actual = json.loads(subprocess.check_output([
    sys.executable, str(ROOT / 'tools/source_layout.py'), '--root', str(ROOT), '--launcher-inputs'], text=True))
expected = {name: path.relative_to(ROOT).as_posix() for name, path in launchers.items()}
if actual != expected:
    raise SystemExit('Gradle 声明的逻辑身份/物理路径与 Python 身份不一致')
print(f'共享映射集合对等：{len(actual)} 启动输入（含 Java/Kotlin、原生树、生成器）；未缩输入契约')
# 先核映射仍不放过旧 descriptor 字节；worker 的待集成输入变化必须在 main 重算前报红。
source_proof = checker.verify(ROOT)
print(f'单源运行时输入契约通过：{len(spec["installs"])} 安装项、{len(identified)} 资产、{len(launchers)} 启动输入；'
      f'缺失离线归档待完整 package 核验：{source_proof["deferredArchives"]}')
