# 个人版 UI：壁纸 + 玻璃材质

分支：个人版 UI 分支（基于 build246 `0505f01b`；远端分支名以实际推送为准）。
这是一份**个人审美版本**，不是上游正式发布；不含自定义壁纸选择。文末列明验证范围与未验证项。

## 1. 定位与边界

- **只改表现层**：颜色、材质、排版、图标、圆角、间距；不改运行逻辑、权限、数据格式、插件协议与启动契约。
- **不引入新依赖**，不使用 `RenderEffect`；底部胶囊的模糊是对内置壁纸做的一次性位图模糊，并按日夜缓存。
- **保留既有交互**：语言弹窗仍是「点选即生效 + 取消」；下载键仍进入下载记录；导航仍是原来的四个根页 + 终端动作。

设计取向偏安静：留白优先、信息密度不最大化、层级靠字重与间距而不是阴影和色块。如果要合并回上游，建议按 §3 的改动表拆分评审，而不是整分支合并。

## 2. 设计主张

1. **壁纸是底，不是背景图**：页面兜底 → 壁纸 → 遮罩 → 内容，四层固定顺序；壁纸挂在 Activity 的 `content` 背景上，页面根布局只负责透出来。
2. **玻璃要可读**：卡片与面板 71%、底部胶囊 86%、选中/凹槽 55%；遮罩 35%（日压白、夜压黑）；1dp 发丝描边；零阴影。
3. **一套 token**：新页面只用 `shell_*`；遗留页面把不透明根底色换成 `page_clear`，旧卡片 `bg_card` 换 `card_glass`，不再各写一套。
4. **状态用轻反馈**：按压态是一层淡主色，不整块变色；选中态是「主色描边 + 6% 主色底 + 圆勾」，不叠双重强调。
5. **文字优先**：所有透明度调整以正文对比度为约束，装饰不压过内容。

## 3. 改动范围

| 区域 | 主要文件 | 内容 |
|---|---|---|
| 窗口与壁纸 | `ui/WindowBackdrop.java`（新）、`ui/ModernAndroidUi.java`、`values/themes.xml` | content 背景改为壁纸 + 遮罩；状态栏/导航栏透明；图标明暗随日夜 |
| 外壳 token | `values/shell_ui.xml`、`values-night/shell_ui.xml`、`values/colors.xml`、`values-night/colors.xml` | 画布透明、卡片玻璃、胶囊玻璃、遮罩色、`page_clear` / `card_glass` / `lang_tile*` |
| 底部导航 | `ui/GlassNavDrawable.java`（新）、`ui/MainActivity.java`、`drawable/bg_shell_nav.xml` | 胶囊改为壁纸模糊 + 86% 底 + 1dp 描边 + 顶部高光；零阴影 |
| 启动页 | `layout/fragment_launch.xml`、`values/shell_launch.xml` | 三段状态卡等高（`minHeight 104dp`、标签单行自动缩字、`baselineAligned=false`） |
| 下载键 | `drawable/ic_ui_downloads.xml`、`ui/MainActivity.java` | 填充字形改线性圆角托盘（1.8 描边、圆头圆角），渲染约 20dp，与主题/关于同重量 |
| 语言弹窗 | `ui/LanguagePicker.java`、`drawable/bg_lang_*`、`drawable/ic_lang_*` | 「跟随系统」独立整行；10 种语言双列轻卡；母语名主标题、译名副标题；选中圆勾；点选即生效不变 |
| 上岛设置页 | `ui/IslandFragment.java` | 改为 `CardPage` 骨架：26sp 标题、12sp 分组、71% 玻璃卡片、56dp 入口行 |
| 终端 | `ui/TerminalFloatingMenu.java`、`ui/TerminalActivity.java`、`drawable/bg_terminal_menu.xml` | 菜单球改玻璃底 + 线性图标、零阴影；终端面板与状态栏配色跟随外壳 |
| 首次引导 | `ui/OnboardingActivity.java`、`ui/OnboardingKit.java`、`ui/PermissionPanel.java`、`drawable/bg_onb_*` | 「纸感留白」版式：大标题 + 一句说明 + 卡片分组；授权卡片模型化；页面间距与卡片描边统一 |
| 插件列表 | `layout/item_plugin.xml`、`ui/PluginFragment.java` | 列表卡改玻璃底；详情按钮文案等长，避免展开时行内跳动 |
| 遗留页面 | `CommunityActivity`、`NativeDataActivity`、`RecoveryWebActivity`、`RetainedDataActivity`、`StartupRecoveryLayout`、`WorkspaceFilesActivity`、`FileTransfersActivity`、`activity_adb_pair.xml`、`activity_update.xml` | 根背景改 `page_clear`，让壁纸透出 |

## 4. Token（日 / 夜）

| token | 日间 | 夜间 | 用途 |
|---|---|---|---|
| `shell_bg` | `#00000000` | `#00000000` | 页面画布（透明） |
| `shell_surface` | `#B5FCFDFF` | `#B5131923` | 卡片 / 面板（71%） |
| `shell_raised` | `#8CE7EDF6` | `#8C1B2432` | 选中态 / 凹槽（55%） |
| `shell_nav_bg` | `#DBFCFDFF` | `#DB131923` | 胶囊兜底（86%） |
| `shell_nav_glass` | `#D2FCFDFF` | `#D2171E28` | 玻璃胶囊主色层 |
| `shell_backdrop_scrim` | `#59FFFFFF` | `#59000000` | 壁纸遮罩（35%） |
| `card_glass` | `#B5FCFDFF` | `#B5131923` | 旧卡片迁移 |
| `lang_tile` | `#F2F4F8` | `#1E2430` | 语言卡未选中 |
| `lang_tile_selected` | `#0F1E50D0` | `#146AA4FF` | 语言卡选中（6% 主色） |

## 5. 明确不做

- 自定义壁纸：选图、裁剪、遮罩滑杆、按页面配置壁纸；
- 实时模糊：胶囊模糊的是固定壁纸，不是滚动内容；
- 改动业务行为：不改权限默认值、不动备份格式、不改插件与启动门禁。

## 6. 构建与验证

构建（固定输入由 `ci/sunset-inputs.lock.json` 锁定，696,361,527 B / SHA-256 `a1225b84…`）：

```bash
bash build.sh :app:assembleStandardRelease \
  -Pdsha.unsignedPackage=true -Pdsha.sunsetTest=true \
  -Pandroid.aapt2FromMavenOverride=/opt/android-aapt2/aapt2 \
  --dependency-verification=lenient --no-daemon
```

- 结果：`BUILD SUCCESSFUL`；产物为未签名测试包，本地以自签测试证书签名后覆盖安装。
- 真机：vivo V2425A / Android 16，测试包 `io.github.sunsetrne.dsha.test`（与正式包数据隔离）。
- 视觉走查：启动页、设置、语言弹窗、上岛页、终端、首次引导；深色模式按同一套 token 推导。

**未验证**：Android 11/12 设备、平板/横屏布局、1.3 倍系统字体、深色模式逐页截图、无障碍关闭状态下的自动化回归。以上不做「已通过」声明。

## 7. 已知限制

- 壁纸为竖屏构图，横屏 `centerCrop` 会裁切较多；
- 终端输出区保持黑底（有意，避免浅色主题下白字不可读）；
- 底部胶囊的模糊在滚动过程中不逐帧刷新，属于性能取舍；
- 语言弹窗手机两列、平板四列；超长语言名仍可能省略号截断。

## 8. 回滚

本分支基于 `main`（build246），与主线同源，可按普通分支合并或直接切回 `main`；不改动数据与配置格式，卸载测试包即可清理。
