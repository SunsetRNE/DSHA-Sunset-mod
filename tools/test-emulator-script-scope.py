#!/usr/bin/env python3
"""静态检查：工具脚本里不能出现「运行到才报」的未定义全局名。

2026-11-08 的真实故障：`standardAssetsDigest` 被插进 `recovery()`，而 `assets` 只是
`main()` 的局部变量，脚本一跑到那里就 `NameError`；CI 只 import 模块、不执行 `main()`，
所以全绿。这类错误单测应当能拦住，不需要真跑一遍资产生成。

用 `symtable` 而不是正则：它能正确区分「局部/参数」与「在模块作用域解析」。
"""
import builtins
import symtable
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = [
    'tools/prepare-emulator-assets.py',
    'tools/prepare-emulator-standard-x64.py',
    'tools/prepare-emulator-platform-packages.py',
    'tools/verify-emulator-assets.py',
    'tools/build-emulator-native.py',
]


def module_names(table):
    names = set()
    for symbol in table.get_symbols():
        if (
            symbol.is_assigned()
            or symbol.is_imported()
            or symbol.is_namespace()
            or symbol.is_parameter()
        ):
            names.add(symbol.get_name())
    return names


def walk(table, defined, problems, trail):
    for child in table.get_children():
        name = child.get_type() + ':' + child.get_name()
        if child.get_type() == 'function':
            for symbol in child.get_symbols():
                if not symbol.is_global():
                    continue
                symbol_name = symbol.get_name()
                if symbol_name in defined or hasattr(builtins, symbol_name):
                    continue
                problems.append((child.get_name(), symbol_name))
        walk(child, defined, problems, trail + [name])


def check(path):
    source = (ROOT / path).read_text(encoding='utf-8')
    table = symtable.symtable(source, path, 'exec')
    defined = module_names(table)
    problems = []
    walk(table, defined, problems, [])
    return problems


def main():
    failed = 0
    for path in SCRIPTS:
        if not (ROOT / path).is_file():
            print('SKIP', path, '(not present)')
            continue
        problems = check(path)
        for function, symbol in problems:
            print('UNDEFINED_GLOBAL: %s: %s() 读取了未定义的全局名 %s' % (path, function, symbol))
            failed += 1
        if not problems:
            print('OK', path)
    if failed:
        print('\n%d 处未定义全局名（运行到那一行才会 NameError）' % failed)
        return 1
    print('\nPASS: 所有工具脚本的函数只引用已定义的全局名')
    return 0


if __name__ == '__main__':
    sys.exit(main())
