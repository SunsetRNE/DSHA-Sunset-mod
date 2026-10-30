# 内置移动插件 dsh-web-mobile 的 DSHA 本地补丁

本文记录 [`tools/apply-mobile-client-patches.mjs`](../tools/apply-mobile-client-patches.mjs) 对固定上游
`dsh-web-mobile` 3.2.2（[`bb0e5e151ca549fef506e829020a5d7138074711`](https://github.com/mexiaosqwq/dsh-web-mobile/commit/bb0e5e151ca549fef506e829020a5d7138074711)，
2026-10-31 同步；上游已把 v3.2.2 标签重指到该提交）
的本地差异。上游产物不手改；改动写进补丁脚本，再生成
[`app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js`](../app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js)。

## 2026-10-30：rc.5 设置弹层字体族行排版（`DSHA_FONT_FAMILY_ROW_V1`）

### 现象与根因

真机（Android WebView，DSH 0.2.1-rc.5 + dsh-web-mobile 3.2.2 手机 sheet）打开
「设置 → 通用设置 → 更多字体设置」后，「正文字体 / 代码字体 / 终端字体」每行右侧固定
240px 输入框，左侧标题+说明被压成约 1 字/行，设置弹层被纵向撑高。

宿主 `@deepseek-ai/dsh-client-ui-theme` 的 `FontFamilyRow` 结构（0.2.1-rc.5，已按
`app/build/locked-dsh-runtime` 的固定运行时核对）：

```html
<div class="bVCLcG_row">                          <!-- .row: flex, 不换行 -->
  <div class="bVCLcG_rowText">…标题 + 说明…</div>   <!-- flex:1, padding-right:48px -->
  <span class="_wrap_14sgc_1 vnGTiq_field">        <!-- .field: flex:0 240px -->
    <input spellcheck="false" autocomplete="off" maxlength="200">
  </span>
</div>
```

关键点：rc.5 的 primitives `Input` 把原生 `<input>` **包在 `<span class="_wrap…">` 里**，
`FontFamilyRow` 的 `className`（`vnGTiq_field`，即 240px 规则）落在这个 wrapper 上，
输入框本身不是行的直接子节点。手机 sheet 内容宽约 340px，扣掉 240px 控件与
`rowText` 的 48px 右内距后，文字列几乎没有可用宽度。

### 上游核验（2026-10-31）

- GitHub `mexiaosqwq/dsh-web-mobile` 的 `main` 已从 `04dcd19f…` 前进到
  [`bb0e5e151ca549fef506e829020a5d7138074711`](https://github.com/mexiaosqwq/dsh-web-mobile/tree/bb0e5e151ca549fef506e829020a5d7138074711)：
  9 个改动提交（另有 1 个合并提交）集中在全屏右侧面板层带 / 底色、宠物悬浮层遮罩与命名根节点、文件面板手势标记
  （#247 / #248 / #249）；`v3.2.2` 标签当前也指向该提交。0 个 open PR。
- 固定上游 `lib/client.js` 中 `bVCLcG` / `rowText` / `FontFamily` 命中数仍为 0，
  即插件没有针对该字体行的移动端规则；本次上游改动没有吸收本补丁。
- 上游 open issue #252、#231 是会话输入框（composer）与按钮间距问题，与本字体行无关。
- 本次同步按新上游字节重放全部本地补丁，client 的各处补丁锚点、file-transfer、locale 与
  server 补丁均保持唯一；`prepare-mobile-plugin.py --write` 重新生成产物并通过摘要核验。

结论：上游暂无此修复，DSHA 需要本地 CSS 补丁。

### 补丁位置与规则

`tools/apply-mobile-client-patches.mjs` 注入的 CSS 模板新增
`@patch id: font-family-row-wrap-rc5` 与产物标记 `DSHA_FONT_FAMILY_ROW_V1`，只在
`@media (max-width: 1023px) and (pointer: coarse)` 下生效：

- 作用域：`[aria-modal="true"][data-shortcut-modal="settings"]`，只进设置弹层；
- 行判定：`:has(> input[spellcheck="false"][autocomplete="off"][maxlength="200"])`
  以及真实 rc.5 DOM 的 `:has(> span[class*="_wrap"] > input[…同上…])`；
- 命中后：行 `flex-wrap: wrap; row-gap: 8px`，`rowText` 与控件各占一行
  （`flex:1 1 100%`），文字列 `padding-right:0`。

选择器依据（均在固定 rc.5 运行时核对）：

- 扫描 `node_modules/@deepseek-ai/**` 全部 client bundle，`maxLength: 200` 只出现在
  `dsh-client-ui-theme` 的 `FontFamilyRow`；`spellcheck=false` + `autocomplete="off"`
  进一步限定到该输入框。
- 字号行（正文字号 / 代码字号 / 终端字号）是 stepper 结构，没有 `input`，不会命中；
- `:has` 保持单层。插件仓库有过「嵌套 `:has()` 整条被 Chromium 丢弃」的教训。

### 验证

```bash
node tools/test-mobile-client-patches.mjs
python3 tools/generate-builtin-plugins.py --verify
python3 tools/check-source-hygiene.py
```

`test-mobile-client-patches.mjs` 断言产物含 `DSHA_FONT_FAMILY_ROW_V1`、两种 DOM 形态的
选择器齐全、同一选择器只有单层 `:has`，且部署产物与 `applyMobileClientPatches()` 输出
逐字节一致。

另用 rc.5 固定前端（真实 React / `dsh-web-mobile` 产物 / 真实 theme client）在 Chromium
无头环境复现并量测（本地验证，不进仓库）：

- 设置弹层内 3 个字体行：`flex-wrap:wrap`，文字列 328px、控件 328px、无横向溢出；
  未打补丁的同一结构文字列 92px、控件 240px；
- 3 个字号行保持 `nowrap`、无 input；
- `data-shortcut-modal="shortcuts"` 的同类结构保持 `nowrap`；
- 视口 1280px 时设置弹层内字体行保持 `nowrap` 与 240px 控件。

### 真机验收（build214，2026-10-30）

在 OnePlus PJD110 / Android 16（1440×3168，4.0×，360dp 逻辑宽）安装 build214 正式包后实测
「设置 → 通用设置 → 更多字体设置」：

- 正文字体：说明 `[80,1928,1340,2072]` 整宽两行；输入框 `[112,2120,1308,2216]` 独占下一行；
- 代码字体：说明 `[80,2464,1340,2612]` 整宽；输入框 `[112,2656,1308,2756]` 独占下一行；
- 代码字号等 stepper 行保持原布局，未命中补丁；
- 「终端字体」行在可视区下方：WebView 内容无障碍滚动返回 `NODE_SCROLL_REGION_UNAVAILABLE`，
  主屏截图被系统悬浮窗标记遮挡、不签发坐标触控，未单独滚入目视；该行与已实测两行共用同一
  `DSHA_FONT_FAMILY_ROW_V1` 规则，无头三行实测覆盖。

完整记录见[build214 真机验收记录](releases/v0.2.1-rc.3-build214-acceptance-20261030.md)。

### 重新生成与退出条件

```bash
node tools/apply-mobile-client-patches.mjs tools/mobile-plugin-upstream/lib/client.js --write
```

退出条件：上游给字体行提供手机档换行排版，或宿主不再把 `.field` 固定成 240px；
届时删除 `font-family-row-wrap-rc5` 整块并重新生成产物。
