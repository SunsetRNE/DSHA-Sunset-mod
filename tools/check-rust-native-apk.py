#!/usr/bin/env python3
"""Rust APK 静态门禁；unsigned 与 signed APK 走同一严格验证器，不改 APK。

无 --apk：只核对 native-budget 与现有 JNI 基线，回执明确 apkVerified=false。
--mode off：拒绝任何 Rust .so 或嵌入回执；不删除残留、不重写 APK。
--mode prebuilt --apk APK --receipt rust-receipt.json --commit FULL_HEAD
    [--run-id ID --run-attempt N --repository OWNER/REPO] [--output REPORT]

prebuilt 要求 schema=2、同 HEAD/完整输入指纹、CI/local 来源和有界新鲜度，以及
APK 嵌入 assets/rust-native-receipt.json 与外部回执逐字节相同。ELF/符号/依赖/
体积检查复用 tools/rust/artifact.py。CI 身份必须由外层显式传入，不从环境补值。
旧 --require 等价 prebuilt；--rust-optional 等价 off（不再接受含 Rust 的可选包）。
所有 PASS 都只证明静态契约，不代表 Android 加载、运行或 APK 签名已验证。
"""
from __future__ import annotations

import argparse
from pathlib import Path
import sys

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
RUST = ROOT / "rust"
BUDGET = RUST / "native-budget.json"
LOCK = ROOT / "ci/rust-toolchain.lock.json"
JNI_DIR = ROOT / "app/src/main/jniLibs"
sys.path.insert(0, str(TOOLS / "rust"))
import artifact  # noqa: E402

GateError = artifact.GateError
digest = artifact.digest


def load_budget() -> dict:
    return artifact.load_budget(ROOT)


def inspect_apk(path: Path, budget: dict, receipt_path: Path | None,
                *, require: bool = False, rust_optional: bool = False,
                expect_commit: str | None = None, expect_run_id: str | None = None,
                expect_run_attempt: str | None = None, expect_repository: str | None = None,
                mode: str | None = None, artifact_dir: Path | None = None) -> dict:
    if require and rust_optional:
        raise GateError("RUST_MODE_CONFLICT: --require / --rust-optional")
    if mode is not None and (require and mode != "prebuilt" or rust_optional and mode != "off"):
        raise GateError("RUST_MODE_CONFLICT: explicit mode / legacy flag")
    selected = mode if mode is not None else ("off" if rust_optional else "prebuilt")
    return artifact.verify_apk(path, mode=selected, receipt_path=receipt_path,
                               artifact_dir=artifact_dir, commit=expect_commit,
                               run_id=expect_run_id, run_attempt=expect_run_attempt,
                               repository=expect_repository, root=ROOT, budget=budget)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("off", "prebuilt"))
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--artifact-dir", type=Path)
    parser.add_argument("--commit")
    parser.add_argument("--run-id")
    parser.add_argument("--run-attempt")
    parser.add_argument("--repository")
    parser.add_argument("--output", type=Path, help="JSON 回执；失败时写 FAIL，防止沿用旧 PASS")
    legacy = parser.add_mutually_exclusive_group()
    legacy.add_argument("--require", action="store_true", help="旧别名：--mode prebuilt")
    legacy.add_argument("--rust-optional", action="store_true", help="旧别名：--mode off")
    args = parser.parse_args(argv)
    protected = (args.apk, args.receipt)
    if args.artifact_dir is not None:
        protected += (args.artifact_dir / artifact.LIB_NAME, args.artifact_dir / artifact.RECEIPT_NAME)
    try:
        budget = load_budget()
        if args.apk is None:
            if any(value is not None for value in (args.mode, args.receipt, args.artifact_dir,
                                                    args.commit, args.run_id, args.run_attempt,
                                                    args.repository)) or args.require or args.rust_optional:
                raise GateError("RUST_APK_REQUIRED: 只有无 APK 参数的调用才是 budget-only")
            result = dict(status="PASS_BUDGET_ONLY", mode="budget-only", apkVerified=False,
                          verification="BUDGET_CONFIG_ONLY_NOT_APK", androidLoadTested=False,
                          baselineNativeBytes=budget["baselineNativeBytes"],
                          rustArtifactBytes=budget["rustArtifactBytes"])
        else:
            result = inspect_apk(args.apk, budget, args.receipt, require=args.require,
                                 rust_optional=args.rust_optional, mode=args.mode,
                                 artifact_dir=args.artifact_dir, expect_commit=args.commit,
                                 expect_run_id=args.run_id, expect_run_attempt=args.run_attempt,
                                 expect_repository=args.repository)
    except GateError as error:
        artifact.report_result(dict(status="FAIL", mode=args.mode, apkVerified=False, error=str(error)),
                               args.output, protected=protected)
        raise
    artifact.report_result(result, args.output, protected=protected)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except GateError as error:
        print("FAIL " + str(error), file=sys.stderr)
        raise SystemExit(1)
