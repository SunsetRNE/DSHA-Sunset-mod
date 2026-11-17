#!/usr/bin/env python3
"""Bounded formatter inventory and package batching regressions."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("format_java_tree", ROOT / "tools/format-java-tree.py")
tree = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tree)


class FormatTreeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="dsha-java-tree-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.layout = json.loads((ROOT / 'ci/source-layout.json').read_text(encoding='utf-8'))
        (self.root / 'ci').mkdir()
        (self.root / 'ci/source-layout.json').write_text(json.dumps(self.layout), encoding='utf-8')
        # 继承真实产品布局时，所有声明模块/格式源集都要有完整夹具根，不能只造 app。
        for module in self.layout['modules']:
            for source_set in module['formatSourceSets']:
                (self.root / module['directory'] / 'src' / source_set / tree.SOURCE).mkdir(parents=True)
        helper = self.root / tree.TOOL_SOURCE
        helper.parent.mkdir(parents=True)
        helper.write_text("class JavaTokenFingerprint {}\n", encoding="utf-8")

    def source(self, source_set, package, name, module=':app', language='java'):
        row = next(row for row in self.layout['modules'] if row['path'] == module)
        base = self.root / row['directory'] / 'src' / source_set / language / tree.PACKAGE
        path = base / package / name if package else base / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("class Example {}\n", encoding="utf-8")
        return path.relative_to(self.root).as_posix()

    def test_inventory_has_exact_five_owned_sets_and_protected_exclusion(self):
        protected = self.source("main", "", "ShellService.java")
        generated = self.source("main", "util", "CredentialPathRules.java")
        main = self.source("main", "util", "Query.java")
        debug = self.source("debug", "ui", "Audit.java")
        self.source("main", "generated", "DoNotFormat.java")
        self.source("main", "vendor", "Foreign.java")
        (self.root / "app/src/main/java/org/upstream").mkdir(parents=True)
        (self.root / "app/src/main/java/org/upstream/Foreign.java").write_text("class Foreign {}\n")
        self.assertEqual(sorted((main, debug, tree.TOOL_SOURCE)),
                         tree.inventory(self.root, protected=(protected,), generated=(generated,)))
        # 旧 low 源集已删除，不能悄悄出现在受管清单里。
        self.assertNotIn("low", tree.SETS)

    def test_inventory_covers_every_declared_module_and_format_source_set(self):
        expected = {tree.TOOL_SOURCE}
        self.assertIn(':core:policy', [row['path'] for row in self.layout['modules']])
        for module in self.layout['modules']:
            for source_set in module['formatSourceSets']:
                stem = module['path'].replace(':', '_') + 'Owned'
                expected.add(self.source(source_set, 'util', stem + '.java', module=module['path']))
                # Kotlin 源同受布局盘点，但不混入 Java 格式清单。
                self.source(source_set, 'util', stem + 'Kt.kt', module=module['path'], language='kotlin')
        actual = tree.inventory(self.root)
        self.assertEqual(sorted(expected), actual)
        policy_test = 'core/policy/src/test/java/' + tree.PACKAGE + '/util/_core_policyOwned.java'
        app_test = 'app/src/test/java/' + tree.PACKAGE + '/util/_appOwned.java'
        self.assertIn(policy_test, actual)
        self.assertIn(app_test, actual)
        with patch.object(tree, 'ROOT', self.root):
            batches = tree.package_batches([policy_test, app_test])
        self.assertEqual([(':core:policy/test', 'util'), ('test', 'util')],
                         [key for key, _ in batches])
        self.assertEqual({policy_test, app_test}, {name for _, names in batches for name in names})

    def test_missing_declared_module_format_root_is_rejected(self):
        # 每次只漏一个声明的源集，其他根完整；空缺不能被别的模块或 Kotlin 根掩盖。
        for module in self.layout['modules']:
            for source_set in module['formatSourceSets']:
                with self.subTest(module=module['path'], source_set=source_set):
                    fixture = self.root / ('missing-' + module['directory'].replace('/', '-') + '-' + source_set)
                    (fixture / 'ci').mkdir(parents=True)
                    (fixture / 'ci/source-layout.json').write_text(json.dumps(self.layout), encoding='utf-8')
                    for other in self.layout['modules']:
                        for other_set in other['formatSourceSets']:
                            if (other['path'], other_set) != (module['path'], source_set):
                                (fixture / other['directory'] / 'src' / other_set / tree.SOURCE).mkdir(parents=True)
                    with self.assertRaises(tree.TreeFormatError) as caught:
                        tree.inventory(fixture)
                    missing = fixture / module['directory'] / 'src' / source_set / tree.SOURCE
                    self.assertEqual('FORMAT_SOURCE_SET_MISSING: ' + str(missing), str(caught.exception))

    def test_declared_machine_generated_sources_have_real_owners(self):
        lock = json.loads(tree.LOCK.read_text(encoding="utf-8"))
        self.assertEqual(set(tree.GENERATED_OWNERS), set(lock["generatedFiles"]))
        for name, generator in tree.GENERATED_OWNERS.items():
            self.assertTrue((ROOT / name).is_file(), name)
            self.assertTrue((ROOT / generator).is_file(), generator)
            self.assertNotIn(name, tree.inventory(ROOT, protected=lock["protectedFiles"],
                                                   generated=lock["generatedFiles"]))

    def test_batches_group_by_source_set_and_package_without_crossing_limit(self):
        names = [f"app/src/main/java/com/deepseekharness/app/util/Class{n}.java"
                 for n in range(300)]
        names += ["app/src/test/java/com/deepseekharness/app/util/QueryTest.java",
                  "app/src/main/java/com/deepseekharness/app/HttpShellService.java"]
        batches = tree.package_batches(names)
        # MAX_BATCH 固定为 128：argparse 的 nargs='+' 在 250+ 文件列表下会静默截断。
        self.assertLessEqual(tree.MAX_BATCH, 128)
        self.assertTrue(all(len(files) <= tree.MAX_BATCH for _, files in batches))
        self.assertEqual([1, 128, 128, 44, 1], [len(files) for _, files in batches])
        self.assertEqual([("main", "(root)"), ("main", "util"),
                          ("main", "util"), ("main", "util"), ("test", "util")],
                         [key for key, _ in batches])
        self.assertEqual(set(names), {name for _, files in batches for name in files})

    def test_manifest_rejects_duplicates_and_non_string_entries(self):
        manifest = self.root / "manifest.json"
        for data in ({"schema": 1, "files": ["x.java", "x.java"]},
                     {"schema": 1, "files": [42]}, {"schema": 2, "files": []}):
            manifest.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaises(tree.TreeFormatError):
                tree.declared_files(manifest)


if __name__ == "__main__":
    unittest.main(verbosity=2)
