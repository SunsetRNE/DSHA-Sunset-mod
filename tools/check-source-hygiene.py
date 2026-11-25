"""检查源码行尾、显式依赖身份与工作流钉住；不扫私有数据或历史日志。"""
import hashlib
import json
from pathlib import Path
import re
from git_guard import git_output
from proroot_distribution import VENDOR_SOURCE_GLOB
from fnmatch import fnmatchcase

ROOT = Path(__file__).resolve().parents[1]
names = git_output(["ls-files", "-z"], cwd=ROOT).decode("utf-8").split("\0")
errors = []
errors.extend('proroot vendor binary tracked separately from the APK: ' + name
              for name in names if fnmatchcase(name, VENDOR_SOURCE_GLOB))
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
    "tools/i18n/translations-extra/ar.json": "46b27ee9a4544cabba992e57299de5a99ec466baff61ded2893375d8f79083de",
    "tools/i18n/translations-extra/fr.json": "3b1b56e447a06ce5bc19c49d1f781233fef36ba08bf989db489fa1d34fa27a08",
    "tools/i18n/translations-extra/ja.json": "9e5495911a4e42492e361af8d99186461f4c0cb49ee1b08bc71a768d37ee0f8e",
    "tools/i18n/translations-extra/ko.json": "b66c9acd9e206903485d6f18f83d05f9ffa7425b379ff0ce3d7d1c647dbf6910",
    "tools/i18n/translations-extra/la.json": "3e35d12649f2129f5429095c5ba970aa3034b1bbc128dca2ef98404b79cc64cd",
    "tools/i18n/translations-extra/pt.json": "c7dcb7f8d868af1dc40e6bb09a3701f48b71fa7433034eceb9aff27d6be64607",
    "tools/i18n/translations-extra/ru.json": "501bff8e3a767327ea1574b7e0c1349362264ad4d59c8da61fdea0e558d4ef2c",
    "tools/i18n/translations-extra/zh-Hant.json": "b05f23c9bc45821c3af717e615a22a16f8ecf83f1ef0b0577b623ee9495021c1",
    "tools/i18n/translations/ar.json": "d728d024e0d0ccfda6beaacdf9fea8a87ae03f4af2a9390d440c31c350bf9da0",
    "tools/i18n/translations/fr.json": "ad6c68d5a94c704f37ee46401899123a4583922a7c0f9f5a8795993de58009d5",
    "tools/i18n/translations/ja.json": "47ddbcb00da1cf6d5e2cbf40da85e6c21ac1b679d14c7f9512aa514910658aae",
    "tools/i18n/translations/ko.json": "f53295f2d0438ae7a385b311c21f33ada4db901e937d4289f8d61ae23ccdb41d",
    "tools/i18n/translations/pt.json": "f22eaf8b017ed045e502515a0a370d830297276c670aa1c5bc184b7f1762c0a3",
    "tools/i18n/translations/ru.json": "1b88cb7bce492e30e28f9101816b159d4569135bc418c8bb7d1eee148511c4fd",
}
descriptor = json.loads((ROOT / "app/src/main/assets/runtime-descriptor.json").read_text(encoding="utf-8"))
binary = {".so", ".bin", ".jar", ".aar", ".gz", ".tgz", ".zip", ".png", ".jpg", ".webp", ".pdf", ".keystore"}
for name in names:
    path = ROOT / name
    # Rust vendor 是上游原始字节树（行尾由 .cargo-checksum.json 绑定），不参与 CRLF 规范化检查；
    # 许可证副本按 vendor 精确字节保存，同样豁免。
    if name.startswith('rust/vendor/') or name.startswith('app/src/main/assets/licenses/rust/'):
        continue
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
for suffix in ("*.java", "*.kt"):
    for path in (ROOT / "app/src/main/java").rglob(suffix):
        if path.name not in ("Ids.java", "Ids.kt") and "[a-f0-9]{8}-" in path.read_text(encoding="utf-8"):
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
