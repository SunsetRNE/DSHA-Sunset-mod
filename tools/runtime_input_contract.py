"""The signed manifest is shared by installation, runtime identity and Gradle inputs."""
import json
from pathlib import Path, PurePosixPath
from source_layout import SourceLayout, PACKAGE

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


def launcher_source_paths(root, spec=None):
    """旧 launcher 键保持；Java/Kotlin 与模块物理迁移仅经共享布局解析。"""
    root = Path(root)
    spec = spec or load(root)
    layout = SourceLayout(root)
    result = {}
    for name in spec['launcherSources']:
        _launcher(name, layout.resolve(PACKAGE + '/' + name), result)
    for name in spec['launcherTrees']:
        # 签名契约中的旧路径现在是逻辑树名，不能按其物理目录是否存在来缩扫描范围。
        logical = None
        for source_root in ('app/src/main/java/', 'app/src/main/kotlin/'):
            if name == source_root.rstrip('/') or name.startswith(source_root):
                logical = name[len(source_root):] if name.startswith(source_root) else ''
                break
        if logical is None:
            if '/java/' in name or '/kotlin/' in name:
                raise ValueError('RUNTIME_LAUNCHER_SOURCE_ROOT_UNSUPPORTED: ' + name)
            continue
        if logical != PACKAGE and not logical.startswith(PACKAGE + '/'):
            raise ValueError('RUNTIME_LAUNCHER_SOURCE_ROOT_UNSUPPORTED: ' + name)
        prefix = logical + '/'
        entries = [entry for entry in layout.source_files(('main',), ('.java', '.kt'))
                   if entry.logical_path.startswith(prefix)]
        if not entries and not any((layout.root / module['directory'] / 'src/main' / language / logical).is_dir()
                                   for module in layout.modules for language in ('java', 'kotlin')):
            raise ValueError('RUNTIME_LAUNCHER_TREE: ' + name)
        for entry in entries:
            key = entry.logical_path[len(PACKAGE) + 1:]
            _launcher(key, entry.path, result)
    return dict(sorted(result.items()))


def launcher_paths(root, spec=None):
    """受管启动输入；所有消费者共用逻辑身份到物理路径映射，键保留扩展名。"""
    root = Path(root)
    spec = spec or load(root)
    result = launcher_source_paths(root, spec)
    for name in spec['launcherTrees']:
        if '/java/' in name or '/kotlin/' in name:
            continue
        directory = root / name
        if not directory.is_dir():
            raise ValueError('RUNTIME_LAUNCHER_TREE: ' + name)
        for path in sorted(directory.rglob('*.so')):
            key = path.relative_to(root).as_posix()
            _launcher(key, _file(root, path.relative_to(root).as_posix()), result)
    for name in spec.get('generatorSources', []):
        result['generator:'+name] = _file(root, name)
    return dict(sorted(result.items()))
