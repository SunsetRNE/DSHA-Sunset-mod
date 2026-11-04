// Reviewed source/prebuilt parity for the fixed mobile plugin. The caller checks
// the upstream SHA before applying these unique, module-scoped anchors.
export const mobileLayoutSourceFiles = [
  'src/client/effects/tablet-quick-settings.ts',
  'src/client/effects/drawer-quick-settings.ts',
];

function replaceOnce(text, before, after) {
  if (text.split(before).length !== 2) throw Error('MOBILE_LAYOUT_ANCHOR: ' + before.slice(0, 100));
  return text.replace(before, after);
}

function patchTablet(text, ts, reverse = false) {
  const once = (text, before, after) => replaceOnce(text, reverse ? after : before, reverse ? before : after);
  const indent = ts ? '      ' : '        ';
  const s = ts ? '' : ';';
  const guard = [
    '// DSHA_TABLET_SETTINGS_OBSERVER_V1: streamed message/composer/session-list',
    '// changes cannot replace the account row or its portal menu. Inspect only',
    '// changed subtrees, never rescan the conversation merely to find a gear.',
    `const relevant = '[class*="triggerRow"], [role="menu"], [data-shell-overlay]'${s}`,
    `const touchesSettings = (record${ts ? ': MutationRecord' : ''})${ts ? ': boolean' : ''} => {`,
    `  const target = record.target instanceof Element ? record.target : record.target.parentElement${s}`,
    `  if (target?.closest(relevant)) return true${s}`,
    `  if (target?.closest('[data-chat-flow]')) return false${s}`,
    '  return [...record.addedNodes, ...record.removedNodes].some((node) =>',
    `    node instanceof Element && (node.matches(relevant) || node.querySelector(relevant) !== null))${s}`,
    `}${s}`,
    `let frame = 0${s}`,
    `let active = true${s}`,
  ].map(line => indent + line).join('\n');
  text = once(text, indent + 'let queued = false' + s, guard);
  text = once(text, 'queued = false' + s, `frame = 0${s}\n${indent}${ts ? '  ' : '    '}if (!active) return${s}`);
  if (ts) {
    text = once(text, `const observer = new MutationObserver(() => {
        if (queued) return
        queued = true
        requestAnimationFrame(run)
      })`, `const observer = new MutationObserver((records) => {
        if (!active || frame !== 0 || !records.some(touchesSettings)) return
        frame = requestAnimationFrame(run)
      })`);
    text = once(text, `      return () => {
        observer.disconnect()`, `      return () => {
        active = false
        if (frame !== 0) cancelAnimationFrame(frame)
        frame = 0
        observer.disconnect()`);
  } else {
    text = once(text, `const observer = new MutationObserver(() => {
            if (queued)
                return;
            queued = true;
            requestAnimationFrame(run);
        });`, `const observer = new MutationObserver((records) => {
            if (!active || frame !== 0 || !records.some(touchesSettings)) return;
            frame = requestAnimationFrame(run);
        });`);
    text = once(text, `        return () => {
            observer.disconnect();`, `        return () => {
            active = false;
            if (frame !== 0) cancelAnimationFrame(frame);
            frame = 0;
            observer.disconnect();`);
  }
  return text;
}

function patchDrawer(text, ts, reverse = false) {
  const once = (text, before, after) => replaceOnce(text, reverse ? after : before, reverse ? before : after);
  if (ts) {
    text = once(text, '  let pending: number | null = null', '  let pending: number | null = null\n  let menuFrame = 0');
    text = once(text, '  const clearPending = (): void => {', '  const clearPending = (): void => {\n    if (menuFrame !== 0) cancelAnimationFrame(menuFrame)\n    menuFrame = 0');
    text = once(text, '  const selectSettingsItem = (trigger: HTMLButtonElement): void => {', '  const selectSettingsItem = (trigger: HTMLButtonElement): void => {\n    clearPending()');
    text = once(text, '    requestAnimationFrame(() => {', '    menuFrame = requestAnimationFrame(() => {\n      menuFrame = 0');
    text = once(text, `  const syncMenuEntry = (target: HTMLElement): void => {
    const menus = document.querySelectorAll<HTMLElement>('[role="menu"]')
    const menu = menus.length > 0 ? menus[menus.length - 1] ?? null : null
    if (menu === null) return`, `  // DSHA_SETTINGS_CLOSED_MENU_V1: the common closed state needs no document scan.
  // Remember only our marked portal, so a closed/replaced menu loses our marker.
  let markedMenu: HTMLElement | null = null
  const syncMenuEntry = (target: HTMLElement): void => {`);
    text = once(text, `    if (!keep) {
      menu.removeAttribute(MENU_ENTRY_ATTR)
      return
    }`, `    if (!keep) {
      markedMenu?.removeAttribute(MENU_ENTRY_ATTR)
      markedMenu = null
      return
    }
    const menus = document.querySelectorAll<HTMLElement>('[role="menu"]')
    const menu = menus.length > 0 ? menus[menus.length - 1] ?? null : null
    if (menu !== markedMenu) markedMenu?.removeAttribute(MENU_ENTRY_ATTR)
    markedMenu = menu
    if (menu === null) return`);
    return once(text, '      clearMenuEntry()', '      clearMenuEntry()\n      markedMenu?.removeAttribute(MENU_ENTRY_ATTR)\n      markedMenu = null');
  }
  text = once(text, '    let pending = null;', '    let pending = null;\n    let menuFrame = 0;');
  text = once(text, '    const clearPending = () => {', '    const clearPending = () => {\n        if (menuFrame !== 0) cancelAnimationFrame(menuFrame);\n        menuFrame = 0;');
  text = once(text, '    const selectSettingsItem = (trigger) => {', '    const selectSettingsItem = (trigger) => {\n        clearPending();');
  text = once(text, '        requestAnimationFrame(() => {', '        menuFrame = requestAnimationFrame(() => {\n            menuFrame = 0;');
  text = once(text, `    const syncMenuEntry = (target) => {
        const menus = document.querySelectorAll('[role="menu"]');
        const menu = menus.length > 0 ? menus[menus.length - 1] ?? null : null;
        if (menu === null)
            return;`, `    // DSHA_SETTINGS_CLOSED_MENU_V1: the common closed state needs no document scan.
    // Remember only our marked portal, so a closed/replaced menu loses our marker.
    let markedMenu = null;
    const syncMenuEntry = (target) => {`);
  text = once(text, `        if (!keep) {
            menu.removeAttribute(MENU_ENTRY_ATTR);
            return;
        }`, `        if (!keep) {
            markedMenu?.removeAttribute(MENU_ENTRY_ATTR);
            markedMenu = null;
            return;
        }
        const menus = document.querySelectorAll('[role="menu"]');
        const menu = menus.length > 0 ? menus[menus.length - 1] ?? null : null;
        if (menu !== markedMenu) markedMenu?.removeAttribute(MENU_ENTRY_ATTR);
        markedMenu = menu;
        if (menu === null) return;`);
  return once(text, '            clearMenuEntry();', '            clearMenuEntry();\n            markedMenu?.removeAttribute(MENU_ENTRY_ATTR);\n            markedMenu = null;');
}

export function applyMobileLayoutSourcePatch(name, text) {
  if (name === mobileLayoutSourceFiles[0]) return patchTablet(text, true);
  if (name === mobileLayoutSourceFiles[1]) return patchDrawer(text, true);
  return text;
}

// Verification restores exactly the reviewed hunks, then hashes against the
// fixed upstream lock; unknown edits cannot be hidden by this round trip.
export function restoreMobileLayoutSource(name, text) {
  if (name === mobileLayoutSourceFiles[0]) return patchTablet(text, true, true);
  if (name === mobileLayoutSourceFiles[1]) return patchDrawer(text, true, true);
  return text;
}

function patchBundle(text, reverse) {
  for (const [name, patch] of [['tablet-quick-settings', patchTablet], ['drawer-quick-settings', patchDrawer]]) {
    const start = `__modules["effects/${name}.js"] = function (require, module, exports) {`;
    const offset = text.indexOf(start), end = text.indexOf('\n__modules[', offset + start.length);
    if (offset < 0 || end < 0 || text.split(start).length !== 2) throw Error('MOBILE_LAYOUT_MODULE: ' + name);
    text = text.slice(0, offset) + patch(text.slice(offset, end), false, reverse) + text.slice(end);
  }
  return text;
}

export const applyMobileLayoutBundlePatch = text => patchBundle(text, false);
export const restoreMobileLayoutBundle = text => patchBundle(text, true);
