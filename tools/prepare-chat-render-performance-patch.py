#!/usr/bin/env python3
"""生成锁定 rc5 聊天轮次增量索引与稳定渲染行补丁。"""
import argparse
import hashlib
import json
from pathlib import Path
from source_text import matches_text, write_text

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / 'app/src/main/assets/chat-render-performance-patch.json'
VERSION = '0.2.1-rc.5'
PACKAGE = '@deepseek-ai/dsh-client-ui-chat'
MODULE = PACKAGE + '/lib/client.js'
SOURCE_SHA256 = '181f766f2fe12ad27eed4f1c0cd4609e8d2211d397890355eb476b6a9ee311c2'


def generate(modules):
    source_bytes = (modules / MODULE).read_bytes()
    if hashlib.sha256(source_bytes).hexdigest() != SOURCE_SHA256:
        raise ValueError('CHAT_RENDER_OFFICIAL_SOURCE_CHANGED')
    package = json.loads((modules / PACKAGE / 'package.json').read_text(encoding='utf8'))
    lock = json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf8'))
    locked = lock['packages']['node_modules/' + PACKAGE]
    if package['name'] != PACKAGE or package['version'] != VERSION or locked['version'] != VERSION:
        raise ValueError('CHAT_RENDER_PACKAGE_VERSION_CHANGED')
    source = source_bytes.decode('utf8')
    edits = []

    def patch(before, after):
        if source.count(before) != 1 or before == after:
            raise ValueError('CHAT_RENDER_ANCHOR_CHANGED: ' + before[:100])
        edits.append(dict(before=before, after=after))

    patch('''\t\tvar MutableTurnNavigationIndex = class {
\t\t\tcurrent = EMPTY_ITEMS;
\t\t\tbyTurn = /* @__PURE__ */ new Map();''', '''\t\tvar MutableTurnNavigationIndex = class {
\t\t\t// DSHA_CHAT_RENDER_INCREMENTAL_V1：流式尾部更新只读取受影响轮次。
\t\t\tcurrent = EMPTY_ITEMS;
\t\t\tbyTurn = /* @__PURE__ */ new Map();
\t\t\tpositions = /* @__PURE__ */ new Map();''')
    patch('''\t\t\t\tthis.byTurn = byTurn;
\t\t\t\tif (!(next.length === this.current.length && next.every((item, index) => item === this.current[index]))) this.current = next;''', '''\t\t\t\tthis.byTurn = byTurn;
\t\t\t\tthis.positions.clear();
\t\t\t\tfor (let index = 0; index < next.length; index++) this.positions.set(next[index].turn, index);
\t\t\t\tif (!(next.length === this.current.length && next.every((item, index) => item === this.current[index]))) this.current = next;''')
    patch('''\t\t\ttouch(turns, locations, nodes) {
\t\t\t\tif (turns.size === 0) return;
\t\t\t\tconst next = this.current.map((item) => {
\t\t\t\t\tif (!turns.has(item.turn)) return item;
\t\t\t\t\tconst derived = turnNavigationItem(item.turn, locations, nodes);
\t\t\t\t\tif (derived === void 0 || sameTurnNavigationItem(item, derived)) return item;
\t\t\t\t\tthis.byTurn.set(item.turn, derived);
\t\t\t\t\treturn derived;
\t\t\t\t});
\t\t\t\tif (next.some((item, index) => item !== this.current[index])) this.current = next;
\t\t\t}''', '''\t\t\ttouch(turns, locations, nodes) {
\t\t\t\tlet next;
\t\t\t\tfor (const turn of turns) {
\t\t\t\t\tconst index = this.positions.get(turn);
\t\t\t\t\tif (index === void 0) continue;
\t\t\t\t\tconst item = this.byTurn.get(turn);
\t\t\t\t\tconst derived = turnNavigationItem(turn, locations, nodes);
\t\t\t\t\tif (derived === void 0 || sameTurnNavigationItem(item, derived)) continue;
\t\t\t\t\t// 仍发布不可变数组；摘要没有变化时不扫描或复制全部历史。
\t\t\t\t\tnext ??= this.current.slice();
\t\t\t\t\tnext[index] = derived;
\t\t\t\t\tthis.byTurn.set(turn, derived);
\t\t\t\t}
\t\t\t\tif (next !== void 0) this.current = next;
\t\t\t}''')

    # 只缓存 React 元素；行自身的节点、分组、展示策略订阅继续独立更新。
    props = ['nodeStore', 'useChatNode', 'useChatNodeBottom', 'useChatNodeProcess', 'usePresentation',
             'useStore', 'actions', 'renderSlot', 't', 'useGroupAction', 'deferCollapse',
             'cwd', 'openFile', 'inspectCall', 'forkAt', 'loadImage', 'fileMentions']
    fields = ''.join('\t\t\t\t' + name + ',\n' for name in props[:-1]) + '\t\t\t\tfileMentions\n'
    before = '\t\t\tconst seatProps = {\n' + fields + '\t\t\t};'
    deps = ', '.join(props)
    after = '\t\t\t// DSHA_CHAT_FLOW_ROWS_V1：父级状态变化复用稳定行，不改变 DOM 层级或键。\n'
    after += '\t\t\tconst seatProps = (0, react.useMemo)(() => ({\n' + fields + '\t\t\t}), [' + deps + ']);'
    patch(before, after)
    before = source[source.index('\t\t\tconst rows = entries.map((entry) => {'):source.index('\t\t\tconst pendingRows = pendingInputs.map')]
    after = before.replace('const rows = entries.map', 'let rows = (0, react.useMemo)(() => entries.map', 1)
    after = after[:-len('\t\t\t});\n')] + '\t\t\t}), [entries, seatProps, useChatGroup, useGroupHeaderAction]);\n'
    patch(before, after)
    patch('''\t\t\t\tif (index !== -1) rows.splice(rows.length - 1, 0, ...pendingRows.splice(index, 1));''', '''\t\t\t\tif (index !== -1) {
\t\t\t\t\t// 不修改已缓存的行数组，下一次提交仍以真实历史顺序为基准。
\t\t\t\t\trows = rows.slice();
\t\t\t\t\trows.splice(rows.length - 1, 0, ...pendingRows.splice(index, 1));
\t\t\t\t}''')
    return json.dumps(dict(version=1, dshVersion=VERSION, module=MODULE,
                          sourceSha256=SOURCE_SHA256, packageIntegrity=locked['integrity'],
                          purpose='Index changed chat turns and retain stable React row elements; preserve complete history, subscriptions, grouping, keyed DOM, pending input ordering and scroll behavior.',
                          patches=edits), ensure_ascii=False, indent=2) + '\n'


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
            raise ValueError('CHAT_RENDER_PATCH_STALE')
    else:
        write_text(ASSET, expected)
    print('PASS locked chat render patch ' + ('matches' if args.check else 'generated'))


if __name__ == '__main__':
    main()
