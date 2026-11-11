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
import java.util.Arrays;
import java.util.WeakHashMap;

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

  /**
   * 每个 Resources 实例各自登记一份已安装的覆盖。
   *
   * <p>为什么按 Resources 记账：{@code Resources.addLoaders} 只作用于调用它的那一个 Resources，
   * 而切日/夜、旋转、改字号或语言后系统会换新实例。若沿用旧版的进程级指纹，新实例永远装不上
   * loader，莫奈会静默退回经典蓝（切日夜必现）。键是弱引用，旧 Resources 被回收后登记自动清掉；
   * loader/provider 的强引用由登记值持有，避免「Resources 还活着、loader 先被 GC」的半失效状态。
   */
  private static final WeakHashMap<Resources, Installed> installed = new WeakHashMap<>();

  private ThemePaletteOverride() {}

  /** 一个 Resources 上已装好的覆盖：loader、provider 和当初的推导依据绑在一起，替换时整组换。 */
  private static final class Installed {
    final ResourcesLoader loader;
    final ResourcesProvider provider;

    /**
     * 上次成功应用时的 palette 数组引用。MonetPalette 的进程内缓存对同一进程始终是同一实例，
     * 引用相等即可断定配色没变，连 33 次字符串解析都省掉。
     */
    final String[][] source;

    /** 上次真正写进资源表的当前 mode 列；逐值比较是最终裁决，杜绝 int 指纹碰撞误判「配色相同」。 */
    final int[] column;

    Installed(ResourcesLoader loader, ResourcesProvider provider, String[][] source, int[] column) {
      this.loader = loader;
      this.provider = provider;
      this.source = source;
      this.column = column;
    }
  }

  /**
   * 覆盖当前进程的 {@code pal_monet_*}。
   *
   * @param palette {@link DynamicPalette} 的推导结果；null 或长度不符时直接返回
   * @return 是否真的应用了覆盖
   */
  public static boolean apply(Context context, String[][] palette) {
    if (palette == null || palette.length != DynamicPalette.NAMES.length) return false;
    try {
      Resources resources = context.getResources();
      Installed previous = installed.get(resources);
      // 同一数组实例即 MonetPalette 缓存未变，配色必然没变：直接短路，不做任何重建。
      if (previous != null && palette == previous.source) return true;

      // isDark 与 parseColor 都可能被意外输入（比如 palette 里混进异常串）绊倒，
      // 所以连同后续全部重建逻辑一起留在 try 里：失败一律返回 false，不抛给调用方。
      int mode = ThemeController.isDark(context) ? 1 : 0;
      int[] argb = new int[DynamicPalette.NAMES.length];
      for (int index = 0; index < argb.length; index++) {
        argb[index] = parseColor(palette[index][mode]);
      }
      // 逐值比较刚解析出的列与该 Resources 上实际生效的列：相同就跳过重建，
      // 33 个 int 的比较远比重建资源表便宜，也比 int 指纹可靠（指纹碰撞会误判「相同」）。
      if (previous != null && Arrays.equals(previous.column, argb)) return true;

      int[] ids = colorIds();
      int packageId = (ids[0] >>> 24) & 0xFF;
      int typeId = (ids[0] >>> 16) & 0xFF;
      byte[] table = ResourcesTable.build(context.getPackageName(), packageId, typeId, ids, argb);
      File file = new File(context.getCacheDir(), FILE_NAME);
      Files.write(file.toPath(), table);

      ParcelFileDescriptor descriptor = null;
      ResourcesProvider provider = null;
      ResourcesLoader next = null;
      try {
        descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        // loadFromTable 收的是 ParcelFileDescriptor（不是 AssetFileDescriptor，这是 API 30 的真实签名，
        // 用 javap 核对过 android.content.res.loader.ResourcesProvider）。open 成功而 loadFromTable
        // 失败的窗口里 fd 还归这里管；provider 一旦建成，所有权整体转移给它（AutoCloseable 链），
        // 成功路径置 descriptor 为 null，失败路径按归属补关，两种情况都不泄漏 fd。
        provider = ResourcesProvider.loadFromTable(descriptor, null);
        descriptor = null;
        next = new ResourcesLoader();
        next.addProvider(provider);
      } catch (Throwable error) {
        // provider 已建成就让 provider 连带关掉 fd；还没建成则 fd 仍是本方法的，直接关。
        if (provider != null) {
          try {
            provider.close();
          } catch (Throwable ignored) {
            // 关闭失败不能掩盖原始错误。
          }
        } else if (descriptor != null) {
          try {
            descriptor.close();
          } catch (Throwable ignored) {
            // 同上。
          }
        }
        throw error;
      }

      if (previous != null) {
        try {
          // 先摘旧 loader 再装新 loader：多个 loader 按「先装的先查」，不摘旧的话旧表会一直压着新表。
          resources.removeLoaders(previous.loader);
        } catch (Throwable error) {
          // 旧 loader 摘不掉（几乎只会是 ROM 故障）：保持旧覆盖原样，登记也不动（它仍如实描述现状，
          // 绝不能在 Resources 还引用旧 provider 时把它关掉）。只关掉新建的 provider 回收 fd。
          try {
            provider.close();
          } catch (Throwable ignored) {
            // 关闭失败不能掩盖原始错误。
          }
          return false;
        }
        // 旧 loader 已摘除，Resources 不再引用旧 provider，此刻才允许关掉回收 fd。
        try {
          previous.provider.close();
        } catch (Throwable ignored) {
          // 关不掉只是这个 fd 多留到进程结束，不影响覆盖的正确性。
        }
      }
      try {
        resources.addLoaders(next);
      } catch (Throwable error) {
        // 走到这里旧覆盖（若有）已摘除：登记一并清掉（它已过时），新 provider 也关掉回收 fd，
        // 下次 apply 会全量重试。
        try {
          provider.close();
        } catch (Throwable ignored) {
          // 关闭失败不能掩盖原始错误。
        }
        installed.remove(resources);
        return false;
      }
      installed.put(resources, new Installed(next, provider, palette, argb));
      return true;
    } catch (Throwable error) {
      // 覆盖是增强能力：ROM 不支持、资源缺失、写缓存失败都退回兜底色，不影响启动。
      return false;
    }
  }

  /**
   * 预留的手动失效钩子（{@link MonetPalette#invalidate} 会调用）。
   *
   * <p>改成按 Resources 逐值比较后，这里不需要清任何状态：配色变了，下一次 apply 靠逐值比较自己
   * 发现差异并连同旧 loader 一起替换；配色没变，强制重建只会白造一张相同的表，还会清掉
   * 「哪个 Resources 装着哪个 loader」的登记，旧 loader 从此摘不掉、fd 泄漏。保留签名只为调用链稳定。
   */
  static void invalidate() {}

  /**
   * 清空进程级覆盖登记，主要给测试用。
   *
   * <p>登记表是 static，Robolectric 在同一 sandbox 内跨测试类共享它；上一个测试留下的
   * 「某 Resources 已装某份配色」会被下一个测试当成事实，测试之间就不再独立。生产代码
   * 不需要调用（覆盖按 Resources 实例自动失效，旧实例被回收后登记由 WeakHashMap 清掉）。
   */
  static void resetForTest() {
    for (Installed entry : installed.values()) {
      try {
        entry.provider.close();
      } catch (Throwable ignored) {
        // 清理阶段的关闭失败不影响测试判定。
      }
    }
    installed.clear();
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
}
