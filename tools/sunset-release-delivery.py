#!/usr/bin/env python3
"""通过当前 Actions 身份和本地证据门禁，仅创建新的私有 Standard 测试预发布。

--tag sunset-build206-YYYYMMDD-HHMMSS --expected-sha FULL_SHA --output out
不生成证据、不调用签名工具、不依赖 artifacts；任何写入只执行一次，失败保留现场。
"""
import argparse
import base64
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

REPOSITORY = "SunsetRNE/DSHA-Sunset-mod-3090-Q"
BRANCH = "Sunset-3090-Q"
WORKFLOW = ".github/workflows/sunset-release.yml"
WORKFLOW_NAME = "Sunset Standard Release delivery"
PACKAGE = "io.github.sunsetrne.dsha.test"
MOD_CERT = "a4b0413c67fa46dd30dc7e2bce3ad56151ed8bfde2a3315fff685baf786b5d5d"
SHA = re.compile(r"[a-f0-9]{40}")
DIGEST = re.compile(r"[a-f0-9]{64}")
POSITIVE = re.compile(r"[1-9][0-9]*")
PREFIX = "/repos/" + REPOSITORY
REQUIRED_STEPS = (
    "Verify checked-in contracts",
    "Branch regression without fixed-input dependencies",
    "Verify runtime patch upgrades and managed recovery consent",
    "Complete Standard unit tests with fixed inputs",
    "Verify all seven Standard external-fixture tests executed",
    "Verify complete Java formatting gate",
    "Lint Standard release",
    "Build unsigned Sunset test package",
    "Sign with test key and verify identity",
)
RECEIPTS = (
    "build-info.json", "full-unit-tests.json", "apk-signature.txt", "apk-badging.txt",
    "sunset-test-cert.pem", "apk-runtime-verification.txt", "apk-recovery-verification.txt",
)
JUNIT_PREFIX = "app/build/test-results/testStandardDebugUnitTest/"
LINT = "app/build/reports/lint-results-standardRelease.xml"
HOST_REPORTS = {
    "app/build/host-tests/manifest.json": "fast",
    "app/build/host-tests/sunset-patch-chains/manifest.json": "runtime",
}
FIXTURES = {
    ("com.deepseekharness.app.runtime.AdbWheelBundleTest",
     "realBundleRebuildsOnlyOmittedEntriesAndPreservesRestoredWheel"),
    *(("com.deepseekharness.app.runtime.RuntimeToolsRegistryAndroidTest", method) for method in (
        "completeProductionChainAppliesAllSpecializedStagesAndIsRepeatable",
        "lanRetirementRejectsModuleDriftAndPreservesOriginalBytes",
        "lanRetirementRejectsUnknownVersionAndContractDrift",
        "oldLanExactReplacementAndIdempotenceRemainStrict",
        "previousRuntimeGenerationCannotReceiveCurrentOverlayInPlace",
    )),
    ("com.deepseekharness.app.util.ManagedPatchRegistryIntegrationTest",
     "everyActiveModuleRepairsAsAWholeAndRetiredUiNeverRuns"),
}


class DeliveryError(ValueError):
    """仅包含受控诊断，不拼接 HTTP 正文、签名 URL 或异常原文。"""


def require(condition, message):
    if not condition:
        raise DeliveryError(message)


def obj(value, label):
    require(isinstance(value, dict), label + ": expected object")
    return value


def positive_id(value):
    return type(value) is int and value > 0


def json_value(data, label):
    def unique(pairs):
        value = {}
        for key, item in pairs:
            require(key not in value, label + ": duplicate JSON key")
            value[key] = item
        return value
    try:
        return json.loads(data, object_pairs_hook=unique)
    except (ValueError, UnicodeError) as error:
        if isinstance(error, DeliveryError):
            raise
        raise DeliveryError(label + ": invalid JSON") from None


def digest_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def identity(environment, tag, expected_sha):
    require(isinstance(tag, str) and re.fullmatch(r"sunset-build206-[0-9]{8}-[0-9]{6}", tag),
            "TAG_FORMAT: expected sunset-build206-YYYYMMDD-HHMMSS")
    try:
        datetime.strptime(tag, "sunset-build206-%Y%m%d-%H%M%S")
    except ValueError:
        raise DeliveryError("TAG_DATE: invalid calendar date/time") from None
    require(isinstance(expected_sha, str) and SHA.fullmatch(expected_sha), "EXPECTED_SHA: full lowercase commit required")
    for name, expected in (("GITHUB_REPOSITORY", REPOSITORY), ("GITHUB_REF", "refs/heads/" + BRANCH),
                           ("GITHUB_SHA", expected_sha), ("GITHUB_EVENT_NAME", "workflow_dispatch")):
        require(environment.get(name) == expected, "IDENTITY: " + name + " mismatch")
    for name, expected in (
        ("GITHUB_SERVER_URL", "https://github.com"), ("GITHUB_API_URL", "https://api.github.com"),
        ("GITHUB_WORKFLOW_REF", f"{REPOSITORY}/{WORKFLOW}@refs/heads/{BRANCH}"),
        ("GITHUB_WORKFLOW_SHA", expected_sha),
    ):
        require(environment.get(name, expected) == expected, "IDENTITY: " + name + " mismatch")
    run_id, attempt = environment.get("GITHUB_RUN_ID", ""), environment.get("GITHUB_RUN_ATTEMPT", "")
    require(POSITIVE.fullmatch(run_id) and POSITIVE.fullmatch(attempt), "IDENTITY: invalid run/attempt")
    event_path = environment.get("GITHUB_EVENT_PATH", "")
    require(event_path, "IDENTITY: GITHUB_EVENT_PATH required")
    try:
        event = obj(json_value(Path(event_path).read_bytes(), "EVENT"), "EVENT")
    except OSError:
        raise DeliveryError("EVENT: cannot read Actions event") from None
    repo = obj(event.get("repository"), "EVENT repository")
    require(repo.get("full_name") == REPOSITORY and repo.get("private") is True
            and positive_id(repo.get("id")), "EVENT: private repository identity mismatch")
    if "ref" in event:
        require(event["ref"] in (BRANCH, "refs/heads/" + BRANCH), "EVENT: branch mismatch")
    return dict(tag=tag, commit=expected_sha, runId=int(run_id), runAttempt=int(attempt), repositoryId=repo["id"])


def parse_xml(data, label):
    require(b"<!DOCTYPE" not in data.upper() and b"<!ENTITY" not in data.upper(), label + ": XML declarations forbidden")
    try:
        return ET.fromstring(data)
    except ET.ParseError:
        raise DeliveryError(label + ": invalid XML") from None


def verify_junit(archive, names, summary):
    reports = sorted(name for name in names if name.startswith(JUNIT_PREFIX) and name.endswith(".xml"))
    require(reports, "EVIDENCE: full Standard JUnit reports missing")
    counts, cases = dict(tests=0, failures=0, errors=0, skipped=0), []
    for name in reports:
        suite = parse_xml(archive.read(name), "JUNIT")
        require(suite.tag == "testsuite", "JUNIT: testsuite required")
        current = suite.findall("testcase")
        measured = dict(tests=len(current), failures=sum(case.find("failure") is not None for case in current),
                        errors=sum(case.find("error") is not None for case in current),
                        skipped=sum(case.find("skipped") is not None for case in current))
        for key, number in measured.items():
            require(suite.get(key) == str(number), "JUNIT: declared counts disagree with cases")
            counts[key] += number
        cases.extend(current)
    require(all(counts[key] == summary[key] for key in counts), "JUNIT: full-unit-tests summary mismatch")
    # 单 Standard 变体：没有已批准的平台跳过名单，任何 skip 都视为未执行。
    skipped = [(case.get("classname"), case.get("name")) for case in cases if case.find("skipped") is not None]
    require(not skipped, "JUNIT: unexpected skip")
    for fixture in FIXTURES:
        matches = [case for case in cases if (case.get("classname"), case.get("name")) == fixture]
        require(len(matches) == 1 and all(matches[0].find(key) is None for key in ("failure", "error", "skipped")),
                "JUNIT: required fixture must pass exactly once")


def verify_evidence(path, receipts, summary):
    try:
        with zipfile.ZipFile(path) as archive:
            members, names = archive.infolist(), set()
            require(len(members) <= 10000 and sum(item.file_size for item in members) <= 1024 ** 3,
                    "EVIDENCE: archive exceeds verification budget")
            for item in members:
                name = item.filename
                require(name and not name.startswith("/") and "\\" not in name and ".." not in PurePosixPath(name).parts
                        and str(PurePosixPath(name)) == name.rstrip("/"), "EVIDENCE: unsafe member path")
                require(name not in names, "EVIDENCE: duplicate archive member")
                names.add(name)
                require(not stat.S_ISLNK(item.external_attr >> 16) and not item.flag_bits & 1,
                        "EVIDENCE: symlink/encrypted member forbidden")
                require(item.file_size <= 64 * 1024 ** 2, "EVIDENCE: member exceeds verification budget")
                if item.is_dir():
                    continue
                allowed = (name in {"out/" + key for key in RECEIPTS}
                           or re.fullmatch(re.escape(JUNIT_PREFIX) + r"TEST-[^/]+\.xml", name)
                           or re.fullmatch(r"app/build/reports/lint-results-standardRelease\.(xml|html|txt)", name)
                           or re.fullmatch(r"app/build/host-tests/(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+\.(json|xml|log)", name))
                require(allowed, "EVIDENCE: unexpected archive file")
            for name, content in receipts.items():
                require("out/" + name in names and archive.read("out/" + name) == content,
                        "EVIDENCE: receipt missing or differs: " + name)
            verify_junit(archive, names, summary)
            require(LINT in names, "EVIDENCE: Standard release lint XML missing")
            lint = parse_xml(archive.read(LINT), "LINT")
            require(lint.tag == "issues", "LINT: issues root required")
            require(all(issue.get("severity") in ("Warning", "Information", "Ignore") for issue in lint.findall("issue")),
                    "LINT: error/fatal or unknown severity")
            for name, stage in HOST_REPORTS.items():
                require(name in names, "HOST: required report missing: " + stage)
                report = obj(json_value(archive.read(name), "HOST"), "HOST")
                require(report.get("schema") == 2 and report.get("status") == "PASS_FOR_SELECTED_STAGES"
                        and report.get("requestedStage") == stage and report.get("selectedStages") == [stage]
                        and report.get("failures") == [], "HOST: incomplete/failed " + stage + " report")
                tests = report.get("tests")
                require(isinstance(tests, list) and tests, "HOST: empty test report")
                seen = set()
                for test in tests:
                    obj(test, "HOST test")
                    test_path, log = test.get("path"), test.get("log")
                    require(isinstance(test_path, str) and test_path not in seen and test.get("status") == "PASS"
                            and type(test.get("exitCode")) is int and test["exitCode"] == 0, "HOST: test did not pass")
                    seen.add(test_path)
                    require(isinstance(log, str) and log in names and log.startswith(str(PurePosixPath(name).parent) + "/")
                            and log.endswith(".log") and test.get("logSha256") == hashlib.sha256(archive.read(log)).hexdigest(),
                            "HOST: test log missing or digest differs")
                checks = report.get("runtimeFixtureVerification")
                require(isinstance(checks, list) and (stage != "runtime" or checks)
                        and all(isinstance(check, dict) and check.get("status") == "PASS_FINAL_COMPLETE_BYTE_RECHECK"
                                for check in checks), "HOST: final runtime fixture verification missing/failed")
    except (OSError, zipfile.BadZipFile, RuntimeError, KeyError):
        raise DeliveryError("EVIDENCE: unreadable or corrupt ZIP") from None


def prepare(environment, tag, expected_sha, output):
    plan = identity(environment, tag, expected_sha)
    output = Path(output)
    require(output.is_dir() and not output.is_symlink(), "OUTPUT: regular directory required")
    apk = f"dsha-sunset-{expected_sha[:8]}.apk"
    upload_names = (apk, "sunset-evidence.zip", "build-info.json", "sunset-test-cert.pem", "SHA256SUMS")
    paths = {}
    for name in set(upload_names) | set(RECEIPTS):
        path = output / name
        require(path.exists() and stat.S_ISREG(path.lstat().st_mode) and path.stat().st_size > 0,
                "FILE: missing/empty/nonregular " + name)
        if name in RECEIPTS or name == "SHA256SUMS":
            require(path.stat().st_size <= 4 * 1024 ** 2, "FILE: oversized receipt " + name)
        paths[name] = path
    receipts = {name: paths[name].read_bytes() for name in RECEIPTS}
    info = obj(json_value(receipts["build-info.json"], "BUILD_INFO"), "BUILD_INFO")
    require(info.get("schema") == 1 and info.get("kind") == "sunset-3090-q-test-apk"
            and info.get("flavors") == ["standard"], "BUILD_INFO: Standard test APK required")
    require(info.get("commit") == expected_sha and info.get("runId") == str(plan["runId"])
            and info.get("runAttempt") == str(plan["runAttempt"]), "BUILD_INFO: commit/run/attempt mismatch")
    require(info.get("apkFile") == apk and info.get("packageName") == PACKAGE, "BUILD_INFO: APK identity mismatch")
    version = info.get("versionName")
    require(isinstance(version, str) and version.endswith("-SunsetTest"), "BUILD_INFO: -SunsetTest version required")
    badging = receipts["apk-badging.txt"].decode("utf-8")
    packages = re.findall(r"^package: (.+)$", badging, re.MULTILINE)
    require(len(packages) == 1 and "application-debuggable" not in badging, "BADGING: nondebuggable package required")
    attributes = re.findall(r"([A-Za-z0-9_]+)='([^']*)'", packages[0])
    require(len(dict(attributes)) == len(attributes), "BADGING: duplicate package attributes")
    attributes = dict(attributes)
    require(attributes.get("name") == PACKAGE and attributes.get("versionCode") == "206"
            and attributes.get("versionName") == version, "BADGING: package/versionCode/versionName mismatch")
    require(type(info.get("versionCode")) is int and info["versionCode"] == 206, "BUILD_INFO: versionCode mismatch")
    certificate = info.get("certificateSha256", "")
    require(isinstance(certificate, str) and DIGEST.fullmatch(certificate) and certificate != MOD_CERT,
            "CERTIFICATE: test-only non-Mod-release SHA-256 required")
    match = re.fullmatch(rb"\s*-----BEGIN CERTIFICATE-----\s+([A-Za-z0-9+/=\r\n]+)\s+-----END CERTIFICATE-----\s*",
                         receipts["sunset-test-cert.pem"])
    require(match, "CERTIFICATE: one public PEM certificate required")
    try:
        der = base64.b64decode(re.sub(rb"\s", b"", match[1]), validate=True)
    except ValueError:
        raise DeliveryError("CERTIFICATE: malformed public PEM") from None
    require(der.startswith(b"\x30") and hashlib.sha256(der).hexdigest() == certificate,
            "CERTIFICATE: public DER digest differs")
    signature = receipts["apk-signature.txt"].decode("utf-8")
    signers = re.findall(r"^Signer #([0-9]+) certificate SHA-256 digest: ([a-fA-F0-9]+)$", signature, re.MULTILINE)
    require(signers == [("1", certificate)] and re.search(r"^Number of signers: 1$", signature, re.MULTILINE)
            and re.search(r"^Verifies$", signature, re.MULTILINE)
            and re.search(r"^Verified using v[23] scheme .*: true$", signature, re.MULTILINE)
            and "DOES NOT VERIFY" not in signature, "SIGNATURE: verified single test signer required")
    tests = obj(json_value(receipts["full-unit-tests.json"], "UNIT_TESTS"), "UNIT_TESTS")
    require(info.get("unitTests") == tests and tests.get("mode") == "full"
            and isinstance(tests.get("flavors"), dict) and set(tests["flavors"]) == {"Standard"},
            "UNIT_TESTS: full Standard evidence required")
    summary = obj(tests["flavors"]["Standard"], "UNIT_TESTS Standard")
    keys = ("tests", "failures", "errors", "skipped", "requiredFixtureTestsPassed")
    require(all(type(summary.get(key)) is int for key in keys) and summary["tests"] > 0
            and summary["failures"] == summary["errors"] == 0 and summary["skipped"] in (0, 1)
            and summary["requiredFixtureTestsPassed"] == 7, "UNIT_TESTS: failures/errors/skips/fixtures mismatch")
    require(zipfile.is_zipfile(paths[apk]), "APK: ZIP package required")
    with zipfile.ZipFile(paths[apk]) as archive:
        require(archive.namelist().count("AndroidManifest.xml") == 1, "APK: unique Android manifest required")
    verify_evidence(paths["sunset-evidence.zip"], receipts, summary)
    assets = [dict(name=name, path=paths[name], size=paths[name].stat().st_size, sha256=digest_file(paths[name]))
              for name in upload_names]
    checksums = {}
    for line in paths["SHA256SUMS"].read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([a-f0-9]{64}) [ *]([A-Za-z0-9_.-]+)", line)
        require(match and match[2] not in checksums, "CHECKSUMS: malformed/duplicate entry")
        checksums[match[2]] = match[1]
    require(checksums == {asset["name"]: asset["sha256"] for asset in assets if asset["name"] != "SHA256SUMS"},
            "CHECKSUMS: exact four-file digest set required")
    require(info.get("apkSha256") == assets[0]["sha256"], "BUILD_INFO: APK digest mismatch")
    require(type(info.get("apkSize")) is int and info["apkSize"] == assets[0]["size"], "BUILD_INFO: APK size mismatch")
    plan.update(assets=assets, versionName=version, certificateSha256=certificate)
    return plan


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        # 不跟随任何重定向，不向签名下载 URL 转发鉴权，也不重放写入。
        request.remove_header("Authorization")
        return None


class GitHubClient:
    def __init__(self, token):
        require(isinstance(token, str) and token and "\n" not in token and "\r" not in token, "AUTH: GH_TOKEN required")
        self.token = token
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, method, endpoint, payload=None, asset=None, missing=False):
        require(method in ("GET", "POST", "PATCH") and (endpoint == PREFIX or endpoint.startswith(PREFIX + "/"))
                and not any(char in endpoint for char in ("#", "\\", "\r", "\n")) and ".." not in endpoint,
                "API: endpoint forbidden")
        require(not missing or method == "GET", "API: missing is read-only")
        host = "uploads.github.com" if asset is not None else "api.github.com"
        if asset is not None:
            require(method == "POST" and re.fullmatch(re.escape(PREFIX) + r"/releases/[1-9][0-9]*/assets\?name=[A-Za-z0-9_.-]+", endpoint),
                    "API: upload endpoint forbidden")
        headers = {"Authorization": "Bearer " + self.token, "Accept": "application/vnd.github+json",
                   "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "dsha-sunset-release-delivery"}
        stream = None
        try:
            data = None
            if asset is not None:
                stream = asset["path"].open("rb")
                data = stream
                headers.update({"Content-Type": "application/octet-stream", "Content-Length": str(asset["size"])})
            elif payload is not None:
                data = json.dumps(payload).encode("utf-8")
                headers["Content-Type"] = "application/json"
            request = urllib.request.Request("https://" + host + endpoint, data=data, headers=headers, method=method)
            with self.opener.open(request, timeout=300) as response:
                require(response.status == (201 if method == "POST" else 200), "API: unexpected HTTP status")
                data = response.read(16 * 1024 ** 2 + 1)
                require(len(data) <= 16 * 1024 ** 2, "API: response exceeds budget")
                return json_value(data, "API response")
        except urllib.error.HTTPError as error:
            if missing and error.code == 404:
                return None
            raise DeliveryError(f"API {method}: HTTP {error.code}; request not retried") from None
        except DeliveryError:
            raise
        except Exception:
            raise DeliveryError(f"API {method}: outcome unknown; request not retried, inspect remote state") from None
        finally:
            if stream is not None:
                stream.close()


def verify_remote_identity(client, plan):
    repo = obj(client.request("GET", PREFIX), "API repository")
    require(repo.get("full_name") == REPOSITORY and repo.get("private") is True
            and repo.get("id") == plan["repositoryId"], "REMOTE: private repository mismatch")
    commit = obj(client.request("GET", PREFIX + "/commits/" + plan["commit"]), "API commit")
    require(commit.get("sha") == plan["commit"], "REMOTE: commit mismatch")
    run = obj(client.request("GET", PREFIX + f"/actions/runs/{plan['runId']}"), "API run")
    require(run.get("id") == plan["runId"] and run.get("run_attempt") == plan["runAttempt"]
            and run.get("head_sha") == plan["commit"] and run.get("head_branch") == BRANCH
            and run.get("event") == "workflow_dispatch" and run.get("path") == WORKFLOW
            and run.get("name") == WORKFLOW_NAME and run.get("status") == "in_progress"
            and run.get("conclusion") is None, "REMOTE: current run/workflow/commit/attempt mismatch")
    for key in ("repository", "head_repository"):
        value = obj(run.get(key), "API run " + key)
        require(value.get("id") == plan["repositoryId"] and value.get("full_name") == REPOSITORY,
                "REMOTE: fork/repository mismatch")
    require(positive_id(run.get("workflow_id")), "REMOTE: workflow id missing")
    workflow = obj(client.request("GET", PREFIX + f"/actions/workflows/{run['workflow_id']}"), "API workflow")
    require(workflow.get("id") == run["workflow_id"] and workflow.get("path") == WORKFLOW
            and workflow.get("name") == WORKFLOW_NAME and workflow.get("state") == "active", "REMOTE: workflow identity mismatch")
    jobs = obj(client.request("GET", PREFIX + f"/actions/runs/{plan['runId']}/attempts/{plan['runAttempt']}/jobs?per_page=100"), "API jobs")
    require(jobs.get("total_count") == 1 and isinstance(jobs.get("jobs"), list) and len(jobs["jobs"]) == 1,
            "REMOTE: single complete current-attempt job required")
    job = obj(jobs["jobs"][0], "API job")
    require(job.get("run_id") == plan["runId"] and job.get("head_sha") == plan["commit"]
            and job.get("status") == "in_progress" and job.get("conclusion") is None, "REMOTE: active job identity mismatch")
    steps = job.get("steps")
    require(isinstance(steps, list) and all(isinstance(step, dict) for step in steps), "REMOTE: job steps missing")
    require(not any(step.get("conclusion") in ("failure", "cancelled", "timed_out", "action_required") for step in steps),
            "REMOTE: job contains failed step")
    for name in REQUIRED_STEPS:
        matches = [step for step in steps if step.get("name") == name]
        require(len(matches) == 1 and matches[0].get("status") == "completed" and matches[0].get("conclusion") == "success",
                "REMOTE: required step not successful: " + name)


def require_new_tag(client, plan):
    require(client.request("GET", PREFIX + "/git/ref/tags/" + plan["tag"], missing=True) is None,
            "TAG_EXISTS: existing tag must never be reused")
    require(client.request("GET", PREFIX + "/releases/tags/" + plan["tag"], missing=True) is None,
            "RELEASE_EXISTS: existing release must never be changed")
    # 按 tag 查询可能不返回 draft，完整分页排除已有草稿；权限失败不能当不存在。
    for page in range(1, 101):
        rows = client.request("GET", PREFIX + f"/releases?per_page=100&page={page}")
        require(isinstance(rows, list) and all(isinstance(item, dict) for item in rows), "API: invalid release listing")
        require(all(item.get("tag_name") != plan["tag"] for item in rows), "RELEASE_EXISTS: existing draft/release")
        if len(rows) < 100:
            return
    raise DeliveryError("API: release pagination incomplete")


def verify_ref(value, plan):
    obj(value, "API tag")
    target = obj(value.get("object"), "API tag target")
    require(value.get("ref") == "refs/tags/" + plan["tag"] and target.get("type") == "commit"
            and target.get("sha") == plan["commit"], "REMOTE: tag target mismatch")


def verify_release(value, plan, release_id=None, draft=True):
    obj(value, "API release")
    require(positive_id(value.get("id")) and (release_id is None or value["id"] == release_id)
            and value.get("tag_name") == plan["tag"] and value.get("target_commitish") == plan["commit"]
            and value.get("draft") is draft and value.get("prerelease") is True, "REMOTE: release identity/state mismatch")
    return value["id"]


def verify_asset(remote, asset, asset_id=None):
    obj(remote, "API asset")
    require(positive_id(remote.get("id")) and (asset_id is None or remote["id"] == asset_id)
            and remote.get("name") == asset["name"] and remote.get("state") == "uploaded"
            and type(remote.get("size")) is int and remote["size"] == asset["size"]
            and remote.get("digest") == "sha256:" + asset["sha256"],
            "ASSET: remote id/name/state/size/digest mismatch: " + asset["name"])
    return remote["id"]


def verify_assets(client, release_id, uploaded):
    rows = client.request("GET", PREFIX + f"/releases/{release_id}/assets?per_page=100")
    require(isinstance(rows, list) and len(rows) == len(uploaded), "ASSETS: incomplete/extra/duplicate remote files")
    by_name, ids = {}, set()
    for row in rows:
        obj(row, "API asset")
        name = row.get("name")
        require(isinstance(name, str) and name not in by_name and positive_id(row.get("id")) and row["id"] not in ids,
                "ASSETS: duplicate remote name/id")
        by_name[name] = row
        ids.add(row["id"])
    require(set(by_name) == {asset["name"] for asset, _ in uploaded}, "ASSETS: whitelist differs")
    for asset, asset_id in uploaded:
        verify_asset(by_name[asset["name"]], asset, asset_id)


def unchanged(asset):
    require(not asset["path"].is_symlink() and asset["path"].stat().st_size == asset["size"]
            and digest_file(asset["path"]) == asset["sha256"], "FILE: changed after verification: " + asset["name"])


def publish(plan, client):
    stage, release_id = "read-only preflight", None
    try:
        verify_remote_identity(client, plan)
        require_new_tag(client, plan)
        for asset in plan["assets"]:
            unchanged(asset)
        stage = "create new tag"
        verify_ref(client.request("POST", PREFIX + "/git/refs", payload=dict(ref="refs/tags/" + plan["tag"], sha=plan["commit"])), plan)
        stage = "create draft release"
        body = (f"Standard 测试 APK，版本码 206，包名 `{PACKAGE}`；使用测试证书，非 Mod 正式签名。\n\n"
                f"源码提交：`{plan['commit']}`\n"
                f"[本次 Actions 运行，attempt {plan['runAttempt']}](https://github.com/{REPOSITORY}/actions/runs/{plan['runId']}/attempts/{plan['runAttempt']})\n\n"
                "完整 Standard 单测、七项固定夹具、宿主回归、格式、Lint 与签名门禁均已通过。\n"
                "本预发布仅用于测试；本次流水线未安装手机，不代表设备验收。")
        created = client.request("POST", PREFIX + "/releases", payload=dict(
            tag_name=plan["tag"], target_commitish=plan["commit"], name="Sunset Standard 测试预发布 " + plan["tag"],
            body=body, draft=True, prerelease=True, make_latest="false"))
        release_id = verify_release(created, plan)
        uploaded = []
        for asset in plan["assets"]:
            stage = "upload " + asset["name"]
            verify_release(client.request("GET", PREFIX + f"/releases/{release_id}"), plan, release_id)
            verify_assets(client, release_id, uploaded)
            unchanged(asset)
            endpoint = PREFIX + f"/releases/{release_id}/assets?name=" + urllib.parse.quote(asset["name"], safe="")
            asset_id = verify_asset(client.request("POST", endpoint, asset=asset), asset)
            require(asset_id not in {value for _, value in uploaded}, "ASSET: reused id")
            verify_asset(client.request("GET", PREFIX + f"/releases/assets/{asset_id}"), asset, asset_id)
            uploaded.append((asset, asset_id))
        stage = "verify complete draft"
        verify_assets(client, release_id, uploaded)
        verify_remote_identity(client, plan)
        verify_ref(client.request("GET", PREFIX + "/git/ref/tags/" + plan["tag"]), plan)
        verify_release(client.request("GET", PREFIX + f"/releases/{release_id}"), plan, release_id)
        stage = "publish draft (outcome may be unknown)"
        published = client.request("PATCH", PREFIX + f"/releases/{release_id}",
                                   payload=dict(draft=False, prerelease=True, make_latest="false"))
        verify_release(published, plan, release_id, draft=False)
        stage = "verify published release"
        verify_release(client.request("GET", PREFIX + f"/releases/{release_id}"), plan, release_id, draft=False)
        verify_assets(client, release_id, uploaded)
        verify_ref(client.request("GET", PREFIX + "/git/ref/tags/" + plan["tag"]), plan)
        return dict(tag=plan["tag"], commit=plan["commit"], runId=plan["runId"], runAttempt=plan["runAttempt"],
                    releaseId=release_id, url=f"https://github.com/{REPOSITORY}/releases/tag/{plan['tag']}",
                    assets=[dict(name=asset["name"], id=asset_id, size=asset["size"], sha256=asset["sha256"])
                            for asset, asset_id in uploaded])
    except Exception as error:
        detail = str(error) if isinstance(error, DeliveryError) else "unexpected failure; remote outcome may be unknown"
        raise DeliveryError(f"DELIVERY_STOPPED: {stage}; tag={plan['tag']}; releaseId={release_id or 'unknown'}; {detail}. "
                            "Preserved any created tag/release; no cleanup, overwrite or retry. Inspect remote state before any further action.") from None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--output", type=Path, default=Path("out"))
    args = parser.parse_args(argv)
    try:
        plan = prepare(os.environ, args.tag, args.expected_sha, args.output)
        result = publish(plan, GitHubClient(os.environ.get("GH_TOKEN", "")))
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except Exception as error:
        print(str(error) if isinstance(error, DeliveryError) else "DELIVERY_STOPPED: local validation failed; no retry", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
