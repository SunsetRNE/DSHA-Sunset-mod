// 从 npm 官方 semver 发布包生成离线适配器；用法：node tools/vendor-plugin-semver.cjs /path/to/semver
const fs = require('node:fs');
const path = require('node:path');
// DSH 兼容检查只读普通 manifest；不导入 app-boot 或任何插件入口。
function compatibility(request) {
  const fs = require('node:fs'), path = require('node:path');
  const { createRequire } = require('node:module'), { createHash } = require('node:crypto');
  const object = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
  const exact = (value) => {
    if (typeof value !== 'string') return false;
    const parsed = semver.parse(value);
    return parsed !== null && value === parsed.version + (parsed.build.length ? '+' + parsed.build.join('.') : '');
  };
  const packageName = /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/;
  const platforms = ['aix','android','darwin','freebsd','haiku','linux','openbsd','sunos','win32','cygwin','netbsd'];
  if (!Array.isArray(request.manifests) || request.manifests.length > 512) throw Error('COMPATIBILITY_INPUT');
  let runtimeVersion = '', runtimeEvidence = '';
  for (const anchor of request.anchors || []) {
    if (!fs.existsSync(anchor)) continue;
    const local = createRequire(anchor);
    const entry = local.resolve('@deepseek-ai/dsh-app-boot');
    const filename = path.resolve(path.dirname(entry), '../package.json');
    const before = fs.statSync(filename, { bigint: true });
    if (!before.isFile() || before.size > 1048576n) throw Error('RUNTIME_MANIFEST');
    const bytes = fs.readFileSync(filename), manifest = JSON.parse(bytes.toString('utf8'));
    const after = fs.statSync(filename, { bigint: true });
    if (before.dev !== after.dev || before.ino !== after.ino || before.ctimeNs !== after.ctimeNs
        || before.mtimeNs !== after.mtimeNs || before.size !== after.size) throw Error('RUNTIME_CHANGED');
    if (!object(manifest) || manifest.name !== '@deepseek-ai/dsh-app-boot'
        || typeof manifest.version !== 'string' || semver.valid(manifest.version) === null) throw Error('RUNTIME_VERSION');
    runtimeVersion = manifest.version;
    runtimeEvidence = createHash('sha256').update(filename).update(bytes)
      .update([before.dev,before.ino,before.ctimeNs,before.mtimeNs,before.size].join(':')).digest('hex');
    break;
  }
  if (!runtimeVersion) throw Error('RUNTIME_MISSING');
  const exemptions = Object.create(null), warnings = [...(request.exemptionWarnings || [])];
  if (!object(request.exemptions)) warnings.push('EXEMPTION_FORMAT');
  else for (const [key, versions] of Object.entries(request.exemptions)) {
    const separator = key.lastIndexOf('@');
    if (separator <= 0 || !packageName.test(key.slice(0,separator)) || !exact(key.slice(separator+1))) {
      warnings.push('EXEMPTION_IDENTITY'); continue;
    }
    if (!Array.isArray(versions) || !versions.every(exact)) { warnings.push('EXEMPTION_VERSION_LIST'); continue; }
    exemptions[key] = versions;
  }
  const accepts = (range) => range.trim() !== '' && semver.satisfies(runtimeVersion,range,{includePrerelease:true});
  return request.manifests.map(manifest => {
    const result = { installedDsh:runtimeVersion, runtimePlatform:process.platform, requirements:{}, unmetRequirements:{},
      versionExempted:false, exemptionWarnings:[...new Set(warnings)].slice(0,16), compatibility:'check-failed',
      compatibilityCode:'PLUGIN_COMPATIBILITY_CHECK_FAILED', runtimeEvidence };
    try {
      if (!object(manifest)) throw Error('MANIFEST_OBJECT');
      const engines = Object.hasOwn(manifest,'engines') ? manifest.engines : {};
      if (!object(engines)) throw Error('ENGINES_OBJECT');
      if (Object.hasOwn(engines,'dsh')) {
        if (typeof engines.dsh !== 'string') throw Error('ENGINE_RANGE');
        result.requirements['engines.dsh'] = engines.dsh;
        if (!accepts(engines.dsh)) result.unmetRequirements['engines.dsh'] = engines.dsh;
      } else if (Object.hasOwn(manifest,'peerDependencies')) {
        if (!object(manifest.peerDependencies)) throw Error('PEERS_OBJECT');
        for (const [name,range] of Object.entries(manifest.peerDependencies)) {
          if (typeof range !== 'string') throw Error('PEER_RANGE');
          if (name !== '@deepseek-ai/dsh' && !name.startsWith('@deepseek-ai/dsh-')) continue;
          result.requirements[name] = range;
          const applied = ['workspace:^','workspace:~','workspace:*'].includes(range) ? runtimeVersion : range;
          if (!accepts(applied)) result.unmetRequirements[name] = range;
        }
      }
      if (Object.keys(result.unmetRequirements).length) {
        if (typeof manifest.name !== 'string' || !manifest.name.trim() || typeof manifest.version !== 'string' || !manifest.version.trim()) throw Error('PLUGIN_IDENTITY');
        result.versionExempted = exemptions[manifest.name+'@'+manifest.version]?.includes(runtimeVersion) === true;
        result.compatibility = result.versionExempted ? 'exempted' : 'incompatible';
        result.compatibilityCode = result.versionExempted ? '' : 'PLUGIN_INCOMPATIBLE';
      } else {
        result.compatibility = Object.keys(result.requirements).length ? 'compatible' : 'unconstrained';
        result.compatibilityCode = '';
      }
      const declared = manifest.dsh?.bundle?.platforms;
      if (declared !== undefined) {
        if (!Array.isArray(declared) || !declared.length || !declared.every(value => typeof value === 'string' && platforms.includes(value))) throw Error('PLATFORMS_FORMAT');
        if (!declared.includes(process.platform)) { result.compatibility='unsupported-platform';result.compatibilityCode='PLUGIN_UNSUPPORTED_PLATFORM'; }
      }
    } catch (error) {
      result.compatibility='check-failed';result.compatibilityCode='PLUGIN_COMPATIBILITY_CHECK_FAILED';
      result.versionExempted=false;result.compatibilityDiagnostic=error.message;
    }
    return result;
  });
}

const root = path.resolve(process.argv[2]);
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
if (pkg.name !== 'semver' || pkg.version !== '7.8.1') throw Error('需要官方 semver 7.8.1');
const modules = new Map();
function collect(id) {
  if (modules.has(id)) return;
  const code = fs.readFileSync(path.join(root, id), 'utf8').replace(/\r\n/g, '\n');
  modules.set(id, '');
  const linked = code.replace(/require\(['"]([^'"]+)['"]\)/g, (_, name) => {
    if (!name.startsWith('.')) throw Error('不支持外部依赖：' + name);
    const target = path.posix.normalize(path.posix.join(path.posix.dirname(id), name)) + (name.endsWith('.js') ? '' : '.js');
    if (target.startsWith('../')) throw Error('依赖越界');
    collect(target);
    return `load(${JSON.stringify(target)})`;
  });
  modules.set(id, linked);
}
collect('index.js');
const license = fs.readFileSync(path.join(root, 'LICENSE'), 'utf8').replace(/\r\n/g, '\n');
const bundle = `// 由 tools/vendor-plugin-semver.cjs 生成。npm/node-semver ${pkg.version}，未修改解析语义。\n/*\n${license}\n*/\n` +
  'const modules = {\n' + [...modules].map(([id, code]) => `${JSON.stringify(id)}: (module, exports, load) => {\n${code}\n}`).join(',\n') + '\n};\n' +
  `const cache = {};
function load(id) {
  if (!cache[id]) { const m = cache[id] = { exports: {} }; modules[id](m, m.exports, load); }
  return cache[id].exports;
}
const semver = load('index.js');
module.exports = semver;
Object.defineProperty(module.exports, 'dshaCompatibilityReports', { value: compatibility });
${compatibility.toString()}
if (require.main === module) {
  const request = JSON.parse(require('node:fs').readFileSync(0, 'utf8'));
  let result = null;
  try {
    if (request.action === 'compatibility') result = compatibility(request);
    else if (request.action === 'exact') result = typeof request.version === 'string' && semver.parse(request.version) !== null
      && request.version === semver.parse(request.version).version + (semver.parse(request.version).build.length ? '+' + semver.parse(request.version).build.join('.') : '');
    else if (request.action === 'compare') result = semver.valid(request.left) && semver.valid(request.right)
      ? semver.compare(request.left, request.right) : null;
    else if (semver.valid(request.version) && semver.validRange(request.range) !== null)
      result = semver.satisfies(request.version, request.range);
  } catch (_) { }
  process.stdout.write(JSON.stringify(result));
}
`;
fs.writeFileSync(path.resolve(__dirname, '../app/src/main/assets/plugin-semver.cjs'), bundle);
console.log(`semver ${pkg.version}: ${modules.size} modules, ${Buffer.byteLength(bundle)} bytes`);
