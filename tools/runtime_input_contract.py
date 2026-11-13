"""The signed manifest is shared by installation, runtime identity and Gradle inputs."""
import json
from pathlib import Path, PurePosixPath

MANIFEST = 'app/src/main/assets/managed-runtime-inputs.json'
ASSETS = 'app/src/main/assets'
JAVA = 'app/src/main/java/com/deepseekharness/app'
BUILTIN_RUNTIME_SUFFIXES = {'.js', '.cjs', '.mjs', '.json', '.yml', '.yaml', '.css', '.wasm',
                            '.html', '.svg', '.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico',
                            '.woff', '.woff2', '.ttf', '.otf'}


def relative(value):
    if not isinstance(value, str) or not value or '\\' in value or ':' in value or '\0' in value \
            or value.startswith('/') or any(p in ('', '.', '..') for p in value.split('/')):
        raise ValueError('INVALID_RUNTIME_INPUT_PATH: ' + repr(value))
    return value


def load(root):
    root = Path(root)
    spec = json.loads((root / MANIFEST).read_text(encoding='utf-8'))
    if spec.get('schema') != 1:
        raise ValueError('RUNTIME_INPUT_SCHEMA')
    targets, assets = set(), set()
    for row in spec['installs']:
        source, target = relative(row['asset']), relative(row['target'])
        if source in assets or target in targets or not isinstance(row['executable'], bool):
            raise ValueError('DUPLICATE_OR_INVALID_RUNTIME_INSTALL')
        assets.add(source)
        targets.add(target)
    for key in ('assetFiles', 'assetTrees', 'launcherTrees', 'launcherSources'):
        values = spec[key]
        if not isinstance(values, list) or len(set(values)) != len(values):
            raise ValueError('RUNTIME_INPUT_LIST: ' + key)
        for value in values:
            relative(value)
    generators=spec.get('generatorSources', [])
    if not isinstance(generators,list) or len(set(generators))!=len(generators):
        raise ValueError('RUNTIME_GENERATOR_SOURCE_LIST')
    for value in generators:relative(value)
    verify_builtin_installs(root, spec)
    return spec


def verify_builtin_installs(root, spec):
    """APK presence and a descriptor digest do not prove delivery to a guest entity.

    Every runtime file in each signed builtin must have its exact installer row.
    TypeScript source, declaration files, source maps and documentation stay outside
    this runtime rule; this never adds user/plugin paths to the managed boundary.
    """
    root = Path(root)
    registry = root / ASSETS / 'builtin-plugins.json'
    if not registry.is_file():
        return  # Small isolated descriptor fixtures need not contain the plugin registry.
    rows = {row['asset']: row for row in spec['installs']}
    for plugin in json.loads(registry.read_text(encoding='utf-8'))['plugins']:
        name, guest = plugin['name'], plugin['guestDirectory']
        tree = 'app-integration' if name == 'dsh-app-integration' else 'builtin-plugins/' + name
        directory = root / ASSETS / tree
        if not directory.is_dir():
            raise ValueError('BUILTIN_RUNTIME_TREE_MISSING:' + name)
        for path in directory.rglob('*'):
            suffix = path.relative_to(directory).as_posix()
            if not path.is_file() or suffix.startswith('src/') or path.suffix not in BUILTIN_RUNTIME_SUFFIXES:
                continue
            asset = tree + '/' + suffix
            expected = guest.lstrip('/') + '/' + suffix
            if asset not in rows:
                raise ValueError('BUILTIN_RUNTIME_FILE_UNINSTALLED:' + asset)
            if rows[asset]['target'] != expected:
                raise ValueError('BUILTIN_RUNTIME_TARGET_MISMATCH:' + asset)


def _file(root, relative_path):
    root = Path(root).resolve()
    path = root / relative_path
    if not path.is_file() or not path.resolve().is_relative_to(root):
        raise ValueError('RUNTIME_INPUT_MISSING_OR_OUTSIDE: ' + relative_path)
    return path


def asset_paths(root, spec=None):
    root = Path(root)
    spec = spec or load(root)
    result = {root / MANIFEST}
    for name in spec['assetFiles'] + [row['asset'] for row in spec['installs']]:
        result.add(_file(root, ASSETS + '/' + name))
    for name in spec['assetTrees']:
        directory = root / ASSETS / name
        if not directory.is_dir() or not directory.resolve().is_relative_to((root / ASSETS).resolve()):
            raise ValueError('RUNTIME_INPUT_TREE: ' + name)
        for path in directory.rglob('*'):
            if path.is_file():
                result.add(_file(root, path.relative_to(root).as_posix()))
    return sorted(result)


def _launcher(key, path, result):
    """同一 key 只能映射一个文件；父/子 tree 重叠或将来多源根映射必须显式处理。"""
    if key in result and result[key] != path:
        raise ValueError('RUNTIME_LAUNCHER_DUPLICATE_KEY: ' + key)
    result[key] = path


def launcher_paths(root, spec=None):
    """受管启动输入：显式源码不限扩展名，launcherTrees 内 Java 源根同时枚举 .java/.kt。

    本函数只承诺 app/src/main/java 源根。若将来新增 main/kotlin 源根，键映射、
    重名处理与 Gradle 输入必须同批扩展；未知源码根按 .so 树处理会静默漏掉身份，
    因此这里直接失败。键是相对 Java 源根的路径（保留扩展名），字典按键排序。
    """
    root = Path(root)
    spec = spec or load(root)
    result = {}
    for name in spec['launcherSources']:
        result[name] = _file(root, JAVA + '/' + name)
    for name in spec['launcherTrees']:
        directory = root / name
        if not directory.is_dir():
            raise ValueError('RUNTIME_LAUNCHER_TREE: ' + name)
        if name == JAVA or name.startswith(JAVA + '/'):
            for path in sorted(directory.rglob('*')):
                if path.suffix not in ('.java', '.kt'):
                    continue
                key = path.relative_to(root / JAVA).as_posix()
                _launcher(key, _file(root, path.relative_to(root).as_posix()), result)
        else:
            if '/java/' in name or '/kotlin/' in name:
                raise ValueError('RUNTIME_LAUNCHER_SOURCE_ROOT_UNSUPPORTED: ' + name)
            for path in sorted(directory.rglob('*.so')):
                key = path.relative_to(root).as_posix()
                _launcher(key, _file(root, path.relative_to(root).as_posix()), result)
    for name in spec.get('generatorSources', []):
        result['generator:'+name]=_file(root,name)
    return dict(sorted(result.items()))
