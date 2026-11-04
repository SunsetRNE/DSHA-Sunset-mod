package com.deepseekharness.app.ui;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 横屏 Dock 的运行时口子：只提供**数据开关**，不执行磁盘上的任何代码。
 *
 * <p>配置固定放在应用私有目录 {@code files/landscape/dsh-a-landscape.json}（与
 * {@code context.getFilesDir()} 同一沙箱）。这里刻意不读 {@code Download/}、不读
 * guest 文件系统：那两处任何 App、MTP 或插件都能写，把可执行内容送进已鉴权的 DSH 页面
 * 等于绕过登录态。运行时外观的样式/脚本注入已从本功能移除，需要改外观请走 App 自身版本。
 *
 * <p>支持字段（全部可选，缺省值见 {@link #fromDirectory(File)}）：
 * <ul>
 *   <li>{@code defaultMode} 0/1/2 —— 默认档（1280×720 / 1024×576 / 854×480）
 *   <li>{@code capsule} right/left/hide —— 胶囊位置
 *   <li>{@code chrome} true/false —— 是否显示舞台控件
 *   <li>{@code desktopUa} true/false —— 是否用桌面 UA
 *   <li>{@code scrollLock} true/false —— 是否注入整页拖不动的脚本
 * </ul>
 *
 * <p>读取规则：拒绝符号链接、拒绝越出配置目录的路径、大小上限 {@link #MAX_BYTES}
 * 且读前读后各校验一次（防 TOCTOU）。文件不存在或损坏时全部回默认值。
 */
final class LandscapeConfig {
  private static final String DIRECTORY = "landscape";
  private static final String BASE = "dsh-a-landscape";
  private static final int MAX_BYTES = 64 * 1024;

  static final String CAPSULE_RIGHT = "right";
  static final String CAPSULE_LEFT = "left";
  static final String CAPSULE_HIDE = "hide";

  final int defaultMode;
  final String capsule;
  final boolean chrome;
  final boolean desktopUa;
  final boolean scrollLock;

  private LandscapeConfig(
      int defaultMode, String capsule, boolean chrome, boolean desktopUa, boolean scrollLock) {
    this.defaultMode = defaultMode;
    this.capsule = capsule;
    this.chrome = chrome;
    this.desktopUa = desktopUa;
    this.scrollLock = scrollLock;
  }

  static LandscapeConfig load(Context context) {
    return fromDirectory(directory(context));
  }

  /** 配置目录；测试与实现共用同一入口。 */
  static File directory(Context context) {
    return new File(context.getFilesDir(), DIRECTORY);
  }

  static LandscapeConfig fromDirectory(File directory) {
    int defaultMode = 0;
    String capsule = CAPSULE_RIGHT;
    boolean chrome = true;
    boolean desktopUa = true;
    boolean scrollLock = true;
    String text = read(directory, BASE + ".json");
    if (!text.isEmpty()) {
      try {
        JSONObject value = new JSONObject(text);
        defaultMode =
            Math.max(
                0,
                Math.min(
                    com.deepseekharness.app.util.DshStageGeometry.MODE_COUNT - 1,
                    value.optInt("defaultMode", 0)));
        String requested = value.optString("capsule", CAPSULE_RIGHT);
        if (CAPSULE_LEFT.equals(requested)
            || CAPSULE_HIDE.equals(requested)
            || CAPSULE_RIGHT.equals(requested)) capsule = requested;
        chrome = value.optBoolean("chrome", true);
        desktopUa = value.optBoolean("desktopUa", true);
        scrollLock = value.optBoolean("scrollLock", true);
      } catch (Exception ignored) {
        // 配置写坏了就退回默认值，不让横屏显示起不来。
      }
    }
    return new LandscapeConfig(defaultMode, capsule, chrome, desktopUa, scrollLock);
  }

  /**
   * 读取配置目录里的普通文件；符号链接、越界路径、空文件与超限文件一律当作不存在。
   *
   * <p>先按 {@code NOFOLLOW_LINKS} 取属性（符号链接在这里就会暴露），再用
   * {@code toRealPath} 做目录前缀复核，最后以 {@code NOFOLLOW_LINKS} 打开并限长读取，
   * 读完再核对一次字节数。
   */
  static String read(File directory, String name) {
    if (directory == null || name == null || name.isEmpty()) return "";
    try {
      Path root = directory.toPath().toRealPath();
      Path target = root.resolve(name).normalize();
      if (!target.startsWith(root) || target.equals(root)) return "";
      BasicFileAttributes attributes =
          Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isRegularFile() || attributes.isSymbolicLink()) return "";
      long size = attributes.size();
      if (size <= 0 || size > MAX_BYTES) return "";
      Path real = target.toRealPath(LinkOption.NOFOLLOW_LINKS);
      if (!real.startsWith(root)) return "";
      try (InputStream in =
          Files.newInputStream(real, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
        byte[] bytes = readBounded(in);
        if (bytes == null || bytes.length != size || bytes.length > MAX_BYTES) return "";
        return new String(bytes, StandardCharsets.UTF_8);
      }
    } catch (Exception ignored) {
      return "";
    }
  }

  /** 最多读 {@link #MAX_BYTES} 字节；再多一个字节即视为超限。 */
  private static byte[] readBounded(InputStream in) throws java.io.IOException {
    byte[] buffer = new byte[8192];
    byte[] result = new byte[0];
    int total = 0;
    int read;
    while ((read = in.read(buffer)) > 0) {
      if (total + read > MAX_BYTES) return null;
      byte[] next = new byte[total + read];
      System.arraycopy(result, 0, next, 0, total);
      System.arraycopy(buffer, 0, next, total, read);
      result = next;
      total += read;
    }
    return result;
  }
}
