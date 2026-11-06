package com.deepseekharness.app.module;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 模块间通信：宿主中转、持久化、限量的消息队列。
 *
 * <p>模块不能直接拿到另一个模块的脚本或对象。发送方只能写一条文字事件到目标模块的收件箱，
 * 接收方下次显式 {@code events.poll} 时取走。这样跨进程/跨页面都不会丢，也不会形成无限
 * 回调链；单卡收件箱最多 32 条、单条 payload 最多 512 字，满了就丢最旧的。
 */
public final class ModuleBus {

  private static final String PREFS = "dsha_module_bus";
  private static final int MAX_EVENTS = 32;
  private static final int MAX_PAYLOAD = 512;
  private static final String KEY_PREFIX = "inbox.";

  private ModuleBus() {}

  /** 发送给指定模块；目标不存在时返回人话错误。 */
  public static String send(Context context, String from, String to, String event, String payload) {
    if (context == null) return "[失败] 缺少上下文";
    if (to == null || to.isEmpty()) return "[参数] 需要 to（目标模块 id）";
    boolean exists = false;
    for (ModuleCard card : ModuleCardStore.load(context)) {
      if (card.id.equals(to)) {
        exists = true;
        break;
      }
    }
    if (!exists) return "[没有这个模块] " + to;
    append(context, to, from, event, payload);
    return "ok";
  }

  /** 广播给所有启用模块；返回投递数量。 */
  public static int emit(Context context, String from, String event, String payload) {
    if (context == null) return 0;
    int delivered = 0;
    for (ModuleCard card : ModuleCardStore.load(context)) {
      append(context, card.id, from, event, payload);
      delivered++;
    }
    return delivered;
  }

  /** 取走并清空本模块收件箱；没有消息返回 "[]"。 */
  public static String poll(Context context, String cardId) {
    if (context == null || cardId == null || cardId.isEmpty()) return "[]";
    String raw = preferences(context).getString(KEY_PREFIX + cardId, null);
    if (raw != null) preferences(context).edit().remove(KEY_PREFIX + cardId).apply();
    return raw == null || raw.isEmpty() ? "[]" : raw;
  }

  public static void clear(Context context, String cardId) {
    if (context == null || cardId == null || cardId.isEmpty()) return;
    preferences(context).edit().remove(KEY_PREFIX + cardId).apply();
  }

  private static void append(
      Context context, String to, String from, String event, String payload) {
    String name = event == null ? "" : event.trim();
    if (!name.matches("[a-z][a-z0-9._-]{0,31}")) return;
    String body = payload == null ? "" : payload;
    if (body.length() > MAX_PAYLOAD) body = body.substring(0, MAX_PAYLOAD) + "…";
    String raw = preferences(context).getString(KEY_PREFIX + to, null);
    JSONArray queue;
    try {
      queue = raw == null || raw.isEmpty() ? new JSONArray() : new JSONArray(raw);
    } catch (Exception ignored) {
      queue = new JSONArray();
    }
    JSONObject item = new JSONObject();
    try {
      item.put("from", from == null ? "" : from);
      item.put("event", name);
      item.put("payload", body);
      item.put("at", System.currentTimeMillis());
      queue.put(item);
      // 满了丢最旧的：模块不能被别的模块用消息撑爆存储。
      while (queue.length() > MAX_EVENTS) queue.remove(0);
    } catch (Exception ignored) {
      return;
    }
    preferences(context).edit().putString(KEY_PREFIX + to, queue.toString()).apply();
  }

  private static SharedPreferences preferences(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }
}
