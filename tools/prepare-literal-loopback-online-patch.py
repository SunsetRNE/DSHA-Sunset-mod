#!/usr/bin/env python3
"""Keep the actual loopback Connection independent of browser WAN availability."""
import argparse
import hashlib
import json
from pathlib import Path

from source_text import matches_text, write_text

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / 'app/src/main/assets/literal-loopback-online-patch.json'
VERSION = '0.2.1-rc.5'
PACKAGE = '@deepseek-ai/dsh-client-connection'
MODULE = PACKAGE + '/lib/client.browser.js'
SOURCE_SHA256 = 'dfc3544eb894c74b340a4c4c63a8c125fd2e983df9efed5885712574af994e62'


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

    edit('''\t\tfunction watchBrowserNetwork(controller) {
\t\t\tconst browser = globalThis.window;''', '''\t\t// DSHA_LITERAL_LOOPBACK_ONLINE_V1: navigator.onLine describes external
\t\t// network availability, not the default socket's numeric loopback route.
\t\t// Admit only the exact canonical numeric authority used by that socket;
\t\t// never change navigator, authentication, the ready stream or retry limits.
\t\tfunction dshaUsesLiteralLoopbackSocket(transport) {
\t\t\ttry {
\t\t\t\tif (transport?.createWebSocket !== void 0 || transport?.openStream !== void 0 || transport?.rpc?.open !== void 0) return false;
\t\t\t\tconst base = transport?.streamBaseUrl ?? (typeof document === "undefined" ? void 0 : document.baseURI);
\t\t\t\tif (typeof base !== "string") return false;
\t\t\t\tconst target = new URL("api/remote.mux", base);
\t\t\t\tif ((target.protocol !== "http:" && target.protocol !== "https:") || target.username || target.password) return false;
\t\t\t\tconst authority = /^https?:\\/\\/(\\[[0-9a-f:.]+\\]|[^\\/?#:]+)(?::[0-9]+)?(?:[\\/?#]|$)/i.exec(base);
\t\t\t\tif (!authority || authority[1].toLowerCase() !== target.hostname.toLowerCase()) return false;
\t\t\t\tif (target.hostname === "[::1]") return true;
\t\t\t\tconst parts = target.hostname.split(".");
\t\t\t\treturn parts.length === 4 && parts[0] === "127" && parts.every((part) => /^[0-9]{1,3}$/.test(part) && String(Number(part)) === part && Number(part) <= 255);
\t\t\t} catch {
\t\t\t\treturn false;
\t\t\t}
\t\t}
\t\tfunction watchBrowserNetwork(controller, transport) {
\t\t\tconst browser = globalThis.window;''')
    edit('''\t\t\tconst offline = () => {
\t\t\t\tcontroller.setNetworkAvailable(false);
\t\t\t};
\t\t\tcontroller.setNetworkAvailable(initiallyAvailable);''', '''\t\t\tconst offline = () => {
\t\t\t\tcontroller.setNetworkAvailable(dshaUsesLiteralLoopbackSocket(transport));
\t\t\t};
\t\t\tcontroller.setNetworkAvailable(initiallyAvailable || dshaUsesLiteralLoopbackSocket(transport));''')
    edit('stopNetworkWatch: watchBrowserNetwork(controller)',
         'stopNetworkWatch: watchBrowserNetwork(controller, transport)')
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
