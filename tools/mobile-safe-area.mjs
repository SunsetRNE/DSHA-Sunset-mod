// The native container owns bars/cutouts. Browser tabs retain CSS env() fallbacks.
export function applyMobileSafeArea(source) {
  // 3.2.3 fixes the old dead header selector and lifts it by 12px. DSHA has
  // already consumed the status/cutout inset outside the WebView, so that
  // browser-only lift would now move the title above its native viewport.
  const header = `  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) {
    margin-top: -12px !important;
  }`;
  if (source.split(header).length !== 2) throw new Error('MOBILE_NATIVE_HEADER_ANCHOR');
  source = source.replace(header, header + `
  /* DSHA_NATIVE_HEADER_SAFE_V1: native already owns the safe top. */
  html[data-dsha-safe-top="native"] [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) {
    margin-top: 0 !important;
  }`);
  return source.replace(/env\(safe-area-inset-(top|right|bottom|left),\s*0px\)/g,
    (original, side) => `var(--dsha-safe-area-inset-${side}, ${original})`);
}
