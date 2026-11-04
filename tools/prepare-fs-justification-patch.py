#!/usr/bin/env python3
"""Generate the narrow, hash-locked rc5 filesystem escalation-argument repair."""
import argparse
import hashlib
import json
from pathlib import Path
from source_text import matches_text, write_text

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / 'app/src/main/assets/fs-justification-patch.json'
VERSION = '0.2.1-rc.5'
PACKAGE = '@deepseek-ai/dsh-tool-fs'
MODULE = PACKAGE + '/lib/index.js'
SOURCE_SHA256 = 'b11d3fa50b9042019a91cc38a6abe35d74ed651374278f95e60b42734e079df1'


def generate(modules):
    source_bytes = (modules / MODULE).read_bytes()
    if hashlib.sha256(source_bytes).hexdigest() != SOURCE_SHA256:
        raise ValueError('FS_JUSTIFICATION_OFFICIAL_SOURCE_CHANGED')
    package = json.loads((modules / PACKAGE / 'package.json').read_text(encoding='utf8'))
    lock = json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf8'))
    locked = lock['packages']['node_modules/' + PACKAGE]
    if package['name'] != PACKAGE or package['version'] != VERSION or locked['version'] != VERSION:
        raise ValueError('FS_JUSTIFICATION_PACKAGE_VERSION_CHANGED')
    source = source_bytes.decode('utf8')
    edits = []

    def patch(before, after):
        if source.count(before) != 1 or before == after:
            raise ValueError('FS_JUSTIFICATION_ANCHOR_CHANGED')
        edits.append(dict(before=before, after=after))

    # The pinned tools schema DSL does not support minLength/pattern or dependent
    # required fields. Keep its supported string schema, and make the optional
    # escalation contract explicit without inventing a user-provided reason.
    patch('description: sandboxPermissionsDescription("operation")',
          'description: sandboxPermissionsDescription("operation") + " Omit sandbox_permissions and justification for an ordinary operation using the current permissions. Do not fill them with the current mode and an empty reason."')
    patch('description: "Required with sandbox_permissions: one sentence for the user explaining why this exact file operation needs the wider access. Use the language of the user’s current request."',
          'description: "Required for a strictly wider sandbox_permissions request: a non-empty sentence explaining why this exact file operation needs the wider access. Use the language of the user’s current request. Omit both escalation fields when no wider access is needed; never send an empty reason for an escalation."')
    patch('* directory, or the deployment fallback when absent. Validates the escalation argument\n\t* pairing first.',
          '* directory, or the deployment fallback when absent. Normalize only empty\n\t* non-escalating placeholders; validate every actual escalation before execution.')
    patch('''\tasync resolvePolicy(toolName, args, exec) {
\t\tvalidateEscalationArgs(args.sandbox_permissions, args.justification);
\t\tconst standingPolicy = this.policy?.resolve({ ...exec.agent ? { session: exec.agent.session } : {} });''',
          '''\tasync resolvePolicy(toolName, args, exec) {
\t\tconst standingPolicy = this.policy?.resolve({ ...exec.agent ? { session: exec.agent.session } : {} });
\t\t// DSHA_FS_JUSTIFICATION_V1: a blank optional placeholder is not an escalation.
\t\t// Read the effective policy for THIS call, never the composition's default.
\t\t// The original filesystem fence, observation guards and approval flow remain.
\t\tconst noReason = args.justification === void 0 || typeof args.justification === "string" && args.justification.trim().length === 0;
\t\tconst noWidening = args.sandbox_permissions === void 0 || this.escalationModes.includes(args.sandbox_permissions) && args.sandbox_permissions === standingPolicy?.mode;
\t\tif (noReason && noWidening) return standingPolicy;
\t\tvalidateEscalationArgs(args.sandbox_permissions, args.justification);''')
    recipe = dict(version=1, dshVersion=VERSION, module=MODULE,
                  sourceSha256=SOURCE_SHA256, packageIntegrity=locked['integrity'],
                  purpose='Treat absent/blank optional reasons only as non-escalating placeholders when no mode is requested or it exactly matches the live standing policy. Preserve all wider-mode validation, approval, filesystem guards and single execution.',
                  patches=edits)
    return json.dumps(recipe, ensure_ascii=False, indent=2) + '\n'


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
            raise ValueError('FS_JUSTIFICATION_PATCH_STALE')
    else:
        write_text(ASSET, expected)
    print('PASS locked filesystem justification patch ' + ('matches' if args.check else 'generated'))


if __name__ == '__main__':
    main()
