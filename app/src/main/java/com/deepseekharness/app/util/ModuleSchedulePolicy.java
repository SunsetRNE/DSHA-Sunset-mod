package com.deepseekharness.app.util;

/** 定时任务的纯校验与计算；不 import Android，方便单测。 */
public final class ModuleSchedulePolicy {

  /**
   * 定时任务能干的三类事；每类都受该卡片的执行授权约束。
   *
   * <p>容器启停两个 kind 与能力总线服务名保持一致（{@code dsh.start} / {@code dsh.stop}），
   * 模块里「现在就做」和「每天定时做」用的是同一套词，不用记两份。
   */
  public static final java.util.List<String> KINDS =
      java.util.Collections.unmodifiableList(
          java.util.Arrays.asList("exec", "dsh.start", "dsh.stop"));

  public static final int MAX_VALUE = 500;

  /** 每张卡片最多同时登记的定时任务数；防止脚本循环把偏好文件写爆。 */
  public static final int MAX_PER_CARD = 8;

  private ModuleSchedulePolicy() {}

  /** @return 人话错误；null 表示合法。 */
  public static String validate(int hour, int minute, String kind, String value) {
    if (hour < 0 || hour > 23) return "hour 要在 0-23 之间";
    if (minute < 0 || minute > 59) return "minute 要在 0-59 之间";
    if (kind == null || !KINDS.contains(kind)) return "kind 只能是 " + KINDS;
    String trimmed = value == null ? "" : value.trim();
    if ("exec".equals(kind)) {
      if (trimmed.isEmpty()) return "exec 需要 value（要跑的命令）";
      if (trimmed.length() > MAX_VALUE) return "命令太长（上限 " + MAX_VALUE + " 字符）";
    } else if (!trimmed.isEmpty()) {
      return kind + " 不需要 value";
    }
    return null;
  }

  /** 下一次触发时刻：今天这个点还没过就是今天，否则明天。按本地时区。 */
  public static long nextTriggerMillis(long nowMillis, int hour, int minute) {
    java.util.Calendar calendar = java.util.Calendar.getInstance();
    calendar.setTimeInMillis(nowMillis);
    calendar.set(java.util.Calendar.HOUR_OF_DAY, hour);
    calendar.set(java.util.Calendar.MINUTE, minute);
    calendar.set(java.util.Calendar.SECOND, 0);
    calendar.set(java.util.Calendar.MILLISECOND, 0);
    if (calendar.getTimeInMillis() <= nowMillis) calendar.add(java.util.Calendar.DAY_OF_MONTH, 1);
    return calendar.getTimeInMillis();
  }

  /**
   * 稳定的调度 id：同一张卡、同一份「时间 + 动作」永远得到同一个 id，重复 set 就是更新。
   *
   * <p>id 刻意<b>不含 label</b>：label 只是给人看的名字。如果 id 跟着 label 走，
   * 改个名字就会留下一条旧任务继续每天触发 —— 那是真实泄漏，不是更新。
   * 哈希用 SHA-256 截取 8 个十六进制位：比 String.hashCode 抗碰撞，
   * 两条不同任务撞 id 的代价是互相覆盖，撞不起。
   */
  public static String scheduleId(String cardId, String kind, String value, int hour, int minute) {
    String seed = cardId + "|" + kind + "|" + value + "|" + hour + ":" + minute;
    return cardId + "#" + sha8(seed);
  }

  /** SHA-256 前 4 字节的十六进制；JDK 自带，无 Android 依赖。 */
  private static String sha8(String seed) {
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(8);
      for (int i = 0; i < 4; i++)
        hex.append(String.format(java.util.Locale.ROOT, "%02x", digest[i]));
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
