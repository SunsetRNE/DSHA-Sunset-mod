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
  @org.junit.Rule
  public final org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();

  @Test
  public void actualSymlinksUseTheSameCanonicalCredentialBoundaryForExportAndReadfile()
      throws Exception {
    java.io.File root = temporary.newFolder("rootfs");
    java.nio.file.Path ordinary =
        java.nio.file.Files.createDirectories(root.toPath().resolve("root/work"));
    java.nio.file.Path secret =
        java.nio.file.Files.createDirectories(root.toPath().resolve("root/.dsh"))
            .resolve(".bridge_token");
    java.nio.file.Files.writeString(secret, "fixture-secret");
    java.nio.file.Path link = ordinary.resolve("report.md");
    java.nio.file.Files.createSymbolicLink(link, secret);
    String canonical = link.toFile().getCanonicalPath();
    assertFalse(BridgePathPolicy.denied("/root/work/report.md"));
    assertTrue(BridgePathPolicy.deniedGuestView(canonical, root.getCanonicalPath()));
    assertTrue(
        BridgePathPolicy.deniedReadCanonical(
            canonical, root.getCanonicalPath(), null, null, false));
    assertTrue(BridgePathPolicy.deniedReadRaw("/root/.dsh/.bridge_token"));
  }

  @Test
  public void rootfsPrefixAndMissingIdentityCannotAuthorizeOutsideFiles() throws Exception {
    java.io.File root = temporary.newFolder("rootfs");
    java.io.File sibling = temporary.newFolder("rootfs-other");
    java.io.File outside = new java.io.File(sibling, "report.md");
    java.nio.file.Files.writeString(outside.toPath(), "outside");
    assertTrue(
        BridgePathPolicy.deniedGuestView(outside.getCanonicalPath(), root.getCanonicalPath()));
    assertTrue(
        BridgePathPolicy.deniedReadCanonical(
            outside.getCanonicalPath(), root.getCanonicalPath(), null, null, false));
    assertTrue(
        BridgePathPolicy.deniedReadCanonical(outside.getCanonicalPath(), null, null, null, false));
    assertTrue(BridgePathPolicy.resolveReadFile("/root/report.md", null) == null);
  }

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
        BridgePathPolicy.deniedHostPath(
            "/data/data/com.other.app/files/x",
            "/data/data/io.github.sunsetrne.dsha/files/linux/ubuntu"));
    // rootfs 内部交给 guest 视角判据，不因位于 /data/data 下一律拒绝
    assertFalse(
        BridgePathPolicy.deniedHostPath(
            "/data/data/io.github.sunsetrne.dsha/files/linux/ubuntu/root/report.md",
            "/data/data/io.github.sunsetrne.dsha/files/linux/ubuntu"));
  }
}
