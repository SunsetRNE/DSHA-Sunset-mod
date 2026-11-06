"""检查源码行尾、显式依赖身份与工作流钉住；不扫私有数据或历史日志。"""
import hashlib
import json
from pathlib import Path
import re
from git_guard import git_output

ROOT = Path(__file__).resolve().parents[1]
names = git_output(["ls-files", "-z"], cwd=ROOT).decode("utf-8").split("\0")
errors = []
byte_pinned = {'app/src/main/assets/ubuntu-tools.manifest.json', 'app/src/main/assets/builtin-plugins/dsh-auto-review/package.json', 'app/src/main/assets/builtin-plugins/dsh-device-shell-guide/package.json', 'app/src/main/assets/lan-settings-upstream-contract.json', 'app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/index.js', 'app/src/main/assets/builtin-plugins/dsh-computer-use-android/package.json', 'app/src/main/assets/ubuntu-tools.layout', 'app/src/main/assets/startup-mcp-timing-patch.json', 'app/src/main/assets/managed-runtime-inputs.json'}
byte_pinned.add("tools/mobile-server/index.js")
byte_pinned.add("app/src/main/assets/plugin-manager-policy-patch.json")
byte_pinned.add("app/src/main/assets/plugin-manager.py")
history_fixture_pins = {
    "tools/fixtures/session-interaction-v3/session-interaction-patch.json": "37f53270df43f737ec7c1d38d55cbf7e30e0cd8f21418ca8831b1582da590b37",
    "tools/fixtures/session-interaction-v3/web-integration/session-interaction.js": "df67935e0ab81e23b494119cb7b4c97e2263cd6d01a6cf9069ca6004e6e3a790",
    "tools/fixtures/rc2-upgrade-source/dsh-client-ui-workspace.js": "52765aacb6c7f96327da5d8709d8cbb30dd2957d4744a42da04c18e07fe264e5",
    "tools/fixtures/rc2-upgrade-source/dsh-client-ui-agent-preset.js": "6ecdf173de66fce620e588f10ed1e3b624725febab059fb867beefef9ae4ddb8",
}
# These frozen language sources retain their reviewed bytes. Runtime generators
# use the signed descriptor; native catalogues and notes have explicit CI pins.
language_generator_pins = {
    "tools/i18n/web-translations/ar.json",
    "tools/i18n/web-translations/ko.json",
    "tools/i18n/web-translations/la.json",
    "tools/i18n/web-translations/zh-Hant.json",
}
language_source_pins = {
    "docs/releases/build197-changes.ar.md": "a96ff6d4f58886938ada1197aa758da75a19ef378c2347830128ca71eda00030",
    "docs/releases/build197-changes.la.md": "24a72be4658c4874920e25f430bbbfdd912ce7d6deb74b428dd9cd32daabcaaa",
    "docs/releases/build197-changes.pt.md": "9c84cad387a0496aa7a0affa9c8a2ee6617ff5d747236e1ebcce84723cbf8500",
    "tools/i18n/translations-extra/ar.json": "fd61c2554a6e20b8a094b3c55d3cbc2b47a5d61ef687354f909eac69cb5e7b84",
    "tools/i18n/translations-extra/fr.json": "83521f7f4e8314881670f9ef68d6211f6565cac8410b3443aec2070a24f55d26",
    "tools/i18n/translations-extra/ja.json": "5c3c8d0ca80819fc67414f08202e70e13771e169598516a652ea152261fe3270",
    "tools/i18n/translations-extra/ko.json": "2a31f7255df132c99804b4b967e6dbf19c150fb5e6c23014f1bd1a40f2c9cbfc",
    "tools/i18n/translations-extra/pt.json": "9e44e658ac3f17b99bd88d7e9d67f42d1e05cb43bad7df4d24c199fc0b551ce0",
    "tools/i18n/translations-extra/ru.json": "562aade8390e149fdb3573f30a5d6736e453799bcba081c5a1ce4dbe0b2e1b01",
    "tools/i18n/translations/ar.json": "f72ca4f204fdd2e36b25329a0af2e3060113bd2605ab993ad4a1cc33c5042ea0",
    "tools/i18n/translations/fr.json": "10f60001e8da134c95482e85f965c8f0cae07bfea6122990b72f4c19823afd34",
    "tools/i18n/translations/ja.json": "5f787b7644b24f85c1d31535b9307d15f0d7bca7a06c9d855d1e6e755d706f7a",
    "tools/i18n/translations/ko.json": "8bf678a76bcd32496b0cd894cfcc8d78e5b228bd3c658a21c58331e7970a3fa9",
    "tools/i18n/translations/pt.json": "f1351765e1a8bed99cb1e8d2d38d664a0c021ae97bf8c7ce509459e370a942e5",
    "tools/i18n/translations/ru.json": "6c40aff3c627040d5a83bb1297f10a7d0215ed3782a0b5023ab66a1621aa3c62",
}
descriptor = json.loads((ROOT / "app/src/main/assets/runtime-descriptor.json").read_text(encoding="utf-8"))
binary = {".so", ".bin", ".jar", ".aar", ".gz", ".tgz", ".zip", ".png", ".jpg", ".webp", ".pdf", ".keystore"}
for name in names:
    path = ROOT / name
    if not path.is_file() or path.suffix in binary or name.startswith('tools/history/') or name in byte_pinned or name in history_fixture_pins or name in language_generator_pins or name in language_source_pins:
        continue
    value = path.read_bytes()
    if b"\0" not in value and b"\r\n" in value:
        errors.append("CRLF: " + name)
# 外部 action 必须钉到完整 commit；仓库内的 ./.github/actions 与 ./.github/workflows
# 节点随同一 commit 审阅，本身也在此扫描。
workflow_files = sorted((ROOT / ".github/workflows").glob("*.yml"))
workflow_files += sorted((ROOT / ".github/actions").glob("*/action.yml"))
for path in workflow_files:
    for selected in re.findall(r"uses:\s*([^\s#]+)", path.read_text(encoding="utf-8")):
        if re.fullmatch(r"\./\.github/(?:actions/[\w-]+|workflows/_node-[\w-]+\.yml)", selected):
            if not (ROOT / selected[2:]).exists():
                errors.append("MISSING_LOCAL_ACTION: " + selected)
            continue
        if not re.fullmatch(r"[\w.-]+/[\w.-]+@[0-9a-f]{40}", selected):
            errors.append("UNPINNED_ACTION: " + str(path.relative_to(ROOT)))
if "mavenLocal()" in (ROOT / "settings.gradle").read_text(encoding="utf-8"):
    errors.append("UNCONTROLLED_MAVEN_LOCAL")
for path in (ROOT / "app/src/main/java").rglob("*.java"):
    if path.name != "Ids.java" and "[a-f0-9]{8}-" in path.read_text(encoding="utf-8"):
        errors.append("DUPLICATE_UUID_PATTERN: " + path.relative_to(ROOT).as_posix())
history_paths = ["tools/history/_byte-policy." + ext for ext in ("py", "java", "md", "txt", "sh", "xml", "json", "cjs")]
history_paths.append("tools/history/LICENSE")
history_paths.extend(sorted(byte_pinned))
history_paths.extend(sorted(history_fixture_pins))
history_paths.extend(sorted(language_generator_pins))
history_paths.extend(sorted(language_source_pins))
for name, expected in history_fixture_pins.items():
    path = ROOT / name
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        errors.append("HISTORICAL_FIXTURE_BYTES: " + name)
attributes = git_output(
    ["check-attr", "-z", "text", "eol", "--", *history_paths], cwd=ROOT
).rstrip(b"\0").split(b"\0")
for at in range(0, len(attributes), 3):
    if attributes[at + 2] != b"unset":
        errors.append("HISTORY_BYTE_NORMALIZATION: " + attributes[at].decode("utf-8"))
for name in sorted(byte_pinned):
    expected = (descriptor["launcherInputs"].get("generator:" + name) if name == "tools/mobile-server/index.js"
                else descriptor["inputs"].get(name.removeprefix("app/src/main/assets/")))
    if not re.fullmatch("[a-f0-9]{64}", expected or "") or hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != expected:
        errors.append("PINNED_BYTE_DIGEST: " + name)
language_pins = dict(language_source_pins)
for name in language_generator_pins:
    language_pins[name] = descriptor["launcherInputs"].get("generator:" + name)
for name, expected in sorted(language_pins.items()):
    path = ROOT / name
    if not re.fullmatch("[a-f0-9]{64}", expected or "") or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        errors.append("PINNED_LANGUAGE_BYTES: " + name)
toolchain = json.loads((ROOT / "ci/toolchain.lock.json").read_text(encoding="utf-8"))
wrapper = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
if "gradle-" + toolchain["gradle"] + "-" not in wrapper or "distributionSha256Sum=" not in wrapper:
    errors.append("GRADLE_IDENTITY")
if errors:
    raise SystemExit("\n".join(errors))
print("PASS tracked text LF or verified exact managed byte pins, pinned Actions, explicit Maven/Gradle identities, shared UUID validation and history byte policy")
