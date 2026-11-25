// Apply the same scroll hot-path changes to the locked prebuilt client and its TypeScript source.
export const mobileScrollSourceFiles = [
  'src/client/effects/document-scroll-lock.ts',
  'src/client/effects/model-menu-anchor.ts',
  'src/client/index.tsx',
];

export function applyMobileScrollPatch(source, file = 'lib/client.js') {
  const bundle = file === 'lib/client.js';
  const end = bundle ? ';' : '';
  const replace = (before, after) => {
    if (source.split(before).length !== 2) throw new Error('Mobile scroll patch anchor: ' + before);
    source = source.replace(before, after);
  };
  const replaceLine = (before, after) => {
    const matches = source.split('\n').filter(line => line.trim() === before + end);
    if (matches.length !== 1) throw new Error('Mobile scroll patch line: ' + before);
    const [line] = matches;
    const indent = line.slice(0, line.length - line.trimStart().length);
    replace('\n' + line + '\n', after.length ? '\n' + after.map(part => indent + part).join('\n') + '\n' : '\n');
  };
  if (bundle || file === mobileScrollSourceFiles[0]) {
    // Document scroll bubbles to window. A capture listener on document also observes every transcript scroll.
    for (const method of ['addEventListener', 'removeEventListener']) {
      replaceLine(`document.${method}('scroll', pin, true)`, []);
    }
  }
  if (bundle || file === mobileScrollSourceFiles[1]) {
    const observe = "document.addEventListener('scroll', onViewportChange, { capture: true, passive: true })";
    const unobserve = "document.removeEventListener('scroll', onViewportChange, true)";
    const stopFollowing = bundle ? 'const stopFollowing = () => {' : 'const stopFollowing = (): void => {';
    const bodyIndent = bundle ? '    ' : '  ';
    // The cache is refreshed by interaction, and only a visible menu needs a capture listener.
    replaceLine('active = findMenu()', [
      `const menu = findMenu()${end}`,
      'if (menu === null) {',
      `${bodyIndent}stopFollowing()${end}`,
      `${bodyIndent}return${end}`,
      '}',
      `if (active === null) ${observe}${end}`,
      `active = menu${end}`,
    ]);
    const nullActive = ['releasePlacement()', 'active = null', 'activeTrigger = null', 'activeCard = null'];
    for (const depth of [3, 4]) {
      const indent = bodyIndent.repeat(depth);
      replace(nullActive.map(line => '\n' + indent + line + end).join(''), '\n' + indent + 'stopFollowing()' + end);
    }
    replaceLine("document.addEventListener('scroll', onViewportChange, true)", []);
    replaceLine(unobserve, []);
    replaceLine('schedule(follow)', [`if (active !== null) schedule(follow)${end}`]);
    const viewportComment = '// 视口变化只走"零查询"的重算路径。';
    const indent = bodyIndent.repeat(2);
    replace(indent + viewportComment, [
      stopFollowing,
      ...[unobserve, ...nullActive].map(line => bodyIndent + line + end),
      '}' + end,
      viewportComment,
    ].map(line => indent + line).join('\n'));
    // A scroll delivered after a click in the same frame must not discard the click's menu query.
    replaceLine('pending = run', [`if (pending !== refresh) pending = run${end}`]);
    replace('本帧待跑的那一份意图。后到的请求覆盖它（见 schedule()）。', '本帧待跑的那一份意图。交互刷新优先于滚动重算。');
    replace('后到的请求覆盖先到的（不是被丢掉）。', '同一帧的 refresh() 优先于 follow()。');
    replace('最新那份意图」，后到者覆盖先到者，请求不再丢。', '待执行的意图」，交互刷新不会被滚动重算覆盖。');
  }
  if (bundle || file === mobileScrollSourceFiles[2]) source = patchConversationScroll(source, bundle);
  return source;
}

// index.tsx also receives a locale patch. Its provenance test reverses both
// operations before checking the original bytes against the upstream lock.
export function restoreMobileConversationScrollSource(source) {
  return patchConversationScroll(source, false, true);
}

function patchConversationScroll(source, bundle, reverse = false) {
    const end = bundle ? ';' : '';
    const changes = [];
    const replace = (before, after) => changes.push([before, after]);
    if (!bundle) replace('  __dshaMobileLastScroll?: SavedScroll', '  __dshaMobileLastScroll?: SavedScroll\n  __dshaMobileScrollRestore?: object');
    const spaces = bundle ? '    ' : '  ';
    const remember = bundle ? 'function rememberConversationScroll() {' : 'function rememberConversationScroll(): void {';
    const restore = bundle ? 'function restoreConversationScroll() {' : 'function restoreConversationScroll(): void {';
    // Copied modules share the token on window, so retries from a previous stylesheet swap cannot write a remounted node.
    replace(`${remember}\n${spaces}for (const element of conversationScrollers()) {`, [
      remember,
      `${spaces}delete scrollWindow.__dshaMobileScrollRestore${end}`,
      `${spaces}const elements = conversationScrollers()${end}`,
      `${spaces}if (elements.length > 0) delete scrollWindow.__dshaMobileLastScroll${end}`,
      `${spaces}for (const element of elements) {`,
    ].join('\n'));
    replace(restore, [
      restore,
      `${spaces}const token = {}${end}`,
      `${spaces}scrollWindow.__dshaMobileScrollRestore = token${end}`,
      `${spaces}const isCurrent = () => scrollWindow.__dshaMobileScrollRestore === token${end}`,
    ].join('\n'));
    replace('if (element.isConnected && saved.url === location.href &&', 'if (isCurrent() && element.isConnected && saved.url === location.href &&');
    replace('if (saved.url !== location.href || Date.now() - saved.savedAt > SWAP_RESTORE_MS)', 'if (!isCurrent() || saved.url !== location.href || Date.now() - saved.savedAt > SWAP_RESTORE_MS)');
    const findScroller = bundle ? "document.querySelector('[class*=\"scrollBody\"]')" : "document.querySelector<HTMLElement>('[class*=\"scrollBody\"]')";
    replace(`${findScroller}${end}\n${spaces.repeat(2)}if (element !== null && element.scrollTop === 0 &&`, [
      findScroller + end,
      `${spaces.repeat(2)}if (element !== null && element.scrollTop !== 0) return${end}`,
      `${spaces.repeat(2)}if (element !== null &&`,
    ].join('\n'));
    if (reverse) changes.reverse();
    for (const change of changes) {
      const [before, after] = reverse ? [change[1], change[0]] : change;
      if (source.split(before).length !== 2) throw new Error('Mobile conversation scroll patch anchor: ' + before);
      source = source.replace(before, after);
    }
    return source;
}
