package com.deepseekharness.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 桥路径策略的回归用例。
 *
 * <p>这张表是「agent 能不能把凭据递出去」的唯一闸门，漏一个名字就等于整条链路失守。
 * 下面每一条都对应一个真实存在于容器里的明文令牌文件。
 */
public class BridgePathPolicyTest {

  @Test
  public void deniesEveryCredentialThatCarriesAPlaintextToken() {
    String[] denied = {
      "/root/.dsh/.bridge_token",
      "/root/.dsh/.credentials.yaml",
      "/root/.ssh/id_ed25519",
      "/root/.ssh/authorized_keys",
      "/root/.aws/credentials",
      "/root/.config/gcloud/application_default_credentials.json",
      "/root/.kube/config",
      "/root/.config/gh/hosts.yml", // gh auth login 的 token
      "/root/.docker/config.json", // registry 凭据
      "/root/.netrc",
      "/root/.pypirc",
      "/root/.gitconfig",
      "/root/.git-credentials",
      "/root/.npmrc",
      "/root/.yarnrc.yml",
      "/root/.cargo/credentials.toml",
      "/root/report/.netrc", // 名字判据在任何层级都生效
      "/root/id_rsa",
    };
    for (String path : denied) {
      assertTrue("必须拒绝: " + path, BridgePathPolicy.denied(path));
    }
  }

  @Test
  public void stillAllowsOrdinaryUserArtifacts() {
    String[] allowed = {
      "/root/report.md",
      "/root/work/output.json",
      "/sdcard/我的项目/说明.txt",
      "/sdcard/Download/DSHA/diagnostics.tar.gz",
    };
    for (String path : allowed) {
      assertFalse("不该拒绝: " + path, BridgePathPolicy.denied(path));
    }
  }

  @Test
  public void rejectsRelativeAndTrickyPaths() {
    String[] denied = {
      "",
      "relative/path.txt",
      "../.dsh/.bridge_token",
      "/root/./.dsh/.bridge_token",
      "/root//.dsh//.bridge_token",
      "\\root\\.dsh\\.bridge_token",
    };
    for (String path : denied) {
      assertTrue("必须拒绝: " + path, BridgePathPolicy.denied(path));
    }
  }

  @Test
  public void hostViewStillRejectsAppPrivateDataOutsideRootfs() {
    assertTrue(
<<<<<<< 208ea63a
        BridgePathPolicy.deniedGuestView("/data/data/com.dsh.client/shared_prefs/x.xml", rootfs));
    // rootfs 根本没有的宿主路径：拒绝
    assertTrue(BridgePathPolicy.deniedGuestView("/sdcard/report.md", rootfs));
  }

  /** rootfs 为 null（拿不到容器目录）时，只按通用宿主判据走，不能崩。 */
  @Test
  public void handlesMissingRootfs() {
    assertFalse(BridgePathPolicy.deniedGuestView("/tmp/x", null));
    assertTrue(BridgePathPolicy.deniedGuestView("/data/data/com.dsh.client/x", null));
    assertTrue(BridgePathPolicy.deniedGuestView(null, null));
    assertTrue(BridgePathPolicy.deniedGuestView("", null));
  }

  @Test
  public void readFileAllowsHostAndGuestWorkPathsButKeepsCredentialBoundary() {
    String rootfs = "/data/user/0/dsh.android.client/files/linux/ubuntu";
    String shared = "/storage/emulated/0";
    String media = "/data/media/0";
    for (String path :
        new String[] {
          rootfs + "/root/work/report.md",
          rootfs + "/root/nonexistent-xyz-123",
          rootfs + "/etc/hosts"
        }) {
      assertFalse(path, BridgePathPolicy.deniedReadCanonical(path, rootfs, shared, media, false));
    }
    for (String path :
        new String[] {shared, shared + "/Download/report.txt", media + "/Download/x"})
      assertFalse(path, BridgePathPolicy.deniedReadCanonical(path, rootfs, shared, media, true));
    for (String path :
        new String[] {
          rootfs,
          rootfs + "/root/.dsh/.bridge_token",
          rootfs + "/root/.ssh/id_rsa",
          "/root/.dsh/.bridge_token",
          "/data/user/0/dsh.android.client/shared_prefs/x.xml",
          "/data/data/dsh.android.client/files/secret",
          "/data/user_de/0/dsh.android.client/x",
          "/etc/hosts",
          shared + "/Download/report.txt"
        }) {
      assertTrue(path, BridgePathPolicy.deniedReadCanonical(path, rootfs, shared, media, false));
    }
    for (String path :
        new String[] {
          rootfs + "/root/report",
          "/data/user_de/0/app/x",
          "/storage/emulated/10/Download/x",
          "/data/media/10/Download/x",
          shared + "/Download/.env"
        })
      assertTrue(path, BridgePathPolicy.deniedReadCanonical(path, rootfs, shared, media, true));
    assertTrue(BridgePathPolicy.deniedReadCanonical(null, rootfs, shared, media, false));
    assertTrue(
        BridgePathPolicy.deniedReadCanonical(rootfs + "/root/x", null, shared, media, false));
    assertTrue(BridgePathPolicy.deniedReadCanonical(shared + "/x", rootfs, null, null, true));
    assertTrue(BridgePathPolicy.deniedReadRaw("/data/user_de/0/dsh.android.client/x"));
    assertTrue(BridgePathPolicy.deniedReadRaw("/data/user/0/dsh.android.client/x"));
    assertTrue(BridgePathPolicy.deniedReadRaw("/data/data/dsh.android.client/x"));
  }

  @Test
  public void readFileMapsGuestPathsWithoutHostFallback() throws Exception {
    File rootfs = temp.newFolder("rootfs");
    String guestPath = "/root/dsha-test-" + java.util.UUID.randomUUID();
    File guestDirectory = new File(rootfs, guestPath.substring(1));
    assertTrue(guestDirectory.mkdirs());
    File guestFile = new File(guestDirectory, "report.txt");
    assertTrue(guestFile.createNewFile());
    assertEquals(guestDirectory, BridgePathPolicy.resolveReadFile(guestPath, rootfs));
    assertEquals(guestFile, BridgePathPolicy.resolveReadFile(guestPath + "/report.txt", rootfs));
    assertEquals(
        new File(guestDirectory, "missing"),
        BridgePathPolicy.resolveReadFile(guestPath + "/missing", rootfs));
    assertEquals(new File(rootfs, "etc"), BridgePathPolicy.resolveReadFile("/etc", rootfs));
    assertEquals(
        new File("/sdcard/Download"), BridgePathPolicy.resolveReadFile("/sdcard/Download", rootfs));
    assertNull(BridgePathPolicy.resolveReadFile(guestPath, null));
    assertTrue(BridgePathPolicy.readSharedDomain("/storage/emulated/0/Download"));
    assertTrue(BridgePathPolicy.readSharedDomain("/data/media/0/Download"));
    assertFalse(BridgePathPolicy.readSharedDomain("/storage/emulatedfake/0"));
    assertFalse(BridgePathPolicy.readSharedDomain("/sdcard/../root/report"));
  }

  @Test
  public void readFileRejectsSensitiveDirectoriesInAnyHome() {
    String root = "/data/user/0/dsh.android.client/files/linux/ubuntu";
    for (String guest :
        new String[] {
          "/home/alice/.ssh/id_rsa", "/home/alice/.aws/credentials",
          "/home/alice/.android/debug.keystore", "/home/alice/.kube/config",
          "/home/alice/.dsh/session", "/home/alice/.config/gcloud/configurations/x"
        }) {
      assertTrue(guest, BridgePathPolicy.deniedReadRaw(guest));
      assertTrue(
          guest, BridgePathPolicy.deniedReadCanonical(root + guest, root, null, null, false));
    }
    assertFalse(BridgePathPolicy.deniedReadRaw("/home/alice/.ssh-backup/report.txt"));
    assertFalse(BridgePathPolicy.deniedReadRaw("/home/alice/.config/gcloud-backup/report.txt"));
  }

  @Test
  public void readFileCanonicalChecksBlockRealSymlinkTargets() throws Exception {
    Path root = temp.newFolder("guest-root").toPath();
    Path home = Files.createDirectories(root.resolve("home/alice"));
    Path sensitive = Files.createDirectories(home.resolve(".ssh")).resolve("id_rsa");
    Files.write(sensitive, new byte[] {1});
    Path external = temp.newFile("outside").toPath();
    Path secretLink = home.resolve("report");
    Path escapeLink = home.resolve("outside-link");
    try {
      Files.createSymbolicLink(secretLink, sensitive);
      Files.createSymbolicLink(escapeLink, external);
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      Assume.assumeNoException("Symbolic links unavailable on this host", e);
    }
    String rootCanonical = root.toFile().getCanonicalPath();
    assertFalse(BridgePathPolicy.deniedReadRaw("/home/alice/report"));
    assertTrue(
        BridgePathPolicy.deniedReadCanonical(
            secretLink.toRealPath().toString(), rootCanonical, null, null, false));
    assertTrue(
        BridgePathPolicy.deniedReadCanonical(
            escapeLink.toRealPath().toString(), rootCanonical, null, null, false));
  }

  /** normalize 的边界。 */
  @Test
  public void normalizesPaths() {
    assertEquals("/root/.dsh/x", BridgePathPolicy.normalize("/root/./foo/../.dsh/x"));
    assertEquals("/root", BridgePathPolicy.normalize("/root/"));
    assertEquals("/", BridgePathPolicy.normalize("/"));
    assertEquals("/a/b", BridgePathPolicy.normalize("//a///b//"));
    assertEquals("/x", BridgePathPolicy.normalize("/../x"));
    assertEquals("/", BridgePathPolicy.normalize("/../../.."));
=======
        BridgePathPolicy.deniedHostPath(
            "/data/data/com.other.app/files/x",
            "/data/data/io.github.sunsetrne.dsha/files/linux/ubuntu"));
    // rootfs 内部交给 guest 视角判据，不因位于 /data/data 下一律拒绝
    assertFalse(
        BridgePathPolicy.deniedHostPath(
            "/data/data/io.github.sunsetrne.dsha/files/linux/ubuntu/root/report.md",
            "/data/data/io.github.sunsetrne.dsha/files/linux/ubuntu"));
>>>>>>> sunset/main
  }
}
