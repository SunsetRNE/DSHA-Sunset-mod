"""Current source ownership checks; behavioral proofs live in the corresponding JVM tests.

Java 与 Kotlin 共用同一套架构边界。tree 级规则（层依赖、共享 owner、可变静态状态、
可见性引用）同时扫描 `.java` 与 `.kt`；按类定位的规则可解析两种后缀。Kotlin 没有
package-private，这里用 `internal` 作为最接近的可见性，真正阻止 `ui` 访问协作者的是
引用扫描。Kotlin 语法检查是保守近似：拿不准的可变共享状态按违规报告，而不是静默放行。
"""
from pathlib import Path
import json
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/deepseekharness/app"
SOURCE_SUFFIXES = (".java", ".kt")
IMMUTABLE_TYPES = {"boolean", "byte", "short", "int", "long", "float", "double", "char",
                   "String", "Pattern"}
UI_LAYER = r"\bcom\.deepseekharness\.app\.ui\."
BACKUP_CORE_EDGE = r"\bcom\.deepseekharness\.app\.core\.(?:[A-Z]\w*|\*)"


def source_path(name):
    """按类定位 .java 或 .kt；两种实现同时存在属于边界违规。"""
    stem = Path(name)
    if stem.suffix in SOURCE_SUFFIXES:
        stem = stem.with_suffix("")
    matches = [MAIN / (str(stem) + suffix) for suffix in SOURCE_SUFFIXES]
    matches = [path for path in matches if path.is_file()]
    if not matches:
        raise AssertionError("SOURCE_MISSING: " + str(name))
    if len(matches) > 1:
        raise AssertionError("DUPLICATE_SOURCE_IMPLEMENTATION: " + str(name))
    return matches[0]


def source(name):
    return source_path(name).read_text(encoding="utf-8")


def source_files(package=None):
    base = MAIN / package if package else MAIN
    return sorted(path for path in base.rglob("*") if path.suffix in SOURCE_SUFFIXES)


def without_comments(text, kotlin=False):
    """删除注释；Kotlin 允许嵌套块注释，Java 不允许。字符串字面量保持原样。"""
    out = []
    at = 0
    size = len(text)
    depth = 0
    while at < size:
        char = text[at]
        following = text[at + 1] if at + 1 < size else ""
        if depth:
            if kotlin and char == "/" and following == "*":
                depth += 1
                out.append("  ")
                at += 2
                continue
            if char == "*" and following == "/":
                depth -= 1
                out.append("  ")
                at += 2
                continue
            out.append("\n" if char == "\n" else " ")
            at += 1
            continue
        if char == "/" and following == "/":
            while at < size and text[at] != "\n":
                out.append(" ")
                at += 1
            continue
        if char == "/" and following == "*":
            depth = 1
            out.append("  ")
            at += 2
            continue
        out.append(char)
        at += 1
    return "".join(out)


def without_literals(text):
    """把字符串/字符字面量替换为空格，保留换行，供花括号深度与声明作用域判断。"""
    out = []
    at = 0
    size = len(text)
    while at < size:
        char = text[at]
        if char == '"' and text.startswith('"""', at):
            end = text.find('"""', at + 3)
            end = size if end < 0 else end + 3
            out.append("".join("\n" if text[seam] == "\n" else " " for seam in range(at, end)))
            at = end
            continue
        if char in "\"'":
            end = at + 1
            while end < size:
                if text[end] == "\\":
                    end += 2
                    continue
                if text[end] == char:
                    end += 1
                    break
                end += 1
            out.append("".join("\n" if text[seam] == "\n" else " " for seam in range(at, end)))
            at = end
            continue
        out.append(char)
        at += 1
    return "".join(out)


def code_text(path):
    return without_comments(path.read_text(encoding="utf-8"), kotlin=path.suffix == ".kt")


def layer_violations(sources, pattern):
    """sources 为 (显示名, 正文) 序列；返回 (显示名, 命中片段)。"""
    compiled = re.compile(pattern)
    found = []
    for name, text in sources:
        clean = without_comments(text, kotlin=name.endswith(".kt"))
        for match in compiled.finditer(clean):
            found.append((name, match.group(0)))
    return found


def backup_core_edges(sources):
    return layer_violations(sources, BACKUP_CORE_EDGE)


def ui_collaborator_references(sources, collaborators):
    found = []
    for name, text in sources:
        clean = without_comments(text, kotlin=name.endswith(".kt"))
        for collaborator in collaborators:
            if "core." + collaborator in clean:
                found.append((name, collaborator))
    return found


def ui_text_in_stop_algorithms(sources):
    """停止判据不得渲染 UiText；.kt 与 .java 一律按正文检查。"""
    return [name for name, text in sources
            if "UiText" in without_comments(text, kotlin=name.endswith(".kt"))]


def parallel_implementations(relative_names):
    """同一相对路径同时存在 .java/.kt 时返回重复对。"""
    by_stem = {}
    for name in relative_names:
        path = Path(name)
        if path.suffix not in SOURCE_SUFFIXES:
            continue
        by_stem.setdefault(str(path.with_suffix("")), set()).add(path.suffix)
    return {stem: suffixes for stem, suffixes in by_stem.items() if len(suffixes) > 1}


def construction_count(text, name, kotlin):
    """实例化次数；Java 只认 new，Kotlin 排除类/构造函数声明。"""
    clean = without_comments(text, kotlin=kotlin)
    if not kotlin:
        return len(re.findall(r"\bnew\s+(?:[\w$.]+\.)?" + re.escape(name) + r"\s*\(", clean))
    count = 0
    for match in re.finditer(r"\b(?:[\w$.]+\.)?" + re.escape(name) + r"\s*\(", clean):
        prefix = clean[max(0, match.start() - 80):match.start()]
        if re.search(r"\b(?:class|constructor)\s+$", prefix):
            continue
        count += 1
    return count


def constructor_references(text, name, kotlin):
    clean = without_comments(text, kotlin=kotlin)
    if kotlin:
        return len(re.findall(r"::\s*" + re.escape(name) + r"\b", clean))
    return len(re.findall(r"\b" + re.escape(name) + r"\s*::\s*new\b", clean))


# ---------------- Kotlin 声明扫描 ----------------

CLASS_DECL = re.compile(
    r"^(?P<mods>(?:(?:public|internal|private|protected|open|abstract|sealed|data|value|"
    r"inline|final|enum|annotation|expect|actual)\s+)*)class\s+(?P<name>[A-Za-z_]\w*)\b")
PROPERTY_DECL = re.compile(
    r"^(?P<anns>(?:@\w+(?:\([^)]*\))?\s+)*)"
    r"(?P<mods>(?:(?:public|private|protected|internal|lateinit|const|override|final|open|"
    r"abstract|inline)\s+)*)"
    r"(?P<kw>val|var)\s+(?P<name>[A-Za-z_]\w*)(?P<rest>.*)$")


def declaration_scopes(text):
    """每行的 (花括号深度, 最内层块类型)。块类型只区分 object/companion object 与其它。"""
    masked = without_literals(without_comments(text, kotlin=True))
    stack = []
    scopes = []
    for line in masked.split("\n"):
        scopes.append((len(stack), stack[-1] if stack else None))
        for match in re.finditer(r"\{", line):
            prefix = line[:match.start()]
            kind = "object" if re.search(r"\b(?:companion\s+)?object\b", prefix) else "other"
            stack.append(kind)
        for _ in range(line.count("}")):
            if stack:
                stack.pop()
    return scopes


def _property_type_and_rhs(rest):
    value = rest.strip()
    if value.startswith(":"):
        value = value[1:].strip()
        if "=" in value:
            declared, rhs = value.split("=", 1)
            return declared.strip(), rhs.strip()
        match = re.search(r"\s+by\s+", value)
        if match:
            return value[:match.start()].strip(), ""
        return value.strip(), ""
    if value.startswith("="):
        return "", value[1:].strip()
    match = re.search(r"\s+by\s+", value)
    if match:
        return "", ""
    return "", ""


def shared_properties(text):
    """返回 (name, kw, type, rhs, mods, annotations) 列表；只含文件级/object 级属性。"""
    clean = without_comments(text, kotlin=True)
    scopes = declaration_scopes(text)
    properties = []
    pending = ""
    for (depth, kind), line in zip(scopes, clean.split("\n")):
        trimmed = line.strip()
        if not trimmed:
            continue
        if trimmed.startswith("@") and not PROPERTY_DECL.match(trimmed):
            pending += trimmed + " "
            continue
        match = PROPERTY_DECL.match(trimmed)
        if not match:
            pending = ""
            continue
        if depth == 0 or kind == "object":
            declared, rhs = _property_type_and_rhs(match.group("rest"))
            properties.append((match.group("name"), match.group("kw"), declared, rhs,
                               match.group("mods") + " " + match.group("anns") + pending))
        pending = ""
    return properties


def _immutable(property_row):
    name, keyword, declared, rhs, modifiers = property_row
    if "const" in modifiers.split():
        return keyword == "val"
    if keyword != "val":
        return False
    if declared:
        return declared.rstrip("?").rsplit(".", 1)[-1] in IMMUTABLE_TYPES
    if not rhs:
        return False
    if rhs[0] in "\"'" or re.match(r"[-+]?[0-9]", rhs):
        return True
    return rhs.startswith("Pattern.compile(")


def mutable_shared_state(text):
    """Kotlin 的静态可变状态：top-level/object/companion 的 var，或非不可变类型的 val。"""
    return [row[0] for row in shared_properties(text) if not _immutable(row)]


def kotlin_visibility_problems(text, name):
    """协作者类在 Kotlin 中应使用 internal（最接近 package-private）且不可 open/abstract。"""
    clean = without_comments(text, kotlin=True)
    match = re.search(r"(?m)^(?P<mods>(?:(?:public|internal|private|protected|open|abstract|"
                      r"sealed|data|value|inline|final|enum|annotation|expect|actual)\s+)*)"
                      r"class\s+" + re.escape(name) + r"\b", clean)
    if match is None:
        return ["KOTLIN_CLASS_MISSING"]
    modifiers = match.group("mods").split()
    problems = []
    if "internal" not in modifiers:
        problems.append("KOTLIN_CLASS_NOT_INTERNAL")
    if {"open", "abstract", "sealed"} & set(modifiers):
        problems.append("KOTLIN_CLASS_NOT_FINAL")
    return problems


def kotlin_shared_runtime_ports(text):
    """RuntimeHostPorts.kt 不得提供共享 owner/状态（静态属性或 shared() 入口）。"""
    problems = []
    for row in shared_properties(text):
        name, keyword, declared, rhs, _ = row
        if "RuntimeHostPorts" in declared or "RuntimeHostPorts" in rhs \
                or declared.rstrip("?").rsplit(".", 1)[-1] in ("Provider", "Settings"):
            problems.append("KOTLIN_SHARED_RUNTIME_PORTS:" + name)
    if re.search(r"\bfun\s+shared\s*\(", without_comments(text, kotlin=True)):
        problems.append("KOTLIN_SHARED_RUNTIME_PORTS_METHOD")
    return problems


def kotlin_mutable_harness_service(text):
    problems = []
    for row in shared_properties(text):
        name, keyword, declared, rhs, _ = row
        if keyword == "var" and ("HarnessService" in declared or "HarnessService" in rhs):
            problems.append("KOTLIN_MUTABLE_HARNESS_SERVICE:" + name)
    return problems


def kotlin_public_activity_static(text):
    """Kotlin 没有 static；文件级/object 级公开 var 或 @JvmField 会编译成公开静态字段。"""
    problems = []
    for name, keyword, declared, rhs, modifiers in shared_properties(text):
        is_activity = bool(re.search(r"\w*Activity\b", declared)) \
            or bool(re.search(r"\w*Activity\b", rhs))
        if keyword == "var" and is_activity and "private" not in modifiers.split() \
                and "internal" not in modifiers.split():
            problems.append("KOTLIN_PUBLIC_STATIC_ACTIVITY:" + name)
    return problems


def runtime_hosts_owner_problems(application, kotlin):
    problems = []
    if "RuntimeHostPorts.Owner" not in application:
        problems.append("RUNTIME_HOSTS_OWNER_MISSING")
    if construction_count(application, "RuntimeHostPorts", kotlin) != 1:
        problems.append("RUNTIME_HOSTS_OWNER_COUNT")
    if construction_count(application, "HarnessSessionState", kotlin) != 1:
        problems.append("HARNESS_SESSION_STATE_COUNT")
    return problems


def mutable_static_fields(text):
    # Methods and nested DTO types are excluded by the field terminator. Only immutable
    # scalar/String/Pattern constants may be shared by these application collaborators.
    fields = re.finditer(
        r"(?m)^\s*(?:(?:public|protected|private)\s+)?static\s+"
        r"(?P<modifiers>(?:(?:final|volatile)\s+)*)"
        r"(?P<type>[\w.]+(?:<[^;\n{}]+>)?(?:\[\])?)\s+"
        r"(?P<name>\w+)\s*(?:=|,|;)", without_comments(text))
    return [field.group("name") for field in fields
            if "final" not in field.group("modifiers").split()
            or field.group("type").rsplit(".", 1)[-1] not in IMMUTABLE_TYPES]


class CoreBoundariesTest(unittest.TestCase):
    def test_harness_facade_does_not_execute_split_concerns(self):
        facade = source("core/HarnessController")
        for execution in ("DshAuthSession.exchange", "LanProxyService.",
                          "NativeConfigurationReset.reset", "BackupManager.recoverInterrupted",
                          "PluginActivationHooks.", "lifecycle.beginStart", "lifecycle.beginStop"):
            self.assertNotIn(execution, facade)
        self.assertRegex(facade, r"(?:new\s+)?WebLifecycleController\s*\(\s*this\s*,\s*session\s*\)")
        self.assertRegex(facade, r"(?:new\s+)?HostConfigService\s*\(\s*(?:this\.)?context\s*,\s*config\s*\)")
        self.assertLessEqual(len(facade.splitlines()), 300)

    def test_one_application_owns_the_runtime_ports_and_web_state(self):
        application_path = source_path("DshaApp")
        application = application_path.read_text(encoding="utf-8")
        self.assertIn("RuntimeHostPorts.Owner", application)
        self.assertEqual(
            [], runtime_hosts_owner_problems(application, application_path.suffix == ".kt"),
            "运行时端口与 Web 会话状态必须由唯一应用实例拥有")
        ports_path = source_path("runtime/RuntimeHostPorts")
        ports = ports_path.read_text(encoding="utf-8")
        if ports_path.suffix == ".java":
            self.assertNotIn("static final RuntimeHostPorts SHARED", ports)
            self.assertNotIn("RuntimeHostPorts shared()", ports)
            self.assertNotIn("static volatile Provider", ports)
        else:
            self.assertEqual([], kotlin_shared_runtime_ports(ports))
        for path in source_files():
            self.assertNotIn("RuntimeHostPorts.shared()", code_text(path),
                             str(path.relative_to(ROOT)))

    def test_runtime_and_backup_have_no_direct_ui_dependency(self):
        for package in ("runtime", "backup"):
            sources = [(path.relative_to(ROOT).as_posix(), path.read_text(encoding="utf-8"))
                       for path in source_files(package)]
            self.assertFalse(layer_violations(sources, UI_LAYER),
                             "runtime/backup 不得直接依赖 ui 层")

    def test_existing_backup_host_coordination_edges_can_only_decrease(self):
        baseline = json.loads((ROOT / "tools/architecture-backup-core-baseline.json")
                              .read_text(encoding="utf-8"))
        self.assertEqual(1, baseline["schema"])
        permitted = {(edge["file"], edge["type"]) for edge in baseline["edges"]}
        sources = [(path.relative_to(ROOT).as_posix(), path.read_text(encoding="utf-8"))
                   for path in source_files("backup")]
        actual = set(backup_core_edges(sources))
        self.assertFalse(actual - permitted,
                         "New backup/core edge requires an explicit host port: "
                         + repr(sorted(actual - permitted)))

    def test_ui_has_no_access_to_package_private_web_collaborators(self):
        collaborators = ("WebLifecycleController", "WebProcessSession", "LanAuthBridge",
                         "HostConfigService", "StartupEnvironmentActions")
        for name in collaborators:
            path = source_path("core/" + name)
            text = path.read_text(encoding="utf-8")
            if path.suffix == ".java":
                self.assertIn("final class " + name, text)
                self.assertNotIn("public final class " + name, text)
            else:
                self.assertEqual([], kotlin_visibility_problems(text, name), name)
        sources = [(path.relative_to(ROOT).as_posix(), path.read_text(encoding="utf-8"))
                   for path in source_files("ui")]
        self.assertFalse(ui_collaborator_references(sources, collaborators),
                         "ui 不得引用 core 的包级 Web 协作者")

    def test_service_reference_is_application_owned_and_public_static_activity_is_absent(self):
        service_path = source_path("HarnessService")
        service = service_path.read_text(encoding="utf-8")
        if service_path.suffix == ".java":
            self.assertNotRegex(service, r"static\s+volatile\s+HarnessService")
        else:
            self.assertEqual([], kotlin_mutable_harness_service(service))
        self.assertIn("harnessService().attach(this)", service)
        self.assertIn("harnessService().release(this)", service)
        for path in source_files():
            text = path.read_text(encoding="utf-8")
            self.assertNotRegex(text, r"public\s+static\s+volatile\s+\w*Activity\b", str(path))
            if path.suffix == ".kt":
                self.assertEqual([], kotlin_public_activity_static(text), str(path))

    def test_stop_algorithms_keep_typed_facts_and_render_only_through_host_display_ports(self):
        stop_algorithms = ("runtime/WebProcessManager", "runtime/WebRecordedStop",
                           "util/WebStopDiagnostic", "util/WebStopException")
        self.assertEqual([], ui_text_in_stop_algorithms(
            [(name, source(name)) for name in stop_algorithms]))
        self.assertRegex(source("core/WebStopCoordinator"), r"List<\s*WebStopDiagnostic\s*>")
        self.assertIn("stopGuestFacts()", source("core/WebLifecycleController"))
        self.assertIn("forceStopResult(forcedCandidate)", source("core/WebLifecycleController"))
        self.assertIn("WebStopText.render(diagnostic)", source("DshaApp"))

    def test_virtual_screen_owner_has_one_lazy_factory_and_no_mutable_static_state(self):
        manager_path = source_path("vscreen/VirtualScreenManager")
        application_path = source_path("DshaApp")
        manager = manager_path.read_text(encoding="utf-8")
        application = application_path.read_text(encoding="utf-8")
        compact = re.sub(r"\s+", "", application)
        if application_path.suffix == ".java":
            self.assertRegex(
                compact,
                r"virtualScreenOwner\.get\(\(\)->new"
                r"(?:com\.deepseekharness\.app\.vscreen\.)?VirtualScreenManager\(this\)\)")
        else:
            self.assertRegex(
                compact,
                r"virtualScreenOwner\.get\{(?:com\.deepseekharness\.app\.vscreen\.)?"
                r"VirtualScreenManager\(this\)\}")
        if manager_path.suffix == ".java":
            self.assertRegex(manager, r"public\s+VirtualScreenManager\s*\(\s*Context\s+\w+\s*\)")
            locator = re.search(r"public\s+static\s+VirtualScreenManager\s+from\s*"
                                r"\(\s*Context\s+\w+\s*\)\s*\{([^{}]*)\}", manager)
            self.assertIsNotNone(locator, "The locator must delegate to the context's Application owner")
            self.assertRegex(locator.group(1),
                             r"return\s+(?:com\.deepseekharness\.app\.)?DshaApp\.from\(\w+\)"
                             r"\.virtualScreenManager\(\)\s*;")
        else:
            self.assertRegex(manager, r"class\s+VirtualScreenManager\s*(?:constructor\s*)?"
                                     r"\(\s*\w+\s*:\s*Context\b")
            locator = re.search(r"fun\s+from\s*\([^)]*\)\s*(?::\s*VirtualScreenManager)?\s*"
                                r"(?::=|=|\{)([^}]{0,400})", manager)
            self.assertIsNotNone(locator, "The locator must delegate to the context's Application owner")
            self.assertIn("DshaApp.from(", locator.group(1))
            self.assertIn(".virtualScreenManager()", locator.group(1))
        self.assertRegex(application,
                         r"throw\s+(?:new\s+)?IllegalStateException\s*\(\s*"
                         r"\"DSHA_APPLICATION_SCOPE_UNAVAILABLE\"\s*\)")
        for name in ("VirtualScreenManager", "VirtualScreenPreviews", "VirtualScreenForeground",
                     "VirtualScreenOverlayController", "VirtualScreenAccessibility", "VirtualScreenInput"):
            path = source_path("vscreen/" + name)
            text = path.read_text(encoding="utf-8")
            if path.suffix == ".java":
                self.assertEqual([], mutable_static_fields(text), name)
            else:
                self.assertEqual([], mutable_shared_state(text), name)
            self.assertNotIn("DshaApp.current()", text, name)
        for path in source_files():
            text = path.read_text(encoding="utf-8")
            calls = construction_count(text, "VirtualScreenManager", path.suffix == ".kt")
            owner_file = path.name in ("DshaApp.java", "DshaApp.kt")
            self.assertEqual(1 if owner_file else 0, calls, str(path))

    def test_portable_settings_listener_and_writer_belong_to_one_application(self):
        settings_path = source_path("data/PortableSettings")
        application_path = source_path("DshaApp")
        settings = settings_path.read_text(encoding="utf-8")
        application = application_path.read_text(encoding="utf-8")
        kotlin = application_path.suffix == ".kt"
        self.assertRegex(application,
                         r"(?:com\.deepseekharness\.app\.data\.)?PortableSettings\.Owner")
        if kotlin:
            self.assertIn("portableSettingsOwner", application)
        else:
            self.assertRegex(re.sub(r"\s+", "", application),
                             r"portableSettingsOwner\.get\((?:com\.deepseekharness\.app\.data\.)?"
                             r"PortableSettings::new\)")
        if settings_path.suffix == ".java":
            self.assertEqual([], mutable_static_fields(settings))
        else:
            self.assertEqual([], mutable_shared_state(settings))
        self.assertRegex(settings, r"application\s+(?:instanceof|is)\s+Owner")
        for code in ("PORTABLE_SETTINGS_OWNER_UNAVAILABLE", "PORTABLE_SETTINGS_UNAVAILABLE"):
            self.assertRegex(settings,
                             r"throw\s+(?:new\s+)?IllegalStateException\s*\(\s*\"" + code + r"\"\s*\)")
        self.assertNotIn("DshaApp.current()", settings)
        for path in source_files():
            text = path.read_text(encoding="utf-8")
            is_kotlin = path.suffix == ".kt"
            calls = construction_count(text, "PortableSettings", is_kotlin)
            references = constructor_references(text, "PortableSettings", is_kotlin)
            self.assertEqual(0, calls, str(path))
            self.assertEqual(1 if path.name in ("DshaApp.java", "DshaApp.kt") else 0,
                             references, str(path))

    def test_no_parallel_java_and_kotlin_implementations(self):
        names = [path.relative_to(MAIN).as_posix() for path in source_files()]
        self.assertEqual({}, parallel_implementations(names),
                         "同一职责不得同时保留 .java 与 .kt 实现")


class KotlinBoundaryRulesTest(unittest.TestCase):
    """Kotlin 反例：这些夹具必须被扫描器拒绝，不能只依赖 `.java` 后缀。"""

    def test_runtime_or_backup_kotlin_importing_ui_is_rejected(self):
        for name in ("runtime/Runner.kt", "backup/Store.kt"):
            sources = [(name, "package x\n\nimport com.deepseekharness.app.ui.MainActivity\n")]
            self.assertEqual([(name, "com.deepseekharness.app.ui.")],
                             layer_violations(sources, UI_LAYER))

    def test_backup_kotlin_new_core_edge_and_wildcard_are_rejected(self):
        sources = [("backup/Store.kt",
                    "import com.deepseekharness.app.core.PluginRepository\n"
                    "import com.deepseekharness.app.core.*\n")]
        edges = {edge for _, edge in backup_core_edges(sources)}
        self.assertEqual({"com.deepseekharness.app.core.PluginRepository",
                          "com.deepseekharness.app.core.*"}, edges)

    def test_kotlin_ui_reference_to_web_collaborator_is_rejected(self):
        sources = [("ui/Page.kt",
                    "import com.deepseekharness.app.core.WebLifecycleController\n")]
        self.assertEqual([("ui/Page.kt", "WebLifecycleController")],
                         ui_collaborator_references(sources, ("WebLifecycleController",)))

    def test_kotlin_stop_algorithm_ui_text_leak_is_rejected(self):
        self.assertEqual(["util/WebStopDiagnostic.kt"], ui_text_in_stop_algorithms(
            [("util/WebStopDiagnostic.kt",
              "import com.deepseekharness.app.util.UiText\n")]))
        self.assertEqual([], ui_text_in_stop_algorithms(
            [("util/WebStopDiagnostic.kt",
              "import com.deepseekharness.app.util.SensitiveData\n")]))

    def test_kotlin_shared_runtime_ports_owner_is_rejected(self):
        bad = [
            "package p\nval SHARED = RuntimeHostPorts()\n",
            "package p\nclass RuntimeHostPorts {\n  companion object {\n"
            "    val provider: Provider? = null\n  }\n}\n",
            "package p\nfun shared() = RuntimeHostPorts()\n",
        ]
        for text in bad:
            self.assertNotEqual([], kotlin_shared_runtime_ports(text), text)
        self.assertEqual([], kotlin_shared_runtime_ports(
            "package p\nclass RuntimeHostPorts {\n  private val provider: Provider? = null\n}\n"))

    def test_kotlin_mutable_shared_state_is_rejected(self):
        bad = [
            "package p\nprivate var shared: Any? = null\n",
            "package p\ninternal val owner = VirtualScreenManager()\n",
            "package p\nclass Holder {\n  companion object {\n    @JvmField var current: Any? = null\n"
            "  }\n}\n",
            "package p\nobject State {\n  var value: String? = null\n}\n",
        ]
        for text in bad:
            self.assertNotEqual([], mutable_shared_state(text), text)
        good = [
            "package p\nprivate const val NAME = \"DSHA\"\n",
            "package p\nprivate val tag: String = \"x\"\n",
            "package p\nfun f() {\n  var local = 1\n  local += 1\n}\n",
            "package p\nclass Holder {\n  private var field: Any? = null\n}\n",
        ]
        for text in good:
            self.assertEqual([], mutable_shared_state(text), text)

    def test_kotlin_public_collaborator_class_is_rejected(self):
        self.assertNotEqual([], kotlin_visibility_problems(
            "public class WebLifecycleController", "WebLifecycleController"))
        self.assertNotEqual([], kotlin_visibility_problems(
            "open class WebLifecycleController", "WebLifecycleController"))
        self.assertEqual([], kotlin_visibility_problems(
            "internal class WebLifecycleController", "WebLifecycleController"))

    def test_kotlin_owner_application_without_owner_or_with_two_instances_is_rejected(self):
        missing = "class DshaApp {\n  val ports = RuntimeHostPorts()\n}\n"
        self.assertIn("RUNTIME_HOSTS_OWNER_MISSING",
                      runtime_hosts_owner_problems(missing, True))
        doubled = ("class DshaApp : RuntimeHostPorts.Owner {\n"
                   "  val ports = RuntimeHostPorts()\n"
                   "  val second = RuntimeHostPorts()\n"
                   "  val session = HarnessSessionState()\n}\n")
        self.assertIn("RUNTIME_HOSTS_OWNER_COUNT",
                      runtime_hosts_owner_problems(doubled, True))
        good = ("class DshaApp : RuntimeHostPorts.Owner {\n"
                "  val ports = RuntimeHostPorts()\n"
                "  val session = HarnessSessionState()\n}\n")
        self.assertEqual([], runtime_hosts_owner_problems(good, True))

    def test_kotlin_public_static_activity_field_is_rejected(self):
        self.assertNotEqual([], kotlin_public_activity_static(
            "package p\n@JvmField\nvar current: MainActivity? = null\n"))
        self.assertEqual([], kotlin_public_activity_static(
            "package p\nprivate var current: MainActivity? = null\n"))

    def test_kotlin_mutable_harness_service_is_rejected(self):
        self.assertNotEqual([], kotlin_mutable_harness_service(
            "package p\nclass HarnessService {\n  companion object {\n"
            "    var instance: HarnessService? = null\n  }\n}\n"))
        self.assertEqual([], kotlin_mutable_harness_service(
            "package p\nclass HarnessService {\n  companion object {\n"
            "    val instance: HarnessService? = null\n  }\n}\n"))

    def test_parallel_java_and_kotlin_implementation_is_rejected(self):
        self.assertEqual({"runtime/Runner": {".java", ".kt"}},
                         parallel_implementations(["runtime/Runner.java",
                                                   "runtime/Runner.kt",
                                                   "runtime/Other.java"]))
        self.assertEqual({}, parallel_implementations(["runtime/Runner.java",
                                                       "runtime/Other.kt"]))


if __name__ == "__main__":
    unittest.main()
