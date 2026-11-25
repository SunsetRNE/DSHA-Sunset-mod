#!/usr/bin/env python3
"""Keep the actual loopback Connection independent of browser WAN availability."""
import argparse
import hashlib
import json
from pathlib import Path

from source_text import matches_text, write_text

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / 'app/src/main/assets/literal-loopback-online-patch.json'
VERSION = '0.2.2-rc.5'
PACKAGE = '@deepseek-ai/dsh-client-connection'
MODULE = PACKAGE + '/lib/client.browser.js'
SOURCE_SHA256 = '5f6107953fa764c80b6c06aa161be318ffa0b71e565ac81536fc4267d5d62412'


def generate(modules):
    data = (modules / MODULE).read_bytes()
    if hashlib.sha256(data).hexdigest() != SOURCE_SHA256:
        raise ValueError('LOOPBACK_ONLINE_SOURCE_CHANGED')
    package = json.loads((modules / PACKAGE / 'package.json').read_text(encoding='utf8'))
    lock = json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf8'))
    locked = lock['packages']['node_modules/' + PACKAGE]
    if package['name'] != PACKAGE or package['version'] != VERSION or locked['version'] != VERSION:
        raise ValueError('LOOPBACK_ONLINE_VERSION_CHANGED')
    source = data.decode('utf8')
    patches = []

    def edit(before, after):
        nonlocal source
        if source.count(before) != 1 or before == after:
            raise ValueError('LOOPBACK_ONLINE_ANCHOR_CHANGED')
        source = source.replace(before, after, 1)
        patches.append(dict(before=before, after=after))

    edit('function watchBrowserNetwork(controller){', '''
// DSHA_LITERAL_LOOPBACK_ONLINE_V1: navigator.onLine describes external
// network availability, not the default socket's numeric loopback route.
// Admit only the exact canonical numeric authority used by that socket;
// never change navigator, authentication, the ready stream or retry limits.
function dshaUsesLiteralLoopbackSocket(transport) {
\ttry {
\t\tif (transport?.createWebSocket !== void 0 || transport?.openStream !== void 0 || transport?.rpc?.open !== void 0) return false;
\t\tconst base = transport?.streamBaseUrl ?? (typeof document === "undefined" ? void 0 : document.baseURI);
\t\tif (typeof base !== "string") return false;
\t\tconst target = new URL("api/remote.mux", base);
\t\tif ((target.protocol !== "http:" && target.protocol !== "https:") || target.username || target.password) return false;
\t\tconst authority = /^https?:\\/\\/(\\[[0-9a-f:.]+\\]|[^\\/?#:]+)(?::[0-9]+)?(?:[\\/?#]|$)/i.exec(base);
\t\tif (!authority || authority[1].toLowerCase() !== target.hostname.toLowerCase()) return false;
\t\tif (target.hostname === "[::1]") return true;
\t\tconst parts = target.hostname.split(".");
\t\treturn parts.length === 4 && parts[0] === "127" && parts.every((part) => /^[0-9]{1,3}$/.test(part) && String(Number(part)) === part && Number(part) <= 255);
\t} catch {
\t\treturn false;
\t}
}
function watchBrowserNetwork(controller,transport){''')
    edit('const offline=()=>{controller.setNetworkAvailable(false)};controller.setNetworkAvailable(initiallyAvailable);',
         'const offline=()=>{controller.setNetworkAvailable(dshaUsesLiteralLoopbackSocket(transport))};'
         'controller.setNetworkAvailable(initiallyAvailable||dshaUsesLiteralLoopbackSocket(transport));')
    edit('stopNetworkWatch:watchBrowserNetwork(controller)',
         'stopNetworkWatch:watchBrowserNetwork(controller,transport)')
    return json.dumps(dict(version=1, dshVersion=VERSION, module=MODULE,
                          sourceSha256=SOURCE_SHA256, packageIntegrity=locked['integrity'],
                          purpose='Do not gate an actual default numeric loopback socket on the browser WAN hint; keep remote, custom transports and real handshake/nonce/home checks unchanged.',
                          patches=patches), ensure_ascii=False, indent=2) + '\n'


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
            raise ValueError('LOOPBACK_ONLINE_PATCH_STALE')
    else:
        write_text(ASSET, expected)
    print('PASS locked literal loopback online patch ' + ('matches' if args.check else 'generated'))


if __name__ == '__main__':
    main()
