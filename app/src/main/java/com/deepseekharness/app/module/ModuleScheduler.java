package com.deepseekharness.app.module;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import com.deepseekharness.app.ModuleScheduleReceiver;
import com.deepseekharness.app.util.ModuleSchedulePolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * 模块定时任务的存储与闹钟登记。
 *
 * <p>每张卡片可以通过能力总线（{@code dsha.call('schedule.set', …)}）登记每天定时执行的任务：
 * 跑一条容器命令（exec）、启动容器（dsh.start）或停止容器（dsh.stop）。
 * 执行仍走这张卡片的执行授权：没授权的任务到点只发「被跳过」的通知，不会静默执行。
 *
 * <p>闹钟用 {@code setAndAllowWhileIdle}：不申请精确闹钟权限；系统省电（Doze）可能把触发
 * 推迟几分钟，登记成功时的返回文案里也向用户说明这一点。重启后由 {@code BootReceiver}
 * 重新登记。
 *
 * <p>模块被移除后撤销它名下全部任务：移除入口在模块中心（不在本文件的可改范围），
 * 所以这里走三条兜底路径 —— 开机重排时清一次（{@link #rearmAll}）、到点触发时清本条
 * （{@link #dropIfCardMissing}）、触发时顺手清其它孤儿（{@link #dropOrphans}）。
 * 三条都查不到卡片才算孤儿；开关关闭的模块照常触发。
 */
public final class ModuleScheduler {

  private static final String PREFS = "dsha_module_schedule";

  /** 一条定时任务。 */
  public static final class Entry {
    public final String id;
    public final String cardId;
    public final String label;
    public final int hour;
    public final int minute;
    public final String kind;
    public final String value;

    Entry(String id, String cardId, String label, int hour, int minute, String kind, String value) {
      this.id = id;
      this.cardId = cardId;
      this.label = label;
      this.hour = hour;
      this.minute = minute;
      this.kind = kind;
      this.value = value;
    }

    JSONObject toJson() {
      JSONObject json = new JSONObject();
      try {
        json.put("cardId", cardId);
        json.put("label", label);
        json.put("hour", hour);
        json.put("minute", minute);
        json.put("kind", kind);
        json.put("value", value);
      } catch (org.json.JSONException ignored) {
        // 固定键名不会抛
      }
      return json;
    }

    static Entry fromJson(String id, JSONObject json) {
      return new Entry(
          id,
          json.optString("cardId", ""),
          json.optString("label", ""),
          json.optInt("hour", -1),
          json.optInt("minute", -1),
          json.optString("kind", ""),
          json.optString("value", ""));
    }
  }

  /** 最近一次真实触发结果；通知权限被拒时，模块中心仍能看到它。 */
  public static final class Result {
    public final long at;
    public final boolean ok;
    public final String text;

    Result(long at, boolean ok, String text) {
      this.at = at;
      this.ok = ok;
      this.text = text == null ? "" : text;
    }
  }

  private static final String RESULT_PREFIX = "result.";
  private static final int MAX_RESULT_TEXT = 500;

  private ModuleScheduler() {}

  /** 新增或更新一张卡片的定时任务；校验失败抛 IllegalArgumentException。 */
  public static Entry set(Context context, String cardId, JSONObject args) {
    // 定时任务永远属于某张卡片：没有卡片身份就登记，到点会查无此卡被清掉，
    // 不如登记时就拒绝，给脚本一句能看懂的原因。
    if (cardId == null || cardId.isEmpty())
      throw new IllegalArgumentException("定时任务必须登记在一张卡片上（缺卡片身份）");
    int hour = args.optInt("hour", -1);
    int minute = args.optInt("minute", -1);
    String kind = args.optString("kind", "").trim();
    String value = args.optString("value", "").trim();
    String label = args.optString("label", "").trim();
    if (label.isEmpty()) label = kind;
    if (label.length() > 24) throw new IllegalArgumentException("label 太长（上限 24 字）");
    String error = ModuleSchedulePolicy.validate(hour, minute, kind, value);
    if (error != null) throw new IllegalArgumentException(error);
    String id = ModuleSchedulePolicy.scheduleId(cardId, kind, value, hour, minute);
    // 改已有任务不受上限约束；只有真正的新任务才数条数，否则改个时间也会被拒。
    if (preferences(context).getString(id, null) == null
        && forCard(context, cardId).size() >= ModuleSchedulePolicy.MAX_PER_CARD)
      throw new IllegalArgumentException(
          "这张卡片定时任务最多 " + ModuleSchedulePolicy.MAX_PER_CARD + " 条，先删旧的");
    Entry entry = new Entry(id, cardId, label, hour, minute, kind, value);
    preferences(context).edit().putString(id, entry.toJson().toString()).apply();
    arm(context, entry);
    return entry;
  }

  /** 某张卡片的全部任务。 */
  public static Map<String, Entry> forCard(Context context, String cardId) {
    Map<String, Entry> result = new LinkedHashMap<>();
    for (Map.Entry<String, ?> saved : preferences(context).getAll().entrySet()) {
      if (!(saved.getValue() instanceof String raw)) continue;
      try {
        Entry entry = Entry.fromJson(saved.getKey(), new JSONObject(raw));
        if (!entry.cardId.equals(cardId)) continue;
        result.put(entry.id, entry);
      } catch (Exception ignored) {
        // 坏记录不拖垮列表
      }
    }
    return result;
  }

  /** 删除一条；返回是否真的删了。 */
  public static boolean clear(Context context, String cardId, String id) {
    Map<String, Entry> entries = forCard(context, cardId);
    Entry entry = entries.get(id);
    if (entry == null) return false;
    cancel(context, entry);
    preferences(context).edit().remove(id).remove(RESULT_PREFIX + id).apply();
    return true;
  }

  /** 移除模块时清掉它名下全部任务。 */
  public static void clearForCard(Context context, String cardId) {
    for (Entry entry : forCard(context, cardId).values()) {
      cancel(context, entry);
      preferences(context).edit().remove(entry.id).remove(RESULT_PREFIX + entry.id).apply();
    }
  }

  /**
   * 记录最近一次触发结果（成功/失败/跳过），供模块中心展示。
   *
   * <p>通知权限被拒绝时系统通知不会出现，但结果仍要落地；不能因为通知失败就让用户
   * 完全不知道任务发生了什么。
   */
  public static void recordResult(Context context, Entry entry, boolean ok, String text) {
    if (entry == null || entry.id == null || entry.id.isEmpty()) return;
    String value = text == null ? "" : text.trim();
    if (value.length() > MAX_RESULT_TEXT) value = value.substring(0, MAX_RESULT_TEXT) + "…";
    JSONObject json = new JSONObject();
    try {
      json.put("at", System.currentTimeMillis());
      json.put("ok", ok);
      json.put("text", value);
    } catch (org.json.JSONException ignored) {
      return;
    }
    preferences(context).edit().putString(RESULT_PREFIX + entry.id, json.toString()).apply();
  }

  /** 最近一次结果；没有或记录损坏时返回 null。 */
  public static Result lastResult(Context context, String id) {
    if (id == null || id.isEmpty()) return null;
    String raw = preferences(context).getString(RESULT_PREFIX + id, null);
    if (raw == null) return null;
    try {
      JSONObject json = new JSONObject(raw);
      return new Result(
          json.optLong("at", 0), json.optBoolean("ok", false), json.optString("text", ""));
    } catch (Exception ignored) {
      return null;
    }
  }

  /** 重启后重排全部闹钟；坏记录跳过，模块已移除的任务一并撤销。 */
  public static void rearmAll(Context context) {
    dropOrphans(context);
    for (Map.Entry<String, ?> saved : preferences(context).getAll().entrySet()) {
      if (!(saved.getValue() instanceof String raw)) continue;
      try {
        arm(context, Entry.fromJson(saved.getKey(), new JSONObject(raw)));
      } catch (Exception ignored) {
        // 单条坏了不影响其他任务
      }
    }
  }

  /**
   * 撤销所有已移除模块名下的任务（含没有卡片身份的记录）；返回撤销条数。
   *
   * <p>模块中心的移除入口不在本文件可改范围，所以孤儿清理靠兜底：开机重排、
   * 每次到点触发各清一次。卡片清单暂时读不出来（坏 JSON、存储不可用）时一条都不动，
   * 宁可让任务空跑一次也不能误删用户还想要的任务。
   */
  public static int dropOrphans(Context context) {
    java.util.Set<String> installed = null;
    try {
      installed = new java.util.HashSet<>();
      for (ModuleCard card : ModuleCardStore.loadAll(context)) installed.add(card.id);
    } catch (Throwable ignored) {
      return 0;
    }
    int dropped = 0;
    // getAll() 返回快照，循环里 remove 不影响遍历。
    for (Map.Entry<String, ?> saved : preferences(context).getAll().entrySet()) {
      if (!(saved.getValue() instanceof String raw)) continue;
      try {
        Entry entry = Entry.fromJson(saved.getKey(), new JSONObject(raw));
        boolean alive =
            entry.cardId != null && !entry.cardId.isEmpty() && installed.contains(entry.cardId);
        if (alive) continue;
        if (drop(context, entry)) dropped++;
      } catch (Exception ignored) {
        // 单条坏了不影响其他任务
      }
    }
    return dropped;
  }

  /**
   * 模块已移除时撤销这一条任务；返回是否真的撤销了。
   *
   * <p>触发路径的兜底：模块中心移除模块的代码不在本文件可改范围，所以到点触发时
   * 先看卡片还在不在，不在就撤闹钟、删记录。卡片文件暂时读不出来（坏 JSON、
   * 存储不可用）时不动它，宁可让任务空跑一次也不能误删用户还想要的任务。
   */
  public static boolean dropIfCardMissing(Context context, Entry entry) {
    if (entry.cardId == null || entry.cardId.isEmpty()) return drop(context, entry);
    try {
      for (ModuleCard card : ModuleCardStore.loadAll(context)) {
        if (card.id.equals(entry.cardId)) return false;
      }
    } catch (Throwable ignored) {
      return false;
    }
    return drop(context, entry);
  }

  private static boolean drop(Context context, Entry entry) {
    cancel(context, entry);
    preferences(context).edit().remove(entry.id).remove(RESULT_PREFIX + entry.id).apply();
    return true;
  }

  /** 登记下一次触发；用非精确闹钟，避免申请精确闹钟权限。 */
  static void arm(Context context, Entry entry) {
    if (entry.hour < 0 || entry.minute < 0) return;
    AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    if (alarm == null) return;
    long next =
        ModuleSchedulePolicy.nextTriggerMillis(
            System.currentTimeMillis(), entry.hour, entry.minute);
    PendingIntent pending =
        PendingIntent.getBroadcast(
            context,
            entry.id.hashCode(),
            fire(context, entry),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending);
  }

  static void cancel(Context context, Entry entry) {
    AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    if (alarm == null) return;
    alarm.cancel(
        PendingIntent.getBroadcast(
            context,
            entry.id.hashCode(),
            fire(context, entry),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
  }

  private static Intent fire(Context context, Entry entry) {
    Intent intent = new Intent(context, ModuleScheduleReceiver.class);
    intent.setAction("dsha.module.SCHEDULE_FIRE");
    // data 参与 filterEquals：不同任务的 PendingIntent 即使 requestCode 撞哈希也不会互相顶掉，
    // 否则一条任务会在登记时静默吞掉另一条的闹钟。
    intent.setData(android.net.Uri.parse("dsha.module.schedule://" + entry.id));
    intent.putExtra("id", entry.id);
    return intent;
  }

  /** 按 id 取回任务（触发时用）。 */
  public static Entry byId(Context context, String id) {
    String raw = preferences(context).getString(id, null);
    if (raw == null) return null;
    try {
      return Entry.fromJson(id, new JSONObject(raw));
    } catch (Exception error) {
      return null;
    }
  }

  private static android.content.SharedPreferences preferences(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  /** 供接收器在触发后登记下一天。 */
  public static void armNextDay(Context context, Entry entry) {
    arm(context, entry);
  }

  /** 供接收器发通知用的常量。 */
  public static final String NOTIFICATION_CHANNEL = "dsha_module_schedule";
}
