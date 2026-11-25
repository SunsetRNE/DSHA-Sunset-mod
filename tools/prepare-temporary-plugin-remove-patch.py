#!/usr/bin/env python3
"""Generate pinned dynamic Cordis removal and late-inventory fences."""
import argparse
import hashlib
import json
from pathlib import Path
from source_text import matches_text, write_text

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
VERSION = '0.2.2-rc.5'
SOURCES = {
    'host': ('@deepseek-ai/dsh-cordis-host-runner/lib/index.js', '99720110b1cfa96fd102243004d4f1166750cd301ee5e3cd3dd773a9cd5b1d6d'),
    'ui': ('@deepseek-ai/dsh-client-ui-cordis/lib/client.js', '2de0e6f09d0e34b847ac3ae29cbbb59c657396c07cfcd8f373cbed580fbd052e'),
}


def generate(modules):
    lock = json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf8'))
    output = {}
    for kind, (module, digest) in SOURCES.items():
        package = module.split('/lib/')[0]
        data = (modules / module).read_bytes()
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError('TEMPORARY_PLUGIN_SOURCE_CHANGED: ' + module)
        manifest = json.loads((modules / package / 'package.json').read_text(encoding='utf8'))
        locked = lock['packages']['node_modules/' + package]
        if manifest['name'] != package or manifest['version'] != VERSION or locked['version'] != VERSION:
            raise ValueError('TEMPORARY_PLUGIN_VERSION_CHANGED')
        source = data.decode('utf8')
        patches = []

        def edit(before, after):
            nonlocal source
            if source.count(before) != 1 or before == after:
                raise ValueError('TEMPORARY_PLUGIN_ANCHOR: ' + before[:100])
            source = source.replace(before, after, 1)
            patches.append(dict(before=before, after=after))

        if kind == 'host':
            edit('\t\tstarting = /* @__PURE__ */ new Map();', '''\t\tstarting = /* @__PURE__ */ new Map();
\t\tremovals = /* @__PURE__ */ new Map();''')
            edit('''\t\t\tconst wasRunning = plugin.run !== void 0;
\t\t\tthis.cancelPending(pluginId, `dynamic plugin "${pluginId}" was removed before approval`);
\t\t\tif (plugin.run !== void 0) await this.retract(plugin);
\t\t\tthis.registry.delete(pluginId);
\t\t\treturn {
\t\t\t\tok: true,
\t\t\t\twasRunning
\t\t\t};''', '''\t\t\t// DSHA_TEMPORARY_PLUGIN_REMOVE_V1: retire before any await. A late
\t\t\t// activation must unwind its own effects before removal can succeed.
\t\t\tconst pendingRemoval = this.removals.get(plugin);
\t\t\tif (pendingRemoval !== void 0) return pendingRemoval;
\t\t\tplugin.dshaRemoving = true;
\t\t\tconst wasRunning = plugin.run !== void 0;
\t\t\tconst removal = (async () => {
\t\t\t\tthis.cancelPending(pluginId, `dynamic plugin "${pluginId}" was removed before approval`);
\t\t\t\tawait this.starting.get(pluginId);
\t\t\t\tif (plugin.run !== void 0) await this.retract(plugin);
\t\t\t\tif (this.registry.get(pluginId) === plugin) this.registry.delete(pluginId);
\t\t\t\treturn { ok: true, wasRunning };
\t\t\t})();
\t\t\tthis.removals.set(plugin, removal);
\t\t\ttry { return await removal; }
\t\t\tfinally { this.removals.delete(plugin); }
\t\t\t// On an unconfirmed cleanup retain the record and retirement fence.
\t\t\t// A later explicit removal may retry cleanup, never activation.''')
            edit('''\t\t\tconst definition = plugin.packages.get(packageId);
\t\t\tif (definition === void 0) return {''', '''\t\t\tif (plugin.dshaRemoving || plugin.run?.dshaRetracting) return {
\t\t\t\tok: false,
\t\t\t\tresponse: { ok: false, reason: "transition-in-flight", message: `dynamic plugin "${pluginId}" is being removed` }
\t\t\t};
\t\t\tconst definition = plugin.packages.get(packageId);
\t\t\tif (definition === void 0) return {''')
            edit('''\t\t\tconst { plugin, definition, mode } = plan;
\t\t\tif (allowActiveAttach''', '''\t\t\tconst { plugin, definition, mode } = plan;
\t\t\tif (plugin.dshaRemoving || this.registry.get(plugin.pluginId) !== plugin) return {
\t\t\t\tok: false, message: "the dynamic plugin was removed before activation"
\t\t\t};
\t\t\tif (allowActiveAttach''')
            edit('''\t\t\tplugin.run = run;
\t\t\tthis.ctx.emit("cordis/dynamic-package", {''', '''\t\t\tplugin.run = run;
\t\t\tif (plugin.dshaRemoving || this.registry.get(plugin.pluginId) !== plugin) {
\t\t\t\tawait this.retract(plugin);
\t\t\t\treturn { ok: false, message: "the dynamic plugin was removed during activation" };
\t\t\t}
\t\t\tthis.ctx.emit("cordis/dynamic-package", {''')
            edit('''\t\t\tif (plugin === void 0 || plugin.run === void 0) return {''',
                 '''\t\t\tif (plugin === void 0 || plugin.dshaRemoving || plugin.run === void 0 || plugin.run.dshaRetracting) return {''')
            edit('''\t\t\tdelete plugin.run;
\t\t\tfor (const dispose of run.handlerDisposers.splice(0)) dispose();
\t\t\tif (run.fiber !== void 0) await run.fiber.dispose();
\t\t\tthis.ctx.emit("cordis/dynamic-retract", {''', '''\t\t\t// Keep the exact run reachable until its cleanup has completed.
\t\t\t// A rejected cleanup is not proof that the plugin stopped. Invocation
\t\t\t// and fresh activation remain fenced until a later explicit cleanup.
\t\t\trun.dshaRetracting = true;
\t\t\tfor (const dispose of [...run.handlerDisposers]) dispose();
\t\t\tif (run.fiber !== void 0) await run.fiber.dispose();
\t\t\trun.handlerDisposers.length = 0;
\t\t\tif (plugin.run === run) delete plugin.run;
\t\t\tthis.ctx.emit("cordis/dynamic-retract", {''')
        else:
            edit('retire:pluginId=>{const removed', """retire:pluginId=>{
    // DSHA_TEMPORARY_PLUGIN_UI_REMOVE_V1: an inventory read issued
    // before the acknowledged removal cannot restore its old row.
    generation += 1;
    inFlight = void 0;
    const removed""")
            remove = '(0,react_jsx_runtime.jsx)(RowAction,{label:t(`action.remove`),'
            edit('awaiting===void 0&&listed!==void 0&&' + remove,
                 '/* Explicit removal also cancels a pending approval. */ listed!==void 0&&' + remove)
            edit('"data-cordis-remove":pluginId,disabled:busy,',
                 '"data-cordis-remove":pluginId,disabled:pending.has(pluginId),')
        output['temporary-plugin-' + kind + '-remove-patch.json'] = json.dumps(dict(
            version=1, dshVersion=VERSION, module=module, sourceSha256=digest,
            packageIntegrity=locked['integrity'], targetSha256=hashlib.sha256(source.encode('utf8')).hexdigest(),
            reason='Preserve explicit dynamic plugin removal through asynchronous activation and stale inventory responses. Retain failed cleanup evidence; never replay activation or change persistent plugin packages.',
            patches=patches), ensure_ascii=False, indent=2) + '\n'
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--modules', type=Path, required=True)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    for name, content in generate(args.modules).items():
        if args.check:
            if not matches_text(ASSETS / name, content):
                raise ValueError('TEMPORARY_PLUGIN_RECIPE_DRIFT: ' + name)
        else:
            write_text(ASSETS / name, content)
        print(name)


if __name__ == '__main__':
    main()
