#!/usr/bin/env python3
"""Check exact SDK source proofs and execute both real SDK module formats."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
from runtime_stable_patches import apply, load_chains

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--require-registered', action='store_true')
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('sse_generator', ROOT / 'tools/prepare-openai-sse-patch.py')
    generator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(generator)
    chains, _ = load_chains(ASSETS, generator.VERSION)
    records = []
    for name, content in generator.generate(args.raw).items():
        assert (ASSETS / name).read_bytes() == content.encode('utf8'), 'OPENAI_SSE_RECIPE_STALE'
        recipe = json.loads(content)
        source = (args.raw / 'node_modules' / recipe['module']).read_bytes()
        chain = [(name, [(p['before'], p['after']) for p in recipe['patches']], [])]
        candidate = apply(source, chain)
        assert hashlib.sha256(candidate).hexdigest() == recipe['targetSha256']
        assert apply(candidate, chain) == candidate, 'OPENAI_SSE_IDEMPOTENCE'
        registered = recipe['module'] in chains
        if registered:
            complete = apply(source, chains[recipe['module']])
            assert complete == candidate, 'OPENAI_SSE_COMPLETE_CHAIN'
            assert apply(complete, chains[recipe['module']]) == complete, 'OPENAI_SSE_COMPLETE_IDEMPOTENCE'
        if args.require_registered:
            assert registered, 'OPENAI_SSE_NOT_REGISTERED'
        for patch in recipe['patches']:
            drift = source.decode('utf8').replace(patch['before'], '/* source drift */', 1).encode('utf8')
            try:
                apply(drift, chain)
            except ValueError:
                pass
            else:
                raise AssertionError('OPENAI_SSE_DRIFT_ACCEPTED')
        records.append(dict(asset=name, module=recipe['module'], sourceSha256=recipe['sourceSha256'],
                            targetSha256=recipe['targetSha256'], registered=registered,
                            anchorDriftRejected=len(recipe['patches']), idempotent=True))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(['node', str(ROOT / 'tools/test-openai-sse-patch.mjs'),
                    str(args.raw.resolve()), str(args.output.resolve())], check=True, cwd=ROOT)
    result = json.loads(args.output.read_text(encoding='utf8'))
    result['recipeProofs'] = records
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
    print('PASS exact rc5 SDK source/proof, recipe regeneration, idempotence and anchor drift rejection')


if __name__ == '__main__':
    main()
