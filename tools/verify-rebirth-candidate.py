#!/usr/bin/env python3
"""核验 Rebirth 未签名候选 APK 的身份、清单约束与“确实未签名”；不签名、不安装、不发布。

候选身份唯一来源是 ci/release-identity.rebirth.json（包名 / versionCode / versionName / appName 等），
本工具不硬编码任何身份值。身份文件不单独存 certificateStatus：证书指纹为 64 个 0 即 unset，
其它合法指纹即 fixed，与 build.gradle 的 printReleaseIdentity 输出同一判定。核验对象只能是未签名候选：

  * aapt2 dump badging / xmltree 核对包名、版本、非 debuggable、minSdk 30、targetSdk 37、
    仅 arm64-v8a、显示名、测试/开发后缀与提供 authorities 的组件；
  * apksigner verify 必须非 0，且不得存在任何证书签名材料（v1 的 META-INF/*.RSA|DSA|EC、
    APK Signing Block 或 apksigner 能打印出的证书）；被任何证书签名即违规并打印证书摘要；
  * certificateStatus=fixed 时未签名候选仍拒绝：候选阶段只接受 unset 身份下的未签名产物。

用法：

  python3 tools/verify-rebirth-candidate.py --apk out/dsha-rebirth-unsigned.apk
  python3 tools/verify-rebirth-candidate.py --apk out/dsha-rebirth-unsigned.apk --json > out/rebirth-candidate.json

退出码：0=符合未签名候选（仍不可安装）；1=身份/格式违规；2=aapt2/apksigner 找不到。
固定标记：UNSIGNED_NOT_FOR_INSTALLATION —— 未签名候选不可安装，也不是发布验收。
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_IDENTITY = ROOT / "ci" / "release-identity.rebirth.json"
MARKER = "UNSIGNED_NOT_FOR_INSTALLATION"
MIN_SDK = 30
TARGET_SDK = 37
APK_SIGNING_BLOCK_MAGIC = b"APK Sig Block 42"
SIGNATURE_FILE_SUFFIXES = (".RSA", ".DSA", ".EC")
IDENTITY_FIELDS = frozenset({
    "schema", "identity", "packageName", "appName", "versionCode", "versionName",
    "certificateSha256", "schemes", "keyAlias", "testSuffix",
    "devSuffix", "tagPrefix", "apkPrefix", "repository", "ref", "privateRepository", "workflow",
})
CERTIFICATE_PLACEHOLDER = "0" * 64
# G0 负控要求：未固定证书前，未签名候选也不得与任何测试/开发身份后缀混用。
FORBIDDEN_SUFFIXES = (".test", ".debug", ".dev")


class CandidateInvalid(ValueError):
    """身份文件或候选 APK 不符合 Rebirth 候选约定（退出码 1）。"""


class ToolMissing(RuntimeError):
    """aapt2 / apksigner 不可用（退出码 2）。"""


def _identity_error(code, detail=""):
    return CandidateInvalid(code + (": " + detail if detail else ""))


def validate_identity(value):
    """逐字段核对冻结的 Rebirth 身份结构；未知字段也拒绝，避免拼写错误被静默忽略。"""
    if not isinstance(value, dict):
        raise _identity_error("IDENTITY_NOT_OBJECT")
    missing = sorted(IDENTITY_FIELDS - set(value))
    extra = sorted(set(value) - IDENTITY_FIELDS)
    if missing or extra:
        raise _identity_error("IDENTITY_FIELDS", f"缺失={missing} 多余={extra}")
    if value["schema"] != 1:
        raise _identity_error("IDENTITY_SCHEMA")
    if value["identity"] != "rebirth":
        raise _identity_error("IDENTITY_NAME", repr(value["identity"]))
    if not isinstance(value["packageName"], str) or \
            not re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+", value["packageName"]):
        raise _identity_error("IDENTITY_PACKAGE")
    if not isinstance(value["appName"], str) or not value["appName"].strip():
        raise _identity_error("IDENTITY_APP_NAME")
    if not isinstance(value["versionCode"], int) or isinstance(value["versionCode"], bool) \
            or value["versionCode"] <= 0:
        raise _identity_error("IDENTITY_VERSION_CODE")
    if not isinstance(value["versionName"], str) or not value["versionName"].strip():
        raise _identity_error("IDENTITY_VERSION_NAME")
    if not isinstance(value["certificateSha256"], str) or \
            not re.fullmatch(r"[a-f0-9]{64}", value["certificateSha256"]):
        raise _identity_error("IDENTITY_CERTIFICATE")
    if value["schemes"] != ["v1", "v2", "v3"]:
        raise _identity_error("IDENTITY_SCHEMES")
    if not isinstance(value["keyAlias"], str) or not re.fullmatch(r"[A-Za-z0-9._-]+", value["keyAlias"]):
        raise _identity_error("IDENTITY_KEY_ALIAS")
    for field in ("testSuffix", "devSuffix"):
        if not isinstance(value[field], str) or not re.fullmatch(r"\.[A-Za-z][A-Za-z0-9_]*", value[field]):
            raise _identity_error("IDENTITY_SUFFIX", field)
    if not isinstance(value["tagPrefix"], str) or not re.fullmatch(r"[a-z0-9-]+-build", value["tagPrefix"]):
        raise _identity_error("IDENTITY_TAG_PREFIX")
    if not isinstance(value["apkPrefix"], str) or not re.fullmatch(r"[a-z0-9-]+", value["apkPrefix"]):
        raise _identity_error("IDENTITY_APK_PREFIX")
    if not isinstance(value["repository"], str) or not re.fullmatch(r"[\w.-]+/[\w.-]+", value["repository"]):
        raise _identity_error("IDENTITY_REPOSITORY")
    if not isinstance(value["ref"], str) or not value["ref"].startswith("refs/heads/"):
        raise _identity_error("IDENTITY_REF")
    if not isinstance(value["privateRepository"], bool):
        raise _identity_error("IDENTITY_PRIVATE_REPOSITORY")
    if not isinstance(value["workflow"], str) or not value["workflow"].strip():
        raise _identity_error("IDENTITY_WORKFLOW")
    return value


def load_identity(path=DEFAULT_IDENTITY):
    path = Path(path)
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        raise _identity_error("IDENTITY_MISSING", str(path)) from None
    except OSError as error:
        raise _identity_error("IDENTITY_UNREADABLE", str(error)) from None
    try:
        return validate_identity(json.loads(text))
    except json.JSONDecodeError as error:
        raise _identity_error("IDENTITY_JSON", str(error)) from None


def certificate_status(identity):
    """身份文件只存证书指纹：占位指纹 = unset，其它合法指纹 = fixed（与 build.gradle 一致）。"""
    return "unset" if identity["certificateSha256"] == CERTIFICATE_PLACEHOLDER else "fixed"


def parse_badging(text):
    """只解析候选核验需要的 aapt2 badging 行；缺失字段保留 None，由逐项检查报告。"""
    package = re.search(r"^package: name='([^']*)'", text, re.M)
    code = re.search(r"\bversionCode='(\d+)'", text)
    version = re.search(r"\bversionName='([^']*)'", text)
    minimum = re.search(r"^(?:sdkVersion|minSdkVersion):'(\d+)'", text, re.M)
    target = re.search(r"^targetSdkVersion:'(\d+)'", text, re.M)
    native = re.search(r"^native-code: (.+)$", text, re.M)
    label = re.search(r"^application-label:'([^']*)'", text, re.M)
    abis = []
    if native:
        abis = re.findall(r"'([^']+)'", native.group(1))
        if not abis:
            abis = [item.strip() for item in native.group(1).split() if item.strip()]
    return {
        "packageName": package.group(1) if package else None,
        "versionCode": int(code.group(1)) if code else None,
        "versionName": version.group(1) if version else None,
        "minSdk": int(minimum.group(1)) if minimum else None,
        "targetSdk": int(target.group(1)) if target else None,
        "nativeCode": abis,
        "debuggable": re.search(r"^application-debuggable\s*$", text, re.M) is not None,
        "applicationLabel": label.group(1) if label else None,
    }


def parse_authorities(xmltree):
    """从 aapt2 dump xmltree 的 AndroidManifest.xml 中取出全部 authorities（分号分隔展开）。"""
    authorities = []
    for line in xmltree.splitlines():
        stripped = line.strip()
        if not stripped.startswith("A:") or ":authorities" not in stripped:
            continue
        _, _, raw = stripped.partition("=")
        raw = raw.strip()
        if raw.startswith('"'):
            end = raw.find('"', 1)
            value = raw[1:end] if end > 0 else raw[1:]
        else:
            value = raw.split()[0] if raw else ""
        for authority in value.split(";"):
            authority = authority.strip()
            if authority:
                authorities.append(authority)
    return authorities


def _check(name, passed, detail, code=None):
    row = {"name": name, "status": "PASS" if passed else "FAIL", "detail": detail}
    if not passed and code:
        row["code"] = code
    return row


def manifest_checks(identity, badging_text, xmltree_text):
    values = parse_badging(badging_text)
    authorities = parse_authorities(xmltree_text)
    package = values["packageName"]
    checks = [
        _check("packageName", package == identity["packageName"],
               f"期望 {identity['packageName']}，实际 {package}", "PACKAGE_NAME_MISMATCH"),
        _check("versionCode", values["versionCode"] == identity["versionCode"],
               f"期望 {identity['versionCode']}，实际 {values['versionCode']}", "VERSION_CODE_MISMATCH"),
        _check("versionName", values["versionName"] == identity["versionName"],
               f"期望 {identity['versionName']}，实际 {values['versionName']}", "VERSION_NAME_MISMATCH"),
        _check("debuggable", not values["debuggable"],
               "badging 出现 application-debuggable" if values["debuggable"] else "非 debuggable",
               "APK_DEBUGGABLE"),
        _check("minSdk", values["minSdk"] == MIN_SDK,
               f"期望 {MIN_SDK}，实际 {values['minSdk']}", "MINSDK_MISMATCH"),
        _check("targetSdk", values["targetSdk"] == TARGET_SDK,
               f"期望 {TARGET_SDK}，实际 {values['targetSdk']}", "TARGETSDK_MISMATCH"),
        _check("nativeCode", values["nativeCode"] == ["arm64-v8a"],
               f"期望 ['arm64-v8a']，实际 {values['nativeCode']}", "NATIVE_CODE_MISMATCH"),
        _check("applicationLabel", values["applicationLabel"] == identity["appName"],
               f"期望 {identity['appName']!r}，实际 {values['applicationLabel']!r}", "APPLICATION_LABEL_MISMATCH"),
    ]
    suffixes = list(FORBIDDEN_SUFFIXES) + [identity["testSuffix"], identity["devSuffix"]]
    hits = []
    for field in ("packageName", "versionName"):
        text = values[field]
        if isinstance(text, str):
            hits.extend(f"{field} 以 {suffix} 结尾" for suffix in suffixes if text.endswith(suffix))
    checks.append(_check("testSuffix", not hits,
                         "；".join(hits) if hits else "未出现 .test/.debug/.dev 或身份测试后缀",
                         "TEST_OR_DEV_SUFFIX"))
    matched = None
    if package:
        matched = next((authority for authority in authorities
                        if authority == package or authority.startswith(package + ".")), None)
    checks.append(_check(
        "authorities", matched is not None,
        f"component authority={matched}" if matched else
        f"没有任何 authorities 以包名 {package!r} 开头（实际 {authorities}）",
        "AUTHORITIES_MISMATCH"))
    return checks


def apk_signing_block(path):
    """流式查找 APK Signing Block 魔数；不把大 APK 整体读入内存。"""
    keep = len(APK_SIGNING_BLOCK_MAGIC) - 1
    tail = b""
    try:
        with Path(path).open("rb") as stream:
            while True:
                block = stream.read(4 * 1024 * 1024)
                if not block:
                    return False
                if APK_SIGNING_BLOCK_MAGIC in tail + block:
                    return True
                tail = (tail + block)[-keep:]
    except OSError as error:
        raise CandidateInvalid("APK_UNREADABLE: " + str(error)) from None


def signature_material(path):
    """证书签名材料：v1 的 META-INF/*.RSA|DSA|EC 与 APK Signing Block。仅无证书签名文件不算法签名。"""
    files = []
    try:
        with zipfile.ZipFile(path) as archive:
            files = sorted(
                name for name in archive.namelist()
                if name.startswith("META-INF/") and Path(name).suffix.upper() in SIGNATURE_FILE_SUFFIXES)
    except (zipfile.BadZipFile, OSError) as error:
        raise CandidateInvalid("APK_ZIP_INVALID: " + str(error)) from None
    return {"metaInfSignatureFiles": files, "apkSigningBlock": apk_signing_block(path)}


def unsigned_checks(identity, probe):
    """apksigner 非 0 只证明“当前不可验证”，还必须排除残留的签名材料。"""
    checks = []
    signed = probe.get("returncode") == 0
    certificates = list(probe.get("certificates") or [])
    files = list(probe.get("metaInfSignatureFiles") or [])
    block = bool(probe.get("apkSigningBlock"))
    if signed:
        detail = "apksigner verify 成功：APK 已被签名"
        if certificates:
            detail += "；证书 SHA-256: " + ", ".join(certificates)
        checks.append(_check("unsigned", False, detail, "APK_IS_SIGNED"))
    elif certificates or files or block:
        parts = []
        if certificates:
            parts.append("证书 SHA-256: " + ", ".join(certificates))
        if files:
            parts.append("META-INF 签名文件: " + ", ".join(files[:5]))
        if block:
            parts.append("存在 APK Signing Block")
        checks.append(_check("unsigned", False,
                             "apksigner verify 非 0，但检测到签名材料：" + "；".join(parts),
                             "APK_HAS_SIGNATURE_MATERIAL"))
    elif "DOES NOT VERIFY" not in probe.get("output", ""):
        # 与 tools/sign-release-apks.py 一致：非 0 还必须明确是“不验证”，不能把工具错误当成未签名。
        checks.append(_check("unsigned", False,
                             "apksigner verify 非 0，但输出未出现 DOES NOT VERIFY：无法确认未签名",
                             "APK_VERIFY_AMBIGUOUS"))
    else:
        checks.append(_check("unsigned", True,
                             f"apksigner verify 退出码 {probe.get('returncode')} 且无签名材料：确实未签名"))
    unsigned_confirmed = (not signed
                          and "DOES NOT VERIFY" in probe.get("output", "")
                          and not (certificates or files or block))
    status = certificate_status(identity)
    if status == "fixed":
        checks.append(_check(
            "certificateStatus", False,
            "身份已固定证书（certificateStatus=fixed）；未签名候选不可交付，必须签名后另行核验",
            "UNSIGNED_WITH_FIXED_CERTIFICATE"))
    else:
        checks.append(_check(
            "certificateStatus", True,
            f"certificateStatus={status}：候选阶段只接受未签名产物（仍不可安装）"))
    return checks, unsigned_confirmed, signed


def evaluate(identity, badging_text, xmltree_text, probe):
    """纯判定入口：不做文件 / 子进程 I/O，便于用 fixture 字符串回归。"""
    checks = manifest_checks(identity, badging_text, xmltree_text)
    unsigned, unsigned_confirmed, signed = unsigned_checks(identity, probe)
    checks += unsigned
    result = "PASS" if all(row["status"] == "PASS" for row in checks) else "FAIL"
    return {
        "result": result,
        "exitCode": 0 if result == "PASS" else 1,
        "checks": checks,
        "unsignedConfirmed": unsigned_confirmed,
        "signed": signed,
    }


def sha256(path):
    value = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def build_report(identity, identity_path, apk_info, evaluated):
    """机器可读回执；apk_info 由调用方给出，方便测试不依赖真实 APK。"""
    result = evaluated["result"]
    report = {
        "schema": 1,
        "kind": "dsha-rebirth-unsigned-candidate",
        "commit": os.environ.get("GITHUB_SHA") or None,
        "result": result,
        "exitCode": evaluated["exitCode"],
        "installable": False,
        "signed": evaluated["signed"],
        "identity": {
            "file": str(identity_path),
            "identity": identity["identity"],
            "packageName": identity["packageName"],
            "appName": identity["appName"],
            "versionCode": identity["versionCode"],
            "versionName": identity["versionName"],
            "certificateStatus": certificate_status(identity),
        },
        "apk": apk_info,
        "checks": evaluated["checks"],
        "note": "未签名候选仅用于构建核验；不可安装，也不是发布验收。",
    }
    if evaluated["unsignedConfirmed"]:
        report["marker"] = MARKER
    elif evaluated["signed"]:
        report["marker"] = "SIGNED_ARTIFACT_REJECTED"
    return report


def _failure_report(check, detail, exit_code, code=None):
    return {
        "schema": 1,
        "kind": "dsha-rebirth-unsigned-candidate",
        "commit": os.environ.get("GITHUB_SHA") or None,
        "result": "FAIL",
        "exitCode": exit_code,
        "installable": False,
        "signed": None,
        "checks": [_check(check, False, detail, code)],
        "note": "未签名候选仅用于构建核验；不可安装，也不是发布验收。",
    }


def emit(report, json_output):
    if json_output:
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return
    for row in report["checks"]:
        print(f"{row['status']} {row['name']}: {row['detail']}")
    print("RESULT: " + report["result"])
    if report.get("marker") == MARKER:
        print(MARKER + ": 未签名候选，不可安装，也不是发布验收。")
    elif report.get("marker"):
        print(str(report["marker"]) + ": 候选链拒绝已签名或身份不符的产物。")


def _build_tools_version():
    path = ROOT / "tools" / "ci-toolchain.py"
    try:
        spec = importlib.util.spec_from_file_location("verify_rebirth_ci_toolchain", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.read(ROOT)["buildTools"]
    except Exception as error:  # noqa: BLE001 - 任何读取失败都意味着工具无法定位。
        raise ToolMissing("无法从 tools/ci-toolchain.py 读取 buildTools: " + str(error)) from None


def _tool_paths(directory):
    suffix = ".exe" if os.name == "nt" else ""
    return {"aapt2": Path(directory) / ("aapt2" + suffix), "apksigner": Path(directory) / ("apksigner" + suffix)}


def _version_key(directory):
    return tuple(int(part) if part.isdigit() else -1 for part in re.split(r"[.-]", directory.name))


def resolve_tools(sdk=None, build_tools=None):
    """优先显式 --build-tools；否则先按 ci-toolchain.py 锁定版本定位，再退到 $ANDROID_HOME/build-tools/*。"""
    if build_tools is not None:
        candidates = [Path(build_tools)]
    else:
        version = _build_tools_version()
        root = Path(sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
                    or "/usr/local/lib/android/sdk")
        candidates = [root / "build-tools" / version]
        base = root / "build-tools"
        if base.is_dir():
            sibling = sorted((path for path in base.iterdir() if path.is_dir()), key=_version_key, reverse=True)
            candidates.extend(path for path in sibling if path != candidates[0])
    for directory in candidates:
        tools = _tool_paths(directory)
        if tools["aapt2"].is_file() and tools["apksigner"].is_file():
            tools["buildTools"] = directory.name
            return tools
    raise ToolMissing("找不到 aapt2/apksigner（尝试：" + ", ".join(str(path) for path in candidates) + "）")


def _run(command, code):
    try:
        return subprocess.check_output([str(item) for item in command], stderr=subprocess.STDOUT,
                                       text=True, encoding="utf-8", errors="replace")
    except (OSError, subprocess.CalledProcessError) as error:
        output = getattr(error, "output", "") or ""
        raise CandidateInvalid(code + ": " + str(error) + "\n" + output[-2000:]) from None


def probe_apk(apksigner, apk):
    try:
        result = subprocess.run([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
                                capture_output=True, text=True, encoding="utf-8", errors="replace")
    except OSError as error:
        raise ToolMissing("无法执行 apksigner: " + str(error)) from None
    output = (result.stdout or "") + (result.stderr or "")
    certificates = sorted({match.group(1).lower() for match in
                           re.finditer(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", output)})
    probe = {"returncode": result.returncode, "output": output, "certificates": certificates}
    probe.update(signature_material(apk))
    return probe


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apk", type=Path, required=True, help="待核验的未签名候选 APK")
    parser.add_argument("--identity", type=Path, default=DEFAULT_IDENTITY,
                        help="候选身份文件（默认 ci/release-identity.rebirth.json）")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON")
    parser.add_argument("--sdk", type=Path, help="Android SDK 根目录（默认 ANDROID_HOME / android-sdk 安装位置）")
    parser.add_argument("--build-tools", type=Path, help="直接指定 build-tools 目录，跳过 ci-toolchain.py 定位")
    args = parser.parse_args(argv)

    try:
        identity = load_identity(args.identity)
    except CandidateInvalid as error:
        report = _failure_report("identityFile", str(error), 1)
        emit(report, args.json)
        return 1
    apk = args.apk
    if not apk.is_file() or apk.is_symlink():
        report = _failure_report("apkFile", "候选 APK 不存在或不是普通文件: " + str(apk), 1)
        emit(report, args.json)
        return 1
    try:
        tools = resolve_tools(args.sdk, args.build_tools)
    except ToolMissing as error:
        report = _failure_report("tools", str(error), 2, "TOOLS_MISSING")
        emit(report, args.json)
        return 2
    try:
        badging = _run([tools["aapt2"], "dump", "badging", apk], "APK_BADGING_FAILED")
        xmltree = _run([tools["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
                       "APK_XMLTREE_FAILED")
        probe = probe_apk(tools["apksigner"], apk)
    except CandidateInvalid as error:
        report = _failure_report("apkInspection", str(error), 1)
        emit(report, args.json)
        return 1
    except ToolMissing as error:
        report = _failure_report("tools", str(error), 2, "TOOLS_MISSING")
        emit(report, args.json)
        return 2
    evaluated = evaluate(identity, badging, xmltree, probe)
    report = build_report(identity, args.identity,
                          {"file": apk.name, "size": apk.stat().st_size, "sha256": sha256(apk)},
                          evaluated)
    emit(report, args.json)
    return evaluated["exitCode"]


if __name__ == "__main__":
    sys.exit(main())
