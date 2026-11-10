package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.ParcelFileDescriptor;

import com.deepseekharness.app.R;
import com.deepseekharness.app.util.DynamicPalette;
import com.deepseekharness.app.util.ResourcesTable;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;

/**
 * 把「莫奈取色」算出的 33 个颜色在运行时覆盖到 {@code R.color.pal_monet_*} 上。
 *
 * <p>为什么必须这样做：界面颜色全部经 {@code ?attr/dsha_pal_*} 间接引用这批 {@code <color>}，
 * 而 Android 没有公开 API 能在运行时改 {@code Resources.Theme} 里的自定义属性值。于是走 RRO 语义：
 * 用 {@link ResourcesTable} 造一张同 package id、只含这 33 个资源的小表，交给
 * {@code ResourcesProvider} + {@code ResourcesLoader}（API 30+）加载 —— 这也是 Material
 * {@code DynamicColors} 内部对 {@code material_dynamic_*} 用的同一套机制。
 *
 * <p>只覆盖 {@code pal_monet_*}：其余五套调色板的颜色资源一个都不碰，所以切换调色板不会互相污染。
 *
 * <p>失败一律不抛给用户：取色或覆盖失败时界面退回 monet 的兜底色（构建期等同标准色），
 * 用户看到的是「没变色」，不是崩溃或空白主题。
 */
public final class ThemePaletteOverride {
  private static final String PREFIX = "pal_monet_";
  private static final String FILE_NAME = "monet-palette.arsc";

  /** 必须持有 loader 强引用：Resources 只保存弱引用，被回收后覆盖会静默失效。 */
  private static ResourcesLoader loader;

  /** 已应用的配色指纹：同一套配色重复 apply 会反复重建资源表，白粉开销。 */
  private static int appliedFingerprint;

  private ThemePaletteOverride() {}

  /**
   * 覆盖当前进程的 {@code pal_monet_*}。
   *
   * @param palette {@link DynamicPalette} 的推导结果；null 或长度不符时直接返回
   * @return 是否真的应用了覆盖
   */
  public static boolean apply(Context context, String[][] palette) {
    if (palette == null || palette.length != DynamicPalette.NAMES.length) return false;
    int mode = ThemeController.isDark(context) ? 1 : 0;
    int fingerprint = fingerprint(palette, mode);
    if (loader != null && fingerprint == appliedFingerprint) return true;
    try {
      int[] ids = colorIds();
      int[] argb = new int[ids.length];
      for (int index = 0; index < ids.length; index++)
        argb[index] = parseColor(palette[index][mode]);
      int packageId = (ids[0] >>> 24) & 0xFF;
      int typeId = (ids[0] >>> 16) & 0xFF;
      byte[] table = ResourcesTable.build(context.getPackageName(), packageId, typeId, ids, argb);
      File file = new File(context.getCacheDir(), FILE_NAME);
      Files.write(file.toPath(), table);
      ParcelFileDescriptor descriptor =
          ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
      // loadFromTable 收的是 ParcelFileDescriptor（不是 AssetFileDescriptor，这是 API 30 的真实签名，
      // 用 javap 核对过 android.content.res.loader.ResourcesProvider）。描述符由 Provider 持有到
      // 进程结束，这里不能 close —— 表是按需加载的，提前关掉会让后续解析拿到已关闭的 fd。
      ResourcesProvider provider = ResourcesProvider.loadFromTable(descriptor, null);
      ResourcesLoader next = new ResourcesLoader();
      next.addProvider(provider);
      context.getResources().addLoaders(next);
      loader = next;
      appliedFingerprint = fingerprint;
      return true;
    } catch (Throwable error) {
      // 覆盖是增强能力：ROM 不支持、资源缺失、写缓存失败都退回兜底色，不影响启动。
      return false;
    }
  }

  /** 清掉最近一次应用的指纹，让下次 apply 一定重建（换壁纸后用）。 */
  static void invalidate() {
    appliedFingerprint = 0;
  }

  private static int[] colorIds() throws IllegalAccessException, NoSuchFieldException {
    int[] ids = new int[DynamicPalette.NAMES.length];
    for (int index = 0; index < ids.length; index++) {
      Field field = R.color.class.getField(PREFIX + DynamicPalette.NAMES[index]);
      ids[index] = field.getInt(null);
    }
    return ids;
  }

  private static int parseColor(String value) {
    String raw = value.startsWith("#") ? value.substring(1) : value;
    if (raw.length() == 6) raw = "FF" + raw;
    long parsed = Long.parseLong(raw, 16);
    return (int) parsed;
  }

  private static int fingerprint(String[][] palette, int mode) {
    int hash = mode + 1;
    for (int index = 0; index < palette.length; index++) {
      hash = hash * 31 + parseColor(palette[index][mode]);
    }
    return hash;
  }
}
