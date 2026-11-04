package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;

import android.app.Application;
import android.graphics.Bitmap;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.VirtualScreenCoreIdentity;
import com.deepseekharness.app.util.VirtualScreenInputResult;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 本机真实配置→Manager→认证HTTP→Core→输入；只替换平台显示、时钟和最终输入系统调用。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public final class VirtualScreenAnimatedInputTest {
  private static final String PKG = "com.example.game";

  private static final class Fixture implements AutoCloseable {
    final AtomicLong clock = new AtomicLong(100);
    final AtomicInteger inputs = new AtomicInteger();
    final AtomicReference<String> actualPackage = new AtomicReference<>(PKG);
    final ConfigStore config = new ConfigStore(RuntimeEnvironment.getApplication());
    final VirtualScreenManager manager =
        new VirtualScreenManager(
            RuntimeEnvironment.getApplication(),
            clock::get,
            new VirtualScreenManager.Visuals() {
              public void present() {}

              public void stopped(long epoch) {}
            });
    final VirtualScreenCore.HostControl core;
    VirtualScreenDisplaySession display;
    VirtualScreenInputResult completion = VirtualScreenInputResult.ACCEPTED;
    Runnable onInput = () -> {};

    Fixture() throws Exception {
      core =
          VirtualScreenCore.bindControlForHost(
              VirtualScreenCoreIdentity.parse(
                  "123:456:2000:11111111-2222-3333-4444-555555555555:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
              (width, height) -> {
                display =
                    new VirtualScreenDisplaySession(
                        width,
                        height,
                        51,
                        VirtualScreenInput.commandForTest(
                            51,
                            (argv, timeout) -> {
                              inputs.incrementAndGet();
                              onInput.run();
                              return completion;
                            }));
                display.frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                display.frame.eraseColor(0xff123456);
                display.sequence = 10;
                return display;
              },
              actualPackage::get,
              clock::get,
              () -> {});
      var endpoint = core.handshake();
      var created =
          new VirtualScreenTransport().request(endpoint, "/vscreen/create", "width=144&height=256");
      assertTrue(created.toString(), created.optBoolean("ok"));
      set("core", endpoint);
      set("port", endpoint.port);
      set("token", endpoint.token);
      set("generation", created.getString("generation"));
      set("activeEpoch", 0L);
      set("channel", "shizuku");
    }

    void set(String name, Object value) throws Exception {
      var field = VirtualScreenManager.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(manager, value);
    }

    JSONObject observe(boolean dynamic) {
      assertTrue(config.setScreenAnimatedPackages(dynamic ? PKG : ""));
      JSONObject result = manager.preview();
      assertTrue(result.toString(), result.optBoolean("ok"));
      assertEquals(10, result.optLong("frameSeq"));
      return result;
    }

    JSONObject tap(String extra) {
      return manager.action("tap", display.generation, 10, "x=20&y=20" + extra);
    }

    @Override
    public void close() throws Exception {
      var worker = VirtualScreenManager.class.getDeclaredField("WORKER");
      worker.setAccessible(true);
      ((ScheduledExecutorService) worker.get(manager)).shutdownNow();
      core.close();
    }
  }

  @Test
  public void defaultStrictAndForgedQueryCannotEnableDynamicInput() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.observe(false);
      fixture.display.sequence = 11;
      JSONObject result = fixture.tap("&nativeFramePackages=" + PKG + "&nativeFrameRevision=1");
      assertEquals("FRAME_EXPIRED", result.optString("reason"));
      assertEquals(0, fixture.inputs.get());
    }
  }

  @Test
  public void explicitLocalAppAllowsFourSecondsOfFramesOnceAndNeverReplaysUnknown()
      throws Exception {
    try (var fixture = new Fixture()) {
      fixture.observe(true);
      fixture.display.sequence = 490;
      fixture.clock.addAndGet(4_000);
      fixture.completion = VirtualScreenInputResult.UNKNOWN;
      JSONObject result = fixture.tap("");
      assertEquals("INPUT_RESULT_UNKNOWN", result.optString("error"));
      assertTrue(result.optBoolean("executionUnknown"));
      assertEquals(1, fixture.inputs.get());
      assertEquals("FRAME_NOT_OBSERVED", fixture.tap("").optString("reason"));
      assertEquals(1, fixture.inputs.get());
    }
  }

  @Test
  public void revocationAndReenableDoNotReuseAnOldObservation() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.observe(true);
      assertTrue(fixture.config.setScreenAnimatedPackages(""));
      assertTrue(fixture.config.setScreenAnimatedPackages(PKG));
      assertEquals("FRAME_NOT_OBSERVED", fixture.tap("").optString("reason"));
      assertEquals(0, fixture.inputs.get());
    }
  }

  @Test
  public void retainedImageCleanupDoesNotChangeLocalPolicyOrReplaySuccessfulInput()
      throws Exception {
    try (var fixture = new Fixture()) {
      fixture.observe(true);
      new VirtualScreenTransport()
          .request(
              fixture.core.handshake(),
              "/vscreen/observation-release",
              "observationId=already-released");
      fixture.display.sequence = 11;
      JSONObject result = fixture.tap("");
      assertTrue(result.toString(), result.optBoolean("ok"));
      assertEquals(1, fixture.inputs.get());
      assertEquals("FRAME_NOT_OBSERVED", fixture.tap("").optString("reason"));
      assertEquals(1, fixture.inputs.get());
    }
  }

  @Test
  public void animatedWindowExpiresByTimeEvenWhenFewFramesAdvance() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.observe(true);
      fixture.display.sequence = 11;
      fixture.clock.addAndGet(5_001);
      assertEquals("OBSERVATION_TIMEOUT", fixture.tap("").optString("reason"));
      assertEquals(0, fixture.inputs.get());
    }
  }

  @Test
  public void animatedPixelsNeverAuthorizeAnotherTargetOrRelaxTextAndKeyInput() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.observe(true);
      fixture.display.sequence = 11;
      for (String route : new String[] {"key", "type", "check"}) {
        JSONObject result =
            fixture.manager.action(
                route,
                fixture.display.generation,
                10,
                route.equals("key") ? "keycode=4" : "text=hello");
        assertEquals(route + ": " + result, "FRAME_EXPIRED", result.optString("reason"));
      }
      fixture.actualPackage.set("com.example.payment");
      assertEquals("OBSERVED_TARGET_CHANGED", fixture.tap("").optString("reason"));
      assertEquals(0, fixture.inputs.get());
    }
  }

  @Test
  public void previewEncodingDelayCannotRenewFiveSecondWindow() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.observe(true);
      // 首次观察已缓存帧10；模拟真实采集到新像素/帧11，才会进入待测编码器。
      synchronized (fixture.display.lock) {
        fixture.display.frame.eraseColor(0xff00ff00);
        fixture.display.sequence = 11;
        fixture.display.capturedAt = fixture.clock.get();
      }
      AtomicInteger encodes = new AtomicInteger();
      JSONObject result =
          VirtualScreenPixels.preview(
              fixture.display,
              -1,
              null,
              () ->
                  new JSONObject()
                      .put("ok", true)
                      .put("active", true)
                      .put("displayId", 51)
                      .put("generation", fixture.display.generation)
                      .put("package", PKG),
              (bitmap, output) -> {
                encodes.incrementAndGet();
                fixture.clock.addAndGet(5_001);
                fixture.display.sequence++;
                return bitmap.compress(Bitmap.CompressFormat.JPEG, 65, output);
              });
      assertTrue(result.toString(), result.optBoolean("ok"));
      assertEquals(
          "The regression must exercise actual encoding, not same-frame cache", 1, encodes.get());
      assertEquals(11, result.getLong("sourceFrameSeq"));
      assertEquals(12, result.getLong("currentFrameSeq"));
      assertEquals(5_001, result.getLong("observationAgeMs"));
      assertEquals(
          "OBSERVATION_TIMEOUT",
          fixture
              .manager
              .action("tap", fixture.display.generation, 11, "x=20&y=20")
              .optString("reason"));
      assertEquals(0, fixture.inputs.get());
    }
  }

  @Test
  public void coreRejectsReorderedOlderRevisionAndSameRevisionWithDifferentList() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.observe(true);
      long oldRevision = fixture.config.screenAnimatedPackagesRevision();
      assertTrue(fixture.config.setScreenAnimatedPackages(""));
      assertTrue(fixture.manager.preview().optBoolean("ok"));
      long currentRevision = fixture.display.frameSettings.revision();
      String action =
          "generation="
              + fixture.display.generation
              + "&frameSeq=10&x=20&y=20"
              + "&nativeFramePackages="
              + PKG
              + "&nativeFrameRevision=";
      var transport = new VirtualScreenTransport();
      assertEquals(
          "SCREEN_FRAME_POLICY_STALE",
          transport
              .request(fixture.core.handshake(), "/vscreen/tap", action + oldRevision)
              .optString("error"));
      assertEquals(
          "SCREEN_FRAME_POLICY_CONFLICT",
          transport
              .request(fixture.core.handshake(), "/vscreen/tap", action + currentRevision)
              .optString("error"));
      assertEquals(currentRevision, fixture.display.frameSettings.revision());
      assertEquals("", fixture.display.frameSettings.configured());
      assertEquals(10, fixture.display.observed);
      assertEquals(0, fixture.inputs.get());
    }
  }

  @Test
  public void requestQueuedBeforeRevocationCannotBorrowAReenabledSameFrameObservation()
      throws Exception {
    try (var fixture = new Fixture()) {
      fixture.observe(true);
      var field = VirtualScreenManager.class.getDeclaredField("ACTIONS");
      field.setAccessible(true);
      var actions = (java.util.concurrent.locks.ReentrantLock) field.get(fixture.manager);
      AtomicReference<JSONObject> result = new AtomicReference<>();
      Thread caller = new Thread(() -> result.set(fixture.tap("")), "queued-animated-tap");
      actions.lock();
      try {
        caller.start();
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (!actions.hasQueuedThread(caller) && System.nanoTime() < until) Thread.sleep(5);
        assertTrue(
            "The call must capture its scope before waiting", actions.hasQueuedThread(caller));
        assertTrue(fixture.config.setScreenAnimatedPackages(""));
        assertTrue(fixture.config.setScreenAnimatedPackages(PKG));
        assertTrue(fixture.manager.preview().optBoolean("ok"));
        assertEquals(10, fixture.display.observed);
      } finally {
        actions.unlock();
        caller.join(3000);
      }
      assertFalse(caller.isAlive());
      assertEquals("SCREEN_FRAME_POLICY_CHANGED", result.get().optString("error"));
      assertFalse(result.get().optBoolean("executionUnknown"));
      assertEquals(0, fixture.inputs.get());
    }
  }

  @Test
  public void revocationDuringAnAlreadySubmittedUnknownInputCannotRewriteOrReplayItsResult()
      throws Exception {
    try (var fixture = new Fixture()) {
      fixture.observe(true);
      fixture.display.sequence = 20;
      fixture.completion = VirtualScreenInputResult.UNKNOWN;
      fixture.onInput = () -> assertTrue(fixture.config.setScreenAnimatedPackages(""));
      JSONObject result = fixture.tap("");
      assertEquals("INPUT_RESULT_UNKNOWN", result.optString("error"));
      assertTrue(result.optBoolean("executionUnknown"));
      assertEquals(1, fixture.inputs.get());
    }
  }

  @Test
  public void displayedSourceDoesNotRegressEvenIfAnOldResponseAdvertisesANewerCurrentSequence()
      throws Exception {
    try (var fixture = new Fixture()) {
      JSONObject older = fixture.observe(false);
      fixture.display.sequence = 20;
      JSONObject newer = fixture.manager.preview();
      assertTrue(newer.toString(), newer.optBoolean("ok"));
      assertTrue(fixture.manager.frameCurrent(newer));
      older.put("currentFrameSeq", 25);
      assertFalse(fixture.manager.frameCurrent(older));
      assertEquals(20, fixture.display.observed);
    }
  }

  @Test
  public void policyRevokedDuringAuthenticatedHandshakeStopsBeforeAnyBusinessRequestBytes()
      throws Exception {
    try (var fixture = new Fixture();
        var listener =
            new java.net.ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1"))) {
      fixture.observe(true);
      var original = fixture.core.handshake();
      var endpoint =
          new com.deepseekharness.app.util.VirtualScreenHandshake(
              listener.getLocalPort(), original.token, original.identity);
      fixture.set("core", endpoint);
      fixture.set("port", endpoint.port);
      AtomicReference<byte[]> business = new AtomicReference<>();
      AtomicReference<Throwable> failed = new AtomicReference<>();
      Thread peer =
          new Thread(
              () -> {
                try (var socket = listener.accept()) {
                  socket.setSoTimeout(2000);
                  var input =
                      new java.io.BufferedReader(
                          new java.io.InputStreamReader(
                              socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                  String[] hello = input.readLine().split(" ");
                  String serverNonce = com.deepseekharness.app.bridge.BridgeHandshake.nonce();
                  var proof = new VirtualScreenPeerProof.Session(endpoint, hello[2], serverNonce);
                  assertTrue(fixture.config.setScreenAnimatedPackages(""));
                  socket
                      .getOutputStream()
                      .write(
                          ("DSHA_VSCREEN_PROOF 1 "
                                  + serverNonce
                                  + " "
                                  + proof.serverProof()
                                  + "\r\n")
                              .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                  socket.getOutputStream().flush();
                  business.set(socket.getInputStream().readAllBytes());
                } catch (Throwable failure) {
                  failed.set(failure);
                }
              },
              "revoke-during-vscreen-peer-proof");
      peer.start();
      JSONObject result = fixture.tap("");
      peer.join(3000);
      assertFalse(peer.isAlive());
      assertNull(failed.get());
      assertEquals("SCREEN_FRAME_POLICY_CHANGED", result.optString("error"));
      assertFalse(result.optBoolean("executionUnknown"));
      assertArrayEquals(new byte[0], business.get());
      assertEquals(0, fixture.inputs.get());
    }
  }
}
