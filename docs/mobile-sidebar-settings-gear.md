# 移动壳侧栏设置齿轮：单实例不变式

> 核验日期：2026-11-12。基线 build359 / `dsh-web-mobile` 3.3.5 / DSH 0.2.2-rc.1。
> 2026-11-12 真机（Lenovo TB321FU，1024×640 CSS、2.5×）在项目还原后出现 **3 颗**设置齿轮；
> 同机同版本重新加载页面只有 **1 颗**。根因不是三份补丁文件，而是页面里同时存活着多个插件
> 任务实例（项目还原 / 热重载 / 运行时重复登记），每个实例各 append 了一颗齿轮。

## 现在长什么样

侧栏 footer 的 `triggerRow` 里只有一条设置入口：

- 账户 launcher（宿主 `settings.launcher`，即头像 + 账号）→ 菜单第一项「设置」；
- 插件只补 **一颗** 一键齿轮：手机/抽屉走 `effects/drawer-quick-settings.ts`，宽屏触屏
  （≥1024px 且粗指针）走 `effects/tablet-quick-settings.ts`，两者复用同一个
  `createDrawerQuickSettingsTask`，在同一宽度断点只会武装一个。

正常状态下 `document.querySelectorAll('[data-mobile-nav="quick-settings"]').length === 1`。

## `DSHA_GEAR_SINGLETON_V1`（不要删）

`tools/apply-mobile-client-patches.mjs` 在齿轮 `ensure()` 挂载前清掉同一 `triggerRow` 里其它
实例的 `[data-mobile-nav="quick-settings-wrap"]`，只保留本实例这一颗；最后一个 ensure 的实例
接管。只动插件自己的节点，不碰宿主控件。这样即使插件被重复应用，页面上也始终最多一颗齿轮。

**硬性约定：**

- 不要删除这段守卫，也不要再加第二颗设置齿轮的补丁。
- 不要把 `dsh-web-mobile` 在 profile 的 `dsh.profile.bundles`、`cordis.patch.yml` 或
  `builtin-plugins.json` 里登记两次；它只能有一条装载路径。
- 不要用「禁用内置插件」来修复重复齿轮——那会同时失去移动壳和设置快捷入口，且真机
  build324 的「侧栏重复」事故里禁用插件只是隐藏症状。
- 不要把这条守卫扩展到宿主节点或 `:has()` 判据；齿轮可见性仍由 `DSHA_GEAR_FIT_V1` 的实测宽度决定。

## 再出现 3 颗时怎么查

1. 先刷新 / 重进主界面。还原或热重载留下的旧实例通常随整页重载消失；刷新后仍为 3 颗才继续查。
2. 页面控制台确认：
   `document.querySelectorAll('[data-mobile-nav="quick-settings"]').length` 与
   `document.querySelectorAll('[class*="triggerRow"]').length`；后者应始终为 1。
3. 确认 profile 只有一份 `dsh-web-mobile`：
   `python3 - <<'PY' …` 或 `grep -n dsh-web-mobile` 于
   `$DSH_HOME/profiles/web/{package.json,cordis.patch.yml,node_modules}`；
   页面 `__DSH_BOOT__.entries` 里也应只有一条 `dsh-web-mobile`。
4. 若三颗来自三个真正不同的装载路径，删掉多余路径，而不是再加补丁或 CSS 隐藏。

## 验证

```bash
node tools/test-mobile-gear-contract.mjs
# 7 项通过，其中「多个实例同时存活时同一行只保留一颗齿轮」直接覆盖本守卫

node tools/apply-mobile-client-patches.mjs tools/mobile-plugin-upstream/lib/client.js --check
# 产物必须与固定上游 + 本地补丁一致

python3 tools/prepare-runtime-descriptor.py --check
python3 tools/generate-third-party-notices.py --check
# 两者需要固定输入（CI/构建机）；改过插件产物后运行 --write/默认写入并审阅
```

## 与既有文档的关系

- 齿轮让位契约：`docs/web-mobile-patches.md` 的 `DSHA_GEAR_FIT_V1`。
- 覆盖升级与插件依赖宿主控件的事故：`docs/overwrite-upgrade-and-maintenance.md`。
- 本文件只新增「同一行单实例」这一条，不改动上游插件的两级安装器设计。
