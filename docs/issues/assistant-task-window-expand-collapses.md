# 待查：手机任务悬浮条（识屏任务窗）点开后立刻消失

记录时间：2026-11-01 00:05（北京时间）。来源：用户实机反馈，分支 `ui-v5-scheme-a`，设备 Lenovo TB321FU / Android 16。
状态：**未定位、未修复**。下面区分已观察到的现象、已读代码和尚未证实的推测。

## 现象（用户描述）

- 模型在本对话里执行手机任务时，用户点击屏幕上的任务悬浮条，想看里面的内容。
- 悬浮条展开，能看到里面有交互控件，但约 1 秒内整条消失。
- 随后模型这一侧的读屏调用返回 `SCREEN_CONTROL_PAUSED`（「手机控制已在本机暂停」），用户感受为「授权异常」。

## 已读代码（事实）

- 点击状态行调用 `AssistantSessionController.expand()`：设 `expanded=true` 并立即
  `pause("ASSISTANT_EXPANDED")`，同时 `requestCancellation(turnId)`（向 Host 发 `sessions.cancel`）。
  展开即暂停是有意设计：展开态有可聚焦窗口与输入，不能和读屏/点按并行。
- `AssistantService.prepareScreenOperation()` 在每次截图/读屏前隐藏窗口；控制已暂停时直接返回
  `ASSISTANT_LOCAL_RESUME_REQUIRED`，展开态下 `hideForScreen()` 会抛异常而不是隐藏。所以单看这段，
  读屏本身不应把已展开的窗口收起。
- `finishLocalTask()` 在本轮手机任务完成时调用 `window.close()`，直接移除悬浮条。

## 推测（未证实）

1. 展开 → `requestCancellation` 取消了正在执行的模型轮次 → 模型/工具层收尾时调用
   `android_control finish` → `finishLocalTask()` → `window.close()`，于是刚展开的窗口被整条移除。
   这能解释「展开后一秒内消失」以及后续 `SCREEN_CONTROL_PAUSED`。
2. 另一种可能：展开时 `updateViewLayout` 失败走 `pause("OVERLAY_REMOVED")`，或厂商 ROM 对
   `TYPE_APPLICATION_OVERLAY` 宽度变化的处理导致窗口被系统移除。

本次 logcat 没有应用侧的窗口事件记录（只有系统 Zui/SurfaceFlinger 行），无法据此区分上面两种推测。

## 复现与排查建议

- 在 `AssistantWindow.close()`、`hideForScreen()`、`render()` 的 `updateViewLayout` 失败分支、
  `AssistantService.finishLocalTask()` 加带原因的 `DiagnosticLog.record`，复现一次后对照时间线。
- 期望行为待产品确认：展开查看内容时，是否应当只暂停而不取消当前轮次，并保留窗口直到用户收起或明确停止。
- 修复前不要把「展开即暂停」改成并行读屏；那是输入安全边界。
