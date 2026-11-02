package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;
import org.junit.Test;

public final class ExternalToolPolicyTest {
  private static List<String> adb(String... tail) {
    var value =
        new java.util.ArrayList<String>(
            List.of(
                "/root/lib/ld-linux-aarch64.so.1",
                "--library-path",
                "/root/lib",
                "/root/usr/bin/adb"));
    java.util.Collections.addAll(value, tail);
    return value;
  }

  private static List<String> fastboot(String... tail) {
    var value =
        new java.util.ArrayList<String>(
            List.of(
                "/root/lib/ld-linux-aarch64.so.1",
                "--library-path",
                "/root/lib",
                "/root/usr/bin/fastboot",
                "-s",
                "serial"));
    java.util.Collections.addAll(value, tail);
    return value;
  }

  @Test
  public void onlyReadOnlyAdbShellCommandsPass() {
    assertNull(ExternalToolPolicy.rejection(adb("devices", "-l")));
    assertNull(
        ExternalToolPolicy.rejection(adb("-s", "serial", "shell", "getprop", "ro.product.device")));
    assertNull(ExternalToolPolicy.rejection(adb("-s", "serial", "reboot", "bootloader")));
    assertEquals(
        "EXTERNAL_ADB_REBOOT_ARGS",
        ExternalToolPolicy.rejection(adb("-s", "serial", "reboot", "edl")));
    assertEquals(
        "EXTERNAL_ADB_REBOOT_ARGS",
        ExternalToolPolicy.rejection(adb("-s", "serial", "reboot", "sideload")));
    assertEquals(
        "EXTERNAL_ADB_OPERATION_BLOCKED",
        ExternalToolPolicy.rejection(adb("-s", "serial", "sideload", "x.zip")));
    assertEquals(
        "EXTERNAL_ADB_SHELL_BLOCKED",
        ExternalToolPolicy.rejection(adb("-s", "serial", "shell", "dd", "if=x")));
    assertEquals(
        "EXTERNAL_ADB_SHELL_BLOCKED",
        ExternalToolPolicy.rejection(adb("-s", "serial", "shell", "pm", "install", "x.apk")));
  }

  @Test
  public void fastbootIsReadOnly() {
    assertNull(ExternalToolPolicy.rejection(fastboot("getvar", "all")));
    assertEquals(
        "EXTERNAL_FASTBOOT_WRITE_BLOCKED",
        ExternalToolPolicy.rejection(fastboot("flash", "boot", "x.img")));
    assertEquals(
        "EXTERNAL_FASTBOOT_WRITE_BLOCKED",
        ExternalToolPolicy.rejection(fastboot("set_active", "a")));
    assertNull(ExternalToolPolicy.rejection(fastboot("reboot")));
    assertNull(ExternalToolPolicy.rejection(fastboot("reboot", "bootloader")));
    assertEquals("EXTERNAL_FASTBOOT_ARGS", ExternalToolPolicy.rejection(fastboot("reboot", "edl")));
    for (String write :
        new String[] {"erase", "oem", "flashing", "boot", "update", "flashall", "-w"})
      assertEquals(
          write,
          "EXTERNAL_FASTBOOT_WRITE_BLOCKED",
          ExternalToolPolicy.rejection(fastboot(write, "x")));
  }

  private static List<String> withLibrary(String library, String... tail) {
    var value =
        new java.util.ArrayList<String>(
            List.of(
                "/root/lib/ld-linux-aarch64.so.1", "--library-path", library, "/root/usr/bin/adb"));
    java.util.Collections.addAll(value, tail);
    return value;
  }

  @Test
  public void emptyLibraryPathSegmentsAreRejected() {
    assertNull(ExternalToolPolicy.rejection(withLibrary("/root/lib:/root/usr/lib", "devices")));
    for (String library :
        new String[] {"/root/lib:", ":/root/lib", "/root/lib::/root/usr/lib", ":"})
      assertEquals(
          library,
          "EXTERNAL_LIBRARY_OUTSIDE_ROOT",
          ExternalToolPolicy.rejection(withLibrary(library, "devices")));
  }

  @Test
  public void adbServerOnlyAcceptsLoopbackNodaemon() {
    assertNull(
        ExternalToolPolicy.rejectionAdbServer(adb("-P", "40123", "nodaemon", "server"), "/"));
    assertEquals(
        "EXTERNAL_ADB_SERVER_ARGS",
        ExternalToolPolicy.rejectionAdbServer(adb("-a", "-P", "40123", "nodaemon", "server"), "/"));
    assertEquals(
        "EXTERNAL_ADB_SERVER_ARGS",
        ExternalToolPolicy.rejectionAdbServer(adb("-P", "40123", "start-server"), "/"));
    assertEquals(
        "EXTERNAL_ADB_OPERATION_BLOCKED",
        ExternalToolPolicy.rejection(adb("-P", "40123", "kill-server")));
  }

  @Test
  public void adbSessionClientMustUseServerPortAndTools() {
    List<String> server = adb("-P", "40123", "nodaemon", "server");
    assertEquals(
        40123, ExternalToolPolicy.adbSessionPort(server, adb("-P", "40123", "devices", "-l")));
    assertEquals(
        -1, ExternalToolPolicy.adbSessionPort(server, adb("-P", "40124", "devices", "-l")));
    assertEquals(-1, ExternalToolPolicy.adbSessionPort(server, adb("devices", "-l", "x")));
    assertEquals(-1, ExternalToolPolicy.adbSessionPort(server, fastboot("getvar", "all", "x")));
    assertEquals(
        -1,
        ExternalToolPolicy.adbSessionPort(
            adb("-P", "80", "nodaemon", "server"), adb("-P", "80", "devices")));
    assertEquals(-1, ExternalToolPolicy.adbSessionPort(null, adb("-P", "40123", "devices")));
  }

  @org.junit.Rule
  public org.junit.rules.TemporaryFolder temp = new org.junit.rules.TemporaryFolder();

  @Test
  public void wirelessConnectPairAndTargetedShell() {
    assertNull(ExternalToolPolicy.rejection(adb("connect", "192.168.1.5:5555")));
    assertNull(ExternalToolPolicy.rejection(adb("pair", "192.168.1.5:37001", "123456")));
    assertNull(ExternalToolPolicy.rejection(adb("-s", "192.168.1.5:5555", "shell", "id")));
    assertNull(ExternalToolPolicy.rejection(adb("-s", "192.168.1.5:5555", "wait-for-device")));
    assertEquals(
        "EXTERNAL_ADB_CONNECT_ARGS",
        ExternalToolPolicy.rejection(adb("connect", "127.0.0.1:5555")));
    assertEquals(
        "EXTERNAL_ADB_CONNECT_ARGS",
        ExternalToolPolicy.rejection(adb("-s", "s", "connect", "1.2.3.4:5")));
    assertEquals(
        "EXTERNAL_ADB_PAIR_ARGS", ExternalToolPolicy.rejection(adb("pair", "192.168.1.5:37001")));
    assertEquals(
        "EXTERNAL_ADB_SERIAL",
        ExternalToolPolicy.rejection(adb("-s", "127.0.0.1:5555", "shell", "id")));
    assertEquals(
        "EXTERNAL_FASTBOOT_SERIAL_REQUIRED",
        ExternalToolPolicy.rejection(
            List.of(
                "/root/lib/ld-linux-aarch64.so.1",
                "--library-path",
                "/root/lib",
                "/root/usr/bin/fastboot",
                "-s",
                "192.168.1.5:5555",
                "getvar",
                "all")));
  }

  @Test
  public void installShapesRequireImportRootAndPlannedRemoteTargets() throws Exception {
    java.io.File imports = temp.newFolder("imports");
    java.io.File apk = new java.io.File(imports, "0123abcd-0000-4000-8000-000000000000-app.apk");
    java.nio.file.Files.write(apk.toPath(), new byte[] {1});
    String root = imports.getPath();
    String local = apk.getPath();
    assertNull(ExternalToolPolicy.rejection(adb("-s", "s", "install", "-r", local), "/", root));
    assertNull(
        ExternalToolPolicy.rejection(
            adb("-s", "s", "install-multiple", "-r", local, local), "/", root));
    assertNull(
        ExternalToolPolicy.rejection(
            adb("-s", "s", "push", local, "/data/local/tmp/dsha_install/a000.apk"), "/", root));
    assertNull(
        ExternalToolPolicy.rejection(adb("-s", "s", "push", local, "/sdcard/app.apk"), "/", root));
    // 没有导入区就不放行任何安装 / 推送
    assertEquals(
        "EXTERNAL_IMPORT_ROOT_REQUIRED",
        ExternalToolPolicy.rejection(adb("-s", "s", "install", "-r", local)));
    // 导入区外的本地文件
    assertEquals(
        "EXTERNAL_ADB_INSTALL_SOURCE",
        ExternalToolPolicy.rejection(adb("-s", "s", "install", "-r", "/etc/hosts.apk"), "/", root));
    assertEquals(
        "EXTERNAL_ADB_INSTALL_SOURCE",
        ExternalToolPolicy.rejection(
            adb("-s", "s", "install", "-r", root + "/../x.apk"), "/", root));
    // 缺少 -r 或附加参数
    assertEquals(
        "EXTERNAL_ADB_INSTALL_ARGS",
        ExternalToolPolicy.rejection(adb("-s", "s", "install", local), "/", root));
    assertEquals(
        "EXTERNAL_ADB_INSTALL_ARGS",
        ExternalToolPolicy.rejection(adb("-s", "s", "install", "-r", "-g", local), "/", root));
    // 推到计划之外的远端位置
    for (String remote :
        new String[] {
          "/system/app/x.apk",
          "/data/local/tmp/other.apk",
          "/sdcard/../data/x",
          "/sdcard/a/b",
          "/sdcard/.x"
        })
      assertEquals(
          remote,
          "EXTERNAL_ADB_PUSH_TARGET",
          ExternalToolPolicy.rejection(adb("-s", "s", "push", local, remote), "/", root));
    assertNull(
        ExternalToolPolicy.rejection(
            adb(
                "-s",
                "s",
                "push",
                local,
                "/sdcard/Android/obb/com.example.game/main.1.com.example.game.obb"),
            "/",
            root));
    assertEquals(
        "EXTERNAL_ADB_PUSH_TARGET",
        ExternalToolPolicy.rejection(
            adb(
                "-s",
                "s",
                "push",
                local,
                "/sdcard/Android/obb/com.example.game/main.1.com.other.obb"),
            "/",
            root));
  }

  @Test
  public void plannedRemoteShellPassesOnlyGeneratedForms() {
    assertNull(
        ExternalToolPolicy.rejection(adb("-s", "s", "shell", "su -c 'pm install-commit 12'")));
    assertNull(
        ExternalToolPolicy.rejection(
            adb("-s", "s", "shell", "mkdir -p '/data/local/tmp/dsha_install'")));
    assertEquals(
        "EXTERNAL_ADB_SHELL_BLOCKED",
        ExternalToolPolicy.rejection(adb("-s", "s", "shell", "su -c 'reboot'")));
    assertEquals(
        "EXTERNAL_ADB_SHELL_BLOCKED",
        ExternalToolPolicy.rejection(adb("-s", "s", "shell", "rm -rf '/data/local/tmp'")));
  }
}
