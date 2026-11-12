package com.deepseekharness.app.ui;

import android.content.res.AssetManager;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 随包第三方许可索引与正文读取：只认 {@code assets/licenses/*.txt}，正文上限 256 KiB，严格 UTF-8。
 *
 * <p>这里不写日志、不缓存、不落盘；读取失败以 {@link Failure} 返回类型，由宿主显示对应提示，
 * 不把资产路径或底层异常带进日志。列表按可读名称稳定排序；未知文件名回退为去扩展名后的可读形式。
 */
final class AboutLicenses {
  static final String DIRECTORY = "licenses";
  static final int MAX_BYTES = 256 * 1024;
  static final int MAX_ENTRIES = 128;
  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*\\.txt");

  private AboutLicenses() {}

  record Entry(String assetName, String displayName) {}

  enum Failure {
    MISSING,
    UNREADABLE,
    TOO_LARGE,
    INVALID_UTF8,
    EMPTY
  }

  record ReadResult(String text, Failure failure) {
    boolean ok() {
      return failure == null;
    }
  }

  static List<Entry> list(AssetManager assets) throws IOException {
    String[] listed = assets == null ? null : assets.list(DIRECTORY);
    if (listed == null) throw new IOException("LICENSE_LIST");
    List<Entry> entries = new ArrayList<>();
    for (String name : listed) {
      if (name == null || !SAFE_NAME.matcher(name).matches()) continue;
      entries.add(new Entry(name, displayName(name)));
    }
    entries.sort(
        Comparator.comparing(Entry::displayName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Entry::assetName));
    if (entries.size() > MAX_ENTRIES) throw new IOException("LICENSE_COUNT");
    return List.copyOf(entries);
  }

  /** 同步读取一次；超过 256 KiB 立即停止，失败只返回类型，不抛出也不记录路径。 */
  static ReadResult read(AssetManager assets, String assetName) {
    if (assets == null || assetName == null || !SAFE_NAME.matcher(assetName).matches())
      return new ReadResult(null, Failure.MISSING);
    try (InputStream input = assets.open(DIRECTORY + "/" + assetName)) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (output.size() + count > MAX_BYTES) return new ReadResult(null, Failure.TOO_LARGE);
        output.write(buffer, 0, count);
      }
      if (output.size() == 0) return new ReadResult(null, Failure.EMPTY);
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(output.toByteArray()))
              .toString();
      if (text.startsWith("\uFEFF")) text = text.substring(1);
      return new ReadResult(text, null);
    } catch (FileNotFoundException missing) {
      return new ReadResult(null, Failure.MISSING);
    } catch (CharacterCodingException invalid) {
      return new ReadResult(null, Failure.INVALID_UTF8);
    } catch (IOException unreadable) {
      return new ReadResult(null, Failure.UNREADABLE);
    }
  }

  /** 已知组件给出可读名称；未知文件去掉扩展名与许可/版权文件后缀后作为标题。 */
  static String displayName(String assetName) {
    if (assetName == null) return "";
    if (!assetName.endsWith(".txt")) return assetName;
    String stem = assetName.substring(0, assetName.length() - 4);
    switch (stem) {
      case "DSHA-MIT":
        return "DSHA · MIT";
      case "GPL-3.0":
        return "GNU GPL v3.0";
      case "LGPL-3.0":
        return "GNU LGPL v3.0";
      case "MPL-2.0":
        return "Mozilla Public License 2.0";
      case "Shizuku-MIT":
        return "Shizuku · MIT";
      case "Termux-terminal-Apache-2.0":
        return "Termux terminal · Apache 2.0";
      case "bouncycastle-LICENSE":
        return "Bouncy Castle";
      case "certifi-LICENSE":
        return "certifi · MPL-2.0";
      case "gh-MIT":
        return "GitHub CLI · MIT";
      case "gson-LICENSE":
        return "Gson · Apache 2.0";
      case "proot-COPYING":
        return "proot · GPL-2.0";
      case "snakeyaml-LICENSE":
        return "SnakeYAML · Apache 2.0";
      default:
        return fallbackName(stem);
    }
  }

  private static String fallbackName(String stem) {
    String readable =
        stem.replaceAll("(?i)[-_](?:license|copying|notice)$", "")
            .replace('-', ' ')
            .replace('_', ' ')
            .trim();
    return readable.isEmpty() ? stem : readable;
  }
}
