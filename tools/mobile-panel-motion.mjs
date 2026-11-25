export const mobilePanelMotionSourceFiles = [
  'src/client/effects/panel-exit.ts',
  'src/client/styles/layout.css.ts',
];

// The checked-in source and prebuilt bundle carry the same focused change.
export function applyMobilePanelMotion(source, file = 'lib/client.js') {
  const bundle = file === 'lib/client.js';
  const [exitSource, cssSource] = mobilePanelMotionSourceFiles;
  const replace = (before, after) => {
    if (source.split(before).length !== 2) throw new Error('Mobile panel motion patch anchor: ' + before);
    source = source.replace(before, after);
  };
  if (bundle || file === exitSource) {
    const space = bundle ? '    ' : '  ';
    const end = bundle ? ';' : '';
    const layout = bundle ? '(0, frame_ts_1.getFrame)()' : 'getFrame()';
    const block = (level, lines) => lines.map(line => space.repeat(level) + line).join('\n');
    const oldFrameMarker = bundle
      ? [`const frame = ${layout}${end}`, 'if (frame !== null)', `${space}frame.setAttribute(PANEL_EXIT_ATTR, '')${end}`]
      : [`const frame = ${layout}`, "if (frame !== null) frame.setAttribute(PANEL_EXIT_ATTR, '')"];
    replace(block(2, ["ghost.setAttribute('aria-hidden', 'true')" + end]),
      block(2, ["ghost.setAttribute('aria-hidden', 'true')" + end, "ghost.setAttribute('inert', '')" + end]));
    replace(block(2, [`finishGhost()${end}`, "const ghost = document.createElement('div')" + end]),
      block(2, ["const ghost = document.createElement('div')" + end]));
    replace(block(2, [...oldFrameMarker, `const panel = panelElement()${end}`, `ghost = panel === null ? null : freezePanel(panel)${end}`]),
      block(2, [
        "const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true" + end,
        `finishGhost()${end}`,
        'if (!reduceMotion) {',
        ...oldFrameMarker.map(line => space + line),
        `${space}const panel = panelElement()${end}`,
        `${space}ghost = panel === null ? null : freezePanel(panel)${end}`,
        '}',
      ]));
    replace(block(bundle ? 4 : 3, [`armExit(0)${end}`]), block(bundle ? 4 : 3, bundle ? [
      'if (reduceMotion)', `${space}finishMain()${end}`, 'else', `${space}armExit(0)${end}`,
    ] : ['if (reduceMotion) finishMain()', 'else armExit(0)']));
    replace('// Fallback: reduced-motion kills the animation, and a layer left behind',
      '// Fallback: settings can change mid-animation, or the browser');
    replace('// would cover the whole screen — never rely on animationend alone.',
      '// may skip animationend if the layer is detached.');
  }
  if (bundle || file === cssSource) {
    const anchor = '    [data-mobile-nav="panel-ghost"],\n' +
      '    [data-mobile-nav="frame"][data-mobile-panel-exit]:not(:has([class*="panelRow"][aria-current="page"])) [class*="_centerCol"] > * > * {\n' +
      '      animation: none !important;\n    }';
    replace(anchor, anchor + '\n    /* The preference can change while the exit snapshot is still mounted. */\n' +
      '    [data-mobile-nav="panel-ghost"] { display: none; }');
  }
  return source;
}
