#!/usr/bin/env python3
"""Generate opt-in, bounded local directory-prefix recipes from exact rc5 inputs."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'

PREFIX_HELPER = '''// DSHA_DIRECTORY_PREFIX_BOUNDED_V1
// Preserve the full listing's stable locale order while retaining at most the
// requested prefix. Full two-argument listDir callers retain their old path.
async function dshaDirectoryPrefixNames(target, signal, limit) {
	const heap = [];
	let sequence = 0;
	// POSIX readdir/scandir pre-sorts raw UTF-8 names before the stable locale
	// sort; opendir streams native order. Windows retains its enumeration order.
	const compare = (left, right) => left.name.localeCompare(right.name)
		|| (process.platform === "win32" ? left.sequence - right.sequence
			: Buffer.compare(Buffer.from(left.name), Buffer.from(right.name)));
	const directory = await opendir(target.targetKey, { encoding: "utf8", bufferSize: 32 });
	try {
		while (true) {
			throwIfAborted(signal, "list");
			const entry = await directory.read();
			throwIfAborted(signal, "list");
			if (entry === null) break;
			const candidate = { name: entry.name, sequence: sequence++ };
			if (heap.length < limit) {
				let child = heap.length;
				heap.push(candidate);
				while (child > 0) {
					const parent = Math.floor((child - 1) / 2);
					if (compare(heap[parent], candidate) >= 0) break;
					heap[child] = heap[parent]; child = parent;
				}
				heap[child] = candidate;
			} else if (compare(candidate, heap[0]) < 0) {
				let parent = 0;
				while (parent * 2 + 1 < heap.length) {
					let child = parent * 2 + 1;
					if (child + 1 < heap.length && compare(heap[child + 1], heap[child]) > 0) child++;
					if (compare(candidate, heap[child]) >= 0) break;
					heap[parent] = heap[child]; parent = child;
				}
				heap[parent] = candidate;
			}
		}
	} finally {
		await directory.close();
	}
	throwIfAborted(signal, "list");
	return heap.sort(compare);
}
'''


def definitions(raw):
    module = '@deepseek-ai/dsh-fs-local/lib/index.js'
    source = (raw / 'node_modules' / module).read_text(encoding='utf8')
    start = source.index('\tasync listDir(target, signal) {')
    end = source.index('\n\tasync writeText(', start)
    original = source[start:end]
    prefix = original.replace('async listDir(target, signal)', 'async dshaListDirPrefix(target, limit, signal)', 1)
    prefix = prefix.replace('\n\t\treturn (await listDirectory(',
        '\n\t\tif (!Number.isSafeInteger(limit) || limit < 1) throw new FsError("Directory prefix limit must be a positive safe integer", "FS_IO_ERROR");\n'
        '\t\t// Inspect the implementation, not Cordis\'s context-bound method wrapper.\n'
        '\t\t// A subclass or instance override may enforce a different read policy.\n'
        '\t\tconst ownList = Object.getOwnPropertyDescriptor(this, "listDir");\n'
        '\t\tconst listImplementation = ownList ? ownList.value : Object.getPrototypeOf(this).listDir;\n'
        '\t\tif (listImplementation !== LocalFileSystem.prototype.listDir) return (await this.listDir(target, signal)).slice(0, limit);\n'
        '\t\treturn (await listDirectory(', 1)
    prefix = prefix.replace('}, signal)).map(', '}, signal, limit)).map(', 1)
    local_patches = [
        ('import { TextDecoder, promisify } from "node:util";',
         'import { TextDecoder, promisify } from "node:util";\nimport { opendir } from "node:fs/promises";'),
        ('async function listDirectory(target, signal) {', PREFIX_HELPER + '\nasync function listDirectory(target, signal, prefixLimit) {'),
        ('\t\tentries = await readdir(target.targetKey, {\n\t\t\twithFileTypes: true,\n\t\t\tencoding: "utf8"\n\t\t});',
         '\t\tentries = prefixLimit === void 0 ? await readdir(target.targetKey, {\n\t\t\twithFileTypes: true,\n\t\t\tencoding: "utf8"\n\t\t}) : await dshaDirectoryPrefixNames(target, signal, prefixLimit);'),
        (original, original + '\n\t// Optional capability: callers explicitly request a sorted prefix, never a complete listing.\n' + prefix),
    ]
    api_patches = [
        ('\t\t\tconst children = await this.ctx.fs.listDir(target, signal);',
         '\t\t\t// DSHA_WORKSPACE_DIRECTORY_PREFIX_V1: one extra entry preserves the existing truncation flag.\n'
         '\t\t\t// Other providers keep their complete API; never parse opaque target identities here.\n'
         '\t\t\tconst fs = this.ctx.fs;\n'
         '\t\t\tconst children = typeof fs.dshaListDirPrefix === "function"\n'
         '\t\t\t\t? await fs.dshaListDirPrefix(target, this.config.maxEntries + 1, signal)\n'
         '\t\t\t\t: await fs.listDir(target, signal);'),
    ]
    watch_start = source.index('\tasync watch(target, changed, signal) {')
    watch_end = source.index('\n\tstatic Config = ', watch_start)
    watch_patches = [
        ('import { createReadStream, realpath } from "node:fs";',
         'import { createReadStream, realpath, watch as dshaWatchNative } from "node:fs";'),
        (source[watch_start:watch_end],
         '''	async watch(target, changed, signal) {
		signal.throwIfAborted();
		const path = resolve(this.processPath(target));
		const directory = (await this.stat(target, signal))?.type === "directory";
		signal.throwIfAborted();
		const root = directory ? path : dirname(path);
		const expected = basename(path);
		// DSHA_DIRECTORY_WATCH_BOUNDED_V1 / DSHA_FILE_WATCH_BOUNDED_V1:
		// one native watch, no complete parent-directory scan or child inventory.
		// File resources watch the canonical parent so replacement/recreation is
		// observed too; only clearly unrelated filenames are filtered out.
		{
			const closed = Promise.withResolvers();
			let stopped = false, failed = false;
			const watcher = dshaWatchNative(root, (_event, filename) => {
				if (stopped || failed) return;
				const name = typeof filename === "string" ? filename : Buffer.isBuffer(filename) ? filename.toString("utf8") : null;
				const uncertain = name === null || /[\\\\/\\uFFFD]/u.test(name)
					|| process.platform === "win32" && /[^\\x00-\\x7f]/u.test(name + expected);
				if (directory || uncertain || (process.platform === "win32" ? name.toLowerCase() === expected.toLowerCase() : name === expected)) changed();
			});
			const fail = (cause) => {
				if (stopped || failed) return;
				failed = true;
				try {
					changed(cause instanceof Error ? cause : new Error(String(cause)));
				} finally {
					// DSHA_WATCH_ERROR_TERMINAL_V1: Node closes its native handle
					// before emitting a fatal error, deliberately without a close event.
					// Also close still-live emitters; never await an event that cannot occur.
					try { watcher.close(); } finally { closed.resolve(); }
				}
			};
			watcher.on("error", fail);
			watcher.once("close", () => {
				closed.resolve();
				if (!stopped) fail(new FsError("File observation ended; reopen the file or directory, or refresh manually", "FS_IO_ERROR"));
			});
			const close = async () => {
				if (!stopped) { stopped = true; watcher.close(); }
				await closed.promise;
				watcher.removeListener("error", fail);
			};
			if (signal.aborted) { await close(); signal.throwIfAborted(); }
			return close;
		}
	}'''),
    ]
    return [
        ('local-directory-prefix-patch.json', module, local_patches,
         'Offer an explicit bounded sorted-prefix capability using opendir and a top-K heap; full listDir callers and sandbox inheritance remain unchanged.'),
        ('workspace-directory-prefix-patch.json', '@deepseek-ai/dsh-api-workspace-files/lib/index.js', api_patches,
         'Request maxEntries+1 through the optional provider prefix capability, preserving confinement, configured prefix ordering and the existing truncated UI flag; unknown providers retain their full listDir behavior.'),
        ('local-directory-watch-patch.json', module, watch_patches,
         'Use one native watcher for directory invalidations or canonical file-parent observation, avoiding complete parent scans while retaining replacement/recreation, conservative unnamed/Buffer/Windows notifications and awaited teardown.'),
    ]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw', required=True, type=Path)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    lock_bytes = (ROOT / 'tools/dsh-runtime/package-lock.json').read_bytes()
    lock = json.loads(lock_bytes)
    proof = json.loads((args.raw / 'dsha-test-runtime.json').read_text(encoding='utf8'))
    if proof['kind'] != 'raw' or proof['dshVersion'] != '0.2.1-rc.5' or proof['lockSha256'] != hashlib.sha256(lock_bytes).hexdigest():
        raise ValueError('DIRECTORY_PREFIX_RAW_LOCK')
    for name, module, patches, reason in definitions(args.raw):
        source = (args.raw / 'node_modules' / module).read_bytes()
        digest = hashlib.sha256(source).hexdigest()
        if proof['contentProof']['entries']['node_modules/' + module]['sha256'] != digest:
            raise ValueError('DIRECTORY_PREFIX_SOURCE_PROOF')
        changed = source.decode('utf8')
        for before, after in patches:
            if changed.count(before) != 1 or after in changed:
                raise ValueError('DIRECTORY_PREFIX_ANCHOR: ' + name)
            changed = changed.replace(before, after, 1)
        package = '/'.join(module.split('/')[:2])
        recipe = dict(version=1, dshVersion='0.2.1-rc.5', module=module, sourceSha256=digest,
                      packageIntegrity=lock['packages']['node_modules/' + package]['integrity'], reason=reason,
                      patches=[dict(before=before, after=after) for before, after in patches])
        content = (json.dumps(recipe, indent=2, ensure_ascii=False) + '\n').encode('utf8')
        if args.write:
            (ASSETS / name).write_bytes(content)
        elif (ASSETS / name).read_bytes() != content:
            raise ValueError('DIRECTORY_PREFIX_RECIPE_DRIFT: ' + name)
        print(name + ' ' + digest)


if __name__ == '__main__':
    main()
