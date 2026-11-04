package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;

import android.app.Application;
import com.deepseekharness.app.util.VirtualScreenCoreIdentity;
import com.deepseekharness.app.util.VirtualScreenHandshake;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 实际 Manager/串行队列/认证 socket；只替换 Core 的显示创建与应用启动结果。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public final class VirtualScreenRotationTest {
  private static final String PKG = "com.example.observed";
  private static final String ORIGINAL = "observed-generation";

  private static Object field(Object owner, String name) throws Exception {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static void field(Object owner, String name, Object value) throws Exception {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private static final class Fixture implements AutoCloseable {
    final AtomicBoolean visible = new AtomicBoolean(true);
    final AtomicBoolean running = new AtomicBoolean(true);
    final AtomicReference<Throwable> peerFailure = new AtomicReference<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final ServerSocket listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    final VirtualScreenHandshake endpoint =
        new VirtualScreenHandshake(
            listener.getLocalPort(),
            "a".repeat(48),
            VirtualScreenCoreIdentity.parse(
                "123:456:2000:11111111-2222-3333-4444-555555555555:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
    volatile Runnable onPresent = () -> {};
    volatile Runnable beforeSecondProof = () -> {};
    volatile String loseReply = "";
    final VirtualScreenManager manager =
        new VirtualScreenManager(
            RuntimeEnvironment.getApplication(),
            () -> 100,
            new VirtualScreenManager.Visuals() {
              public void present() {
                onPresent.run();
              }

              public void stopped(long fence) {}
            },
            new VirtualScreenManager.Channels() {
              public VirtualScreenChannel choose(android.content.Context app) {
                return null;
              }

              public VirtualScreenChannel find(String id) {
                return null;
              }
            });
    final Thread peer;

    Fixture() throws Exception {
      field(manager, "core", endpoint);
      field(manager, "port", endpoint.port);
      field(manager, "token", endpoint.token);
      field(manager, "generation", ORIGINAL);
      field(manager, "activeEpoch", 7L);
      ((AtomicLong) field(manager, "LIFECYCLE_EPOCH")).set(7);
      field(manager, "channel", "shizuku");
      listener.setSoTimeout(100);
      peer = new Thread(this::serve, "rotation-authenticated-peer");
      peer.setDaemon(true);
      peer.start();
    }

    private void serve() {
      int connections = 0, generation = 0;
      while (running.get()) {
        try (Socket socket = listener.accept()) {
          socket.setSoTimeout(2000);
          if (++connections == 2) beforeSecondProof.run();
          var proof = VirtualScreenPeerProof.server(socket, endpoint);
          var input =
              new BufferedReader(
                  new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
          String first = input.readLine();
          if (first == null) continue;
          String target = first.split(" ")[1], supplied = "", line;
          while ((line = input.readLine()) != null && !line.isEmpty())
            if (line.startsWith(VirtualScreenPeerProof.HEADER + ": "))
              supplied = line.substring(VirtualScreenPeerProof.HEADER.length() + 2);
          assertTrue(
              "Business request must carry the actual handshake proof",
              proof.authorizes("GET", target, supplied));
          requests.add(target);
          if (target.startsWith("/vscreen/create")) generation++;
          if (target.startsWith(loseReply) && !loseReply.isEmpty()) continue;
          byte[] body =
              new JSONObject()
                  .put("ok", true)
                  .put("generation", "rotated-" + generation)
                  .put("frameSeq", 0)
                  .toString()
                  .getBytes(StandardCharsets.UTF_8);
          socket
              .getOutputStream()
              .write(
                  ("HTTP/1.1 200 OK\r\nContent-Length: "
                          + body.length
                          + "\r\nConnection: close\r\n\r\n")
                      .getBytes(StandardCharsets.US_ASCII));
          socket.getOutputStream().write(body);
          socket.getOutputStream().flush();
        } catch (SocketTimeoutException idle) {
        } catch (Throwable error) {
          if (running.get()) peerFailure.compareAndSet(null, error);
        }
      }
    }

    JSONObject rotate() {
      return manager.rotateAndRelaunch(
          "landscape", PKG, endpoint.identity.instance, ORIGINAL, 7, visible::get);
    }

    List<String> routes() {
      return requests.stream()
          .map(target -> target.split("\\?", 2)[0])
          .filter(route -> route.equals("/vscreen/create") || route.equals("/vscreen/launch"))
          .toList();
    }

    @Override
    public void close() throws Exception {
      running.set(false);
      listener.close();
      peer.join(2500);
      ((ScheduledExecutorService) field(manager, "WORKER")).shutdownNow();
      assertNull("Authenticated peer failed", peerFailure.get());
    }
  }

  @Test
  public void confirmedRotationLaunchesObservedPackageExactlyOnce() throws Exception {
    try (var f = new Fixture()) {
      JSONObject result = f.rotate();
      assertTrue(result.toString(), result.optBoolean("ok"));
      assertEquals(List.of("/vscreen/create", "/vscreen/launch"), f.routes());
      assertTrue(f.requests.get(1).contains("package=" + PKG));
    }
  }

  @Test
  public void pageRetiredAfterSuccessfulCreateCannotLaunch() throws Exception {
    try (var f = new Fixture()) {
      f.onPresent = () -> f.visible.set(false);
      JSONObject result = f.rotate();
      assertEquals("SCREEN_TARGET_CHANGED", result.optString("error"));
      assertTrue(result.optBoolean("rotationCompleted"));
      assertTrue(result.optBoolean("relaunchNotSent"));
      assertEquals(List.of("/vscreen/create"), f.routes());
    }
  }

  @Test
  public void concurrentRevokeAfterSuccessfulCreateCannotLaunch() throws Exception {
    try (var f = new Fixture()) {
      AtomicReference<Throwable> failure = new AtomicReference<>();
      f.onPresent =
          () -> {
            Thread revoke = new Thread(f.manager::revoke, "rotation-concurrent-revoke");
            revoke.start();
            try {
              revoke.join(2000);
              assertFalse(revoke.isAlive());
            } catch (Throwable error) {
              failure.set(error);
            }
          };
      JSONObject result = f.rotate();
      assertNull(failure.get());
      assertEquals("VSCREEN_REVOKED", result.optString("error"));
      assertTrue(result.optBoolean("relaunchNotSent"));
      assertEquals(List.of("/vscreen/create"), f.routes());
    }
  }

  @Test
  public void pageRetiredDuringLaunchHandshakeSendsNoLaunchBusinessBytes() throws Exception {
    try (var f = new Fixture()) {
      f.beforeSecondProof = () -> f.visible.set(false);
      JSONObject result = f.rotate();
      assertFalse(result.optBoolean("ok"));
      assertFalse(result.optBoolean("executionUnknown"));
      assertEquals(List.of("/vscreen/create"), f.routes());
    }
  }

  @Test
  public void staleOrMissingObservedIdentityCannotRotateOrReuseAnOldPackage() throws Exception {
    try (var f = new Fixture()) {
      assertFalse(
          f.manager
              .rotateAndRelaunch("landscape", PKG, "foreign-core", ORIGINAL, 7, f.visible::get)
              .optBoolean("ok"));
      assertFalse(
          f.manager
              .rotateAndRelaunch(
                  "landscape", PKG, f.endpoint.identity.instance, "", 7, f.visible::get)
              .optBoolean("ok"));
      assertFalse(
          f.manager
              .rotateAndRelaunch(
                  "landscape", PKG, f.endpoint.identity.instance, ORIGINAL, 6, f.visible::get)
              .optBoolean("ok"));
      assertTrue(f.requests.isEmpty());
    }
  }

  @Test
  public void lostCreateReplyCannotLaunchOrReplayCreate() throws Exception {
    try (var f = new Fixture()) {
      f.loseReply = "/vscreen/create";
      assertFalse(f.rotate().optBoolean("ok"));
      assertEquals(List.of("/vscreen/create"), f.routes());
    }
  }

  @Test
  public void lostLaunchReplyDoesNotReplayTheLaunch() throws Exception {
    try (var f = new Fixture()) {
      f.loseReply = "/vscreen/launch";
      assertFalse(f.rotate().optBoolean("ok"));
      assertEquals(List.of("/vscreen/create", "/vscreen/launch"), f.routes());
    }
  }

  @Test
  public void queuedReplacementCannotInterleaveBetweenCreateAndLaunch() throws Exception {
    try (var f = new Fixture()) {
      AtomicInteger presented = new AtomicInteger();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      AtomicReference<JSONObject> replacement = new AtomicReference<>();
      Thread other =
          new Thread(
              () -> replacement.set(f.manager.start("portrait")), "rotation-queued-replacement");
      ReentrantLock queue = (ReentrantLock) field(f.manager, "ACTIONS");
      f.onPresent =
          () -> {
            if (presented.incrementAndGet() != 1) return;
            other.start();
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (!queue.hasQueuedThread(other) && System.nanoTime() < deadline) Thread.yield();
            if (!queue.hasQueuedThread(other))
              failure.set(new AssertionError("Replacement never queued"));
          };
      assertTrue(f.rotate().optBoolean("ok"));
      other.join(2500);
      assertFalse(other.isAlive());
      assertNull(failure.get());
      assertNotNull(replacement.get());
      assertTrue(replacement.get().optBoolean("ok"));
      assertEquals(List.of("/vscreen/create", "/vscreen/launch", "/vscreen/create"), f.routes());
    }
  }
}
