#!/usr/bin/env python3
"""确定性展开 `tools/rust/fixtures.json` 为 host-jvm 探针可读的 PlanView 夹具 corpus。

输出格式（UTF-8，一行一例，`|` 分隔）：

    <name>|<expected>|<hex>

`expected` 为 `VALID`、逗号分隔的原因码（如 `20,21`），或 `JVM_RUST`（双向对拍：
JVM 结果是参照，Rust 必须给出相同 VALID/原因集合）。

生成物只写 `app/build/`（不入库）；`canonical.hex` 与 Rust/Java 单测常量是同一份
跨实现字节 pin，生成时会先核对字段化描述重新编码的结果。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FIXTURES = Path(__file__).resolve().parent / "fixtures.json"
DEFAULT_OUT = ROOT / "app/build/rust-fixtures/plan-fixtures.txt"
HEADER_BYTES = 48
MASK64 = (1 << 64) - 1
TYPE_CODE = {"u8": 1, "u32": 2, "u64": 3, "bytes32": 4, "utf8": 5}
FIELD_TYPE = {
    1: "u32",
    2: "u8",
    3: "u8",
    4: "u32",
    5: "u32",
    6: "u64",
    7: "u64",
    8: "bytes32",
    9: "u32",
    10: "u8",
    11: "utf8",
    12: "utf8",
    13: "utf8",
    14: "u32",
    15: "bytes32",
}


class FixtureError(RuntimeError):
    pass


def u16(value: int) -> bytes:
    return int(value).to_bytes(2, "little")


def u32(value: int) -> bytes:
    return int(value).to_bytes(4, "little")


def u64(value: int) -> bytes:
    return int(value).to_bytes(8, "little")


def encode_value(field_type: str, spec: dict) -> bytes:
    if "valueHex" in spec:
        return bytes.fromhex(spec["valueHex"])
    if "repeat" in spec or "length" in spec:
        repeat = spec.get("repeat", "a")
        length = int(spec.get("length", 0))
        text = repeat * length
        if field_type == "utf8":
            return text.encode("utf-8")
        raise FixtureError("repeat 只用于 utf8 字段")
    value = spec.get("value")
    if field_type == "utf8":
        if not isinstance(value, str):
            raise FixtureError("utf8 字段需要字符串 value")
        return value.encode("utf-8")
    if field_type == "bytes32":
        raise FixtureError("bytes32 字段需要 valueHex")
    if not isinstance(value, int) or isinstance(value, bool):
        raise FixtureError("数值字段需要整数 value")
    if field_type == "u8":
        return int(value).to_bytes(1, "little")
    if field_type == "u32":
        return int(value).to_bytes(4, "little")
    if field_type == "u64":
        return int(value).to_bytes(8, "little")
    raise FixtureError("未知字段类型 " + field_type)


def make_field(field_id: int, field_type: str, flags: int, value: bytes) -> dict:
    return {
        "id": int(field_id),
        "type": TYPE_CODE[field_type],
        "flags": int(flags),
        "value": value,
    }


def parse_field(spec: dict) -> dict:
    field_id = int(spec["id"])
    field_type = spec.get("type", FIELD_TYPE.get(field_id))
    if field_type not in TYPE_CODE:
        raise FixtureError("未知字段类型 %r" % (field_type,))
    return make_field(field_id, field_type, spec.get("flags", 0), encode_value(field_type, spec))


def default_header(fixtures: dict) -> dict:
    canonical = fixtures["canonical"]
    return {
        "magic": bytes.fromhex("44534850"),
        "viewVersion": 1,
        "policyVersion": 1,
        "totalDelta": 0,
        "fieldCount": 15,
        "flags": int(canonical["flags"]),
        "generatedAtMs": int(canonical["generatedAtMs"]),
        "expiresAtMs": int(canonical["expiresAtMs"]),
        "nowMs": int(canonical["nowMs"]),
        "reserved": int(canonical["reserved"]),
    }


def build(header: dict, fields: list[dict]) -> bytes:
    body = b"".join(
        u16(field["id"])
        + bytes([field["type"], field["flags"]])
        + u32(len(field["value"]))
        + field["value"]
        for field in fields
    )
    total = HEADER_BYTES + len(body) + int(header.get("totalDelta", 0))
    out = bytearray()
    out += header["magic"]
    out += u16(header["viewVersion"])
    out += u16(header["policyVersion"])
    out += u32(total)
    out += u32(header["fieldCount"])
    out += u32(header["flags"])
    out += u64(header["generatedAtMs"])
    out += u64(header["expiresAtMs"])
    out += u64(header["nowMs"])
    out += u32(header["reserved"])
    out += body
    return bytes(out)


def field_index(fields: list[dict], field_id: int) -> int:
    for index, field in enumerate(fields):
        if field["id"] == field_id:
            return index
    raise FixtureError("夹具缺少字段 %d" % field_id)


def apply_case(fixtures: dict, case: dict) -> bytes:
    header = default_header(fixtures)
    fields = [
        make_field(
            int(spec["id"]),
            spec.get("type", FIELD_TYPE[int(spec["id"])]),
            spec.get("flags", 0),
            encode_value(spec.get("type", FIELD_TYPE[int(spec["id"])]), spec),
        )
        for spec in fixtures["canonical"]["fields"]
    ]
    op = case.get("op", "none")
    if op == "none":
        return build(header, fields)
    if op == "magic":
        header["magic"] = bytes.fromhex(case["valueHex"])
        return build(header, fields)
    if op == "header":
        for key in ("viewVersion", "policyVersion", "fieldCount", "flags", "reserved",
                    "generatedAtMs", "expiresAtMs", "nowMs"):
            if key in case:
                if key not in header:
                    raise FixtureError("未知头部字段 " + key)
                header[key] = int(case[key])
        return build(header, fields)
    if op == "total-delta":
        header["totalDelta"] = int(case["value"])
        return build(header, fields)
    if op == "swap":
        first, second = (field_index(fields, int(value)) for value in case["ids"])
        fields[first], fields[second] = fields[second], fields[first]
        return build(header, fields)
    if op == "duplicate":
        index = field_index(fields, int(case["id"]))
        fields.insert(index + 1, dict(fields[index]))
        return build(header, fields)
    if op == "drop":
        fields.pop(field_index(fields, int(case["id"])))
        return build(header, fields)
    if op == "insert":
        position = field_index(fields, int(case["after"])) + 1
        fields.insert(
            position,
            {
                "id": int(case["id"]),
                "type": int(case["wireType"]),
                "flags": int(case.get("flags", 0)),
                "value": bytes.fromhex(case["valueHex"]),
            },
        )
        return build(header, fields)
    if op == "set":
        index = field_index(fields, int(case["id"]))
        fields[index] = parse_field(case)
        return build(header, fields)
    if op == "set-raw":
        # 原始三元组：允许未知类型码、字段 flags 与任意长度（结构反例）。
        index = field_index(fields, int(case["id"]))
        fields[index] = {
            "id": int(case["id"]),
            "type": int(case["type"]),
            "flags": int(case.get("flags", 0)),
            "value": bytes.fromhex(case["valueHex"]),
        }
        return build(header, fields)
    if op == "append":
        return build(header, fields) + bytes.fromhex(case["valueHex"])
    if op == "truncate":
        view = build(header, fields)
        return view[: len(view) - int(case["value"])]
    if op == "multi":
        header["policyVersion"] = int(case["policyVersion"])
        header["flags"] = int(case["flags"])
        header["generatedAtMs"] = int(case["generatedAtMs"])
        header["expiresAtMs"] = int(case["expiresAtMs"])
        header["nowMs"] = int(case["nowMs"])
        fields[field_index(fields, 4)]["value"] = u32(int(case["rootCount"]))
        return build(header, fields)
    raise FixtureError("未知 op " + op)


class Lcg:
    """确定性 64 位 LCG（与 Java 差分测试、Rust 单测同一算法的不同实现）。"""

    def __init__(self, seed: int) -> None:
        self.state = seed & MASK64

    def next(self) -> int:
        self.state = (
            self.state * 6364136223846793005 + 1442695040888963407
        ) & MASK64
        return self.state


def random_cases(fixtures: dict, canonical: bytes) -> list[tuple[str, str, bytes]]:
    spec = fixtures["random"]
    seed = int(spec["seed"])
    count = int(spec["count"])
    max_length = int(spec["maxLength"])
    generator = Lcg(seed)
    cases = []
    for index in range(count):
        mode = index % 3
        if mode == 0:
            length = generator.next() % (max_length + 1)
            view = bytes(generator.next() % 256 for _ in range(length))
        elif mode == 1:
            suffix = generator.next() % 120
            view = bytearray(canonical[: min(len(canonical), 48 + suffix)])
            for position in range(48, len(view)):
                view[position] = generator.next() % 256
            view = bytes(view)
        else:
            view = bytearray(canonical)
            mutations = 1 + generator.next() % 4
            for _ in range(mutations):
                position = generator.next() % len(view)
                view[position] = generator.next() % 256
            view = bytes(view)
        cases.append(("random-%03d" % index, "JVM_RUST", view))
    return cases


def expected_text(expect: object) -> str:
    if expect == "VALID":
        return "VALID"
    if isinstance(expect, list):
        return ",".join(str(int(value)) for value in expect)
    raise FixtureError("非法 expect: %r" % (expect,))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, default=DEFAULT_FIXTURES)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--summary", type=Path, help="可选：写出 JSON 摘要")
    args = parser.parse_args(argv)

    fixtures = json.loads(args.fixtures.read_text(encoding="utf-8"))
    if fixtures.get("schema") != 1 or fixtures.get("kind") != "dsha-planview-fixtures":
        raise FixtureError("FIXTURES_SCHEMA: schema/kind 不符")
    canonical = apply_case(fixtures, {"name": "canonical", "op": "none"})
    pinned = bytes.fromhex(fixtures["canonical"]["hex"])
    if canonical != pinned:
        raise FixtureError(
            "FIXTURES_CANONICAL_MISMATCH: 生成字节与 canonical.hex 不一致\n实际 %s\n期望 %s"
            % (canonical.hex(), pinned.hex())
        )

    rows: list[tuple[str, str, bytes]] = []
    for case in fixtures["cases"]:
        name = case["name"]
        if not isinstance(name, str) or "|" in name:
            raise FixtureError("FIXTURES_NAME: " + repr(name))
        rows.append((name, expected_text(case["expect"]), apply_case(fixtures, case)))
    rows.extend(random_cases(fixtures, canonical))

    lines = ["%s|%s|%s" % (name, expected, view.hex()) for name, expected, view in rows]
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    random_spec = fixtures["random"]
    summary = {
        "fixtures": len(fixtures["cases"]),
        "random": len(rows) - len(fixtures["cases"]),
        "randomSeed": int(random_spec["seed"]),
        "randomCount": int(random_spec["count"]),
        "total": len(rows),
        "canonicalBytes": len(canonical),
        "canonicalSha256": __import__("hashlib").sha256(canonical).hexdigest(),
        "out": str(args.out),
    }
    if args.summary:
        args.summary.parent.mkdir(parents=True, exist_ok=True)
        args.summary.write_text(
            json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
            newline="\n",
        )
    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except FixtureError as error:
        print("FAIL " + str(error), file=sys.stderr)
        raise SystemExit(1)
