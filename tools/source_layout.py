"""源码逻辑身份与物理布局的唯一映射；身份不包含 Gradle 模块或 java/kotlin 源根。"""
import argparse
from dataclasses import dataclass
import json
from pathlib import Path, PurePosixPath
import re

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = "ci/source-layout.json"
PACKAGE = "com/deepseekharness/app"
SOURCE_ROOTS = ("java", "kotlin")
SOURCE_SUFFIXES = (".java", ".kt", ".kts")
KINDS = ("android-application", "android-library", "jvm")


def relative(value):
    if not isinstance(value, str) or not value or any(c in value for c in "\\:\0\r\n") \
            or value.startswith("/") or any(p in ("", ".", "..") for p in value.split("/")):
        raise ValueError("SOURCE_LAYOUT_PATH: " + repr(value))
    return value


@dataclass(frozen=True)
class SourceFile:
    module: str
    source_set: str
    path: Path
    relative: str
    logical_path: str
    language: str

    @property
    def identity(self):
        return self.source_set + ":" + self.logical_path

    @property
    def legacy_relative(self):
        # 既有边界白名单仍按旧逻辑路径比较，搬文件不能抹掉一条受限依赖。
        return "app/src/" + self.source_set + "/java/" + self.logical_path


class SourceLayout:
    def __init__(self, root=ROOT, spec=None):
        self.root = Path(root).resolve()
        self.spec = spec if spec is not None else json.loads(
            (self.root / MANIFEST).read_text(encoding="utf-8"))
        if self.spec.get("schema") != 1 or not isinstance(self.spec.get("modules"), list) \
                or not self.spec["modules"]:
            raise ValueError("SOURCE_LAYOUT_SCHEMA")
        self.modules = self.spec["modules"]
        projects, directories = set(), set()
        for module in self.modules:
            name = module.get("path")
            if not isinstance(name, str) or not re.fullmatch(r":[A-Za-z][\w-]*(?::[A-Za-z][\w-]*)*", name) \
                    or name in projects or module.get("kind") not in KINDS:
                raise ValueError("SOURCE_LAYOUT_MODULE: " + repr(name))
            directory = relative(module.get("directory"))
            if any(directory == d or directory.startswith(d + "/") or d.startswith(directory + "/")
                   for d in directories):
                raise ValueError("SOURCE_LAYOUT_OVERLAPPING_MODULE: " + directory)
            self._bounded(self.root / directory)
            projects.add(name)
            directories.add(directory)
            for field in ("sourceSets", "formatSourceSets", "testSourceSets"):
                values = module.get(field)
                if not isinstance(values, list) or not values or len(values) != len(set(values)) \
                        or any(not isinstance(v, str) or not re.fullmatch(r"[A-Za-z][\w]*", v) for v in values):
                    raise ValueError("SOURCE_LAYOUT_SOURCE_SETS: " + field)
            if not set(module["formatSourceSets"] + module["testSourceSets"]) <= set(module["sourceSets"]):
                raise ValueError("SOURCE_LAYOUT_UNKNOWN_SOURCE_SET")
            if not re.fullmatch(r"[A-Za-z][\w]*", module.get("testTask", "")):
                raise ValueError("SOURCE_LAYOUT_TEST_TASK")

    def _bounded(self, path):
        if not path.resolve().is_relative_to(self.root):
            raise ValueError("SOURCE_LAYOUT_OUTSIDE: " + str(path))
        for parent in (path, *path.parents):
            if parent == self.root:
                break
            if parent.is_symlink():
                raise ValueError("SOURCE_LAYOUT_LINK: " + str(parent))
        return path

    def bounded_files(self, directory):
        """遍历前拒绝每个条目的链接/越界；后缀与目录名都不能成为漏检条件。"""
        directory = self._bounded(directory)
        if not directory.exists():
            return
        if not directory.is_dir():
            raise ValueError('SOURCE_LAYOUT_NOT_DIRECTORY: ' + str(directory))
        for entry in sorted(directory.iterdir()):
            self._bounded(entry)
            if entry.is_dir():
                yield from self.bounded_files(entry)
            elif entry.is_file():
                yield entry
            else:
                raise ValueError('SOURCE_LAYOUT_NONFILE: ' + str(entry))

    def classify(self, name):
        """只按声明的源集分类；生成源、第三方文件与未知模块不自动取得受管身份。"""
        name = relative(name)
        for module in self.modules:
            prefix = module["directory"] + "/src/"
            if not name.startswith(prefix):
                continue
            pieces = name[len(prefix):].split("/", 2)
            if len(pieces) != 3 or pieces[0] not in module["sourceSets"] or pieces[1] not in SOURCE_ROOTS:
                break
            path = self._bounded(self.root / name)
            return SourceFile(module["path"], pieces[0], path, name, pieces[2],
                              "java" if path.suffix == ".java" else "kotlin")
        raise ValueError("SOURCE_LAYOUT_UNMAPPED: " + name)

    def source_files(self, source_sets=None, suffixes=SOURCE_SUFFIXES):
        """同一实例持有一次布局观察；文件变化后须新建实例，不跨运行复用清单。"""
        selected = None if source_sets is None else set(source_sets)
        if hasattr(self, '_source_entries'):
            return [entry for entry in self._source_entries
                    if entry.path.suffix in suffixes and (selected is None or entry.source_set in selected)]
        result, identities, implementations = [], {}, {}
        for module in self.modules:
            source_directory = self._bounded(self.root / module['directory'] / 'src')
            if source_directory.is_dir():
                for directory in source_directory.iterdir():
                    self._bounded(directory)
                    if not directory.is_dir() or directory.name in module['sourceSets']:
                        continue
                    unexpected = [path for language in SOURCE_ROOTS
                                  for path in self.bounded_files(directory / language)]
                    if any(path.suffix in SOURCE_SUFFIXES for path in unexpected):
                        raise ValueError('SOURCE_LAYOUT_UNDECLARED_SOURCE_SET: ' + str(directory))
            for source_set in module["sourceSets"]:
                for language in SOURCE_ROOTS:
                    directory = self._bounded(self.root / module["directory"] / "src" / source_set / language)
                    if not directory.exists():
                        continue  # 空的语言源根合法，缺模块任务不由空源根豁免。
                    if not directory.is_dir():
                        raise ValueError("SOURCE_LAYOUT_NOT_DIRECTORY: " + str(directory))
                    for path in self.bounded_files(directory):
                        if path.suffix not in SOURCE_SUFFIXES:
                            continue
                        entry = self.classify(path.relative_to(self.root).as_posix())
                        key = entry.identity.casefold()
                        stem = entry.source_set + ":" + str(PurePosixPath(entry.logical_path).with_suffix("")).casefold()
                        if key in identities or stem in implementations:
                            raise ValueError("SOURCE_LAYOUT_DUPLICATE_IDENTITY: " + entry.identity)
                        identities[key], implementations[stem] = entry, entry
                        result.append(entry)
        self._source_entries = tuple(sorted(result, key=lambda entry: (entry.identity, entry.module)))
        return self.source_files(source_sets, suffixes)

    def format_files(self, suffixes):
        result = []
        for entry in self.source_files(suffixes=suffixes):
            module = next(m for m in self.modules if m["path"] == entry.module)
            if entry.source_set in module["formatSourceSets"]:
                result.append(entry)
        return sorted(result, key=lambda entry: entry.relative)

    def resolve(self, logical_path, source_set="main"):
        relative(logical_path)
        matches = [entry.path for entry in self.source_files((source_set,))
                   if entry.logical_path == logical_path]
        if len(matches) != 1:
            raise ValueError("SOURCE_LAYOUT_MISSING_OR_DUPLICATE: " + source_set + ":" + logical_path)
        return matches[0]

    def resolve_class(self, name, source_set="main"):
        name = relative(name)
        if not name.startswith(PACKAGE + "/"):
            name = PACKAGE + "/" + name
        stem = str(PurePosixPath(name).with_suffix("")) if PurePosixPath(name).suffix else name
        matches = [entry.path for entry in self.source_files((source_set,), (".java", ".kt"))
                   if str(PurePosixPath(entry.logical_path).with_suffix("")) == stem]
        if len(matches) != 1:
            raise ValueError("SOURCE_LAYOUT_MISSING_OR_DUPLICATE_CLASS: " + name)
        return matches[0]

    def package_files(self, package="", source_set="main", suffixes=(".java", ".kt")):
        prefix = PACKAGE + ("/" + relative(package) if package else "") + "/"
        return [entry for entry in self.source_files((source_set,), suffixes)
                if entry.logical_path.startswith(prefix)]

    def compile_inputs(self):
        return {'schema': 1, 'modules': self.modules, 'sources': [
            {'module': entry.module, 'sourceSet': entry.source_set, 'path': entry.relative,
             'logicalPath': entry.logical_path} for entry in self.source_files()]}

    def verify_compile_head(self):
        """编译前核代码输入的 HEAD blob；工程脚本的未提交改动仍可做配置负控。"""
        from unit_test_contract import commit_blobs, git_blob_sha, head
        commit = head(self.root)
        blobs = commit_blobs(self.root, commit)
        sources = self.source_files()
        for entry in sources:
            if blobs.get(entry.relative) != git_blob_sha(entry.path):
                raise ValueError('SOURCE_COMPILE_NOT_IN_HEAD: ' + entry.relative)
        return {'commit': commit, 'sources': len(sources)}

    def test_tasks(self):
        return [{"module": module["path"], "task": module["path"] + ":" + module["testTask"],
                 "reports": module["directory"] + "/build/test-results/" + module["testTask"],
                 "sourceSets": module["testSourceSets"]} for module in self.modules]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--test-tasks", action="store_true")
    parser.add_argument("--launcher-inputs", action="store_true")
    parser.add_argument("--compile-inputs", action="store_true")
    parser.add_argument("--verify-compile-head", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    layout = SourceLayout(args.root)
    if args.verify_compile_head:
        result = layout.verify_compile_head()
    elif args.compile_inputs:
        result = layout.compile_inputs()
    elif args.launcher_inputs:
        from runtime_input_contract import launcher_paths
        result = {key: path.relative_to(layout.root).as_posix()
                  for key, path in launcher_paths(layout.root).items()}
    else:
        result = layout.test_tasks() if args.test_tasks else [
            {"identity": entry.identity, "module": entry.module, "path": entry.relative}
            for entry in layout.source_files()]
    text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    else:
        print(text, end="")


if __name__ == "__main__":
    main()
