package com.deepseekharness.app.assistant;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** 通知动作的精确地址；不是手机操作授权，也不代替原任务权威。 */
public record AssistantNotificationTicket(
    AssistantBinding binding, String turnId, String epoch, String action) {
  public AssistantNotificationTicket {
    Objects.requireNonNull(binding);
    if (turnId == null || turnId.length() > 256 || turnId.indexOf('\0') >= 0)
      throw new IllegalArgumentException("ASSISTANT_NOTIFICATION_TURN_INVALID");
    if (epoch == null || epoch.isBlank() || epoch.length() > 128 || epoch.indexOf('\0') >= 0)
      throw new IllegalArgumentException("ASSISTANT_NOTIFICATION_EPOCH_INVALID");
    if (!"STOP_TASK".equals(action)
        && !"REPLY".equals(action)
        && !"SHOW_OVERLAY".equals(action)
        && !"OPEN".equals(action))
      throw new IllegalArgumentException("ASSISTANT_NOTIFICATION_ACTION_INVALID");
  }

  public AssistantNotificationTicket forAction(String value) {
    return new AssistantNotificationTicket(binding, turnId, epoch, value);
  }

  public boolean addresses(AssistantBinding expected, String turn) {
    return binding.equals(expected) && turnId.equals(turn);
  }

  /** Android PendingIntent 比较 data，不比较 extras；每段独立编码以免分隔符碰撞。 */
  public String identity() {
    return "dsha-assistant://task/"
        + encode(epoch)
        + "/"
        + action
        + "/"
        + binding.generation()
        + "/"
        + encode(binding.hostInstanceId())
        + "/"
        + encode(binding.sessionId())
        + "/"
        + encode(binding.connectionEpoch())
        + "/"
        + encode(turnId);
  }

  private static String encode(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
