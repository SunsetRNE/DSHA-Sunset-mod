#!/usr/bin/env python3
"""发布身份解析：mod（默认）、upstream 与 rebirth 三套身份互斥，绝不混用。

身份文件 ci/release-identity.<name>.json 保存包名、证书 SHA-256、显示名、默认 key alias、
测试/开发后缀、tag 前缀、versionCode/versionName 与允许发布的仓库/分支。当前三条发行线的
versionCode/versionName 取值相同（Rebirth 首包沿用 359），并各自记录在身份文件里供核验；
build.gradle 的版本基准与所选身份文件必须在配置阶段一致。Rebirth 将来独立递增需要把版本源
迁移到身份文件并同步 generate-changelog / release_names / sign-release-apks 的解析（G1 另立），
在此之前不允许只改一边。ci/release-identity.json
仍是 Mod 身份的兼容入口（旧脚本与门禁读取它），必须与 ci/release-identity.mod.json 的包名、
证书、签名方案与版本号一致。

Rebirth 的证书尚未创建：certificateSha256 为 64 个 0 表示“未固定”，只有 rebirth 身份允许该占位，
且任何签名门禁遇到占位必须失败；Mod/upstream 身份出现占位视为身份损坏。

app/build.gradle 用同一规则解析 -Pdsha.identity；两边的允许值由 test-release-identity.py 交叉核对。

  python3 tools/release-identity.py --identity upstream --field packageName
  python3 tools/release-identity.py --identity rebirth --field packageName
  python3 tools/release-identity.py --check
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IDENTITIES = ("mod", "upstream", "rebirth")
DEFAULT = "mod"
# 证书尚未创建的占位；只是“未固定”，不能被当成可用证书。
CERTIFICATE_PLACEHOLDER = "0" * 64
FIELDS = ("schema", "identity", "packageName", "certificateSha256", "schemes", "versionCode", "versionName",
          "appName", "keyAlias", "testSuffix", "devSuffix", "tagPrefix", "apkPrefix", "repository", "ref",
          "privateRepository", "workflow")
SHARED = ("packageName", "certificateSha256", "schemes", "versionCode", "versionName")


def path_for(name):
    if name not in IDENTITIES:
        raise ValueError(f"UNKNOWN_RELEASE_IDENTITY: {name!r}（只接受 {' / '.join(IDENTITIES)}）")
    return ROOT / "ci" / f"release-identity.{name}.json"


def validate(name, value):
    if not isinstance(value, dict) or set(value) != set(FIELDS):
        raise ValueError(f"RELEASE_IDENTITY_FIELDS: {name}")
    if value["schema"] != 1 or value["identity"] != name:
        raise ValueError(f"RELEASE_IDENTITY_NAME: {name}")
    if not re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+", value["packageName"]):
        raise ValueError(f"RELEASE_IDENTITY_PACKAGE: {name}")
    if not re.fullmatch(r"[a-f0-9]{64}", value["certificateSha256"]):
        raise ValueError(f"RELEASE_IDENTITY_CERTIFICATE: {name}")
    if value["certificateSha256"] == CERTIFICATE_PLACEHOLDER and name != "rebirth":
        raise ValueError(f"RELEASE_IDENTITY_CERTIFICATE_PLACEHOLDER: {name}")
    if value["schemes"] != ["v1", "v2", "v3"]:
        raise ValueError(f"RELEASE_IDENTITY_SCHEMES: {name}")
    if not isinstance(value["versionCode"], int) or isinstance(value["versionCode"], bool) \
            or not 1 <= value["versionCode"] <= 999999999:
        raise ValueError(f"RELEASE_IDENTITY_VERSION_CODE: {name}")
    if not isinstance(value["versionName"], str) \
            or not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z._+-]*", value["versionName"]):
        raise ValueError(f"RELEASE_IDENTITY_VERSION_NAME: {name}")
    if not re.fullmatch(r"\.[A-Za-z][A-Za-z0-9_]*", value["testSuffix"]):
        raise ValueError(f"RELEASE_IDENTITY_TEST_SUFFIX: {name}")
    if not re.fullmatch(r"\.[A-Za-z][A-Za-z0-9_]*", value["devSuffix"]):
        raise ValueError(f"RELEASE_IDENTITY_DEV_SUFFIX: {name}")
    if not re.fullmatch(r"[A-Za-z0-9._-]+", value["keyAlias"]) or not value["appName"].strip():
        raise ValueError(f"RELEASE_IDENTITY_ALIAS_OR_NAME: {name}")
    if not re.fullmatch(r"[a-z0-9-]+-build", value["tagPrefix"]) or not re.fullmatch(r"[a-z0-9-]+", value["apkPrefix"]):
        raise ValueError(f"RELEASE_IDENTITY_TAG: {name}")
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", value["repository"]) or not value["ref"].startswith("refs/heads/") \
            or not isinstance(value["privateRepository"], bool):
        raise ValueError(f"RELEASE_IDENTITY_REPOSITORY: {name}")
    return value


def load(name=None, root=None):
    """name 为 None 或空串时取默认 mod；未知值直接报错，不回退。"""
    selected = DEFAULT if name in (None, "") else name
    path = path_for(selected)
    if root is not None:
        path = Path(root) / "ci" / path.name
    return validate(selected, json.loads(path.read_text(encoding="utf-8")))


def check(root=None):
    """三套身份互不相同，且 Mod 兼容入口与 mod 身份一致。

    repository 允许相同（三条发行线都在本仓产出），但包名、证书、显示名、key alias、
    测试/开发后缀、tag 前缀、APK 前缀与发布工作流必须两两不同；Rebirth 的证书可以是占位。
    """
    base = Path(root) if root is not None else ROOT
    loaded = {name: load(name, base) for name in IDENTITIES}
    shared_forbidden = ("packageName", "certificateSha256", "appName", "keyAlias", "testSuffix", "devSuffix",
                        "tagPrefix", "apkPrefix", "workflow")
    for left in range(len(IDENTITIES)):
        for right in range(left + 1, len(IDENTITIES)):
            first, second = IDENTITIES[left], IDENTITIES[right]
            for key in shared_forbidden:
                if loaded[first][key] == loaded[second][key]:
                    raise ValueError(f"RELEASE_IDENTITIES_SHARE_{key}: {first}/{second}")
    legacy = json.loads((base / "ci/release-identity.json").read_text(encoding="utf-8"))
    for key in SHARED:
        if legacy.get(key) != loaded["mod"][key]:
            raise ValueError(f"LEGACY_IDENTITY_DIFFERS_FROM_MOD: {key}")
    return loaded


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--identity", default=DEFAULT)
    parser.add_argument("--field", choices=FIELDS)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.check:
            check()
        value = load(args.identity)
    except ValueError as error:
        print(error, file=sys.stderr)
        return 2
    if args.field:
        field = value[args.field]
        print(json.dumps(field) if isinstance(field, (list, bool)) else field)
    elif not args.check:
        print(json.dumps(value, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
