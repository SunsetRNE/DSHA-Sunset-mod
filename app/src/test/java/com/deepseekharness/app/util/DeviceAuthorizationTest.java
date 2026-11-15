package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class DeviceAuthorizationTest {
  private static DeviceAuthorization.Values values(boolean root, DeviceShellPolicy.Mode mode) {
    return new DeviceAuthorization.Values(root, mode, true);
  }

  @Test
  public void oldKeysAndDefaultsRemainTheOnlySource() {
    var authority = new DeviceAuthorization();
    Map<String, Object> preferences = new HashMap<>();
    var absent = authority.snapshot(() -> DeviceAuthorization.Values.fromPreferences(preferences));
    assertTrue(absent.readable());
    assertFalse(absent.rootAllowed());
    assertFalse(absent.adbAllowed());
    assertEquals(DeviceShellPolicy.Mode.STRICT, absent.rootMode());
    preferences.put(Constants.KEY_ALLOW_ROOT_SHELL, true);
    preferences.put(Constants.KEY_ROOT_POLICY_MODE, "relaxed");
    preferences.put("adb_enabled", true);
    var present = authority.snapshot(() -> DeviceAuthorization.Values.fromPreferences(preferences));
    assertTrue(present.rootAllowed());
    assertTrue(present.adbAllowed());
    assertEquals(DeviceShellPolicy.Mode.RELAXED, present.rootMode());
    assertEquals(3, preferences.size());
    preferences.put(Constants.KEY_ROOT_POLICY_MODE, "future");
    assertEquals(
        DeviceShellPolicy.Mode.STRICT,
        authority
            .snapshot(() -> DeviceAuthorization.Values.fromPreferences(preferences))
            .rootMode());
  }

  @Test
  public void nonBooleanIntentNeverBecomesANewDeviceGrant() {
    for (Object malformed : new Object[] {"true", "1", 1, 1L, "false"}) {
      var snapshot =
          new DeviceAuthorization()
              .snapshot(
                  () ->
                      DeviceAuthorization.Values.fromPreferences(
                          Map.of(
                              Constants.KEY_ALLOW_ROOT_SHELL,
                              malformed,
                              "adb_enabled",
                              malformed,
                              Constants.KEY_ROOT_POLICY_MODE,
                              "full")));
      assertFalse(snapshot.rootAllowed());
      assertFalse(snapshot.adbAllowed());
      assertEquals(DeviceShellPolicy.Mode.STRICT, snapshot.planningMode(false, true));
    }
  }

  @Test
  public void everySwitchModeAndCommandClassMatchesExistingPolicy() {
    String[] commands = {
      "id", "touch /sdcard/Download/a", "am force-stop example.app",
      "content query --uri content://sms", "echo a; echo b", "fastboot devices"
    };
    var authority = new DeviceAuthorization();
    for (boolean root : new boolean[] {false, true})
      for (DeviceShellPolicy.Mode mode : DeviceShellPolicy.Mode.values())
        for (boolean present : new boolean[] {false, true})
          for (boolean force : new boolean[] {false, true}) {
            var snapshot = authority.snapshot(() -> values(root, mode));
            var old =
                force || mode != DeviceShellPolicy.Mode.STRICT && root && present
                    ? mode
                    : DeviceShellPolicy.Mode.STRICT;
            assertEquals(old, snapshot.planningMode(force, present));
            for (String command : commands) {
              var expected = DeviceShellPolicy.inspect(command, old);
              var actual =
                  DeviceShellPolicy.inspect(command, snapshot.planningMode(force, present));
              assertEquals(expected.kind, actual.kind);
              assertEquals(expected.argv, actual.argv);
              assertEquals(expected.operands, actual.operands);
              assertEquals(expected.shell, actual.shell);
              assertEquals(expected.reason, actual.reason);
            }
          }
  }

  @Test
  public void changeFencesOldSnapshotsBeforeCallbacksAndPublishesOnlyAfterWrite() {
    var authority = new DeviceAuthorization();
    var source = new AtomicReference<>(values(true, DeviceShellPolicy.Mode.FULL));
    var old = authority.snapshot(source::get);
    assertTrue(authority.current(old));
    assertTrue(
        authority.change(
            () -> {
              assertFalse(authority.current(old));
              var during = authority.snapshot(source::get);
              assertEquals(DeviceAuthorization.ReadState.CHANGING, during.state);
              assertFalse(during.rootAllowed());
            },
            () -> {
              assertEquals(DeviceShellPolicy.Mode.FULL, source.get().rootMode());
              source.set(values(false, DeviceShellPolicy.Mode.STRICT));
              assertFalse(authority.snapshot(source::get).readable());
              return true;
            }));
    var after = authority.snapshot(source::get);
    assertTrue(after.readable());
    assertTrue(authority.current(after));
    assertFalse(after.rootAllowed());
    assertTrue(after.revision > old.revision);
  }

  @Test
  public void readOverlappingWriteNeverPublishesATornCombinationOrRetriesSource() {
    var authority = new DeviceAuthorization();
    var reads = new AtomicInteger();
    var snapshot =
        authority.snapshot(
            () -> {
              reads.incrementAndGet();
              authority.change(() -> {}, () -> true);
              return values(true, DeviceShellPolicy.Mode.FULL);
            });
    assertEquals(1, reads.get());
    assertEquals(DeviceAuthorization.ReadState.CHANGING, snapshot.state);
    assertFalse(snapshot.rootAllowed());
    assertEquals(DeviceShellPolicy.Mode.STRICT, snapshot.rootMode());
    assertFalse(authority.current(snapshot));
  }

  @Test
  public void failedOrThrowingWriteDoesNotReviveOldSnapshotOrOverwriteSource() {
    var authority = new DeviceAuthorization();
    var source = values(true, DeviceShellPolicy.Mode.RELAXED);
    var old = authority.snapshot(() -> source);
    assertFalse(authority.change(() -> {}, () -> false));
    assertFalse(authority.current(old));
    assertEquals(DeviceShellPolicy.Mode.RELAXED, authority.snapshot(() -> source).rootMode());
    try {
      authority.change(
          () -> {
            throw new IllegalStateException("cancelled");
          },
          () -> {
            fail("must not persist after invalidation failure");
            return true;
          });
      fail("failure must remain visible");
    } catch (IllegalStateException expected) {
      assertEquals("cancelled", expected.getMessage());
    }
    assertTrue(authority.snapshot(() -> source).readable());
  }

  @Test
  public void sourceFailureAndForeignSnapshotAreNotCurrent() {
    var authority = new DeviceAuthorization();
    var other = new DeviceAuthorization();
    assertFalse(authority.current(other.snapshot(() -> values(true, DeviceShellPolicy.Mode.FULL))));
    var unavailable =
        authority.snapshot(
            () -> {
              throw new IllegalStateException("unreadable");
            });
    assertEquals(DeviceAuthorization.ReadState.UNAVAILABLE, unavailable.state);
    assertFalse(unavailable.rootAllowed());
    assertFalse(unavailable.adbAllowed());
    assertFalse(authority.current(unavailable));
  }

  @Test
  public void blockedPersistenceNeverBlocksReaderOrOtherWriter() throws Exception {
    var authority = new DeviceAuthorization();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    Thread writer =
        new Thread(
            () -> {
              try {
                authority.change(
                    () -> {},
                    () -> {
                      entered.countDown();
                      await(release);
                      return true;
                    });
              } catch (Throwable error) {
                failure.set(error);
              }
            });
    writer.start();
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      var finished = new CountDownLatch(1);
      Thread reader =
          new Thread(
              () -> {
                try {
                  assertEquals(
                      DeviceAuthorization.ReadState.CHANGING,
                      authority.snapshot(() -> values(true, DeviceShellPolicy.Mode.FULL)).state);
                  assertTrue(authority.change(() -> {}, () -> true));
                  assertFalse(
                      authority
                          .snapshot(() -> values(true, DeviceShellPolicy.Mode.FULL))
                          .readable());
                } catch (Throwable error) {
                  failure.set(error);
                } finally {
                  finished.countDown();
                }
              });
      reader.start();
      assertTrue(
          "reader or second writer blocked on persistence", finished.await(2, TimeUnit.SECONDS));
      reader.join(2000);
    } finally {
      release.countDown();
      writer.join(2000);
    }
    assertFalse(writer.isAlive());
    if (failure.get() != null) throw new AssertionError(failure.get());
    assertTrue(authority.snapshot(() -> values(false, DeviceShellPolicy.Mode.STRICT)).readable());
  }

  @Test
  public void callbacksAndSourceAreOutsideAuthorityMonitor() throws Exception {
    var authority = new DeviceAuthorization();
    var entered = new AtomicBoolean();
    Runnable crossThread =
        () -> {
          var complete = new CountDownLatch(1);
          Thread observer =
              new Thread(
                  () -> {
                    authority.snapshot(() -> values(false, DeviceShellPolicy.Mode.STRICT));
                    complete.countDown();
                  });
          observer.start();
          await(complete);
          entered.set(true);
        };
    authority.snapshot(
        () -> {
          crossThread.run();
          return values(false, DeviceShellPolicy.Mode.STRICT);
        });
    assertTrue(entered.get());
    entered.set(false);
    assertTrue(
        authority.change(
            crossThread,
            () -> {
              crossThread.run();
              return true;
            }));
    assertTrue(entered.get());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("deadline exceeded");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
