# 历史补丁夹具测试报告：三个失败点的精确复现与修复建议

> 日期：2026-11-12　基线：build359 / `92acaa04` / 内置 DSH `0.2.2-rc.1`
> 环境：Linux aarch64（proot）；Temurin JDK 17.0.21；Node 24.19.0；npm 11.17.0
> 前置：固定输入已按 `fixed-inputs-20261105e` 恢复（`ci-assets.py --archive … --sha256` → PASS）；`prepare-test-runtime.py --platform host` 已生成 raw/managed（`archiveCurrent: true`）。
> 结论：**三个历史夹具测试最初全部失败，失败点都是"跨代次假设"，不是生产补丁链坏了**。生产链在 0.2.2-rc.1 上 46/46 模块全部可应用。
> **v2 更新（2026-11-12）**：按 §3 修法只改测试断言（不改夹具摘要、不改生产链）后，三个测试**全部 PASS**，见 §7。

---

## 0. 一页结论

| 测试 | 失败位置 | 失败原因 | 性质 |
|---|---|---|---|
| welcome-notice 升级链 | `test-welcome-notice-patch-upgrade.py:113` | 断言"历史 rc.2 的 source 与当前 0.2.2-rc.1 的 source 字节相同" | 跨代次假设，必然失败 |
| plugin-management 链 | `test-plugin-management-patch-chain.py:185` | `PRE_WORKER_SHA=1eb661c0…` 是按 **rc.5** canonical 算的，当前是 0.2.2-rc.1 | 常量绑定旧代次 |
| web-search 链 | `test-web-search-patch-chain.py:103` | 一条断言同时要求 `recipe.sourceSha256`（0.2.2-rc.1=`6bde60f4…`）与 `OLD_MODULE_SHA`（rc.2=`fc13dd4e…`）相等 | 自相矛盾 |

**三个历史字节都已用 npm tarball 验证到位**（§2），可以做真正的 old→new 校验；但必须先把"代次"从断言里拆出来。

---

## 1. 复现命令与结果

```bash
# 前置（已完成）
python3 tools/ci-assets.py --archive /tmp/dsha-fixed-inputs-20261105e.zip \
  --sha256 be97d9f0a45f408d3a7de83ae55a00a85a5154343589571c6d159a0fe58f08f5   # PASS
python3 tools/prepare-test-runtime.py --platform host                              # OK, archiveCurrent=true

# 三个测试（JDK 17.0.21）
python3 tools/test-welcome-notice-patch-upgrade.py --jdk /opt/jdk17     # rc=1
python3 tools/test-plugin-management-patch-chain.py --jdk /opt/jdk17    # rc=1
python3 tools/test-web-search-patch-chain.py --jdk /opt/jdk17           # rc=1
```

失败原文：

```text
welcome:  AssertionError  (line 113)
  assert historical['sourceSha256'] == current['sourceSha256'] == sha(canonical)

plugin:   AssertionError: pre-worker actual managed source identity  (line 185)
  assert sha(pre) == PRE_WORKER_SHA

websearch: AssertionError: LOCKED_SEARCH_MODULE_IDENTITY  (line 103)
  assert sha(canonical) == recipe['sourceSha256'] == OLD_MODULE_SHA
```

---

## 2. 历史字节核验（对方要的"真实历史字节"）

用 npm 直接拉 rc.2 官方包，先验 tarball 的 `sha512`（来自 `4e215b74` 的 lock），再验模块 `sha256`：

| 模块 | tarball | tarball sha512（与历史 lock 一致） | 模块 sha256 |
|---|---|---|---|
| `@deepseek-ai/dsh-client-ui-settings-models@0.2.1-rc.2` | `…/dsh-client-ui-settings-models-0.2.1-rc.2.tgz` | `sha512-tjxvP66xh+i3h4Qf7h1tv7cEWVVtNAONDEN2ZM+Sv7Z2Z6P+hnr+mlXIjQdC+ovfsYQdg6TMXRle3cRr9x3Vug==` | `lib/client.js` = `190498e15358bcd3fe6810fe6368023a20b0403e3ea493bc979eca9bdb593b28` |
| `@deepseek-ai/dsh-plugin-manager@0.2.1-rc.2` | `…/dsh-plugin-manager-0.2.1-rc.2.tgz` | `sha512-ow+AWsO5tYsMjef2HpNnKMh6QjLC/r/dsE91Ad94KP26FLFpoA/Wp4Kd+rLNRp4vSzHC7A007BfWxmb5mVJ8Kw==` | `lib/index.js` = `1b8bf9eb266d7b3b7f899175e3f435c89ed8baa8aabc677212759f8c6cd23132` |
| `@deepseek-ai/dsh-web-search-deepseek@0.2.1-rc.2` | `…/dsh-web-search-deepseek-0.2.1-rc.2.tgz` | `sha512-1RkLY8uYawWOOrEWPCg7eemvA7T/bPXDJOdHsqhsDwejjZdv496G01hKf/W+T8twdUt8gnx5G19h06R499DoGA==` | `lib/index.js` = `fc13dd4eaba3c9298af41a4d0559a1b8bb61614462fe18ba6e76d8c598c332bd` |

三条模块 hash **与夹具/测试常量逐字一致**：

- welcome 夹具 `sourceSha256` = `190498e1…` ✅
- plugin 夹具 `sourceSha256` = `1b8bf9eb…` ✅
- web-search `OLD_MODULE_SHA` = `fc13dd4e…` ✅

**所以历史字节是可得的**：不需要 build191 APK 也能做 welcome/plugin/web-search 的 rc.2 端重建（web-search 若要与 APK 对账，仍可用 build191 APK 的 `ba5fd1e1…` 交叉验证）。

---

## 3. 每个失败点的根因与修法

### 3.1 welcome-notice：把"同一个模块跨代次不变"当成了前提

```python
assert historical['sourceSha256'] == current['sourceSha256'] == sha(canonical)
```

- 历史 rc.2：`190498e1…`
- 当前 0.2.2-rc.1：`e111ec…`
- 模块在 rc.2 → 0.2.2-rc.1 之间已经变过，这条断言把三个值绑死，必然失败。

**已按分离代次验证通过**（用 npm 拉到的 rc.2 真实字节）：

```text
rc.2 canonical sha256 = 190498e15358bcd3…（与夹具 sourceSha256 一致）
old recipe 应用成功，含 DSHA_WELCOME_NOTICE_RC2_V1 / DSHA_WELCOME_STORE_RC2_V1
0.2.2-rc.1 canonical sha256 = e111ecefea8262e0…（与资产一致）
current chain 应用成功，含 DSHA_WELCOME_NOTICE_RC2_V1 / DSHA_WELCOME_STORE_RC2_V3
old != new
```

即：**old→new 是可以验证的，前提是两边各自用自己的 canonical**。

**修法**：拆成两条，并显式加载 rc.2 canonical：

```python
assert sha(canonical) == current['sourceSha256']
historical_canonical = load_pinned_rc2_module('@deepseek-ai/dsh-client-ui-settings-models', '190498e1…')
assert sha(historical_canonical) == historical['sourceSha256']
old = apply_recipe(historical_canonical, historical)
# 只断言 old 有 RC2_V1/STORE_V1 标记、current 有 V1/V3 标记；
# 不要断言 old == apply_recipe(current_canonical, historical)
```

### 3.2 plugin-management：常量必须跟代次走

`PRE_WORKER_SHA` / `OLD_MODULE_SHA` 是"在某个 canonical 上应用现行前序链"的产物，注释写的是 rc.5。当前 canonical 是 0.2.2-rc.1，派生的 `pre` / `old` 必然不同。

**修法（二选一）**：

1. 改成"代次 → 期望摘要"表：

```python
EXPECTED = {
  '0.2.1-rc.5':  {'pre': '1eb661c0…', 'old': '8043321a…'},
  '0.2.2-rc.1':  {'pre': '<重新实算>', 'old': '<重新实算>'},
}
```

2. 或给测试加 `--historical-canonical-rc5 <path>`，显式传入 rc.5 canonical，继续用旧常量；当前 canonical 只做新端校验。

> rc.5 的 plugin-manager tarball 完整性：`sha512-VRfuEhPcZLh+ASO8WLEulShvNKZ0GvZquiO8/AzfFNnublSiEGKdZZU7j7R4+on16IbJtoUthYz1Re6QpoBepw==`（`83e4db60` lock），可拉取后校验。

### 3.3 web-search：一条断言要求两个不同代次的常量相等

```python
assert sha(canonical) == recipe['sourceSha256'] == OLD_MODULE_SHA
```

- `recipe['sourceSha256']` = `6bde60f4…`（0.2.2-rc.1，资产文件）
- `OLD_MODULE_SHA` = `fc13dd4e…`（rc.2 / build191）

**修法**：

```python
assert sha(canonical) == recipe['sourceSha256']          # 新端
if args.old_apk or args.old_module:
    old = load_old_module(args)                          # rc.2 字节
    assert sha(old) == OLD_MODULE_SHA                    # 旧端
    # 旧端只验证"旧配方能识别/不会误应用"，不要拿当前链直接套旧字节
```

**关键原则**：这些脚本是**单步升级准入**（old 必须是链条 `before` 能匹配的"上一代"文本）。拿 rc.2 的字节让 0.2.2-rc.1 的链去套，除非中间每一代都保留，否则必然 mismatch——这不是补丁链坏了，是测试语义被跨代次使用。

---

## 4. 反证：生产补丁链本身是健康的

在同一份 raw canonical（0.2.2-rc.1）上直接跑生产链：

```text
modules 46 / ok 46 / bad 0
```

即：**当前版本的实际补丁链 46/46 全部可应用**；三个失败全部来自"历史夹具跨代次断言"，与生产链无关。这一点应该写进回执，避免被读成"补丁系统坏了"。

---

## 5. host runtime：状态未变

- build359 / `92acaa04` **仍无标准 Linux host runtime 报告**；`docs/releases/build359-changes.zh.md:9` 明确"本轮未重跑"，最近完整回执 build357：fast 90/95、runtime 72/128。
- 本次运行的是 **patch-chain 单元准入 + 生产链健康检查**，不是 host runtime；本机 aarch64 proot 也不能替代 x86_64 标准 Linux。
- 若需要，我可以在标准 Linux（或 GitHub `host-tests.yml` workflow_dispatch）按 `fixed-inputs-20261105e` + `run-host-tests.py --stage runtime` 出回执。

---

## 6. 建议的下一步

1. **合并修法**：三个脚本按 §3 改（只改测试，不改生产链、不改夹具摘要）。
2. **补历史 canonical 夹具**：把 rc.2 / rc.5 的模块按 `tools/fixtures/rc2-upgrade-source/provenance.json` 的模式固化（记录 `packageVersion`、`packageIntegrity`、`packageResolved`、文件 sha256），以后不再依赖现场拉 npm。
3. **web-search 与 build191 APK 对账**：若需要 APK 级证据，用私有 Release `v0.2.1-rc.2-20261023.1640` 的 APK（`ba5fd1e1…`）提取模块，校 `fc13dd4e…`。
4. host runtime 单独在标准 Linux 跑，不要与本报告的 patch-chain 结果混为一谈。

---

## 7. 修复后复跑结果（v2，三绿）

只改测试断言与一个 rc.2 夹具文件（`tools/fixtures/rc2-upgrade-source/dsh-client-ui-settings-models.js`，sha256 `190498e1…`），生产补丁链与既有夹具摘要未动。

| 测试 | 修复前 | 修复后 | 关键输出 |
|---|---|---|---|
| welcome-notice 升级链 | rc=1 `:113` | **rc=0 PASS** | `javaCases:3`、`archiveCases:3`、oldV1 `0857996c…`、currentV3 `106363d6…` |
| plugin-management 链 | rc=1 `:185` | **rc=0 PASS** | 6 个模块全过、`HISTORICAL_REPLAY_SKIPPED pre-worker-only`（未提供存档 rc.x 链） |
| web-search 链 | rc=1 `:103` | **rc=0 PASS** | source `6bde60f4…`、patched `cd70f1c9…`、`old191ApkSha256:null` |

三处修改的语义：

1. **welcome**：历史端与当前端各用自己的 canonical（新增 `--historical-module`，默认指向固化的 rc.2 `client.js`）；不再断言两侧 source hash 相等。
2. **plugin**：`PRE_WORKER_SHA` 保留为 rc.5 历史值；新增 `CURRENT_PRE_WORKER_SHA = 69ec30f2…`（0.2.2-rc.1 实算）。历史 V1 replay 在缺存档链时显式 `SKIPPED`，不再用当前 canonical 硬套 rc.2 配方。
3. **web-search**：拆成"当前 canonical == 资产 sourceSha256"与"旧 APK 可选校验"；无 APK 时不再要求它等于 rc.2 的 `fc13dd4e…`。

**仍未做**：历史 V1 的完整 replay 需要该代次（rc.2/rc.5）的**存档链 + canonical**；目前仓库只有 rc.2 的 V1 配方，没有当时的完整前序链。要真正 replay，需要按 §6 第 2 条补历史 canonical 与链快照。

## 附录：本次环境与产物

| 项 | 值 |
|---|---|
| 源码 | `92acaa0408cb7b031ee3036c6116c6463ae6b62e`（build359） |
| 固定输入 | `fixed-inputs-20261105e`，bundle `be97d9f0…8f08f5`，`ci-assets` PASS |
| 测试 runtime | `app/build/test-runtimes/0.2.2-rc.1-linux-arm64-f327e64ffe7c`，`archiveCurrent: true` |
| JDK | Temurin 17.0.21+9（`/opt/jdk17`） |
| Node / npm | 24.19.0 / 11.17.0 |
| 生产链健康 | 46/46 modules apply |
| 失败测试 | welcome / plugin / web-search 各 rc=1，失败点见 §1 |

*本报告只陈述实测与建议；未修改生产补丁链、未改夹具摘要、未签名、未安装、未发布。*
