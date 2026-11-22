#!/usr/bin/env python3
"""真实文件/XML/时钟/摘要负控；临时 CI/原生库/编译目录均为明确标识的合成夹具。

不运行 Gradle，不声称这些夹具证明 JNI 或历史 CI；历史冻结必须另取真实 CI 产物。
"""
import copy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import unit_test_contract as contract


class FixtureLayout:
    def __init__(self, root):
        self.root = root
        self.tasks = [
            {"module": ":app", "task": ":app:testStandardDebugUnitTest",
             "reports": "app/build/test-results/testStandardDebugUnitTest", "sourceSets": ["test"]},
            {"module": ":core:policy", "task": ":core:policy:test",
             "reports": "core/policy/build/test-results/test", "sourceSets": ["test"]},
        ]

    @property
    def modules(self):
        return [{"path": module, "directory": directory, "testSourceSets": ["test"]}
                for directory, module in (("app", ":app"), ("core/policy", ":core:policy"))]

    def test_tasks(self):
        return copy.deepcopy(self.tasks)

    def source_files(self, source_sets=None, suffixes=(".java", ".kt")):
        files = []
        for directory, module in (("app", ":app"), ("core/policy", ":core:policy")):
            for path in sorted((self.root / directory / "src").rglob("*")):
                if not path.is_file() or path.suffix not in suffixes:
                    continue
                relative = path.relative_to(self.root).as_posix()
                pieces = path.relative_to(self.root / directory / "src").parts
                if source_sets is None or pieces[0] in source_sets:
                    files.append(SimpleNamespace(module=module, source_set=pieces[0], path=path,
                                                 relative=relative, logical_path="/".join(pieces[2:])))
        return files


def xml(path, pairs, *, status=None, override=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    counts = dict(tests=len(pairs), failures=0, errors=0, skipped=0)
    if status:
        counts[{"failure": "failures", "error": "errors", "skipped": "skipped"}[status]] = 1
    counts.update(override or {})
    suite = ET.Element("testsuite", **{key: str(value) for key, value in counts.items()})
    for index, (owner, name) in enumerate(pairs):
        case = ET.SubElement(suite, "testcase", classname=owner, name=name)
        if index == 0 and status:
            ET.SubElement(case, status)
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


class UnitContract(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="unit contract 中文 # ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.layout = FixtureLayout(self.root)
        fixtures = {}
        for owner, method in contract.REQUIRED_FIXTURES:
            fixtures.setdefault(owner, []).append(method)
        fixtures[contract.PREFIX + "util.RustDigestDifferentialTest"] = ["nativeFixture"]
        fixtures["one.SharedTest"] = ["sameName", "sdk"]
        for owner, methods in fixtures.items():
            package, name = owner.rsplit(".", 1)
            self.put("app/src/test/java/" + owner.replace(".", "/") + ".java",
                     "package " + package + "; public class " + name + " { " +
                     " ".join("@org.junit.Test public void " + method + "() {}" for method in methods) + " }")
        self.put("app/src/test/java/one/TestHelper.java", "package one; public class TestHelper {}")
        self.put("app/src/main/java/one/Production.java", "package one; public class Production {}")
        self.put("core/policy/src/test/kotlin/two/SharedTest.kt",
                 "package two\nimport org.junit.Test\nclass SharedTest {\n"
                 "@Test fun sameName() {}\n@Test fun `keeps spaces and brackets [x]`() {}\n}")
        self.put("core/policy/src/test/kotlin/two/KotlinHelper.kt",
                 "package two\nclass KotlinHelper\n")
        self.put("app/src/main/res/values/strings.xml", "<resources><string name='fixture'>before</string></resources>")
        self.put("build.gradle", "// committed synthetic Gradle input\n")
        self.put(".gitignore", "**/build/\nout/\n**/src/test/resources/robolectric.properties\n")
        self.git("init", "-q")
        self.git("add", "app", "core", "build.gradle", ".gitignore")
        self.git("-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "synthetic contract source fixture")
        self.commit = contract.head(self.root)
        self.library = self.put("out/rust/libdsha_rs.so", "synthetic opaque native artifact, not executable JNI")
        self.rust_receipt = self.root / "out/rust/receipt.json"
        contract.write_json(self.rust_receipt, {"kind": "dsha-rust-native", "target": "host",
                            "commit": self.commit, "runId": "12345", "runAttempt": "1",
                            "artifact": {"artifactFile": self.library.name, "sha256": contract.digest(self.library),
                                         "size": self.library.stat().st_size}})
        self.plan_path = self.root / "out/plan.json"
        self.state = self.root / "out/run"
        self.baseline = self.root / "ci/unit-test-baseline.json"
        contract.write_json(self.baseline, {"schema": 1, "kind": "dsha-unit-test-baseline",
                                           "status": "awaiting-real-full-ci"})
        self.ci_path = self.root / "out/ci-run.json"
        contract.write_json(self.ci_path, {"databaseId": 12345, "headSha": self.commit, "attempt": 1,
                            "status": "completed", "conclusion": "success",
                            "url": "https://github.com/synthetic/contract-fixture/actions/runs/12345"})
        self.refresh_plan()

    def put(self, name, body):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
        return path

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.root), *args], check=True, capture_output=True, text=True).stdout

    def commit_candidate(self):
        self.git("add", "app", "core", "build.gradle")
        self.git("-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "synthetic candidate source change")
        self.commit = contract.head(self.root)
        for path, key in ((self.rust_receipt, "commit"), (self.ci_path, "headSha")):
            value = contract.load(path)
            value[key] = self.commit
            contract.write_json(path, value)

    def refresh_plan(self):
        self.plan = contract.plan(self.root, self.layout)
        contract.write_json(self.plan_path, self.plan)
        for task in self.plan["tasks"]:
            target = self.class_dir(task)
            for source in self.plan["sources"]:
                if source["module"] == task["module"]:
                    for owner in source["classes"]:
                        self.put((target.relative_to(self.root) / (owner.replace(".", "/") + ".class")).as_posix(),
                                 "synthetic class inventory fixture, no compilation claim")

    def class_dir(self, task):
        module = task["reports"].split("/build/", 1)[0]
        return self.root / module / "build/classes/test-fixture"

    def begin(self, **kwargs):
        return contract.begin(self.root, self.plan_path, self.state, "12345", "1", layout=self.layout,
                              rust_library=self.library, rust_receipt=self.rust_receipt, **kwargs)

    def proof(self, task):
        path = contract.compile_proof_file(self.state, task["task"])
        if path.exists():
            return path
        run = contract.load(self.state / "run.json")
        inventory = contract.compile_inventory(self.root, self.layout)
        module = next(row for row in inventory["modules"] if row["path"] == task["module"])
        owned = [row for row in self.plan["inputSources"] if row["module"] == task["module"]]
        source_sets = module.get("sourceSets", sorted({"main", *module["testSourceSets"],
                                                     *(row["sourceSet"] for row in owned)}))
        kotlin = any(row["path"].endswith(".kt") for row in owned)
        roots, compiles = [], []
        for source_set in source_sets:
            prefix = module["directory"] + "/src/" + source_set + "/"
            roots.append({"module": module["path"], "sourceSet": source_set,
                          "api": "jvm-java", "roots": [prefix + "java"]})
            if kotlin:
                for api in ("kotlin", "kotlin-extension"):
                    roots.append({"module": module["path"], "sourceSet": source_set,
                                  "api": api, "roots": [prefix + "java", prefix + "kotlin"]})
        for source_set in ["main", *module["testSourceSets"]]:
            selected = [row for row in owned if row["sourceSet"] == source_set]
            java = [{"path": row["path"], "sha256": row["sha256"], "origin": "source"}
                    for row in selected if row["path"].endswith(".java")]
            kt = [{"path": row["path"], "sha256": row["sha256"], "origin": "source"}
                  for row in selected if row["path"].endswith(".kt")]
            for compiler in (["java", "kotlin"] if kotlin else ["java"]):
                name = "compile" + ("" if source_set == "main" else source_set[:1].upper() + source_set[1:])
                name += "Java" if compiler == "java" else "Kotlin"
                row = {"task": module["path"] + ":" + name, "module": module["path"], "compiler": compiler,
                       "sourceSets": [source_set], "component": None,
                       "sources": java if compiler == "java" else kt,
                       "javaSources": [] if compiler == "java" else java, "generators": []}
                if row["sources"]:
                    execution = contract.compile_execution_file(self.state, row["task"])
                    if not execution.exists():
                        receipt = {"schema": 1, "kind": "dsha-compile-execution",
                                   **{key: run[key] for key in ("commit", "runId", "runAttempt", "planSha256")},
                                   "compileTask": row["task"], "module": module["path"], "compiler": compiler,
                                   "startedNs": contract.time.time_ns(), "finishedNs": contract.time.time_ns(),
                                   "outcome": "SUCCESS", "before": copy.deepcopy(row), "after": copy.deepcopy(row)}
                        contract.write_json(execution, receipt, exclusive=True)
                    row["execution"] = {"file": execution.relative_to(self.state).as_posix(),
                                        "sha256": contract.digest(execution)}
                else:
                    row["execution"] = None
                compiles.append(row)
        value = {"schema": 1, "kind": "dsha-compile-proof",
                 **{key: run[key] for key in ("commit", "runId", "runAttempt", "planSha256")},
                 "testTask": task["task"], "createdNs": contract.time.time_ns(), "inventory": inventory,
                 "sourceRoots": roots, "compileTasks": compiles}
        contract.write_json(path, value, exclusive=True)
        return path

    def rewrite_compile_proof(self, task, change, *, sync_execution=False):
        """只修改显式合成的记录；不是生产编译或 JNI 证明。"""
        path = self.proof(task)
        value = contract.load(path)
        change(value)
        if sync_execution:
            run = contract.load(self.state / "run.json")
            for row in value["compileTasks"]:
                if row.get("execution") is None:
                    continue
                execution = contract.compile_execution_file(self.state, row["task"])
                receipt = contract.load(execution)
                model = {key: item for key, item in row.items() if key != "execution"}
                receipt["before"] = copy.deepcopy(model)
                receipt["after"] = copy.deepcopy(model)
                contract.write_json(execution, receipt)
                row["execution"]["sha256"] = contract.digest(execution)
        contract.write_json(path, value)
        return path

    def configure_android_fixture(self):
        """与实测 AGP schema 同形的合成输入；不调用 Gradle，也不声称编译这些文件。"""
        base = self.layout
        modules = [{**module, "kind": "jvm", "sourceSets": ["main", "test"]} for module in base.modules]
        modules[0].update(kind="android-application", namespace=contract.PREFIX[:-1],
                          sourceSets=["main", "debug", "standard", "test"])
        self.layout = SimpleNamespace(modules=modules, source_files=base.source_files,
                                      test_tasks=base.test_tasks)
        self.put("app/src/main/aidl/sample/Port.aidl", "package sample; interface Port {}\n")
        self.commit_candidate()
        self.refresh_plan()

    def android_proof(self, task):
        """生成原始 JSON、精确 generated owner 和 before/after receipt 的隔离测试样本。"""
        path = contract.compile_proof_file(self.state, task["task"])
        if path.exists():
            return path
        run = contract.load(self.state / "run.json")
        inventory = contract.compile_inventory(self.root, self.layout)
        module = next(row for row in inventory["modules"] if row["path"] == task["module"])
        from ui_language_catalog import LANGUAGES
        generated = []
        ui_root = "app/build/generated/uiLanguage"
        ui_files = []
        for name in ["UiMessages", *("UiMessages_" + value.replace("-", "_") for value in LANGUAGES)]:
            output = self.put(ui_root + "/com/deepseekharness/app/util/" + name + ".java",
                              "// synthetic generated-file fixture, no compiler execution\n")
            ui_files.append({"path": output.relative_to(self.root).as_posix(),
                             "sha256": contract.digest(output), "origin": "generated"})
        generated.append({"task": ":app:prepareUiLanguages", "root": ui_root, "files": ui_files})
        for producer, root, name in (
                (":app:generateStandardDebugBuildConfig", "app/build/generated/source/buildConfig/standard/debug",
                 "com/deepseekharness/app/BuildConfig.java"),
                (":app:compileStandardDebugAidl", "app/build/generated/aidl_source_output_dir/standardDebug/out",
                 "sample/Port.java")):
            output = self.put(root + "/" + name, "// synthetic generated-file fixture, no compiler execution\n")
            generated.append({"task": producer, "root": root, "files": [{
                "path": output.relative_to(self.root).as_posix(), "sha256": contract.digest(output),
                "origin": "generated"}]})
        all_sets = ("main", "debug", "standard", "test", "testDebug", "testStandard")
        roots = []
        for source_set in all_sets:
            prefix = "app/src/" + source_set + "/"
            roots.append({"module": ":app", "sourceSet": source_set, "api": "android-java",
                          "roots": [prefix + "java", *([ui_root] if source_set == "main" else [])]})
            roots.append({"module": ":app", "sourceSet": source_set, "api": "kotlin",
                          "roots": [prefix + "java", prefix + "kotlin"]})
        compiles = []
        for component_name, selected in (("standardDebug", ["main", "debug", "standard"]),
                                         ("standardDebugUnitTest", ["test", "testDebug", "testStandard"])):
            task_prefix = component_name[:1].upper() + component_name[1:]
            production = component_name == "standardDebug"
            java_static = ["app/src/" + source_set + "/java" for source_set in selected]
            component = {"name": component_name, "buildType": "debug",
                         "productFlavors": [{"dimension": "androidSupport", "name": "standard"}],
                         "namespace": contract.PREFIX[:-1],
                         "compileJavaTask": ":app:compile" + task_prefix + "JavaWithJavac",
                         "compileKotlinTask": ":app:compile" + task_prefix + "Kotlin",
                         "aidlTask": ":app:compileStandardDebugAidl" if production else None,
                         "buildConfigTask": ":app:generateStandardDebugBuildConfig" if production else None,
                         "javaStatic": java_static + ([ui_root] if production else []),
                         "kotlinStatic": java_static + ["app/src/" + source_set + "/kotlin" for source_set in selected]}
            roots.append({"module": ":app", "sourceSet": component_name, "api": "kotlin-extension", "roots": []})
            java = [{"path": item["path"], "sha256": item["sha256"], "origin": "source"}
                    for item in run["plan"]["inputSources"] if item["module"] == ":app"
                    and item["sourceSet"] in selected and item["path"].endswith(".java")]
            kotlin = [{"path": item["path"], "sha256": item["sha256"], "origin": "source"}
                      for item in run["plan"]["inputSources"] if item["module"] == ":app"
                      and item["sourceSet"] in selected and item["path"].endswith(".kt")]
            generators = copy.deepcopy(generated) if production else []
            java += [copy.deepcopy(item) for owner in generators for item in owner["files"]]
            for compiler in ("java", "kotlin"):
                row = {"task": component["compileJavaTask" if compiler == "java" else "compileKotlinTask"],
                       "module": ":app", "compiler": compiler, "sourceSets": selected, "component": component,
                       "sources": java if compiler == "java" else kotlin,
                       "javaSources": [] if compiler == "java" else java, "generators": copy.deepcopy(generators)}
                if row["sources"]:
                    execution = contract.compile_execution_file(self.state, row["task"])
                    receipt = {"schema": 1, "kind": "dsha-compile-execution",
                               **{key: run[key] for key in ("commit", "runId", "runAttempt", "planSha256")},
                               "compileTask": row["task"], "module": ":app", "compiler": compiler,
                               "startedNs": contract.time.time_ns(), "finishedNs": contract.time.time_ns(),
                               "outcome": "SUCCESS", "before": copy.deepcopy(row), "after": copy.deepcopy(row)}
                    contract.write_json(execution, receipt, exclusive=True)
                    row["execution"] = {"file": execution.relative_to(self.state).as_posix(),
                                        "sha256": contract.digest(execution)}
                else:
                    row["execution"] = None
                compiles.append(row)
        value = {"schema": 1, "kind": "dsha-compile-proof",
                 **{key: run[key] for key in ("commit", "runId", "runAttempt", "planSha256")},
                 "testTask": task["task"], "createdNs": contract.time.time_ns(),
                 "inventory": inventory, "sourceRoots": roots, "compileTasks": compiles}
        contract.write_json(path, value, exclusive=True)
        return path

    def start(self, task, **overrides):
        args = dict(source_only=False, filters=[], rust_required=True, rust_library=self.library,
                    includes=[], excludes=[], scan_for_test_classes=True,
                    test_classes_dirs=[str(self.class_dir(task))], compile_proof=self.proof(task), layout=self.layout)
        args.update(overrides)
        return contract.task_begin(self.root, self.state, task["task"], **args)

    def pairs(self, task):
        rows = []
        for source in self.plan["sources"]:
            if source["module"] == task["module"]:
                for test in source["tests"]:
                    if test["method"] == "sdk":
                        rows.extend([(test["classname"], "sdk[28][0: a]"),
                                     (test["classname"], "sdk[35][1: b]")])
                    else:
                        rows.append((test["classname"], test["method"]))
        return rows

    def report_path(self, task):
        return self.root / task["reports"] / "TEST-fixture.xml"

    def end(self, task):
        return contract.task_end(self.root, self.state, task["task"], outcome="SUCCESS", layout=self.layout)

    def finish(self, transform=None):
        self.begin()
        for task in self.plan["tasks"]:
            self.start(task)
            rows = self.pairs(task)
            xml(self.report_path(task), transform(task, rows) if transform else rows)
            self.end(task)

    def verify(self, **kwargs):
        return contract.verify_run(self.root, self.state, baseline=self.baseline,
                                   run_id="12345", run_attempt="1", layout=self.layout, **kwargs)

    def freeze(self):
        return contract.freeze(self.root, self.state, self.ci_path, self.baseline, layout=self.layout)

    def import_inputs(self, **overrides):
        receipt = self.root / "out/old-summary.json"
        contract.write_json(receipt, {"mode": "full", "flavors": {"Standard": {
            "tests": sum(len(self.pairs(task)) for task in self.plan["tasks"]),
            "failures": 0, "errors": 0, "skipped": 0}}})
        log = self.put("out/unit-job.log", "  MODE: full\n  TESTS: \n  RUST_HOST: true\n"
                       "args+=(-Pdsha.rust.required=true)\nargs+=(-Pdsha.sourceOnlyTests=false)\n")
        artifact = self.root / "out/artifact.json"
        archive = self.root / "out/explicit-synthetic-artifact.zip"
        with contract.zipfile.ZipFile(archive, "w") as bundle:
            for task in self.plan["tasks"]:
                path = self.report_path(task)
                if path.exists():
                    bundle.write(path, path.relative_to(self.root).as_posix())
            bundle.write(receipt, receipt.relative_to(self.root).as_posix())
        contract.write_json(artifact, {"id": 123, "name": "explicit-synthetic-artifact", "expired": False,
                            "workflow_run": {"id": 12345, "head_sha": self.commit},
                            "digest": "sha256:" + contract.digest(archive)})
        args = dict(root=self.root, plan_path=self.plan_path, ci_run=self.ci_path,
                    reports_root=self.root, unit_receipt=receipt, run_log=log, artifact=artifact,
                    output=self.baseline, archive=archive, layout=self.layout)
        args.update(overrides)
        return args

    def imported(self, **overrides):
        return contract.import_ci_baseline(**self.import_inputs(**overrides))

    def test_plan_keeps_kotlin_and_both_helpers_and_all_modules(self):
        plan = self.plan
        self.assertEqual({":app", ":core:policy"}, {t["module"] for t in plan["tasks"]})
        self.assertTrue(any(s["path"].endswith("SharedTest.kt") and s["tests"] for s in plan["sources"]))
        self.assertEqual(2, sum(s["path"].endswith(("TestHelper.java", "KotlinHelper.kt")) for s in plan["sources"]))
        self.layout.tasks.pop()
        with self.assertRaisesRegex(ValueError, "SOURCE_WITHOUT_TASK"):
            contract.plan(self.root, self.layout)

    def test_full_roundtrip_retains_exact_fqcn_parameter_sdk_and_kotlin_names(self):
        self.finish()
        baseline = self.freeze()
        result = self.verify()
        self.assertEqual("matched", result["baselineStatus"])
        self.assertIn(["one.SharedTest", "sdk[28][0: a]"], baseline["identities"])
        self.assertIn(["one.SharedTest", "sdk[35][1: b]"], baseline["identities"])
        self.assertIn(["two.SharedTest", "keeps spaces and brackets [x]"], baseline["identities"])
        self.assertIn(["one.SharedTest", "sameName"], baseline["identities"])
        self.assertIn(["two.SharedTest", "sameName"], baseline["identities"])
        self.assertEqual("matched", self.verify()["baselineStatus"])
        self.assertEqual(7, result["requiredFixtureTestsPassed"])

    def test_pending_baseline_never_passes_regular_aggregate(self):
        self.finish()
        with self.assertRaisesRegex(ValueError, "BASELINE_PENDING"):
            self.verify()

    def test_begin_records_and_removes_old_xml_then_rejects_repeated_begin(self):
        path = self.report_path(self.plan["tasks"][0])
        xml(path, [("old.X", "old")])
        old_sha = contract.digest(path)
        run = self.begin()
        self.assertFalse(path.exists())
        self.assertEqual(old_sha, run["priorReports"][0]["sha256"])
        with self.assertRaisesRegex(ValueError, "RUN_ALREADY_EXISTS"):
            self.begin()

    def test_full_cannot_use_source_only_filtered_optional_rust_or_class_filters(self):
        self.begin()
        task = self.plan["tasks"][0]
        for override in (dict(source_only=True), dict(filters=["*OneTest"]), dict(rust_required=False),
                         dict(includes=["**/*Test.class"]), dict(excludes=["**/Missing.class"]),
                         dict(scan_for_test_classes=False)):
            with self.subTest(override=override), self.assertRaisesRegex(ValueError, "FULL_TASK_"):
                self.start(task, **override)
        self.start(task)
        with self.assertRaisesRegex(ValueError, "EVIDENCE_ALREADY_EXISTS"):
            self.start(task)

    def test_compiled_inventory_rejects_missing_kotlin_and_helper_classes(self):
        self.begin()
        task = self.plan["tasks"][1]
        for owner in ("two.SharedTest", "two.KotlinHelper"):
            path = self.class_dir(task) / (owner.replace(".", "/") + ".class")
            body = path.read_bytes()
            path.unlink()
            with self.assertRaisesRegex(ValueError, "SOURCE_CLASS_NOT_COMPILED"):
                self.start(task)
            path.write_bytes(body)

    def test_missing_task_xml_and_zero_tests_are_rejected(self):
        self.begin()
        with self.assertRaisesRegex(ValueError, "TASK_EVIDENCE_MISSING"):
            self.verify()
        task = self.plan["tasks"][0]
        self.start(task)
        with self.assertRaisesRegex(ValueError, "JUNIT_MISSING"):
            self.end(task)
        xml(self.report_path(task), [])
        with self.assertRaisesRegex(ValueError, "ZERO_TEST_REPORT"):
            self.end(task)
        for outcome in ("UP-TO-DATE", "FROM-CACHE", "NO-SOURCE", "SKIPPED", "FAILED"):
            with self.subTest(outcome=outcome), self.assertRaisesRegex(ValueError, "TASK_NOT_EXECUTED"):
                contract.task_end(self.root, self.state, task["task"], outcome=outcome, layout=self.layout)

    def test_fail_error_skip_and_lying_counts_execute_xml_parser(self):
        self.begin()
        task = self.plan["tasks"][0]
        self.start(task)
        for status in ("failure", "error", "skipped"):
            xml(self.report_path(task), self.pairs(task), status=status)
            with self.subTest(status=status), self.assertRaisesRegex(ValueError, "FULL_UNIT_TESTS_FAILED"):
                self.end(task)
        xml(self.report_path(task), self.pairs(task), status="failure", override={"failures": 0})
        with self.assertRaisesRegex(ValueError, "JUNIT_COUNTS"):
            self.end(task)

    def test_old_xml_mtime_and_preexisting_task_report_are_rejected(self):
        self.begin()
        task = self.plan["tasks"][0]
        path = self.report_path(task)
        xml(path, self.pairs(task))
        with self.assertRaisesRegex(ValueError, "TASK_REPORTS_NOT_EMPTY"):
            self.start(task)
        path.unlink()
        self.start(task)
        xml(path, self.pairs(task))
        os.utime(path, ns=(1_000_000_000, 1_000_000_000))
        with self.assertRaisesRegex(ValueError, "STALE_XML"):
            self.end(task)

    def test_after_end_hash_mtime_and_xml_set_tampering_are_rejected(self):
        self.finish()
        task = self.plan["tasks"][0]
        path = self.report_path(task)
        saved, stat = path.read_bytes(), path.stat()
        path.write_bytes(saved + b"\n")
        os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        with self.assertRaisesRegex(ValueError, "XML_CHANGED"):
            self.verify()
        path.write_bytes(saved)
        extra = path.with_name("TEST-extra.xml")
        xml(extra, [("one.SharedTest", "injected")])
        with self.assertRaisesRegex(ValueError, "XML_SET_CHANGED"):
            self.verify()

    def test_run_commit_attempt_and_task_binding_are_enforced(self):
        self.finish()
        with self.assertRaisesRegex(ValueError, "RUN_IDENTITY"):
            contract.verify_run(self.root, self.state, run_id="wrong", baseline=self.baseline,
                                layout=self.layout)
        with self.assertRaisesRegex(ValueError, "RUN_IDENTITY"):
            contract.verify_run(self.root, self.state, run_attempt="2", baseline=self.baseline,
                                layout=self.layout)
        path = contract.task_record(self.state, self.plan["tasks"][0]["task"], "end")
        body = contract.load(path)
        body["runId"] = "wrong"
        contract.write_json(path, body)
        with self.assertRaisesRegex(ValueError, "TASK_BINDING"):
            self.verify()
        self.git("-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid", "commit", "--allow-empty", "-qm", "different commit fixture")
        with self.assertRaisesRegex(ValueError, "RUN_COMMIT"):
            self.verify()

    def test_helper_and_kotlin_source_drift_invalidate_fresh_run(self):
        self.finish()
        for relative in ("app/src/test/java/one/TestHelper.java", "core/policy/src/test/kotlin/two/SharedTest.kt",
                         "app/src/main/java/one/Production.java"):
            path = self.root / relative
            body = path.read_text(encoding="utf-8")
            path.write_text(body + "\n// real byte drift\n", encoding="utf-8")
            with self.subTest(relative=relative), self.assertRaisesRegex(ValueError, "PLAN_DRIFT"):
                self.verify()
            path.write_text(body, encoding="utf-8")

    def test_missing_source_method_and_duplicate_identity_fail(self):
        self.finish(lambda task, rows: [r for r in rows if r != ("one.SharedTest", "sameName")])
        with self.assertRaisesRegex(ValueError, "SOURCE_TEST_NOT_EXECUTED"):
            self.verify()
        self.state = self.root / "out/another-run"
        self.finish(lambda task, rows: rows + ([rows[0]] if task["module"] == ":app" else []))
        with self.assertRaisesRegex(ValueError, "DUPLICATE_TEST_IDENTITY"):
            self.verify()

    def test_baseline_detects_lost_sdk_identity_even_when_source_and_total_count_pass(self):
        self.finish()
        self.freeze()
        self.state = self.root / "out/candidate"
        self.finish(lambda task, rows: [(o, "sdk[99][2: substituted]" if n == "sdk[35][1: b]" else n) for o, n in rows])
        with self.assertRaisesRegex(ValueError, "BASELINE_TEST_MISSING"):
            self.verify()
        with self.assertRaisesRegex(ValueError, "BASELINE_TEST_MISSING"):
            self.freeze()

    def test_baseline_helper_loss_is_detected_independently_of_test_counts(self):
        self.finish()
        self.freeze()
        (self.root / "core/policy/src/test/kotlin/two/KotlinHelper.kt").unlink()
        self.commit_candidate()
        self.refresh_plan()
        self.state = self.root / "out/candidate"
        self.finish()
        with self.assertRaisesRegex(ValueError, "BASELINE_SOURCE_MISSING"):
            self.verify()

    def test_ignore_annotations_aliases_and_comment_string_false_positives(self):
        path = self.root / "core/policy/src/test/kotlin/two/SharedTest.kt"
        body = path.read_text(encoding="utf-8")
        for annotation in ("@org.junit.Ignore", "@Disabled", "@Skip"):
            path.write_text("import org.junit.Ignore as Skip\n" + body.replace("@Test", annotation + "\n@Test", 1), encoding="utf-8")
            with self.subTest(annotation=annotation), self.assertRaisesRegex(ValueError, "IGNORED_SOURCE"):
                contract.plan(self.root, self.layout)
        path.write_text(body + '\n// @Ignore @Test\nval text = "@Ignore and @Test"\n', encoding="utf-8")
        self.assertEqual(len(self.plan["sources"]), len(contract.plan(self.root, self.layout)["sources"]))

    def test_path_escape_and_report_symlink_never_clean_external_file(self):
        outside = self.put("outside/TEST-keep.xml", "untouched")
        report = self.root / self.plan["tasks"][0]["reports"]
        report.parent.mkdir(parents=True)
        report.symlink_to(outside.parent, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "PATH_SYMLINK"):
            self.begin()
        self.assertEqual("untouched", outside.read_text(encoding="utf-8"))
        report.unlink()
        self.layout.tasks[0]["reports"] = "../escape/build/test-results/testStandardDebugUnitTest"
        with self.assertRaisesRegex(ValueError, "PATH_INVALID"):
            contract.plan(self.root, self.layout)

    def test_fixture_presence_is_mandatory_even_if_no_source_declares_it(self):
        self.finish()
        target = contract.REQUIRED_FIXTURES[0]
        # 保留 class 源但删除该测试声明，证明不是只依赖源覆盖断言来验证七夹具。
        path = self.root / "app/src/test/java" / (target[0].replace(".", "/") + ".java")
        path.write_text("package " + target[0].rsplit(".", 1)[0] + "; public class AdbWheelBundleTest {}", encoding="utf-8")
        self.commit_candidate()
        self.refresh_plan()
        self.state = self.root / "out/candidate"
        self.finish()
        with self.assertRaisesRegex(ValueError, "REQUIRED_FIXTURE"):
            self.verify()

    def test_freeze_requires_matching_completed_ci_and_reverifies_real_xml(self):
        self.finish()
        ci = contract.load(self.ci_path)
        for field, value in (("conclusion", "failure"), ("status", "in_progress"),
                             ("headSha", "0" * 40), ("attempt", 2), ("databaseId", 54321)):
            contract.write_json(self.ci_path, {**ci, field: value})
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "BASELINE_CI_PROVENANCE"):
                self.freeze()
        contract.write_json(self.ci_path, ci)
        self.report_path(self.plan["tasks"][0]).unlink()
        with self.assertRaisesRegex(ValueError, "JUNIT_MISSING"):
            self.freeze()

    def test_archived_freeze_uses_recorded_time_and_hash_not_extraction_mtime(self):
        self.finish()
        for task in self.plan["tasks"]:
            path = self.report_path(task)
            os.utime(path, ns=(1_000_000_000, 1_000_000_000))
        with self.assertRaisesRegex(ValueError, "XML_CHANGED"):
            self.verify()
        baseline = contract.freeze(self.root, self.state, self.ci_path, self.baseline,
                                   archived=True, layout=self.layout)
        self.assertEqual("frozen", baseline["status"])
        self.report_path(self.plan["tasks"][0]).write_bytes(b"changed bytes")
        with self.assertRaisesRegex(ValueError, "XML_CHANGED"):
            contract.freeze(self.root, self.state, self.ci_path, self.baseline, archived=True, layout=self.layout)

    def test_historical_import_only_freezes_actual_xml_and_git_bound_source_inventory(self):
        for task in self.plan["tasks"]:
            xml(self.report_path(task), self.pairs(task))
        result = self.imported()
        self.assertEqual("historical-ci-import-no-freshness-attestation", result["acquisition"])
        self.assertFalse(self.state.exists(), "历史导入绝不能伪造 run/task freshness 证据")
        self.assertEqual(contract.digest(self.report_path(self.plan["tasks"][0])), result["rawXml"][0]["sha256"])
        with self.assertRaisesRegex(ValueError, "CONTRACT_JSON"):
            self.verify()
        path = self.root / "app/src/test/java/one/TestHelper.java"
        path.write_text(path.read_text(encoding="utf-8") + "\n// uncommitted drift", encoding="utf-8")
        self.refresh_plan()
        with self.assertRaisesRegex(ValueError, "HISTORICAL_SOURCE_CHANGED"):
            self.imported()

    def test_historical_summary_alone_missing_kotlin_xml_and_source_only_cannot_import(self):
        for task in self.plan["tasks"]:
            xml(self.report_path(task), self.pairs(task))
        kotlin = self.report_path(self.plan["tasks"][1])
        kotlin.unlink()
        with self.assertRaisesRegex(ValueError, "JUNIT_MISSING"):
            self.imported()
        xml(kotlin, self.pairs(self.plan["tasks"][1]))
        bad_log = self.put("out/filtered.log", "MODE: full\nTESTS: *SomeTest\nRUST_HOST: true\n-Pdsha.rust.required=true\n-Pdsha.sourceOnlyTests=false")
        with self.assertRaisesRegex(ValueError, "CI_UNIT_LOG_CONFIGURATION"):
            self.imported(run_log=bad_log)
        bad_receipt = self.root / "out/source-only-summary.json"
        contract.write_json(bad_receipt, {"mode": "source-only", "flavors": {"Standard": {"tests": 999}}})
        with self.assertRaisesRegex(ValueError, "HISTORICAL_NOT_FULL"):
            self.imported(unit_receipt=bad_receipt)

    def test_historical_rest_metadata_and_artifact_list_select_exact_archive(self):
        for task in self.plan["tasks"]:
            xml(self.report_path(task), self.pairs(task))
        args = self.import_inputs()
        ci = contract.load(self.ci_path)
        contract.write_json(self.ci_path, {"id": ci["databaseId"], "head_sha": ci["headSha"],
                            "run_attempt": ci["attempt"], "status": "completed", "conclusion": "success",
                            "url": "https://api.github.com/repos/synthetic/contract-fixture/actions/runs/12345",
                            "html_url": ci["url"]})
        metadata = contract.load(args["artifact"])
        contract.write_json(args["artifact"], {"artifacts": [
            {**metadata, "id": 456, "name": "other-artifact", "digest": "sha256:" + "0" * 64}, metadata]})
        result = contract.import_ci_baseline(**args)
        self.assertEqual(123, result["artifact"]["id"])
        self.assertEqual(self.commit, result["provenance"]["headSha"])

    def test_historical_archive_digest_and_extracted_xml_mutation_are_rejected(self):
        for task in self.plan["tasks"]:
            xml(self.report_path(task), self.pairs(task))
        args = self.import_inputs()
        archive = args["archive"]
        body = archive.read_bytes()
        archive.write_bytes(body + b"not part of the GitHub artifact")
        with self.assertRaisesRegex(ValueError, "CI_ARTIFACT_DIGEST"):
            contract.import_ci_baseline(**args)
        archive.write_bytes(body)
        path = self.report_path(self.plan["tasks"][0])
        path.write_bytes(path.read_bytes().replace(b"sdk[35][1: b]", b"sdk[36][1: b]"))
        with self.assertRaisesRegex(ValueError, "CI_ARCHIVE_XML_CHANGED"):
            contract.import_ci_baseline(**args)
        self.assertEqual("awaiting-real-full-ci", contract.load(self.baseline)["status"])

    def test_historical_missing_git_kotlin_helper_and_wrong_artifact_run_fail(self):
        for task in self.plan["tasks"]:
            xml(self.report_path(task), self.pairs(task))
        args = self.import_inputs()
        metadata = contract.load(args["artifact"])
        metadata["workflow_run"]["id"] = 54321
        contract.write_json(args["artifact"], metadata)
        with self.assertRaisesRegex(ValueError, "CI_ARTIFACT_PROVENANCE"):
            contract.import_ci_baseline(**args)
        (self.root / "core/policy/src/test/kotlin/two/KotlinHelper.kt").unlink()
        self.refresh_plan()
        with self.assertRaisesRegex(ValueError, "HISTORICAL_SOURCE_SET"):
            self.imported()

    def test_historical_rust_required_and_true_full_environment_are_mandatory(self):
        for task in self.plan["tasks"]:
            xml(self.report_path(task), self.pairs(task))
        args = self.import_inputs()
        log = Path(args["run_log"])
        original = log.read_text(encoding="utf-8")
        for before, after in (("RUST_HOST: true", "RUST_HOST: false"),
                              ("MODE: full", "MODE: source-only"),
                              ("-Pdsha.rust.required=true", "-Pdsha.rust.required=false")):
            log.write_text(original.replace(before, after), encoding="utf-8")
            with self.subTest(after=after), self.assertRaisesRegex(ValueError, "CI_UNIT_LOG_"):
                contract.import_ci_baseline(**args)

    def test_rust_receipt_identity_and_post_begin_library_bytes_are_pinned(self):
        receipt = contract.load(self.rust_receipt)
        for key, value in (("commit", "0" * 40), ("runId", "wrong"), ("runAttempt", "2")):
            contract.write_json(self.rust_receipt, {**receipt, key: value})
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "RUST_RECEIPT_IDENTITY"):
                self.begin()
        contract.write_json(self.rust_receipt, receipt)
        self.begin()
        self.library.write_bytes(b"changed actual library bytes")
        with self.assertRaisesRegex(ValueError, "TASK_RUST_LIBRARY"):
            self.start(self.plan["tasks"][0])

    def test_compile_proof_is_mandatory_in_api_and_real_cli(self):
        self.begin()
        task = self.plan["tasks"][0]
        with self.assertRaisesRegex(ValueError, "COMPILE_PROOF_REQUIRED"):
            self.start(task, compile_proof=None)
        self.assertFalse(contract.task_record(self.state, task["task"], "start").exists())
        result = subprocess.run([sys.executable, str(TOOLS / "unit_test_contract.py"), "task-begin",
                                 "--root", str(self.root), "--state", str(self.state), "--task", task["task"],
                                 "--source-only", "false", "--filters-json", "[]", "--rust-required", "true",
                                 "--rust-library", str(self.library), "--includes-json", "[]", "--excludes-json", "[]",
                                 "--scan-for-test-classes", "true", "--test-classes-dirs-json", "[]"],
                                capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn("--compile-proof", result.stderr)

    def test_compile_proof_commit_run_task_plan_and_time_bindings_are_exact(self):
        self.begin()
        task = self.plan["tasks"][0]
        path = self.proof(task)
        original = contract.load(path)
        for field, value in (("commit", "0" * 40), ("runId", "other-run"), ("runAttempt", "2"),
                             ("planSha256", "0" * 64), ("testTask", ":wrong:test"),
                             ("createdNs", 0), ("createdNs", contract.time.time_ns() + 10**12),
                             ("schema", 2), ("kind", "summary-not-proof")):
            candidate = copy.deepcopy(original)
            candidate[field] = value
            contract.write_json(path, candidate)
            with self.subTest(field=field, value=value), self.assertRaisesRegex(ValueError, "COMPILE_PROOF_"):
                self.start(task)
            self.assertFalse(contract.task_record(self.state, task["task"], "start").exists())
        contract.write_json(path, original)
        self.start(task)

    def test_compile_proof_inventory_roots_and_kotlin_extension_cannot_be_omitted(self):
        self.begin()
        task = self.plan["tasks"][1]
        path = self.proof(task)
        original = contract.load(path)
        mutations = []
        value = copy.deepcopy(original)
        value["inventory"]["sources"].pop()
        mutations.append((value, "COMPILE_INVENTORY_MISMATCH"))
        value = copy.deepcopy(original)
        value["sourceRoots"] = [row for row in value["sourceRoots"] if row["api"] != "kotlin-extension"]
        mutations.append((value, "COMPILE_ROOTS_MISSING"))
        value = copy.deepcopy(original)
        value["sourceRoots"][0]["roots"] = ["out/external-code-root"]
        mutations.append((value, "COMPILE_ROOT_MISMATCH"))
        value = copy.deepcopy(original)
        value["sourceRoots"].append(copy.deepcopy(value["sourceRoots"][0]))
        mutations.append((value, "COMPILE_ROOT_DUPLICATE"))
        value = copy.deepcopy(original)
        value["sourceRoots"][0] = "not-a-root-object"
        mutations.append((value, "COMPILE_ROOT_SCHEMA"))
        for candidate, expected in mutations:
            contract.write_json(path, candidate)
            with self.subTest(expected=expected), self.assertRaisesRegex(ValueError, expected):
                self.start(task)
        contract.write_json(path, original)
        self.start(task)

    def test_compile_effective_inputs_reject_omitted_production_extra_and_changed_bytes(self):
        self.begin()
        task = self.plan["tasks"][0]
        path = self.proof(task)
        original = contract.load(path)
        main = next(row for row in original["compileTasks"] if row["sourceSets"] == ["main"])
        extra = self.put("out/extra-production.java", "class UnreviewedProduction {}\n")
        candidates = []
        value = copy.deepcopy(original)
        value["compileTasks"] = [row for row in value["compileTasks"] if row["sourceSets"] != ["main"]]
        candidates.append((value, "COMPILE_TASK_MISSING"))
        value = copy.deepcopy(original)
        value["compileTasks"][0]["sources"] = []
        value["compileTasks"][0]["execution"] = None
        candidates.append((value, "COMPILE_EFFECTIVE_SOURCES"))
        value = copy.deepcopy(original)
        value["compileTasks"][0]["sources"].append({"path": extra.relative_to(self.root).as_posix(),
                                                   "sha256": contract.digest(extra), "origin": "source"})
        candidates.append((value, "COMPILE_EFFECTIVE_SOURCES"))
        value = copy.deepcopy(original)
        value["compileTasks"][0]["sources"][0]["sha256"] = "0" * 64
        candidates.append((value, "COMPILE_FILE_CHANGED"))
        value = copy.deepcopy(original)
        value["compileTasks"][0]["sources"].append(copy.deepcopy(value["compileTasks"][0]["sources"][0]))
        candidates.append((value, "COMPILE_FILE_DUPLICATE"))
        value = copy.deepcopy(original)
        value["compileTasks"].append(copy.deepcopy(value["compileTasks"][0]))
        candidates.append((value, "COMPILE_TASK_DUPLICATE"))
        for candidate, expected in candidates:
            contract.write_json(path, candidate)
            with self.subTest(expected=expected), self.assertRaisesRegex(ValueError, expected):
                self.start(task)
        contract.write_json(path, original)
        self.assertTrue(main["sources"])
        self.start(task)

    def test_compile_execution_receipt_required_for_nonempty_production_sources(self):
        self.begin()
        task = self.plan["tasks"][0]
        path = self.proof(task)
        original = contract.load(path)
        value = copy.deepcopy(original)
        value["compileTasks"][0]["execution"] = None
        contract.write_json(path, value)
        with self.assertRaisesRegex(ValueError, "COMPILE_EXECUTION_REQUIRED"):
            self.start(task)
        contract.write_json(path, original)
        reference = original["compileTasks"][0]["execution"]
        execution = self.state / reference["file"]
        raw = execution.read_bytes()
        self.assertEqual(contract.compile_execution_file(self.state, original["compileTasks"][0]["task"]), execution)
        execution.unlink()
        with self.assertRaisesRegex(ValueError, "COMPILE_EXECUTION_HASH"):
            self.start(task)
        execution.write_bytes(raw)
        self.start(task)

    def test_compile_execution_receipt_checks_binding_success_time_and_pre_post_sources(self):
        self.begin()
        task = self.plan["tasks"][0]
        proof_path = self.proof(task)
        original_proof = contract.load(proof_path)
        row = original_proof["compileTasks"][0]
        execution_path = self.state / row["execution"]["file"]
        original = contract.load(execution_path)
        mutations = [("runId", "wrong", "COMPILE_EXECUTION_BINDING"),
                     ("compileTask", ":other:compileJava", "COMPILE_EXECUTION_BINDING"),
                     ("module", ":other", "COMPILE_EXECUTION_BINDING"),
                     ("outcome", "FROM-CACHE", "COMPILE_EXECUTION_BINDING"),
                     ("finishedNs", 0, "COMPILE_EXECUTION_TIME"),
                     ("after", {}, "COMPILE_EXECUTION_SOURCES"),
                     ("before", {}, "COMPILE_EXECUTION_SOURCES")]
        for field, value, expected in mutations:
            candidate = copy.deepcopy(original)
            candidate[field] = value
            contract.write_json(execution_path, candidate)
            changed_proof = copy.deepcopy(original_proof)
            changed_proof["compileTasks"][0]["execution"]["sha256"] = contract.digest(execution_path)
            contract.write_json(proof_path, changed_proof)
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, expected):
                self.start(task)
        contract.write_json(execution_path, original)
        contract.write_json(proof_path, original_proof)
        self.start(task)

    def test_compile_proof_and_receipt_bytes_cannot_change_between_start_end_and_verify(self):
        self.finish()
        self.freeze()
        task = self.plan["tasks"][0]
        proof_path = self.proof(task)
        raw = proof_path.read_bytes()
        proof_path.write_bytes(raw + b"\n")
        with self.assertRaisesRegex(ValueError, "COMPILE_PROOF_CHANGED"):
            self.verify()
        proof_path.write_bytes(raw)
        proof = contract.load(proof_path)
        execution = self.state / proof["compileTasks"][0]["execution"]["file"]
        receipt_raw = execution.read_bytes()
        execution.write_bytes(receipt_raw + b"\n")
        with self.assertRaisesRegex(ValueError, "COMPILE_EXECUTION_HASH"):
            self.verify(archived=True)
        execution.write_bytes(receipt_raw)
        self.assertEqual("matched", self.verify()["baselineStatus"])

    def test_compile_proof_generated_files_bind_exact_android_owners_and_namespace(self):
        self.configure_android_fixture()
        self.begin()
        task = self.plan["tasks"][0]
        path = self.android_proof(task)
        original = contract.load(path)
        # 构造的三个 generator 的真实临时文件只用于证明校验器按精确集合工作。
        self.assertEqual(3, len(original["compileTasks"][0]["generators"]))
        mutations = []
        for index in range(3):
            value = copy.deepcopy(original)
            value["compileTasks"][0]["generators"][index]["task"] = ":app:unreviewedGenerator"
            mutations.append((value, "COMPILE_GENERATOR_OWNER"))
            value = copy.deepcopy(original)
            del value["compileTasks"][0]["generators"][index]
            mutations.append((value, "COMPILE_GENERATOR_MISSING"))
        value = copy.deepcopy(original)
        value["compileTasks"][0]["component"]["namespace"] = "wrong.namespace"
        mutations.append((value, "COMPILE_BUILD_CONFIG_NAMESPACE"))
        value = copy.deepcopy(original)
        value["compileTasks"][0]["generators"][0]["root"] = "app/build"
        mutations.append((value, "COMPILE_GENERATOR_FILES"))
        for candidate, expected in mutations:
            contract.write_json(path, candidate)
            with self.subTest(expected=expected), self.assertRaisesRegex(ValueError, expected):
                self.start(task)
        contract.write_json(path, original)
        self.start(task)

    def test_compile_proof_component_and_generator_cannot_jointly_invent_a_producer(self):
        self.configure_android_fixture()
        self.begin()
        task = self.plan["tasks"][0]
        path = self.android_proof(task)
        original = contract.load(path)
        for key, old in (("aidlTask", ":app:compileStandardDebugAidl"),
                         ("buildConfigTask", ":app:generateStandardDebugBuildConfig")):
            candidate = copy.deepcopy(original)
            for row in candidate["compileTasks"]:
                if row["component"]["name"] != "standardDebug":
                    continue
                row["component"][key] = ":app:unreviewedProducer"
                for generator in row["generators"]:
                    if generator["task"] == old:
                        generator["task"] = ":app:unreviewedProducer"
            contract.write_json(path, candidate)
            with self.subTest(role=key), self.assertRaisesRegex(ValueError, "COMPILE_COMPONENT_TASK_ROLE"):
                self.start(task)
        candidate = copy.deepcopy(original)
        for row in candidate["compileTasks"]:
            if row["component"]["name"] == "standardDebug":
                row["component"]["buildConfigTask"] = None
                row["generators"] = [owner for owner in row["generators"]
                                     if owner["task"] != ":app:generateStandardDebugBuildConfig"]
        contract.write_json(path, candidate)
        with self.assertRaisesRegex(ValueError, "COMPILE_COMPONENT_TASK_ROLE"):
            self.start(task)
        contract.write_json(path, original)
        self.start(task)

    def test_compile_proof_android_static_and_extension_roots_are_mandatory(self):
        self.configure_android_fixture()
        self.begin()
        task = self.plan["tasks"][0]
        path = self.android_proof(task)
        original = contract.load(path)
        value = copy.deepcopy(original)
        value["compileTasks"][0]["component"]["javaStatic"] = ["out/replacement"]
        contract.write_json(path, value)
        with self.assertRaisesRegex(ValueError, "COMPILE_COMPONENT_ROOTS"):
            self.start(task)
        value = copy.deepcopy(original)
        value["sourceRoots"] = [row for row in value["sourceRoots"] if row["api"] != "kotlin-extension"]
        contract.write_json(path, value)
        with self.assertRaisesRegex(ValueError, "COMPILE_ROOTS_MISSING"):
            self.start(task)
        contract.write_json(path, original)
        self.start(task)

    def test_archived_compile_proof_keeps_exact_generated_and_execution_evidence_without_old_outputs(self):
        self.configure_android_fixture()
        self.begin()
        for task in self.plan["tasks"]:
            if task["module"] == ":app":
                self.android_proof(task)
            self.start(task)
            xml(self.report_path(task), self.pairs(task))
            self.end(task)
        self.freeze()
        app = self.plan["tasks"][0]
        proof = contract.load(self.proof(app))
        generated = {item["path"] for row in proof["compileTasks"] for owner in row["generators"] for item in owner["files"]}
        for name in generated:
            target = self.root / name
            self.assertTrue(target.resolve().is_relative_to(self.root / "app/build/generated"))
            target.unlink()
        self.library.unlink()
        self.rust_receipt.unlink()
        self.assertEqual("matched", self.verify(archived=True)["baselineStatus"])
        with self.assertRaisesRegex(ValueError, "COMPILE_FILE_CHANGED"):
            self.verify()
        execution = self.state / proof["compileTasks"][0]["execution"]["file"]
        original = contract.load(execution)
        original["after"]["sources"] = []
        contract.write_json(execution, original)
        with self.assertRaisesRegex(ValueError, "COMPILE_EXECUTION_HASH"):
            self.verify(archived=True)

    def test_rust_library_persistent_change_after_last_start_is_rejected_at_end(self):
        self.begin()
        for task in self.plan["tasks"][:-1]:
            self.start(task)
            xml(self.report_path(task), self.pairs(task))
            self.end(task)
        last = self.plan["tasks"][-1]
        self.start(last)
        original = self.library.read_bytes()
        self.library.write_bytes(original[:-1] + bytes([original[-1] ^ 1]))
        self.assertNotEqual(contract.load(self.rust_receipt)["artifact"]["sha256"], contract.digest(self.library))
        xml(self.report_path(last), self.pairs(last))
        with self.assertRaisesRegex(ValueError, "TASK_RUST_LIBRARY"):
            self.end(last)

    def test_rust_library_persistent_change_after_end_is_rejected_by_live_verify(self):
        self.finish()
        self.freeze()
        original = self.library.read_bytes()
        self.library.write_bytes(original[:-1] + bytes([original[-1] ^ 1]))
        self.assertNotEqual(contract.load(self.rust_receipt)["artifact"]["sha256"], contract.digest(self.library))
        with self.assertRaisesRegex(ValueError, "TASK_RUST_LIBRARY"):
            self.verify()

    def test_rust_live_verify_rejects_missing_non_regular_and_size_changes(self):
        self.finish()
        self.freeze()
        original = self.library.read_bytes()
        self.library.unlink()
        with self.assertRaisesRegex(ValueError, "TASK_RUST_LIBRARY"):
            self.verify()
        self.library.mkdir()
        with self.assertRaisesRegex(ValueError, "TASK_RUST_LIBRARY"):
            self.verify()
        self.library.rmdir()
        self.library.write_bytes(original + b"changed size")
        with self.assertRaisesRegex(ValueError, "TASK_RUST_LIBRARY"):
            self.verify()
        self.library.write_bytes(original)
        self.assertEqual("matched", self.verify()["baselineStatus"])

    def test_rust_reopen_rejects_final_and_ancestor_links_even_with_identical_bytes(self):
        self.finish()
        self.freeze()
        original = self.library.read_bytes()
        target = self.root / "out/identical-lib.so"
        target.write_bytes(original)
        self.library.unlink()
        self.library.symlink_to(target)
        with self.assertRaisesRegex(ValueError, "TASK_RUST_LIBRARY"):
            self.verify()
        self.library.unlink()
        self.library.write_bytes(original)
        parent = self.library.parent
        moved = self.root / "out/rust-moved"
        self.assertEqual(self.root / "out/rust", parent.resolve())
        self.assertEqual(self.root / "out/rust-moved", moved.resolve())
        parent.rename(moved)
        parent.symlink_to(moved, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "TASK_RUST_LIBRARY"):
            self.verify()

    def test_archived_rust_checks_saved_start_end_without_original_library(self):
        self.finish()
        self.freeze()
        expected = contract.expected_rust_library(contract.load(self.state / "run.json"))
        for task in self.plan["tasks"]:
            start = contract.load(contract.task_record(self.state, task["task"], "start"))
            end = contract.load(contract.task_record(self.state, task["task"], "end"))
            self.assertEqual(expected, start["rustLibrary"])
            self.assertEqual(expected, end["rustLibrary"])
        self.library.unlink()
        self.rust_receipt.unlink()
        self.assertEqual("matched", self.verify(archived=True)["baselineStatus"])
        with self.assertRaisesRegex(ValueError, "TASK_RUST_LIBRARY"):
            self.verify()
        end_path = contract.task_record(self.state, self.plan["tasks"][-1]["task"], "end")
        original = contract.load(end_path)
        for field, value in (("path", "/different/runner/libdsha_rs.so"), ("fileType", "symlink"),
                             ("size", expected["size"] + 1), ("sha256", "0" * 64), (None, None)):
            changed = copy.deepcopy(original)
            if field is None:
                del changed["rustLibrary"]
            else:
                changed["rustLibrary"][field] = value
            contract.write_json(end_path, changed)
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "TASK_RUST_EVIDENCE"):
                self.verify(archived=True)
        contract.write_json(end_path, original)
        run_path = self.state / "run.json"
        run = contract.load(run_path)
        run["rust"]["receipt"]["runId"] = "wrong"
        contract.write_json(run_path, run)
        with self.assertRaisesRegex(ValueError, "RUST_RECEIPT_IDENTITY"):
            self.verify(archived=True)

    def test_summary_only_cli_title_does_not_claim_full_fixed_input_aggregate(self):
        report = self.report_path(self.plan["tasks"][0])
        xml(report, self.pairs(self.plan["tasks"][0]))
        for mode in ("full", "source-only"):
            summary = self.root / ("out/legacy-" + mode + ".md")
            output = self.root / ("out/legacy-" + mode + ".json")
            result = subprocess.run([sys.executable, str(TOOLS / "verify-unit-results.py"),
                                     "--mode", mode, "--reports", str(report.parent), "--output", str(output)],
                                    capture_output=True, text=True,
                                    env={**os.environ, "GITHUB_STEP_SUMMARY": str(summary)})
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIs(False, contract.load(output)["aggregate"])
            rendered = summary.read_text(encoding="utf-8").lower()
            self.assertIn("report summary", rendered)
            self.assertNotIn("with fixed inputs", rendered)
            self.assertNotIn("full repository unit tests", rendered)

    def test_kotlin_top_level_helper_facade_is_part_of_compiled_inventory(self):
        self.put("core/policy/src/test/kotlin/two/TopLevel.kt", "package two\nfun helperAnswer() = 42\n")
        self.commit_candidate()
        self.refresh_plan()
        self.begin()
        task = self.plan["tasks"][1]
        facade = self.class_dir(task) / "two/TopLevelKt.class"
        self.assertTrue(facade.is_file())
        facade.unlink()
        with self.assertRaisesRegex(ValueError, "SOURCE_CLASS_NOT_COMPILED"):
            self.start(task)

    def test_historical_fixture_src_test_roots_outside_modules_do_not_pollute_baseline(self):
        self.put("tools/fixtures/native-presentation/src/test/java/hidden/TrapTest.java",
                 "package hidden; @org.junit.Ignore public class TrapTest { @org.junit.Test public void trap() {} }")
        self.git("add", "tools/fixtures")
        self.commit_candidate()
        for task in self.plan["tasks"]:
            xml(self.report_path(task), self.pairs(task))
        result = self.imported()
        self.assertFalse(any("fixtures/" in source["path"] for source in result["sources"]))
        self.assertEqual(len(self.plan["sources"]), len(result["sources"]))

    def test_begin_rejects_dirty_resource_and_gradle_before_cleaning_reports(self):
        task = self.plan["tasks"][0]
        report = self.report_path(task)
        xml(report, [("old.Report", "mustRemain")])
        old_sha = contract.digest(report)
        for relative in ("app/src/main/res/values/strings.xml", "build.gradle"):
            path = self.root / relative
            original = path.read_bytes()
            path.write_bytes(original + b"\nchanged tracked input\n")
            # plan 仍可供 dirty 调查；完整 begin 必须拒绝且不能先清报告。
            self.refresh_plan()
            with self.subTest(relative=relative), self.assertRaisesRegex(ValueError, "WORKTREE_DIRTY"):
                self.begin()
            self.assertEqual(old_sha, contract.digest(report))
            self.assertFalse(self.state.exists())
            path.write_bytes(original)

    def test_mid_run_resource_and_gradle_drift_invalidate_old_reports(self):
        self.finish()
        for relative in ("app/src/main/res/values/strings.xml", "build.gradle"):
            path = self.root / relative
            original = path.read_bytes()
            path.write_bytes(original + b"\nchanged after task completion\n")
            code = "PLAN_DRIFT" if "/res/" in relative else "WORKTREE_DIRTY"
            with self.subTest(relative=relative), self.assertRaisesRegex(ValueError, code):
                self.verify()
            path.write_bytes(original)
        self.freeze()
        self.assertEqual("matched", self.verify()["baselineStatus"])

    def test_untracked_and_ignored_source_set_inputs_never_clean_old_reports(self):
        report = self.report_path(self.plan["tasks"][0])
        xml(report, [("old.Report", "mustRemain")])
        old_sha = contract.digest(report)
        candidates = (
            "app/src/test/resources/robolectric.properties",
            "app/src/main/res/xml/untracked.xml",
            "app/src/main/AndroidManifest.xml",
            "app/src/test/fixtures/case.txt",
            "core/policy/src/main/aidl/example/Probe.aidl",
            "core/policy/src/main/cpp/probe.cpp",
            "core/policy/src/main/jni/probe.c",
            "core/policy/src/main/jniLibs/x86/libfixture.so",
            "core/policy/src/main/shaders/probe.glsl",
            "core/policy/src/main/assets/unowned.bin",
            "app/src/test/assets/unowned.bin",
        )
        for relative in candidates:
            with self.subTest(relative=relative):
                path = self.put(relative, "explicit untracked/ignored input fixture\n")
                if relative.endswith("robolectric.properties"):
                    self.assertEqual(relative, self.git("check-ignore", relative).strip())
                self.assertEqual("", self.git("diff", "--name-only", "HEAD"))
                self.refresh_plan()
                self.assertIn(relative, {item["path"] for item in self.plan["resourceInputs"]})
                with self.assertRaisesRegex(ValueError, "INPUT_NOT_IN_COMMIT"):
                    self.begin()
                self.assertFalse(self.state.exists())
                self.assertEqual(old_sha, contract.digest(report))
                path.unlink()

    def test_new_ignored_and_untracked_resources_after_run_invalidate_reports(self):
        self.finish()
        self.freeze()
        report = self.report_path(self.plan["tasks"][0])
        old_sha = contract.digest(report)
        for relative in ("app/src/test/resources/robolectric.properties",
                         "app/src/main/res/xml/untracked.xml",
                         "app/src/main/AndroidManifest.xml",
                         "core/policy/src/main/aidl/Probe.aidl"):
            with self.subTest(relative=relative):
                path = self.put(relative, "new source-set input\n")
                self.assertEqual("", self.git("diff", "--name-only", "HEAD"))
                with self.assertRaisesRegex(ValueError, "PLAN_DRIFT"):
                    self.verify()
                path.write_text("changed source-set input\n", encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "PLAN_DRIFT"):
                    self.verify()
                self.assertEqual(old_sha, contract.digest(report))
                path.unlink()
                self.assertEqual("matched", self.verify()["baselineStatus"])

    def test_committed_ignored_resource_hash_detects_changes_even_when_git_hides_them(self):
        relative = "app/src/test/resources/robolectric.properties"
        path = self.put(relative, "sdk=28\n")
        self.git("add", "-f", relative)
        self.commit_candidate()
        self.refresh_plan()
        self.finish()
        self.freeze()
        self.git("update-index", "--assume-unchanged", relative)
        path.write_text("sdk=35\n", encoding="utf-8")
        self.assertEqual("", self.git("diff", "--name-only", "HEAD"))
        with self.assertRaisesRegex(ValueError, "PLAN_DRIFT"):
            self.verify()

    def test_resource_roots_follow_explicit_source_sets_without_java_files(self):
        original = self.layout
        self.layout = SimpleNamespace(
            modules=[{**row, "sourceSets": ["main", "test", "standard"]} for row in original.modules],
            source_files=original.source_files, test_tasks=original.test_tasks)
        relative = "app/src/standard/resources/flavor.properties"
        self.put(relative, "untracked flavor resource\n")
        self.refresh_plan()
        row = next(item for item in self.plan["resourceInputs"] if item["path"] == relative)
        self.assertEqual("standard", row["sourceSet"])
        with self.assertRaisesRegex(ValueError, "INPUT_NOT_IN_COMMIT"):
            self.begin()

    def test_source_set_resource_symlinks_are_rejected_before_report_cleanup(self):
        outside = tempfile.TemporaryDirectory(prefix="outside resource fixture ")
        self.addCleanup(outside.cleanup)
        external = Path(outside.name) / "robolectric.properties"
        external.write_text("synthetic outside bytes", encoding="utf-8")
        report = self.report_path(self.plan["tasks"][0])
        xml(report, [("old.Report", "mustRemain")])
        old_sha = contract.digest(report)
        directory = self.root / "core/policy/src/test/resources"
        directory.symlink_to(external.parent, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "PATH_SYMLINK"):
            self.begin()
        directory.unlink()
        directory.mkdir()
        (directory / "robolectric.properties").symlink_to(external)
        with self.assertRaisesRegex(ValueError, "PATH_SYMLINK"):
            self.begin()
        self.assertFalse(self.state.exists())
        self.assertEqual(old_sha, contract.digest(report))
        self.assertEqual("synthetic outside bytes", external.read_text(encoding="utf-8"))

    def test_external_outputs_local_properties_and_owned_assets_are_not_resource_inputs(self):
        before = self.plan["resourceInputs"]
        paths = [self.put(relative, "separate input-owner fixture\n") for relative in (
            "out/generated-resource.bin", "app/build/generated/resources/ignored.properties",
            "local.properties", "app/src/main/assets/locked-archive.bin")]
        self.refresh_plan()
        self.assertEqual(before, self.plan["resourceInputs"])
        self.begin()
        self.assertTrue(self.state.is_dir())
        for path in paths:
            self.assertEqual("separate input-owner fixture\n", path.read_text(encoding="utf-8"))

    def test_historical_resource_inventory_and_ignored_overrides_are_commit_bound(self):
        for task in self.plan["tasks"]:
            xml(self.report_path(task), self.pairs(task))
        args = self.import_inputs()
        path = self.put("app/src/test/resources/robolectric.properties", "sdk=35\n")
        self.refresh_plan()
        with self.assertRaisesRegex(ValueError, "HISTORICAL_RESOURCE_SET"):
            contract.import_ci_baseline(**args)
        path.unlink()
        existing = self.root / "app/src/main/res/values/strings.xml"
        existing.write_text("<resources/>\n", encoding="utf-8")
        self.refresh_plan()
        with self.assertRaisesRegex(ValueError, "HISTORICAL_SOURCE_CHANGED"):
            self.imported()

    def test_digest_and_git_blob_hash_stream_real_native_resource_in_bounded_chunks(self):
        path = self.root / "core/policy/src/main/jniLibs/x86/libfixture.so"
        path.parent.mkdir(parents=True)
        payload = b"synthetic native resource, not executable\0" * 90000
        path.write_bytes(payload)
        expected = contract.hashlib.sha256(payload).hexdigest()
        expected_git = contract.hashlib.sha1(b"blob " + str(len(payload)).encode() + b"\0" + payload).hexdigest()
        original_open = Path.open
        sizes = []
        class BoundedReader:
            def __init__(self, stream):
                self.stream = stream
            def __enter__(self):
                self.stream.__enter__()
                return self
            def __exit__(self, *args):
                return self.stream.__exit__(*args)
            def read(self, size=-1):
                self_case.assertGreater(size, 0)
                self_case.assertLessEqual(size, 1024 * 1024)
                sizes.append(size)
                return self.stream.read(size)
        self_case = self
        def bounded_open(target, *args, **kwargs):
            return BoundedReader(original_open(target, *args, **kwargs))
        with patch.object(Path, "open", bounded_open):
            self.assertEqual(expected, contract.digest(path))
            self.assertEqual(expected_git, contract.git_blob_sha(path))
        self.assertGreater(len(sizes), 6)

    def test_kotlin_grouped_and_ambiguous_use_site_annotations_fail_closed_in_memory(self):
        safe = 'package two\nimport org.junit.Test\nclass SharedTest { @Test fun kept() { val text = "@[Ignore Test]" } /* @[Ignore Test] */ }'
        parsed = contract.source_contract(safe, "two/SharedTest.kt")
        self.assertEqual(["kept"], [test["method"] for test in parsed["tests"]])
        for annotation, code in (("@[Ignore Test Category(Slow::class)]", "KOTLIN_ANNOTATION_GROUP_UNSUPPORTED"),
                                 ("@get:[Ignore Test]", "KOTLIN_ANNOTATION_GROUP_UNSUPPORTED"),
                                 ("@get:Test", "KOTLIN_USE_SITE_ANNOTATION_UNSUPPORTED"),
                                 ("@field:Ignore", "KOTLIN_USE_SITE_ANNOTATION_UNSUPPORTED"),
                                 ("@get:`Test Alias`", "KOTLIN_USE_SITE_ANNOTATION_UNSUPPORTED")):
            text = safe[:-1] + annotation + " fun omitted() {} }"
            with self.subTest(annotation=annotation), self.assertRaisesRegex(ValueError, code):
                contract.source_contract(text, "two/SharedTest.kt")
        facade = contract.source_contract('@file:JvmName("Helpers")\npackage two\nfun answer() = 42', "two/HelpersFile.kt")
        self.assertEqual(["two.Helpers"], facade["classes"])

    def test_kotlin_hidden_annotation_group_and_use_site_in_new_commit_never_begin(self):
        self.finish()
        self.freeze()
        report = self.report_path(self.plan["tasks"][1])
        old_sha = contract.digest(report)
        path = self.root / "core/policy/src/test/kotlin/two/SharedTest.kt"
        original = path.read_text(encoding="utf-8").replace(
            "import org.junit.Test\n", "import org.junit.Test\nimport org.junit.Ignore\n"
            "import org.junit.experimental.categories.Category\ninterface Slow\n")
        for annotation, code in (("@[Ignore Test Category(Slow::class)]", "KOTLIN_ANNOTATION_GROUP_UNSUPPORTED"),
                                 ("@get:Test", "KOTLIN_USE_SITE_ANNOTATION_UNSUPPORTED")):
            declaration = "fun omitted() {}" if annotation.startswith("@[") else "val omitted: Unit get() = Unit"
            path.write_text(original.rsplit("}", 1)[0] + annotation + " " + declaration + " }", encoding="utf-8")
            self.commit_candidate()
            self.state = self.root / "out/annotation-candidate"
            with self.subTest(annotation=annotation), self.assertRaisesRegex(ValueError, code):
                self.begin()
            self.assertFalse(self.state.exists())
            self.assertEqual(old_sha, contract.digest(report))

    def test_undeclared_resource_only_variant_cannot_begin_or_be_hidden_by_layout(self):
        report = self.report_path(self.plan["tasks"][0])
        xml(report, [("old.Report", "mustRemain")])
        old_sha = contract.digest(report)
        for relative in ("app/src/testStandardDebug/resources/robolectric.properties",
                         "app/src/testStandardDebug/res/values/overrides.xml",
                         "app/src/testStandardDebug/AndroidManifest.xml",
                         "app/src/testStandardDebug/aidl/Probe.aidl"):
            with self.subTest(relative=relative):
                path = self.put(relative, "untracked resource-only variant input\n")
                with self.assertRaisesRegex(ValueError, "UNDECLARED_RESOURCE_SOURCE_SET"):
                    contract.plan(self.root, self.layout)
                with self.assertRaisesRegex(ValueError, "UNDECLARED_RESOURCE_SOURCE_SET"):
                    self.begin()
                self.assertFalse(self.state.exists())
                self.assertEqual(old_sha, contract.digest(report))
                path.unlink()
        original = self.layout
        self.layout = SimpleNamespace(
            modules=[{**row, "sourceSets": ["main", "test", "testStandardDebug"]}
                     for row in original.modules],
            source_files=original.source_files, test_tasks=original.test_tasks)
        relative = "app/src/testStandardDebug/res/values/overrides.xml"
        self.put(relative, "declared resource before removing its layout entry\n")
        self.refresh_plan()
        self.assertIn(relative, {item["path"] for item in self.plan["resourceInputs"]})
        for module in self.layout.modules:
            module["sourceSets"].remove("testStandardDebug")
        with self.assertRaisesRegex(ValueError, "UNDECLARED_RESOURCE_SOURCE_SET"):
            self.begin()
        self.assertFalse(self.state.exists())
        self.assertEqual(old_sha, contract.digest(report))

    def test_new_undeclared_variant_resources_invalidate_completed_reports(self):
        self.finish()
        self.freeze()
        report = self.report_path(self.plan["tasks"][0])
        old_sha = contract.digest(report)
        path = self.put("app/src/testStandardDebug/resources/robolectric.properties", "sdk=28\n")
        self.assertEqual("", self.git("diff", "--name-only", "HEAD"))
        with self.assertRaisesRegex(ValueError, "UNDECLARED_RESOURCE_SOURCE_SET"):
            self.verify()
        path.write_text("sdk=35\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "UNDECLARED_RESOURCE_SOURCE_SET"):
            self.verify()
        self.assertEqual(old_sha, contract.digest(report))
        path.unlink()
        # 没有实际输入的空目录不是额外源集证据。
        self.assertEqual("matched", self.verify()["baselineStatus"])

    def test_java17_record_helper_and_nested_record_must_be_compiled(self):
        relative = "app/src/test/java/one/RecordHelper.java"
        self.put(relative, "package one; public record RecordHelper(int value) { "
                 "public record Nested(String label) {} }")
        self.commit_candidate()
        self.refresh_plan()
        row = next(source for source in self.plan["sources"] if source["path"] == relative)
        self.assertEqual(["one.RecordHelper", "one.RecordHelper$Nested"], row["classes"])
        self.begin()
        task = self.plan["tasks"][0]
        for owner in row["classes"]:
            path = self.class_dir(task) / (owner.replace(".", "/") + ".class")
            original = path.read_bytes()
            path.unlink()
            with self.subTest(owner=owner), self.assertRaisesRegex(ValueError, "SOURCE_CLASS_NOT_COMPILED"):
                self.start(task)
            path.write_bytes(original)
        self.start(task)

    def test_untracked_kotlin_helper_cannot_be_claimed_as_committed_input(self):
        self.put("core/policy/src/test/kotlin/two/UntrackedHelper.kt", "package two\nclass UntrackedHelper\n")
        self.refresh_plan()
        with self.assertRaisesRegex(ValueError, "INPUT_NOT_IN_COMMIT"):
            self.begin()
        self.assertFalse(self.state.exists())

    def test_public_cli_has_no_bootstrap_bypass(self):
        output = self.root / "out/must-not-exist.json"
        result = subprocess.run([sys.executable, str(TOOLS / "unit_test_contract.py"), "verify",
                                 "--root", str(self.root), "--state", str(self.state), "--run-id", "12345",
                                 "--output", str(output), "--bootstrap"], capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertFalse(output.exists())

    def test_real_cli_plan_and_legacy_api_are_separate(self):
        # 真实 CLI 消费与 production 相同的 SourceLayout schema，无动态 monkeypatch。
        spec = {"schema": 1, "modules": [
            {"path": t["module"], "directory": t["reports"].split("/build/")[0],
             "kind": "jvm", "sourceSets": ["main", "test"], "formatSourceSets": ["test"],
             "testSourceSets": ["test"], "testTask": t["task"].rsplit(":", 1)[1]}
            for t in self.plan["tasks"]]}
        contract.write_json(self.root / "ci/source-layout.json", spec)
        output = self.root / "out/cli-plan.json"
        result = subprocess.run([sys.executable, str(TOOLS / "unit_test_contract.py"), "plan", "--root", str(self.root), "--output", str(output)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(self.plan, contract.load(output))
        legacy = importlib.util.spec_from_file_location("legacy_unit", TOOLS / "verify-unit-results.py")
        module = importlib.util.module_from_spec(legacy)
        legacy.loader.exec_module(module)
        task = self.plan["tasks"][0]
        xml(self.report_path(task), self.pairs(task))
        summary = module.verify(self.report_path(task).parent, "full", require_fixtures=True)
        self.assertEqual(7, summary["flavors"]["Standard"]["requiredFixtureTestsPassed"])
        with self.assertRaisesRegex(ValueError, "CONTRACT_JSON"):
            self.verify()
        from source_layout import SourceLayout
        self.layout = SourceLayout(self.root)
        self.finish()
        self.freeze()
        aggregate = self.root / "out/aggregate-cli.json"
        markdown = self.root / "out/step-summary.md"
        result = subprocess.run([sys.executable, str(TOOLS / "verify-unit-results.py"), "--mode", "full",
                                 "--state", str(self.state), "--root", str(self.root), "--run-id", "12345",
                                 "--output", str(aggregate)], capture_output=True, text=True,
                                env={**os.environ, "GITHUB_STEP_SUMMARY": str(markdown)})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("dsha-unit-aggregate", contract.load(aggregate)["kind"])
        self.assertIn("fresh task evidence", markdown.read_text(encoding="utf-8"))
        legacy_output = self.root / "out/legacy-cli.json"
        result = subprocess.run([sys.executable, str(TOOLS / "verify-unit-results.py"), "--mode", "full",
                                 "--reports", str(self.report_path(task).parent),
                                 "--output", str(legacy_output)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("dsha-unit-summary", contract.load(legacy_output)["kind"])
        self.assertIs(False, contract.load(legacy_output)["aggregate"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
