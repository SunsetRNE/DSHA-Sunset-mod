#!/usr/bin/env python3
"""Generate a hash-locked rc5 UI Session status batching patch."""
import argparse
import hashlib
import json
from pathlib import Path
from source_text import matches_text, write_text

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / 'app/src/main/assets/session-status-scale-patch.json'
VERSION = '0.2.1-rc.5'
PACKAGE = '@deepseek-ai/dsh-client-ui-session'
MODULE = PACKAGE + '/lib/client.js'
SOURCE_SHA256 = 'c2aee5c41e606b62f845ff65bb9db8874a1ea4e21dfec8551ca70a9e36767311'


def generate(modules):
    data = (modules / MODULE).read_bytes()
    if hashlib.sha256(data).hexdigest() != SOURCE_SHA256:
        raise ValueError('SESSION_STATUS_SCALE_OFFICIAL_SOURCE_CHANGED')
    package = json.loads((modules / PACKAGE / 'package.json').read_text(encoding='utf8'))
    lock = json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf8'))
    locked = lock['packages']['node_modules/' + PACKAGE]
    if package['name'] != PACKAGE or package['version'] != VERSION or locked['version'] != VERSION:
        raise ValueError('SESSION_STATUS_SCALE_PACKAGE_VERSION_CHANGED')
    source = data.decode('utf8')
    edits = []

    def patch(before, after):
        nonlocal source
        if source.count(before) != 1 or before == after:
            raise ValueError('SESSION_STATUS_SCALE_ANCHOR_CHANGED: ' + before[:80])
        edits.append(dict(before=before, after=after))
        source = source.replace(before, after, 1)

    patch('''\t\t\tstatusSnapshot = /* @__PURE__ */ new Map();''', '''\t\t\tstatusSnapshot = /* @__PURE__ */ new Map();
\t\t\tstatusListById;''')
    patch('''\t\t\tobserveRunning(sessionId, running) {
\t\t\t\tconst previous = this.running.get(sessionId);
\t\t\t\tconst beforeBaseline''', '''\t\t\tobserveRunning(sessionId, running, publish = true) {
\t\t\t\t// DSHA_SESSION_STATUS_SCALE_V1: one projection per authoritative list update.
\t\t\t\tconst previous = this.running.get(sessionId);
\t\t\t\tif (previous === running && (!running || !this.completionUnread.has(sessionId)) && this.statusListById === this.sessions.list.getSnapshot().byId) return;
\t\t\t\tconst beforeBaseline''')
    patch('''\t\t\t\telse if ((previous === true || previous === void 0 && beforeBaseline) && !this.isMain(sessionId)) this.completionUnread.add(sessionId);
\t\t\t\tthis.publishStatus();''', '''\t\t\t\telse if ((previous === true || previous === void 0 && beforeBaseline) && !this.isMain(sessionId)) this.completionUnread.add(sessionId);
\t\t\t\tif (publish) this.publishStatus(sessionId);''')
    patch('else if (previous !== row.running) this.observeRunning(id, row.running);',
          'else if (previous !== row.running) this.observeRunning(id, row.running, false);')
    patch('''\t\t\tpublishStatus() {
\t\t\t\tconst ids''', '''\t\t\tpublishStatus(changedId) {
\t\t\t\t// A remote status event changes one entry. Retain the public immutable Map
\t\t\t\t// contract and other row identities; never mutate an earlier snapshot.
\t\t\t\tif (changedId !== void 0 && this.statusSnapshot.has(changedId) && this.statusListById === this.sessions.list.getSnapshot().byId) {
\t\t\t\t\tconst previous = this.statusSnapshot.get(changedId);
\t\t\t\t\tconst status = this.statusValue(changedId, previous);
\t\t\t\t\tif (status === previous) return;
\t\t\t\t\tconst next = new Map(this.statusSnapshot);
\t\t\t\t\tnext.set(changedId, status);
\t\t\t\t\tthis.statusSnapshot = next;
\t\t\t\t\t(0, _deepseek_ai_dsh_client_store.notifySubscribers)(this.statusListeners, "[ui-session] Session status");
\t\t\t\t\treturn;
\t\t\t\t}
\t\t\t\tconst ids''')
    patch('''\t\t\t\tconst ids = new Set([
\t\t\t\t\t...Object.keys(this.sessions.list.getSnapshot().byId),''', '''\t\t\t\tconst byId = this.sessions.list.getSnapshot().byId;
\t\t\t\tthis.statusListById = byId;
\t\t\t\tconst ids = new Set([
\t\t\t\t\t...Object.keys(byId),''')
    patch('''\t\t\t\tfor (const id of ids) next.set(id, {
\t\t\t\t\trunning: this.running.get(id),
\t\t\t\t\tpendingInteraction: this.pendingSnapshot.get(id),
\t\t\t\t\tcompletionUnread: this.completionUnread.has(id)
\t\t\t\t});
\t\t\t\tif (sameSessionStatus(this.statusSnapshot, next)) return;''', '''\t\t\t\tlet changed = ids.size !== this.statusSnapshot.size;
\t\t\t\tfor (const id of ids) {
\t\t\t\t\tconst previous = this.statusSnapshot.get(id);
\t\t\t\t\tconst status = this.statusValue(id, previous);
\t\t\t\t\tnext.set(id, status);
\t\t\t\t\tif (status !== previous) changed = true;
\t\t\t\t}
\t\t\t\tif (!changed) return;''')
    patch('''\t\t\tcreateMaterializedBinding(owner) {''', '''\t\t\tstatusValue(id, previous) {
\t\t\t\tconst running = this.running.get(id);
\t\t\t\tconst pendingInteraction = this.pendingSnapshot.get(id);
\t\t\t\tconst completionUnread = this.completionUnread.has(id);
\t\t\t\tif (previous !== void 0 && previous.running === running && previous.pendingInteraction === pendingInteraction && previous.completionUnread === completionUnread) return previous;
\t\t\t\treturn { running, pendingInteraction, completionUnread };
\t\t\t}
\t\t\tcreateMaterializedBinding(owner) {''')
    return json.dumps(dict(version=1, dshVersion=VERSION, module=MODULE,
        sourceSha256=SOURCE_SHA256, packageIntegrity=locked['integrity'],
        purpose='Batch list status reconciliation; skip duplicate remote states; preserve unchanged status entry references and immutable snapshots. Session membership, completion reminders, pending interactions, and persisted data remain authoritative.',
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
            raise ValueError('SESSION_STATUS_SCALE_PATCH_STALE')
    else:
        write_text(ASSET, expected)
    print('PASS locked session status scale patch ' + ('matches' if args.check else 'generated'))


if __name__ == '__main__':
    main()
