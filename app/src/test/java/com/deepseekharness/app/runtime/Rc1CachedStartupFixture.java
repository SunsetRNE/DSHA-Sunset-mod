package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import com.deepseekharness.app.util.Rc1MigrationResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** CJS 联合测试入口：真实缓存命中与持久交接，只映射临时宿主路径和 guest 路径。 */
public final class Rc1CachedStartupFixture {
  private Rc1CachedStartupFixture() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("state home startupId");
    File state = new File(args[0]).getAbsoluteFile();
    File home = new File(args[1]).getAbsoluteFile();
    File files = home.getParentFile();
    var fs =
        new JvmBackupFileSystem() {
          public Node stat(File file) throws IOException {
            Node value = super.stat(file);
            if (!file.equals(home)) return value;
            long inode = ((Number) Files.getAttribute(file.toPath(), "unix:ino")).longValue();
            return new Node(
                value.type,
                value.device + ":" + inode,
                value.size,
                value.modified,
                value.device,
                value.mode);
          }

          public File child(File root, String relative) throws IOException {
            if (root.equals(files) && relative.equals("rc1-migration-state")) return state;
            return super.child(root, relative);
          }

          public byte[] small(File file, int limit) throws IOException {
            byte[] bytes = super.small(file, limit);
            return file.getName().equals("current.json") || file.getName().equals("reuse.json")
                ? mapHome(bytes, "/root/.dsh")
                : bytes;
          }

          public void atomic(File directory, String name, byte[] bytes) throws IOException {
            super.atomic(
                directory,
                name,
                name.equals("current.json") ? mapHome(bytes, home.getAbsolutePath()) : bytes);
          }
        };
    var scripts =
        new GuestPluginScripts(
            new GuestPluginScripts.Preparation() {
              public boolean environmentReady() {
                return true;
              }

              public boolean pythonReady() {
                return true;
              }

              public boolean rootPresent() {
                return true;
              }

              public void pnpm() {
                throw new AssertionError("cache must not install");
              }

              public void assets() {
                throw new AssertionError("cache must not dispatch guest");
              }

              public Rc1MigrationCache.Reuse reusableMigration() {
                return Rc1MigrationCache.reuse(fs, files, home);
              }

              public void bindMigrationHistory(Rc1MigrationCache.Reuse reuse, String startupId)
                  throws IOException {
                Rc1MigrationCache.bindHistory(fs, files, home, reuse, startupId);
              }
            },
            (command, timeout) -> {
              throw new AssertionError("cache must not dispatch guest");
            });
    String output = scripts.migration("prepare", args[2]).output;
    if (!Rc1MigrationResult.allowsStart(Rc1MigrationResult.parse(output)))
      throw new AssertionError(output);
    System.out.print(output);
  }

  private static byte[] mapHome(byte[] bytes, String home) throws IOException {
    Map<String, Object> value = BackupJson.read(bytes, 65536);
    value.put("dshHome", home);
    if (value.get("dataRoot") instanceof Map) {
      Map<String, Object> root = new LinkedHashMap<>();
      for (var entry : ((Map<?, ?>) value.get("dataRoot")).entrySet())
        root.put((String) entry.getKey(), entry.getValue());
      root.put("path", home);
      value.put("dataRoot", root);
    }
    return BackupJson.write(value, 65536);
  }
}
