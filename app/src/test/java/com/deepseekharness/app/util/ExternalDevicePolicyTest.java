package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ExternalDevicePolicyTest {
  @Test
  public void rejectsHostAndNetworkTargetsForUsbOperations() {
    assertFalse(ExternalDevicePolicy.isUsbSerial("127.0.0.1:5038"));
    assertFalse(ExternalDevicePolicy.isUsbSerial("192.168.1.5:5555"));
    assertTrue(ExternalDevicePolicy.isUsbSerial("0123abcd"));
  }

  @Test
  public void onlyManagedPartitionsCanBeFlashed() {
    assertEquals(
        ExternalDevicePolicy.Risk.L2,
        ExternalDevicePolicy.plan("flash", "init_boot", "x.img").risk);
    assertFalse(ExternalDevicePolicy.plan("flash", "userdata", "x.img").allowed());
    assertFalse(ExternalDevicePolicy.plan("unlock", "", "").allowed());
  }

  @Test
  public void shellDoesNotTurnIntoAnArbitraryScriptSurface() {
    assertEquals(
        ExternalDevicePolicy.Risk.L0,
        ExternalDevicePolicy.plan("shell", "", "getprop ro.product.device").risk);
    assertEquals(
        ExternalDevicePolicy.Risk.L3,
        ExternalDevicePolicy.plan("shell", "", "dd if=x of=/dev/block/by-name/boot").risk);
  }

  @Test
  public void wirelessTargetsAreIpv4WithPortAndNeverLoopback() {
    assertTrue(ExternalDevicePolicy.isWirelessTarget("192.168.1.5:5555"));
    assertTrue(ExternalDevicePolicy.isAdbTarget("192.168.1.5:41234"));
    for (String bad :
        new String[] {
          "127.0.0.1:5555", "127.1.2.3:5555", "0.0.0.0:5555", "224.0.0.1:5555",
              "255.255.255.255:5555",
          "192.168.1.5", "192.168.1.5:0", "192.168.1.5:70000", "192.168.01.5:5555",
              "256.1.1.1:5555",
          "phone.lan:5555", "[::1]:5555", "localhost:5555", "-a"
        }) assertFalse(bad, ExternalDevicePolicy.isWirelessTarget(bad));
    assertFalse(ExternalDevicePolicy.isUsbSerial("192.168.1.5:5555"));
  }

  @Test
  public void connectPairInstallAndRebootPlans() {
    assertEquals(
        ExternalDevicePolicy.Risk.L0,
        ExternalDevicePolicy.plan("connect", "", "10.0.0.8:5555").risk);
    assertFalse(ExternalDevicePolicy.plan("connect", "", "127.0.0.1:5555").allowed());
    assertEquals(
        ExternalDevicePolicy.Risk.L1,
        ExternalDevicePolicy.plan("pair", "", "10.0.0.8:37000 123456").risk);
    assertFalse(ExternalDevicePolicy.plan("pair", "", "10.0.0.8:37000 12345").allowed());
    assertFalse(ExternalDevicePolicy.plan("pair", "", "10.0.0.8:37000 123456 x").allowed());
    String name = "0123abcd-0000-4000-8000-000000000000-app.apk";
    assertEquals(
        ExternalDevicePolicy.Operation.INSTALL,
        ExternalDevicePolicy.plan("install", "", name).operation);
    assertFalse(ExternalDevicePolicy.plan("install", "", "/sdcard/Download/app.apk").allowed());
    assertEquals(
        ExternalDevicePolicy.Risk.L1, ExternalDevicePolicy.plan("reboot", "", "bootloader").risk);
    assertFalse(ExternalDevicePolicy.plan("reboot", "", "edl").allowed());
    assertFalse(ExternalDevicePolicy.plan("reboot", "", "sideload").allowed());
    assertEquals(
        new String[] {"10.0.0.8:37000", "123456"}[1],
        ExternalDevicePolicy.pairTarget(" 10.0.0.8:37000  123456 ")[1]);
  }
}
