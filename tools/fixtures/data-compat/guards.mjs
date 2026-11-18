import assert from 'node:assert/strict';
import { lstat, realpath } from 'node:fs/promises';
import { basename, dirname, join, resolve, sep } from 'node:path';

export const beneath = (path, root) => path === root || path.startsWith(root.endsWith(sep) ? root : root + sep);
export const processPassed = outcome => outcome.status === 0 && outcome.signal === null && !outcome.error;

export async function createPathGuard(protectedPaths, rootfs = '') {
  const prefix = rootfs.replace(/\/$/, '');
  const normalize = value => {
    const absolute = resolve(value);
    if (prefix && absolute === prefix) return sep;
    return prefix && absolute.startsWith(prefix + sep) ? absolute.slice(prefix.length) : absolute;
  };
  async function canonical(value) {
    let current = resolve(value);
    const suffix = [];
    for (;;) {
      try {
        return normalize(join(await realpath(current), ...suffix));
      } catch (error) {
        if (error.code !== 'ENOENT') throw error;
        // A dangling link is not a new directory spelling and must not be created through.
        const node = await lstat(current).catch(failure => {
          if (failure.code !== 'ENOENT') throw failure;
          return undefined;
        });
        assert(!node?.isSymbolicLink(), 'dangling path alias: ' + value);
        const parent = dirname(current);
        assert(parent !== current, 'path has no existing ancestor: ' + value);
        suffix.unshift(basename(current));
        current = parent;
      }
    }
  }
  const roots = new Set();
  for (const value of protectedPaths.filter(Boolean)) {
    roots.add(normalize(value));
    roots.add(await canonical(value));
  }
  return async value => {
    const lexical = normalize(value);
    assert(![...roots].some(root => beneath(lexical, root)), 'refusing installed runtime or live profile path: ' + value);
    const actual = await canonical(value);
    assert(![...roots].some(root => beneath(actual, root)), 'refusing resolved live profile path: ' + value);
    // Canonical return values also make distinct-root and work-dir overlap checks alias-safe.
    return actual;
  };
}
