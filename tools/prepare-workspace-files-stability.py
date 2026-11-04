#!/usr/bin/env python3
"""Generate exact rc5 file observation/list/preview lifetime recipes; never edit a runtime tree."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'


def recipes():
    host = [
        ('\t\t\tif (target === void 0 || aborted()) return;\n\t\t\tconst stat = async () => {',
         '\t\t\tif (target === void 0 || aborted()) return;\n'
         '\t\t\t// DSHA_WORKSPACE_CHANGE_FEED_BOUNDED_V1: observations invalidate current metadata.\n'
         '\t\t\tfollower.targetKey = target.targetKey;\n\t\t\tconst stat = async () => {'),
        ('\tqueue = new Deque();', '\ttargetKey;\n\tpending;'),
        ('\t\tif (this.closed) return;\n\t\tthis.queue.pushBack(observed);\n\t\tthis.wake?.();',
         '\t\tif (this.closed || observed[0].targetKey !== this.targetKey) return;\n'
         '\t\t// One pending invalidation is sufficient: the consumer reads the latest stat.\n'
         '\t\tthis.pending = observed;\n\t\tthis.wake?.();'),
        ('\t\tthis.closed = true;\n\t\tthis.closing =', '\t\tthis.closed = true;\n\t\tthis.pending = void 0;\n\t\tthis.closing ='),
        ('\t\t\tconst observed = this.queue.popFront();', '\t\t\tconst observed = this.pending;\n\t\t\tthis.pending = void 0;'),
    ]
    files = [
        ('\t\t\t\t\t\treturn result.ok ? result.value : void 0;\n\t\t\t\t\t});',
         '\t\t\t\t\t\treturn result.ok ? result.value : void 0;\n'
         '\t\t\t\t\t}, (error) => {\n'
         '\t\t\t\t\t\t// DSHA_FILES_LIST_REJECTION_V1: only the current listing may report failure.\n'
         '\t\t\t\t\t\tif (signal.aborted || generations.get(tabId)?.get(path) !== generation) return;\n'
         '\t\t\t\t\t\tactions.failed(tabId, path, new RemoteError("gateway/internal", error instanceof Error ? error.message : String(error), {}));\n'
         '\t\t\t\t\t});'),
    ]
    preview = [
        ('\t\t\t\t\t\treads.version = result.value.version;\n\t\t\t\t\t\tactions.page(tabId, result.value);\n\t\t\t\t\t});',
         '\t\t\t\t\t\treads.version = result.value.version;\n\t\t\t\t\t\tactions.page(tabId, result.value);\n'
         '\t\t\t\t\t}, (error) => {\n'
         '\t\t\t\t\t\t// DSHA_TEXT_PAGE_REJECTION_V1: match the complete-byte failure contract.\n'
         '\t\t\t\t\t\tif (signal.aborted || reads.generation !== generation) return;\n'
         '\t\t\t\t\t\tactions.failed(tabId, Object.assign(new Error(error instanceof Error ? error.message : String(error), { cause: error }), {\n'
         '\t\t\t\t\t\t\tname: "RemoteError", isDSHRemoteError: true, code: "gateway/internal", details: {}\n'
         '\t\t\t\t\t\t}));\n\t\t\t\t\t});'),
    ]
    return [
        ('workspace-change-feed-bounded-patch.json', '@deepseek-ai/dsh-api-workspace-files/lib/index.js', host,
         'Filter observations by the resolved target before enqueueing and coalesce current-metadata invalidations; retain readiness, cancellation and watcher failures.'),
        ('files-list-rejection-patch.json', '@deepseek-ai/dsh-client-ui-sidebar-files/lib/client.js', files,
         'Settle rejected directory reads into the existing failure state behind the same tab/path generation and cancellation fence; no replay.'),
        ('text-page-rejection-patch.json', '@deepseek-ai/dsh-client-ui-sidebar-documentpreview/lib/client.js', preview,
         'Settle rejected text-page reads exactly as complete-byte reads do, retaining generation/cancellation fencing and retry UI.'),
    ]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw', type=Path, required=True)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    lock_bytes = (ROOT / 'tools/dsh-runtime/package-lock.json').read_bytes()
    lock = json.loads(lock_bytes)
    proof = json.loads((args.raw / 'dsha-test-runtime.json').read_text(encoding='utf8'))
    if proof['kind'] != 'raw' or proof['dshVersion'] != '0.2.1-rc.5':
        raise ValueError('WORKSPACE_FILES_RAW_RC5_REQUIRED')
    if proof['lockSha256'] != hashlib.sha256(lock_bytes).hexdigest():
        raise ValueError('WORKSPACE_FILES_LOCK_PROOF')
    for name, module, patches, reason in recipes():
        source = (args.raw / 'node_modules' / module).read_bytes()
        digest = hashlib.sha256(source).hexdigest()
        if proof['contentProof']['entries']['node_modules/' + module]['sha256'] != digest:
            raise ValueError('WORKSPACE_FILES_SOURCE_PROOF: ' + module)
        package = '/'.join(module.split('/')[:2])
        if lock['packages']['node_modules/' + package]['version'] != '0.2.1-rc.5':
            raise ValueError('WORKSPACE_FILES_PACKAGE_VERSION')
        patched = source.decode('utf8')
        for before, after in patches:
            if patched.count(before) != 1 or after in patched:
                raise ValueError('WORKSPACE_FILES_ANCHOR: ' + name)
            patched = patched.replace(before, after, 1)
        recipe = dict(version=1, dshVersion='0.2.1-rc.5', module=module, sourceSha256=digest,
                      packageIntegrity=lock['packages']['node_modules/' + package]['integrity'], reason=reason,
                      patches=[dict(before=before, after=after) for before, after in patches])
        content = json.dumps(recipe, ensure_ascii=False, indent=2) + '\n'
        if args.write:
            (ASSETS / name).write_bytes(content.encode('utf8'))
        elif (ASSETS / name).read_text(encoding='utf8') != content:
            raise ValueError('WORKSPACE_FILES_RECIPE_DRIFT: ' + name)
        print(name + ' ' + digest)


if __name__ == '__main__':
    main()
