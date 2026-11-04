#!/usr/bin/env python3
"""Generate the exact rc5 workspace fork-order scaling patch."""
import argparse
import hashlib
import json
from pathlib import Path
from source_text import matches_text, write_text

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / 'app/src/main/assets/workspace-order-scale-patch.json'
VERSION = '0.2.1-rc.5'
PACKAGE = '@deepseek-ai/dsh-client-ui-workspace'
MODULE = PACKAGE + '/lib/client.js'
SOURCE_SHA256 = '9c2c17a66a5c20de00ac2852cb76debb5237439e97f877403bcaa2a471086544'


def generate(modules):
    data = (modules / MODULE).read_bytes()
    if hashlib.sha256(data).hexdigest() != SOURCE_SHA256:
        raise ValueError('WORKSPACE_ORDER_SCALE_OFFICIAL_SOURCE_CHANGED')
    package = json.loads((modules / PACKAGE / 'package.json').read_text(encoding='utf8'))
    lock = json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf8'))
    locked = lock['packages']['node_modules/' + PACKAGE]
    if package['name'] != PACKAGE or package['version'] != VERSION or locked['version'] != VERSION:
        raise ValueError('WORKSPACE_ORDER_SCALE_PACKAGE_VERSION_CHANGED')
    before = '''\t\t\tconst pending = new Set(ordinary);
\t\t\tconst placeFork = (id) => {
\t\t\t\tif (!pending.delete(id)) return;
\t\t\t\tconst parentId = summaries[id]?.parentId;
\t\t\t\tif (parentId === void 0 || parentId === id || !result.includes(parentId)) return;
\t\t\t\tplaceFork(parentId);
\t\t\t\tresult.splice(result.indexOf(id), 1);
\t\t\t\tresult.splice(result.indexOf(parentId), 0, id);
\t\t\t};
\t\t\tfor (const id of [...ordinary].reverse()) placeFork(id);
\t\t\treturn result;'''
    after = '''\t\t\t// DSHA_WORKSPACE_ORDER_SCALE_V1: local links preserve the exact fork-before-
\t\t\t// parent moves without repeated full-array scans, splices or recursive depth.
\t\t\tif (!ordinary.some((id) => summaries[id]?.parentId !== void 0 && summaries[id].parentId !== id)) return result;
\t\t\tconst pending = new Set(ordinary);
\t\t\tconst links = new Map(result.map((id) => [id, { id, previous: null, next: null }]));
\t\t\tlet head = null;
\t\t\tlet tail = null;
\t\t\tfor (const id of result) {
\t\t\t\tconst node = links.get(id);
\t\t\t\tnode.previous = tail;
\t\t\t\tif (tail === null) head = node;
\t\t\t\telse tail.next = node;
\t\t\t\ttail = node;
\t\t\t}
\t\t\tfor (let index = ordinary.length - 1; index >= 0; index--) {
\t\t\t\tconst moves = [];
\t\t\t\tlet id = ordinary[index];
\t\t\t\twhile (pending.delete(id)) {
\t\t\t\t\tconst parentId = summaries[id]?.parentId;
\t\t\t\t\tif (parentId === void 0 || parentId === id || !links.has(parentId)) break;
\t\t\t\t\tmoves.push([links.get(id), links.get(parentId)]);
\t\t\t\t\tid = parentId;
\t\t\t\t}
\t\t\t\tfor (let move = moves.length - 1; move >= 0; move--) {
\t\t\t\t\tconst [node, parent] = moves[move];
\t\t\t\t\tif (node.previous === null) head = node.next;
\t\t\t\t\telse node.previous.next = node.next;
\t\t\t\t\tif (node.next !== null) node.next.previous = node.previous;
\t\t\t\t\tnode.previous = parent.previous;
\t\t\t\t\tnode.next = parent;
\t\t\t\t\tif (parent.previous === null) head = node;
\t\t\t\t\telse parent.previous.next = node;
\t\t\t\t\tparent.previous = node;
\t\t\t\t}
\t\t\t}
\t\t\tconst reconciled = [];
\t\t\tfor (let node = head; node !== null; node = node.next) reconciled.push(node.id);
\t\t\treturn reconciled;'''
    if data.decode('utf8').count(before) != 1:
        raise ValueError('WORKSPACE_ORDER_SCALE_ANCHOR_CHANGED')
    return json.dumps(dict(version=1, dshVersion=VERSION, module=MODULE,
        sourceSha256=SOURCE_SHA256, packageIntegrity=locked['integrity'],
        purpose='Preserve the complete existing manual session/fork order with local indexed links and iterative traversal. No pagination, membership changes, or persistent data changes.',
        patches=[dict(before=before, after=after)]), ensure_ascii=False, indent=2) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--modules', type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    modules = args.modules
    if modules is None:
        pointer = json.loads((ROOT / 'app/build/test-runtimes/current.json').read_text(encoding='utf8'))
        modules = Path(pointer['raw']) / 'node_modules'
    expected = generate(modules)
    if args.check:
        if not matches_text(ASSET, expected):
            raise ValueError('WORKSPACE_ORDER_SCALE_PATCH_STALE')
    else:
        write_text(ASSET, expected)
    print('PASS locked workspace order scale patch ' + ('matches' if args.check else 'generated'))


if __name__ == '__main__':
    main()
