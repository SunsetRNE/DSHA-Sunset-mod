package com.deepseekharness.app.module;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * 用户卡片模块的落盘与来源。
 *
 * <p>宿主持有唯一一份拷贝：{@code files/modules/<id>/card.json}。两个入口都汇到这里：
 * 用户自己选文件（来源 local），或容器里的 dsh 往 {@code ~/.dsha-modules/} 写 JSON
 * （来源 container）。宿主只认这两个来源，不接受任意路径、不跟随符号链接。
 *
 * <p><b>安装是整目录事务</b>：新内容先写进 {@code files/module-staging/<uuid>} 暂存，
 * 全部成功后把旧目录整只搬进 {@code files/module-backup/<id>-<时间戳>} 备份区，再把暂存
 * 顶上。任何一步失败，旧版原样留在位（或从备份搬回），当前模块不会被半新半旧的内容损坏；
 * 旧版备份保留最近一份，供模块中心「恢复上一版」。素材按新卡片的引用清单完整替换：
 * 新版不再引用的旧图随旧目录一起退役，不会留下脏文件。
 */
public final class ModuleCardStore {
  private static final String DIR = "modules";
  private static final String FILE = "card.json";

  /** 同目录可能并存声明式清单；移除卡片时要保留它。 */
  private static final String MANIFEST_FILE = "module.json";

  /** 宿主落盘的卡片文件名；签名/校验和诊断共用。 */
  public static final String CARD_FILE = FILE;

  private static final String STAGING_DIR = "module-staging";
  private static final String BACKUP_DIR = "module-backup";
  private static final String INSTALL_PREFS = "dsha_module_install";
  private static final int MAX_BYTES = 32 * 1024;
  private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

  /** 模块自带网页单文件上限；HTML/CSS/JS 比图片大，但仍是有限值。 */
  private static final int MAX_WEB_BYTES = 8 * 1024 * 1024;

  /** 单次扫描/加载最多记录的无效模块错误数，防止坏文件刷屏。 */
  static final int MAX_ISSUES = 20;

  /** 同一模块保留的旧版备份数（刚换下来的那一份）。 */
  static final int KEEP_BACKUPS = 1;

  private static volatile long stamp;

  private ModuleCardStore() {}

  /** 模块集合发生任何变化（导入/移除/开关）都会递增；主页靠它判断要不要重渲染。 */
  public static long stamp() {
    return stamp;
  }

  public static void bump() {
    stamp++;
  }

  /** 容器内约定的投放目录（相对 rootfs 根）。 */
  public static final String CONTAINER_DIR = "root/.dsha-modules";

  /** 无效模块错误：扫描收件箱或加载已装模块时发现但没安装的内容。 */
  public static final class Issue {
    public final String where;
    public final String message;

    Issue(String where, String message) {
      this.where = where == null ? "" : where;
      this.message = message == null ? "" : message;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Issue
          && where.equals(((Issue) other).where)
          && message.equals(((Issue) other).message);
    }

    @Override
    public int hashCode() {
      return where.hashCode() * 31 + message.hashCode();
    }

    @Override
    public String toString() {
      return where + " · " + message;
    }
  }

  /** 最近一次收件箱扫描发现的错误（文件修好或收走后，下次扫描自动消失）。 */
  private static volatile List<Issue> inboxIssues = Collections.emptyList();

  /** 最近一次加载已装模块时发现的错误（文件修好或删除后，下次加载自动消失）。 */
  private static volatile List<Issue> diskIssues = Collections.emptyList();

  /** 主页与模块中心展示的合并错误列表；两侧都不再静默吞掉无效模块。 */
  public static List<Issue> issues() {
    List<Issue> merged = new ArrayList<>(inboxIssues);
    for (Issue issue : diskIssues) if (!merged.contains(issue)) merged.add(issue);
    return Collections.unmodifiableList(merged);
  }

  /** 去重并截断，防坏文件刷屏；纯逻辑，配单测。 */
  static List<Issue> capIssues(List<Issue> found, int max) {
    if (found == null || found.isEmpty() || max <= 0) return Collections.emptyList();
    List<Issue> result = new ArrayList<>();
    for (Issue issue : found) {
      if (result.size() >= max) break;
      if (!result.contains(issue)) result.add(issue);
    }
    return Collections.unmodifiableList(result);
  }

  private static void publishInboxIssues(List<Issue> found) {
    inboxIssues = capIssues(found, MAX_ISSUES);
  }

  private static void publishDiskIssues(List<Issue> found) {
    diskIssues = capIssues(found, MAX_ISSUES);
  }

  private static String message(Throwable error) {
    String text = error.getMessage();
    return text == null || text.isEmpty() ? error.getClass().getSimpleName() : text;
  }

  /** 主页/悬浮层/主题层用的清单：只含「开关打开」的模块。 */
  public static List<ModuleCard> load(Context context) {
    List<ModuleCard> cards = new ArrayList<>();
    for (Map.Entry<String, ModuleCard> entry : loadById(context).entrySet())
      cards.add(entry.getValue());
    return cards;
  }

  /** 底栏外观：按模块 id 稳定取第一个声明了 ui.nav 的启用模块。 */
  public static ModuleCard navCard(Context context) {
    for (ModuleCard card : load(context)) if (card.nav != null) return card;
    return null;
  }

  /** 原生控件换肤：按模块 id 稳定取第一个声明了 ui.skin 的启用模块。 */
  public static ModuleCard skinCard(Context context) {
    for (ModuleCard card : load(context)) if (card.skin != null) return card;
    return null;
  }

  /**
   * 指定宿主页面（home/settings/plugins）的接管模块；按模块 id 稳定取第一个。
   *
   * <p>接管宿主界面必须有一次针对当前内容的显式授权（{@link ModuleSurfaceAuth#APP}）：
   * 只看通用启用开关的话，用户为了试皮肤点一次启用，就等于允许模块替换主页内容区。
   * 未授权就当作没有这个模块，渲染层拿到 null 就渲染原生界面。
   */
  public static ModuleCard appCard(Context context, String surface) {
    for (ModuleCard card : load(context)) {
      if (card.app == null || !card.app.targets.contains(surface)) continue;
      String digest = contentDigest(directoryOf(context, card));
      if (ModuleSurfaceAuth.isAllowed(context, card.id, ModuleSurfaceAuth.APP, digest)) return card;
    }
    return null;
  }

  /** 模块中心用的清单：不管开关状态，全部列出。 */
  public static List<ModuleCard> loadAll(Context context) {
    List<ModuleCard> cards = new ArrayList<>();
    for (Map.Entry<String, ModuleCard> entry : loadById(context, false).entrySet())
      cards.add(entry.getValue());
    return cards;
  }

  public static Map<String, ModuleCard> loadById(Context context) {
    return loadById(context, true);
  }

  private static Map<String, ModuleCard> loadById(Context context, boolean onlyEnabled) {
    Map<String, ModuleCard> result = new LinkedHashMap<>();
    List<Issue> broken = new ArrayList<>();
    File root = new File(context.getFilesDir(), DIR);
    File[] dirs = root.listFiles();
    if (dirs != null) {
      for (File dir : dirs) {
        if (!dir.isDirectory() || isSymlink(dir)) continue;
        File card = new File(dir, FILE);
        if (!card.isFile() || isSymlink(card)) continue;
        try {
          // 来源按目录名查宿主安装记录；旧版本装的模块没有记录，如实显示 unknown。
          String raw = read(card);
          ModuleCard parsed = ModuleCard.parse(raw, sourceOf(context, dir.getName()));
          if (onlyEnabled && !ModuleState.isEnabled(context, ModuleState.SCOPE_CARD, parsed.id))
            continue;
          result.put(parsed.layoutId(), parsed);
        } catch (Exception error) {
          // 单个坏卡片不影响其它模块，也不静默改写它 —— 错误上报主页和模块中心。
          broken.add(new Issue(dir.getName(), message(error)));
        }
      }
    }
    publishDiskIssues(broken);
    return result;
  }

  /** 导入一份卡片 JSON（来源：本地导入）；返回 layoutId。校验失败直接抛，调用方负责提示。 */
  public static String importCard(Context context, String raw) throws IllegalArgumentException {
    return importCard(context, raw, (java.util.Map<String, byte[]>) null, ModuleCard.SOURCE_LOCAL);
  }

  /**
   * 导入卡片，可同时带一张立绘（来源：本地导入）。
   *
   * @param image 立绘图字节；null 表示这份 JSON 没带图（已有图会保留）
   */
  public static String importCard(Context context, String raw, byte[] image)
      throws IllegalArgumentException {
    java.util.Map<String, byte[]> assets = new java.util.LinkedHashMap<>();
    if (image != null && image.length > 0) {
      ModuleCard probe = ModuleCard.parse(raw);
      if (!probe.image.isEmpty()) assets.put(probe.image, image);
    }
    return importCard(context, raw, assets, ModuleCard.SOURCE_LOCAL);
  }

  /** 导入卡片并搬素材（来源：本地导入）。 */
  public static String importCard(Context context, String raw, java.util.Map<String, byte[]> assets)
      throws IllegalArgumentException {
    return importCard(context, raw, assets, ModuleCard.SOURCE_LOCAL);
  }

  /**
   * 带安装来源导入：这是所有入口的汇合点。
   *
   * <p>先整体校验（JSON + 全部素材），再写暂存目录，最后原子提交：
   * 旧版整目录进备份区，暂存顶上；素材按新卡片的引用清单完整替换。
   *
   * @param assets 文件名 → 字节；没提供的图片若旧目录里同名存在则沿用（缺图时卡片退化成无图显示）
   */
  public static String importCard(
      Context context, String raw, java.util.Map<String, byte[]> assets, String source)
      throws IllegalArgumentException {
    ModuleCard card = ModuleCard.parse(raw, source);
    // 素材校验全部前置：任何超限都在动旧模块之前失败，更新不会半途而废。
    Map<String, byte[]> accepted = new LinkedHashMap<>();
    if (assets != null) {
      for (Map.Entry<String, byte[]> entry : assets.entrySet()) {
        String name = entry.getKey();
        byte[] bytes = entry.getValue();
        if (name == null || bytes == null || bytes.length == 0) continue;
        // 只允许模块自己声明过的图片名，防止借 assets 写到目录外面去。
        if (!card.assetNames().contains(name)) continue;
        int limit =
            card.isProgramAsset(name)
                ? ModuleProgram.MAX_BYTES
                : card.isWebAsset(name) ? MAX_WEB_BYTES : MAX_IMAGE_BYTES;
        if (bytes.length > limit)
          throw new IllegalArgumentException("模块素材太大（上限 " + (limit / 1024 / 1024) + "MB）：" + name);
        accepted.put(name, bytes);
      }
    }
    File root = new File(context.getFilesDir(), DIR);
    File dir = new File(root, card.id);
    if (root.exists() && (!root.isDirectory() || isSymlink(root)))
      throw new IllegalArgumentException("模块根目录非法");
    if (dir.exists() && (!dir.isDirectory() || isSymlink(dir)))
      throw new IllegalArgumentException("模块目录非法");
    if (!root.isDirectory() && !root.mkdirs()) throw new IllegalArgumentException("无法创建模块目录");
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalArgumentException("无法创建模块目录");

    File stagingRoot = new File(context.getFilesDir(), STAGING_DIR);
    File staging = new File(stagingRoot, UUID.randomUUID().toString());
    File junk = new File(stagingRoot, "junk-" + UUID.randomUUID());
    try {
      if (!stagingRoot.isDirectory() && !stagingRoot.mkdirs())
        throw new IllegalArgumentException("无法创建暂存目录");
      if (isSymlink(stagingRoot)) throw new IllegalArgumentException("暂存目录非法");
      if (!staging.mkdirs() || isSymlink(staging))
        throw new IllegalArgumentException("无法创建安全的模块暂存目录");
      // 1. 新内容整体先进暂存：card.json + 新素材 + 旧目录里同名沿用的素材。
      writeAtomically(new File(staging, FILE), raw);
      for (String name : card.assetNames()) {
        // 卡片 JSON 来自容器，名字必须在这里再断言一次：../ 或绝对路径会让下面的
        // new File(dir, name) / new File(staging, name) 写到模块目录之外。
        if (!ModuleCard.isAssetFileName(name))
          throw new IllegalArgumentException("模块素材名非法：" + name);
        int limit =
            card.isProgramAsset(name)
                ? ModuleProgram.MAX_BYTES
                : card.isWebAsset(name) ? MAX_WEB_BYTES : MAX_IMAGE_BYTES;
        byte[] bytes = accepted.get(name);
        if (bytes == null) {
          File existing = new File(dir, name);
          if (existing.isFile() && !isSymlink(existing) && existing.length() <= limit)
            try {
              bytes = readBytes(existing);
            } catch (IOException error) {
              throw new IllegalArgumentException("无法读取旧模块素材，更新已取消：" + name, error);
            }
        }
        if (bytes != null && bytes.length > limit)
          throw new IllegalArgumentException("模块素材过大：" + name);
        if (bytes != null && bytes.length > 0) {
          ModuleCard.Program program =
              card.isProgramAsset(name) ? card.programForAsset(name) : null;
          if (program != null) {
            String error = ModuleProgram.validate(program, bytes, android.os.Build.SUPPORTED_ABIS);
            if (error != null) throw new IllegalArgumentException(error + "：" + name);
          }
          File staged = new File(staging, name);
          writeBytesAtomically(staged, bytes);
          if (program != null) staged.setExecutable(true, false);
        }
      }
      // 2. 残缺旧目录（没有 card.json，装到一半的残留）整体挪走，不挡新安装。
      if (dir.isDirectory() && !new File(dir, FILE).isFile() && !dir.renameTo(junk))
        throw new IllegalArgumentException("无法清理残缺的旧模块目录");
      // 3. 提交：旧版整目录搬进备份区，再让暂存顶上；顶不上就把旧版搬回来。
      File backup = null;
      if (new File(dir, FILE).isFile()) {
        File backupRoot = new File(context.getFilesDir(), BACKUP_DIR);
        if (!backupRoot.isDirectory() && !backupRoot.mkdirs())
          throw new IllegalArgumentException("无法创建备份目录");
        if (isSymlink(backupRoot)) throw new IllegalArgumentException("备份目录非法");
        backup = new File(backupRoot, backupDirName(card.id));
        if (!dir.renameTo(backup)) throw new IllegalArgumentException("无法备份旧版模块，本次更新已取消");
      }
      try {
        if (!staging.renameTo(dir)) throw new IllegalArgumentException("无法保存模块");
      } catch (RuntimeException error) {
        if (backup != null && !backup.renameTo(dir)) {
          // 旧版搬不回去：内容仍完整保存在备份区，模块中心可用「恢复上一版」找回。
          publishInboxIssues(
              new ArrayList<>(Arrays.asList(new Issue(card.id, "更新失败且旧版未能自动还原，可在备份区恢复上一版"))));
        }
        throw error;
      }
      // 4. 成功：记录安装来源，只留刚换下来的这一份旧版备份。
      recordSource(context, card.id, source);
      // 5. 开关初值按来源定：用户自己挑文件导入的立即生效；容器投递来的默认关闭，
      //    等用户在宿主上显式确认再加载 —— 否则容器写一个 JSON 就能改宿主界面。
      ModuleState.setEnabled(
          context, ModuleState.SCOPE_CARD, card.id, !ModuleCard.SOURCE_CONTAINER.equals(source));
      retireBackups(context, card.id, KEEP_BACKUPS);
      bump();
      return card.layoutId();
    } finally {
      deleteTree(staging);
      deleteTree(junk);
    }
  }

  /** 备份目录名：<id>-<时间戳>-<短随机>；随机段保证同一毫秒内不撞名。 */
  static String backupDirName(String id) {
    return id
        + "-"
        + System.currentTimeMillis()
        + "-"
        + UUID.randomUUID().toString().substring(0, 8);
  }

  /**
   * 备份条目识别：名字必须是 {@code <id>-<纯数字时间戳>-<8位随机>}。
   *
   * <p>不能用简单前缀判断：id {@code a} 的前缀 {@code a-} 会误吃 id {@code a-b} 的备份。
   */
  static boolean isBackupEntry(String name, String id) {
    if (name == null || id == null || !name.startsWith(id + "-")) return false;
    String rest = name.substring(id.length() + 1);
    int dash = rest.indexOf('-');
    if (dash <= 0) return false;
    String stamp = rest.substring(0, dash);
    if (stamp.isEmpty()) return false;
    for (int i = 0; i < stamp.length(); i++) if (!Character.isDigit(stamp.charAt(i))) return false;
    String unique = rest.substring(dash + 1);
    if (unique.length() != 8) return false;
    for (int i = 0; i < unique.length(); i++) {
      char item = unique.charAt(i);
      boolean hex = (item >= '0' && item <= '9') || (item >= 'a' && item <= 'f');
      if (!hex) return false;
    }
    return true;
  }

  /**
   * 纯逻辑：给定备份条目，挑出该 id 应当退役的（按名字里的时间戳留 keep 份）。
   *
   * <p>备份目录名本身已带 {@code <id>-<毫秒时间戳>-<随机>}，排序必须用名字里的时间戳，
   * 不能用 {@link File#lastModified()}：Windows 对早期时间戳的写入/读取会按文件系统
   * 精度截断或拒绝，测试和真实升级都可能把新备份误当旧备份退役。
   */
  static List<File> backupsToRetire(File[] entries, String id, int keep) {
    List<File> mine = new ArrayList<>();
    if (entries != null) {
      for (File entry : entries) {
        if (entry != null && isBackupEntry(entry.getName(), id)) mine.add(entry);
      }
    }
    mine.sort(
        Comparator.comparingLong(ModuleCardStore::backupStamp)
            .reversed()
            .thenComparing(File::getName, Comparator.reverseOrder()));
    List<File> retire = new ArrayList<>();
    for (int i = Math.max(0, keep); i < mine.size(); i++) retire.add(mine.get(i));
    // 退役列表按「最旧的先退」返回：删除顺序可读、测试和日志也更直观。
    retire.sort(
        Comparator.comparingLong(ModuleCardStore::backupStamp).thenComparing(File::getName));
    return retire;
  }

  /** 从备份目录名里取出毫秒时间戳；调用方已通过 {@link #isBackupEntry} 校验。 */
  static long backupStamp(File file) {
    String name = file == null ? "" : file.getName();
    int first = name.indexOf('-');
    int second = first < 0 ? -1 : name.indexOf('-', first + 1);
    if (first < 0 || second <= first + 1) return 0;
    try {
      return Long.parseLong(name.substring(first + 1, second));
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static void retireBackups(Context context, String id, int keep) {
    File backupRoot = new File(context.getFilesDir(), BACKUP_DIR);
    File[] entries = backupRoot.listFiles();
    for (File stale : backupsToRetire(entries, id, keep)) deleteTree(stale);
  }

  /** 是否存在可恢复的上一版（模块中心据此显示「恢复上一版」）。 */
  public static boolean hasPrevious(Context context, String cardId) {
    return newestBackup(context, cardId) != null;
  }

  private static File newestBackup(Context context, String cardId) {
    File backupRoot = new File(context.getFilesDir(), BACKUP_DIR);
    File[] entries = backupRoot.listFiles();
    List<File> mine = new ArrayList<>();
    if (entries != null) {
      for (File entry : entries) {
        if (entry == null || isSymlink(entry) || !entry.isDirectory()) continue;
        // 必须用严格条目解析：宽松前缀会让 id "a" 把 id "a-b" 的备份认成自己的，
        // 「恢复上一版」就会把别的模块的内容盖进当前模块 —— 跨模块损坏。
        if (!isBackupEntry(entry.getName(), cardId)) continue;
        if (new File(entry, FILE).isFile() && !isSymlink(new File(entry, FILE))) mine.add(entry);
      }
    }
    // 和 {@link #backupsToRetire} 同一个理由：排序用名字里的毫秒时间戳，
    // 不用 lastModified（Windows 对时间戳精度会截断，同秒备份可能排错）。
    mine.sort(
        Comparator.comparingLong(ModuleCardStore::backupStamp)
            .reversed()
            .thenComparing(File::getName, Comparator.reverseOrder()));
    return mine.isEmpty() ? null : mine.get(0);
  }

  /**
   * 把当前模块换回上一版备份。
   *
   * <p>同样走整目录事务：当前版先挪进暂存区，备份顶上，失败就把当前版搬回来。
   * 来源记录不变（同一模块的旧版）；摘要随 card.json 内容重新计算。
   */
  public static boolean restorePrevious(Context context, String cardId) {
    File newest = newestBackup(context, cardId);
    if (newest == null) return false;
    File dir = new File(new File(context.getFilesDir(), DIR), cardId);
    File stagingRoot = new File(context.getFilesDir(), STAGING_DIR);
    if (!stagingRoot.isDirectory() && !stagingRoot.mkdirs()) return false;
    File swap = new File(stagingRoot, "restore-" + UUID.randomUUID());
    if (dir.isDirectory() && !dir.renameTo(swap)) return false;
    if (!newest.renameTo(dir)) {
      if (swap.isDirectory()) {
        if (!swap.renameTo(dir))
          publishInboxIssues(
              new ArrayList<>(Arrays.asList(new Issue(cardId, "恢复上一版失败，当前版暂存于暂存区"))));
      }
      return false;
    }
    deleteTree(swap);
    bump();
    return true;
  }

  /** 模块私有目录；模块网页的 WebView 只允许从这里加载资源。 */
  public static File directoryOf(Context context, ModuleCard card) {
    if (card == null) return null;
    File dir = new File(new File(context.getFilesDir(), DIR), card.id);
    return dir.isDirectory() && !isSymlink(dir) ? dir : null;
  }

  /** 卡片立绘的宿主路径；没有图时返回 null。 */
  public static File imageOf(Context context, ModuleCard card) {
    return assetOf(context, card, card.image);
  }

  /** 模块目录里某张图片的宿主路径；文件名不在本模块声明范围内或不存在时返回 null。 */
  public static File assetOf(Context context, ModuleCard card, String name) {
    if (name == null || name.isEmpty() || !ModuleCard.isAssetFileName(name)) return null;
    if (!card.assetNames().contains(name)) return null;
    File dir = new File(new File(context.getFilesDir(), DIR), card.id);
    if (isSymlink(dir)) return null;
    File file = new File(dir, name);
    return file.isFile() && !isSymlink(file) ? file : null;
  }

  /** 宿主记录的安装来源；没有记录（旧版本装的模块）返回 unknown。 */
  public static String sourceOf(Context context, String cardId) {
    return context
        .getSharedPreferences(INSTALL_PREFS, Context.MODE_PRIVATE)
        .getString("source." + cardId, ModuleCard.SOURCE_UNKNOWN);
  }

  private static void recordSource(Context context, String cardId, String source) {
    context
        .getSharedPreferences(INSTALL_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(
            "source." + cardId,
            source == null || source.isEmpty() ? ModuleCard.SOURCE_UNKNOWN : source)
        .apply();
  }

  /**
   * 移除一张卡片。
   *
   * <p>同目录可能还放着同 id 的清单（{@code module.json}，{@code ModuleCatalog} 明确允许）：
   * 那种情况只删卡片与它声明的素材，目录留给清单；没有清单就把整目录按 NOFOLLOW 清掉 ——
   * 原先的 {@code dir.delete()} 在目录非空时直接失败，图片/网页/程序会一直留在
   * {@code files/modules/<id>} 里，同 id 再次导入还会沿用这些旧字节。
   */
  public static void remove(Context context, String cardId) {
    File dir = new File(new File(context.getFilesDir(), DIR), cardId);
    File card = new File(dir, FILE);
    java.util.List<String> assets = new java.util.ArrayList<>();
    if (card.isFile() && !isSymlink(card)) {
      try {
        assets.addAll(ModuleCard.parse(read(card)).assetNames());
      } catch (RuntimeException | IOException ignored) {
        // 坏卡片也要能移除；素材清单拿不到就只按目录处理。
      }
      card.delete();
    }
    if (new File(dir, MANIFEST_FILE).isFile()) {
      for (String name : assets) {
        if (!ModuleCard.isAssetFileName(name)) continue;
        File asset = new File(dir, name);
        if (asset.isFile() && !isSymlink(asset)) asset.delete();
      }
    } else {
      deleteTree(dir);
    }
    // 来源记录和旧版备份跟着模块一起清，不留孤儿数据。
    context
        .getSharedPreferences(INSTALL_PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove("source." + cardId)
        .apply();
    File[] entries = new File(context.getFilesDir(), BACKUP_DIR).listFiles();
    for (File stale : backupsToRetire(entries, cardId, 0)) deleteTree(stale);
    bump();
  }

  /**
   * 把「怎么做模块」的说明写进容器，让模型不用用户解释就知道格式。
   *
   * <p>写两处：投放目录里的 HOW-TO.md（人和模型都能直接看到），
   * 以及 dsh 技能目录下的 SKILL.md（命中技能加载）。内容相同就跳过，不反复 IO。
   */
  public static void ensureSkill(File rootfsDir) {
    if (rootfsDir == null) return;
    File inbox = new File(rootfsDir, CONTAINER_DIR);
    if (plainPath(inbox, rootfsDir, true))
      writeIfChanged(new File(inbox, "HOW-TO.md"), ModuleCard.SKILL);
    File skills = new File(rootfsDir, "root/.agents/skills/home-module");
    if (plainPath(skills, rootfsDir, true))
      writeIfChanged(new File(skills, "SKILL.md"), ModuleCard.SKILL);
  }

  private static void writeIfChanged(File target, String content) {
    try {
      // 目标父目录必须在写之前仍是真实目录：ensureSkill 建完目录到这里之间还有窗口。
      File parent = target.getParentFile();
      if (parent == null || Files.isSymbolicLink(parent.toPath())) return;
      if (target.isFile() && read(target).equals(content)) return;
      writeAtomically(target, content);
    } catch (IOException | IllegalArgumentException ignored) {
      // 容器还没解压或目录只读时静默跳过，不阻断主页。
    }
  }

  /**
   * 扫描容器投放目录。dsh 在容器里写 {@code ~/.dsha-modules/x.json}，
   * 宿主在 rootfs 的同一位置读，导入后立即出现在主页。
   *
   * <p>只有<b>完整安装成功</b>（JSON 与全部素材都已落进私有目录）才把原件改名
   * {@code .done}；解析失败或安装失败的原件留在原地等修正，错误进 {@link #issues()}
   * 供主页和模块中心展示，不再静默丢失。
   *
   * @return 新导入的数量
   */
  public static int scanContainerInbox(Context context, File rootfsDir) {
    if (rootfsDir == null) return 0;
    File inbox = new File(rootfsDir, CONTAINER_DIR);
    List<Issue> found = new ArrayList<>();
    int imported = 0;
    // 投放目录本身必须是非链接的真实目录；是链接就当作「没有收件箱」，不读也不写。
    if (!plainPath(inbox, rootfsDir, false)) {
      publishInboxIssues(found);
      return 0;
    }
    File[] files = inbox.listFiles();
    if (files != null) {
      for (File file : files) {
        // 目录在 listFiles 之后可能已被换成链接：每个条目都重新过一遍逐级检查。
        if (!plainPath(inbox, rootfsDir, false)) break;
        if (file == null || !file.isFile() || isSymlink(file)) continue;
        String name = file.getName();
        if (!name.endsWith(".json")) continue;
        if (file.length() > MAX_BYTES) {
          found.add(new Issue(name, "文件超过 32KB 上限"));
          continue;
        }
        try {
          String raw = new String(readBytesNoFollow(file, MAX_BYTES), StandardCharsets.UTF_8);
          ModuleCard parsed = ModuleCard.parse(raw, ModuleCard.SOURCE_CONTAINER);
          java.util.Map<String, byte[]> assets = new java.util.LinkedHashMap<>();
          for (String assetName : parsed.assetNames()) {
            // 上限三档必须和 importCard 一致：program / web / image。
            // 之前容器路径漏了 web 档，>4MB 的 module.web 文件在容器投放会被误拒，
            // 同样的包走本地导入却能装上。
            int limit =
                parsed.isProgramAsset(assetName)
                    ? ModuleProgram.MAX_BYTES
                    : parsed.isWebAsset(assetName) ? MAX_WEB_BYTES : MAX_IMAGE_BYTES;
            File asset = new File(inbox, assetName);
            if (asset.isFile()
                && !isSymlink(asset)
                && asset.length() > 0
                && asset.length() <= limit) {
              assets.put(assetName, readBytesNoFollow(asset, limit));
            }
          }
          importCard(context, raw, assets, ModuleCard.SOURCE_CONTAINER);
          imported++;
          // 导入成功后把原件移走，避免每次启动重复导入；移动失败则删除原件，
          // 都失败也只是下次重装一遍（同内容整目录替换，幂等），不影响已装模块。
          File done = new File(inbox, name + ".done");
          if (!plainPath(inbox, rootfsDir, false) || !file.renameTo(done)) file.delete();
        } catch (Exception error) {
          // 无效文件留在原地，用户或模型可以自己改；错误可见，不静默丢。
          found.add(new Issue(name, message(error)));
        }
      }
    }
    publishInboxIssues(found);
    return imported;
  }

  /**
   * 目录存在且不是符号链接；{@code create} 为真时按需创建并复检。
   *
   * <p>容器可以把 {@code root/.dsha-modules} 换成指向宿主目录的软链，宿主若照旧
   * {@code mkdirs()}+写入，就等于替攻击者往别处写文件。这里只接受真实目录。
   */
  private static boolean plainDirectory(File dir, boolean create) {
    if (dir == null) return false;
    java.nio.file.Path path = dir.toPath();
    try {
      if (Files.isSymbolicLink(path)) return false;
      if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return true;
      if (!create) return false;
      if (!dir.mkdirs()) return false;
      return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
    } catch (RuntimeException error) {
      return false;
    }
  }

  /**
   * 从 {@code base} 到 {@code dir} 逐级确认是真实目录（不跟随符号链接），必要时逐级创建。
   *
   * <p>只检查最后一级不够：把 {@code root} 或 {@code root/.agents} 换成链接，后面的
   * {@code mkdirs()} 就会替容器把目录建到别处。
   */
  private static boolean plainPath(File dir, File base, boolean create) {
    if (dir == null || base == null) return false;
    java.nio.file.Path basePath = base.getAbsoluteFile().toPath();
    java.nio.file.Path target = dir.getAbsoluteFile().toPath();
    if (!target.startsWith(basePath)) return false;
    java.nio.file.Path current = basePath;
    for (java.nio.file.Path part : basePath.relativize(target)) {
      current = current.resolve(part);
      try {
        if (Files.isSymbolicLink(current)) return false;
        if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) continue;
        if (!create) return false;
        Files.createDirectory(current);
        if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) return false;
      } catch (java.io.IOException | RuntimeException error) {
        return false;
      }
    }
    return true;
  }

  /** 读普通文件：open 带 NOFOLLOW，读完比对 fileKey 与长度，读到一半被换掉就失败。 */
  private static byte[] readBytesNoFollow(File file, int limit) throws IOException {
    java.nio.file.Path path = file.toPath();
    BasicFileAttributes before =
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!before.isRegularFile()) throw new IOException("NOT_A_REGULAR_FILE");
    if (before.size() > limit) throw new IOException("ASSET_TOO_LARGE");
    byte[] buffer = new byte[(int) before.size()];
    int read = 0;
    try (java.io.InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      while (read < buffer.length) {
        int count = input.read(buffer, read, buffer.length - read);
        if (count < 0) break;
        read += count;
      }
    }
    BasicFileAttributes after =
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size())
      throw new IOException("INBOX_FILE_CHANGED");
    return java.util.Arrays.copyOf(buffer, read);
  }

  private static String read(File file) throws IOException {
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

  private static byte[] readBytes(File file) throws IOException {
    byte[] buffer = new byte[(int) file.length()];
    try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
      int read = 0;
      while (read < buffer.length) {
        int count = input.read(buffer, read, buffer.length - read);
        if (count < 0) break;
        read += count;
      }
    }
    return buffer;
  }

  private static void writeBytesAtomically(File target, byte[] content) {
    File temp = new File(target.getParentFile(), target.getName() + ".tmp");
    try (FileOutputStream output = new FileOutputStream(temp)) {
      output.write(content);
      output.getFD().sync();
    } catch (IOException error) {
      temp.delete();
      throw new IllegalArgumentException("写入立绘失败：" + error.getMessage());
    }
    if (!temp.renameTo(target)) {
      temp.delete();
      throw new IllegalArgumentException("无法保存立绘");
    }
  }

  private static void writeAtomically(File target, String content) {
    File temp = new File(target.getParentFile(), target.getName() + ".tmp");
    try (FileOutputStream output = new FileOutputStream(temp)) {
      output.write(content.getBytes(StandardCharsets.UTF_8));
      output.getFD().sync();
    } catch (IOException error) {
      temp.delete();
      throw new IllegalArgumentException("写入失败：" + error.getMessage());
    }
    if (!temp.renameTo(target)) {
      temp.delete();
      throw new IllegalArgumentException("无法保存模块");
    }
  }

  /**
   * 用 lstat 判符号链接，异常一律按「是链接」处理（fail-closed）。
   *
   * <p>不能用 canonicalPath 比较：/data/data/&lt;pkg&gt; 本身就是 /data/user/0/&lt;pkg&gt; 的
   * 符号链接，canonical 与 absolute 永远不相等，会把所有正常文件都误判成链接。
   */
  private static boolean isSymlink(File file) {
    try {
      return android.system.OsConstants.S_ISLNK(
          android.system.Os.lstat(file.getAbsolutePath()).st_mode);
    } catch (Throwable error) {
      // 连 Error 一起接住：lstat 不可用的环境宁可整目录拒绝，也不能当普通目录放行。
      return true;
    }
  }

  /** 删私有目录树；符号链接只删链接本身，绝不跟进目标（NOFOLLOW）。 */
  private static void deleteTree(File file) {
    if (file == null) return;
    if (isSymlink(file)) {
      file.delete();
      return;
    }
    if (!file.exists()) return;
    File[] children = file.listFiles();
    if (children != null) for (File child : children) deleteTree(child);
    file.delete();
  }

  /**
   * 卡片内容摘要：目录下全部文件按相对路径排序后的 SHA-256。
   *
   * <p>授权（{@link ModuleExec} / {@link ModuleProgramAuth}）绑这个值，而不是绑 id：
   * 同一 id 的卡片会被容器投放或重新导入更新，内容一变摘要就变，旧授权自动失效。
   *
   * <p>返回空串表示读不出来（目录不存在、文件读失败、摘要算法缺失）；调用方必须把空串
   * 当成「未授权」，不要让异常路径变成放行。
   */
  public static String contentDigest(File directory) {
    if (directory == null || !directory.isDirectory()) return "";
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException missing) {
      return "";
    }
    List<File> files = new ArrayList<>();
    collectFiles(directory, files);
    Collections.sort(files, Comparator.comparing(file -> relativeName(directory, file)));
    byte[] buffer = new byte[8192];
    for (File file : files) {
      String name = relativeName(directory, file);
      digest.update(name.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      try (InputStream input = new FileInputStream(file)) {
        for (int count; (count = input.read(buffer)) != -1; ) digest.update(buffer, 0, count);
      } catch (IOException unreadable) {
        return "";
      }
    }
    StringBuilder hex = new StringBuilder(64);
    for (byte value : digest.digest()) hex.append(String.format("%02x", value));
    return hex.toString();
  }

  private static void collectFiles(File directory, List<File> into) {
    File[] children = directory.listFiles();
    if (children == null) return;
    for (File child : children) {
      // 不跟随符号链接：卡片目录里的链接指到宿主别处时，不把它算进内容摘要。
      if (isSymlink(child)) continue;
      if (child.isDirectory()) collectFiles(child, into);
      else if (child.isFile()) into.add(child);
    }
  }

  private static String relativeName(File directory, File file) {
    String base = directory.getAbsolutePath();
    String path = file.getAbsolutePath();
    if (path.startsWith(base) && path.length() > base.length()) {
      path = path.substring(base.length());
      while (path.startsWith(File.separator)) path = path.substring(File.separator.length());
    }
    return path.replace(File.separatorChar, '/');
  }
}
