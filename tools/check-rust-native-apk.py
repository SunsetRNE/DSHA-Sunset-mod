#!/usr/bin/env python3
"""APK 内 Rust 成员门禁骨架（R0 不接入 build-unsigned；R1 起由 package/发布链调用）。

无 `--apk`：只核对 `rust/native-budget.json` 与锁文件一致（rust-check 的配置门禁）。
带 `--apk`：
1. `lib/` 只允许 `arm64-v8a/`、仓库现有 14 个 `.so` 名字与 `rust/native-budget.json`
   的 `allowedRustName`；出现其它 ABI 目录或未知 `.so` 即失败；
2. 若 APK 含 `libdsha_rs.so`：必须有 `--receipt`，摘要/大小与 receipt 一致，receipt 的
   commit/runId（给出时）与本 run 绑定；ELF 机器码 = AArch64、16 KB 对齐、RELRO 映射，
   体积不超过预算；
3. 若 APK 不含 `libdsha_rs.so`：需要显式 `--rust-optional`（R0 的未签名 APK 属此情形），
   否则 `--require` 会失败；R0 不因为“没有 Rust 产物”而宣称 Rust 已验证。

本脚本只读，不改 APK；签名后的二次核对仍以 sign-verify 的载荷比对为准。
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
import zipfile

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
RUST = ROOT / "rust"
BUDGET = RUST / "native-budget.json"
LOCK = ROOT / "ci/rust-toolchain.lock.json"
JNI_DIR = ROOT / "app/src/main/jniLibs"
sys.path.insert(0, str(TOOLS / "rust"))

import elf  # noqa: E402


class GateError(RuntimeError):
    pass


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_budget() -> dict:
    budget = json.loads(BUDGET.read_text(encoding="utf-8"))
    if budget.get("abi") != "arm64-v8a" or not budget.get("allowedRustName"):
        raise GateError("RUST_BUDGET_SCHEMA")
    if not 0 < budget["rustWarnBytes"] < budget["rustArtifactBytes"]:
        raise GateError("RUST_BUDGET_THRESHOLDS")
    tracked = sorted(p.name for p in (JNI_DIR / budget["abi"]).glob("*.so"))
    if len(tracked) != budget["baselineNativeFiles"]:
        raise GateError("RUST_BUDGET_BASELINE_FILES: %d != %d"
                        % (len(tracked), budget["baselineNativeFiles"]))
    total = sum((JNI_DIR / budget["abi"] / name).stat().st_size for name in tracked)
    if total != budget["baselineNativeBytes"]:
        raise GateError("RUST_BUDGET_BASELINE_BYTES: %d != %d"
                        % (total, budget["baselineNativeBytes"]))
    return budget


def inspect_apk(path: Path, budget: dict, receipt_path: Path | None,
                *, require: bool, rust_optional: bool, expect_commit: str | None,
                expect_run_id: str | None) -> dict:
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    tracked = {name for name in (JNI_DIR / budget["abi"]).glob("*.so")}
    allowed = {"lib/" + budget["abi"] + "/" + path.name for path in tracked}
    allowed.add("lib/" + budget["abi"] + "/" + budget["allowedRustName"])
    rust_name = "lib/" + budget["abi"] + "/" + budget["allowedRustName"]
    with zipfile.ZipFile(path) as apk:
        members = [item for item in apk.infolist() if item.filename.startswith("lib/")]
        names = [item.filename for item in members]
        unknown = sorted(set(names) - allowed)
        if unknown:
            raise GateError("RUST_APK_UNEXPECTED_LIB: " + str(unknown))
        abis = sorted({name.split("/")[1] for name in names if name.count("/") >= 2})
        if abis != [budget["abi"]]:
            raise GateError("RUST_APK_ABI: " + str(abis))
        rust_member = next((item for item in members if item.filename == rust_name), None)
        if rust_member is None:
            if require or not rust_optional:
                raise GateError("RUST_APK_ARTIFACT_MISSING: %s（R0 未接入时须显式 --rust-optional）"
                                % rust_name)
            return {"status": "PASS_NO_RUST_ARTIFACT", "rustArtifact": "none",
                    "nativeMembers": len(names),
                    "note": "R0 未接入 build-unsigned；不把“没有 .so”当成 Rust 已验证"}
        data = apk.read(rust_member)
    if receipt_path is None or not receipt_path.is_file():
        raise GateError("RUST_APK_RECEIPT_MISSING")
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    artifact = receipt.get("artifact", {})
    if artifact.get("sha256") != digest(data) or artifact.get("size") != len(data):
        raise GateError("RUST_APK_RECEIPT_MISMATCH")
    if expect_commit and receipt.get("commit") != expect_commit:
        raise GateError("RUST_APK_RECEIPT_COMMIT: %s != %s"
                        % (receipt.get("commit"), expect_commit))
    if expect_run_id and receipt.get("runId") != expect_run_id:
        raise GateError("RUST_APK_RECEIPT_RUN: %s != %s"
                        % (receipt.get("runId"), expect_run_id))
    contract = elf.inspect_bytes(data)
    if contract["machine"] != elf.EM_AARCH64:
        raise GateError("RUST_APK_ELF_MACHINE: %d" % contract["machine"])
    if not contract["aligned_16k"]:
        raise GateError("RUST_APK_ELF_ALIGN: 未按 16 KB 对齐")
    if not contract["relro_mapped"]:
        raise GateError("RUST_APK_ELF_RELRO")
    if len(data) > budget["rustArtifactBytes"]:
        raise GateError("RUST_APK_BUDGET: %d > %d" % (len(data), budget["rustArtifactBytes"]))
    total = 0
    with zipfile.ZipFile(path) as apk:
        for item in apk.infolist():
            if item.filename.startswith("lib/") and item.filename.endswith(".so"):
                total += item.file_size
    if total > budget["baselineNativeBytes"] + budget["rustArtifactBytes"]:
        raise GateError("RUST_APK_TOTAL_BUDGET: %d" % total)
    warning = len(data) > budget["rustWarnBytes"]
    if warning:
        print("::warning::Rust APK 成员 %d B 超过警戒线 %d B" % (len(data), budget["rustWarnBytes"]))
    return {"status": "PASS", "rustArtifact": "present",
            "rustBytes": len(data), "rustSha256": digest(data),
            "elfMachine": contract["machine"],
            "aligned16k": contract["aligned_16k"], "relroMapped": contract["relro_mapped"],
            "needed": contract["needed"], "nativeMembers": len(names),
            "budget": "WARN" if warning else "PASS", "abi": budget["abi"],
            "exportedSymbols": len(elf.exported_functions(data)),
            "expectedSymbols": len(lock["abi"]["symbols"])}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--commit")
    parser.add_argument("--run-id")
    parser.add_argument("--require", action="store_true",
                        help="APK 必须包含 Rust 产物（R1 起的发布链使用）")
    parser.add_argument("--rust-optional", action="store_true",
                        help="显式接受 R0 的“APK 不含 Rust 产物”")
    args = parser.parse_args(argv)
    budget = load_budget()
    if args.apk is None:
        print(json.dumps({"status": "PASS", "mode": "budget-only",
                          "baselineNativeBytes": budget["baselineNativeBytes"],
                          "rustArtifactBytes": budget["rustArtifactBytes"],
                          "warning": "R0 未接入 build-unsigned；本模式只核对预算配置"},
                         ensure_ascii=False))
        return 0
    if not args.apk.is_file():
        raise GateError("RUST_APK_MISSING: " + str(args.apk))
    result = inspect_apk(args.apk, budget, args.receipt, require=args.require,
                         rust_optional=args.rust_optional,
                         expect_commit=args.commit, expect_run_id=args.run_id)
    result["apk"] = {"name": args.apk.name, "sha256": digest(args.apk.read_bytes()),
                     "size": args.apk.stat().st_size}
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except GateError as error:
        print("FAIL " + str(error), file=sys.stderr)
        raise SystemExit(1)
