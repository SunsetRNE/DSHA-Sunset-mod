#!/usr/bin/env python3
"""Generate fail-closed SSE framing recipes for the rc5 locked OpenAI SDK."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
VERSION = '0.2.1-rc.5'
SOURCES = {
    'esm': ('openai/core/streaming.mjs', '1b8fa8338dc28913351993382187962082f0af42c5274a009060de3263579842'),
    'cjs': ('openai/core/streaming.js', '9230a5e7579c7a26aaf973626ef1e90174925ee1ed9f76204ec02a4c4d07d862'),
}
MARKER = 'DSHA_OPENAI_SSE_FRAMING_V1'


def generate(raw):
    raw = Path(raw)
    lock_bytes = (ROOT / 'tools/dsh-runtime/package-lock.json').read_bytes()
    lock = json.loads(lock_bytes)
    proof = json.loads((raw / 'dsha-test-runtime.json').read_text(encoding='utf8'))
    if proof['kind'] != 'raw' or proof['dshVersion'] != VERSION:
        raise ValueError('OPENAI_SSE_RAW_RC5_REQUIRED')
    if proof['lockSha256'] != hashlib.sha256(lock_bytes).hexdigest():
        raise ValueError('OPENAI_SSE_LOCK_PROOF')
    package = lock['packages']['node_modules/openai']
    if package['version'] != '7.19.0':
        raise ValueError('OPENAI_SSE_LOCKED_SDK_VERSION')
    output = {}
    for kind, (module, expected) in SOURCES.items():
        source_bytes = (raw / 'node_modules' / module).read_bytes()
        digest = hashlib.sha256(source_bytes).hexdigest()
        if digest != expected or proof['contentProof']['entries']['node_modules/' + module]['sha256'] != digest:
            raise ValueError('OPENAI_SSE_SOURCE_PROOF: ' + module)
        source = source_bytes.decode('utf8')
        # 同一锁定 SDK 的两种模块入口都执行同一个解码器实现。
        start = source.index('class SSEDecoder {')
        end = source.index('\nfunction partition(', start)
        before = source[start:end]
        after = '''// DSHA_OPENAI_SSE_FRAMING_V1: 无 data 字段的控制块不产生 JSON 事件；数据帧仍严格解析。
class SSEDecoder {
    constructor() {
        this.event = null;
        this.data = [];
        this.chunks = [];
    }
    decode(line) {
        if (line.endsWith('\\r')) {
            line = line.slice(0, -1);
        }
        if (!line) {
            // SSE 规范：没有 data 字段时仅复位事件类型；不能把空 JSON 送给协议层。
            // data:（显式空值）仍会产生事件，由 JSON.parse 报错，绝不吞掉坏数据。
            const sse = this.data.length === 0 ? null : {
                event: this.event || null,
                data: this.data.join('\\n'),
                raw: this.chunks,
            };
            this.event = null;
            this.data = [];
            this.chunks = [];
            return sse;
        }
        // 心跳注释不属于数据，避免长时间等待时累计无用诊断副本。
        if (line.startsWith(':')) {
            return null;
        }
        const [fieldname, , initialValue] = partition(line, ':');
        let value = initialValue;
        if (value.startsWith(' ')) {
            value = value.slice(1);
        }
        if (fieldname === 'event') {
            this.event = value;
        }
        else if (fieldname === 'data') {
            this.data.push(value);
        }
        else {
            return null;
        }
        this.chunks.push(line);
        return null;
    }
    flush() {
        // 保留 SDK 对缺少末尾空行的完整 JSON 的兼容；不完整 JSON 仍由协议层拒绝。
        return this.decode('');
    }
}'''
        patches = [(before, after)]
        # 分别定位两个 JSON 分支，保持逐锚点精确匹配与运行时幂等。
        old_error = "throw new SyntaxError('Error reading response: malformed server-sent event JSON.');"
        new_error = "throw new SyntaxError(`Error reading response: malformed server-sent event JSON (DSHA_SSE_INVALID_JSON; dataChars=${sse.data.length}).`);"
        for suffix in ["\n                        }\n                        if (sse.event === 'error')", "\n                        }\n                        // SAFETY: Named SSE events"]:
            patches.append((old_error + suffix, new_error + suffix))
        candidate = source
        for old, new in patches:
            if candidate.count(old) != 1 or new in candidate:
                raise ValueError('OPENAI_SSE_ANCHOR: ' + kind)
            candidate = candidate.replace(old, new, 1)
        recipe = dict(version=1, dshVersion=VERSION, module=module,
                      packageVersion=package['version'], sourceSha256=digest,
                      packageIntegrity=package['integrity'],
                      targetSha256=hashlib.sha256(candidate.encode('utf8')).hexdigest(),
                      reason='Ignore and reset only SSE blocks without a data field and release heartbeat diagnostics. Keep the SDK complete-JSON EOF compatibility; malformed or truncated JSON remains terminal without payload logging or request replay.',
                      patches=[dict(before=old, after=new) for old, new in patches])
        output['openai-sse-' + kind + '-patch.json'] = json.dumps(recipe, ensure_ascii=False, indent=2) + '\n'
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw', type=Path, required=True)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    for name, content in generate(args.raw).items():
        if args.write:
            (ASSETS / name).write_bytes(content.encode('utf8'))
        elif (ASSETS / name).read_bytes() != content.encode('utf8'):
            raise ValueError('OPENAI_SSE_RECIPE_DRIFT: ' + name)
        print(name)


if __name__ == '__main__':
    main()
