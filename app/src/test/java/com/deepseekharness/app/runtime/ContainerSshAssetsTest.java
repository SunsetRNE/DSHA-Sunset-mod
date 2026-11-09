package com.deepseekharness.app.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.os.Build;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;

/**
 * 随包 SSH 资产的绑定契约：bind 之后 LaunchSpec 必须带上三个目标路径；清单摘要、架构、
 * ELF 头任意一处不符都要整体拒绝，绝不把可疑字节送进容器。
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = android.app.Application.class,
    instrumentedPackages = {"com.deepseekharness.app.backup"},
    shadows = ContainerSshAssetsTest.JvmHostFileSystem.class)
public final class ContainerSshAssetsTest {
  private Path temporary, data;
  private Context context;
  private AssetManager assets;
  private String[] originalAbis;

  @Implements(value = AndroidBackupFileSystem.class, isInAndroidSdk = false)
  public static final class JvmHostFileSystem
      extends RuntimeToolsRegistryAndroidTest.HostFileSystem {}

  @Before
  public void prepare() throws Exception {
    temporary = Files.createTempDirectory("dsha-ssh-assets-");
    data = Files.createDirectories(temporary.resolve("data"));
    Files.createDirectories(data.resolve("no_backup"));
    assets = AssetManager.class.getDeclaredConstructor().newInstance();
    assertNotNull(Build.SUPPORTED_ABIS);
    assertTrue(Build.SUPPORTED_ABIS.length > 0);
    originalAbis = Build.SUPPORTED_ABIS.clone();
    Build.SUPPORTED_ABIS[0] = "aarch64";
    context =
        new ContextWrapper(RuntimeEnvironment.getApplication()) {
          @Override
          public android.content.pm.ApplicationInfo getApplicationInfo() {
            var info = new android.content.pm.ApplicationInfo(super.getApplicationInfo());
            info.dataDir = data.toString();
            return info;
          }

          @Override
          public File getNoBackupFilesDir() {
            return data.resolve("no_backup").toFile();
          }

          @Override
          public AssetManager getAssets() {
            return assets;
          }
        };
  }

  @After
  public void close() throws Exception {
    System.arraycopy(originalAbis, 0, Build.SUPPORTED_ABIS, 0, originalAbis.length);
    assets.close();
    try (var paths = Files.walk(temporary)) {
      for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        try {
          Files.delete(path);
        } catch (java.nio.file.FileSystemException heldByWindows) {
          path.toFile().deleteOnExit();
        }
      }
    }
  }

  private static byte[] elf(long machine) {
    byte[] elf = new byte[64];
    elf[0] = 127;
    elf[1] = 'E';
    elf[2] = 'L';
    elf[3] = 'F';
    elf[4] = 2; // ELFCLASS64
    elf[5] = 1; // 小端
    elf[16] = 3; // ET_DYN（PIE）
    elf[18] = (byte) machine;
    elf[19] = (byte) (machine >> 8);
    return elf;
  }

  private static String sha256(byte[] value) throws Exception {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  /** 生成随包清单与三个 payload，打进 fake APK 的 assets/ 里；manifestRow 覆盖可注入损坏。 */
  private void installAssets(
      String architecture, Map<String, String> manifestRowOverrides, boolean corruptPayload)
      throws Exception {
    byte[][] payloads = {elf(183), elf(183), elf(183)};
    String[] targets = {"sshd", "ssh-keygen", "lib/libwrap.so.0"};
    StringBuilder files = new StringBuilder();
    for (int i = 0; i < 3; i++) {
      String digest = corruptPayload && i == 1 ? "0".repeat(64) : sha256(payloads[i]);
      String row =
          "{\"asset\":\"payload-"
              + i
              + ".bin\",\"target\":\""
              + targets[i]
              + "\",\"sha256\":\""
              + (manifestRowOverrides.containsKey(targets[i])
                  ? manifestRowOverrides.get(targets[i])
                  : digest)
              + "\",\"bytes\":"
              + payloads[i].length
              + "}";
      if (files.length() > 0) files.append(',');
      files.append(row);
    }
    String manifest =
        "{\"schema\":1,\"architecture\":\"" + architecture + "\",\"files\":[" + files + "]}";
    Path apk = temporary.resolve("fixture.apk");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apk))) {
      zip.putNextEntry(new ZipEntry("assets/container-ssh/manifest.json"));
      zip.write(manifest.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      for (int i = 0; i < 3; i++) {
        zip.putNextEntry(new ZipEntry("assets/container-ssh/payload-" + i + ".bin"));
        zip.write(payloads[i]);
        zip.closeEntry();
      }
    }
    var add = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
    add.setAccessible(true);
    assertTrue((int) add.invoke(assets, apk.toString()) > 0);
  }

  @Test
  public void bindAddsAllThreeGuestPathsToTheLaunchSpec() throws Exception {
    installAssets("aarch64", Map.of(), false);
    assertTrue(ContainerSshAssets.supported(context));
    LaunchSpec.Builder builder = LaunchSpec.runtime(new StubRuntime());
    ContainerSshAssets.bind(context, builder);
    LaunchSpec spec = builder.build();
    List<String> guests = new java.util.ArrayList<>();
    for (ContainerRuntime.Bind entry : spec.binds) guests.add(entry.guest());
    // 第一个是主机密钥目录（/run/dsha-container-ssh），其余三个是随包 payload。
    assertEquals(4, guests.size());
    assertEquals("/run/dsha-container-ssh", guests.get(0));
    assertTrue(guests.contains("/usr/sbin/sshd"));
    assertTrue(guests.contains("/usr/bin/ssh-keygen"));
    assertTrue(guests.contains("/usr/lib/aarch64-linux-gnu/libwrap.so.0"));
    // 宿主侧字节落在 dataDir/no_backup 的按清单摘要命名的目录里（Windows 临时目录
    // 可能有 8.3 短名，两边都取规范路径再比较）。
    Path noBackup = data.toRealPath().resolve("no_backup");
    for (ContainerRuntime.Bind entry : spec.binds)
      assertTrue(
          entry.host() + " under " + noBackup,
          new File(entry.host()).getCanonicalFile().toPath().startsWith(noBackup));
  }

  @Test
  public void manifestArchitectureMismatchIsRejectedForBothSides() throws Exception {
    installAssets("x86_64", Map.of(), false);
    assertFalse("arm64 设备不接受 x86_64 清单", ContainerSshAssets.supported(context));
    try {
      ContainerSshAssets.bind(context, LaunchSpec.runtime(new StubRuntime()));
      fail("expected SSH_ASSET_ARCHITECTURE");
    } catch (IOException error) {
      assertEquals("SSH_ASSET_ARCHITECTURE", error.getMessage());
    }
  }

  @Test
  public void payloadDigestMismatchIsRejected() throws Exception {
    installAssets("aarch64", Map.of("ssh-keygen", "1".repeat(64)), false);
    try {
      ContainerSshAssets.bind(context, LaunchSpec.runtime(new StubRuntime()));
      fail("expected SSH_ASSET_BYTES");
    } catch (IOException error) {
      assertEquals("SSH_ASSET_BYTES", error.getMessage());
    }
  }

  @Test
  public void nonElfOrWrongArchitecturePayloadIsRejected() throws Exception {
    // e_machine 声明 x86_64（62），与 aarch64 清单不符。
    byte[][] payloads = {elf(183), elf(62), elf(183)};
    String[] targets = {"sshd", "ssh-keygen", "lib/libwrap.so.0"};
    StringBuilder files = new StringBuilder();
    for (int i = 0; i < 3; i++) {
      if (files.length() > 0) files.append(',');
      files.append(
          "{\"asset\":\"payload-"
              + i
              + ".bin\",\"target\":\""
              + targets[i]
              + "\",\"sha256\":\""
              + sha256(payloads[i])
              + "\",\"bytes\":"
              + payloads[i].length
              + "}");
    }
    String manifest = "{\"schema\":1,\"architecture\":\"aarch64\",\"files\":[" + files + "]}";
    Path apk = temporary.resolve("fixture.apk");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apk))) {
      zip.putNextEntry(new ZipEntry("assets/container-ssh/manifest.json"));
      zip.write(manifest.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      for (int i = 0; i < 3; i++) {
        zip.putNextEntry(new ZipEntry("assets/container-ssh/payload-" + i + ".bin"));
        zip.write(payloads[i]);
        zip.closeEntry();
      }
    }
    var add = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
    add.setAccessible(true);
    assertTrue((int) add.invoke(assets, apk.toString()) > 0);
    try {
      ContainerSshAssets.bind(context, LaunchSpec.runtime(new StubRuntime()));
      fail("expected SSH_ASSET_BYTES");
    } catch (IOException error) {
      assertEquals("SSH_ASSET_BYTES", error.getMessage());
    }
  }

  private static final class StubRuntime implements ContainerRuntime {
    @Override
    public String id() {
      return "proot";
    }

    @Override
    public String displayName() {
      return "stub";
    }

    @Override
    public boolean available() {
      return true;
    }

    @Override
    public String unavailableReason() {
      return "";
    }

    @Override
    public List<String> baseArgv(File rootfsDir, boolean hardlinkSupported) {
      return List.of();
    }

    @Override
    public List<String> baseArgv(File rootfsDir, boolean hardlinkSupported, File dataDomain) {
      return List.of();
    }

    @Override
    public void applyEnv(java.lang.ProcessBuilder pb, File baseDir, File libDir, File tmpDir) {}

    @Override
    public void prepare() {}
  }
}
