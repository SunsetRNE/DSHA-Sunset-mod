# 实体屏特权通道

设置中的「特权执行（实验）」控制当前手机任务查询、配置和关闭特权通道的权利。应用授权覆盖用户批准的目标。旧版整屏镜像会混入通知、输入法或其他应用浮层的像素，即使系统只报告一个活动任务。因此当前 Core 只处理设备状态，不会创建显示镜像。

## 在手机任务中读屏

1. 在「设置 → 设备能力授权 → 识屏」选择实体屏，在 Android 系统设置中启用 DSHA 无障碍服务。
2. 开始手机任务，使用当前会话的 `taskSessionId` 和 `turnId`。用户暂停、结束任务或撤销目标授权后，后续读取受到阻止。
3. Android 14 及以上使用 `android_screenshot`。同一个观察入口也服务于 `/app/priv/frame`。特权别名额外检查特权开关、目标应用规则和敏感应用限制。系统仅对当前已授权窗口 ID 取图。尺寸、目标或可见区域无法核实就停止交付。
4. 新截图的 `observationId` 和图片坐标可用于一次 `android_click` 或 `android_swipe`。特权别名 `/app/priv/tap` 与 `/app/priv/swipe` 同样要求这份窗口凭据。授权或窗口变化后需重新观察。
5. Android 11–13 使用 `android_get_state` 读取已授权窗口的控件，随后用 `android_node` 和对应的 `treeId`、`nodeId` 操作。系统没有按无障碍窗口 ID 截图的接口。此通道也没有单独的整屏录制授权。

## 接口

| 入口 | 当前行为 |
|---|---|
| `/app/priv/info` | 读取显示、配置和经授权核对的任务元数据。不会开始抓图 |
| `/app/priv/config`、`/app/priv/close` | 校验并保存通道参数，或关闭现有通道 |
| `/app/priv/frame` | Android 14+ 默认走独立授权的窗口截图。也接受显式 `source=window`。成功回复遵循 `dsha-screenshot-v1`，含 PNG、窗口几何与一次性观察 ID |
| `/app/priv/tap`、`/app/priv/swipe` | 在 Android 14+ 有新窗口凭据时走无障碍坐标校验。坐标沿用返回图片的物理显示坐标 |
| `/app/priv/key`、`/app/priv/benchmark` | 旧接口依赖整屏镜像凭据，返回 `PRIV_AUTHORIZED_WINDOW_SOURCE_REQUIRED`、`NOT_SENT`。需要系统按键时在正常手机任务中按原有授权使用 `android_key` |

无障碍未连接时，窗口入口提示打开系统无障碍设置。目标未知、未批准或敏感时保留拒绝。虚拟屏使用独立的 `/app/vscreen/*` 通道。历史 Phase A 的镜像与 benchmark 实验见 [build351 发行记录](releases/build351-changes.zh.md)。
