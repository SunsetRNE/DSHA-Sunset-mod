package com.deepseekharness.app.module;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/**
 * 容器程序登记：启动按钮启动的只有容器里的东西 —— 容器内的 DSH 工作台，或这里登记的程序。
 *
 * <p>登记方式与模块同一条通道 —— 容器里的 dsh（或用户自己）往 {@code ~/.dsha-launch/}
 * 写一份 JSON：
 * <pre>
 *   {"schema":1,"id":"my-tool","name":"我的工具",
 *    "command":"cd /opt/mytool &amp;&amp; ./tool --port 8080","port":8080,"note":"可选说明"}
 * </pre>
 * 宿主回主页扫描后，程序会出现在模块中心的「启动入口」列表里；选中后按启动键就在
 * 容器里执行 {@code command}（宿主直接持有 proot 子进程，输出落
 * {@code /tmp/dsha-tool-<id>.log}）。有 {@code port} 时启动后还会探测
 * 127.0.0.1:<port> 是否开始监听，并把结果告诉用户。
 *
 * <p>壳内也能改：模块中心 → 启动入口 →「新建容器程序 / 编辑 / 删除」调用 {@link #save} /
 * {@link #delete} 把同一份 JSON 写回容器，用户不必手写文件；容器里的 dsh 写进来的登记
 * 和壳内新建的是同一种东西，两边看到的是同一份列表。
 *
 * <p>边界：command 在容器内以容器身份执行（和插件执行同一条 proot 通道），宿主不做二次
 * 白名单 —— 这是用户显式登记的程序，不是模块偷偷执行；扫描只读 JSON，不执行任何内容。
 */
public final class ContainerTools {

  /** 一条登记好的容器工具。 */
  public static final class Tool {
    public final String id;
    public final String name;
    public final String command;
    public final String note;

    /** 期望监听的端口；0 表示没有端口语义。 */
    public final int port;

    Tool(String id, String name, String command, String note, int port) {
      this.id = id;
      this.name = name;
      this.command = command;
      this.note = note;
      this.port = port;
    }
  }

  /** 容器内约定的登记目录（相对 rootfs 根）。 */
  public static final String CONTAINER_DIR = "root/.dsha-launch";

  /** 单个登记文件的字节上限。 */
  static final int MAX_BYTES = 8 * 1024;

  static final int MAX_TOOLS = 16;

  private static final java.util.regex.Pattern ID =
      java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._-]{0,47}");

  private static volatile long stamp;
  private static volatile List<Tool> cache = Collections.emptyList();
  private static volatile List<String> issues = Collections.emptyList();

  private ContainerTools() {}

  /** 登记集合变化时递增；主页/选择器靠它判断要不要重新扫描。 */
  public static long stamp() {
    return stamp;
  }

  /** 当前登记的全部工具（扫描失败时是空列表，不抛）。 */
  public static List<Tool> load(Context context) {
    List<Tool> tools = cache;
    return tools;
  }

  public static Tool byId(Context context, String id) {
    if (id == null || id.isEmpty()) return null;
    for (Tool tool : load(context)) if (tool.id.equals(id)) return tool;
    return null;
  }

  /** 最近一次扫描发现的问题（坏 JSON、超限），供模块中心展示。 */
  public static List<String> currentIssues() {
    return issues;
  }

  /** 扫描容器登记目录；rootfs 还没解压或读取失败时保持现有缓存。 */
  public static void refresh(Context context) {
    File dir;
    try {
      File rootfs =
          com.deepseekharness.app.core.HarnessController.get(context).proot().getRootfsDir();
      dir = new File(rootfs, CONTAINER_DIR);
    } catch (RuntimeException ignored) {
      // 容器还没就绪：保持现有缓存，等下次回主页再试。
      return;
    }
    publish(scanDir(dir));
  }

  /** 一次扫描的结果：可用的登记 + 坏文件的说明。 */
  static final class Scan {
    final List<Tool> tools;
    final List<String> issues;

    Scan(List<Tool> tools, List<String> issues) {
      this.tools = tools;
      this.issues = issues;
    }
  }

  /** 纯目录扫描：只读 JSON，不碰 Context，单测直接给临时目录。 */
  static Scan scanDir(File dir) {
    List<Tool> found = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    File[] files = dir == null ? null : dir.listFiles();
    if (files != null) {
      for (File file : files) {
        if (file == null || !file.isFile() || isSymlink(file)) continue;
        String name = file.getName();
        if (!name.endsWith(".json")) continue;
        if (found.size() >= MAX_TOOLS) {
          problems.add(name + " · 登记数量超过 " + MAX_TOOLS + "，已忽略");
          continue;
        }
        if (file.length() > MAX_BYTES) {
          problems.add(name + " · 文件超过 8KB 上限");
          continue;
        }
        try {
          found.add(parse(read(file)));
        } catch (IllegalArgumentException error) {
          problems.add(name + " · " + error.getMessage());
        } catch (Exception error) {
          problems.add(name + " · 读取失败");
        }
      }
    }
    return new Scan(found, problems);
  }

  private static void publish(Scan scan) {
    cache = Collections.unmodifiableList(scan.tools);
    issues = Collections.unmodifiableList(scan.issues);
    stamp++;
  }

  /** 严格解析一份登记 JSON；任何不合规抛 IllegalArgumentException。 */
  static Tool parse(String raw) {
    JSONObject json;
    try {
      json = new JSONObject(raw);
    } catch (Exception error) {
      throw new IllegalArgumentException("不是合法 JSON");
    }
    if (json.optInt("schema", 0) != 1) throw new IllegalArgumentException("schema 必须是 1");
    String id = json.optString("id", "").trim();
    if (!ID.matcher(id).matches())
      throw new IllegalArgumentException("id 只能是小写字母/数字/点/横线/下划线（≤48 位）");
    String name = json.optString("name", "").trim();
    if (name.isEmpty()) throw new IllegalArgumentException("缺 name（工具显示名）");
    if (name.length() > 24) throw new IllegalArgumentException("name 太长（上限 24 字）");
    String command = json.optString("command", "").trim();
    if (command.isEmpty()) throw new IllegalArgumentException("缺 command（容器内执行的命令）");
    if (command.length() > 500) throw new IllegalArgumentException("command 太长（上限 500 字）");
    // port 用字符串读再解析：写 "port":"8099"（字符串）不能静默变成「无端口」，
    // 那会让用户以为有端口探测实际没有。
    int port = 0;
    Object rawPort = json.opt("port");
    if (rawPort != null && !(rawPort == JSONObject.NULL)) {
      String portText = String.valueOf(rawPort).trim();
      if (!portText.isEmpty()) {
        try {
          port = Integer.parseInt(portText);
        } catch (NumberFormatException error) {
          throw new IllegalArgumentException("port 必须是 1024-65535 的数字（0 表示不探测端口）");
        }
      }
    }
    if (port < 0 || port > 65535 || (port > 0 && port < 1024))
      throw new IllegalArgumentException("port 必须是 1024-65535（0 表示不探测端口）");
    String note = json.optString("note", "").trim();
    if (note.length() > 64) throw new IllegalArgumentException("note 太长（上限 64 字）");
    return new Tool(id, name, command, note, port);
  }

  /**
   * 壳内新建或编辑一条容器程序登记，写回容器 {@code ~/.dsha-launch/<id>.json}。
   *
   * <p>和容器里 dsh 直接写文件是同一种产物：先按同一套规则校验（id/name/command/port/note），
   * 再原子写入（临时文件 + 改名），最后刷新缓存。{@code previousId} 非空且与新 id 不同时，
   * 视为「改 id」——旧登记删除、旧文件不留在容器里。
   *
   * @return 写入后的登记
   * @throws IllegalArgumentException 校验不通过、容器未就绪或写入失败，消息可直接给用户看
   */
  public static Tool save(
      Context context,
      String previousId,
      String id,
      String name,
      String command,
      int port,
      String note) {
    File dir = registrationDir(context);
    if (dir == null) throw new IllegalArgumentException("容器环境还没就绪，暂时不能保存");
    Tool saved = saveTo(dir, previousId, id, name, command, port, note);
    refresh(context);
    return saved;
  }

  /**
   * {@link #save} 的纯目录版本：不碰 Context，单测直接给临时目录。
   *
   * <p>校验和容器写文件共用 {@link #parse}，不另立一套规则。
   */
  static Tool saveTo(
      File dir, String previousId, String id, String name, String command, int port, String note) {
    if (dir == null) throw new IllegalArgumentException("容器环境还没就绪，暂时不能保存");
    JSONObject json = new JSONObject();
    try {
      json.put("schema", 1);
      json.put("id", id == null ? "" : id.trim());
      json.put("name", name == null ? "" : name.trim());
      json.put("command", command == null ? "" : command.trim());
      if (port > 0) json.put("port", port);
      String trimmedNote = note == null ? "" : note.trim();
      if (!trimmedNote.isEmpty()) json.put("note", trimmedNote);
    } catch (org.json.JSONException error) {
      throw new IllegalArgumentException("登记内容无法编码");
    }
    Tool parsed = parse(json.toString());
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalArgumentException("无法创建容器登记目录");
    if (isSymlink(dir)) throw new IllegalArgumentException("容器登记目录非法");
    String previous = previousId == null ? "" : previousId.trim();
    File target = new File(dir, parsed.id + ".json");
    // 改 id 撞上另一条登记：必须拒绝而不是静默把别人的登记覆盖掉。
    if (!previous.equals(parsed.id) && target.isFile() && !isSymlink(target))
      throw new IllegalArgumentException("已有同 id 的容器程序登记：" + parsed.id);
    if (!previous.isEmpty() && !previous.equals(parsed.id)) deleteFrom(dir, previous);
    if (target.isFile() && isSymlink(target)) throw new IllegalArgumentException("同名登记是符号链接，已拒绝覆盖");
    writeAtomically(target, json.toString());
    return parsed;
  }

  /**
   * 删除一条容器程序登记（连同它的运行实例）。
   *
   * @return 是否真的删掉了文件
   */
  public static boolean delete(Context context, String id) {
    if (id == null || id.isEmpty()) return false;
    stopRunning(id);
    File dir = registrationDir(context);
    if (dir == null) return false;
    boolean removed = deleteFrom(dir, id);
    refresh(context);
    return removed;
  }

  /** {@link #delete} 的纯目录版本。 */
  static boolean deleteFrom(File dir, String id) {
    if (dir == null || id == null || id.isEmpty()) return false;
    File target = new File(dir, id + ".json");
    if (!target.isFile() || isSymlink(target)) return false;
    return target.delete();
  }

  /** 容器登记目录；容器还没解压时返回 null（调用方负责给用户人话）。 */
  private static File registrationDir(Context context) {
    try {
      File rootfs =
          com.deepseekharness.app.core.HarnessController.get(context).proot().getRootfsDir();
      return new File(rootfs, CONTAINER_DIR);
    } catch (RuntimeException error) {
      return null;
    }
  }

  /**
   * 原子写入：临时文件 fsync 后改名，避免写一半的登记被下一次扫描当成坏文件。
   *
   * <p>Windows 上 {@code renameTo} 不能覆盖已存在的文件，而 Android/Linux 可以；
   * 先试直接改名（Android 上是原子的），失败且目标确实存在时删旧再改名。
   */
  private static void writeAtomically(File target, String content) {
    File temp = new File(target.getParentFile(), target.getName() + ".tmp");
    try (java.io.FileOutputStream output = new java.io.FileOutputStream(temp)) {
      output.write(content.getBytes(StandardCharsets.UTF_8));
      output.getFD().sync();
    } catch (java.io.IOException error) {
      temp.delete();
      throw new IllegalArgumentException("写入容器登记失败：" + error.getMessage());
    }
    if (temp.renameTo(target)) return;
    if (target.isFile() && target.delete() && temp.renameTo(target)) return;
    temp.delete();
    throw new IllegalArgumentException("无法保存容器登记");
  }

  private static String read(File file) throws java.io.IOException {
    byte[] buffer = new byte[(int) Math.min(file.length(), MAX_BYTES)];
    try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
      int read = 0;
      while (read < buffer.length) {
        int count = input.read(buffer, read, buffer.length - read);
        if (count < 0) break;
        read += count;
      }
      return new String(buffer, 0, read, StandardCharsets.UTF_8);
    }
  }

  /** 用 lstat 判符号链接，异常一律按「是链接」处理（fail-closed，与模块目录同一策略）。 */
  private static boolean isSymlink(File file) {
    try {
      return android.system.OsConstants.S_ISLNK(
          android.system.Os.lstat(file.getAbsolutePath()).st_mode);
    } catch (Throwable error) {
      return true;
    }
  }

  // ------------------------------------------------------------ 运行中的工具进程

  /**
   * 正在运行的容器工具进程（id → proot 直接子进程）。
   *
   * <p>容器会话里不能靠 {@code nohup … &} 常驻：proot 退出时内核会杀掉它 trace 的所有
   * 后台进程。所以工具和 DSH web 一样由宿主持有进程句柄、作为 proot 直接子进程运行；
   * 宿主进程被杀时工具随之退出（与主工作台同等生命周期）。重复「启动」先停旧实例。
   */
  private static final java.util.concurrent.ConcurrentHashMap<String, Process> RUNNING =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** 停掉某个工具的旧实例（没有在跑就什么也不做）。 */
  public static void stopRunning(String id) {
    if (id == null) return;
    Process process = RUNNING.remove(id);
    if (process == null) return;
    try {
      process.destroy();
    } catch (RuntimeException ignored) {
      // 已经退出
    }
  }

  /** 记录新实例（同 id 旧实例已由调用方先停）。进程退出后由查询方惰性清理。 */
  public static void putRunning(String id, Process process) {
    if (id == null || process == null) return;
    RUNNING.put(id, process);
  }

  /** 工具是否正在运行（进程已退出会从登记表清掉）。 */
  public static boolean isRunning(String id) {
    if (id == null) return false;
    Process process = RUNNING.get(id);
    if (process == null) return false;
    if (!process.isAlive()) {
      RUNNING.remove(id);
      return false;
    }
    return true;
  }
}
