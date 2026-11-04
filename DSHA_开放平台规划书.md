# DSHA 开放平台规划书

> 版本：v0.1（待核验稿）
> 制定日期：2026-10-27
> 说明：本文档中标注 `【待核验】` 的事实均来自公开资料检索，需团队独立验证后方可采信。第八章列出完整核验清单。

---

## 〇、需求原句（经确认稿）

以下为团队原始表述的整理，作为本规划书的需求基线。

**1. 关于"自由功能"的定义**
> 「自由功能是指更多可玩性，更自由，我目前的想法是，将UI拆掉，改成模块化组合，UI的布局都可以由用户进行调整，然后跟DSH一样，万物皆插件，我们也学习这个，支持皮肤插件改造，软件图标更换」

**2. 开放的主体是"壳"，不是 dsh 前端**
> 「我说的不是内部的DSH皮肤插件，而是我们这个壳，我们想把这个壳也打造成开放平台，支持接入，让本身更自由、更开放」

**3. 模块化 + 视觉自由度 + web↔壳双向联动**
> 「我还想走模块化，用户可以自己去排版布局，支持透明度，颜色，排版，图标等等，只要能动的都可以动，自由度拉满，同时内部的web还要能和壳联动，能管理到壳」

**4. 模块化的价值主张**
> 「关于模块化，用户可以自行添加各种模块，为DSHA增加不同功能，让软件不只是软件，让软件为用户服务」

**5. 生态策略：能接就接，不必自建**
> 「模块，这玩意不是有模块市场吗？模块站，他们的协议，我们可以直接接入，或者说支持他们的协议，我们支持root，那些root圈，一堆的模块」

**6. 安全立场**
> 「我们做了守门狗，也在尽可能的完善root功能，对于非root用户，我们可以提供全局授权，危及不到手机，顶多就是软件环境坏了，清除一下数据，重下环境，照样能用」

**7. 实施节奏**
> 「我们的想法就是分期做，先完成一部分，然后逐步放开」

---

## 一、产品定位

### 1.1 一句话定位

> 别人是"手机上看看电脑的 Agent"，DSHA 是"手机上跑着一个能装模块的 Agent"。

### 1.2 为什么这是结构性差异化

DSH 手机端生态按技术路线可分为两类：

| 类型 | 代表 | agent 位置 | 模块能拿到什么 |
|---|---|---|---|
| **远程型** | dsh-pocket、saya-ch/dsh-mobile、Clarklevis1995/dsh-mobile | 运行在电脑上，手机只是 Viewer | 只能改 UI、加面板。**结构性地调不动手机的真实能力** |
| **本地运行型** | **DSHA**、kelai141/dsh-mobile-apk、woaiys3/deepseek-harness-android-app | 运行在手机本地 | shell、文件系统、无障碍、通知、传感器、ADB |

**模块化本身不是护城河**——GridStack 是开源的，Home Assistant 那套卡片模型谁都能学。真正的壁垒是"**本地运行 × 模块化**"这个组合：同样一个"时钟卡片"，在远程架构里只能显示时间，在 DSHA 里能读系统时区、能触发 Tasker、能在特定时间唤醒 agent 干活。

**结论：模块化要做成"能力开放"，不是"界面花活"。**

### 1.3 产品形态演进

```
现在：DSHA = 一个能在手机上跑 DSH 的 App
         ↓
目标：DSHA = 一个用户可自行组装的移动 Agent 平台
         ↓
形态：软件不只是软件，而是"为用户服务"的可组装工具
```

---

## 二、生态策略：借 vs 建

核心原则：**优先接入已有生态，不自建重复轮子。**

### 2.1 三个可接入的生态

| 生态 | 规模【待核验】 | 接什么 | 怎么接 | 优先级 |
|---|---|---|---|---|
| **dsh 插件市场** | `dsh.works/awesome-dsh-plugins/plugins.json` 约 21,928 条 / 12,985 作者 / 17 个功能领域 | 全部 | 内置索引 URL；dsh 0.2.1+ 已支持"自定义插件市场索引" | **第一期** |
| **MCP 生态** | 去重后约 15,000–20,000 个 server | 能力模块层 | 做 MCP host，支持 `server.json` 规范 | **第一期** |
| **Magisk / APatch / KernelSU** | root 圈存量模块，数量级待核 | **只借格式** | manifest 兼容 `module.prop`，更新协议复用 `updateJson` | 第二期 |

### 2.2 dsh 插件市场：零成本接入

`https://dsh.works/awesome-dsh-plugins/plugins.json` 是现成的开放数据，每条带安装路径验证与适配的 dsh 版本【待核验】。备选源 `awesome-dsh-plugin.com/plugins.json`（约 839 精选条目）。

**dsh 0.2.1 起支持在界面内添加自定义市场索引 URL**【待核验】——若成立，DSHA 内置该地址即可获得整个 dsh 生态，几乎零开发成本。

### 2.3 Magisk 格式：借格式，不借执行

**Magisk 模块规范**（官方 `docs/guides.md`）【待核验】：

```
/data/adb/modules/$MODID/
├── module.prop        # id / name / version / versionCode / author / description / updateJson
├── service.sh         # late_start 阶段执行
├── post-fs-data.sh    # data 挂载后执行
├── action.sh          # 用户点击触发
├── system.prop        # 系统属性
├── sepolicy.rule      # SELinux 规则
└── system/            # magic mount 覆盖
```

`id` 必须匹配 `^[a-zA-Z][a-zA-Z0-9._-]+$`，`versionCode` 必须为整数，**所有字段单行、LF 换行**。

**更新协议 `update.json` 仅四字段**【待核验】：`version` / `versionCode` / `zipUrl` / `changelog`。

APatch 的 APM 兼容 Magisk 格式；KernelSU 模块格式基本一致，差异在挂载机制（metamodule）、`.replace` 不支持、busybox 路径【待核验】。**该格式事实上是 root 圈的通用标准。**

**可借**：`module.prop` 清单格式（root 圈开发者零学习成本）、`updateJson` 更新机制（十年验证的成熟设计）。

**不可借**：功能语义。Magisk 模块是"boot 阶段改 /system"，DSHA 模块是"运行时加 UI 卡片 + 能力"。格式兼容是假象，绝大多数 Magisk 模块在 DSHA 内没有对应语义。

**明确不做的**：让 agent 执行第三方 root 模块脚本（见第五章安全红线）。

### 2.4 MCP：真正的能力供给源

MCP 是唯一同时满足"规模大 + AI 原生"的生态：

- 工具契约标准化：`tools/list` 返回 `inputSchema`（JSON Schema），DSHA 可**自动生成调用表单**
- 官方 registry 用 `server.json` + 反 DNS 命名空间（`io.github.xxx`）+ GitHub OAuth / DNS TXT 验证所有权【待核验】
- **规范明确要求"人类应在决策回路中"，危险操作需确认**——与 DSHA 的安全模型天然同构

Magisk 模块给不了以上任何一点。**MCP 解决"工具长什么样"，不解决"谁允许它运行、用什么权限运行、进程如何保活"——后三者仍需本地权限与生命周期层。**

---

## 三、技术架构

### 3.1 三层模块模型

| 层级 | 载体 | 能做什么 | 生命周期 | 通信 |
|---|---|---|---|---|
| **前端模块（web）** | rootfs 内 ESM JS + CSS | 注册卡片类型、抽屉项、设置页、命令、菜单项、CSS 片段、图标包 | 热加载/热卸载 | 桥 RPC + 事件订阅 |
| **壳模块（Android）** | 独立 APK + IPC | Tile、AppWidget、通知、快捷方式、无障碍、后台任务、前台服务、ADB | 独立安装/启停 | AIDL/Binder |
| **能力模块（agent 侧）** | MCP server / 脚本 / dsh 插件 | 被 agent 调用的工具 | dsh 内注册/卸载 | JSON-RPC（MCP） |

**统一点**：三层都声明 `id` / `version` / `type` / `capabilities` / `permissions` / `contributes`，差异只在 `entry` 与 `runtime`。壳模块不直接进布局，通过"贡献一个前端卡片 + 一个后端 capability"成对出现，卸载时级联清理。

### 3.2 布局引擎

**选型：GridStack.js（MIT）**【待核验】

理由：v6 起原生 mouse+touch、零依赖、1–12 列响应式、`save()/load()` 直接出 JSON、支持网格套网格。

**为什么不选 react-grid-layout / Dockview**：前两者桌面鼠标优先，React/Vue 绑定重。手机上三个坑——① 滚动与拖拽打架；② 拖动中屏幕不滚；③ 无 hover 无法做八向缩放。

**对策**：
- 拖拽手柄只挂在 `.dsh-drag-handle`，加 `touch-action: none`，手柄外走页面滚动
- 拖到视口边缘自动滚动容器
- 手机上把 8 个 resize handle 换成右下角单点 + 双指 pinch-to-resize，松手吸附到列网格

**几何与内容分层**：几何层用 12 列网格节点（断点切换只重算几何，不动卡片状态），内容层用 `type` 分派渲染器。

### 3.3 视觉自由度

**双层 token 体系**（复用 DSH 官方契约，不另起炉灶）【待核验】：
- `--dsw-*`：静态尺度，主题不得覆盖
- `--dsw-alias-*`：语义别名，主题只覆盖这一层
- `--dsha-*`：DSHA 自有语义层，避免污染

**CSS Snippets**（学 Obsidian）：用户在 `snippets/` 放 CSS 文件，UI 里勾选启用，本质是追加 `<link>`，改文件即热重载。这是"透明度/颜色/排版随便改"的最低成本实现。加载时做基础校验（拒绝含 `javascript:` 或超白名单外部 `@import` 的片段）。

**图标包**：借鉴 VS Code `file-icon-theme.json` 的 `iconDefinitions` + `fileExtensions`/`fileNames` 映射，把 `languageIds` 换成 `mimeTypes`/`moduleTypes`【待核验】。Android 端换桌面图标用 `activity-alias`（需预埋坑位，见第五章限制）。

**移动端自由度基准：KWGT/Kustom**（闭源商业，仅对齐产品预期，不借代码）：图层、视觉全可调（透明度/圆角/模糊/渐变/旋转/锚点）、**全局变量**（改一处全局更新，即 CSS 变量的移动端等价物）、条件显隐公式、触摸动作、实时预览+撤销。

### 3.4 WebView ↔ 壳双向联动

**关键决策：管理 UI 做成独立的 DSHA 控制台页，不注入进官方 dsh web。**

四条理由：
1. 官方 dsh web 随版本更新，注入脚本会被上游 DOM/路由变化打挂
2. 管理壳需要高权限桥，与聊天界面混在同 origin 会放大 XSS 爆炸半径
3. 独立页可自由选型框架、做拖拽、加载第三方资源
4. 改主题只需覆盖 `--dsw-alias-*` token 即可影响 dsh web，无需 DOM 注入

**三通道分工**：

| 通道 | 用途 | 安全模型 |
|---|---|---|
| `WebMessageListener` | 受信任的命令/响应 | 强制 `allowedOriginRules` 白名单，平台级校验【待核验：Android 官方标为 Recommended: Yes / Security: Highest】 |
| 本地 WebSocket | 事件订阅与推送 | 每会话 token |
| HTTP | 文件流（模块资源、图标、日志） | 短效 token |

**不用 `@JavascriptInterface` 作主通道**——它注入到所有 frame 含 iframe，无 origin 校验，被 XSS 后可跨 frame 调用【待核验】。

**事件模型**学 HA WebSocket：`id` 关联请求响应，订阅返回 `subscription id`，服务端持续推事件。方法命名用 `domain.object.action` 三段式。

---

## 四、分期实施路线

### 阶段 0 · 桥与控制台骨架（2–3 周）
`WebMessageListener` 桥（origin 白名单）+ CommandRouter + PolicyEngine + 事件总线；控制台独立 origin；GridStack 引入与**真实 WebView 触屏验证**（不要只看桌面浏览器）。

**验收**：控制台能读到壳状态（版本 / dsh 运行状态 / agent 状态 / 权限清单）。

### 阶段 1 · 布局引擎（2–3 周）
12 列网格、手机/平板/宽屏三档断点、拖拽手柄触屏优化、`layout.json` 持久化。

**验收**：拖 3 张卡 → 杀进程重启 → 布局完整保留；手机窄屏不误触滚动。

### 阶段 2 · 卡片注册 + SDK（2–3 周）
模块 manifest schema、卡片类型注册表、JSON Schema → 自动设置表单、`when` 条件表达式、CSS 片段加载器、**SDK + 文档 + 示例模块**。

**验收**：内置 5 张卡全部走 manifest 声明；装一个越权模块被静态扫描拦下。

### 阶段 3 · 主题 + 图标 + 分享（2–3 周）
`dsha-*` 语义 token、主题 YAML、明暗双槽、`snippets/`、图标包、撤销栈、`.dsha-pack` 导出导入。

**验收**：能改透明度/模糊/圆角/间距/字体/图标包并一键还原；导出的包别人能导入。

### 阶段 4 · 市场与三模块打通（3–4 周）
接入 dsh 插件市场索引、MCP host、本地市场索引、安装流程（下载→校验→静态扫描→原子替换→注册）。

**验收**：从市场一键装/更新/卸载；权限分级提示正确。

### 阶段 5 · web 管理壳能力面（3–4 周）
模块/外观/生命周期/设备桥全部 RPC、高危操作二次确认、日志流式、ADB/网络/权限管理。

### 阶段 6 · 自由度打磨（持续）
卡片内部图层编辑器、全局变量、触摸动作映射、社区 Hub、版本历史、无障碍适配。

**总估**：阶段 0–3 约 8–12 人周到可用 MVP；阶段 4–6 再加 6–10 人周。

**每阶段必跑的验收**：① 手机窄屏不误滚动、不误触拖动；② 重启/rootfs 重建后布局与主题仍在；③ 装恶意模块（超权限/远程脚本）被静态扫描与运行时策略引擎双双拦下；④ 千行日志流推送无 ANR。

---

## 五、安全模型

### 5.1 你们的判断是对的，但边界比直觉窄

**"非 root 下最坏结果是软件环境损坏，清数据重下即可恢复"** ——这个判断基本成立，且是真实的架构优势：**可重置的执行环境**。远程方案的 agent 跑在用户电脑上，删库就是真删库；你们的 agent 跑在随时可推倒重来的沙箱里。

**这可以做成安全叙事的卖点**："你的 agent 在一个可随时重置的沙箱里。"

### 5.2 但"清数据"救不回来的四种情况

| 不可逆场景 | 为什么清数据没用 |
|---|---|
| **① 数据外泄** | 东西已经出去了。守门狗防崩溃/卡死/死循环，**防不了"逻辑合规但行为恶意"**——每次调用都合法，合起来在搬数据 |
| **② 共享存储 `/sdcard`** | 照片、文档、下载目录不在 App 私有目录内。清数据不会删掉它们，但 agent 能删 |
| **③ 无障碍服务** | 能读屏、点按、输入——能读银行 App 余额、短信验证码、在支付界面点确认。**这已脱离"软件环境坏了"的范畴** |
| **④ 剪贴板** | 密码管理器复制的内容在里面 |

### 5.3 开放平台特有的坑：信任链转移

用户点"全局授权"时以为授权给的是 DSHA，但模块化后真正执行的是**第三方模块**。你们守住了自己，控制不了模块作者。

**对策：权限提示必须显示"调用方身份 + 模块作者"**，让用户知道现在说话的是谁。

### 5.4 建议：按"可逆性"分两档，不按权限类别分

| 档位 | 范围 | 策略 |
|---|---|---|
| **可逆** | 改 App 内文件、装/卸模块、改配置、改主题布局、在 rootfs 里跑命令、重启 dsh | **全局授权，随便放** |
| **不可逆** | 网络外传、写 `/sdcard`、读剪贴板、无障碍操作其他 App、Shizuku 调用 | **逐次确认或默认拒绝** |

**用户感知的自由度几乎不变**——绝大多数操作都在可逆那一档。

### 5.5 root 场景必须单列

root 下不是"环境坏了"，是真实的设备级风险：改系统分区、改 build.prop、刷模块变砖。

**做成独立的、每次确认、带明确警告的开关，与非 root 的全局授权完全分开。** 别让用户在"全局授权"的惯性下顺手把 root 也开了。

### 5.6 明确红线

**绝不让 agent 执行第三方 root 模块脚本。** `service.sh` 是任意 shell 代码，作者从未为"AI 自动调用"做过任何安全设计。让 agent 执行 = 一个恶意模块即设备完全失陷。

**低风险中间路线**：有 root 时只读 `/data/adb/modules/*/module.prop`，把已装模块展示成"系统状态卡片"。只读不执行，风险为零，对 root 用户有实用价值。

---

## 六、待决事项（团队讨论中，尚未拍板）

> 以下两项本规划书**不预设结论**，仅列出选项、影响面与各方代价，供团队决策后回填。

### 待决 1：「全局授权」的边界在哪里？

**问题**：非 root 下的"全局授权"是否包含以下三项？

| 候选能力 | 是否纳入全局授权 | 影响 |
|---|---|---|
| 网络外传 | ？ | 数据外泄不可逆，清数据救不回来 |
| 写 `/sdcard` | ？ | 用户真实文件，不在沙箱内 |
| 无障碍操作其他 App | ？ | 能读屏/点按，风险量级远超其他项 |

**两种取向**：

- **取向 A（保守）**：三项均排除在全局授权外，走逐次确认。代价是增加授权弹窗，可能削弱"自由度拉满"的体感。
- **取向 B（激进）**：纳入全局授权，通过守门狗 + 行为审计（记录"哪个模块向哪个主机发了多少数据"，首次外联告知用户）来兜底。代价是审计系统本身要投入开发，且一旦失效后果不可逆。

**我的倾向**：取向 A，因为用户感知的自由度几乎不变（绝大多数操作都在可逆档），而风险量级差异巨大。**但这是产品决策，以团队结论为准。**

---

### 待决 2：root 圈模块接入的定位

**问题**：Magisk / APatch / KernelSU 模块，借到什么程度？

| 方案 | 做什么 | 代价 | 风险 |
|---|---|---|---|
| **方案 1（只借格式）** | DSHA 模块 manifest 兼容 `module.prop` 字段，更新协议复用 `updateJson` | 极低 | 无 |
| **方案 2（只读展示）** | 方案 1 + 有 root 时读取已装模块列表，展示成状态卡 | 低 | 无 |
| **方案 3（执行脚本）** | 让 agent 执行第三方 root 模块脚本 | 中 | **极高——一个恶意模块即设备完全失陷** |

**我的倾向**：方案 1 必做，方案 2 可选，**方案 3 不做**。理由：Magisk 模块语义是"boot 阶段改 /system"，与 DSHA 的"运行时加 UI + 能力"没有对应关系；且脚本作者从未为 AI 自动调用做过安全设计。

**但这是产品决策，以团队结论为准。**

---

## 七、开放平台 API 草案（v0.1，建议稿非既有标准）

### 7.1 模块 manifest（前端/能力模块）

```json
{
  "$schema": "https://dsha.app/schemas/module-manifest/v1.json",
  "id": "dsha-module-clock",
  "name": "Clock & World Time",
  "version": "1.2.0",
  "type": "frontend",
  "runtime": {
    "dsha": { "minVersion": "0.1.0", "maxVersion": "0.3.0" },
    "android": { "minSdk": 26 }
  },
  "entry": {
    "main": "./dist/main.js",
    "style": "./dist/main.css",
    "iconPack": "./icon-pack.json"
  },
  "permissions": [
    { "capability": "shell.time.read", "reason": "读取系统时区以显示本地时间" },
    { "capability": "shell.network.fetch", "scope": "https://worldtimeapi.org/*" }
  ],
  "contributes": {
    "cards": [{
      "type": "dsha-module-clock/clock",
      "title": "时钟",
      "icon": "mdi:clock-outline",
      "minSize": { "w": 2, "h": 2 },
      "defaultSize": { "w": 3, "h": 3 },
      "configSchema": {
        "type": "object",
        "properties": {
          "zone": { "type": "string", "default": "Asia/Shanghai" },
          "style": {
            "type": "object",
            "properties": {
              "opacity": { "type": "number", "minimum": 0, "maximum": 1, "default": 1 },
              "blur": { "type": "number", "minimum": 0, "maximum": 32, "default": 0 },
              "bg": { "type": "string", "default": "var(--dsha-color-surface-1)" },
              "radius": { "type": "string", "default": "var(--dsha-radius-l)" }
            }
          }
        }
      }
    }],
    "commands": [{ "id": "dsha-module-clock.openPicker", "title": "打开时区选择器" }],
    "snippets": [{ "id": "glass", "name": "毛玻璃卡片", "source": "./snippets/glass.css" }]
  },
  "integrity": { "algorithm": "sha256", "main": "BASE64..." },
  "signature": { "keyId": "dsha:community:alice", "value": "BASE64..." }
}
```

**要点**：`configSchema` 用标准 JSON Schema，宿主据此**自动生成设置表单**；`permissions` 用细粒度 capability 而非"全部权限"，安装时按等级分组提示；`contributes` 完全声明式，模块不自己操作 DOM；`integrity` 防损坏，`signature` 验作者身份——两者解耦。

### 7.2 与 Magisk `module.prop` 的兼容映射（若采纳待决 2 方案 1）

| DSHA 字段 | Magisk 字段 | 说明 |
|---|---|---|
| `id` | `id` | 均需匹配 `^[a-zA-Z][a-zA-Z0-9._-]+$` |
| `version` | `version` | 展示用 |
| `versionCode`（新增） | `versionCode` | 整数比较 |
| `author` | `author` | |
| `description` | `description` | |
| 更新源 | `updateJson` | 四字段：`version`/`versionCode`/`zipUrl`/`changelog` |

### 7.3 桥 RPC 方法命名

`domain.object.action` 三段式：

- **模块**：`module.list` / `module.get` / `module.install` / `module.uninstall` / `module.enable` / `module.disable` / `module.update` / `module.hotReload`
- **外观**：`theme.apply` / `iconPack.set` / `appIcon.set` / `layout.export` / `layout.import` / `layout.reset`
- **生命周期**：`shell.dsh.restart` / `shell.dsh.stop` / `shell.rootfs.reclaim` / `shell.log.tail`
- **设备桥**：`permission.request` / `permission.revoke` / `adb.exec` / `notification.list` / `tile.configure`
- **观测**：`subscribeEvents` / `unsubscribeEvents` / `metrics.get`

`adb.exec` 与 `shell.rootfs.reclaim` 属高危，只在管理会话内允许。

---

## 八、核验清单

> 团队提到会进行多人核验。以下为本规划书中所有**非团队自述**的关键事实，逐条列出来源、验证方式与置信度。**未标注置信度"高"的，请勿直接采信。**

### 8.1 高置信度（多源一致，建议优先验证）

| # | 事实 | 来源 | 如何自行验证 | 置信度 |
|---|---|---|---|---|
| 1 | Magisk 模块目录结构、`module.prop` 字段、`id` 正则、`versionCode` 整数、`updateJson` 四字段 | Magisk 官方 `docs/guides.md`（topjohnwu/Magisk） | 直接读仓库 `docs/guides.md` 原文 | **高**（官方文档，多源引用一致） |
| 2 | Magisk 本体为 GPL-3.0 | topjohnwu/Magisk LICENSE | 读仓库 LICENSE | **高** |
| 3 | dsh.works 的 awesome-dsh-plugins 约 21,928 条 / 12,985 作者 | 该仓库 README 自述 | `curl -s https://dsh.works/awesome-dsh-plugins/stats.json` | 中高（README 自述，但有 stats.json 可对） |
| 4 | awesome-dsh-plugin.com/plugins.json 约 839 精选 | dsh-plugin-marketplace 文档 | 直接拉取 JSON 数条目 | 中高 |

### 8.2 中置信度（需自行验证，直接影响选型决策）

| # | 事实 | 来源 | 如何自行验证 | 风险 |
|---|---|---|---|---|
| 5 | **GridStack.js 为 MIT、v6 起原生 touch、零依赖、`save()/load()`** | gridstackjs.com + GitHub README | 读仓库 LICENSE 与 v6 changelog | **选型依赖项，必须验证** |
| 6 | **dsh 0.2.1+ 支持界面内添加自定义插件市场索引 URL** | awesome-dsh-plugins README | 在 dsh web 设置页找"实验性插件 → 自定义插件市场" | **直接决定阶段 4 工作量，必须验证** |
| 7 | DSH 官方 `--dsw-*`（静态尺度）+ `--dsw-alias-*`（语义别名）双层 token | `@deepseek-ai/dsh-client-ui-theme` npm 描述页 | 查该包源码或官方文档站 | 主题方案的地基 |
| 8 | `WebMessageListener` 为官方推荐、安全性最高；`addJavascriptInterface` 缺 origin 校验 | Android 开发者文档 | 读 developer.android.com WebView 章节 | 安全架构依赖项 |
| 9 | MCP 生态去重后 15,000–20,000 server；官方 registry 约 2,000（preview） | 多个第三方统计站（Glama/mcp.so/PulseMCP/Smithery 口径差异大） | 查 registry.modelcontextprotocol.io | 数字波动大，仅作量级参考 |
| 10 | VS Code `file-icon-theme.json` 的 `iconDefinitions`/`fileExtensions`/`fileNames` 字段 | VS Code 官方文档 | 读官方文档 | 图标包设计依赖项 |

### 8.3 低置信度 / 需谨慎（多为文档自述或单一来源）

| # | 事实 | 问题 | 建议 |
|---|---|---|---|
| 11 | **各布局库"支持触屏"** | 多来自文档自述，**未在 DSHA 所用 WebView 上独立验证** | **必须在阶段 0 用真机实测**，这是最高优先级的验证项 |
| 12 | 包体积数字（GridStack ~30–40 KB gz 等） | 工程估算，非官方 benchmark | 用 `rollup-plugin-visualizer` 在自有 bundle 实测 |
| 13 | KernelSU / APatch 与 Magisk 的格式差异细节 | 来自第三方对比文章与官方文档混合 | 若采纳待决 2，需逐条读三个项目官方文档 |
| 14 | MCP 官方 registry 的 `server.json` 规范与命名空间验证方式 | registry 仍处 preview，官方警告数据可能重置 | 若第一期接入，需确认当前状态 |

### 8.4 本规划书中属于"建议草案"而非既有事实的内容

以下内容为本文提出的设计建议，**不是需要核验的外部事实**，团队应作为讨论起点而非结论：

- 模块 manifest JSON schema（7.1）
- 与 `module.prop` 的字段映射表（7.2）
- 桥 RPC 方法命名（7.3）
- 权限"可逆/不可逆"两档划分（5.4）
- 分期路线与各阶段工作量估算（第四章）
- WebView ↔ 壳三通道架构（3.4）

### 8.5 协议红线速查

| 项目 | 协议 | 对 MIT 的 DSHA |
|---|---|---|
| GridStack.js | MIT【待核验】 | 可嵌入，保留版权声明 |
| Home Assistant（Core/前端） | Apache-2.0 | 学 API 设计无冲突；复制代码需遵守署名与 NOTICE |
| VS Code | MIT | 可借鉴 manifest 设计 |
| Obsidian 本体 | 商业闭源 | 不可嵌入；其 Plugin API 与示例插件为 MIT |
| **Magisk** | **GPL-3.0** | **只学格式设计，不复制代码**（复制会使 DSHA 面临 GPL 传染） |
| KernelSU / APatch | 需逐项核实 | 核实后再定 |
| MCP 规范 | 开放规范 | 无许可负担；各 SDK 各异（多为 MIT） |
| KWGT/Kustom | 闭源商业 | 仅对齐产品预期 |
| Grafana 前端 | AGPLv3 | 不建议嵌入或复制前端代码 |

---

## 附：一句话总结

> 借 **Magisk 的格式**降低 root 圈开发者的上手门槛，借 **dsh 市场的数据**填满第一天的模块列表，借 **MCP 的生态**拿到真正的能力供给。三件事一起做，开放平台上线当天就不是空市场。

**前提**：第八章的核验项（尤其是第 5、6、11 条）验证通过后，本规划书的选型与工作量估算才成立。
