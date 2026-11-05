# 内置移动插件 dsh-web-mobile 的 DSHA 本地补丁

> **当前状态（build327 起）**：该插件默认禁用，**覆盖更新后也会再次收敛为禁用**（只有当前安装内
> 从原生插件页显式启用才生效），原因是大量代码不适配新页面与新逻辑、已造成 build324 白屏 /
> 侧栏重复事故。原因、临时启用方法与恢复默认启用的硬门槛见[插件页的默认禁用一节](plugins.md)。
> 本页记录的补丁仍然保留，启用插件后照常生效；在恢复默认启用前不要删除本页所述的修复。

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

## 2026-10-31：宽屏触屏档会话行收起归档/置顶（`DSHA_WIDE_TOUCH_ROW_ACTIONS_V1`）

### 现象与根因

内置 3.2.2 的 `@media (min-width: 1024px) and (pointer: coarse)` 档把会话行
`_rowActions` 常显，并把 ⋯ / 归档 / 置顶三颗按钮放大到 40×40（gap 4）；宿主只在
`:hover` / `menuOpen` 时隐藏 `.time`，触屏没有 hover，于是 280px 侧栏里同时常显
「时间 + ⋯ + 归档 + 置顶」，标题可用宽只剩约 55px（实测 1024×640 / dpr 2.5）。
手机壳（≤1023）自 #154 起已按 aria-label 收起归档/置顶，宽屏触屏档缺同一条规则。

### 上游核验（2026-10-31）

- issue [#255](https://github.com/mexiaosqwq/dsh-web-mobile/issues/255)；
  PR [#256](https://github.com/mexiaosqwq/dsh-web-mobile/pull/256)（OPEN，SunsetRNE，
  分支 `fix/255-wide-touch-row-actions`，含源码、测试、重建的 `lib/`）。
- 固定上游 `bb0e5e15` 仍无该规则；`layout.css.ts:3469` 只作用于手机壳 frame。
- 上游维护窗口暂停，DSHA 先打本地补丁；PR 合并后摘除。

### 补丁位置与规则

[apply-mobile-client-patches.mjs](../tools/apply-mobile-client-patches.mjs) 在宽屏触屏档
`①′` 热区规则之后、`① 隐藏「更多视图」` 之前做锚点替换，注入带
`@patch id: wide-touch-row-actions` 与产物标记 `DSHA_WIDE_TOUCH_ROW_ACTIONS_V1` 的规则：

```css
[class*="sessionRow"] [class*="_rowActions"] :is(
  [aria-label="归档会话"], [aria-label="取消归档"],
  [aria-label="置顶会话"], [aria-label="取消置顶"],
  [aria-label="Archive session"], [aria-label="Unarchive session"],
  [aria-label="Pin session"], [aria-label="Unpin session"]
) {
  display: none !important;
}
```

只命中 ≥1024 + 粗指针的会话行；⋯ 仍保留 40×40 热区，归档/置顶/分叉/删除仍在 ⋯ 菜单。

### 验证

```bash
node tools/apply-mobile-client-patches.mjs tools/mobile-plugin-upstream/lib/client.js --check
node tools/test-mobile-client-patches.mjs
python3 tools/generate-builtin-plugins.py --verify
python3 tools/check-source-hygiene.py
```

`test-mobile-client-patches.mjs` 断言：规则位于 ≥1024 档内、名单与手机壳逐字一致
（8 个 zh/en 标签）、`display:none !important`。真机前后量测等包含本补丁的 APK 安装后
补写，指标见[平板会话行挤压记录](web-mobile-tablet-rows-20261031.md)。

### 退出条件

内置上游同步到包含 PR #256 的提交后：删除 `wide-touch-row-actions` 整块、对应测试分支与
本文件该段，重新生成 `lib/client.js` 并核验。

## 2026-11-05：侧栏 footer 齿轮让位改实测宽度（`DSHA_GEAR_FIT_V1`）

### 现象与根因

build324 真机覆盖安装后主界面白屏、侧栏 footer 出现「重复叠加」观感；禁用内置
`dsh-web-mobile` 后两个现象都消失。补丁链侧取证（[build324 白屏排查](releases/v0.2.1-rc.5-build324-blank-screen-20261105.md)）
确认 323→324 的运行树只差两条补丁，而手机上唯一可见差异是：手机是 drawer 模式
（`viewport < 768`），`wide` 因此为真，宿主 `ConnectionIndicator` 本来真实渲染；T58
（`first-connect-indicator-patch.json`）让首次连接不再渲染指示灯。

上游 `dsh-web-mobile` 3.2.2 的 `drawer-quick-settings-chip-guard` 恰好用
`:has(> button[data-phase], > [role="status"])` 判断「这一行里有没有指示灯」来决定是否
让出 footer 齿轮（该行只有 260px，连接条中文约 136px、英文约 190px）。指示灯消失后
判据永远得到「放得下」：插件的渲染因此**依赖宿主某个子节点存在**，宿主一改首次连接的
渲染方式，让位口径就失准。

### 补丁位置与规则

[apply-mobile-client-patches.mjs](../tools/apply-mobile-client-patches.mjs) 用四处锚点替换：

- 守卫规则改为只认插件自己的属性，不再 `:has()` 宿主节点：
  `[data-mobile-nav="quick-settings-wrap"][data-mobile-nav-quick-fit="0"] { display: none !important; }`
- 新增纯判据 `dshaGearFits(rowWidth, usedWidth)`（导出给宿主测试）：行内容宽度 − 其它
  可见孩子宽度 ≥ `GEAR_SLOT_PX`（40 = 按钮 30 + 右外边距 6 + 4 余量）才放得下；行宽
  不可用（≤ 0 / 非有限数）按「放不下」处理，保守保留账户菜单里的设置项。
- `effects/drawer-quick-settings` 的 `gearAvailable()` 改为实测行宽与其它可见孩子的
  宽度和（含外边距，跳过 `display:none` / `visibility:hidden`），结论幂等写入
  `data-mobile-nav-quick-fit`；`syncMenuEntry` 用同一个结论决定是否收起账户菜单设置项，
  保证任何时刻只有一条设置入口。

产物标记 `DSHA_GEAR_FIT_V1`，位于 `lib/client.js` 的
`effects/drawer-quick-settings.js` 模块与 CSS；上游文件本身不改。

### 验证

```bash
node tools/apply-mobile-client-patches.mjs tools/mobile-plugin-upstream/lib/client.js --check
node tools/test-mobile-client-patches.mjs
node --test tools/test-mobile-gear-contract.mjs
python3 tools/generate-builtin-plugins.py --verify
python3 tools/check-source-hygiene.py
```

`test-mobile-gear-contract.mjs` 用最小 DOM 语义跑真实产物：**没有指示灯时**齿轮正常注入并
标记放得下；指示灯出现后按实测宽度让位、消失后恢复；行宽不足时保守让位；重复 `ensure`
不重复注入。真机验收（build325 覆盖安装）由主会话装测试包后按实际结果补写。

### 退出条件

宿主 `settings.launcher` 槽位提供独立的次要动作位（或连接条不再占用该行宽度）后，删除
`drawer-quick-settings-fit-guard` 整块与 `DSHA_GEAR_FIT_V1` 判据，重新生成 `lib/client.js`
并核验；届时本段一并删除。
