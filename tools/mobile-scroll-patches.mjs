// Keep the fixed mobile TS sources and prebuilt modules in parity. These
// patches change scheduling only; focus ownership and menu placement stay local.
export const mobileScrollSourceFiles = [
  'src/client/effects/model-menu-anchor.ts',
  'src/client/effects/session-focus-guard.ts',
];

function replaceOnce(text, before, after, reverse) {
  const from = reverse ? after : before, to = reverse ? before : after;
  if (text.split(from).length !== 2) throw Error('MOBILE_SCROLL_ANCHOR: ' + from.slice(0, 100));
  return text.replace(from, to);
}

function patchModel(text, ts, reverse = false) {
  const once = (before, after) => { text = replaceOnce(text, before, after, reverse); };
  const s = ts ? '' : ';', indent = ts ? '      ' : '            ';
  once('本帧待跑的那一份意图。后到的请求覆盖它（见 schedule()）。',
    '本帧待跑的那一份意图。菜单发现优先于仅跟随位置（见 schedule()）。');
  once('槽位存的是「最新那份意图」', '槽位保留优先级最高的意图');
  once('后到的请求覆盖先到的（不是被丢掉）。',
    'refresh 发现节点优先；follow 只在本帧没有发现任务时入槽。');
  once(indent + 'pending = run' + s,
    indent + '// DSHA_MENU_SCROLL_V1: discovery must survive a later scroll in the same frame.\n' +
    indent + 'if (pending !== refresh) pending = run' + s);
  once(indent + 'schedule(follow)' + s,
    indent + '// Closed menus need no animation frame, allocation or layout read.\n' +
    indent + 'if (active !== null) schedule(follow)' + s);
  return text;
}

function patchFocus(text, ts, reverse = false) {
  const i = ts ? '    ' : '        ', s = ts ? '' : ';';
  const once = (before, after) => { text = replaceOnce(text, before, after, reverse); };
  const filter = [
    '// DSHA_FOCUS_MUTATION_V2: keep upstream window-bound input observation.',
    '// Route by changed subtrees; the marker observer also retains the local',
    '// late-mounted panel fallback without scanning streamed history.',
    `const relevant = \`[data-mobile-nav="frame"][\${PANEL_EXIT_ATTR}], \${COMPOSER_INPUT_SELECTOR}\`${s}`,
    `const touchesFocus = (record${ts ? ': MutationRecord' : ''})${ts ? ': boolean' : ''} => {`,
    `  const target = record.target instanceof Element ? record.target : record.target.parentElement${s}`,
    `  if (record.type === 'attributes') return target?.matches(relevant) ?? false${s}`,
    `  if (target?.closest('[data-chat-flow]')) return false${s}`,
    '  return [...record.addedNodes].some((node) =>',
    `    node instanceof Element && (node.matches(relevant) || node.querySelector(relevant) !== null))${s}`,
    `}${s}`,
    'const inputObserver = new MutationObserver((records) => {',
    `  if (!records.some(touchesFocus)) return${s}`,
  ].map(line => i + line).join('\n');
  once(i + 'const inputObserver = new MutationObserver(() => {', filter);
  once(i + 'const markerObserver = new MutationObserver(() => {',
    i + 'const markerObserver = new MutationObserver((records) => {\n' + i + `  if (!records.some(touchesFocus)) return${s}`);
  once(i + 'markerObserver.observe(document.documentElement, {\n' + i + (ts ? '  ' : '    ') + 'subtree: true,',
    i + 'markerObserver.observe(document.documentElement, {\n' + i + (ts ? '  ' : '    ') + 'childList: true,\n' + i + (ts ? '  ' : '    ') + 'subtree: true,');
  return text;
}

const patches = [patchModel, patchFocus];
export function applyMobileScrollSourcePatch(name, text) {
  const index = mobileScrollSourceFiles.indexOf(name);
  return index < 0 ? text : patches[index](text, true);
}
export function restoreMobileScrollSource(name, text) {
  const index = mobileScrollSourceFiles.indexOf(name);
  return index < 0 ? text : patches[index](text, true, true);
}
function patchBundle(text, reverse) {
  for (const [index, name] of ['model-menu-anchor', 'session-focus-guard'].entries()) {
    const start = `__modules["effects/${name}.js"] = function (require, module, exports) {`;
    const offset = text.indexOf(start), end = text.indexOf('\n__modules[', offset + start.length);
    if (offset < 0 || end < 0 || text.split(start).length !== 2) throw Error('MOBILE_SCROLL_MODULE: ' + name);
    text = text.slice(0, offset) + patches[index](text.slice(offset, end), false, reverse) + text.slice(end);
  }
  return text;
}
export const applyMobileScrollBundlePatch = text => patchBundle(text, false);
export const restoreMobileScrollBundle = text => patchBundle(text, true);
