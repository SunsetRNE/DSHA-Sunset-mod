package com.deepseekharness.app.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.util.AdbResult;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class AdbKeepaliveTest {
  @Test
  public void otherUsersNeverReadOrRequestKeepalivePermission() {
    String result =
        AdbBridge.wirelessDebugKeepalive(
            false,
            () -> {
              throw new AssertionError("unexpected clone permission read");
            },
            () -> {
              throw new AssertionError("unexpected clone grant");
            });
    assertTrue(AdbResult.marker(result, "KEEPALIVE_SKIPPED"));
  }

  @Test
  public void existingOwnerPermissionRequiresNoCommand() {
    String result =
        AdbBridge.wirelessDebugKeepalive(
            true,
            () -> true,
            () -> {
              throw new AssertionError("unexpected duplicate grant");
            });
    assertTrue(AdbResult.marker(result, "KEEPALIVE_OK"));
  }

  @Test
  public void anAttemptRequiresReadbackFromTheOwnerInstallation() {
    AtomicBoolean permission = new AtomicBoolean();
    AtomicInteger requests = new AtomicInteger();
    String unavailable =
        AdbBridge.wirelessDebugKeepalive(true, permission::get, requests::incrementAndGet);
    assertTrue(AdbResult.marker(unavailable, "KEEPALIVE_WARN"));
    assertEquals(1, requests.get());

    String available =
        AdbBridge.wirelessDebugKeepalive(
            true,
            permission::get,
            () -> {
              requests.incrementAndGet();
              permission.set(true);
            });
    assertTrue(AdbResult.marker(available, "KEEPALIVE_OK"));
    assertEquals(2, requests.get());
  }
}
