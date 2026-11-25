// 用锁定前端的 React 和本地化服务验证真实按钮。WebView 消息和设置请求在宿主浏览器中捕获。
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { browserFixture } from './rc1-browser-fixture.mjs';

const assets = 'app/src/main/assets/';
const fixture = await browserFixture();
const { page } = fixture;
const presentation = readFileSync(assets + 'web-integration/page.js', 'utf8');
const requests = [];

try {
  await page.route('**/api/dsha-launch-target', async route => {
    const request = route.request();
    const target = request.method() === 'POST' ? request.postDataJSON().target : 'web';
    requests.push({ method: request.method(), target });
    await route.fulfill({ json: { ok: true, target } });
  });
  await fixture.load('dsh-client-locale');
  await page.addScriptTag({ content: readFileSync(assets + 'builtin-plugins/dsh-launch-target/lib/client.js', 'utf8') });
  await page.addScriptTag({ content: presentation });
  await page.evaluate(() => {
    const React = auditModules.react;
    const { createRoot } = auditModules['react-dom/client'];
    const { LocaleRuntime } = auditExports['@deepseek-ai/dsh-client-locale'];
    const plugin = auditExports['dsh-launch-target'];
    const ctx = {
      effect: run => run(), emit() {},
      slots: { inject: (_, run) => run(), register() {} }
    };
    const locale = ctx.locale = new LocaleRuntime(ctx);
    for (const id of ['zh-Hant', 'ru', 'ja', 'ko', 'ar', 'pt', 'la', 'fr']) {
      if (!locale.getSnapshot().locales.some(entry => entry.id === id))
        locale.addLanguage({ id, label: id, fallback: id === 'zh-Hant' ? 'zh' : 'en' });
    }
    locale.setLocale('en');
    plugin.apply(ctx);
    const t = locale.bind(plugin.NS);
    function Settings() {
      const localeState = React.useSyncExternalStore(
        callback => locale.subscribe(callback), () => locale.getSnapshot());
      return React.createElement('div', { dir: localeState.active === 'ar' ? 'rtl' : 'ltr' },
        React.createElement('textarea', { 'data-draft': '', defaultValue: 'An unsent draft' }),
        React.createElement(plugin.LaunchTargetSection, { t }));
    }
    window.launchTargetTest = { locale, reports: [] };
    createRoot(document.querySelector('#root')).render(React.createElement(Settings));
    history.pushState({ session: 'example' }, '', '#example');
  });
  const action = page.locator('[data-dsha-return-home]');
  const card = page.locator('[data-dsha-launch-target="web"]');
  await card.waitFor();
  assert.equal(await action.count(), 0, 'normal browser has no native action');
  await page.evaluate(() => {
    window.DshaTheme = { postMessage: text => launchTargetTest.reports.push(JSON.parse(text)) };
  });
  assert.equal(await action.count(), 0, 'native channel alone is insufficient without the live page binding');
  const binding = { nonce: 'a'.repeat(32), instance: 'web-instance', generation: 7 };
  await page.evaluate(binding => {
    launchTargetTest.originalDraft = document.querySelector('[data-draft]');
    launchTargetTest.history = history.length;
    __dshaNativePresentation.install(binding);
  }, binding);
  await action.waitFor();
  assert.equal(await action.innerText(), 'Return to DSHA home');

  // 合成点击不触发原生消息。普通点击和键盘使用真正的 DOM 用户事件。
  const homeReports = () => page.evaluate(() => launchTargetTest.reports.filter(report => report.type === 'return-home'));
  await action.dispatchEvent('click');
  assert.deepEqual(await homeReports(), []);
  await action.click();
  await action.press('Enter');
  const reports = await homeReports();
  assert.equal(reports.length, 2);
  assert.ok(reports[1].sequence > reports[0].sequence);
  for (const { sequence, ...report } of reports) assert.deepEqual(report, { ...binding, type: 'return-home' });
  assert.deepEqual(requests, [{ method: 'GET', target: 'web' }]);
  assert.deepEqual(await page.evaluate(() => ({
    draft: document.querySelector('[data-draft]').value,
    sameDraft: document.querySelector('[data-draft]') === launchTargetTest.originalDraft,
    historyIntact: history.length === launchTargetTest.history && history.state.session === 'example'
  })), { draft: 'An unsent draft', sameDraft: true, historyIntact: true });
  await page.getByRole('tab', { name: 'Main screen', exact: true }).click();
  await page.locator('[data-dsha-launch-target="main"]').waitFor();
  assert.deepEqual(requests, [{ method: 'GET', target: 'web' }, { method: 'POST', target: 'main' }]);
  assert.deepEqual(await homeReports(), reports);

  const labels = new Set();
  for (const locale of ['zh', 'zh-Hant', 'en', 'ru', 'ja', 'ko', 'ar', 'pt', 'la', 'fr']) {
    await page.evaluate(locale => launchTargetTest.locale.setLocale(locale), locale);
    const label = await page.evaluate(() => launchTargetTest.locale.bind('dsha.launchTarget')('returnHome'));
    await page.getByRole('button', { name: label, exact: true }).waitFor();
    assert.match(label, /DSHA/);
    labels.add(label);
  }
  assert.equal(labels.size, 10);

  // 窄屏、大字和 RTL 可换行，旋转后控件还在同一设置面板。
  await page.evaluate(() => {
    launchTargetTest.locale.setLocale('ar');
    document.documentElement.style.setProperty('--dsh-content-font-size-secondary', '32px');
  });
  for (const viewport of [{ width: 300, height: 700 }, { width: 700, height: 300 }]) {
    await page.setViewportSize(viewport);
    await action.waitFor();
    const box = await action.boundingBox();
    assert.ok(box.height >= 44 && box.x >= 0 && box.x + box.width <= viewport.width);
    assert.equal(await action.evaluate(button => getComputedStyle(button).fontSize), '32px');
    assert.equal(await action.evaluate(button => getComputedStyle(button).direction), 'rtl');
  }

  await page.evaluate(() => document.body.appendChild(document.createElement('iframe')));
  const frame = page.frames().find(candidate => candidate.parentFrame() === page.mainFrame());
  await frame.addScriptTag({ content: presentation });
  assert.equal(await frame.evaluate(binding => {
    window.DshaTheme = { postMessage() { throw Error('iframe sent a native message'); } };
    __dshaNativePresentation.install(binding);
    return __dshaNativePresentation.canReturnHome();
  }, binding), false);
  assert.deepEqual(fixture.errors, []);
  console.log('PASS_LAUNCH_TARGET_BROWSER: native availability, origin frame, trusted clicks, ten locales, font scaling, rotation, draft and history');
} finally {
  await fixture.close();
}
