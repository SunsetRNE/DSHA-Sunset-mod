#!/usr/bin/env python3
"""离线 fixtures + 假 GitHub client：身份、证据、只创建一次与不完整上传门禁。"""
import base64
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import socket
import sys
import tempfile
import unittest
from unittest import mock
import urllib.error
import urllib.request
import warnings
import xml.etree.ElementTree as ET
import zipfile

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("sunset_release_delivery", Path(__file__).with_name("sunset-release-delivery.py"))
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
COMMIT = "abcdef12" + "3" * 32
TAG = "sunset-build206-20261028-123456"
P = release.PREFIX


def encoded(value):
    return (json.dumps(value, sort_keys=True) + "\n").encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


class Fixture:
    def __init__(self, root):
        self.root, self.out = root, root / "out"
        self.out.mkdir(exist_ok=True)
        self.apk = f"dsha-sunset-{COMMIT[:8]}.apk"
        self.env = dict(GITHUB_REPOSITORY=release.REPOSITORY, GITHUB_REF="refs/heads/" + release.BRANCH,
                        GITHUB_SHA=COMMIT, GITHUB_EVENT_NAME="workflow_dispatch", GITHUB_RUN_ID="1234",
                        GITHUB_RUN_ATTEMPT="2", GITHUB_EVENT_PATH=str(root / "event.json"))
        self.event = dict(repository=dict(id=17, full_name=release.REPOSITORY, private=True), ref=release.BRANCH)
        (root / "event.json").write_bytes(encoded(self.event))
        # 仅模拟上游签名/Android 工具的回执；不是实际签名 APK，也没有私钥。
        der = b"\x30\x03\x02\x01\x01"
        certificate = digest(der)
        (self.out / "sunset-test-cert.pem").write_bytes(
            b"-----BEGIN CERTIFICATE-----\n" + base64.b64encode(der) + b"\n-----END CERTIFICATE-----\n")
        with zipfile.ZipFile(self.out / self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"synthetic binary manifest")
            archive.writestr("classes.dex", b"synthetic fixture")
        self.tests = dict(mode="full", flavors=dict(Standard=dict(
            tests=7, failures=0, errors=0, skipped=0, requiredFixtureTestsPassed=7)))
        self.info = dict(schema=1, kind="sunset-3090-q-test-apk", flavors=["standard"], commit=COMMIT,
                         runId="1234", runAttempt="2", apkFile=self.apk, packageName=release.PACKAGE,
                         versionCode=206, versionName="0.2.1-rc.3-SunsetTest", certificateSha256=certificate,
                         apkSha256=digest((self.out / self.apk).read_bytes()), apkSize=(self.out / self.apk).stat().st_size,
                         unitTests=self.tests)
        (self.out / "build-info.json").write_bytes(encoded(self.info))
        (self.out / "full-unit-tests.json").write_bytes(encoded(self.tests))
        (self.out / "apk-badging.txt").write_text(
            f"package: name='{release.PACKAGE}' versionCode='206' versionName='{self.info['versionName']}'\nsdkVersion:'30'\n")
        (self.out / "apk-signature.txt").write_text(
            "Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\nNumber of signers: 1\n"
            f"Signer #1 certificate SHA-256 digest: {certificate}\n")
        for name in ("apk-runtime-verification.txt", "apk-recovery-verification.txt"):
            (self.out / name).write_text("synthetic upstream verification succeeded\n")
        suite = ET.Element("testsuite", tests="7", failures="0", errors="0", skipped="0")
        for owner, name in sorted(release.FIXTURES):
            ET.SubElement(suite, "testcase", classname=owner, name=name)
        self.junit = release.JUNIT_PREFIX + "TEST-fixture.xml"
        self.entries = {self.junit: ET.tostring(suite), release.LINT: b'<issues format="6"><issue severity="Warning"/></issues>'}
        for name, stage in release.HOST_REPORTS.items():
            log = str(Path(name).parent / ("test-" + stage + ".py.log"))
            self.entries[log] = b"synthetic host regression passed\n"
            self.entries[name] = encoded(dict(
                schema=2, requestedStage=stage, selectedStages=[stage], status="PASS_FOR_SELECTED_STAGES", failures=[],
                tests=[dict(path="tools/test-" + stage + ".py", status="PASS", exitCode=0, log=log,
                            logSha256=digest(self.entries[log]))],
                runtimeFixtureVerification=([dict(status="PASS_FINAL_COMPLETE_BYTE_RECHECK")] if stage == "runtime" else [])))
        self.pack()

    def pack(self):
        self.entries.update({"out/" + name: (self.out / name).read_bytes() for name in release.RECEIPTS})
        with zipfile.ZipFile(self.out / "sunset-evidence.zip", "w", zipfile.ZIP_DEFLATED) as archive:
            for name, value in self.entries.items():
                archive.writestr(name, value)
        self.checksums()

    def checksums(self):
        names = (self.apk, "sunset-evidence.zip", "build-info.json", "sunset-test-cert.pem")
        (self.out / "SHA256SUMS").write_text("".join(digest((self.out / name).read_bytes()) + "  " + name + "\n" for name in names))

    def change_info(self, key, value):
        self.info[key] = value
        (self.out / "build-info.json").write_bytes(encoded(self.info))
        self.pack()

    def plan(self):
        return release.prepare(self.env, TAG, COMMIT, self.out)


class FakeClient:
    """同步内存中的 GitHub 状态，支持写后丢回执；不调用真实传输层。"""
    def __init__(self):
        self.calls, self.assets, self.pages = [], [], {1: []}
        self.ref, self.release = None, None
        self.repo = dict(id=17, full_name=release.REPOSITORY, private=True)
        self.run = dict(id=1234, run_attempt=2, head_sha=COMMIT, head_branch=release.BRANCH,
                        event="workflow_dispatch", path=release.WORKFLOW, name=release.WORKFLOW_NAME,
                        workflow_id=55, status="in_progress", conclusion=None, repository=self.repo, head_repository=self.repo)
        self.workflow = dict(id=55, name=release.WORKFLOW_NAME, path=release.WORKFLOW, state="active")
        self.jobs = dict(total_count=1, jobs=[dict(run_id=1234, head_sha=COMMIT, status="in_progress", conclusion=None,
                         steps=[dict(name=name, status="completed", conclusion="success") for name in release.REQUIRED_STEPS])])
        self.commit = dict(sha=COMMIT)
        self.override = {}
        self.transform = lambda method, endpoint, result: result

    @property
    def writes(self):
        return [call for call in self.calls if call[0] != "GET"]

    def request(self, method, endpoint, payload=None, asset=None, missing=False):
        self.calls.append((method, endpoint, copy.deepcopy(payload), asset["name"] if asset else None))
        key = (method, endpoint)
        if key in self.override:
            result = self.override[key]
            if isinstance(result, Exception):
                raise result
        elif method == "GET":
            if endpoint == P:
                result = self.repo
            elif endpoint == P + "/commits/" + COMMIT:
                result = self.commit
            elif endpoint == P + "/actions/runs/1234":
                result = self.run
            elif endpoint == P + "/actions/workflows/55":
                result = self.workflow
            elif endpoint == P + "/actions/runs/1234/attempts/2/jobs?per_page=100":
                result = self.jobs
            elif endpoint == P + "/git/ref/tags/" + TAG:
                result = self.ref
            elif endpoint == P + "/releases/tags/" + TAG:
                result = self.release
            elif endpoint.startswith(P + "/releases?per_page=100&page="):
                result = self.pages[int(endpoint.rsplit("=", 1)[1])]
            elif endpoint == P + "/releases/77":
                result = self.release
            elif endpoint == P + "/releases/77/assets?per_page=100":
                result = self.assets
            elif endpoint.startswith(P + "/releases/assets/"):
                result = next(row for row in self.assets if row["id"] == int(endpoint.rsplit("/", 1)[1]))
            else:
                raise AssertionError("unexpected fake GET endpoint")
        elif method == "POST" and endpoint == P + "/git/refs":
            if self.ref is not None:
                raise release.DeliveryError("HTTP 422 tag exists")
            self.ref = dict(ref=payload["ref"], object=dict(type="commit", sha=payload["sha"]))
            result = self.ref
        elif method == "POST" and endpoint == P + "/releases":
            if self.release is not None:
                raise AssertionError("existing release changed")
            self.release = dict(payload, id=77)
            result = self.release
        elif method == "POST" and asset is not None:
            assert endpoint == P + "/releases/77/assets?name=" + asset["name"]
            assert self.release["draft"] is True
            assert asset["name"] not in {item["name"] for item in self.assets}
            content = asset["path"].read_bytes()
            result = dict(id=100 + len(self.assets), name=asset["name"], state="uploaded", size=len(content), digest="sha256:" + digest(content))
            self.assets.append(result)
        elif method == "PATCH" and endpoint == P + "/releases/77":
            assert len(self.assets) == 5 and self.release["draft"] is True
            self.release.update(payload)
            result = self.release
        else:
            raise AssertionError("unexpected fake mutation")
        return self.transform(method, endpoint, copy.deepcopy(result))


class FixtureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="sunset-release-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.fixture = Fixture(self.root)
        self.addCleanup(mock.patch.stopall)
        mock.patch.object(socket, "create_connection", side_effect=AssertionError("NETWORK_FORBIDDEN")).start()

    def reset(self):
        self.fixture = Fixture(self.root)
        return self.fixture

class LocalTests(FixtureTests):
    def test_complete_local_bundle_has_only_five_uploads(self):
        (self.fixture.out / "unrelated.txt").write_text("not an upload")
        plan = self.fixture.plan()
        self.assertEqual([asset["name"] for asset in plan["assets"]], [self.fixture.apk, "sunset-evidence.zip", "build-info.json", "sunset-test-cert.pem", "SHA256SUMS"])
        self.assertEqual(plan["runAttempt"], 2)
        self.assertNotEqual(plan["certificateSha256"], release.MOD_CERT)

    def test_local_identity_mismatches_fail(self):
        for key, value in (("GITHUB_REPOSITORY", "DSH-APP/DSHA"), ("GITHUB_REF", "refs/tags/test"),
                           ("GITHUB_SHA", "1" * 40), ("GITHUB_EVENT_NAME", "pull_request"),
                           ("GITHUB_RUN_ID", "0"), ("GITHUB_RUN_ATTEMPT", "2\n"),
                           ("GITHUB_WORKFLOW_REF", "foreign"), ("GITHUB_WORKFLOW_SHA", "f" * 40),
                           ("GITHUB_API_URL", "https://attacker.invalid")):
            with self.subTest(key=key), self.assertRaisesRegex(release.DeliveryError, "IDENTITY"):
                release.prepare(dict(self.fixture.env, **{key: value}), TAG, COMMIT, self.fixture.out)

    def test_event_private_repo_id_and_ref_are_required(self):
        for key, value in (("private", False), ("private", "true"), ("full_name", "other/repo"), ("id", 0)):
            event = copy.deepcopy(self.fixture.event)
            event["repository"][key] = value
            (self.root / "event.json").write_bytes(encoded(event))
            with self.subTest(key=key, value=value), self.assertRaisesRegex(release.DeliveryError, "EVENT"):
                self.fixture.plan()
        event = dict(self.fixture.event, ref="main")
        (self.root / "event.json").write_bytes(encoded(event))
        with self.assertRaisesRegex(release.DeliveryError, "EVENT"):
            self.fixture.plan()

    def test_tag_date_and_full_sha_are_strict(self):
        for tag in ("v206", "sunset-build206-20260230-123456", "sunset-build206-20261028-246000", TAG + "/other", TAG + "\n"):
            with self.subTest(tag=tag), self.assertRaisesRegex(release.DeliveryError, "TAG_"):
                release.identity(self.fixture.env, tag, COMMIT)
        for sha in (COMMIT[:8], COMMIT.upper(), "", "f" * 40):
            with self.subTest(sha=sha), self.assertRaises(release.DeliveryError):
                release.identity(self.fixture.env, TAG, sha)

    def test_build_info_wrong_commit_attempt_package_flavor_or_actual_digest_fails(self):
        for key, value in (("commit", "a" * 40), ("runId", "1235"), ("runAttempt", "1"),
                           ("packageName", "io.github.sunsetrne.dsha"), ("flavors", ["low"]), ("versionCode", 205),
                           ("versionName", "0.2.1"), ("apkFile", "other.apk"), ("apkSha256", "0" * 64), ("apkSize", 1)):
            f = self.reset()
            f.change_info(key, value)
            with self.subTest(key=key), self.assertRaisesRegex(release.DeliveryError, "BUILD_INFO|BADGING"):
                f.plan()

    def test_actual_badging_package_code_debuggable_and_duplicate_rejected(self):
        for old, new in ((release.PACKAGE, "io.github.sunsetrne.dsha"), ("versionCode='206'", "versionCode='205'"),
                         ("sdkVersion:'30'", "application-debuggable"), ("versionCode='206'", "versionCode='206' versionCode='206'")):
            f = self.reset()
            path = f.out / "apk-badging.txt"
            path.write_text(path.read_text().replace(old, new))
            f.pack()
            with self.subTest(new=new), self.assertRaisesRegex(release.DeliveryError, "BADGING"):
                f.plan()

    def test_public_certificate_and_signature_are_cross_checked(self):
        for action in ("MOD_CERT", "wrong-public-cert", "private-pem", "not-verified", "multiple-signers"):
            f = self.reset()
            if action == "MOD_CERT":
                f.change_info("certificateSha256", release.MOD_CERT)
            elif action == "wrong-public-cert":
                (f.out / "sunset-test-cert.pem").write_text("-----BEGIN CERTIFICATE-----\nMAMCAQI=\n-----END CERTIFICATE-----\n")
            elif action == "private-pem":
                (f.out / "sunset-test-cert.pem").write_text("-----BEGIN PRIVATE KEY-----\nsynthetic invalid fixture\n")
            else:
                path = f.out / "apk-signature.txt"
                path.write_text(path.read_text().replace("Verifies", "DOES NOT VERIFY") if action == "not-verified"
                                else path.read_text() + "Signer #2 certificate SHA-256 digest: " + f.info["certificateSha256"] + "\n")
            f.pack()
            with self.subTest(action=action), self.assertRaisesRegex(release.DeliveryError, "CERTIFICATE|SIGNATURE"):
                f.plan()

    def test_summary_requires_full_no_failure_error_and_seven_fixtures(self):
        for key, value in (("tests", 0), ("failures", 1), ("errors", 1), ("skipped", 2), ("requiredFixtureTestsPassed", 6)):
            f = self.reset()
            f.tests["flavors"]["Standard"][key] = value
            (f.out / "full-unit-tests.json").write_bytes(encoded(f.tests))
            f.change_info("unitTests", f.tests)
            with self.subTest(key=key), self.assertRaisesRegex(release.DeliveryError, "UNIT_TESTS"):
                f.plan()
        f = self.reset()
        f.tests["mode"] = "source-only"
        (f.out / "full-unit-tests.json").write_bytes(encoded(f.tests))
        f.change_info("unitTests", f.tests)
        with self.assertRaisesRegex(release.DeliveryError, "UNIT_TESTS"):
            f.plan()

    def test_actual_junit_cases_skip_and_fixture_counts_are_verified(self):
        for action in ("missing-fixture", "duplicate-fixture", "skipped-fixture", "unexpected-skip", "failed-case", "wrong-count"):
            f = self.reset()
            root = ET.fromstring(f.entries[f.junit])
            if action == "missing-fixture":
                root[0].set("name", "notTheRequiredFixture")
            elif action == "duplicate-fixture":
                root[0].attrib.update(root[1].attrib)
            elif action == "skipped-fixture":
                ET.SubElement(root[0], "skipped")
            elif action == "unexpected-skip":
                case = ET.SubElement(root, "testcase", classname="some.other.SkippedTest",
                                     name="skippedCase")
                ET.SubElement(case, "skipped")
            elif action == "failed-case":
                ET.SubElement(root[0], "failure")
            else:
                root.set("tests", "9")
            f.entries[f.junit] = ET.tostring(root)
            f.pack()
            with self.subTest(action=action), self.assertRaisesRegex(release.DeliveryError, "JUNIT"):
                f.plan()

    def test_lint_errors_missing_report_and_host_failure_or_log_tamper_fail(self):
        for action in ("lint-error", "no-lint", "host-failed", "host-log", "fixture-invalid"):
            f = self.reset()
            if action == "lint-error":
                f.entries[release.LINT] = b'<issues><issue severity="Fatal"/></issues>'
            elif action == "no-lint":
                del f.entries[release.LINT]
            else:
                name = "app/build/host-tests/sunset-patch-chains/manifest.json"
                report = json.loads(f.entries[name])
                if action == "host-failed":
                    report["tests"][0]["exitCode"] = 1
                elif action == "fixture-invalid":
                    report["runtimeFixtureVerification"] = []
                else:
                    f.entries[report["tests"][0]["log"]] = b"tampered"
                f.entries[name] = encoded(report)
            f.pack()
            with self.subTest(action=action), self.assertRaisesRegex(release.DeliveryError, "LINT|HOST|EVIDENCE"):
                f.plan()

    def test_archive_rejects_unknown_paths_and_duplicate_receipts(self):
        for name in ("../outside", "out/signing-key.jks", "out/extra.txt", "/absolute", "out\\receipt.json"):
            f = self.reset()
            f.entries[name] = b"synthetic unknown file"
            f.pack()
            with self.subTest(name=name), self.assertRaisesRegex(release.DeliveryError, "EVIDENCE"):
                f.plan()
        f = self.reset()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(f.out / "sunset-evidence.zip", "a") as archive:
                archive.writestr("out/build-info.json", encoded(f.info))
        f.checksums()
        with self.assertRaisesRegex(release.DeliveryError, "duplicate archive"):
            f.plan()

    def test_archive_receipts_must_equal_loose_receipts(self):
        (self.fixture.out / "apk-runtime-verification.txt").write_text("different")
        with self.assertRaisesRegex(release.DeliveryError, "receipt missing or differs"):
            self.fixture.plan()

    def test_local_checksum_tampering_duplicate_and_missing_entries_fail(self):
        for action in ("digest", "duplicate", "missing"):
            f = self.reset()
            path = f.out / "SHA256SUMS"
            lines = path.read_text().splitlines()
            if action == "digest":
                lines[0] = "0" * 64 + "  " + f.apk
            elif action == "duplicate":
                lines.append(lines[0])
            else:
                lines.pop()
            path.write_text("\n".join(lines) + "\n")
            with self.subTest(action=action), self.assertRaisesRegex(release.DeliveryError, "CHECKSUMS"):
                f.plan()

    def test_invalid_local_inputs_do_not_construct_network_client(self):
        stderr = io.StringIO()
        with mock.patch.dict(release.os.environ, self.fixture.env, clear=True), mock.patch.object(release, "GitHubClient") as client, mock.patch("sys.stderr", stderr):
            code = release.main(["--tag", "bad", "--expected-sha", COMMIT, "--output", str(self.fixture.out)])
        self.assertEqual(code, 1)
        client.assert_not_called()
        self.assertIn("TAG_FORMAT", stderr.getvalue())


class PublicationTests(FixtureTests):
    def test_success_exact_create_draft_upload_verify_publish_sequence(self):
        client = FakeClient()
        result = release.publish(self.fixture.plan(), client)
        writes = client.writes
        self.assertEqual(len(writes), 8)
        self.assertEqual(writes[0][0:2], ("POST", P + "/git/refs"))
        self.assertEqual(writes[0][2], dict(ref="refs/tags/" + TAG, sha=COMMIT))
        self.assertEqual(writes[1][0:2], ("POST", P + "/releases"))
        self.assertIs(writes[1][2]["draft"], True)
        self.assertIn("Standard 测试预发布", writes[1][2]["name"])
        self.assertIn("非 Mod 正式签名", writes[1][2]["body"])
        self.assertIn("不代表设备验收", writes[1][2]["body"])
        self.assertEqual([call[3] for call in writes[2:7]], [self.fixture.apk, "sunset-evidence.zip", "build-info.json", "sunset-test-cert.pem", "SHA256SUMS"])
        self.assertEqual(writes[-1][0], "PATCH")
        self.assertEqual(writes[-1][2], dict(draft=False, prerelease=True, make_latest="false"))
        self.assertEqual(len(result["assets"]), 5)
        self.assertFalse(client.release["draft"])
        self.assertFalse(any("artifacts" in call[1] for call in client.calls))

    def test_remote_identity_and_current_attempt_mismatch_prevent_all_writes(self):
        for target, key, value in (("repo", "private", False), ("repo", "id", 18), ("commit", "sha", "f" * 40),
                                   ("run", "head_sha", "f" * 40), ("run", "head_branch", "main"), ("run", "run_attempt", 1),
                                   ("run", "path", ".github/workflows/other.yml"), ("run", "status", "completed"),
                                   ("workflow", "name", "Other"), ("workflow", "state", "disabled_manually")):
            client = FakeClient()
            getattr(client, target)[key] = value
            with self.subTest(target=target, key=key), self.assertRaisesRegex(release.DeliveryError, "REMOTE"):
                release.publish(self.fixture.plan(), client)
            self.assertEqual(client.writes, [])

    def test_required_steps_must_be_single_success_and_job_list_complete(self):
        for action in ("skipped", "failed", "duplicate", "two-jobs", "other-sha"):
            client = FakeClient()
            steps = client.jobs["jobs"][0]["steps"]
            if action == "two-jobs":
                client.jobs["total_count"] = 2
            elif action == "other-sha":
                client.jobs["jobs"][0]["head_sha"] = "e" * 40
            elif action == "duplicate":
                steps.append(copy.deepcopy(steps[0]))
            else:
                steps[0]["conclusion"] = "skipped" if action == "skipped" else "failure"
            with self.subTest(action=action), self.assertRaisesRegex(release.DeliveryError, "REMOTE"):
                release.publish(self.fixture.plan(), client)
            self.assertEqual(client.writes, [])

    def test_existing_tag_release_or_paginated_draft_never_changed(self):
        for existing in ("tag", "release", "page2-draft"):
            client = FakeClient()
            if existing == "tag":
                client.ref = dict(object=dict(sha=COMMIT))
            elif existing == "release":
                client.release = dict(tag_name=TAG, draft=False)
            else:
                client.pages = {1: [dict(tag_name="old-" + str(i)) for i in range(100)], 2: [dict(tag_name=TAG, draft=True)]}
            with self.subTest(existing=existing), self.assertRaisesRegex(release.DeliveryError, "TAG_EXISTS|RELEASE_EXISTS"):
                release.publish(self.fixture.plan(), client)
            self.assertEqual(client.writes, [])

    def test_wrong_create_tag_receipt_stops_before_release(self):
        client = FakeClient()
        client.transform = lambda method, endpoint, value: dict(value, object=dict(type="commit", sha="d" * 40)) if method == "POST" and endpoint.endswith("/git/refs") else value
        with self.assertRaisesRegex(release.DeliveryError, "tag target mismatch"):
            release.publish(self.fixture.plan(), client)
        self.assertEqual(len(client.writes), 1)
        self.assertIsNotNone(client.ref)
        self.assertIsNone(client.release)

    def test_bad_asset_digest_size_state_and_missing_digest_keep_draft(self):
        for field, bad in (("digest", "sha256:" + "0" * 64), ("digest", None), ("size", 1), ("state", "starter")):
            client = FakeClient()
            client.transform = lambda method, endpoint, value: dict(value, **{field: bad}) if method == "POST" and "/assets?" in endpoint else value
            with self.subTest(field=field, bad=bad), self.assertRaisesRegex(release.DeliveryError, "ASSET"):
                release.publish(self.fixture.plan(), client)
            self.assertEqual(len(client.writes), 3)
            self.assertTrue(client.release["draft"])

    def test_preexisting_asset_and_changed_draft_prevent_upload(self):
        for action in ("preexisting-asset", "not-draft"):
            client = FakeClient()
            def transform(method, endpoint, value):
                if action == "preexisting-asset" and method == "GET" and endpoint.endswith("/77/assets?per_page=100"):
                    return [dict(id=99, name=self.fixture.apk)]
                if action == "not-draft" and method == "POST" and endpoint == P + "/releases":
                    value["draft"] = False
                return value
            client.transform = transform
            with self.subTest(action=action), self.assertRaises(release.DeliveryError):
                release.publish(self.fixture.plan(), client)
            self.assertEqual(len(client.writes), 2)
            self.assertEqual(client.assets, [])

    def test_asset_readback_must_match_the_upload_receipt(self):
        client = FakeClient()
        def transform(method, endpoint, value):
            if method == "GET" and endpoint == P + "/releases/assets/100":
                value["digest"] = "sha256:" + "0" * 64
            return value
        client.transform = transform
        with self.assertRaisesRegex(release.DeliveryError, "ASSET"):
            release.publish(self.fixture.plan(), client)
        self.assertEqual(len(client.writes), 3)
        self.assertTrue(client.release["draft"])

    def test_incomplete_duplicate_and_unexpected_final_asset_lists_never_publish(self):
        for action in ("missing", "duplicate", "extra", "wrong-digest"):
            client = FakeClient()
            def transform(method, endpoint, value):
                if method == "GET" and endpoint.endswith("/77/assets?per_page=100") and len(value) == 5:
                    if action == "missing":
                        return value[:-1]
                    if action == "duplicate":
                        return [value[0]] * 5
                    if action == "extra":
                        return value + [dict(value[0], id=900, name="unknown.txt")]
                    value[0]["digest"] = "sha256:" + "0" * 64
                return value
            client.transform = transform
            with self.subTest(action=action), self.assertRaisesRegex(release.DeliveryError, "ASSET"):
                release.publish(self.fixture.plan(), client)
            self.assertEqual(len(client.writes), 7)
            self.assertTrue(client.release["draft"])

    def test_rerun_after_uploads_cannot_publish_old_attempt(self):
        client = FakeClient()
        def transform(method, endpoint, value):
            if endpoint == P + "/actions/runs/1234" and len(client.assets) == 5:
                value["run_attempt"] = 3
            return value
        client.transform = transform
        with self.assertRaisesRegex(release.DeliveryError, "current run"):
            release.publish(self.fixture.plan(), client)
        self.assertEqual(len(client.writes), 7)
        self.assertTrue(client.release["draft"])

    def test_each_write_can_lose_receipt_without_retry_or_cleanup(self):
        for write_number in range(1, 9):
            client = FakeClient()
            def lose_receipt(method, endpoint, value):
                if method != "GET" and len(client.writes) == write_number:
                    raise RuntimeError("synthetic-secret https://signed.invalid/?token=do-not-print")
                return value
            client.transform = lose_receipt
            with self.subTest(write=write_number), self.assertRaises(release.DeliveryError) as caught:
                release.publish(self.fixture.plan(), client)
            text = str(caught.exception)
            self.assertIn("no cleanup, overwrite or retry", text)
            self.assertNotIn("synthetic-secret", text)
            self.assertNotIn("signed.invalid", text)
            self.assertEqual(len(client.writes), write_number)
            self.assertEqual(len({call[1] for call in client.writes}), write_number)
            self.assertIsNotNone(client.ref)
            if write_number >= 2:
                self.assertIsNotNone(client.release)
                self.assertEqual(client.release["draft"], write_number != 8)

    def test_file_changed_after_preparation_does_not_create_tag(self):
        plan = self.fixture.plan()
        (self.fixture.out / self.fixture.apk).write_bytes(b"changed")
        client = FakeClient()
        with self.assertRaisesRegex(release.DeliveryError, "changed after verification"):
            release.publish(plan, client)
        self.assertEqual(client.writes, [])


class TransportTests(unittest.TestCase):
    def setUp(self):
        self.client = release.GitHubClient("synthetic-test-token")
        self.client.opener = mock.Mock()
        self.addCleanup(mock.patch.stopall)
        mock.patch.object(socket, "create_connection", side_effect=AssertionError("NETWORK_FORBIDDEN")).start()

    def test_only_expected_https_hosts_and_environment_token_header(self):
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.status, response.read.return_value = 200, b'{"ok":true}'
        self.client.opener.open.return_value = response
        self.assertEqual(self.client.request("GET", P), dict(ok=True))
        request = self.client.opener.open.call_args.args[0]
        self.assertEqual(request.full_url, "https://api.github.com" + P)
        self.assertEqual(request.get_header("Authorization"), "Bearer synthetic-test-token")
        for endpoint in ("https://attacker.invalid/", "/repos/DSH-APP/DSHA", P + "/../other", P + "\nother"):
            with self.subTest(endpoint=endpoint), self.assertRaisesRegex(release.DeliveryError, "endpoint forbidden"):
                self.client.request("GET", endpoint)
        self.assertEqual(self.client.opener.open.call_count, 1)

    def test_upload_stream_uses_official_host_and_content_length(self):
        with tempfile.TemporaryDirectory(prefix="release-upload-") as directory:
            path = Path(directory) / "fixture.apk"
            path.write_bytes(b"synthetic")
            def open_response(request, **kwargs):
                self.assertEqual(request.full_url, "https://uploads.github.com" + P + "/releases/77/assets?name=fixture.apk")
                self.assertEqual(request.get_header("Content-length"), "9")
                self.assertEqual(request.data.read(), b"synthetic")
                response = mock.MagicMock()
                response.__enter__.return_value = response
                response.status, response.read.return_value = 201, b'{}'
                return response
            self.client.opener.open.side_effect = open_response
            self.client.request("POST", P + "/releases/77/assets?name=fixture.apk", asset=dict(path=path, size=9))
            self.assertEqual(self.client.opener.open.call_count, 1)

    def test_redirects_strip_authentication_and_never_forward_or_replay(self):
        for method in ("GET", "POST", "PATCH"):
            request = urllib.request.Request("https://api.github.com" + P, headers={"Authorization": "Bearer synthetic"}, method=method)
            result = release.NoRedirect().redirect_request(request, None, 302, "Moved", {}, "https://signed.invalid/?token=hidden")
            self.assertIsNone(result)
            self.assertIsNone(request.get_header("Authorization"))

    def test_http_denial_and_unknown_errors_are_sanitized_and_not_retried(self):
        for error in (urllib.error.HTTPError("https://signed.invalid/?secret=x", 403, "synthetic-test-token", {}, None),
                      TimeoutError("synthetic-test-token"), RuntimeError("https://signed.invalid/?secret=x")):
            self.client.opener.open.reset_mock()
            self.client.opener.open.side_effect = error
            with self.subTest(error=type(error).__name__), self.assertRaises(release.DeliveryError) as caught:
                self.client.request("POST", P + "/git/refs", payload={})
            self.assertEqual(self.client.opener.open.call_count, 1)
            self.assertNotIn("synthetic-test-token", str(caught.exception))
            self.assertNotIn("signed.invalid", str(caught.exception))

    def test_only_explicit_get_404_is_missing(self):
        for status in (404, 403, 429):
            self.client.opener.open.side_effect = urllib.error.HTTPError("https://api.github.com", status, "safe", {}, None)
            if status == 404:
                self.assertIsNone(self.client.request("GET", P, missing=True))
            else:
                with self.assertRaises(release.DeliveryError):
                    self.client.request("GET", P, missing=True)
        with self.assertRaisesRegex(release.DeliveryError, "read-only"):
            self.client.request("POST", P, missing=True)


if __name__ == "__main__":
    unittest.main()
