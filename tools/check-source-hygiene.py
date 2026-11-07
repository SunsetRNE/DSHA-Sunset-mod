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
    "tools/i18n/translations-extra/ar.json": "316ccc38ab79e60e043435df7ef13676c049c79b8a7263cda615a943a7d15e1b",
    "tools/i18n/translations-extra/fr.json": "30013488d6f1187b9ef8f4d5d5dbe45695a55e7344b4035af252b8a10e8a2fcb",
    "tools/i18n/translations-extra/ja.json": "7c7f7966dacea4ad1a2586381ba209d7c5075bcf1a11d7472634f72982c16493",
    "tools/i18n/translations-extra/ko.json": "d79164ecd0eb80a6c6d62bffa67eccc0412a7555611da470a042f46b1888624f",
    "tools/i18n/translations-extra/pt.json": "448559b6a1503d5cff98c48c7e53d38e4386ecd3ac88f03bb7159cc3b7118169",
    "tools/i18n/translations-extra/ru.json": "268bfec06639621cefda09d6254f433539d8f98bab14186a34cb29db77b0d76e",
    "tools/i18n/translations/ar.json": "703c422e01dd597ea73526c8cf1f70d49ed830f3af99a222e2b4beaea53e3a95",
    "tools/i18n/translations/fr.json": "a86a2652c380ed977aaecfca0127964a68b8341d31d48b207e75f7fac1fa6d23",
    "tools/i18n/translations/ja.json": "0a1657113690a2b9626d3a7c31bc9191b39bc9e7d126b5a99a033bd448fb8cad",
    "tools/i18n/translations/ko.json": "c3eed008718e8cdd8ee7aff803d4607533ca5f01e03f00c9808d6160d4c40149",
    "tools/i18n/translations/pt.json": "1c1db7c8445cb5992cfcb2db1a5e8262b5a40fc1c594b5b780024474df1d2e1c",
    "tools/i18n/translations/ru.json": "57e5b1d9227fb9228a918caa943f8f13a626f14fe1e500d02f4e11d9c368e80d",
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
