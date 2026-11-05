package com.deepseekharness.app.module;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.ui.MainActivity;
import com.deepseekharness.app.ui.ModulePageActivity;
import com.deepseekharness.app.HomeLayoutStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 能力总线：模块调用宿主能力的唯一入口。
 *
 * <p>这是整套体系「开放」的地方。以前每加一个能力都要改 card.json 的字段、改解析、改渲染；
 * 现在模块只写 {@code dsha.call('服务名', {...})}，宿主只要 {@code register(...)} 一个实现。
 * <b>加能力不用改契约，也不用改模块。</b>
 *
 * <p>权限：每个服务自己声明需要什么。需要执行能力的服务统一走这张卡片的单独授权
 * （{@link ModuleExec#isAllowed}），没授权直接返回拒绝原因，不静默失败。
 */
public final class ModuleServices {

  /** 服务实现。返回给模块的字符串会原样进脚本。 */
  public interface Handler {
    String call(Context context, ModuleCard card, JSONObject args) throws Exception;
  }

  private static final class Entry {
    final String name;
    final String permission;
    final Handler handler;

    Entry(String name, String permission, Handler handler) {
      this.name = name;
      this.permission = permission;
      this.handler = handler;
    }
  }

  private static final Map<String, Entry> SERVICES = new LinkedHashMap<>();

  static {
    // ---- 只读，不需要授权 ----
    register(
        "data.get",
        "",
        (context, card, args) -> ModuleData.value(context, args.optString("source", "")));

    register(
        "ui.toast",
        "",
        (context, card, args) -> {
          Toast.makeText(context, args.optString("text", ""), Toast.LENGTH_SHORT).show();
          return "ok";
        });

    register(
        "ui.copy",
        "",
        (context, card, args) -> {
          ClipboardManager clipboard =
              (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
          if (clipboard != null)
            clipboard.setPrimaryClip(ClipData.newPlainText("DSHA", args.optString("text", "")));
          return "ok";
        });

    register(
        "ui.open",
        "",
        (context, card, args) -> {
          String url = args.optString("url", "").trim();
          if (!url.startsWith("https://") && !url.startsWith("http://")) return "只支持 http(s)";
          context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
          return "ok";
        });

    register(
        "module.list",
        "",
        (context, card, args) -> {
          JSONArray list = new JSONArray();
          for (ModuleCard item : ModuleCardStore.load(context)) list.put(item.id);
          return list.toString();
        });

    // 二级页面：只打开这张卡片自己声明的页面，跨模块/任意 Activity 一律拒绝。
    register(
        "page.open",
        "",
        (context, card, args) -> {
          if (card == null) return "[需要卡片] 页面必须属于一张卡片";
          String page = args.optString("page", "").trim();
          if (page.isEmpty()) return "[参数] 需要 page（页面 id）";
          if (!card.pages.containsKey(page)) return "[没有这个页面] " + page;
          ModulePageActivity.open(context, card, page);
          return "ok";
        });

    register(
        "nav.order",
        "",
        (context, card, args) -> new JSONArray(HomeLayoutStore.navOrder(context)).toString());

    // ---- 模块间通信：宿主中转、有界、显式轮询 ----
    register(
        "module.send",
        "",
        (context, card, args) -> {
          if (card == null) return "[需要卡片] 发送方必须有模块身份";
          return ModuleBus.send(
              context,
              card.id,
              args.optString("to", "").trim(),
              args.optString("event", "").trim(),
              args.optString("payload", ""));
        });

    register(
        "events.emit",
        "",
        (context, card, args) -> {
          if (card == null) return "[需要卡片] 发送方必须有模块身份";
          int count =
              ModuleBus.emit(
                  context,
                  card.id,
                  args.optString("event", "").trim(),
                  args.optString("payload", ""));
          return count == 0 ? "[没有可投递的模块]" : "ok：" + count;
        });

    register(
        "events.poll",
        "",
        (context, card, args) -> ModuleBus.poll(context, card == null ? "" : card.id));

    register(
        "events.clear",
        "",
        (context, card, args) -> {
          ModuleBus.clear(context, card == null ? "" : card.id);
          return "ok";
        });

    // ---- 会改变 App 或跑命令，需要这张卡片的授权 ----
    register(
        "nav.set",
        "exec",
        (context, card, args) -> {
          JSONArray order = args.optJSONArray("order");
          if (order == null || order.length() != HomeLayoutStore.DEFAULT_NAV_ORDER.size())
            return "order 必须正好 " + HomeLayoutStore.DEFAULT_NAV_ORDER.size() + " 项";
          List<String> values = new ArrayList<>();
          for (int i = 0; i < order.length(); i++) values.add(order.optString(i, ""));
          try {
            HomeLayoutStore.setNavOrder(context, values);
          } catch (IllegalArgumentException error) {
            return "order 里有不认识或重复的项";
          }
          MainActivity main = MainActivity.active();
          if (main != null) main.runOnUiThread(main::refreshBottomNavigation);
          return "ok";
        });

    register(
        "launch.set",
        "exec",
        (context, card, args) -> {
          String action = args.optString("action", "");
          // 启动按钮只能指向容器内的东西：容器工作台（web），或容器里登记的程序（tool:<id>）。
          if (!HomeLayoutStore.ACTIONS_ALL.contains(action)
              && !HomeLayoutStore.isToolAction(action))
            return "action 只能是 web 或 tool:<id>（容器里登记的程序）";
          if (HomeLayoutStore.isToolAction(action)) {
            String id = HomeLayoutStore.toolIdOf(action);
            ContainerTools.refresh(context);
            if (ContainerTools.byId(context, id) == null)
              return "容器里没有登记程序 " + id + "，先写 ~/.dsha-launch/" + id + ".json";
          }
          try {
            HomeLayoutStore.setLaunchAction(context, action);
          } catch (IllegalArgumentException error) {
            return "action 格式不对：只能是 web 或 tool:<id>";
          }
          return "ok";
        });

    register(
        "exec.run",
        "exec",
        (context, card, args) -> {
          ModuleExec.Result result = ModuleExec.run(context, args.optString("command", ""));
          return (result.ok ? "" : "[失败] ") + result.output;
        });

    // ---- 定时任务：到点执行仍走这张卡片的执行授权 ----
    register(
        "schedule.set",
        "exec",
        (context, card, args) -> {
          ModuleScheduler.Entry entry =
              ModuleScheduler.set(context, card == null ? "" : card.id, args);
          return "ok："
              + entry.label
              + " 每天 "
              + entry.hour
              + ":"
              + String.format("%02d", entry.minute)
              + " → "
              + entry.kind
              + "（未申请精确闹钟，系统省电可能让触发推迟几分钟）";
        });

    register(
        "schedule.list",
        "",
        (context, card, args) -> {
          org.json.JSONArray list = new org.json.JSONArray();
          for (ModuleScheduler.Entry entry :
              ModuleScheduler.forCard(context, card == null ? "" : card.id).values()) {
            JSONObject item = new JSONObject();
            item.put("id", entry.id);
            item.put("label", entry.label);
            item.put("time", String.format("%02d:%02d", entry.hour, entry.minute));
            item.put("kind", entry.kind);
            if (!entry.value.isEmpty()) item.put("value", entry.value);
            list.put(item);
          }
          return list.toString();
        });

    register(
        "schedule.clear",
        "exec",
        (context, card, args) -> {
          String cardId = card == null ? "" : card.id;
          String id = args.optString("id", "").trim();
          if (id.isEmpty()) {
            int count = ModuleScheduler.forCard(context, cardId).size();
            ModuleScheduler.clearForCard(context, cardId);
            return "已清除 " + count + " 条定时任务";
          }
          return ModuleScheduler.clear(context, cardId, id) ? "ok" : "[没有这条任务]";
        });

    // ---- 容器控制：启动/停止走宿主生命周期，不做端口或进程强杀 ----
    register(
        "dsh.start",
        "exec",
        (context, card, args) -> {
          HarnessController controller = HarnessController.get(context);
          if (controller.isWebRunning()) return "容器已在运行";
          new Thread(
                  () -> {
                    try {
                      controller.startWeb(msg -> {});
                    } catch (RuntimeException ignored) {
                      // 启动失败由启动页诊断呈现，不在模块里重复报
                    }
                  },
                  "module-dsh-start")
              .start();
          return "启动请求已提交，结果见启动页";
        });

    register(
        "dsh.stop",
        "exec",
        (context, card, args) -> {
          HarnessController controller = HarnessController.get(context);
          if (!controller.isWebRunning()) return "容器未在运行";
          new Thread(
                  () -> {
                    try {
                      controller.stopWeb(msg -> {});
                    } catch (RuntimeException ignored) {
                      // 停止失败由启动页与停止屏障兜底
                    }
                  },
                  "module-dsh-stop")
              .start();
          return "停止请求已提交，结果见启动页";
        });
  }

  private ModuleServices() {}

  /** 注册/覆盖一个服务。宿主以后加能力就一行。 */
  public static void register(String name, String permission, Handler handler) {
    if (name == null || name.isEmpty() || handler == null) return;
    SERVICES.put(name, new Entry(name, permission == null ? "" : permission, handler));
  }

  public static List<String> names() {
    return new ArrayList<>(SERVICES.keySet());
  }

  /** 供模块中心展示：每个服务需要什么权限。 */
  public static Map<String, String> catalog() {
    Map<String, String> map = new LinkedHashMap<>();
    for (Entry entry : SERVICES.values()) map.put(entry.name, entry.permission);
    return map;
  }

  /**
   * 模块调用：找不到服务、没授权、参数不对都返回人话，不抛给脚本。
   */
  public static String call(Context context, ModuleCard card, String name, String rawArgs) {
    Entry entry = SERVICES.get(name);
    if (entry == null) return "[没有这个能力] " + name + "；可用：" + String.join(", ", names());
    if (!entry.permission.isEmpty()) {
      String cardId = card == null ? "" : card.id;
      if (!ModuleExec.isAllowed(context, cardId))
        return "[未授权] 「" + (card == null ? "这张卡片" : card.name) + "」还没有运行权限，先在卡片上确认一次。";
    }
    JSONObject args;
    try {
      args =
          rawArgs == null || rawArgs.trim().isEmpty() ? new JSONObject() : new JSONObject(rawArgs);
    } catch (Exception error) {
      return "[参数不是合法 JSON]";
    }
    try {
      String result = entry.handler.call(context, card, args);
      return result == null ? "" : result;
    } catch (Exception error) {
      return "[失败] " + (error.getMessage() == null ? error.toString() : error.getMessage());
    }
  }
}
