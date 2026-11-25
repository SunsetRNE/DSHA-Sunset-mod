#!/usr/bin/env python3
"""Compare every registered DSH patch target with its actual file inside a runtime archive or APK."""
import argparse
from contextlib import ExitStack
import hashlib
import json
from pathlib import Path
import runpy
import tarfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
NPM = ROOT / "tools/dsh-runtime"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify(npm_tree, artifact, from_apk):
    for name in ("package.json", "package-lock.json"):
        if (npm_tree / name).read_bytes() != (NPM / name).read_bytes():
            raise ValueError("Locked npm input differs: " + name)
    version = json.loads((NPM / "package.json").read_bytes())["dependencies"]["@deepseek-ai/dsh"]
    builder = runpy.run_path(str(ROOT / "tools/build-dsh-runtime.py"))
    chains, recipe_names = builder["load_chains"](ASSETS, version)
    if not chains:
        raise ValueError("Empty DSH patch registry")
    expected = {}
    for module, chain in chains.items():
        relative = Path(module)
        raw = (npm_tree / "node_modules" / relative).read_bytes()
        before_chain = builder["patched_content"](relative, raw)
        output = builder["apply_stable_patches"](before_chain, chain)
        name = builder["target_path"](relative)
        if name in expected:
            raise ValueError("Duplicate target path: " + name)
        expected[name] = {
            "module": module, "recipes": [entry[0] for entry in chain],
            "rawSha256": digest(raw), "expectedSha256": digest(output), "observedSha256": None,
        }

    with ExitStack() as streams:
        if from_apk:
            apk = streams.enter_context(zipfile.ZipFile(artifact))
            members = apk.namelist()
            identified = json.loads(apk.read("assets/runtime-descriptor.json"))["inputs"]
            for name in recipe_names:
                member = "assets/" + name
                recipe = (ASSETS / name).read_bytes()
                if members.count(member) != 1 or apk.read(member) != recipe:
                    raise ValueError("Packaged patch recipe differs: " + name)
                if identified.get(name) != digest(recipe):
                    raise ValueError("Runtime descriptor does not identify patch input: " + name)
            if members.count("assets/dsh-runtime.bin") != 1:
                raise ValueError("Expected exactly one DSH runtime archive in the APK")
            source = streams.enter_context(apk.open("assets/dsh-runtime.bin"))
        else:
            source = streams.enter_context(artifact.open("rb"))
        archive = streams.enter_context(tarfile.open(fileobj=source, mode="r|*"))
        for member in archive:
            item = expected.get(member.name.removeprefix("./"))
            if item is None:
                continue
            if not member.isfile() or item["observedSha256"] is not None:
                raise ValueError("Invalid or duplicate DSH module: " + member.name)
            with archive.extractfile(member) as file:
                observed = hashlib.sha256()
                for chunk in iter(lambda: file.read(1024 * 1024), b""):
                    observed.update(chunk)
                item["observedSha256"] = observed.hexdigest()

    mismatches = [row["module"] for row in expected.values() if row["observedSha256"] != row["expectedSha256"]]
    return dict(status="FAILED" if mismatches else "PASS", version=version, artifact=str(artifact),
                npmTree=str(npm_tree), lockSha256=digest((NPM / "package-lock.json").read_bytes()),
                registrySha256=digest((ASSETS / "runtime-patches.json").read_bytes()), fromApk=from_apk,
                recipeInputs=len(recipe_names), moduleCount=len(expected), mismatches=mismatches, modules=expected)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--npm-tree", type=Path, required=True, help="Root of an installation from tools/dsh-runtime/package-lock.json")
    choice = parser.add_mutually_exclusive_group(required=True)
    choice.add_argument("--archive", type=Path)
    choice.add_argument("--apk", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    report = verify(args.npm_tree, args.apk or args.archive, args.apk is not None)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("status", "recipeInputs", "moduleCount", "mismatches")}))
    raise SystemExit(1 if report["mismatches"] else 0)
