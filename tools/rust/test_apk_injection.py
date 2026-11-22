#!/usr/bin/env python3
"""Rust 注入的真实二进制/ZIP/temp fixtures；不编译、不加载、不签名。

这些合成 ELF 只有供解析器核验的表和 AArch64 RET 字节，并不是 Android 加载证据。
所有负控都经过生产验证器；不 mock 核心 ELF、receipt、指纹或 ZIP 验证。
可直接运行，或由已登记的 host suite 导入 suite()。
"""
from __future__ import annotations

import copy
from datetime import datetime, timedelta, timezone
import importlib.util
import json
import os
from pathlib import Path
import stat
import struct
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
TOOLS = HERE.parent
ROOT = TOOLS.parent
sys.path.insert(0, str(HERE))
import artifact  # noqa: E402
import build as rust_build  # noqa: E402


def synthetic_elf(symbols: list[str], needed: tuple[str, ...] = ("libc.so",), *, machine: int = 183) -> bytes:
    """构造完整 ELF64 header/LOAD/RELRO/DYNAMIC/dynsym/dynstr/section headers。"""
    data = bytearray(0x3000)
    strings = bytearray(b"\0")
    def string(value):
        offset = len(strings)
        strings.extend(value.encode("utf-8") + b"\0")
        return offset
    dependencies = [string(value) for value in needed]
    names = [string(value) for value in symbols]
    if len(strings) >= 0x800:
        raise ValueError("fixture strings too large")
    data[:16] = b"\x7fELF\x02\x01\x01" + b"\0" * 9
    struct.pack_into("<HHIQQQIHHHHHH", data, 16, 3, machine, 1, 0, 64, 0x2000, 0,
                     64, 56, 3, 64, 4, 0)
    dynamic = [(1, offset) for offset in dependencies]
    dynamic += [(5, 0x800), (10, len(strings)), (6, 0x1000), (11, 24), (0, 0)]
    dynamic_size = len(dynamic) * 16
    # LOAD 全文件、RELRO 包住 dynamic，均为真实字段而不是 mock 契约结果。
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 7, 0, 0, 0, len(data), 0x4000, 16384)
    struct.pack_into("<IIQQQQQQ", data, 120, 0x6474e552, 4, 0x300, 0x300, 0x300,
                     dynamic_size, dynamic_size, 1)
    struct.pack_into("<IIQQQQQQ", data, 176, 2, 6, 0x300, 0x300, 0x300,
                     dynamic_size, dynamic_size, 8)
    for index, (tag, value) in enumerate(dynamic):
        struct.pack_into("<QQ", data, 0x300 + index * 16, tag, value)
    data[0x800:0x800 + len(strings)] = strings
    for index, name in enumerate(names, 1):
        struct.pack_into("<IBBHQQ", data, 0x1000 + index * 24, name, 0x12, 0, 3,
                         0x200 + (index - 1) * 4, 4)
        data[0x200 + (index - 1) * 4:0x204 + (index - 1) * 4] = b"\xc0\x03\x5f\xd6"
    struct.pack_into("<IIQQQQIIQQ", data, 0x2040, 0, 3, 2, 0x800, 0x800, len(strings), 0, 0, 1, 0)
    struct.pack_into("<IIQQQQIIQQ", data, 0x2080, 0, 11, 2, 0x1000, 0x1000,
                     (len(names) + 1) * 24, 1, 1, 8, 24)
    struct.pack_into("<IIQQQQIIQQ", data, 0x20c0, 0, 1, 6, 0x200, 0x200,
                     max(4, len(names) * 4), 0, 0, 4, 0)
    return bytes(data)


def patched(data: bytes, offset: int, fmt: str, value: int) -> bytes:
    result = bytearray(data)
    struct.pack_into(fmt, result, offset, value)
    return bytes(result)


class RustApkInjectionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lock = artifact.load_json(ROOT / "ci/rust-toolchain.lock.json")
        cls.budget = artifact.load_budget(ROOT)
        cls.commit = artifact.current_commit(ROOT)
        cls.source = artifact.source_inputs(ROOT)
        cls.symbols = sorted(cls.lock["abi"]["symbols"])
        cls.library = synthetic_elf(cls.symbols)
        cls.info = artifact.describe_bytes(cls.library, target="android", lock=cls.lock, budget=cls.budget)
        cls.identity_args = dict(commit=cls.commit, run_id="12345", run_attempt="2", repository="fixture/repo")
        cls.identity = artifact.expected_identity(**cls.identity_args)
        cls.baseline_member = "lib/arm64-v8a/" + sorted((ROOT / "app/src/main/jniLibs/arm64-v8a").glob("*.so"))[0].name

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="dsha-rust-injection-test-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.inputs = self.directory / "artifact"
        self.inputs.mkdir()
        self.out = self.directory / "jni"
        self.assets = self.directory / "assets"
        self.apk = self.directory / "fixture.apk"
        self.receipt_path = self.inputs / artifact.RECEIPT_NAME
        self.library_path = self.inputs / artifact.LIB_NAME
        self.receipt = self.make_receipt()
        self.write_pair()

    def make_receipt(self, data: bytes | None = None) -> dict:
        data = self.library if data is None else data
        info = {**self.info, "size": len(data), "sha256": artifact.digest(data), "reproducible": "NOT_RUN"}
        found = dict(rustc=self.lock["rustc"]["version"], rustcCommit=self.lock["rustc"]["commit"].ljust(40, "0"),
                     cargo=self.lock["cargo"]["version"], host="x86_64-unknown-linux-gnu")
        result = rust_build.receipt(found=found, lock=self.lock, target="android", ndk=None, artifact=info,
                                    reproducible=dict(status="NOT_RUN", differentDirectory=False,
                                                      firstSha256=None, secondSha256=None),
                                    jobs=2, expectation={"status": "NOT_RUN"},
                                    sources=copy.deepcopy(self.source), commit=self.commit)
        result.update(provenance="github-actions", runId="12345", runAttempt="2", repository="fixture/repo",
                      workflow={"name": "synthetic static fixtures", "ref": "fixture/repo/.github/workflows/check.yml@refs/heads/main",
                                "sha": self.commit},
                      runner={"name": "synthetic-runner", "os": "Linux", "arch": "ARM64", "environment": "self-hosted"})
        return result

    def receipt_bytes(self) -> bytes:
        return (json.dumps(self.receipt, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

    def write_pair(self, data: bytes | None = None):
        self.library_path.write_bytes(self.library if data is None else data)
        self.receipt_path.write_bytes(self.receipt_bytes())

    def stage(self, **kwargs):
        options = dict(mode="prebuilt", artifact_dir=self.inputs, out=self.out, assets_out=self.assets, **self.identity_args)
        options.update(kwargs)
        return artifact.stage(**options)

    def write_apk(self, entries: list[tuple] | None = None, *, data: bytes | None = None,
                  embedded: bytes | None = None, rust: bool = True, receipt: bool = True,
                  compression: int = zipfile.ZIP_STORED):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.apk, "w", compression=compression) as archive:
                archive.writestr("AndroidManifest.xml", b"synthetic, not an installable Android manifest")
                archive.writestr(self.baseline_member, b"baseline fixture")
                if rust:
                    archive.writestr("lib/arm64-v8a/libdsha_rs.so", self.library if data is None else data)
                if receipt:
                    archive.writestr(artifact.APK_ASSET, self.receipt_bytes() if embedded is None else embedded)
                for name, value in entries or []:
                    archive.writestr(name, value)

    def verify(self, **kwargs):
        options = dict(mode="prebuilt", receipt_path=self.receipt_path, **self.identity_args)
        options.update(kwargs)
        return artifact.verify_apk(self.apk, **options)

    def run_cli(self, command: str, *arguments, ci: bool = False):
        env = dict(os.environ)
        for key in list(env):
            if key.startswith("GITHUB_") or key.startswith("RUNNER_") or key == "CI":
                env.pop(key)
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        if ci:
            env["CI"] = "true"
        script = HERE / "artifact.py" if command in ("stage", "verify-apk") else TOOLS / "check-rust-native-apk.py"
        prefix = [command] if command in ("stage", "verify-apk") else []
        return subprocess.run([sys.executable, str(script), *prefix, *map(str, arguments)], cwd=ROOT,
                              env=env, capture_output=True, text=True, check=False)

    def cli_identity(self):
        return ("--commit", self.commit, "--run-id", "12345", "--run-attempt", "2", "--repository", "fixture/repo")

    def test_valid_stage_is_exact_and_read_back(self):
        result = self.stage()
        self.assertEqual("PASS", result["status"])
        self.assertFalse(result["apkVerified"])
        self.assertFalse(result["androidLoadTested"])
        self.assertEqual(self.library, (self.out / "arm64-v8a/libdsha_rs.so").read_bytes())
        self.assertEqual(self.receipt_bytes(), (self.assets / artifact.ASSET_NAME).read_bytes())
        self.assertEqual({"arm64-v8a", "arm64-v8a/libdsha_rs.so"}, {p.relative_to(self.out).as_posix() for p in self.out.rglob("*")})
        self.assertEqual({artifact.ASSET_NAME}, {p.name for p in self.assets.iterdir()})
        self.assertEqual(self.library, self.library_path.read_bytes())
        self.assertEqual("PASS", self.stage()["status"])

    def test_default_assets_output_is_a_separate_directory(self):
        self.stage(assets_out=None)
        self.assertTrue((self.out.parent / "rustAssets" / artifact.ASSET_NAME).is_file())
        self.assertFalse((self.out / artifact.ASSET_NAME).exists())

    def test_unsigned_and_signed_shape_use_same_static_gate(self):
        self.write_apk()
        before = artifact.file_digest(self.apk)
        first = self.verify()
        self.assertEqual(before, artifact.file_digest(self.apk))
        # 仅增加签名形状的元数据，不声称这个 ZIP 被密码学签名过。
        self.write_apk(entries=[("META-INF/FIXTURE.SF", b"synthetic metadata; no cryptographic signature")])
        second = self.verify()
        self.assertEqual(first["artifact"], second["artifact"])
        self.assertEqual(first["receiptSha256"], second["receiptSha256"])
        self.assertEqual("STATIC_ONLY_NOT_ANDROID_LOAD", second["verification"])

    def test_stage_and_both_apk_clis(self):
        staged = self.run_cli("stage", "--mode", "prebuilt", "--artifact-dir", self.inputs,
                              "--out", self.out, "--assets-out", self.assets, *self.cli_identity())
        self.assertEqual(0, staged.returncode, staged.stderr)
        self.write_apk()
        for cli in ("verify-apk", "check"):
            with self.subTest(cli=cli):
                report = self.directory / (cli + ".json")
                result = self.run_cli(cli, "--mode", "prebuilt", "--apk", self.apk,
                                      "--receipt", self.receipt_path, "--output", report, *self.cli_identity())
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertTrue(json.loads(report.read_text())["apkVerified"])

    def test_verify_artifact_directory_matches_embedded_bytes(self):
        self.write_apk()
        self.assertEqual("PASS", self.verify(receipt_path=None, artifact_dir=self.inputs)["status"])
        self.library_path.write_bytes(self.library + b"changed")
        with self.assertRaisesRegex(artifact.GateError, "EXTERNAL_ARTIFACT_MISMATCH"):
            self.verify(receipt_path=None, artifact_dir=self.inputs)

    def test_missing_library_and_receipt(self):
        for name in (artifact.LIB_NAME, artifact.RECEIPT_NAME):
            with self.subTest(name=name):
                path = self.inputs / name
                saved = path.read_bytes()
                path.unlink()
                with self.assertRaisesRegex(artifact.GateError, "DIR_MEMBERS"):
                    self.stage()
                self.assertFalse(self.out.exists())
                path.write_bytes(saved)

    def test_artifact_directory_unknown_member_or_abi(self):
        for name in ("unknown.so", "armeabi-v7a", "arm64-v8a"):
            with self.subTest(name=name):
                path = self.inputs / name
                path.write_bytes(b"unknown")
                with self.assertRaisesRegex(artifact.GateError, "DIR_MEMBERS"):
                    self.stage()
                path.unlink()

    def test_artifact_library_receipt_and_parent_symlinks(self):
        for path in (self.library_path, self.receipt_path):
            with self.subTest(path=path.name):
                saved = path.read_bytes()
                real = self.directory / (path.name + ".real")
                real.write_bytes(saved)
                path.unlink()
                path.symlink_to(real)
                with self.assertRaisesRegex(artifact.GateError, "SYMLINK"):
                    self.stage()
                path.unlink()
                path.write_bytes(saved)
        linked = self.directory / "linked"
        linked.symlink_to(self.inputs, target_is_directory=True)
        with self.assertRaisesRegex(artifact.GateError, "SYMLINK"):
            self.stage(artifact_dir=linked)

    def test_output_symlink_unknown_member_and_wrong_abi(self):
        self.out.mkdir()
        wrong = self.out / "x86_64"
        wrong.mkdir()
        with self.assertRaisesRegex(artifact.GateError, "UNEXPECTED_MEMBER"):
            self.stage()
        wrong.rmdir()
        unknown = self.out / "unknown.so"
        unknown.write_bytes(b"unknown")
        with self.assertRaisesRegex(artifact.GateError, "UNEXPECTED_MEMBER"):
            self.stage()
        unknown.unlink()
        linked = self.out / "arm64-v8a"
        linked.symlink_to(self.directory, target_is_directory=True)
        with self.assertRaisesRegex(artifact.GateError, "SYMLINK"):
            self.stage()

    def test_overlapping_output_and_input_directories_rejected(self):
        for options in ({"out": self.inputs}, {"assets_out": self.out}, {"assets_out": self.out / "assets"}):
            with self.subTest(options=options), self.assertRaisesRegex(artifact.GateError, "OVERLAP"):
                self.stage(**options)

    def test_off_clean_has_no_writes_and_apk_is_unchanged(self):
        result = self.stage(mode="off", artifact_dir=None)
        self.assertEqual("PASS_NO_RUST_ARTIFACT", result["status"])
        self.assertFalse(self.out.exists())
        self.assertFalse(self.assets.exists())
        self.write_apk(rust=False, receipt=False)
        before = self.apk.read_bytes()
        result = self.verify(mode="off")
        self.assertEqual("PASS_NO_RUST_ARTIFACT", result["status"])
        self.assertEqual(before, self.apk.read_bytes())

    def test_off_refuses_apk_without_actual_native_abi(self):
        for directory_only in (False, True):
            with self.subTest(directory_only=directory_only):
                with zipfile.ZipFile(self.apk, "w") as archive:
                    archive.writestr("AndroidManifest.xml", b"fixture")
                    if directory_only:
                        archive.writestr("lib/arm64-v8a/", b"")
                with self.assertRaisesRegex(artifact.GateError, "RUST_APK_ABI"):
                    self.verify(mode="off")

    def test_off_refuses_library_or_receipt_in_staging_or_artifact(self):
        with self.assertRaisesRegex(artifact.GateError, "OFF_RESIDUE"):
            self.stage(mode="off")
        self.stage()
        with self.assertRaisesRegex(artifact.GateError, "OFF_RESIDUE"):
            self.stage(mode="off", artifact_dir=None)
        (self.out / "arm64-v8a/libdsha_rs.so").unlink()
        with self.assertRaisesRegex(artifact.GateError, "OFF_RESIDUE"):
            self.stage(mode="off", artifact_dir=None)

    def test_off_refuses_any_rust_apk_library_or_receipt(self):
        for rust, receipt in ((True, False), (False, True), (True, True)):
            with self.subTest(rust=rust, receipt=receipt):
                self.write_apk(rust=rust, receipt=receipt)
                before = self.apk.read_bytes()
                with self.assertRaisesRegex(artifact.GateError, "OFF_RESIDUE"):
                    self.verify(mode="off")
                self.assertEqual(before, self.apk.read_bytes())

    def test_commit_is_required_full_head_and_receipt_bound(self):
        for commit in (None, self.commit[:8], "f" * 40):
            with self.subTest(commit=commit), self.assertRaisesRegex(artifact.GateError, "COMMIT"):
                self.stage(commit=commit)
        self.receipt["commit"] = "f" * 40
        self.write_pair()
        with self.assertRaisesRegex(artifact.GateError, "IDENTITY"):
            self.stage()

    def test_ci_run_attempt_repository_and_workflow_bound(self):
        for field, value in (("runId", "999"), ("runAttempt", "3"), ("repository", "other/repo")):
            with self.subTest(field=field):
                self.receipt = self.make_receipt()
                self.receipt[field] = value
                self.write_pair()
                with self.assertRaisesRegex(artifact.GateError, "IDENTITY"):
                    self.stage()
        self.receipt = self.make_receipt()
        self.receipt["workflow"]["ref"] = "other/repo/.github/workflows/check.yml@refs/heads/main"
        self.write_pair()
        with self.assertRaisesRegex(artifact.GateError, "WORKFLOW_REPOSITORY"):
            self.stage()

    def test_workflow_sha_must_match_same_commit(self):
        self.receipt["workflow"]["sha"] = "f" * 40
        self.write_pair()
        with self.assertRaisesRegex(artifact.GateError, "WORKFLOW_COMMIT"):
            self.stage()

    def test_ci_requires_explicit_outer_identity(self):
        result = self.run_cli("stage", "--mode", "prebuilt", "--artifact-dir", self.inputs,
                              "--out", self.out, "--commit", self.commit, ci=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("CI_IDENTITY_REQUIRED", result.stderr)
        for options in ({"run_id": None}, {"run_attempt": "0"}, {"repository": "invalid"}):
            with self.subTest(options=options), self.assertRaises(artifact.GateError):
                self.stage(**options)

    def test_local_provenance_allowed_only_without_ci_expectation(self):
        self.receipt.update(artifact.generation_metadata({}))
        self.write_pair()
        self.write_apk()
        result = self.run_cli("check", "--mode", "prebuilt", "--apk", self.apk,
                              "--receipt", self.receipt_path, "--commit", self.commit)
        self.assertEqual(0, result.returncode, result.stderr)
        with self.assertRaisesRegex(artifact.GateError, "IDENTITY"):
            self.verify()
        result = self.run_cli("check", "--mode", "prebuilt", "--apk", self.apk,
                              "--receipt", self.receipt_path, "--commit", self.commit, ci=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("CI_IDENTITY_REQUIRED", result.stderr)

    def test_expired_future_naive_and_invalid_receipts(self):
        now = datetime.now(timezone.utc)
        values = [(now - timedelta(hours=25)).strftime("%Y-%m-%dT%H:%M:%SZ"),
                  (now + timedelta(minutes=6)).strftime("%Y-%m-%dT%H:%M:%SZ"),
                  "2026-11-15T04:00:00", "2026-02-30T04:00:00Z"]
        for value in values:
            with self.subTest(value=value):
                self.receipt["generatedAt"] = value
                self.write_pair()
                with self.assertRaisesRegex(artifact.GateError, "FRESHNESS|TIMESTAMP"):
                    self.stage()

    def test_freshness_inclusive_boundaries(self):
        now = datetime.now(timezone.utc).replace(microsecond=0)
        for age in (timedelta(hours=-24), timedelta(minutes=5)):
            with self.subTest(age=age):
                self.receipt["generatedAt"] = (now + age).strftime("%Y-%m-%dT%H:%M:%SZ")
                info = artifact.validate_receipt(self.receipt, self.library, identity=self.identity,
                                                 budget=self.budget, now=now)
                self.assertEqual(self.info, info)

    def test_bad_sha_and_size_are_rejected_before_staging(self):
        for field, value in (("sha256", "0" * 64), ("size", len(self.library) + 1), ("size", True)):
            with self.subTest(field=field, value=value):
                self.receipt = self.make_receipt()
                self.receipt["artifact"][field] = value
                self.write_pair()
                with self.assertRaisesRegex(artifact.GateError, "RECEIPT_ARTIFACT"):
                    self.stage()
                self.assertFalse(self.out.exists())

    def test_receipt_schema_target_triple_and_build_are_strict(self):
        mutations = [("schema", 1), ("target", "host"), ("toolchain.targetTriple", "x86_64-linux-android"),
                     ("toolchain.androidApi", 99), ("toolchain.rustc", "0.0.0"), ("build.offline", False),
                     ("build.jobs", True), ("artifact.exportedSymbols", []), ("runner.name", None)]
        for field, value in mutations:
            with self.subTest(field=field):
                self.receipt = self.make_receipt()
                target = self.receipt
                parts = field.split(".")
                for part in parts[:-1]:
                    target = target[part]
                target[parts[-1]] = value
                self.write_pair()
                with self.assertRaises(artifact.GateError):
                    self.stage()
        self.receipt = self.make_receipt()
        self.receipt["unreviewed"] = True
        self.write_pair()
        with self.assertRaisesRegex(artifact.GateError, "SCHEMA"):
            self.stage()

    def test_complete_source_fingerprint_not_just_self_reported_digest(self):
        for mutation in ("aggregate", "file_hash", "missing_file", "cargo", "vendor"):
            with self.subTest(mutation=mutation):
                self.receipt = self.make_receipt()
                source = self.receipt["sources"]
                if mutation == "aggregate":
                    source["fingerprintSha256"] = "0" * 64
                elif mutation == "file_hash":
                    source["files"][0]["sha256"] = "0" * 64
                    source["fingerprintSha256"] = artifact.rows_digest(source["files"])
                elif mutation == "missing_file":
                    source["files"].pop()
                    source["fingerprintSha256"] = artifact.rows_digest(source["files"])
                elif mutation == "cargo":
                    source["cargoLockSha256"] = "0" * 64
                else:
                    source["vendorTreeSha256"] = "0" * 64
                self.write_pair()
                with self.assertRaisesRegex(artifact.GateError, "SOURCE_FINGERPRINT"):
                    self.stage()

    def test_source_digest_covers_builders_locks_cargo_vendor_and_wiring(self):
        names = {row["path"] for row in self.source["files"]}
        required = set(artifact.INPUT_FILES) | {"rust/Cargo.lock", "rust/Cargo.toml", "rust/rust-toolchain.toml",
                                               "rust/native-budget.json", "rust/artifact.lock.json", "rust/.cargo/config.toml",
                                               "rust/crates/dsha-core/Cargo.toml", "rust/crates/dsha-core/src/lib.rs"}
        self.assertTrue(required <= names)
        self.assertTrue(any(name.startswith("rust/vendor/") for name in names))
        self.assertTrue(any(name.startswith(".github/workflows/") for name in names))
        self.assertFalse(any("/target/" in name for name in names))
        self.assertEqual(self.source["fingerprintSha256"], artifact.rows_digest(list(reversed(self.source["files"]))))
        self.assertEqual(self.lock["vendor"]["files"], self.source["vendorFiles"])
        self.assertEqual(self.lock["vendor"]["bytes"], self.source["vendorBytes"])

    def test_tree_digest_is_stable_for_mtime_and_sensitive_to_paths_and_bytes(self):
        root = self.directory / "tree"
        root.mkdir()
        (root / "one").write_bytes(b"one")
        rows = artifact.file_rows(root, root)
        first = artifact.rows_digest(rows)
        os.utime(root / "one", (1, 1))
        self.assertEqual(first, artifact.rows_digest(artifact.file_rows(root, root)))
        (root / "two").write_bytes(b"one")
        self.assertNotEqual(first, artifact.rows_digest(artifact.file_rows(root, root)))
        (root / "one").write_bytes(b"changed")
        # file_rows 依赖 os.walk 顺序，不能假设 rows[0] 仍是 "one"；按路径取回以保证与文件系统顺序无关。
        changed = {row["path"]: row["sha256"] for row in artifact.file_rows(root, root)}
        self.assertNotEqual(rows[0]["sha256"], changed[rows[0]["path"]])

    def test_symbols_exact_not_merely_count(self):
        variants = [self.symbols[:-1], ["Java_wrong_symbol"] + self.symbols[1:], self.symbols + ["extra_export"]]
        for symbols in variants:
            with self.subTest(symbols=symbols):
                data = synthetic_elf(symbols)
                self.receipt = self.make_receipt(data)
                self.write_pair(data)
                with self.assertRaisesRegex(artifact.GateError, "ELF_SYMBOLS"):
                    self.stage()

    def test_hidden_and_internal_jni_visibility_rejected_in_stage_and_apk(self):
        for visibility in (1, 2):  # STV_INTERNAL / STV_HIDDEN
            with self.subTest(visibility=visibility):
                data = patched(self.library, 0x1000 + 24 + 5, "<B", visibility)
                self.receipt = self.make_receipt(data)
                self.write_pair(data)
                with self.assertRaisesRegex(artifact.GateError, "export visibility"):
                    self.stage()
                self.write_apk(data=data)
                with self.assertRaisesRegex(artifact.GateError, "export visibility"):
                    self.verify()

    def test_needed_whitelist(self):
        data = synthetic_elf(self.symbols, needed=("libc.so", "libevil.so"))
        self.receipt = self.make_receipt(data)
        self.write_pair(data)
        with self.assertRaisesRegex(artifact.GateError, "ELF_NEEDED"):
            self.stage()

    def test_wrong_machine_and_exec_elf_rejected(self):
        for data in (synthetic_elf(self.symbols, machine=62), patched(self.library, 16, "<H", 2)):
            with self.subTest(sha=artifact.digest(data)):
                self.receipt = self.make_receipt(data)
                self.write_pair(data)
                with self.assertRaisesRegex(artifact.GateError, "ELF_MACHINE|ET_DYN"):
                    self.stage()

    def test_empty_missing_and_malformed_load_relro_dynamic(self):
        cases = {
            "no-load": patched(self.library, 64, "<I", 0),
            "empty-load": patched(patched(self.library, 64 + 32, "<Q", 0), 64 + 40, "<Q", 0),
            "filesz-over-memsz": patched(self.library, 64 + 40, "<Q", 1),
            "unaligned": patched(self.library, 64 + 48, "<Q", 4096),
            "no-relro": patched(self.library, 120, "<I", 0),
            "empty-relro": patched(patched(self.library, 120 + 32, "<Q", 0), 120 + 40, "<Q", 0),
            "unmapped-relro": patched(self.library, 120 + 16, "<Q", 0x9000),
            "no-dynamic": patched(self.library, 176, "<I", 0),
            "unterminated-dynamic": patched(self.library, 0x300 + 5 * 16, "<Q", 99),
            "dynsym-section-disagrees": patched(self.library, 0x2080 + 16, "<Q", 0x1100),
            "truncated": self.library[:200],
        }
        for name, data in cases.items():
            with self.subTest(name=name):
                self.receipt = self.make_receipt(data)
                self.write_pair(data)
                with self.assertRaisesRegex(artifact.GateError, "ELF_STRUCTURE|ELF_INVALID"):
                    self.stage()

    def test_artifact_size_budget(self):
        data = self.library.ljust(self.budget["rustArtifactBytes"] + 1, b"\0")
        self.receipt = self.make_receipt(data)
        self.write_pair(data)
        with self.assertRaisesRegex(artifact.GateError, "ARTIFACT_BUDGET"):
            self.stage()

    def test_missing_apk_library_embedded_and_external_receipt(self):
        for rust, receipt in ((False, True), (True, False), (False, False)):
            with self.subTest(rust=rust, receipt=receipt):
                self.write_apk(rust=rust, receipt=receipt)
                with self.assertRaisesRegex(artifact.GateError, "ARTIFACT_OR_RECEIPT_MISSING"):
                    self.verify()
        self.write_apk()
        with self.assertRaisesRegex(artifact.GateError, "EXTERNAL_RECEIPT_REQUIRED"):
            self.verify(receipt_path=None)

    def test_embedded_receipt_must_be_byte_identical_to_external(self):
        # 语义完全相同但格式不同也拒绝，不能把 APK 内另一个来源回执混过去。
        self.write_apk(embedded=json.dumps(self.receipt).encode("utf-8"))
        with self.assertRaisesRegex(artifact.GateError, "EMBEDDED_RECEIPT_MISMATCH"):
            self.verify()

    def test_bad_apk_library_sha_with_valid_external_receipt(self):
        self.write_apk(data=self.library + b"changed")
        with self.assertRaisesRegex(artifact.GateError, "RECEIPT_ARTIFACT"):
            self.verify()

    def test_apk_reuses_elf_symbols_and_needed_checks(self):
        variants = [synthetic_elf(self.symbols[:-1]), synthetic_elf(self.symbols, needed=("libevil.so",))]
        for data in variants:
            with self.subTest(sha=artifact.digest(data)):
                self.receipt = self.make_receipt(data)
                self.write_pair(data)
                self.write_apk(data=data)
                with self.assertRaisesRegex(artifact.GateError, "ELF_SYMBOLS|ELF_NEEDED"):
                    self.verify()

    def test_duplicate_zip_anywhere_rejected(self):
        for name in ("lib/arm64-v8a/libdsha_rs.so", artifact.APK_ASSET, "AndroidManifest.xml"):
            with self.subTest(name=name):
                self.write_apk(entries=[(name, b"duplicate")])
                with self.assertRaisesRegex(artifact.GateError, "DUPLICATE_ZIP"):
                    self.verify()

    def test_zip_symlink_and_special_file_rejected(self):
        for kind in (stat.S_IFLNK, stat.S_IFIFO):
            with self.subTest(kind=kind):
                item = zipfile.ZipInfo("assets/unknown")
                item.create_system = 3
                item.external_attr = (kind | 0o777) << 16
                self.write_apk(entries=[(item, b"target")])
                with self.assertRaisesRegex(artifact.GateError, "NONREGULAR_MEMBER"):
                    self.verify()

    def test_unknown_abi_library_and_noncanonical_zip_names(self):
        for name in ("lib/x86_64/libdsha_rs.so", "lib/armeabi-v7a/", "lib/arm64-v8a/unknown.so",
                     "lib//arm64-v8a/unknown.so", "assets/../evil", "/absolute", "assets\\evil",
                     "assets/libdsha_rs.so", "other/rust-receipt.json"):
            with self.subTest(name=name):
                self.write_apk(entries=[(name, b"unexpected")])
                with self.assertRaises(artifact.GateError):
                    self.verify()

    def test_apk_total_and_receipt_member_budgets(self):
        total = self.budget["baselineNativeBytes"] + self.budget["rustArtifactBytes"]
        self.write_apk(entries=[("lib/arm64-v8a/" + sorted((ROOT / "app/src/main/jniLibs/arm64-v8a").glob("*.so"))[1].name,
                                 b"\0" * (total + 1))])
        with self.assertRaisesRegex(artifact.GateError, "TOTAL_BUDGET"):
            self.verify()
        self.write_apk(embedded=b" " * (artifact.MAX_RECEIPT_BYTES + 1))
        with self.assertRaisesRegex(artifact.GateError, "MEMBER_BUDGET"):
            self.verify()

    def test_compressed_and_stored_rust_members(self):
        for compression in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
            with self.subTest(compression=compression):
                self.write_apk(compression=compression)
                self.assertEqual("PASS", self.verify()["status"])

    def test_json_duplicate_keys_and_nonfinite_values_rejected(self):
        for data in (b'{"schema":2,"schema":2}', b'{"schema":NaN}', b'[]', b'{broken}'):
            with self.subTest(data=data), self.assertRaises(artifact.GateError):
                artifact.parse_json(data)

    def test_budget_only_never_reports_apk_pass(self):
        report = self.directory / "budget.json"
        result = self.run_cli("check", "--output", report)
        self.assertEqual(0, result.returncode, result.stderr)
        receipt = json.loads(report.read_text())
        self.assertEqual("PASS_BUDGET_ONLY", receipt["status"])
        self.assertFalse(receipt["apkVerified"])
        self.assertEqual("BUDGET_CONFIG_ONLY_NOT_APK", receipt["verification"])
        result = self.run_cli("check", "--mode", "prebuilt", "--output", report)
        self.assertEqual(1, result.returncode)
        self.assertEqual("FAIL", json.loads(report.read_text())["status"])

    def test_failed_check_replaces_old_pass_report(self):
        self.write_apk()
        report = self.directory / "report.json"
        result = self.run_cli("check", "--mode", "prebuilt", "--apk", self.apk, "--receipt", self.receipt_path,
                              "--output", report, *self.cli_identity())
        self.assertEqual(0, result.returncode, result.stderr)
        self.receipt["commit"] = "f" * 40
        self.write_pair()
        self.write_apk()
        result = self.run_cli("check", "--mode", "prebuilt", "--apk", self.apk, "--receipt", self.receipt_path,
                              "--output", report, *self.cli_identity())
        self.assertEqual(1, result.returncode)
        self.assertEqual("FAIL", json.loads(report.read_text())["status"])
        self.assertFalse(json.loads(report.read_text())["apkVerified"])

    def test_report_output_cannot_overwrite_apk_or_external_receipt(self):
        self.write_apk()
        for path in (self.apk, self.receipt_path):
            with self.subTest(path=path):
                before = path.read_bytes()
                result = self.run_cli("check", "--mode", "prebuilt", "--apk", self.apk, "--receipt", self.receipt_path,
                                      "--output", path, *self.cli_identity())
                self.assertEqual(1, result.returncode)
                self.assertIn("REPORT_INPUT_OVERLAP", result.stderr)
                self.assertEqual(before, path.read_bytes())

    def test_legacy_require_optional_flags_and_python_wrapper(self):
        self.write_apk()
        result = self.run_cli("check", "--require", "--apk", self.apk, "--receipt", self.receipt_path, *self.cli_identity())
        self.assertEqual(0, result.returncode, result.stderr)
        result = self.run_cli("check", "--rust-optional", "--apk", self.apk)
        self.assertEqual(1, result.returncode)
        self.assertIn("OFF_RESIDUE", result.stderr)
        self.write_apk(rust=False, receipt=False)
        result = self.run_cli("check", "--rust-optional", "--apk", self.apk)
        self.assertEqual(0, result.returncode, result.stderr)
        spec = importlib.util.spec_from_file_location("rust_apk_check_fixture", TOOLS / "check-rust-native-apk.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        result = module.inspect_apk(self.apk, self.budget, None, require=False, rust_optional=True,
                                    expect_commit=None, expect_run_id=None)
        self.assertEqual("PASS_NO_RUST_ARTIFACT", result["status"])


class CommittedInputTests(unittest.TestCase):
    """仅在临时独立 Git 仓库提交 fixture，不改当前工作树或它的 index/HEAD。"""

    def git(self, root: Path, *arguments) -> str:
        env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)
        result = subprocess.run(["git", "-c", "core.hooksPath=" + os.devnull,
                                 "-c", "user.name=Rust fixture", "-c", "user.email=fixture@invalid.example",
                                 "-c", "commit.gpgsign=false", *arguments], cwd=root, env=env,
                                capture_output=True, text=True, check=False)
        if result.returncode:
            self.fail("scratch Git fixture failed (%d): %s" % (result.returncode, result.stderr))
        return result.stdout.strip()

    def fixture(self, object_format: str = "sha1") -> tuple[Path, str]:
        temporary = tempfile.TemporaryDirectory(prefix="dsha-rust-committed-fixture-")
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        for name in artifact.INPUT_FILES:
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(artifact.read_regular(ROOT / name))
        files = {"rust/Cargo.toml": b"[workspace]\nmembers = []\n", "rust/Cargo.lock": b"version = 4\n",
                 "rust/.cargo/config.toml": b"# synthetic configuration\n",
                 "rust/crates/fixture/src/lib.rs": b"// synthetic source, never compiled\n",
                 "rust/vendor/fixture/Cargo.toml": b"# synthetic vendor, never compiled\n",
                 ".cargo/config.toml": b"# repository configuration\n",
                 ".github/workflows/fixture.yml": b"name: synthetic-fixture\n",
                 ".gitignore": b"rust/ignored-input.rs\nrust/target/\n",
                 "docs/fixture.md": b"unrelated tracked documentation\n"}
        for name, data in files.items():
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        self.git(root, "init", "--quiet", "--object-format=" + object_format)
        self.git(root, "add", "--all")
        self.git(root, "commit", "--quiet", "-m", "synthetic source fixture")
        return root, self.git(root, "rev-parse", "HEAD")

    def check(self, root: Path, commit: str):
        artifact.assert_committed_inputs(artifact.source_inputs(root), commit, root=root)

    def test_clean_committed_inputs(self):
        root, commit = self.fixture()
        self.check(root, commit)

    def test_modified_tracked_source_even_with_self_consistent_snapshot(self):
        root, commit = self.fixture()
        (root / "rust/crates/fixture/src/lib.rs").write_bytes(b"// dirty source\n")
        with self.assertRaisesRegex(artifact.GateError, "INPUT_NOT_COMMITTED: modified"):
            self.check(root, commit)

    def test_deleted_tracked_input(self):
        root, commit = self.fixture()
        (root / "rust/crates/fixture/src/lib.rs").unlink()
        with self.assertRaisesRegex(artifact.GateError, "INPUT_NOT_COMMITTED:.*missing="):
            self.check(root, commit)

    def test_new_untracked_and_ignored_input(self):
        root, commit = self.fixture()
        for name in ("rust/untracked-input.rs", "rust/ignored-input.rs"):
            with self.subTest(name=name):
                path = root / name
                path.write_bytes(b"// extra source\n")
                if "ignored" in name:
                    self.git(root, "check-ignore", "--quiet", name)
                with self.assertRaisesRegex(artifact.GateError, "INPUT_NOT_COMMITTED: added="):
                    self.check(root, commit)
                path.unlink()

    def test_unrelated_dirty_docs_and_target_are_allowed(self):
        root, commit = self.fixture()
        (root / "docs/fixture.md").write_bytes(b"dirty documentation\n")
        (root / "docs/untracked.md").write_bytes(b"new unrelated documentation\n")
        (root / "rust/target").mkdir()
        (root / "rust/target/generated").write_bytes(b"generated output\n")
        self.check(root, commit)

    def test_git_index_flags_cannot_hide_dirty_source(self):
        for flag in ("--assume-unchanged", "--skip-worktree"):
            with self.subTest(flag=flag):
                root, commit = self.fixture()
                name = "rust/crates/fixture/src/lib.rs"
                self.git(root, "update-index", flag, name)
                (root / name).write_bytes(b"// hidden dirty source\n")
                with self.assertRaisesRegex(artifact.GateError, "INPUT_NOT_COMMITTED: modified"):
                    self.check(root, commit)

    def test_sha256_git_object_format(self):
        root, commit = self.fixture("sha256")
        self.assertEqual(64, len(commit))
        self.check(root, commit)

    def test_changed_snapshot_during_check(self):
        root, commit = self.fixture()
        snapshot = artifact.source_inputs(root)
        (root / "rust/crates/fixture/src/lib.rs").write_bytes(b"// changed after snapshot\n")
        with self.assertRaisesRegex(artifact.GateError, "INPUT_SNAPSHOT_CHANGED"):
            artifact.assert_committed_inputs(snapshot, commit, root=root)

    def test_receipt_rejects_dirty_source_matching_its_current_fingerprint(self):
        root, commit = self.fixture()
        lock = artifact.load_json(root / "ci/rust-toolchain.lock.json")
        budget = artifact.load_budget(ROOT)
        data = synthetic_elf(sorted(lock["abi"]["symbols"]))
        info = artifact.describe_bytes(data, target="android", lock=lock, budget=budget)
        info["reproducible"] = "NOT_RUN"
        found = dict(rustc=lock["rustc"]["version"], rustcCommit=lock["rustc"]["commit"].ljust(40, "0"),
                     cargo=lock["cargo"]["version"], host="x86_64-unknown-linux-gnu")
        identity = artifact.expected_identity(commit, None, None, None, root=root, env={})
        def receipt():
            result = rust_build.receipt(found=found, lock=lock, target="android", ndk=None, artifact=info,
                                        reproducible=dict(status="NOT_RUN", differentDirectory=False,
                                                          firstSha256=None, secondSha256=None), jobs=2,
                                        expectation={"status": "NOT_RUN"}, sources=artifact.source_inputs(root), commit=commit)
            result.update(artifact.generation_metadata({}))
            return result
        artifact.validate_receipt(receipt(), data, identity=identity, budget=budget, root=root)
        (root / "rust/crates/fixture/src/lib.rs").write_bytes(b"// dirty but matching receipt\n")
        with self.assertRaisesRegex(artifact.GateError, "INPUT_NOT_COMMITTED: modified"):
            artifact.validate_receipt(receipt(), data, identity=identity, budget=budget, root=root)


class ElfVisibilityTests(unittest.TestCase):
    def setUp(self):
        self.lock = artifact.load_json(ROOT / "ci/rust-toolchain.lock.json")
        self.budget = artifact.load_budget(ROOT)
        self.data = synthetic_elf(sorted(self.lock["abi"]["symbols"]))

    def test_default_and_protected_exports_are_visible(self):
        for visibility in (0, 3):
            with self.subTest(visibility=visibility):
                data = patched(self.data, 0x1000 + 24 + 5, "<B", visibility)
                artifact.describe_bytes(data, target="android", lock=self.lock, budget=self.budget)

    def test_hidden_and_internal_exports_are_rejected(self):
        for visibility in (1, 2):
            with self.subTest(visibility=visibility):
                data = patched(self.data, 0x1000 + 24 + 5, "<B", visibility)
                with self.assertRaisesRegex(artifact.GateError, "export visibility"):
                    artifact.describe_bytes(data, target="android", lock=self.lock, budget=self.budget)


def suite() -> unittest.TestSuite:
    return unittest.TestSuite(unittest.defaultTestLoader.loadTestsFromTestCase(testcase)
                              for testcase in (RustApkInjectionTests, CommittedInputTests, ElfVisibilityTests))


if __name__ == "__main__":
    unittest.main(verbosity=2)
