package com.deepseekharness.app.util;

import java.util.Locale;

/**
 * 容器 SSH 的状态文案：首屏只给 ≤5 字的结论，明细（端口、原因、下一步）单独给。
 *
 * <p>UI 基准线要求状态行 ≤5 字、错误三要素（发生什么 / 影响 / 下一步）进 InfoHint 或折叠区；
 * 诊断码 {@code SSH_*} 不能出现在用户可见文本里。这个类把「状态码 → 短标签 + 明细」的映射
 * 收在一处，界面与通知都从这里取，避免两边各写一份又对不上。
 */
public final class ContainerSshStatus {

  /** 诊断码前缀；其它状态（running/starting/...）是内部状态机用词。 */
  private static final String CODE_PREFIX = "SSH_";

  private ContainerSshStatus() {}

  /** 首屏短标签：≤5 字；未知状态回落为「启动失败」，绝不把诊断码显示给用户。 */
  public static String label(String raw) {
    String state = raw == null ? "" : raw.trim();
    return switch (state) {
      case "running" -> UiText.text("运行中");
      case "starting" -> UiText.text("启动中…");
      case "stopping" -> UiText.text("停止中…");
      case "stopped" -> UiText.text("已停止");
      case "SSH_CLEANUP_UNCONFIRMED" -> UiText.text("监听未确认");
        // 上次会话残留：进程还在，但已经被身份校验（pid+starttime）确认过。
      case "SSH_ORPHAN_RUNNING", "SSH_ORPHAN_UNVERIFIED" -> UiText.text("残留会话");
      default -> UiText.text("启动失败");
    };
  }

  /** 详情：端口与可达范围，或三要素错误说明；用于 InfoHint / 通知正文。 */
  public static String detail(String raw, int port, boolean lan) {
    String state = raw == null ? "" : raw.trim();
    return switch (state) {
      case "running", "starting", "stopping" ->
          UiText.format(lan ? "端口 %s · 局域网" : "端口 %s · 仅本机", Integer.toString(port));
      case "SSH_DISABLED" -> UiText.text("开关已关闭；SSH 没有启动；重新开启后再试");
      case "SSH_LAN_PERMISSION_REQUIRED" -> UiText.text("缺少局域网权限；只有本机能连；先授予局域网权限");
      case "SSH_RUNTIME_NOT_READY" -> UiText.text("容器环境未就绪；SSH 无法启动；先完成部署再试");
      case "SSH_PASSWORD_UNAVAILABLE" -> UiText.text("密码未设置或不合规；SSH 不会启动；先设置 12 位以上密码");
      case "SSH_LISTENER_UNCONFIRMED" -> UiText.text("端口未确认在监听；外部连不上；查看日志后重试");
      case "SSH_CLEANUP_UNCONFIRMED" -> UiText.text("上次会话可能未退出；维护被推迟；稍后重试停止");
      case "SSH_ORPHAN_RUNNING" -> UiText.text("上次的 SSH 未退出，终止失败；端口可能仍在监听；重启设备后再试");
      case "SSH_ORPHAN_UNVERIFIED" -> UiText.text("发现残留进程但无法确认身份；为避免误杀没有启动；重启设备后再试");
      case "stopped" -> UiText.text("已停止");
      default -> UiText.text("启动时出错；SSH 未运行；查看日志后重试");
    };
  }

  /** 是否是需要保留通知的失败态（用户要能看到原因与下一步）。 */
  public static boolean isFailure(String raw) {
    String state = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    return state.startsWith(CODE_PREFIX);
  }

  /** 状态机里的内部码；只用于日志与诊断，不直接显示。 */
  public static boolean isCode(String raw) {
    return raw != null && raw.trim().startsWith(CODE_PREFIX);
  }
}
