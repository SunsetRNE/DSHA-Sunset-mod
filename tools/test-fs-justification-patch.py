#!/usr/bin/env python3
"""Run the exact rc5 filesystem patch through real schemas, approval and host I/O."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
from runtime_stable_patches import load_chains, apply

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw', type=Path, default=os.environ.get('DSHA_TEST_RUNTIME'))
    parser.add_argument('--output', type=Path, default=ROOT / 'app/build/fs-justification-test-results.json')
    args = parser.parse_args()
    if args.raw is None:
        args.raw = Path(json.loads((ROOT / 'app/build/test-runtimes/current.json').read_text(encoding='utf8'))['raw'])
    spec = importlib.util.spec_from_file_location('fs_justification_generator', ROOT / 'tools/prepare-fs-justification-patch.py')
    generator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(generator)
    expected = generator.generate(args.raw / 'node_modules')
    assert (ASSETS / 'fs-justification-patch.json').read_bytes() == expected.encode(), 'GENERATED_RECIPE_STALE'
    recipe = json.loads(expected)
    source = (args.raw / 'node_modules' / generator.MODULE).read_bytes()
    chains, inputs = load_chains(ASSETS, generator.VERSION)
    assert 'fs-justification-patch.json' in inputs, 'PATCH_NOT_REGISTERED'
    chain = chains[generator.MODULE]
    own = [row for row in chain if row[0] == 'fs-justification-patch.json']
    assert len(own) == 1
    patched = apply(source, own)
    complete = apply(source, chain)
    assert apply(patched, own) == patched, 'SINGLE_RECIPE_NOT_IDEMPOTENT'
    assert apply(complete, chain) == complete, 'COMPLETE_CHAIN_NOT_IDEMPOTENT'
    assert complete.count(b'await guardIntent(') == 2, 'DESTRUCTIVE_GUARD_REMOVED'
    assert complete.count(b'const sandboxPolicy = await sandbox.resolvePolicy(') == 2
    for before, _ in own[0][1]:
        drift = source.decode().replace(before, '/* source drift */', 1).encode()
        try:
            apply(drift, chain)
        except ValueError:
            pass
        else:
            raise AssertionError('CHANGED_ANCHOR_ACCEPTED')
    work = (ROOT / 'app/build/fs-justification-tests').resolve()
    assert (ROOT / 'app/build').resolve() in work.parents
    work.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='case-', dir=work) as temporary:
        stage = Path(temporary)
        raw_module = stage / 'official.mjs'
        patched_module = stage / 'patched.mjs'
        full_module = stage / 'complete-chain.mjs'
        raw_module.write_bytes(source)
        patched_module.write_bytes(patched)
        full_module.write_bytes(complete)
        subprocess.run(['node', '--check', str(full_module)], check=True, cwd=ROOT)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['node', str(ROOT / 'tools/test-fs-justification-patch.mjs'),
                        str(args.raw.resolve()), str(raw_module), str(patched_module),
                        str(args.output.resolve())], check=True, cwd=ROOT)
    print('PASS rc5 filesystem exact source, registered complete chain, unchanged destructive guards, repeat application and anchor drift rejection')
    print('sourceSha256=' + hashlib.sha256(source).hexdigest())


if __name__ == '__main__':
    main()
