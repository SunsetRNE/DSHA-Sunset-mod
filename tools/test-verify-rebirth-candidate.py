#!/usr/bin/env python3
"""Rebirth 候选核验器的纯逻辑回归：清单、authorities、未签名判定、身份文件与退出码。

全部输入使用 fixture 字符串或临时目录，不依赖真实 APK、Android SDK、aapt2 或 apksigner。
"""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

TOOL = Path(__file__).resolve().with_name("verify-rebirth-candidate.py")


def load():
    spec = importlib.util.spec_from_file_location("verify_rebirth_candidate", TOOL)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


VERIFY = load()
SIGNED_CERT = "ab" * 32
FIXED_CERT = "cd" * 32

# 与 ci/release-identity.rebirth.json 的冻结结构一致（没有单独的 certificateStatus 字段）。
IDENTITY = {
    "schema": 1,
    "identity": "rebirth",
    "packageName": "dsh.android.client.rebirth",
    "certificateSha256": "0" * 64,
    "schemes": ["v1", "v2", "v3"],
    "versionCode": 359,
    "versionName": "0.2.1-rc.5-20261111.1200-dsh0.2.2-rc.1",
    "appName": "DSHA 新生",
    "keyAlias": "dsha-rebirth",
    "testSuffix": ".rebirthtest",
    "devSuffix": ".rebirthdev",
    "tagPrefix": "dsha-rebirth-build",
    "apkPrefix": "dsha-rebirth",
    "repository": "SunsetRNE/DSHA-Sunset-mod",
    "ref": "refs/heads/main",
    "privateRepository": False,
    "workflow": ".github/workflows/rebirth-candidate.yml",
}


def identity(**changes):
    value = dict(IDENTITY)
    value.update(changes)
    return value


def badging(*, package=None, version_code=None, version_name=None, min_sdk=30, target_sdk=37,
            abis=("arm64-v8a",), label=None, debuggable=False):
    package = IDENTITY["packageName"] if package is None else package
    version_code = IDENTITY["versionCode"] if version_code is None else version_code
    version_name = IDENTITY["versionName"] if version_name is None else version_name
    label = IDENTITY["appName"] if label is None else label
    lines = [
        f"package: name='{package}' versionCode='{version_code}' versionName='{version_name}' "
        "platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='37' "
        "compileSdkVersionCodename='15'",
        f"sdkVersion:'{min_sdk}'",
        f"targetSdkVersion:'{target_sdk}'",
        "uses-permission: name='android.permission.INTERNET'",
        f"application-label:'{label}'",
        "application-label-en:'Rebirth'",
        "native-code: " + " ".join(f"'{abi}'" for abi in abis),
    ]
    if debuggable:
        lines.append("application-debuggable")
    return "\n".join(lines) + "\n"


def xmltree(*authorities):
    rows = [
        "N: android=http://schemas.android.com/apk/res/android (line=1)",
        "  E: manifest (line=2)",
        '    A: package="dsh.android.client.rebirth" (Raw: "dsh.android.client.rebirth")',
        "    E: application (line=10)",
        '      A: http://schemas.android.com/apk/res/android:label(0x01010001)="DSHA 新生" (Raw: "DSHA 新生")',
    ]
    for index, value in enumerate(authorities):
        rows += [
            f"      E: provider (line={20 + index})",
            "        A: http://schemas.android.com/apk/res/android:name(0x01010003)="
            '"com.deepseekharness.app.DshaDocumentsProvider"',
            f'        A: http://schemas.android.com/apk/res/android:authorities(0x01010018)="{value}"',
        ]
    return "\n".join(rows) + "\n"


def probe(*, returncode=1, certificates=(), meta_inf=(), signing_block=False, output=None):
    if output is None:
        output = "DOES NOT VERIFY\nERROR: Missing META-INF/MANIFEST.MF\n" if returncode else "Verifies\n"
    for digest in certificates:
        output += "Signer #1 certificate SHA-256 digest: " + digest + "\n"
    return {"returncode": returncode, "output": output, "certificates": list(certificates),
            "metaInfSignatureFiles": list(meta_inf), "apkSigningBlock": signing_block}


def clean_probe():
    return probe()


def verdict(**changes):
    identity_value = changes.pop("identity_value", IDENTITY)
    badging_text = changes.pop("badging_text", badging())
    xmltree_text = changes.pop("xmltree_text", xmltree("dsh.android.client.rebirth.documents"))
    probe_value = changes.pop("probe_value", clean_probe())
    if changes:
        raise TypeError("unexpected fixture overrides: " + ", ".join(sorted(changes)))
    return VERIFY.evaluate(identity_value, badging_text, xmltree_text, probe_value)


def named(evaluated, name):
    return next(row for row in evaluated["checks"] if row["name"] == name)


def failure_codes(evaluated):
    return {row.get("code") for row in evaluated["checks"] if row["status"] == "FAIL"}


class BadgingParsing(unittest.TestCase):
    def test_candidate_badging_is_parsed(self):
        values = VERIFY.parse_badging(badging())
        self.assertEqual(values["packageName"], IDENTITY["packageName"])
        self.assertEqual(values["versionCode"], IDENTITY["versionCode"])
        self.assertEqual(values["versionName"], IDENTITY["versionName"])
        self.assertEqual(values["minSdk"], 30)
        self.assertEqual(values["targetSdk"], 37)
        self.assertEqual(values["nativeCode"], ["arm64-v8a"])
        self.assertEqual(values["applicationLabel"], IDENTITY["appName"])
        self.assertFalse(values["debuggable"])

    def test_alternate_minsdk_key_and_multiple_abis(self):
        text = badging().replace("sdkVersion:'30'", "minSdkVersion:'30'").replace(
            "native-code: 'arm64-v8a'", "native-code: 'arm64-v8a' 'armeabi-v7a'")
        values = VERIFY.parse_badging(text)
        self.assertEqual(values["minSdk"], 30)
        self.assertEqual(values["nativeCode"], ["arm64-v8a", "armeabi-v7a"])

    def test_debuggable_line_is_detected(self):
        self.assertTrue(VERIFY.parse_badging(badging(debuggable=True))["debuggable"])
        self.assertFalse(VERIFY.parse_badging(badging())["debuggable"])

    def test_missing_lines_degrade_to_none(self):
        values = VERIFY.parse_badging("")
        self.assertIsNone(values["packageName"])
        self.assertIsNone(values["versionCode"])
        self.assertIsNone(values["versionName"])
        self.assertIsNone(values["minSdk"])
        self.assertIsNone(values["targetSdk"])
        self.assertEqual(values["nativeCode"], [])


class AuthorityParsing(unittest.TestCase):
    def test_authorities_are_extracted_and_semicolons_split(self):
        parsed = VERIFY.parse_authorities(xmltree(
            "dsh.android.client.rebirth.documents",
            "dsh.android.client.rebirth.files;dsh.android.client.rebirth.extra"))
        self.assertEqual(parsed, [
            "dsh.android.client.rebirth.documents",
            "dsh.android.client.rebirth.files",
            "dsh.android.client.rebirth.extra",
        ])

    def test_prefixed_authority_is_accepted(self):
        result = verdict(xmltree_text=xmltree("dsh.android.client.rebirth.documents"))
        self.assertEqual(named(result, "authorities")["status"], "PASS")
        self.assertIn("documents", named(result, "authorities")["detail"])

    def test_authority_without_package_prefix_is_rejected(self):
        for authority in ("io.github.sunsetrne.dsha.documents", "dsh.android.client.rebirthx.documents", ""):
            with self.subTest(authority=authority):
                result = verdict(xmltree_text=xmltree(authority) if authority else xmltree())
                self.assertEqual(result["exitCode"], 1)
                self.assertEqual(named(result, "authorities")["status"], "FAIL")
                self.assertIn("AUTHORITIES_MISMATCH", failure_codes(result))


class CandidateVerdict(unittest.TestCase):
    def test_correct_candidate_passes_every_check(self):
        result = verdict()
        self.assertEqual(result["exitCode"], 0)
        self.assertEqual(result["result"], "PASS")
        self.assertTrue(result["unsignedConfirmed"])
        self.assertFalse(result["signed"])
        self.assertEqual({row["status"] for row in result["checks"]}, {"PASS"})
        self.assertEqual({row["name"] for row in result["checks"]}, {
            "packageName", "versionCode", "versionName", "debuggable", "minSdk", "targetSdk",
            "nativeCode", "applicationLabel", "testSuffix", "authorities", "unsigned",
            "certificateStatus",
        })

    def test_wrong_package_is_rejected(self):
        result = verdict(badging_text=badging(package="io.github.sunsetrne.dsha"))
        self.assertEqual(result["exitCode"], 1)
        self.assertIn("PACKAGE_NAME_MISMATCH", failure_codes(result))

    def test_debuggable_is_rejected(self):
        result = verdict(badging_text=badging(debuggable=True))
        self.assertEqual(result["exitCode"], 1)
        self.assertIn("APK_DEBUGGABLE", failure_codes(result))

    def test_wrong_version_code_is_rejected(self):
        result = verdict(badging_text=badging(version_code=360))
        self.assertEqual(result["exitCode"], 1)
        self.assertIn("VERSION_CODE_MISMATCH", failure_codes(result))

    def test_wrong_version_name_is_rejected(self):
        result = verdict(badging_text=badging(version_name=IDENTITY["versionName"] + "-Rebirth-Sprout.1"))
        self.assertEqual(result["exitCode"], 1)
        self.assertIn("VERSION_NAME_MISMATCH", failure_codes(result))

    def test_non_arm64_or_multiple_abi_is_rejected(self):
        for abis in (("armeabi-v7a",), ("arm64-v8a", "x86_64")):
            with self.subTest(abis=abis):
                result = verdict(badging_text=badging(abis=abis))
                self.assertEqual(result["exitCode"], 1)
                self.assertIn("NATIVE_CODE_MISMATCH", failure_codes(result))

    def test_platform_and_label_mismatch_is_rejected(self):
        for changes, code in (
                ({"min_sdk": 24}, "MINSDK_MISMATCH"),
                ({"target_sdk": 34}, "TARGETSDK_MISMATCH"),
                ({"label": "DSHA"}, "APPLICATION_LABEL_MISMATCH"),
        ):
            with self.subTest(code=code):
                result = verdict(badging_text=badging(**changes))
                self.assertEqual(result["exitCode"], 1)
                self.assertIn(code, failure_codes(result))

    def test_test_or_dev_package_suffix_is_rejected(self):
        result = verdict(badging_text=badging(
            package=IDENTITY["packageName"] + IDENTITY["devSuffix"]))
        self.assertEqual(result["exitCode"], 1)
        self.assertIn("PACKAGE_NAME_MISMATCH", failure_codes(result))
        self.assertIn("TEST_OR_DEV_SUFFIX", failure_codes(result))

    def test_signed_candidate_is_rejected_and_prints_certificate(self):
        result = verdict(probe_value=probe(returncode=0, certificates=(SIGNED_CERT,)))
        self.assertEqual(result["exitCode"], 1)
        self.assertTrue(result["signed"])
        self.assertFalse(result["unsignedConfirmed"])
        unsigned = named(result, "unsigned")
        self.assertEqual(unsigned["status"], "FAIL")
        self.assertIn(SIGNED_CERT, unsigned["detail"])
        self.assertIn("APK_IS_SIGNED", failure_codes(result))

    def test_signature_material_with_failed_verify_is_rejected(self):
        cases = {
            "apk-signing-block": probe(returncode=1, signing_block=True),
            "v1-signature-file": probe(returncode=1, meta_inf=("META-INF/CERT.RSA",)),
            "printed-certificate": probe(returncode=1, certificates=(SIGNED_CERT,)),
        }
        for name, probe_value in cases.items():
            with self.subTest(case=name):
                result = verdict(probe_value=probe_value)
                self.assertEqual(result["exitCode"], 1)
                self.assertFalse(result["unsignedConfirmed"])
                self.assertIn("APK_HAS_SIGNATURE_MATERIAL", failure_codes(result))
                self.assertEqual(named(result, "unsigned")["status"], "FAIL")

    def test_fixed_certificate_still_rejects_unsigned_candidate(self):
        # G0-4 负控：证书固定后，未签名产物仍不可交付；但产物本身确实是未签名候选。
        result = verdict(identity_value=identity(certificateSha256=FIXED_CERT))
        self.assertEqual(result["exitCode"], 1)
        self.assertTrue(result["unsignedConfirmed"])
        self.assertIn("UNSIGNED_WITH_FIXED_CERTIFICATE", failure_codes(result))
        self.assertEqual(named(result, "certificateStatus")["status"], "FAIL")

    def test_ambiguous_verify_failure_is_rejected(self):
        # 非 0 不一定是“未签名”：工具错误不能冒充通过。
        result = verdict(probe_value=probe(returncode=2, output="ERROR: internal verify failure\n"))
        self.assertEqual(result["exitCode"], 1)
        self.assertFalse(result["unsignedConfirmed"])
        self.assertIn("APK_VERIFY_AMBIGUOUS", failure_codes(result))

    def test_missing_package_line_is_a_format_violation(self):
        result = verdict(badging_text="sdkVersion:'30'\n")
        self.assertEqual(result["exitCode"], 1)
        self.assertIn("PACKAGE_NAME_MISMATCH", failure_codes(result))


class IdentityFile(unittest.TestCase):
    def tearDown(self):
        if getattr(self, "folder", None) is not None:
            self.folder.cleanup()
            self.folder = None

    def write_identity(self, value):
        if getattr(self, "folder", None) is None:
            self.folder = tempfile.TemporaryDirectory(prefix="rebirth-identity-")
        path = Path(self.folder.name) / "release-identity.rebirth.json"
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        return path

    def test_valid_identity_is_loaded(self):
        path = self.write_identity(IDENTITY)
        self.assertEqual(VERIFY.load_identity(path), IDENTITY)
        self.assertEqual(VERIFY.certificate_status(IDENTITY), "unset")
        self.assertEqual(VERIFY.certificate_status(identity(certificateSha256=FIXED_CERT)), "fixed")

    def test_missing_and_broken_json_identity_is_rejected(self):
        missing = Path(self.write_identity(IDENTITY)).with_name("missing.json")
        with self.assertRaises(VERIFY.CandidateInvalid) as raised:
            VERIFY.load_identity(missing)
        self.assertIn("IDENTITY_MISSING", str(raised.exception))
        if getattr(self, "folder", None) is None:
            self.folder = tempfile.TemporaryDirectory(prefix="rebirth-identity-")
        broken = Path(self.folder.name) / "broken.json"
        broken.write_text("{not json", encoding="utf-8")
        with self.assertRaises(VERIFY.CandidateInvalid) as raised:
            VERIFY.load_identity(broken)
        self.assertIn("IDENTITY_JSON", str(raised.exception))

    def test_every_invalid_identity_field_is_rejected(self):
        cases = {
            "IDENTITY_FIELDS": {"extraField": True},
            "IDENTITY_SCHEMA": {"schema": 2},
            "IDENTITY_NAME": {"identity": "mod"},
            "IDENTITY_PACKAGE": {"packageName": "Dsh.Bad"},
            "IDENTITY_APP_NAME": {"appName": "  "},
            "IDENTITY_VERSION_CODE": {"versionCode": 0},
            "IDENTITY_VERSION_NAME": {"versionName": ""},
            "IDENTITY_CERTIFICATE": {"certificateSha256": "xyz"},
            "IDENTITY_SCHEMES": {"schemes": ["v2"]},
            "IDENTITY_KEY_ALIAS": {"keyAlias": "bad alias"},
            "IDENTITY_SUFFIX": {"testSuffix": "rebirthtest"},
            "IDENTITY_TAG_PREFIX": {"tagPrefix": "dsha_rebirth"},
            "IDENTITY_APK_PREFIX": {"apkPrefix": "dsha_rebirth"},
            "IDENTITY_REPOSITORY": {"repository": "no-slash"},
            "IDENTITY_REF": {"ref": "main"},
            "IDENTITY_PRIVATE_REPOSITORY": {"privateRepository": "no"},
            "IDENTITY_WORKFLOW": {"workflow": ""},
        }
        for error, changes in cases.items():
            with self.subTest(error=error):
                with self.assertRaises(VERIFY.CandidateInvalid) as raised:
                    VERIFY.load_identity(self.write_identity(identity(**changes)))
                self.assertIn(error, str(raised.exception))

    def test_missing_field_is_rejected(self):
        value = identity()
        value.pop("workflow")
        with self.assertRaises(VERIFY.CandidateInvalid) as raised:
            VERIFY.load_identity(self.write_identity(value))
        self.assertIn("IDENTITY_FIELDS", str(raised.exception))

    def test_repository_identity_file_is_accepted(self):
        # 真实身份文件必须与冻结结构一致；版本号由身份文件持有，不在测试里写死。
        value = VERIFY.load_identity()
        self.assertEqual(value["identity"], "rebirth")
        self.assertEqual(value["packageName"], "dsh.android.client.rebirth")
        self.assertEqual(value["appName"], "DSHA 新生")
        self.assertIsInstance(value["versionCode"], int)
        self.assertEqual(VERIFY.certificate_status(value), "unset")


class Report(unittest.TestCase):
    def test_pass_report_carries_the_fixed_marker(self):
        evaluated = verdict()
        report = VERIFY.build_report(IDENTITY, "ci/release-identity.rebirth.json",
                                     {"file": "candidate.apk", "size": 1, "sha256": "0" * 64}, evaluated)
        self.assertEqual(report["result"], "PASS")
        self.assertEqual(report["exitCode"], 0)
        self.assertEqual(report["marker"], "UNSIGNED_NOT_FOR_INSTALLATION")
        self.assertIs(report["installable"], False)
        self.assertIs(report["signed"], False)
        self.assertEqual(report["kind"], "dsha-rebirth-unsigned-candidate")
        self.assertEqual(report["identity"]["packageName"], IDENTITY["packageName"])
        self.assertEqual(report["identity"]["certificateStatus"], "unset")

    def test_signed_report_is_not_marked_unsigned(self):
        evaluated = verdict(probe_value=probe(returncode=0, certificates=(SIGNED_CERT,)))
        report = VERIFY.build_report(IDENTITY, "ci/release-identity.rebirth.json",
                                     {"file": "candidate.apk", "size": 1, "sha256": "0" * 64}, evaluated)
        self.assertEqual(report["exitCode"], 1)
        self.assertNotEqual(report.get("marker"), "UNSIGNED_NOT_FOR_INSTALLATION")
        self.assertIs(report["installable"], False)

    def test_fixed_certificate_failure_keeps_unsigned_marker(self):
        evaluated = verdict(identity_value=identity(certificateSha256=FIXED_CERT))
        report = VERIFY.build_report(identity(certificateSha256=FIXED_CERT),
                                     "ci/release-identity.rebirth.json",
                                     {"file": "candidate.apk", "size": 1, "sha256": "0" * 64}, evaluated)
        self.assertEqual(report["exitCode"], 1)
        self.assertEqual(report["marker"], "UNSIGNED_NOT_FOR_INSTALLATION")


class SignatureMaterial(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="rebirth-apk-")
        self.root = Path(self.folder.name)

    def tearDown(self):
        self.folder.cleanup()

    def test_signing_block_magic_is_detected(self):
        with_magic = self.root / "with-magic.apk"
        with_magic.write_bytes(b"payload" + VERIFY.APK_SIGNING_BLOCK_MAGIC + b"payload")
        self.assertTrue(VERIFY.apk_signing_block(with_magic))
        without = self.root / "without.apk"
        without.write_bytes(b"payload" * 1000)
        self.assertFalse(VERIFY.apk_signing_block(without))

    def test_v1_signature_files_are_detected(self):
        with_signature = self.root / "signed.zip"
        with zipfile.ZipFile(with_signature, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            archive.writestr("META-INF/MANIFEST.MF", b"manifest")
            archive.writestr("META-INF/CERT.SF", b"sf")
            archive.writestr("META-INF/CERT.RSA", b"rsa")
        material = VERIFY.signature_material(with_signature)
        self.assertEqual(material["metaInfSignatureFiles"], ["META-INF/CERT.RSA"])
        self.assertFalse(material["apkSigningBlock"])

    def test_plain_zip_has_no_signature_material(self):
        plain = self.root / "plain.zip"
        with zipfile.ZipFile(plain, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            archive.writestr("META-INF/MANIFEST.MF", b"manifest")
        material = VERIFY.signature_material(plain)
        self.assertEqual(material["metaInfSignatureFiles"], [])
        self.assertFalse(material["apkSigningBlock"])


class Cli(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="rebirth-cli-")
        self.root = Path(self.folder.name)
        self.identity_path = self.root / "release-identity.rebirth.json"
        self.identity_path.write_text(json.dumps(IDENTITY, ensure_ascii=False), encoding="utf-8")
        self.apk = self.root / "candidate-unsigned.apk"
        self.apk.write_bytes(b"not a real apk")

    def tearDown(self):
        self.folder.cleanup()

    def run_main(self, *argv):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            code = VERIFY.main(list(argv))
        return code, output.getvalue()

    def test_missing_identity_returns_one(self):
        code, text = self.run_main("--apk", str(self.apk),
                                   "--identity", str(self.root / "missing.json"), "--json")
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(text)["result"], "FAIL")

    def test_missing_apk_returns_one(self):
        code, text = self.run_main("--apk", str(self.root / "missing.apk"),
                                   "--identity", str(self.identity_path), "--json")
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(text)["exitCode"], 1)

    def test_missing_tools_returns_two(self):
        empty = self.root / "build-tools-empty"
        empty.mkdir()
        code, text = self.run_main("--apk", str(self.apk), "--identity", str(self.identity_path),
                                   "--build-tools", str(empty), "--json")
        self.assertEqual(code, 2)
        report = json.loads(text)
        self.assertEqual(report["exitCode"], 2)
        self.assertEqual(report["checks"][0]["code"], "TOOLS_MISSING")

    def test_human_output_contains_the_fixed_marker(self):
        evaluated = verdict()
        report = VERIFY.build_report(IDENTITY, "ci/release-identity.rebirth.json",
                                     {"file": "candidate.apk", "size": 1, "sha256": "0" * 64}, evaluated)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            VERIFY.emit(report, False)
        text = output.getvalue()
        self.assertIn("UNSIGNED_NOT_FOR_INSTALLATION", text)
        self.assertIn("RESULT: PASS", text)
        self.assertIn("PASS packageName", text)


if __name__ == "__main__":
    unittest.main()
