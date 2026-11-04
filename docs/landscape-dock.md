# 横屏 DSH Dock（桌面视口）

本文说明启动页「横屏 DSH」入口的行为、边界与配置。**它不是仓库里的虚拟屏（VirtualDisplay）**，两套东西没有代码或生命周期上的关系。

## 是什么

- 入口：启动页新增「横屏 DSH」一行。
  - 点按：进入 `DockDisplayActivity`，在横屏窗口里放一块 16:9 舞台。
  - 长按：进入 `LandscapeProbeActivity`（机制探针，只用于安全区/尺寸自查）。**探针只在可调试构建可用**，
    release 包长按不进入（Activity 自身也会在非 debuggable 包直接 `finish()`）。
- 舞台：WebView 内部按三档 CSS 视口布局，再整体缩放进 16:9 区域：

  | 档位 | CSS 视口 | 说明 |
  | --- | --- | --- |
  | 模式 1 | 1280×720 | 默认，视口最宽 |
  | 模式 2 | 1024×576 | 字大 25% |
  | 模式 3 | 854×480 | 字大 50% |

  缩放与落位由 `util/DshStageGeometry.compute(...)` 计算（纯函数，带边界单测）。
- 控件：左侧滚动条与三档按钮、右侧胶囊（后退 / 回启动页 / 刷新 / 截图）；胶囊配置为 `left` 时三档按钮自动换到右侧空白区。
- 舞台底：代码生成的深色渐变（`stageBackdrop()`），不依赖任何图片资产。
- 键盘：输入法弹出时沿用当前缩放、把 WebView 高度压到可用高度，让网页自己 reflow；
  不再整块上移（旧做法会把顶部裁掉）。

## 明确不是什么

- **不创建 `VirtualDisplay`、不启动 Core**，与 [虚拟屏架构](virtual-screen-architecture.md) 和
  [ADR0003](adr/0003-virtual-screen-lifecycle-and-input.md) 的虚拟屏是两回事。
- 助手侧的 `/app/vscreen`（创建 / see / tap / tree）**看不到也控制不了**这块舞台。
- 不是设备级输入注入：所有点击/滑动都是应用内 WebView 的普通触摸。
- 不读写 `Download/` 等公共目录；唯一例外是用户点「截图」时把 16:9 视口写入公共相册 `Pictures/DSHA`（主动操作，非后台行为）。

## 配置（只读数据开关，不执行代码）

配置文件固定为应用私有目录：

```
files/landscape/dsh-a-landscape.json
```

字段（全部可选）：

| 字段 | 取值 | 默认 |
| --- | --- | --- |
| `defaultMode` | 0 / 1 / 2（上限由 `DshStageGeometry.MODE_COUNT - 1` 决定） | 0 |
| `capsule` | `right` / `left` / `hide` | `right` |
| `chrome` | true / false | true |
| `desktopUa` | true / false | true |
| `scrollLock` | true / false | true |

改完点胶囊「↻」或重开横屏生效：两条路径都会重读数据开关，并重新应用 WebSettings（含 UA）、胶囊/控件布局；
档位只在 `defaultMode` **变化时**重置，否则保留用户手动选中的档位。
文件不存在、损坏、是符号链接、越出目录或超过 64 KiB 时一律按默认值处理。
**运行时不再注入自定义 CSS/JS**：配置只影响数据开关，避免把公共存储里的可执行内容送进已鉴权的 DSH 页面。

## 交互边界（本轮补齐）

- 桥注册失败（特性不支持/异常）时 `bridgeAvailable=false`，滚动条与热区隐藏，`window.open` 走顶层导航。
- document-start 不支持时，在 `onPageFinished` 补一次兼容脚本（与竖屏回退一致）。
- 页面是否可滚**每次滚动都实测内容高度**，SPA 变长后不会因旧标志被永久归零。
- 截图前临时隐藏胶囊/滚动条，截完恢复，避免把控件截进 16:9 视口。
- 胶囊为 `hide` 或 `chrome=false` 时，根页按返回直接 `finish()`，不再只提示。
- 不允许脚本自动开窗、媒体播放需手势（与竖屏对齐）。
- 插件安装/管理只接受**带手势**的 `https://dsha.cc/install...` 与 `https://dsha.cc/app/plugins` 精确白名单；
  `dsha://` / `dsh://` 从 WebView 路径直接拒绝，`window.open`/桥消息同样不触发安装页。

## 兼容性与最低 WebView

- 与竖屏一致注册 document-start 兼容脚本：`es-compat.js` + `compat.js` + 语言/主题 + `startup.js`，
  并设置 `window.__DSHA_NATIVE_OAUTH__=true`（授权跳转走顶层导航而不是弹窗）。
- 刻意**不设置** `__DSHA_NATIVE_PLUGINS__`：Dock 不承载内置插件桥。
- 最低要求 **Android System WebView / Chromium 88**（来自 androidx.webkit 官方特性映射：
  `WEB_MESSAGE_LISTENER` 88+、`DOCUMENT_START_SCRIPT` 81+；未在低版本内核实测）。
  低于该版本时：滚动条隐藏、`window.open` 外链回退为顶层导航（仍由原生拦截后交系统浏览器），
  页面其余功能照常。
- 桥只有在「特性支持 + origin 可校验」时才注册（origin 为空一律视为不可用）；不可用时
  `window.open` 的假窗口把目标交给顶层导航，不会静默失败。
- 滚动条只在「主内容容器」（`main` / `article` / `[data-dsh-scroll]`，找不到退回 `body`）里重选，
  总扫描量上限 4000 个节点；JS 侧不再保留无用的 `toTop`。

## 安全边界

- WebView 基线与竖屏 `WebPreviewActivity` 一致：`setAllowFileAccess(false)`、
  `MIXED_CONTENT_NEVER_ALLOW`、不接受第三方 cookie、Safe Browsing 保持开启。
- 网页 → 原生只用 `WebViewCompat.addWebMessageListener`，并校验来源 origin 与主 frame；
  不再使用对所有 frame 生效的 `addJavascriptInterface`。
- 麦克风授权（`onPermissionRequest`）先校验 `request.getOrigin()` 与当前页面 origin，跨源 iframe 直接 `deny()`。
- 不处理 `intent://`（scheme 归一化后大小写变体一并拒绝）；其它非网页 scheme 一律不启动组件——
  包括 `dsh://` / `dsha://` 深链：`dsha://install` 会直接进插件安装页并自动 inspect/download，
  页面或跨源 iframe 不该能触发；插件安装/授权请回竖屏页面走显式入口。
- 子框架（iframe）只允许加载网页 scheme；`intent:` 与任何深链在子框架里直接拒绝。
- 下载复用 `WebDownloads` / `WebBlobDownload`（带 cookie、进度与 SAF 目标目录）。
- 外部页面弹回走已鉴权的 `baseUrl`（不把带 token 的鉴权 URL 写进 WebView 历史），
  10 秒窗口内最多自动回跳 3 次，超过就停手并提示用户点 ⌂ 回启动页。

## 验证

- 单测：`DshStageGeometryTest`（三档、密度 1/2/4、窗口压矮、键盘态、负 top、无效输入）、
  `LandscapeConfigTest`（缺省/合法/损坏/超限/符号链接/无代码注入入口）、
  `DockDrawableTest`（两个 Drawable 的 draw/alpha/colorFilter 冒烟，防止绘制期崩溃）。
- 真机自查：启动页点「横屏 DSH」进入；三档按钮与右侧胶囊正常绘制；键盘弹出后输入框可见；胶囊四键可用。
- 策略单测：`DockPolicyTest` 13 项（UA 保留真实版本、弹回入口与退避、intent 大小写、scheme 白名单、
  scheme 解析、档位保留、桥可用性、cutout 安全区、兼容脚本骨架 + es-compat 资产 `AbortSignal` 实测、
  插件导航手势+白名单）。
- 触控与安全区：三档 chip 与胶囊按钮按 48×48dp 布局（chip 在窄留边下会收窄），滚动条热区 48dp 宽；
  `displayCutout` 安全区已消费（舞台与控件都排进安全区，见 `insetArea`）。
- 配色：舞台/滚动条/胶囊/两个 Drawable 统一取 `LandscapePalette`，XML 侧对应
  `res/values/landscape_colors.xml`。
- 相关门禁：`python3 tools/check-ui-i18n.py`、`python3 tools/test-terminal-home-i18n.py`、
  `python3 tools/format-java-tree.py --check`。
- **未验证矩阵（如实声明）**：目前只在作者自述的 vivo V2425A / Android 16 上走过主路径；
  中英/日夜、短屏、1.3 倍字体、挖孔/折叠屏、第三方输入法候选、跨源 iframe 麦克风负控均**未验证**。
  三档按钮已暴露 `setSelected` 与 `stateDescription`（选中时读「已选中」）。
