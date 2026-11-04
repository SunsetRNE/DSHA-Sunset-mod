package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 一键备份先定位、停 Web 时桥会删除 .bridge_port 并原子重写 .bridge_status。
 * 机器状态整项排除，不能因此让整份备份以 DATA_LOCATION_CHANGED 失败；用户数据仍失败关闭。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class NativeDataLocationsProofTest {
  private Context context;
  private File dsh;

  @Before
  public void setUp() throws Exception {
    context = org.robolectric.RuntimeEnvironment.getApplication();
    File files = context.getFilesDir().getCanonicalFile();
    dsh = new File(files, "linux/ubuntu/root/.dsh");
    assertTrue(new File(dsh, "sessions").mkdirs());
    Files.writeString(new File(dsh, "sessions/one.jsonl").toPath(), "{}\n");
    Files.writeString(new File(dsh, "settings.yaml").toPath(), "a: 1\n");
    Files.writeString(new File(dsh, ".bridge_status").toPath(), "ok port=3091\n");
    Files.writeString(new File(dsh, ".bridge_port").toPath(), "3091");
  }

  private NativeDataLocations.Located locate() throws IOException {
    return locate(new JvmBackupFileSystem());
  }

  private NativeDataLocations.Located locate(BackupFileSystem fs) throws IOException {
    var selection = new NativeDataLocations.Selection();
    selection.scope = "application";
    return new NativeDataLocations(context, fs).locate(selection);
  }

  private static BackupSource source(NativeDataLocations.Located located, String name) {
    String id = NativeDataLocations.id(name);
    for (BackupSource source : located.sources) if (source.id().equals(id)) return source;
    throw new AssertionError("missing source " + name);
  }

  private static List<BackupSource.Item> walk(BackupSource source) throws IOException {
    List<BackupSource.Item> items = new ArrayList<>();
    source.walk(items::add, new BackupControl(null));
    return items;
  }

  /** 写临时文件后原子 rename；是否能观察到真实文件身份变化，须由宿主能力另行确认。 */
  private static void replace(File file, String text) throws IOException {
    Path tmp = file.toPath().resolveSibling(file.getName() + ".tmp");
    Files.writeString(tmp, text);
    Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE);
  }

  @Test
  public void bridgeStateRewrittenAfterLocateStaysExcluded() throws Exception {
    var located = locate();
    replace(new File(dsh, ".bridge_status"), "stopped\n");
    Files.delete(new File(dsh, ".bridge_port").toPath());
    for (String name : List.of(".bridge_status", ".bridge_port")) {
      List<BackupSource.Item> items = walk(source(located, name));
      assertEquals(1, items.size());
      assertEquals("EXCLUDED", items.get(0).kind);
      assertEquals("DEVICE_SPECIFIC_STATE", items.get(0).reason);
    }
  }

  @Test
  public void unchangedUserDataIsReadableBeforeAnyReplacement() throws Exception {
    BackupSource user = source(locate(), "settings.yaml");
    List<BackupSource.Item> items = walk(user);
    assertEquals(1, items.size());
    assertEquals("FILE", items.get(0).kind);
    try (var input = user.open(items.get(0))) {
      assertEquals(
          "a: 1\n", new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  @Test
  public void controlledOsIdentityChangeRejectsActualReplacementOnEveryHost() throws Exception {
    File settings = new File(dsh, "settings.yaml").getAbsoluteFile();
    var identity = new java.util.concurrent.atomic.AtomicInteger(1);
    // 只模拟宿主提供的 OS 文件身份；定位、目录、内容、原子替换与生产 walk 全部真实执行。
    BackupFileSystem fs =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws IOException {
            Node actual = super.stat(file);
            if (!file.getAbsoluteFile().equals(settings) || !actual.type.equals("FILE"))
              return actual;
            return new Node(
                actual.type,
                "CONTROLLED_OS_FILE_KEY_" + identity.get(),
                actual.size,
                actual.modified,
                actual.device,
                actual.mode);
          }
        };
    BackupSource user = source(locate(fs), "settings.yaml");
    List<BackupSource.Item> before = walk(user);
    assertEquals(1, before.size());
    assertEquals("FILE", before.get(0).kind);
    try (var input = user.open(before.get(0))) {
      assertEquals(
          "a: 1\n", new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
    replace(settings, "a: 2\n");
    identity.incrementAndGet();
    assertEquals("a: 2\n", Files.readString(settings.toPath()));
    List<BackupSource.Item> after = walk(user);
    assertEquals(1, after.size());
    assertEquals("UNREADABLE", after.get(0).kind);
    assertEquals("DATA_LOCATION_CHANGED", after.get(0).reason);
    assertThrows(IOException.class, () -> user.open(before.get(0)));
  }

  @Test
  public void userDataReplacedAfterLocateStillFailsClosed() throws Exception {
    var located = locate();
    BackupSource user = source(located, "settings.yaml");
    assertEquals("FILE", walk(user).get(0).kind);
    File settings = new File(dsh, "settings.yaml");
    Object before =
        Files.readAttributes(
                settings.toPath(),
                java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS)
            .fileKey();
    // Windows JDK 可返回 null；字符串 "null"、mtime 或路径都不能冒充 inode。
    Assume.assumeTrue(
        "HOST_FILE_IDENTITY_UNAVAILABLE: BasicFileAttributes.fileKey() is null", before != null);
    replace(settings, "a: 2\n");
    Object after =
        Files.readAttributes(
                settings.toPath(),
                java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS)
            .fileKey();
    assertNotNull("The replacement must retain host identity support", after);
    assertNotEquals("The fixture must actually replace the file identity", before, after);
    assertEquals("a: 2\n", Files.readString(settings.toPath()));
    List<BackupSource.Item> items = walk(user);
    assertEquals(1, items.size());
    assertEquals("UNREADABLE", items.get(0).kind);
    assertEquals("DATA_LOCATION_CHANGED", items.get(0).reason);
  }
}
