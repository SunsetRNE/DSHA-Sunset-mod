# 莫奈取色（Monet）设计说明

> 状态：实现已完成，**真机验证待补**。本文区分「本机已验证」与「待真机验证」，不要当成验收结论。
> 相关代码：`util/DynamicPalette`、`util/PaletteSeedExtractor`、`util/ResourcesTable`、
> `ui/MonetPalette`、`ui/ThemePaletteOverride`、`ui/ThemeController`、`ui/ThemeActivity`。

## 1. 为什么自己做取色，而不用系统动态色

Android 12 起有 `android.R.color.system_accent1_*` 那套动态色（Material You 的代号就是 Monet），
但**只有真正实现 Material You 的 ROM 才让它随壁纸更新**。

实测（Xiaomi 25113PN0EC / HyperOS / Android 17）：

| 检查项 | 结果 |
|---|---|
| `/system/overlay` | 不存在 |
| `/data/overlay`、`/data/system/overlay`（AOSP 运行时 RRO 的落点） | 都不存在 |
| `/product/overlay` 里的 `com.android.theme.color.*` | 一个都没有 |
| `Settings.Secure` 的 `theme_customization_overlay_packages` | 键存在，但没有任何 color overlay 包 |

结论：这台机器上读 `system_accent1_*` 得到的是 framework 静态默认值，**不随壁纸变化**。

其他厂商同样指望不上：OPPO ColorOS、vivo OriginOS 不向第三方 App 提供真实壁纸色（社区要靠
Magisk 模块去补）；鸿蒙没有等价公开 API。

所以只能自己在 App 内取色 —— 好处是一套代码跨所有 ROM 一致。

## 2. 现有配色架构（改代码前必读）

- 界面颜色不是写死的 `#RRGGBB`，而是 **33 个自定义 attr** `dsha_pal_*`。
- 色值在 `app/src/main/res/values(-night)/theme_palettes.xml` 的 `pal_<id>_<名>`，
  由 `tools/generate-theme-palettes.py` 生成，**不要手改**。
- 引用链：1100+ 处 `@color/<名>`（`res/color/<名>.xml` 是个 selector）→ `?attr/dsha_pal_<名>`
  → `ThemeOverlay.DSHA.Palette.<Id>` 把 attr 指到具体颜色。
- 叠加时机：`ThemeController.PaletteHook.onActivityPreCreated`，每个 Activity 创建前 `applyStyle`。
- 生效范围：**只作用于原生外壳**（设置页、终端、侧栏、悬浮球、启动页）。聊天主界面是 WebView 里的
  DSH Web，App 只同步明暗（`WebPageScripts.theme`），没有强调色通道。

新增一套调色板必须同时改：生成器 `PALETTES`、`UiThemePalette.REGISTERED`、
`ThemeController.paletteOverlay/paletteSwatch`、`ThemeActivity.paletteName`、`tools/i18n` 十语文案、
`test-ui-colors.py` 的调色板集合断言、`java-format-files.json`。

## 3. 三个运行时构件

### 3.1 `util/DynamicPalette`：把生成器搬到运行时

移植 `tools/generate-theme-palettes.py` 的 `derive(hue, saturation)`：底色按色相重映射并保持原亮度，
强调色逐档搜索 HSL 亮度直到所有强调色对比度达标（留 4.6 余量），最后按主色亮度回填 `primary_dark`。

**同一输入必须得到逐字相同的 hex**，否则运行期配色会偏离构建期五套的风格、对比度门禁失去意义。
两个容易踩的点：

- Python 的 `round()` 是**银行家舍入**（half-to-even），Java 对应的是 `Math.rint`，不是 `Math.round`
  —— 后者在 `.5` 处差 1 个量化单位。
- `colorsys.hls_to_rgb` / `rgb_to_hls` 要逐行移植，不能换成"等价"写法。

验证方式：18 个色相样本 × 33 色 × 日夜 = **1188 个色值与 Python 生成器逐字一致**（`DynamicPaletteTest`）。
另有穷举结论：0–359° × 0.00–1.00 共 7560 组 `derive()` 全部可达，**null 数 0**。

### 3.2 `util/PaletteSeedExtractor`：位图 → 一个色相 + 一个饱和度

- 按 15° 分 24 个色相桶，用**饱和度**做权重投票（不是像素计数：大面积灰底不该盖过小面积高饱和色）。
- 桶内用 cos/sin 加权平均求色相，避免红色 0°/359° 被拆成两桶各占一半。
- 亮度双门槛（过暗/过亮不投票）+ 彩色占比门槛（< 千分之四视为整图无彩）。
- 灰度壁纸如实返回 `found=false`，**不硬造一个色** —— 硬造出来的主题会比"没变色"更让人困惑。

### 3.3 `ui/MonetPalette`：Android 侧入口

`WallpaperManager.getDrawable()` → 缩到 48×48 采样 → 上面的取色器 → `DynamicPalette` 推导。
任何一步失败返回 null，由调用方回退标准色。带进程内缓存（`invalidate()` 清），
否则每个 Activity 创建都要解一次壁纸。

## 4. 落地机制：运行时资源覆盖（最容易误解的一节）

Android **没有公开 API** 能在运行时修改 `Resources.Theme` 里的自定义属性值。所以
「运行时算出的色 → 全界面换色」只能走资源覆盖，也就是官方给 RRO 用的那条路：

1. `util/ResourcesTable` 运行时**构造一张只含 33 个 color 资源的小 `resources.arsc`**：
   - 同 package id（取自 `R.color.*` 的高字节）、同 type id（次字节）
   - entry 下标与 `R.color.pal_monet_*` 一一对应；未覆盖的槽位写 `0xFFFFFFFF`
   - 每个条目 = `ResTable_entry`(8B) + `Res_value`(8B)，`dataType = 0x1C`（ARGB8）
2. `ui/ThemePaletteOverride` 把它写进 `getCacheDir()`，然后
   `ResourcesProvider.loadFromTable(ParcelFileDescriptor, null)` → `ResourcesLoader.addProvider`
   → `resources.addLoaders(...)`（API 30+，与 minSdk 一致）。
3. Google 的 Material `DynamicColors` 内部对 `material_dynamic_*` 用的是同一机制 —— 但那个类是
   package-private，用不了，所以自己实现。

**两个字节布局坑（都写了回归测试）**：

| 坑 | 后果 |
|---|---|
| 漏写 `ResTable_package.typeIdOffset`（headerSize 声明 288、实际只写 284） | 按 headerSize 遍历子 chunk 的解析器（含 `ResourcesProvider`）落到错误偏移，**整张表被静默忽略**。真机表现是「取色算了但没变色」，且不报错 |
| 类型名池只放 1 项 `"color"`，而真实 `typeId` 不是 1 | `typeStrings[typeId-1]` 越界，取不到类型名。正确做法是池铺到 `typeId` 项、前面留空名 |

**顺序约束**：必须**先覆盖资源、再叠 overlay**。反了的话 attr 先解析到兜底色，本页就停在标准蓝上
直到下次重建（`ThemeController.PaletteHook.onActivityPreCreated` 里就是这个顺序）。

只覆盖 `pal_monet_*`，其余五套调色板的资源一个不碰，所以切换调色板不会互相污染。

## 5. 构建期兜底

`tools/generate-theme-palettes.py` 的 `PALETTES` 增加 `('monet', 'Monet', None, None)`：
**构建期色值等于标准色**。这样取不到色时界面是正常的经典蓝，而不是"无主题"状态；
同时它照样参与六套 × 日夜的对比度门禁（最低 4.567:1）。

## 6. 门禁与测试

- `tools/generate-theme-palettes.py --check`、`tools/test-ui-colors.py`（调色板集合断言已含 monet）
- `tools/java-format-files.json`（新文件必须登记）、`tools/test-source-text.py`、架构边界测试
- `tools/i18n` 十语文案 + `tools/check-source-hygiene.py` 的**语言源 SHA-256 钉扎**（改文案必须同步，
  否则 contracts job 报 `PINNED_LANGUAGE_BYTES`）
- 单测：`DynamicPaletteTest`（1188 色值对拍 + 全色相格式/对比度 + 回退）、`PaletteSeedExtractorTest`、
  `ResourcesTableTest`（字节结构回归：偏移、稀疏槽位、非法入参）
- `ThemePaletteResourcesTest` 对 monet **放行**「各套调色板必须彼此不同」这条断言（它的构建期色值
  就是标准色，运行时才被覆盖），但**单独保留**「monet 必须有独立 overlay」——运行时覆盖需要有确定落点。

## 7. 未验证 / 已知风险

- **`ResourcesProvider.loadFromTable` + `resources.addLoaders` 在真机上尚未验证**。Material 用的是
  同一套 API，但不同 ROM 的 Resources 缓存行为可能有差异；如果某些 ROM 上已解析的 `<color>` 不会
  被新 loader 影响，表现就是"取色生效但界面不变"。
- **主线程开销**：单次 `derive()` 实测平均 10–12 ms，`deriveWithinReach` 最差 54.5 ms。
  有进程内缓存兜底，但进程内首次进入莫奈界面会吃一次这个开销。若要优化，方向是把首次取色+推导
  挪到后台线程预热，`PaletteHook` 只读缓存。
- **Web 主界面不跟随莫奈色**：129 个 `--dsw-alias-*` CSS 变量是 DSH 内部实现，升级会变，
  单独评估。
- **灰度壁纸会回退**：整图无彩时不硬造色，用户看到的是「没变色」，主题页会明说原因。
- 换壁纸后没有全局回调（各 ROM 广播时机不一），需要用户在主题页点「重新取色」。

## 8. 真机验收清单

1. 设置 → 主题与外观 → 选「莫奈」，确认色块颜色与当前壁纸主色接近（色块画的是真实取色结果）。
2. 逐页检查是否换色：设置页、终端（面板与强调色）、侧栏选中态、悬浮球、启动页。
3. 换一张主色明显不同的壁纸 → 回主题页点「重新取色」→ 确认整界面跟着变。
4. 换一张纯灰/纯白壁纸 → 确认状态说明变成「这张壁纸没有足够彩色，暂用经典蓝」，界面回到经典蓝而不崩。
5. 在莫奈与极光/樱花/赛博/琥珀之间来回切，确认不残留上一套的颜色（覆盖只作用于 `pal_monet_*`）。
6. 冷启动计时：首次进入莫奈界面比经典蓝多多少毫秒（对照上述 10–54 ms 量级）。
7. 深色/浅色各验证一遍（日夜两套颜色都要覆盖，`ThemePaletteOverride` 按当前明暗取 mode）。
8. 回滚：切回「经典蓝」，确认与未启用莫奈前完全一致。
