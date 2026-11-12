"""发布身份解析：默认 mod、显式 upstream / rebirth、未知值报错；Gradle 与 Python 解析规则一致。"""
import contextlib
import importlib.util
import io
import json
import re
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("release_identity", ROOT / "tools/release-identity.py")
identity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(identity)

MOD_CERT = "a4b0413c67fa46dd30dc7e2bce3ad56151ed8bfde2a3315fff685baf786b5d5d"
UPSTREAM_CERT = "e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5"
REBIRTH_CERT_PLACEHOLDER = "0" * 64


def run(*argv):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = identity.main(list(argv))
    return code, out.getvalue().strip(), err.getvalue().strip()


class Resolution(unittest.TestCase):
    def test_default_is_mod(self):
        for name in (None, ""):
            value = identity.load(name)
            self.assertEqual(value["identity"], "mod")
            self.assertEqual(value["packageName"], "io.github.sunsetrne.dsha")
            self.assertEqual(value["certificateSha256"], MOD_CERT)
            self.assertEqual((value["appName"], value["keyAlias"], value["testSuffix"], value["devSuffix"]),
                             ("DSH-A-Su", "dsha-a-su", ".test", ".dev"))
        self.assertEqual(run("--field", "packageName"), (0, "io.github.sunsetrne.dsha", ""))

    def test_upstream_branch(self):
        value = identity.load("upstream")
        self.assertEqual(value["packageName"], "dsh.android.client")
        self.assertEqual(value["certificateSha256"], UPSTREAM_CERT)
        self.assertEqual((value["appName"], value["keyAlias"], value["testSuffix"], value["devSuffix"], value["tagPrefix"]),
                         ("DSHA", "androiddebugkey", ".SunsetRNE", ".SunsetDev", "dsha-build"))
        self.assertEqual((value["repository"], value["ref"], value["privateRepository"]),
                         ("ym2025szz/DSHA-prerelase", "refs/heads/codex/prerelease-190", True))
        self.assertEqual(run("--identity", "upstream", "--field", "keyAlias"), (0, "androiddebugkey", ""))

    def test_rebirth_branch_is_isolated_candidate_identity(self):
        value = identity.load("rebirth")
        self.assertEqual(value["packageName"], "dsh.android.client.rebirth")
        self.assertEqual(value["certificateSha256"], REBIRTH_CERT_PLACEHOLDER)
        self.assertEqual((value["appName"], value["keyAlias"], value["testSuffix"], value["devSuffix"],
                          value["tagPrefix"], value["apkPrefix"]),
                         ("DSHA 新生", "dsha-rebirth", ".rebirthtest", ".rebirthdev",
                          "dsha-rebirth-build", "dsha-rebirth"))
        self.assertEqual(value["workflow"], ".github/workflows/rebirth-candidate.yml")
        self.assertEqual((value["repository"], value["ref"], value["privateRepository"]),
                         ("SunsetRNE/DSHA-Sunset-mod", "refs/heads/main", False))
        self.assertEqual(run("--identity", "rebirth", "--field", "packageName"),
                         (0, "dsh.android.client.rebirth", ""))

    def test_unknown_value_is_rejected_without_fallback(self):
        for name in ("Mod", "UPSTREAM", "release", "../release-identity", "mod ", "e7e3"):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "UNKNOWN_RELEASE_IDENTITY"):
                identity.load(name)
        code, out, err = run("--identity", "other", "--field", "packageName")
        self.assertEqual((code, out), (2, ""))
        self.assertIn("UNKNOWN_RELEASE_IDENTITY", err)

    def test_identities_never_share_signing_or_package(self):
        loaded = identity.check()
        self.assertEqual(set(loaded), {"mod", "upstream", "rebirth"})
        with self.assertRaisesRegex(ValueError, "RELEASE_IDENTITY_CERTIFICATE_PLACEHOLDER"):
            with tempfile.TemporaryDirectory() as temp:
                base = Path(temp)
                shutil.copytree(ROOT / "ci", base / "ci")
                mod = json.loads((base / "ci/release-identity.mod.json").read_text(encoding="utf-8"))
                mod["certificateSha256"] = REBIRTH_CERT_PLACEHOLDER
                (base / "ci/release-identity.mod.json").write_text(json.dumps(mod), encoding="utf-8")
                identity.check(base)
        legacy = json.loads((ROOT / "ci/release-identity.json").read_text(encoding="utf-8"))
        for key in ("packageName", "certificateSha256", "schemes"):
            self.assertEqual(legacy[key], loaded["mod"][key], key)

    def test_check_rejects_drift_and_mixed_identity(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            shutil.copytree(ROOT / "ci", base / "ci")
            identity.check(base)
            legacy = json.loads((base / "ci/release-identity.json").read_text(encoding="utf-8"))
            legacy["certificateSha256"] = UPSTREAM_CERT
            (base / "ci/release-identity.json").write_text(json.dumps(legacy), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "LEGACY_IDENTITY_DIFFERS_FROM_MOD"):
                identity.check(base)
            shutil.copy(ROOT / "ci/release-identity.json", base / "ci/release-identity.json")
            upstream = json.loads((base / "ci/release-identity.upstream.json").read_text(encoding="utf-8"))
            upstream["certificateSha256"] = MOD_CERT
            (base / "ci/release-identity.upstream.json").write_text(json.dumps(upstream), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "RELEASE_IDENTITIES_SHARE_certificateSha256"):
                identity.check(base)
            upstream["certificateSha256"] = UPSTREAM_CERT
            upstream["identity"] = "mod"
            (base / "ci/release-identity.upstream.json").write_text(json.dumps(upstream), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "RELEASE_IDENTITY_NAME"):
                identity.check(base)


class GradleContract(unittest.TestCase):
    """app/build.gradle 用同一份身份文件和同一组允许值；versionCode/versionName 记录在身份文件。"""

    def setUp(self):
        self.gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")

    def test_same_names_default_and_unknown_failure(self):
        names = re.search(r"def releaseIdentityNames = \[([^\]]+)\]", self.gradle)
        self.assertIsNotNone(names)
        self.assertEqual(tuple(re.findall(r"'([^']+)'", names.group(1))), identity.IDENTITIES)
        self.assertIn("def requestedIdentity = project.findProperty('dsha.identity')", self.gradle)
        self.assertIn("(requestedIdentity ?: (rebirthCandidate ? 'rebirth' : 'mod')).toString()", self.gradle)
        self.assertEqual(identity.DEFAULT, "mod")
        self.assertIn('throw new GradleException("UNKNOWN_RELEASE_IDENTITY', self.gradle)
        self.assertIn('rootProject.file("ci/release-identity.${releaseIdentityName}.json")', self.gradle)

    def test_identity_fields_drive_package_name_suffix_alias_and_certificate(self):
        self.assertIn("applicationId releaseIdentity.packageName", self.gradle)
        self.assertIn("applicationIdSuffix releaseIdentity.testSuffix", self.gradle)
        self.assertIn("applicationIdSuffix releaseIdentity.devSuffix", self.gradle)
        self.assertIn("applicationIdSuffix '.debug'", self.gradle)
        self.assertIn("testVariantLabel ? releaseIdentity.appName + ' ' + testVariantLabel : releaseIdentity.appName", self.gradle)
        self.assertIn("versionNameSuffix '-SunsetDev'", self.gradle)
        self.assertIn("versionNameSuffix '-SunsetDebug'", self.gradle)
        self.assertIn('project.findProperty("dsha.key.alias") ?: releaseIdentity.keyAlias', self.gradle)
        self.assertIn('def identityFile = "ci/release-identity.${releaseIdentityName}.json"', self.gradle)
        self.assertIn("android.defaultConfig.applicationId != identity.packageName", self.gradle)
        self.assertIn("RELEASE_IDENTITY_VERSION_MISMATCH", self.gradle)
        self.assertNotIn('applicationId "', self.gradle)
        self.assertNotIn('"androiddebugkey"', self.gradle)
        self.assertNotIn('"dsha-a-su"', self.gradle)

    def test_rebirth_candidate_switch_and_isolated_signing(self):
        for needle in ("dsha.rebirthCandidate", "rebirthCandidate", "REBIRTH_CANDIDATE_VARIANT_CONFLICT",
                       "REBIRTH_CANDIDATE_IDENTITY_CONFLICT", "REBIRTH_IDENTITY_REQUIRES_CANDIDATE_SWITCH",
                       "REBIRTH_CERTIFICATE_UNSET", "signingConfigs.rebirth",
                       "DSHA_REBIRTH_KEYSTORE", "DSHA_REBIRTH_KEYSTORE_PASSWORD", "DSHA_REBIRTH_KEY_PASSWORD",
                       "REBIRTH_CERT_REUSES_"):
            self.assertIn(needle, self.gradle)
        self.assertIn("signingConfig (releaseIdentityName == 'rebirth' ? signingConfigs.rebirth : signingConfigs.publish)",
                      self.gradle)

    def test_version_fields_are_recorded_per_identity_and_match_gradle(self):
        code = re.search(r"(?m)^\s+versionCode (\d+)$", self.gradle)
        name = re.search(r'(?m)^\s+versionName "([^"]+)"$', self.gradle)
        self.assertIsNotNone(code)
        self.assertIsNotNone(name)
        self.assertEqual(len(re.findall(r"(?m)^\s+versionCode \d+$", self.gradle)), 1)
        self.assertEqual(len(re.findall(r'(?m)^\s+versionName "[^"]+"$', self.gradle)), 1)
        expected = (int(code.group(1)), name.group(1))
        versions = set()
        for identity_name in identity.IDENTITIES:
            value = json.loads((ROOT / f"ci/release-identity.{identity_name}.json").read_text(encoding="utf-8"))
            self.assertIsInstance(value["versionCode"], int)
            self.assertIsInstance(value["versionName"], str)
            versions.add((value["versionCode"], value["versionName"]))
        self.assertEqual(versions, {expected}, "三份身份文件必须与 build.gradle 的版本基准一致")
        legacy = json.loads((ROOT / "ci/release-identity.json").read_text(encoding="utf-8"))
        self.assertEqual((legacy["versionCode"], legacy["versionName"]), expected)


if __name__ == "__main__":
    unittest.main()
