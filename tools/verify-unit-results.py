#!/usr/bin/env python3
"""核对 Standard JUnit XML 并写出单测回执（CI 各节点共用）。

full：旧单目录 API 要求 0 failure / 0 error / 0 skipped；仅为定向运行汇总。
      --require-fixtures 额外要求七个外部夹具用例各恰好执行一次并通过。
source-only：仅汇总（Gradle 已对失败返回非零）；给出 --max-skips 时才限制跳过数。
全仓 full：使用 --state/--run-id 接入 unit_test_contract；不能用旧 summary 冒充聚合。
"""
import argparse
import json
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
from unit_test_contract import PREFIX, REQUIRED_FIXTURES

DEFAULT_REPORTS = Path("app/build/test-results/testStandardDebugUnitTest")


def collect(reports):
    files = sorted(reports.glob("TEST-*.xml"))
    if not files:
        raise ValueError("JUNIT_MISSING: " + str(reports))
    suites = [ET.parse(path).getroot() for path in files]
    counts = {key: sum(int(suite.get(key, "0")) for suite in suites)
              for key in ("tests", "failures", "errors", "skipped")}
    cases = [case for suite in suites for case in suite.findall("testcase")]
    actual = {key: sum(case.find(key) is not None for case in cases)
              for key in ("failure", "error", "skipped")}
    if counts["tests"] != len(cases) or counts["skipped"] != actual["skipped"] \
            or counts["failures"] != actual["failure"] or counts["errors"] != actual["error"]:
        raise ValueError("JUNIT_COUNTS: report counts disagree with testcases " + json.dumps(counts))
    return counts, cases


def verify(reports, mode, require_fixtures=False, max_skips=None):
    counts, cases = collect(reports)
    skipped = [(case.get("classname"), case.get("name")) for case in cases if case.find("skipped") is not None]
    if mode == "full":
        if not counts["tests"] or counts["failures"] or counts["errors"]:
            raise ValueError("FULL_UNIT_TESTS_FAILED: " + json.dumps(counts))
        if skipped:
            raise ValueError("UNEXPECTED_SKIP: " + json.dumps(skipped))
    elif max_skips is not None and len(skipped) > max_skips:
        raise ValueError(f"SOURCE_ONLY_SKIPS: {len(skipped)} > {max_skips}")
    if require_fixtures:
        for owner, method in REQUIRED_FIXTURES:
            rows = [case for case in cases if case.get("classname") == owner and case.get("name") == method]
            if len(rows) != 1 or any(rows[0].find(tag) is not None for tag in ("skipped", "failure", "error")):
                raise ValueError(f"REQUIRED_FIXTURE: {owner}.{method}")
        counts["requiredFixtureTestsPassed"] = len(REQUIRED_FIXTURES)
    result = {"mode": mode, "flavors": {"Standard": counts}}
    if mode != "full":
        result["skipped"] = [f"{owner}.{name}" for owner, name in skipped]
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("full", "source-only"), required=True)
    parser.add_argument("--reports", type=Path, default=DEFAULT_REPORTS)
    parser.add_argument("--require-fixtures", action="store_true")
    parser.add_argument("--max-skips", type=int)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--state", type=Path, help="全仓新鲜运行证据目录；与旧单目录汇总分开")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--run-id")
    parser.add_argument("--run-attempt", default="1")
    parser.add_argument("--baseline", type=Path)
    args = parser.parse_args(argv)
    if args.state and (args.mode != "full" or not args.run_id or args.max_skips is not None):
        parser.error("全仓 --state 需要 --mode full/--run-id，且不接受 --max-skips")
    if args.mode == "full" and args.max_skips is not None:
        parser.error("--max-skips 只用于 source-only")
    try:
        if args.state:
            import sys
            sys.path.insert(0, str(Path(__file__).resolve().parent))
            from unit_test_contract import verify_run
            result = verify_run(args.root, args.state, run_id=args.run_id,
                                run_attempt=args.run_attempt, baseline=args.baseline)
        else:
            result = verify(args.reports, args.mode, args.require_fixtures, args.max_skips)
            result.update(kind="dsha-unit-summary", aggregate=False)
    except ValueError as error:
        raise SystemExit(str(error)) from None
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as stream:
            title = "Unit-test report summary (not a full repository aggregate)" if args.mode == "full" else "Source-only unit-test report summary (not a full repository aggregate)"
            if args.state:
                title = "Full repository unit tests with fresh task evidence"
                counts = {key: result[key] for key in ("tests", "failures", "errors", "skipped")}
            else:
                counts = result["flavors"]["Standard"]
            stream.write(f"### {title}\n- Standard: " + ", ".join(f"{k}={v}" for k, v in counts.items()) + "\n")
            if not args.state:
                for name in result.get("skipped", []):
                    stream.write(f"  - skipped: {name}\n")
    printed = {key: result[key] for key in ("kind", "mode", "tests", "baselineStatus")} if args.state else result
    print(json.dumps(printed))


if __name__ == "__main__":
    main()
