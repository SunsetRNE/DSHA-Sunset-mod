package com.deepseekharness.app.plugins;

import com.deepseekharness.app.core.PluginRepository.Item;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** 当前 Web 的只读运行证据；不把磁盘配置、历史激活记录或快照缺席当作加载结论。 */
public final class PluginRuntimeProjection {
  public static final long MAX_AGE_MILLIS = 10_000;
  private static final int MAX_INSTALLED = 512, MAX_DYNAMIC = 256;

  private PluginRuntimeProjection() {}

  public static List<Item> apply(
      List<Item> items, JSONObject activity, long generation, String instance, long now)
      throws Exception {
    boolean fresh = valid(activity, generation, instance, now);
    Map<String, JSONObject> installed = new HashMap<>();
    Set<String> ambiguous = new HashSet<>();
    JSONArray rows = fresh ? activity.optJSONArray("installedPlugins") : null;
    if (rows != null && rows.length() <= MAX_INSTALLED) {
      for (int i = 0; i < rows.length(); i++) {
        JSONObject row = rows.optJSONObject(i);
        if (row == null || row.optString("name").isEmpty()) continue;
        String key = identity(row.optString("profile", "web"), row.optString("name"));
        if (installed.put(key, row) != null) ambiguous.add(key);
      }
    }
    List<Item> result = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    JSONArray dynamic = fresh ? activity.optJSONArray("plugins") : null;
    boolean dynamicKnown = dynamic != null && dynamic.length() <= MAX_DYNAMIC;
    for (Item item : items) {
      if (!keys.add(item.stableKey)) throw new IOException("PLUGIN_DUPLICATE_IDENTITY");
      if (item.dynamic) {
        if (!dynamicKnown && item.runtimeGeneration == generation)
          result.add(item.withRuntime(item.runtimeState, generation, true));
        continue;
      }
      Item next = stale(item, generation);
      String key = identity(item.profile, item.name);
      JSONObject observed = ambiguous.contains(key) ? null : installed.get(key);
      if (observed != null && "application".equals(item.scope)) {
        String version = observed.optString("version"), state = observed.optString("state");
        if (!version.isEmpty() && version.equals(item.version) && confirmedState(state)) {
          next = item.withRuntime(state, generation, false);
          if (!"queued".equals(item.loadState)
              && ((item.enabled && "loaded".equals(state))
                  || (!item.enabled && ("unloaded".equals(state) || "disabled".equals(state)))))
            next = next.withRestartRequired(false);
        } else if (!version.isEmpty() && !version.equals(item.version)) {
          // 当前进程仍持有前一版模块；不能让相同名字替新版领取加载成功。
          next = item.withRuntime("unknown", generation, true).withRestartRequired(true);
        }
      }
      result.add(next);
    }
    if (dynamicKnown) {
      Set<String> dynamicKeys = new HashSet<>();
      List<Item> nextDynamic = new ArrayList<>();
      for (int i = 0; i < dynamic.length(); i++) {
        JSONObject row = dynamic.optJSONObject(i);
        if (row == null) continue;
        String session = row.optString("sessionId"), pluginId = row.optString("id");
        if (session.isEmpty() || pluginId.isEmpty()) continue;
        Item item =
            new Item(
                new JSONObject()
                    .put("name", row.optString("name"))
                    .put("dynamic", true)
                    .put("sessionId", session)
                    .put("pluginId", pluginId)
                    .put("runtimeGeneration", generation)
                    .put("runtimeStale", false)
                    .put(
                        "runtimeState",
                        row.optString("state", row.optBoolean("active") ? "active" : "inactive"))
                    .put("version", row.optString("version"))
                    .put("available", true)
                    .put("enabled", row.optBoolean("active"))
                    .put("location", "会话 " + session.substring(Math.max(0, session.length() - 8)))
                    .put("description", "会话内临时插件，请在 Web 页面管理；重启后需重新定义，不作为安装包备份。"));
        if (!dynamicKeys.add(item.stableKey)) throw new IOException("PLUGIN_DUPLICATE_IDENTITY");
        nextDynamic.add(item);
      }
      result.addAll(nextDynamic);
    }
    return Collections.unmodifiableList(result);
  }

  /** 同版本同代目录刷新只更新安装事实，不能抹掉上次实际运行证据。 */
  public static Item retainRuntime(Item fresh, Item previous, long generation) throws Exception {
    if (previous == null
        || !fresh.stableKey.equals(previous.stableKey)
        || !fresh.version.equals(previous.version)
        || !fresh.origin.equals(previous.origin)
        || previous.runtimeGeneration != generation)
      return fresh.withRuntime("unknown", generation, true);
    return fresh.withRuntime(previous.runtimeState, generation, previous.runtimeStale);
  }

  private static Item stale(Item item, long generation) throws Exception {
    if (item.runtimeGeneration != generation)
      return item.withRuntime("unknown", generation, true).withRestartRequired(false);
    return item.withRuntime(item.runtimeState, generation, true);
  }

  private static boolean valid(JSONObject activity, long generation, String instance, long now) {
    if (activity == null || instance == null || instance.isEmpty() || generation < 1) return false;
    long at = activity.optLong("at", -1);
    return at > 0
        && now >= at
        && now - at <= MAX_AGE_MILLIS
        && Long.toString(generation).equals(activity.optString("generation"))
        && instance.equals(activity.optString("instance"));
  }

  private static boolean confirmedState(String value) {
    return switch (value) {
      case "loaded", "failed", "loading", "pending", "waiting", "unloaded", "disabled" -> true;
      default -> false;
    };
  }

  private static String identity(String profile, String name) {
    String canonical =
        "dsh-auto-review".equals(name) ? "@deepseek-ai/dsh-experimental-auto-review" : name;
    return profile.length() + ":" + profile + ":" + canonical;
  }
}
