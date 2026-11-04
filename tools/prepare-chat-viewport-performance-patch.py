#!/usr/bin/env python3
"""Generate a locked rc5 semantic-anchor lookup patch without a DOM cache."""
import argparse
import hashlib
import json
from pathlib import Path

from source_text import matches_text, write_text

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / 'app/src/main/assets/chat-viewport-performance-patch.json'
VERSION = '0.2.1-rc.5'
PACKAGE = '@deepseek-ai/dsh-client-ui-chat'
MODULE = PACKAGE + '/lib/client.js'
SOURCE_SHA256 = '181f766f2fe12ad27eed4f1c0cd4609e8d2211d397890355eb476b6a9ee311c2'


def generate(modules):
    source_bytes = (modules / MODULE).read_bytes()
    if hashlib.sha256(source_bytes).hexdigest() != SOURCE_SHA256:
        raise ValueError('CHAT_VIEWPORT_OFFICIAL_SOURCE_CHANGED')
    package = json.loads((modules / PACKAGE / 'package.json').read_text(encoding='utf8'))
    lock = json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf8'))
    locked = lock['packages']['node_modules/' + PACKAGE]
    if package['name'] != PACKAGE or package['version'] != VERSION or locked['version'] != VERSION:
        raise ValueError('CHAT_VIEWPORT_PACKAGE_VERSION_CHANGED')
    before = '''\t\t\tanchor(key, identity = "position") {
\t\t\t\tif (this.elements === null) return null;
\t\t\t\tlet nodePart = null;'''
    after = '''\t\t\tanchor(key, identity = "position") {
\t\t\t\tif (this.elements === null) return null;
\t\t\t\t// DSHA_CHAT_ANCHOR_LOOKUP_V1: query the requested key without materializing every historical anchor.
\t\t\t\t// No retained DOM cache: hidden ancestors, split rows, replacement and prepending are read live.
\t\t\t\t// CSS parsers can normalize NUL or unpaired UTF-16 differently; keep their strict dataset fallback.
\t\t\t\tif (typeof key === "string" && !/[\\u0000\\uD800-\\uDFFF]/.test(key) && typeof CSS !== "undefined" && typeof CSS.escape === "function") {
\t\t\t\t\tconst escaped = CSS.escape(key);
\t\t\t\t\tconst visible = ":not([hidden]):not([hidden] *)";
\t\t\t\t\tconst exact = `[data-chat-anchor-key="${escaped}"]${visible}`;
\t\t\t\t\tconst node = `[data-chat-anchor-key][data-chat-node-key="${escaped}"]${visible}`;
\t\t\t\t\tconst list = this.elements.list;
\t\t\t\t\t// Node identity chooses the first visible match in DOM order; position identity prefers the exact part.
\t\t\t\t\treturn identity === "node" ? list.querySelector(`${exact},${node}`) : list.querySelector(exact) ?? list.querySelector(node);
\t\t\t\t}
\t\t\t\tlet nodePart = null;'''
    if source_bytes.decode('utf8').count(before) != 1:
        raise ValueError('CHAT_VIEWPORT_ANCHOR_CHANGED')
    return json.dumps(dict(version=1, dshVersion=VERSION, module=MODULE,
                          sourceSha256=SOURCE_SHA256, packageIntegrity=locked['integrity'],
                          purpose='Resolve live semantic anchors with native exact-key selectors; preserve visibility, split-node ordering, full history, scroll memory and fallback behavior.',
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
            raise ValueError('CHAT_VIEWPORT_PATCH_STALE')
    else:
        write_text(ASSET, expected)
    print('PASS locked chat viewport patch ' + ('matches' if args.check else 'generated'))


if __name__ == '__main__':
    main()
