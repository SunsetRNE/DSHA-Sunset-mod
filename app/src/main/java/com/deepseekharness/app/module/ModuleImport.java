package com.deepseekharness.app.module;

import android.content.Context;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 统一导入入口：用户只面对「导入模块文件」一个按钮，不用分辨是哪种 JSON。
 *
 * <p>先按卡片契约解析；不成立再按声明式清单解析。两种都失败时把卡片的报错抛出去
 * （那是用户最可能写的东西，报错最有参考价值）。
 *
 * <p>卡片导入来源固定记为 {@link ModuleCard#SOURCE_LOCAL}（用户手动选择）；
 * 容器收取走 {@link ModuleCardStore#scanContainerInbox}，来源记为 container。
 */
public final class ModuleImport {

  public static final class Result {
    public final boolean card;
    public final String detail;

    /** 卡片导入成功时是解析出的模块身份（含 author/version/source/digest）；清单导入为 null。 */
    public final ModuleCard identity;

    Result(boolean card, String detail, ModuleCard identity) {
      this.card = card;
      this.detail = detail;
      this.identity = identity;
    }

    /** 人话身份串：作者/版本/来源/摘要短形/信任状态，给导入反馈用。 */
    public String identitySummary(boolean zh) {
      return ModuleImport.identitySummary(identity, zh);
    }
  }

  /**
   * 模块身份的人话串：作者 / 版本 / 来源 / 摘要短形 / 信任状态。
   *
   * <p>如实标注：作者是自述、摘要是宿主算的内容一致性校验，两者都不是密码学签名；
   * 只有签名验签通过且公钥已被信任时才显示「已验证」，其余状态按
   * {@link ModuleTrust#statusLabel} 原样展示（未验证 / 未受信 / 无效）。
   */
  public static String identitySummary(ModuleCard card, boolean zh) {
    if (card == null) return "";
    StringBuilder builder = new StringBuilder();
    builder.append(zh ? "作者 " : "author ").append(card.author);
    builder.append(" · v").append(card.version);
    builder.append(zh ? " · 来源 " : " · source ").append(sourceLabel(card.source, zh));
    builder.append(" · SHA-256 ").append(card.digestShort());
    builder.append(" · ").append(ModuleTrust.statusLabel(ModuleTrust.statusOf(card), zh));
    return builder.toString();
  }

  /** 来源的人话标签；unknown 如实显示「来源未知」，不猜测。 */
  public static String sourceLabel(String source, boolean zh) {
    if (ModuleCard.SOURCE_CONTAINER.equals(source)) return zh ? "容器收取" : "container";
    if (ModuleCard.SOURCE_LOCAL.equals(source)) return zh ? "本地导入" : "local";
    if (ModuleCard.SOURCE_GALLERY.equals(source)) return zh ? "广场" : "gallery";
    return zh ? "来源未知" : "unknown";
  }

  private ModuleImport() {}

  public static Result importAny(Context context, String raw) {
    ModuleCard card;
    try {
      card = ModuleCard.parse(raw, ModuleCard.SOURCE_LOCAL);
    } catch (IllegalArgumentException cardError) {
      // 不是卡片：按声明式清单再试一次；两条路都失败时把卡片的报错抛出去
      // （那是用户最可能写的东西，报错最有参考价值）。
      try {
        ModuleRegistry.installUserManifest(context, raw);
        return new Result(false, "module", null);
      } catch (Exception listError) {
        throw new IllegalArgumentException("无法识别这份文件。按卡片看：" + cardError.getMessage());
      }
    }
    // 是合法卡片：到这里再失败就是存储/磁盘问题，必须如实抛原错误，
    // 不能谎报成「无法识别这份文件」误导用户去改一个本来没写错的 JSON。
    String layoutId = ModuleCardStore.importCard(context, raw, null, ModuleCard.SOURCE_LOCAL);
    return new Result(true, layoutId, card);
  }

  /**
   * 导入一个模块包（ZIP）：包内只能有扁平的 card.json / module.json、素材、签名、程序文件。
   *
   * <p>ZIP 只是传输容器；落盘仍走 {@link ModuleCardStore} 的整目录事务和逐文件校验。
   * 没有 card.json 时按 module.json 清单安装。非 ZIP 字节按普通 JSON 文本导入。
   */
  public static Result importPackage(Context context, byte[] bytes) throws IOException {
    if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("模块包为空");
    if (bytes.length > ModuleProgram.MAX_BYTES) throw new IllegalArgumentException("模块包超过 64MB");
    boolean zip = bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K';
    if (!zip) return importAny(context, new String(bytes, StandardCharsets.UTF_8));

    Map<String, byte[]> entries = new LinkedHashMap<>();
    // 解压后的累计字节也要设上限：只限单文件 64MB 时，一个高压缩比的恶意 ZIP
    // 可以用 64 个条目撑出几 GB 内存直接把进程 OOM 杀掉（OOM 是 Error，接不住）。
    long totalDecompressed = 0;
    try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        if (entry.isDirectory()) continue;
        String name = entry.getName();
        if (name == null
            || name.length() > 100
            || name.contains("/")
            || name.contains("\\")
            || name.startsWith(".")
            || entries.containsKey(name))
          throw new IllegalArgumentException("模块包里有非法或重复的文件名：" + name);
        if (entries.size() >= 64) throw new IllegalArgumentException("模块包文件数量超过 64");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1; ) {
          output.write(buffer, 0, count);
          if (output.size() > ModuleProgram.MAX_BYTES)
            throw new IllegalArgumentException("模块包单个文件超过 64MB：" + name);
        }
        totalDecompressed += output.size();
        if (totalDecompressed > ModuleProgram.MAX_BYTES)
          throw new IllegalArgumentException("模块包解压后总量超过 64MB");
        entries.put(name, output.toByteArray());
      }
    }
    String cardRaw = textOf(entries.get("card.json"));
    if (cardRaw == null) {
      String manifestRaw = textOf(entries.get("module.json"));
      if (manifestRaw == null) throw new IllegalArgumentException("模块包缺少 card.json 或 module.json");
      try {
        ModuleRegistry.installUserManifest(context, manifestRaw);
        return new Result(false, "module", null);
      } catch (Exception error) {
        throw new IllegalArgumentException("module.json 安装失败：" + error.getMessage());
      }
    }
    ModuleCard card = ModuleCard.parse(cardRaw, ModuleCard.SOURCE_LOCAL);
    Map<String, byte[]> assets = new LinkedHashMap<>();
    for (String asset : card.assetNames()) {
      byte[] value = entries.get(asset);
      if (value != null) assets.put(asset, value);
    }
    ModuleCardStore.importCard(context, cardRaw, assets, ModuleCard.SOURCE_LOCAL);
    return new Result(true, card.layoutId(), card);
  }

  private static String textOf(byte[] bytes) {
    return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
  }
}
