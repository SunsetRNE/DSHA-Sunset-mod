#!/usr/bin/env python3
"""生成锁定 rc5 聊天轮次增量索引与稳定渲染行补丁。"""
import argparse
import hashlib
import json
from pathlib import Path
from source_text import matches_text, write_text

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / 'app/src/main/assets/chat-render-performance-patch.json'
VERSION = '0.2.2-rc.5'
PACKAGE = '@deepseek-ai/dsh-client-ui-chat'
MODULE = PACKAGE + '/lib/client.js'
SOURCE_SHA256 = 'eef53e82b8cd584d2f0e543a4a93395f9d90df3bba1218891bd99d07d788de5c'


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

    patch('var MutableTurnNavigationIndex=class{current=EMPTY_ITEMS;byTurn=new Map;', '''var MutableTurnNavigationIndex=class{
        // DSHA_CHAT_RENDER_INCREMENTAL_V1：流式尾部更新只读取受影响轮次。
        current = EMPTY_ITEMS;
        byTurn = new Map();
        positions = new Map();
    ''')
    patch('this.byTurn=byTurn;if(!(next.length===this.current.length&&next.every((item,index)=>item===this.current[index])))this.current=next',
          '''this.byTurn = byTurn;
        this.positions.clear();
        for (let index = 0; index < next.length; index++) this.positions.set(next[index].turn, index);
        if (!(next.length === this.current.length && next.every((item, index) => item === this.current[index]))) this.current = next;
    ''')
    patch('touch(turns,locations,nodes){if(turns.size===0)return;const next=this.current.map(item=>{if(!turns.has(item.turn))return item;const derived=turnNavigationItem(item.turn,locations,nodes);if(derived===void 0||sameTurnNavigationItem(item,derived))return item;this.byTurn.set(item.turn,derived);return derived});if(next.some((item,index)=>item!==this.current[index]))this.current=next}',
          '''touch(turns, locations, nodes) {
        let next;
        for (const turn of turns) {
            const index = this.positions.get(turn);
            if (index === void 0) continue;
            const item = this.byTurn.get(turn);
            const derived = turnNavigationItem(turn, locations, nodes);
            if (derived === void 0 || sameTurnNavigationItem(item, derived)) continue;
            // Keep old published snapshots immutable.
            next ??= this.current.slice();
            next[index] = derived;
            this.byTurn.set(turn, derived);
        }
        if (next !== void 0) this.current = next;
    }''')

    # 只缓存 React 元素；行自身的节点、分组、展示策略订阅继续独立更新。
    props = ['nodeStore', 'useChatNode', 'useChatNodeBottom', 'useChatNodeProcess', 'usePresentation',
             'useStore', 'actions', 'renderSlot', 't', 'useGroupAction', 'deferCollapse',
             'cwd', 'openFile', 'inspectCall', 'forkAt', 'loadImage']
    patch('const seatProps={' + ','.join(props) + '};',
          '\n// DSHA_CHAT_FLOW_ROWS_V1: keep stable element identities and the original row subscriptions.\n'
          'const seatProps = (0, react.useMemo)(() => ({' + ', '.join(props) + '}), [' + ', '.join(props) + ']);\n')
    start = 'const rows=entries.map(entry=>{'
    end = 'const firstTranscript=pendingInputs.findIndex(isTranscriptEcho);'
    before = source[source.index(start):source.index(end)]
    if not before.endswith('});'):
        raise ValueError('CHAT_RENDER_ROWS_END_CHANGED')
    after = before.replace(start, 'let rows = (0, react.useMemo)(() => entries.map(entry=>{', 1)
    after = after[:-3] + '}), [entries, seatProps, useChatGroup, useGroupHeaderAction]);'
    patch(before, after)
    insertion = 'rows.splice(rows.length-1,0,...leadingRows.splice(0));'
    patch(insertion, '''{
        // Keep the rc.5 leading/transcript-echo order without mutating memoized rows.
        rows = rows.slice();
        rows.splice(rows.length - 1, 0, ...leadingRows.splice(0));
    }''')
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
