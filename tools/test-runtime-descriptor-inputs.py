#!/usr/bin/env python3
"""Validate the shared installer/identity manifest and Gradle consumer contract."""
import ast
import fnmatch
import re
import json
from pathlib import Path
from runtime_input_contract import JAVA, load, asset_paths, launcher_paths
import importlib.util

ROOT = Path(__file__).resolve().parents[1]
spec = load(ROOT)
checker_spec = importlib.util.spec_from_file_location('descriptor_source_checker',
                                                     ROOT / 'tools/verify-runtime-descriptor-source.py')
checker = importlib.util.module_from_spec(checker_spec)
checker_spec.loader.exec_module(checker)
source_proof = checker.verify(ROOT)
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
for required in ['inputs.file(runtimeInputContractFile)', 'runtimeInputContract.assetFiles', 'runtimeInputContract.installs', 'runtimeInputContract.assetTrees', 'runtimeInputContract.launcherTrees', 'runtimeInputContract.launcherSources', 'runtimeInputContract.generatorSources', 'tools/runtime_input_contract.py', 'tools/dsh-runtime/package.json']:
    if required not in task:
        raise SystemExit('Gradle 未消费完整输入契约: ' + required)


def gradle_source_contract(task_text):
    """解析 launcherTrees 的 Gradle include：源根收 .java/.kt，其它树收 .so。

    不再按某个固定字面量断言整个列表；这里解析三元 include 的实际后缀集合与非源根
    后缀，并核对源根判定条件仍以工具链同一个 Java 源根为界。
    """
    include = re.search(
        r"include\s+(?P<cond>\w+)\s*\?\s*(?P<source>\[[^\]]*\])\s*:\s*(?P<native>'[^']*'|\"[^\"]*\")",
        task_text)
    if include is None:
        raise SystemExit('Gradle 未按源根分别声明 java/kt 与 .so 输入')
    source_suffixes = ast.literal_eval(include.group('source'))
    if not isinstance(source_suffixes, list) or set(source_suffixes) != {'**/*.java', '**/*.kt'}:
        raise SystemExit('Gradle 源根输入未同时收录 **/*.java 与 **/*.kt: ' + repr(source_suffixes))
    native_suffix = ast.literal_eval(include.group('native'))
    if native_suffix != '**/*.so':
        raise SystemExit('Gradle 非源根输入必须只收 **/*.so: ' + repr(native_suffix))
    condition = include.group('cond')
    definition = re.search(
        r"def\s+" + re.escape(condition) + r"\s*=\s*(?P<expr>(?:[^\n]*\\\n)*[^\n]*)", task_text)
    expression = re.sub(r"\s+", "", definition.group('expr') if definition else task_text)
    expression = expression.replace('"', "'")
    for predicate in ("=='" + JAVA + "'", ".startsWith('" + JAVA + "/')"):
        if predicate not in expression:
            raise SystemExit('Gradle 源根判定必须与 tools/runtime_input_contract.py 使用同一 Java 源根: '
                             + predicate)
    return source_suffixes, native_suffix


def gradle_match(pattern, relative):
    """Ant/Gradle 风格 include：`**/` 前缀可匹配零层或多层目录（fnmatch 的 * 跨 /）。"""
    if pattern.startswith('**/'):
        return fnmatch.fnmatchcase(relative, pattern[3:])
    return fnmatch.fnmatchcase(relative, pattern)


def gradle_tree_inputs(source_suffixes, native_suffix):
    """按 app/build.gradle 的 include 规则重放 launcherTrees 的 Gradle 输入文件集合。"""
    selected = set()
    for tree in spec['launcherTrees']:
        directory = ROOT / tree
        if not directory.is_dir():
            raise SystemExit('Gradle launcherTree 目录不存在: ' + tree)
        is_source = tree == JAVA or tree.startswith(JAVA + '/')
        patterns = source_suffixes if is_source else [native_suffix]
        for path in directory.rglob('*'):
            if path.is_file() and any(
                    gradle_match(pattern, path.relative_to(directory).as_posix())
                    for pattern in patterns):
                selected.add(path.resolve())
    return selected


source_suffixes, native_suffix = gradle_source_contract(task)
tree_roots = [(ROOT / tree).resolve() for tree in spec['launcherTrees']]
identity_files = {path for path in launchers.values() if any(path.is_relative_to(root) for root in tree_roots)}
gradle_files = gradle_tree_inputs(source_suffixes, native_suffix)
if identity_files != gradle_files:
    missing = sorted(path.relative_to(ROOT.resolve()).as_posix() for path in identity_files - gradle_files)
    extra = sorted(path.relative_to(ROOT.resolve()).as_posix() for path in gradle_files - identity_files)
    raise SystemExit('Gradle 声明的 launcher 输入与运行时身份不一致: missing='
                     + repr(missing) + ' extra=' + repr(extra))
print(f'单源运行时输入契约通过：{len(spec["installs"])} 安装项、{len(identified)} 资产、{len(launchers)} 启动输入'
      f'（Gradle launcherTree 输入与身份一致：{len(gradle_files)} 文件）；'
      f'缺失离线归档待完整 package 核验：{source_proof["deferredArchives"]}')
