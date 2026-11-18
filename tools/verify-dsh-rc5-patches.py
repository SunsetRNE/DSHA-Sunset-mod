#!/usr/bin/env python3
"""逐字节核验 rc5 上游模块、完整补丁链和严格拒绝；行为回归另行执行。"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
from runtime_stable_patches import load_chains, apply

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
VERSION = '0.2.2-rc.5'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def verify(modules, assets=None):
    modules = Path(modules).resolve()
    lock = json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text())
    registry = json.loads((ASSETS / 'runtime-patches.json').read_text())
    if registry['dshVersion'] != VERSION:
        raise ValueError('RC5_PATCH_VERSION')
    chains, _ = load_chains(ASSETS, VERSION)
    rows = []
    selected = None if assets is None else set(assets)
    seen = set()
    selected_modules = set()
    for row in registry['active']:
        if selected is not None and row['asset'] not in selected:
            continue
        seen.add(row['asset'])
        recipe = json.loads((ASSETS / row['asset']).read_text())
        module = row.get('module', recipe.get('module'))
        data = (modules / module).read_bytes()
        digest = sha(data)
        if recipe.get('sourceSha256') != digest:
            raise ValueError('RC5_PATCH_SOURCE_CHANGED: ' + row['asset'])
        package_name = '/'.join(module.split('/')[:2])
        package = json.loads((modules / package_name / 'package.json').read_text())
        locked = lock['packages']['node_modules/' + package_name]
        if package['version'] != locked['version'] or recipe.get('packageIntegrity') != locked['integrity']:
            raise ValueError('RC5_PATCH_PACKAGE_CHANGED: ' + row['asset'])
        selected_modules.add(module)
        rows.append(dict(asset=row['asset'], module=module, sourceSha256=digest,
                         packageVersion=package['version'], integrity=locked['integrity']))
    if selected is not None and seen != selected:
        raise ValueError('RC5_PATCH_FAMILY_MISSING: ' + repr(sorted(selected - seen)))
    for module, chain in chains.items():
        if module not in selected_modules:
            continue
        data = (modules / module).read_bytes()
        fixed = apply(data, chain)
        if apply(fixed, chain) != fixed:
            raise ValueError('RC5_PATCH_NOT_IDEMPOTENT: ' + module)
        syntax = subprocess.run(['node', '--check', '--input-type=' + ('commonjs' if module.endswith('/client.js') else 'module')],
                                input=fixed, capture_output=True)
        if syntax.returncode:
            raise ValueError('RC5_PATCH_SYNTAX: ' + module + ': ' + syntax.stderr.decode())
        # Every chain's first admission remains strict, even when later recipes
        # contain a prior after-form. This is not behavior verification.
        first = chain[0]
        before = first[1][0][0].encode()
        changed = data.replace(before, b'/* RC5 deliberately changed upstream anchor */', 1)
        try:
            apply(changed, [first])
        except ValueError:
            pass
        else:
            raise ValueError('RC5_CHANGED_ANCHOR_ACCEPTED: ' + module)
    return dict(status='PASS_EXACT_RC5_PATCH_ADMISSION', behaviorVerified=False,
                version=VERSION, lockSha256=sha((ROOT / 'tools/dsh-runtime/package-lock.json').read_bytes()),
                active=len(rows), modules=len(selected_modules), recipes=rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--modules', type=Path, required=True)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    report = verify(args.modules)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: value for key, value in report.items() if key != 'recipes'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
