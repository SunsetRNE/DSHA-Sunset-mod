package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;

import com.deepseekharness.app.R;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.nio.charset.StandardCharsets;

/**
 * DSHA-Mod 的自定义背景（只作用在原生界面）。
 *
 * <p>实现方式参考 b-2 星曜奇门：<b>一张底图 + 一层遮罩 + 外壳面板半透明</b>。
 * 和旧版最大的区别是<b>不再指望 window / DecorView 背景</b> —— 那些会被每个
 * 页面自己的不透明根布局（{@code @color/surface}）整个盖住，这正是 191~200
 * 一直「背景不生效」的真正原因。现在改成：
 *
 * <ol>
 *   <li>{@code activity_main.xml} 最底层放显式的 {@code app_background} ImageView
 *       和 {@code app_scrim} 遮罩层，页面内容全部在它们上面；
 *   <li>各页面根布局改成透明，卡片/底栏/按钮改成 90% 左右的半透明底（{@code card_glass}），
 *       底图从缝隙里透出来；
 *   <li>遮罩层压在「底图与界面之间」，颜色按深浅色自动取黑/白，默认 55%，可调 0~90%。
 * </ol>
 *
 * <p>没设自定义壁纸时用<b>内置默认壁纸</b>：浅色主题走 {@code drawable-nodpi/default_wallpaper.jpg}，
 * 深色主题走 {@code drawable-night-nodpi/default_wallpaper.jpg}（218 起）—— 由资源系统按
 * {@code uiMode} 自动挑，切换昼夜主题时跟着换，不用额外通知。
 *
 * <p>配置只认 {@code app} 一块（DSH 网页与横屏界面按用户要求保持原样）。
 * 配置主路径在应用私有目录，不依赖全盘存储权限：
 * {@code getFilesDir()/dsh-a-appearance.json} + {@code appearance-bg.jpg}；
 * 外部 {@code /sdcard/Download/DSHA/dsh-a-appearance.json} 只做兼容读取与尽力镜像。
 */
public final class DshAppearance {
  private static final String TAG = "DSHA_APPEARANCE";
  private static final String CONFIG_NAME = "dsh-a-appearance.json";
  private static final String IMAGE_NAME = "appearance-bg.jpg";
  /** 210：横屏 DSH 舞台壁纸独立一张图，不与原生界面共用。 */
  private static final String IMAGE_NAME_LANDSCAPE = "appearance-bg-landscape.jpg";
  /** 205 新增：每次 apply 的可读快照，真机排查时直接看这个文件。 */
  private static final String TRACE_NAME = "appearance-apply.txt";
  private static final String EXTERNAL_DIR = "/sdcard/Download/DSHA/";
  private static final String EXTERNAL = EXTERNAL_DIR + CONFIG_NAME;
  private static final String EXTERNAL_IMAGE = EXTERNAL_DIR + IMAGE_NAME;
  /** v2 = 只作用原生界面 + 显式背景层 + 遮罩；没有该字段的是 191~200 的旧配置。 */
  private static final int CONFIG_VERSION = 2;

  private static final float DEFAULT_SCRIM = 0.35f;
  private static final float MAX_SCRIM = 0.9f;

  private static SoftReference<Bitmap> cachedBitmap;
  private static String cachedKey = "";
  /**
   * 内置默认壁纸（深浅各一张）的 ConstantState 缓存。
   *
   * <p>缓存 ConstantState 而不是 Drawable 本体：多个 ImageView 共享同一份位图（约 14 MB），
   * 但各自 newDrawable 出独立的 Drawable 状态，bounds / alpha 不会互相踩。
   */
  private static SoftReference<android.graphics.drawable.Drawable.ConstantState> cachedDefault;
  private static String cachedDefaultKey = "";
  /** 206：最近一次配置读取失败的原因；自检直接显示，不再静默。 */
  private static String lastIoError = "";
  /** 206：最近一次写入后的回读结论（期望 type / 读回 type），证明「写得进也读得回」。 */
  private static String lastWriteBack = "";
  /**
   * apply 追踪写盘改到单线程，且内容没变就不写。
   *
   * <p>主题切换会让 Activity 重建，onCreate + 每个 Fragment 各 apply 一次，
   * 原来每次都同步写私有目录和 /sdcard 两份文件 —— 主线程 I/O 白白拖慢重建。
   */
  private static final java.util.concurrent.Executor TRACE_IO =
      java.util.concurrent.Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "dsha-appearance-trace");
            thread.setDaemon(true);
            return thread;
          });
  private static volatile String lastTraceKey = "";
  /**
   * 配置 JSON 的内存缓存（按 路径+大小+mtime 判新鲜度）。
   *
   * <p>apply 一次要读配置好几回（apply 自己、presetLabel、各 Fragment 的 apply），
   * 原来是每次都读盘 + 解析一遍，重建时就把主线程攒起来了。
   */
  private static JSONObject cachedRoot;
  private static String cachedRootKey = "";

  private DshAppearance() {}

  /** 解析后的一份背景配置。 */
  public static final class Spec {
    public String type = "default";
    public String color = "";
    public String path = "";
    public float alpha = 1f;
    public float scrim = DEFAULT_SCRIM;
    /** 配置写着 image，但图片文件已经不在了 —— 界面要明确提示，不能装成「没设置」。 */
    public boolean missing;

    public boolean isImage() {
      return "image".equals(type) && !path.isEmpty();
    }

    public boolean isTransparent() {
      return "transparent".equals(type);
    }
  }

  // ---------------------------------------------------------------------------
  // 读写
  // ---------------------------------------------------------------------------

  private static File configFile(Context context) {
    return new File(context.getFilesDir(), CONFIG_NAME);
  }

  public static File imageFile(Context context) {
    return imageFile(context, "app");
  }

  /** 每个区域一张私有图：{@code app} = 原生界面，{@code landscape} = 横屏 DSH 舞台。 */
  public static File imageFile(Context context, String area) {
    return new File(
        context.getFilesDir(), "landscape".equals(area) ? IMAGE_NAME_LANDSCAPE : IMAGE_NAME);
  }

  /** 读取当前外观；缺省/坏文件一律回落到「默认」。 */
  public static Spec read(Context context) {
    return readArea(context, "app");
  }

  /** 横屏 DSH 舞台壁纸：独立配置，不与原生界面共用。 */
  public static Spec readLandscape(Context context) {
    return readArea(context, "landscape");
  }

  private static Spec readArea(Context context, String area) {
    JSONObject root = readJsonCached(context == null ? null : configFile(context));
    boolean legacy = false;
    if (root == null) {
      root = readJson(new File(EXTERNAL));
      if (root != null) legacy = !root.has("v");
    } else {
      legacy = !root.has("v");
    }
    Spec spec = parse(root, context, area);
    spec = repairMisplacedArea(context, area, spec);
    if (legacy && "app".equals(area) && (spec.isTransparent() || "default".equals(spec.type))) {
      // 191~200 的「透明」只是把 window 设成透明，页面根布局照样是实心白。
      // 升级后：私有目录里还留着旧上传图就直接接回来，没有才回默认。
      File old = context == null ? null : imageFile(context);
      if (old != null && old.isFile()) {
        Log.i(TAG, "legacy config -> restore private image");
        spec = new Spec();
        spec.type = "image";
        spec.path = old.getAbsolutePath();
        spec.scrim = 0.35f;
      } else if (spec.isTransparent()) {
        Log.i(TAG, "legacy transparent config -> default");
        spec = new Spec();
      }
    }
    return spec;
  }

  /**
   * 210 早批次的写错段现场自愈（数据修复，不是布局）。
   *
   * <p>那批包里「横屏壁纸」的裁剪结果把配置写进了 {@code app} 段：竖屏界面被换成横屏图，
   * {@code landscape} 段却还是 default。这里按文件现状把两份配置归位，
   * 用户不必重设两次：
   *
   * <ul>
   *   <li>app 段指向横屏图 → 换回自己的 {@code appearance-bg.jpg}（没有就按丢失处理）；
   *   <li>landscape 段仍是默认、但 {@code appearance-bg-landscape.jpg} 已经存在 → 接回来。
   * </ul>
   */
  private static Spec repairMisplacedArea(Context context, String area, Spec spec) {
    if (context == null || spec == null) return spec;
    try {
      if ("app".equals(area) && spec.isImage() && spec.path.endsWith(IMAGE_NAME_LANDSCAPE)) {
        File own = imageFile(context, "app");
        if (own.isFile()) {
          Log.i(TAG, "repair: app 段错指横屏图 -> " + own.getAbsolutePath());
          spec.path = own.getAbsolutePath();
          // 把配置也修干净（只做一次）：否则外部 JSON 一直指着横屏图，排查时看着矛盾。
          if (!context
              .getSharedPreferences("dsh-appearance", Context.MODE_PRIVATE)
              .getBoolean("repair_210_app_done", false)) {
            writeArea(context, "app", spec);
            context
                .getSharedPreferences("dsh-appearance", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("repair_210_app_done", true)
                .apply();
            Log.i(TAG, "repair: app 段配置已写回");
          }
        } else {
          Log.w(TAG, "repair: app 段错指横屏图，且本机没有竖向图");
          spec.type = "default";
          spec.path = "";
          spec.missing = true;
        }
      }
      if ("landscape".equals(area) && !spec.isImage()) {
        File own = imageFile(context, "landscape");
        if (own.isFile() && own.length() > 0) {
          Log.i(TAG, "repair: landscape 段缺配置，用已落盘的图恢复");
          Spec fixed = new Spec();
          fixed.type = "image";
          fixed.path = own.getAbsolutePath();
          fixed.alpha = 1f;
          fixed.scrim = clampScrim(spec.scrim);
          // 顺手把配置段补回去，下次读就不用再修。
          writeArea(context, "landscape", fixed);
          return fixed;
        }
      }
    } catch (Throwable error) {
      Log.w(TAG, "repair: " + error, error);
    }
    return spec;
  }

  private static Spec parse(JSONObject root, Context context, String area) {
    Spec spec = new Spec();
    if (root == null) return spec;
    JSONObject app = root.optJSONObject(area);
    if (app == null) return spec;
    String type = app.optString("type", "default");
    if (!"transparent".equals(type) && !"color".equals(type) && !"image".equals(type)) {
      return spec;
    }
    spec.type = type;
    spec.color = app.optString("color", "");
    spec.path = app.optString("path", "");
    spec.alpha = clamp01((float) app.optDouble("alpha", 1.0));
    spec.scrim = clampScrim((float) app.optDouble("scrim", DEFAULT_SCRIM));
    if ("color".equals(type) && spec.color.isEmpty()) spec.type = "default";
    if ("image".equals(type)) {
      // 旧配置里可能写的是 /sdcard 路径；有同名的应用私有副本就优先用它。
      File internal = context == null ? null : imageFile(context, area);
      if (!new File(spec.path).isFile() && internal != null && internal.isFile()) {
        spec.path = internal.getAbsolutePath();
      }
      if (spec.path.isEmpty() || !new File(spec.path).isFile()) {
        // 205：私有副本和配置里的路径都失效时，再试 191~200 写过的外部镜像图；
        // 都没有才算「图片丢失」，并在界面上说清楚，而不是静默退回白卡。
        // 横屏是 210 才独立出来的一块，没有 191~200 的老镜像可回退。
        File legacy =
            new File("landscape".equals(area) ? EXTERNAL_DIR + IMAGE_NAME_LANDSCAPE : EXTERNAL_IMAGE);
        boolean recovered = false;
        if (legacy.isFile() && internal != null) {
          try {
            copyFile(legacy, internal);
            spec.path = internal.getAbsolutePath();
            recovered = true;
            Log.i(TAG, "recovered image from " + EXTERNAL_IMAGE);
          } catch (Throwable error) {
            Log.w(TAG, "recover image: " + error, error);
          }
        }
        if (!recovered) {
          spec.type = "default";
          spec.missing = true;
        }
      }
    }
    return spec;
  }

  private static void copyFile(File from, File to) throws IOException {
    File parent = to.getParentFile();
    if (parent != null) parent.mkdirs();
    try (InputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
    }
  }

  /** 应用私有配置，尽量再镜像一份到 /sdcard 方便外部查看；写不进去不影响主路径。 */
  private static boolean writeJson(Context context, Spec spec) {
    return writeArea(context, "app", spec);
  }

  /**
   * 只改一个区域的段，另一个区域原样保留。
   *
   * <p>210：横屏壁纸从「共用 app 段」改成独立 {@code landscape} 段。这里如果还按老写法
   * 整份重建 root，选横屏图就会把竖屏配置冲掉（拖遮罩同理）。
   */
  public static boolean writeArea(Context context, String area, Spec spec) {
    JSONObject root;
    try {
      root = readJson(context == null ? null : configFile(context));
      if (root == null) root = readJson(new File(EXTERNAL));
      if (root == null) root = new JSONObject();
      if (!root.has("v")) root.put("v", CONFIG_VERSION);
      JSONObject node = new JSONObject();
      node.put("type", spec.type);
      if ("color".equals(spec.type)) node.put("color", spec.color);
      if ("image".equals(spec.type)) {
        node.put("path", spec.path);
        node.put("alpha", spec.alpha);
        node.put("scrim", spec.scrim);
      }
      root.put(area, node);
      // 另一块与 portrait 保持原值；缺键时补 default，避免旧版本读到缺键按别的语义处理。
      String other = "app".equals(area) ? "landscape" : "app";
      if (!root.has(other)) root.put(other, new JSONObject().put("type", "default"));
      if (!root.has("portrait")) root.put("portrait", new JSONObject().put("type", "default"));
    } catch (Throwable error) {
      Log.w(TAG, "writeArea build: " + error.getClass().getSimpleName());
      return false;
    }
    invalidateCache();
    return writeRaw(context, root, area);
  }

  /** 直接把一份完整 JSON 写回两处；setScrim 用它，避免把 image 配置降级成 default。 */
  private static boolean writeRaw(Context context, JSONObject root, String area) {
    String text;
    String expect;
    try {
      text = root.toString(2);
      JSONObject node = root.optJSONObject(area);
      expect = node == null ? "" : node.optString("type", "");
    } catch (Throwable error) {
      Log.w(TAG, "writeRaw: " + error.getClass().getSimpleName());
      return false;
    }
    boolean ok = false;
    if (context != null) {
      ok = writeText(configFile(context), text);
      invalidateReadCache();
      // 206：写完立刻回读。写过却读不回来 = 真机白卡的真正原因，必须留下证据。
      JSONObject check = readJson(configFile(context));
      JSONObject checked = check == null ? null : check.optJSONObject(area);
      String back = checked == null ? "<读不回>" : checked.optString("type", "<空>");
      lastWriteBack = "expect=" + expect + " readBack=" + back;
      if (!"<读不回>".equals(back) && !expect.equals(back)) ok = false;
    }
    try {
      File external = new File(EXTERNAL);
      File parent = external.getParentFile();
      if (parent != null) parent.mkdirs();
      writeText(external, text);
    } catch (Throwable ignored) {
    }
    return ok;
  }

  private static boolean writeText(File file, String text) {
    try {
      File parent = file.getParentFile();
      if (parent != null) parent.mkdirs();
      try (FileOutputStream out = new FileOutputStream(file)) {
        out.write(text.getBytes(StandardCharsets.UTF_8));
      }
      return true;
    } catch (Throwable error) {
      Log.w(TAG, "writeFile: " + error.getClass().getSimpleName());
      return false;
    }
  }

  /**
   * 206 根因：读配置原本用 {@code java.nio.file.Files.readString(file.toPath())}。
   *
   * <p>真机证据（205 自检 + 手机侧文件）：选图时 {@code FileOutputStream} 写配置成功、
   * 外部 JSON 一直是 {@code type=image}、apply 快照也写成功了；但 App 自己读回来永远是
   * {@code type=default}，连 apply 快照都读不出来。也就是说 <b>Files.readString 在这台机器上
   * 静默失败并返回 null</b>，而 {@code FileInputStream} 正常 —— 这正是 201~205「设置成功、
   * 界面永远白卡」的根因。
   *
   * <p>现在改为最朴素的 FileInputStream 读取，并把失败原因记进 {@link #lastIoError}，
   * 写进自检与 apply 快照，不再静默。
   */
  /** 读配置：同一份文件（大小与 mtime 都没变）只读盘+解析一次。 */
  private static JSONObject readJsonCached(File file) {
    if (file == null || !file.isFile()) return readJson(file);
    final String key = file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
    JSONObject cached = cachedRoot;
    if (cached != null && key.equals(cachedRootKey)) return cached;
    JSONObject fresh = readJson(file);
    if (fresh != null) {
      cachedRoot = fresh;
      cachedRootKey = key;
    } else {
      cachedRoot = null;
      cachedRootKey = "";
    }
    return fresh;
  }

  private static void invalidateReadCache() {
    cachedRoot = null;
    cachedRootKey = "";
  }

  private static JSONObject readJson(File file) {
    if (file == null || !file.isFile()) return null;
    try {
      byte[] bytes = readAllBytes(file);
      if (bytes == null || bytes.length == 0) return null;
      String raw = new String(bytes, StandardCharsets.UTF_8);
      if (raw.trim().isEmpty()) return null;
      return new JSONObject(raw);
    } catch (Throwable error) {
      lastIoError =
          file.getName()
              + ": "
              + error.getClass().getSimpleName()
              + (error.getMessage() == null ? "" : " " + error.getMessage());
      Log.w(TAG, "readJson: " + lastIoError, error);
      return null;
    }
  }

  /** 不用 Files.readString / readAllBytes：那条路在本机返回空，改用最朴素的流读。 */
  private static byte[] readAllBytes(File file) throws IOException {
    try (InputStream in = new FileInputStream(file);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
      return out.toByteArray();
    }
  }

  // ---------------------------------------------------------------------------
  // 预设 / 图片 / 遮罩
  // ---------------------------------------------------------------------------

  /** 207：只保留「默认」和「透明（兼容旧配置）」；深色/暖色预设已按最小完备集砍掉。 */
  public static boolean writePreset(Context context, String preset) {
    Spec spec = new Spec();
    if ("default".equals(preset)) {
      spec.type = "default";
    } else if ("transparent".equals(preset)) {
      spec.type = "transparent";
      spec.scrim = 0f;
    } else {
      return false;
    }
    return writeJson(context, spec);
  }

  /** 选图结果：成功与否 + 一句能直接给用户看的原因。 */
  public static final class ImportResult {
    public final boolean ok;
    public final String message;

    ImportResult(boolean ok, String message) {
      this.ok = ok;
      this.message = message;
    }
  }

  private static void rememberPick(Context context, String message) {
    try {
      context
          .getSharedPreferences("dsh-appearance", Context.MODE_PRIVATE)
          .edit()
          .putString("last_pick", message)
          .apply();
    } catch (Throwable ignored) {
    }
  }

  public static String lastPickMessage(Context context) {
    try {
      return context
          .getSharedPreferences("dsh-appearance", Context.MODE_PRIVATE)
          .getString("last_pick", "");
    } catch (Throwable error) {
      return "";
    }
  }

  /** 从相册选图：复制进应用私有目录，再写成 image 配置。失败也把原因留下来。 */
  public static ImportResult importImageDetailed(Context context, Uri uri) {
    return importImageDetailedArea(context, uri, "app");
  }

  /** 210：按区域导入（横屏舞台有自己的图与配置段）。 */
  public static ImportResult importImageDetailedArea(Context context, Uri uri, String area) {
    if (context == null || uri == null) return new ImportResult(false, "没有拿到图片 URI");
    File out = imageFile(context, area);
    String scheme = uri.getScheme() == null ? "" : uri.getScheme();
    long bytes = 0;
    try {
      try (InputStream in = openImageStream(context, uri);
          FileOutputStream stream = new FileOutputStream(out)) {
        if (in == null) {
          String message = "打开图片流失败（scheme=" + scheme + "）";
          rememberPick(context, message);
          return new ImportResult(false, message);
        }
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
          stream.write(buffer, 0, read);
          bytes += read;
        }
      }
      if (!out.isFile() || out.length() <= 0) {
        String message = "复制后文件为空（bytes=" + bytes + "）";
        rememberPick(context, message);
        return new ImportResult(false, message);
      }
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(out.getAbsolutePath(), bounds);
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        String message =
            "文件复制成功（" + out.length() + " B），但解码失败：可能不是图片或格式不支持";
        rememberPick(context, message);
        return new ImportResult(false, message);
      }
      // 205：相册原图动辄 8000×6000，原样存着每次 onResume 都要按 4096 上限解一次 ≈48 MB，
      // 真机上很容易 OOM → BitmapFactory 返回 null → 「配置对、图也在、就是白卡」。
      normalizeImage(out);
      bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(out.getAbsolutePath(), bounds);
      Spec current = "landscape".equals(area) ? readLandscape(context) : read(context);
      Spec spec = new Spec();
      spec.type = "image";
      spec.path = out.getAbsolutePath();
      spec.alpha = 1f;
      spec.scrim = current.isImage() ? current.scrim : DEFAULT_SCRIM;
      boolean written = writeArea(context, area, spec);
      invalidateCache();
      mirrorToExternal(out, area);
      String message =
          "已复制 " + out.length() + " B，" + bounds.outWidth + "×" + bounds.outHeight
              + "，配置" + (written ? "写入成功" : "写入失败") + "（" + out.getAbsolutePath() + "）";
      rememberPick(context, message);
      return new ImportResult(written, message);
    } catch (Throwable error) {
      String message =
          "复制图片失败（" + error.getClass().getSimpleName()
              + (error.getMessage() == null ? "" : ": " + error.getMessage())
              + "，scheme=" + scheme + "）";
      rememberPick(context, message);
      return new ImportResult(false, message);
    }
  }

  public static boolean importImage(Context context, Uri uri) {
    return importImageDetailed(context, uri).ok;
  }

  public static boolean importImageArea(Context context, Uri uri, String area) {
    return importImageDetailedArea(context, uri, area).ok;
  }

  /** 选图后先复制到缓存，再进裁剪页：跨 Activity 传文件路径比传 content:// 稳。 */
  public static File copyPickToCache(Context context, Uri uri) {
    if (context == null || uri == null) return null;
    File out = new File(context.getCacheDir(), "appearance-pick.jpg");
    try (InputStream in = openImageStream(context, uri);
        FileOutputStream stream = new FileOutputStream(out)) {
      if (in == null) return null;
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) > 0) stream.write(buffer, 0, read);
      return out.isFile() && out.length() > 0 ? out : null;
    } catch (Throwable error) {
      Log.w(TAG, "copyPickToCache: " + error.getClass().getSimpleName());
      return null;
    }
  }

  /** 裁剪页确认后：写私有图 + 配置 + 外部镜像 + 回读校验，和 importImage 走同一套收尾。 */
  public static ImportResult commitCroppedBitmap(Context context, Bitmap bitmap) {
    return commitCroppedBitmapArea(context, bitmap, "app");
  }

  /** 210：横屏壁纸裁剪结果单独落一张图，只改 {@code landscape} 段。 */
  public static ImportResult commitCroppedBitmapArea(
      Context context, Bitmap bitmap, String area) {
    if (context == null || bitmap == null) return new ImportResult(false, "没有裁剪结果");
    File out = imageFile(context, area);
    Bitmap scaled = bitmap;
    try {
      int longSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
      int maxLong = screenLongSide();
      if (longSide > maxLong) {
        float factor = (float) maxLong / longSide;
        scaled =
            Bitmap.createScaledBitmap(
                bitmap,
                Math.max(1, Math.round(bitmap.getWidth() * factor)),
                Math.max(1, Math.round(bitmap.getHeight() * factor)),
                true);
      }
      try (FileOutputStream stream = new FileOutputStream(out)) {
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
          rememberPick(context, "裁剪结果压缩失败");
          return new ImportResult(false, "裁剪结果压缩失败");
        }
      } finally {
        if (scaled != bitmap) scaled.recycle();
      }
      if (!out.isFile() || out.length() <= 0) {
        rememberPick(context, "裁剪结果为空");
        return new ImportResult(false, "裁剪结果为空");
      }
      // 210 修正：这里原来写死 read(context)/writeJson(context, spec)（只动 app 段），
      // 横屏选图会把横屏图写进竖屏配置、landscape 段反而没写 —— 用户实测「竖屏跟着换、
      // 横屏不生效」就是这一处。现在读、写、遮罩都按 area 走。
      Spec current = "landscape".equals(area) ? readLandscape(context) : read(context);
      Spec spec = new Spec();
      spec.type = "image";
      spec.path = out.getAbsolutePath();
      spec.alpha = 1f;
      spec.scrim = current.isImage() ? current.scrim : DEFAULT_SCRIM;
      boolean written = writeArea(context, area, spec);
      invalidateCache();
      mirrorToExternal(out, area);
      String message =
          "已裁剪 " + out.length() + " B，" + (written ? "配置写入成功" : "配置写入失败")
              + "（" + out.getAbsolutePath() + "）";
      rememberPick(context, message);
      return new ImportResult(written, message);
    } catch (Throwable error) {
      String message = "保存裁剪结果失败（" + error.getClass().getSimpleName() + "）";
      rememberPick(context, message);
      return new ImportResult(false, message);
    }
  }

  /**
   * 205：把私有副本再镜像一份到 {@code /sdcard/Download/DSHA/appearance-bg.jpg}。
   *
   * <p>三个用途：外部能直接确认「图到底在不在、是什么格式」；重装/换机能恢复；
   * 真机排查时排查者不用进 /data。写不进去只记日志，不影响主路径。
   */
  private static void mirrorToExternal(File source, String area) {
    if (source == null || !source.isFile()) return;
    try {
      File dir = new File(EXTERNAL_DIR);
      if (!dir.isDirectory() && !dir.mkdirs()) return;
      File target =
          new File(dir, "landscape".equals(area) ? IMAGE_NAME_LANDSCAPE : IMAGE_NAME);
      if (target.equals(source)) return;
      copyFile(source, target);
    } catch (Throwable error) {
      Log.w(TAG, "mirror image: " + error.getClass().getSimpleName());
    }
  }

  /** content:// 走 ContentResolver，file:// 走 FileInputStream；两条都不通再报错。 */
  private static InputStream openImageStream(Context context, Uri uri) throws IOException {
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
    if ("file".equals(scheme) && uri.getPath() != null) {
      return new FileInputStream(uri.getPath());
    }
    InputStream in = context.getContentResolver().openInputStream(uri);
    if (in != null) return in;
    android.os.ParcelFileDescriptor pfd =
        context.getContentResolver().openFileDescriptor(uri, "r");
    if (pfd != null) return new android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd);
    return null;
  }

  /** 外部脚本/调试用：直接把某个可读文件设成背景图。 */
  public static boolean setScrim(Context context, float scrim) {
    return setScrimArea(context, "app", scrim);
  }

  /** 210：横屏舞台的遮罩单独存，拖横屏滑杆不会动到竖屏配置。 */
  public static boolean setScrimArea(Context context, String area, float scrim) {
    // 不能走 read() → writeJson()：图片文件一时读不到时 read() 会把 spec 降级成
    // default，拖一下遮罩滑杆就把 image 配置整个写没了（205 修）。
    JSONObject root = readJson(context == null ? null : configFile(context));
    if (root == null || root.optJSONObject(area) == null) root = readJson(new File(EXTERNAL));
    if (root == null || root.optJSONObject(area) == null) return false;
    try {
      root.optJSONObject(area).put("scrim", (double) clampScrim(scrim));
    } catch (Throwable error) {
      Log.w(TAG, "setScrimArea: " + error.getClass().getSimpleName());
      return false;
    }
    return writeRaw(context, root, area);
  }

  public static float currentScrim(Context context) {
    return read(context).scrim;
  }

  public static float currentLandscapeScrim(Context context) {
    return readLandscape(context).scrim;
  }

  /** 诊断用：把底图换成纯色。能看见色 = 图层链路通，问题在图片；看不见 = 图层被盖/没应用。 */
  public static boolean clearImage(Context context) {
    return clearImageArea(context, "app");
  }

  /** 210：只清某个区域的壁纸（横屏舞台有自己一份）。 */
  public static boolean clearImageArea(Context context, String area) {
    try {
      File file = imageFile(context, area);
      if (file.isFile()) file.delete();
    } catch (Throwable ignored) {
    }
    invalidateCache();
    Spec spec = new Spec();
    spec.type = "default";
    return writeArea(context, area, spec);
  }

  private static void invalidateCache() {
    cachedBitmap = null;
    cachedKey = "";
  }

  // ---------------------------------------------------------------------------
  // 应用
  // ---------------------------------------------------------------------------

  public static boolean isTransparent(String area) {
    return false;
  }

  public static boolean isTransparent(Context context, String area) {
    return "app".equals(area) && read(context).isTransparent();
  }

  // ---------------------------------------------------------------------------
  // 全面屏：底图铺满整屏（含状态栏/导航栏区域）+ 竖屏隐藏状态栏开关
  // ---------------------------------------------------------------------------

  private static final String KEY_FULLSCREEN = "fullscreen_status_bar";

  /** 竖屏隐藏状态栏：设置页开关，默认开启（壁纸要铺到屏幕顶部）。 */
  /**
   * 竖屏是否隐藏状态栏（壁纸铺满到屏幕顶部）。
   *
   * <p>默认**关闭**：新装用户先看到正常状态栏，需要沉浸式再自己去开。
   * 老用户已存过值的不受影响（只改读取时的 fallback）。
   */
  public static boolean fullscreenStatusBar(Context context) {
    try {
      return context
          .getSharedPreferences("dsh-appearance", Context.MODE_PRIVATE)
          .getBoolean(KEY_FULLSCREEN, false);
    } catch (Throwable error) {
      return false;
    }
  }

  public static void setFullscreenStatusBar(Context context, boolean on) {
    try {
      context
          .getSharedPreferences("dsh-appearance", Context.MODE_PRIVATE)
          .edit()
          .putBoolean(KEY_FULLSCREEN, on)
          .commit();
    } catch (Throwable ignored) {
    }
  }

  /**
   * 把窗口改成 edge-to-edge：状态栏/导航栏透明，内容延伸到系统栏底下，
   * 底图（{@code app_background}）才能真正铺满整屏，而不是卡在导航栏上沿。
   *
   * <p>只有带 {@code app_bar} / {@code bottom_nav} 的页面需要；外观页自己传 anchor。
   */
  public static void applyEdgeToEdge(Activity activity) {
    if (activity == null) return;
    try {
      android.view.Window window = activity.getWindow();
      window.setStatusBarColor(Color.TRANSPARENT);
      window.setNavigationBarColor(Color.TRANSPARENT);
      androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false);
    } catch (Throwable error) {
      Log.w(TAG, "edgeToEdge: " + error, error);
      return;
    }
    View bar = activity.findViewById(R.id.app_bar);
    View nav = activity.findViewById(R.id.bottom_nav);
    insetAnchor(bar, true);
    insetAnchor(nav, false);
  }

  /**
   * 给顶部栏补状态栏高度、底部栏补导航栏高度；系统栏被隐藏时不再补，让内容顶到屏幕边。
   *
   * <p>210 用户验收：底栏要「贴到屏幕最底、下面不留缝」。所以底部恢复 padding 避让 ——
   * 底栏那层玻璃一直画到屏幕最底，手势条压在上面；209 试过的 margin 让位会在下面露一条壁纸缝。
   */
  private static void insetAnchor(View view, boolean top) {
    if (view == null) return;
    final int baseTop = view.getPaddingTop();
    final int baseBottom = view.getPaddingBottom();
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
        view,
        (v, insets) -> {
          androidx.core.graphics.Insets bars =
              insets.getInsets(
                  androidx.core.view.WindowInsetsCompat.Type.systemBars()
                      | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
          if (top) {
            int extra =
                insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                    ? bars.top
                    : 0;
            v.setPadding(
                v.getPaddingLeft(), baseTop + extra, v.getPaddingRight(), v.getPaddingBottom());
          } else {
            int extra =
                insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                    ? bars.bottom
                    : 0;
            v.setPadding(
                v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), baseBottom + extra);
          }
          return insets;
        });
  }

  /** 按设置显示/隐藏竖屏状态栏；隐藏后 insets 归零，顶部栏自然顶到屏幕边。 */
  public static void applyStatusBarVisibility(Activity activity) {
    if (activity == null) return;
    try {
      android.view.Window window = activity.getWindow();
      androidx.core.view.WindowInsetsControllerCompat controller =
          androidx.core.view.WindowCompat.getInsetsController(window, window.getDecorView());
      if (fullscreenStatusBar(activity)) {
        controller.setSystemBarsBehavior(
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars());
      } else {
        controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars());
      }
    } catch (Throwable error) {
      Log.w(TAG, "statusBar: " + error, error);
    }
  }

  /**
   * 把配置应用到界面。只处理 {@code app} 区域；其它区域（portrait / landscape）
   * 现在由网页和横屏自己负责，这里明确不动。
   *
   * <p>{@code target} 只为兼容旧调用保留，实际铺在 {@code app_background} /
   * {@code app_scrim} 两个显式层上 —— 每个页面根布局都是透明的，目标 View 不再需要背景。
   */
  public static void apply(Activity activity, String area, View target) {
    if (activity == null || !"app".equals(area)) return;
    final Spec spec;
    try {
      spec = read(activity);
    } catch (Throwable error) {
      Log.w(TAG, "read: " + error, error);
      return;
    }
    StringBuilder trace = new StringBuilder("apply\n");
    trace
        .append("spec type=")
        .append(spec.type)
        .append(" scrim=")
        .append(Math.round(clampScrim(spec.scrim) * 100f))
        .append("% missing=")
        .append(spec.missing)
        .append(" night=")
        .append(isNight(activity))
        .append('\n');
    if (!spec.path.isEmpty()) {
      File file = new File(spec.path);
      trace.append("path=").append(spec.path).append('\n');
      trace.append("file=").append(file.isFile() ? file.length() + " B" : "不存在").append('\n');
      if (file.isFile()) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        trace.append("decode=").append(bounds.outWidth).append('x').append(bounds.outHeight).append('\n');
      }
    }
    View shell = activity.findViewById(R.id.app_shell);
    if (shell == null) shell = activity.findViewById(android.R.id.content);
    ImageView background = shell == null ? null : shell.findViewById(R.id.app_background);
    // 238：这里只查找。没有 app_scrim 的页面（后台任务等）在 ② 里补，
    // 因为那一步必须晚于「① 铺底图」—— 否则新加的遮罩层会排在内容之上，把整页盖黑。
    View scrim = shell == null ? null : shell.findViewById(R.id.app_scrim);

    // ① 先铺底图。顺序很重要：旧版把 window.setFormat/setBackgroundDrawable 放在前面，
    //    某些 ROM 上那一步抛异常/重排，就直接把后面的 ImageView 跳过了 —— 表现就是「完全没有背景」。
    Drawable drawable = null;
    if (!spec.missing) {
      try {
        drawable = drawable(activity, spec);
      } catch (Throwable error) {
        Log.w(TAG, "drawable: " + error, error);
        trace.append("drawableError=").append(error.getClass().getSimpleName()).append('\n');
      }
    }
    // 214：没设自定义壁纸（或图丢了）时用内置壁纸兜底，不再是一块纯白/纯灰底。
    // 218：内置壁纸分深浅两张（drawable-nodpi / drawable-night-nodpi），由 uiMode 自动选，
    //      切换主题时这里拿到的就已经是对应那张，不需要额外通知。
    if (drawable == null) {
      drawable = defaultWallpaper(activity);
      if (drawable == null) trace.append("defaultWallpaper=null\n");
    }
    if (background == null
        && drawable != null
        && shell instanceof android.view.ViewGroup) {
      // 205 兜底：这个页面没有 app_background（自绘页 / 旧布局 / 别的 Activity），
      // 由代码在最底层补一层，壁纸不再依赖某一份布局 XML 是否被正确打包。
      background = installBackgroundLayer(activity, (android.view.ViewGroup) shell);
      trace.append("bgLayer=created\n");
    }
    if (background != null) {
      try {
        background.setImageDrawable(drawable);
        background.setVisibility(drawable == null ? View.INVISIBLE : View.VISIBLE);
        background.invalidate();
        trace
            .append("bgView vis=")
            .append(background.getVisibility())
            .append(" drawable=")
            .append(drawable == null ? "null" : drawable.getClass().getSimpleName())
            .append(" size=")
            .append(background.getWidth())
            .append('x')
            .append(background.getHeight())
            .append('\n');
      } catch (Throwable error) {
        Log.w(TAG, "setImageDrawable: " + error, error);
        trace.append("bgViewError=").append(error.getClass().getSimpleName()).append('\n');
      }
    } else {
      trace.append("bgView=null\n");
    }

    // ② 遮罩（兜底创建放在这里：底图已铺好，新层插在它后面才是「背景之上、内容之下」）
    if (scrim == null && shell instanceof android.view.ViewGroup) {
      // 238：后台任务这类自绘页面没有 app_scrim 层。不补的话，卡片调透明后壁纸直接顶着
      // 文字，深色图上读不清。
      scrim = installScrimLayer(activity, (android.view.ViewGroup) shell, background);
      trace.append("scrimLayer=created\n");
    }
    if (scrim != null) {
      try {
        int alpha = spec.isImage() ? Math.round(clampScrim(spec.scrim) * 255f) : 0;
        if (alpha > 0) {
          scrim.setBackgroundColor(withAlpha(scrimColor(activity), alpha));
          scrim.setVisibility(View.VISIBLE);
        } else {
          scrim.setBackgroundColor(Color.TRANSPARENT);
          scrim.setVisibility(View.GONE);
        }
        trace.append("scrim alpha=").append(alpha).append('\n');
      } catch (Throwable error) {
        Log.w(TAG, "scrim: " + error, error);
      }
    }

    // ③ 文字保护
    protectText(activity, spec.isImage());

    // ④ window 收尾，逐项隔离：失败只记日志，不再影响底图
    try {
      shell.setBackgroundColor(Color.TRANSPARENT);
    } catch (Throwable error) {
      Log.w(TAG, "shell: " + error, error);
    }
    boolean transparent = spec.isTransparent();
    // 218：这两个动作都会推动整窗重绘/Surface 重配，值没变就别重复设。
    try {
      android.view.Window window = activity.getWindow();
      final int format = transparent ? PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE;
      final int windowBackground =
          transparent ? Color.TRANSPARENT : activity.getColor(R.color.surface);
      final String signature = format + ":" + windowBackground;
      View decor = window.getDecorView();
      Object applied = decor.getTag(R.id.tag_appearance_window);
      if (!signature.equals(applied)) {
        window.setFormat(format);
        window.setBackgroundDrawable(new ColorDrawable(windowBackground));
        decor.setTag(R.id.tag_appearance_window, signature);
      }
    } catch (Throwable error) {
      Log.w(TAG, "window format/background: " + error, error);
    }
    try {
      android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
      trace.append("screen=").append(metrics.widthPixels).append('x').append(metrics.heightPixels)
          .append(" density=").append(metrics.density).append('\n');
    } catch (Throwable ignored) {
    }
    writeTrace(activity, trace.toString());
  }

  /** 没有 app_background 的页面：用代码补一层最底层的 ImageView。 */
  private static ImageView installBackgroundLayer(Activity activity, android.view.ViewGroup shell) {
    try {
      ImageView background = new ImageView(activity);
      background.setId(R.id.app_background);
      background.setScaleType(ImageView.ScaleType.CENTER_CROP);
      background.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      background.setVisibility(View.INVISIBLE);
      shell.addView(
          background,
          0,
          new android.view.ViewGroup.LayoutParams(
              android.view.ViewGroup.LayoutParams.MATCH_PARENT,
              android.view.ViewGroup.LayoutParams.MATCH_PARENT));
      return background;
    } catch (Throwable error) {
      Log.w(TAG, "install background: " + error, error);
      return null;
    }
  }

  /** 没有 app_scrim 的页面：在背景层之上补一层全屏遮罩。 */
  private static View installScrimLayer(
      Activity activity, android.view.ViewGroup shell, View background) {
    try {
      View scrim = new View(activity);
      scrim.setId(R.id.app_scrim);
      scrim.setBackgroundColor(Color.TRANSPARENT);
      // 必须紧跟在底图后面：索引取底图的下一格，底图不存在才退回 0。
      int index = background == null ? 0 : shell.indexOfChild(background) + 1;
      shell.addView(
          scrim,
          Math.min(index, shell.getChildCount()),
          new android.view.ViewGroup.LayoutParams(
              android.view.ViewGroup.LayoutParams.MATCH_PARENT,
              android.view.ViewGroup.LayoutParams.MATCH_PARENT));
      return scrim;
    } catch (Throwable error) {
      Log.w(TAG, "install scrim: " + error);
      return null;
    }
  }

  /** 当前是否深色主题（和 scrimColor 同一套判据，唯一真源）。 */
  private static boolean isNight(Context context) {
    return context != null
        && (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
  }

  /**
   * 内置默认壁纸：浅色轮 `drawable-nodpi/default_wallpaper.jpg`，
   * 深色轮 `drawable-night-nodpi/default_wallpaper.jpg` —— 资源系统按 uiMode 自动挑，
   * 这里只负责「别重复解码」。
   *
   * <p>缓存键带上密度与深浅，切主题后键不同，自然换到另一张图而不是继续用旧位图。
   */
  private static Drawable defaultWallpaper(Context context) {
    if (context == null) return null;
    final String key = (isNight(context) ? "night" : "light")
        + "|" + context.getResources().getConfiguration().densityDpi;
    android.graphics.drawable.Drawable.ConstantState state =
        cachedDefault == null ? null : cachedDefault.get();
    if (state != null && key.equals(cachedDefaultKey)) {
      try {
        Drawable shared = state.newDrawable(context.getResources());
        if (shared != null) return shared;
      } catch (Throwable error) {
        Log.w(TAG, "default wallpaper reuse: " + error);
      }
    }
    Drawable fresh;
    try {
      fresh =
          androidx.core.content.ContextCompat.getDrawable(context, R.drawable.default_wallpaper);
    } catch (Throwable error) {
      Log.w(TAG, "default wallpaper: " + error);
      return null;
    }
    if (fresh != null) {
      android.graphics.drawable.Drawable.ConstantState constant = fresh.getConstantState();
      if (constant != null) {
        cachedDefault = new SoftReference<>(constant);
        cachedDefaultKey = key;
      }
    }
    return fresh;
  }

  /**
   * 把一次 apply 的结论写到私有目录，并尽力镜像到 /sdcard/Download/DSHA 供外部排查。
   *
   * <p>218：写盘挪到后台单线程，并先比对内容 —— 主题切换期间一次重建会 apply 好几次，
   * 原来的「每次两趟同步写文件」全压在主线程上。
   */
  private static void writeTrace(Activity activity, String text) {
    if (activity == null) return;
    final String key =
        text + "|" + (lastIoError.isEmpty() ? "无" : lastIoError) + "|"
            + (lastWriteBack.isEmpty() ? "无" : lastWriteBack);
    if (key.equals(lastTraceKey)) return;
    lastTraceKey = key;
    final String body =
        "207\n"
            + text
            + "ioError=" + (lastIoError.isEmpty() ? "无" : lastIoError) + '\n'
            + "writeBack=" + (lastWriteBack.isEmpty() ? "无" : lastWriteBack) + '\n'
            + "at=" + System.currentTimeMillis() + '\n';
    final Context appContext;
    try {
      appContext = activity.getApplicationContext();
    } catch (Throwable error) {
      return;
    }
    final File privateFile = new File(appContext.getFilesDir(), TRACE_NAME);
    TRACE_IO.execute(
        () -> {
          try {
            writeText(privateFile, body);
          } catch (Throwable ignored) {
          }
          try {
            File dir = new File(EXTERNAL_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            writeText(new File(dir, TRACE_NAME), body);
          } catch (Throwable ignored) {
          }
        });
  }

  /** 外部（自检 / 排查）读最近一次 apply 的结果。 */
  public static String applyTrace(Context context) {
    String[] candidates =
        context == null
            ? new String[] {EXTERNAL_DIR + TRACE_NAME}
            : new String[] {
              new File(context.getFilesDir(), TRACE_NAME).getAbsolutePath(),
              EXTERNAL_DIR + TRACE_NAME
            };
    for (String name : candidates) {
      try {
        File file = new File(name);
        if (!file.isFile()) continue;
        byte[] bytes = readAllBytes(file);
        if (bytes == null || bytes.length == 0) continue;
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text != null && !text.trim().isEmpty()) return text.trim();
      } catch (Throwable ignored) {
      }
    }
    return "";
  }

  /**
   * 横屏舞台底：视口外那圈留边也用和竖屏同一张壁纸。
   *
   * <p>没有配置 / 只有透明预设时返回 null，调用方退回舞台纯色（STAGE_COLOR）。
   */
  public static Drawable stageDrawable(Context context) {
    try {
      return drawable(context, readLandscape(context));
    } catch (Throwable error) {
      Log.w(TAG, "stageDrawable: " + error, error);
      return null;
    }
  }

  /** 横屏舞台遮罩色（独立配置里的 scrim；没有图时返回 0 = 不加遮罩）。 */
  public static int stageScrimColor(Context context) {
    try {
      Spec spec = readLandscape(context);
      if (!spec.isImage()) return 0;
      int alpha = Math.round(clampScrim(spec.scrim) * 255f);
      if (alpha <= 0) return 0;
      return withAlpha(scrimColor(context), alpha);
    } catch (Throwable error) {
      Log.w(TAG, "stageScrimColor: " + error, error);
      return 0;
    }
  }

  /** 供自绘页面（外观页）拿到底图；没有图返回 null。 */
  public static Drawable drawable(Context context, Spec spec) {
    if (spec == null) return null;
    if ("transparent".equals(spec.type) || "default".equals(spec.type)) return null;
    if ("color".equals(spec.type)) {
      try {
        return new ColorDrawable(Color.parseColor(spec.color));
      } catch (IllegalArgumentException bad) {
        return null;
      }
    }
    if ("image".equals(spec.type)) {
      Bitmap bitmap = decode(spec.path);
      if (bitmap == null) return null;
      BitmapDrawable out = new BitmapDrawable(context.getResources(), bitmap);
      out.setAlpha(Math.round(clamp01(spec.alpha) * 255f));
      return out;
    }
    return null;
  }

  /** 图片解码：按屏幕长边采样 + OOM 降级重试 + 单张缓存，避免每次 onResume 都重解原图。 */
  private static Bitmap decode(String path) {
    if (path == null || path.isEmpty()) return null;
    File file = new File(path);
    if (!file.isFile()) return null;
    String key = file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
    Bitmap cached = cachedBitmap == null ? null : cachedBitmap.get();
    if (cached != null && key.equals(cachedKey)) return cached;

    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
    BitmapFactory.Options options = new BitmapFactory.Options();
    // 205：原来固定 4096 上限，8000×6000 的相册原图会解出 4000×3000 ≈48 MB，
    // 真机一 OOM 就返回 null，界面表现就是「配置对、图也在、整片白卡」。
    options.inSampleSize = sampleFor(bounds, screenLongSide());
    Bitmap bitmap = decodeWithRetry(file.getAbsolutePath(), options);
    bitmap = rotateForExif(bitmap, file.getAbsolutePath());
    if (bitmap != null) {
      cachedBitmap = new SoftReference<>(bitmap);
      cachedKey = key;
    }
    return bitmap;
  }

  /** 目标长边：屏幕长边（1080×2400 → 2400）；拿不到就按 2048。 */
  private static int screenLongSide() {
    try {
      android.util.DisplayMetrics metrics =
          android.content.res.Resources.getSystem().getDisplayMetrics();
      int longSide = Math.max(metrics.widthPixels, metrics.heightPixels);
      if (longSide >= 720) return longSide;
    } catch (Throwable ignored) {
    }
    return 2048;
  }

  private static int sampleFor(BitmapFactory.Options bounds, int maxLongSide) {
    if (bounds == null || bounds.outWidth <= 0 || bounds.outHeight <= 0) return 1;
    int longSide = Math.max(bounds.outWidth, bounds.outHeight);
    int sample = 1;
    while (longSide / sample > maxLongSide) sample *= 2;
    return Math.max(1, sample);
  }

  /** 解码时 OOM 就加倍采样再试；4 次还不行返回 null，由界面按「无图」处理。 */
  private static Bitmap decodeWithRetry(String path, BitmapFactory.Options options) {
    for (int attempt = 0; attempt < 4; attempt++) {
      try {
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        if (bitmap != null) return bitmap;
        // 返回 null 说明连头都解不开，再降采样也没用。
        return null;
      } catch (OutOfMemoryError oom) {
        System.gc();
        options.inSampleSize = Math.max(2, options.inSampleSize * 2);
        Log.w(TAG, "decode OOM, retry sample=" + options.inSampleSize);
      }
    }
    return null;
  }

  /**
   * 选图后把原图规整成「屏幕够用」的 JPEG：长边压到 2400 以内，质量 92。
   *
   * <p>相册原图 8000×6000 原样存着，每次 onResume 都要解一次大图，是白卡/OOM 的根；
   * 规整后私有副本只有几百 KB，解码最多 2400 长边（约 14 MB），且 EXIF 方向已经烧进像素。
   * 失败就保留原图，不影响主路径。
   */
  private static void normalizeImage(File file) {
    if (file == null || !file.isFile()) return;
    try {
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return;
      int maxLong = screenLongSide();
      if (Math.max(bounds.outWidth, bounds.outHeight) <= maxLong) return;

      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inSampleSize = sampleFor(bounds, maxLong);
      Bitmap bitmap = decodeWithRetry(file.getAbsolutePath(), options);
      if (bitmap == null) return;
      bitmap = rotateForExif(bitmap, file.getAbsolutePath());
      if (bitmap == null) return;
      int longSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
      if (longSide > maxLong) {
        float scale = (float) maxLong / longSide;
        Bitmap scaled =
            Bitmap.createScaledBitmap(
                bitmap,
                Math.max(1, Math.round(bitmap.getWidth() * scale)),
                Math.max(1, Math.round(bitmap.getHeight() * scale)),
                true);
        if (scaled != bitmap) bitmap.recycle();
        bitmap = scaled;
      }
      File temp = new File(file.getParentFile(), file.getName() + ".tmp");
      boolean ok = false;
      try (FileOutputStream out = new FileOutputStream(temp)) {
        ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
      } finally {
        bitmap.recycle();
      }
      if (ok && temp.length() > 0) {
        if (!temp.renameTo(file)) {
          copyFile(temp, file);
          temp.delete();
        }
        Log.i(TAG, "normalize image -> " + file.length() + " B");
      } else {
        temp.delete();
      }
    } catch (Throwable error) {
      Log.w(TAG, "normalize image: " + error.getClass().getSimpleName());
    }
  }

  /** 相机/相册图经常带 EXIF 方向；BitmapFactory 不解，这里补上，免得图躺着。 */
  private static Bitmap rotateForExif(Bitmap bitmap, String path) {
    if (bitmap == null) return null;
    try {
      android.media.ExifInterface exif = new android.media.ExifInterface(path);
      int orientation =
          exif.getAttributeInt(
              android.media.ExifInterface.TAG_ORIENTATION,
              android.media.ExifInterface.ORIENTATION_NORMAL);
      int degrees = 0;
      boolean flip = false;
      switch (orientation) {
        case android.media.ExifInterface.ORIENTATION_ROTATE_90:
          degrees = 90;
          break;
        case android.media.ExifInterface.ORIENTATION_ROTATE_180:
          degrees = 180;
          break;
        case android.media.ExifInterface.ORIENTATION_ROTATE_270:
          degrees = 270;
          break;
        case android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
          flip = true;
          break;
        case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL:
          flip = true;
          degrees = 180;
          break;
        case android.media.ExifInterface.ORIENTATION_TRANSPOSE:
          flip = true;
          degrees = 90;
          break;
        case android.media.ExifInterface.ORIENTATION_TRANSVERSE:
          flip = true;
          degrees = 270;
          break;
        default:
          break;
      }
      if (degrees == 0 && !flip) return bitmap;
      android.graphics.Matrix matrix = new android.graphics.Matrix();
      if (flip) matrix.postScale(-1f, 1f);
      if (degrees != 0) matrix.postRotate(degrees);
      Bitmap rotated =
          Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
      if (rotated != bitmap) bitmap.recycle();
      return rotated;
    } catch (Throwable ignored) {
      return bitmap;
    }
  }

  /**
   * 文字保护：底图上那些直接压在背景上的小标题（「快捷入口」「工作区」等）没有卡片衬底，
   * 一张深色或花哨的照片能把灰字吃掉。有图时给 TextView 加一层很淡的白色光晕——
   * 在白卡片上基本看不见，在图上能把字和背景分开；回到默认/纯色时清掉。
   */
  private static void protectText(Activity activity, boolean on) {
    try {
      View root = activity.findViewById(android.R.id.content);
      if (root == null) return;
      // 同一棵 View 树、同一个状态只走一遍：apply 一次重建里会被调到好几回。
      Object state = root.getTag(R.id.tag_text_shadow_protect);
      if (state instanceof Boolean && ((Boolean) state) == on) return;
      root.setTag(R.id.tag_text_shadow_protect, on);
      protectText(root, on, activity.getResources().getDisplayMetrics().density);
    } catch (Throwable ignored) {
    }
  }

  private static void protectText(View view, boolean on, float density) {
    if (view instanceof android.widget.TextView) {
      android.widget.TextView text = (android.widget.TextView) view;
      Object tagged = text.getTag(R.id.tag_text_shadow);
      if (on && tagged == null) {
        text.setTag(R.id.tag_text_shadow, Boolean.TRUE);
        text.setShadowLayer(2f * density, 0f, 0f, 0xB3FFFFFF);
      } else if (!on && tagged != null) {
        text.setTag(R.id.tag_text_shadow, null);
        text.setShadowLayer(0f, 0f, 0f, 0);
      }
    }
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) protectText(group.getChildAt(i), on, density);
    }
  }

  /** 遮罩色：浅色主题压白，深色主题压黑；和面板底色走同一套明暗。 */
  private static int scrimColor(Context context) {
    return isNight(context) ? Color.BLACK : Color.WHITE;
  }

  // ---------------------------------------------------------------------------
  // 展示
  // ---------------------------------------------------------------------------

  public static String presetLabel(Context context) {
    Spec spec = read(context);
    // 218：内置壁纸分深浅两张，名字跟着主题走，别再一律写「浅色」。
    final String builtIn = isNight(context) ? "内置深色壁纸" : "内置浅色壁纸";
    if (spec.missing) return "图片丢失 · 请重新选图";
    if ("transparent".equals(spec.type)) return "透明";
    // 旧版深色/暖色（color）不再提供入口，统一显示「默认」，可一键清除。
    if ("color".equals(spec.type)) return builtIn;
    if (spec.isImage()) {
      File file = new File(spec.path);
      if (!file.isFile()) return "图片丢失（" + file.getName() + "）";
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
      String size = bounds.outWidth > 0 ? bounds.outWidth + "×" + bounds.outHeight : "尺寸未知";
      return "图片 " + size + " · 遮罩 " + Math.round(spec.scrim * 100f) + "%";
    }
    return builtIn;
  }

  private static float clamp01(float value) {
    if (Float.isNaN(value)) return 1f;
    return Math.max(0f, Math.min(1f, value));
  }

  private static float clampScrim(float value) {
    if (Float.isNaN(value)) return DEFAULT_SCRIM;
    return Math.max(0f, Math.min(MAX_SCRIM, value));
  }

  private static int withAlpha(int color, int alpha) {
    return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
  }
}
