package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.BackupJson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 只证明本次没有旧审阅标记；调用者须持有数据锁和 profile 写锁，不修改插件。 */
final class LegacyPluginReviewCheck {
  private static final int MANIFEST_LIMIT = 1024 * 1024, DEPENDENCY_LIMIT = 4096;
  private static final byte[] LEGACY = "DSHA_REVIEW_REQUIRED\n".getBytes(StandardCharsets.US_ASCII);
  private final BackupFileSystem fs;
  private final File authority;
  private final Map<File, BackupFileSystem.Node> nodes = new LinkedHashMap<>();
  private final Map<File, byte[]> contents = new LinkedHashMap<>();

  private LegacyPluginReviewCheck(BackupFileSystem fs, File authority) {
    this.fs = fs;
    this.authority = authority.getAbsoluteFile();
  }

  static boolean noLegacyReviews(BackupFileSystem fs, File authority, File home)
      throws IOException {
    return new LegacyPluginReviewCheck(fs, authority).inspect(home.getAbsoluteFile());
  }

  private boolean inspect(File home) throws IOException {
    File profile = new File(home, "profiles/web"), modules = new File(profile, "node_modules");
    requireDirectory(home);
    requireDirectory(profile);
    requireDirectory(modules);
    File manifest = new File(profile, "package.json");
    var document = BackupJson.read(read(manifest, MANIFEST_LIMIT), MANIFEST_LIMIT);
    Object dependencies = document.get("dependencies");
    if (dependencies != null && !(dependencies instanceof Map)) return false;
    if (dependencies instanceof Map<?, ?> references) {
      if (references.size() > DEPENDENCY_LIMIT) return false;
      for (var entry : references.entrySet()) {
        checkpoint();
        if (!(entry.getKey() instanceof String name)
            || name.length() > 214
            || !name.matches("(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*")
            || !(entry.getValue() instanceof String)) return false;
        // 不免检内置名称：即使 Python 会跳过它，发现旧字节也保守走原入口。
        File marker = new File(modules, name + ".disabled");
        var node = inspectPath(marker);
        if (node.type.equals("MISSING")) continue;
        if (!node.type.equals("FILE")) return false;
        if (node.size == LEGACY.length && Arrays.equals(LEGACY, read(marker, LEGACY.length)))
          return false;
      }
    }
    // 第二次读正文，避免相同大小/秒级时间戳掩盖原地修改；不复用旧回执或时间戳。
    for (var entry : contents.entrySet()) {
      checkpoint();
      if (!Arrays.equals(entry.getValue(), fs.small(entry.getKey(), entry.getValue().length)))
        return false;
    }
    for (var entry : nodes.entrySet()) {
      checkpoint();
      if (!entry.getValue().same(fs.stat(entry.getKey()))) return false;
    }
    return true;
  }

  private void requireDirectory(File file) throws IOException {
    if (!inspectPath(file).type.equals("DIRECTORY"))
      throw new IOException("LEGACY_REVIEW_PARENT_TYPE");
  }

  private BackupFileSystem.Node inspectPath(File target) throws IOException {
    checkpoint();
    String prefix = authority.getAbsolutePath() + File.separator;
    if (!target.getAbsolutePath().startsWith(prefix))
      throw new IOException("LEGACY_REVIEW_OUTSIDE_AUTHORITY");
    String relative = target.getAbsolutePath().substring(prefix.length());
    File current = authority;
    var node = remember(current);
    for (String part : relative.split(java.util.regex.Pattern.quote(File.separator))) {
      if (!node.type.equals("DIRECTORY") || part.isEmpty() || part.equals(".") || part.equals(".."))
        throw new IOException("LEGACY_REVIEW_PARENT_TYPE");
      current = new File(current, part);
      node = remember(current);
    }
    return node;
  }

  private BackupFileSystem.Node remember(File file) throws IOException {
    var current = fs.stat(file);
    var previous = nodes.putIfAbsent(file, current);
    if (previous != null && !previous.same(current))
      throw new IOException("LEGACY_REVIEW_SOURCE_CHANGED");
    return current;
  }

  private byte[] read(File file, int limit) throws IOException {
    var before = inspectPath(file);
    if (!before.type.equals("FILE") || before.size > limit)
      throw new IOException("LEGACY_REVIEW_FILE_TYPE_OR_LIMIT");
    byte[] bytes = fs.small(file, limit);
    if (!before.same(fs.stat(file))) throw new IOException("LEGACY_REVIEW_SOURCE_CHANGED");
    contents.put(file, bytes);
    return bytes;
  }

  private static void checkpoint() throws IOException {
    if (Thread.currentThread().isInterrupted()) throw new IOException("LEGACY_REVIEW_INTERRUPTED");
  }
}
