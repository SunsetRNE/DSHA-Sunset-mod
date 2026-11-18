"""rc5 手工审查配方的兼容入口；旧生成器不得覆盖新格式配方。"""
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def check_rc5(modules, names, check):
    version = json.loads((ROOT / 'tools/dsh-runtime/package.json').read_text())['dependencies']['@deepseek-ai/dsh']
    if version != '0.2.2-rc.5':
        return False
    if not check:
        raise ValueError('RC5_RECIPE_REVIEW_REQUIRED: rc5 uses reviewed exact-byte recipes; use --check and review source/API changes before editing, not the historical rc1 generator')
    specification = importlib.util.spec_from_file_location('rc5_recipe_verifier', ROOT / 'tools/verify-dsh-rc5-patches.py')
    verifier = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(verifier)
    result = verifier.verify(modules, names)
    print('PASS_RC5_REVIEWED_RECIPE_CHECK (not regenerated): ' + ', '.join(row['asset'] for row in result['recipes']))
    return True
