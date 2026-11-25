package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;

import android.content.Context;
import com.deepseekharness.app.device.DeviceTransportIdentity;
import com.deepseekharness.app.device.DeviceTransportResult;
import com.deepseekharness.app.util.VirtualScreenHandshake;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 实体屏特权管理器：受管启动、一次性凭据、UNKNOWN 不重放与 benchmark 统计。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class PhysicalScreenManagerTest {
  private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String INSTANCE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
  private static final String GENERATION = "gen-1";

  private static final com.deepseekharness.app.control.ControlTaskAuthority.Lease LEASE =
      new com.deepseekharness.app.control.ControlTaskAuthority.Lease(
          new com.deepseekharness.app.control.ControlTaskAuthority.Binding(
              com.deepseekharness.app.control.ControlTaskAuthority.Owner.LOCAL_DSH,
              "run",
              "session",
              "1"),
          1);
  private static final PhysicalScreenManager.Access ACCESS =
      new PhysicalScreenManager.Access(
          LEASE, () -> true, pkg -> "com.game".equals(pkg) ? null : "SCREEN_CONFIRMATION_REQUIRED");

  private static final class FakeChannel implements VirtualScreenChannel {
    int starts;
    String command = "";

    public String id() {
      return "root";
    }

    public boolean available(Context context) {
      return true;
    }

    public String start(Context context, String command) {
      return startTyped(context, command).output;
    }

    public DeviceTransportResult startTyped(Context context, String command) {
      starts++;
      this.command = command;
      String output =
          "DSHA_VSCREEN_STARTED 1 4433 "
              + TOKEN
              + " 123:456:2000:11111111-2222-3333-4444-555555555555:"
              + INSTANCE
              + "\n[EXIT=0]";
      return DeviceTransportResult.fromLegacy("root", output, DeviceTransportIdentity.unknown());
    }
  }

  private static final class FakeTransport implements PhysicalScreenManager.Transport {
    final List<String> routes = new ArrayList<>();
    boolean failRead;
    String infoPackage = "com.game";
    String pixels = "fixture-game-pixels";

    public JSONObject request(VirtualScreenHandshake endpoint, String route, String query) {
      routes.add(route);
      if (failRead) return failure("VSCREEN_TRANSPORT_UNAVAILABLE", false);
      try {
        return new JSONObject()
            .put("ok", true)
            .put("coreInstance", INSTANCE)
            .put("generation", GENERATION)
            .put("package", infoPackage)
            .put("packageVerified", true)
            .put("width", 100)
            .put("height", 200)
            .put("previewB64", pixels)
            .put("observationId", "fixture-credential")
            .put("observation", new JSONObject().put("extra", pixels))
            .put("frameConfig", new JSONObject().put("maxWidth", 1280).put("extra", pixels));
      } catch (org.json.JSONException invalid) {
        throw new AssertionError(invalid);
      }
    }

    private static JSONObject failure(String code, boolean unknown) {
      try {
        return new JSONObject()
            .put("ok", false)
            .put("error", code)
            .put("executionUnknown", unknown);
      } catch (Exception impossible) {
        throw new IllegalStateException(impossible);
      }
    }
  }

  private PhysicalScreenManager manager(FakeChannel channel, FakeTransport transport) {
    return new PhysicalScreenManager(
        RuntimeEnvironment.getApplication(), context -> channel, transport);
  }

  @Test
  public void startUsesTheManagedLauncherAndBindsTheCoreInstance() {
    FakeChannel channel = new FakeChannel();
    FakeTransport transport = new FakeTransport();
    PhysicalScreenManager manager = manager(channel, transport);
    JSONObject info = manager.info(ACCESS);
    assertTrue(info.optBoolean("ok"));
    assertEquals(1, channel.starts);
    assertTrue(channel.command.contains("--launch --physical --port 0 --package"));
    assertEquals("root", info.optString("channel"));
    assertEquals(INSTANCE, info.optString("coreInstance"));
    assertEquals(GENERATION, info.optString("generation"));
    assertFalse(info.optBoolean("accessibilityRequired", true));
    // 启动探针 + 真正的 info 各一次；没有重放启动。
    assertEquals(List.of("/priv/info", "/priv/info"), transport.routes);
  }

  @Test
  public void compositeAndActionReceiptsAreRejectedBeforeReadingOrDispatching() throws Exception {
    var channel = new FakeChannel();
    var transport = new FakeTransport();
    var manager = manager(channel, transport);
    var task = visibleTask("com.game", 0);
    for (boolean overlay : new boolean[] {false, true}) {
      // A non-task overlay changes the composited image without adding an activity task.
      transport.pixels = overlay ? "fixture-overlay-pixels" : "fixture-game-pixels";
      assertEquals("com.game", PhysicalScreenCore.singleVisiblePackage(List.of(task)));
      for (String verb : List.of("frame", "tap", "swipe", "key", "benchmark")) {
        assertTrue(
            com.deepseekharness.app.util.PrivilegedScreenRoutes.needsMirrorObservation(verb));
        var reply = manager.windowSourceRequired(ACCESS);
        assertEquals("NOT_SENT", reply.getString("state"));
        assertEquals("PRIV_AUTHORIZED_WINDOW_SOURCE_REQUIRED", reply.getString("error"));
        assertFalse(reply.has("previewB64"));
        assertFalse(reply.has("observationId"));
        if (!verb.equals("benchmark")) {
          var core =
              PhysicalScreenCore.route(
                  "/priv/" + verb, java.util.Map.of("expectedPackage", "com.game"));
          assertEquals("NOT_SENT", core.getString("state"));
          assertFalse(core.has("previewB64"));
          assertFalse(core.has("observation"));
        }
      }
    }
    assertEquals(0, channel.starts);
    assertTrue(transport.routes.isEmpty());
  }

  @Test
  public void disconnectedWindowPipelineIdentifiesItsRequiredAccessibilitySetting()
      throws Exception {
    assertEquals(
        "screenshot",
        com.deepseekharness.app.util.PrivilegedScreenRoutes.windowOperation(
            "frame", "", false, 34));
    var reply = com.deepseekharness.app.screen.ScreenReply.unavailable();
    assertEquals("NOT_SENT", reply.getString("state"));
    assertTrue(reply.getBoolean("accessibilityRequired"));
    assertEquals("OPEN_ACCESSIBILITY_SETTINGS", reply.getString("nextAction"));
    assertEquals(
        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS, reply.getString("settingsAction"));
    assertFalse(reply.has("data"));
  }

  @Test
  public void metadataRepliesFromOlderCoresCannotAttachImagesOrNestedObservations()
      throws Exception {
    var channel = new FakeChannel();
    var transport = new FakeTransport();
    var manager = manager(channel, transport);
    for (boolean overlay : new boolean[] {false, true}) {
      transport.pixels = overlay ? "fixture-overlay-pixels" : "fixture-game-pixels";
      var info = manager.info(ACCESS);
      assertTrue(info.getBoolean("ok"));
      assertTrue(manager.resultCurrent(ACCESS, "com.game", GENERATION));
      assertFalse(info.toString().contains(transport.pixels));
      assertFalse(info.has("previewB64"));
      assertFalse(info.has("observation"));
      assertFalse(info.has("observationId"));
      assertEquals(1280, info.getJSONObject("frameConfig").getInt("maxWidth"));
      var config = manager.configure(ACCESS, 640, 60, 6);
      assertTrue(config.getBoolean("ok"));
      assertFalse(config.toString().contains(transport.pixels));
    }
    transport.infoPackage = "com.other";
    assertFalse(manager.resultCurrent(ACCESS, "com.game", GENERATION));
    assertTrue(manager.close(ACCESS).getBoolean("ok"));
    assertFalse(manager.running());
  }

  private static android.app.ActivityManager.RunningTaskInfo visibleTask(String pkg, int display) {
    var task = new android.app.ActivityManager.RunningTaskInfo();
    task.topActivity = new android.content.ComponentName(pkg, pkg + ".Main");
    org.robolectric.util.ReflectionHelpers.setField(task, "displayId", display);
    org.robolectric.util.ReflectionHelpers.setField(task, "isVisible", true);
    return task;
  }

  @Test
  public void fullDisplayFrameRequiresOneVisibleApplicationOwner() {
    var first = visibleTask("com.first", 0);
    var second = visibleTask("com.second", 0);
    assertEquals(
        "com.first",
        PhysicalScreenCore.singleVisiblePackage(List.of(first, visibleTask("com.other", 1))));
    assertEquals("", PhysicalScreenCore.singleVisiblePackage(List.of(first, second)));
    assertEquals("", PhysicalScreenCore.singleVisiblePackage(List.of()));
  }

  @Test
  public void saturatedTaskQueryCannotProveExclusiveOwnership() {
    var tasks = new java.util.ArrayList<android.app.ActivityManager.RunningTaskInfo>();
    tasks.add(visibleTask("com.first", 0));
    var background = visibleTask("com.background", 0);
    org.robolectric.util.ReflectionHelpers.setField(background, "isVisible", false);
    for (int index = 1; index < 99; index++) tasks.add(background);
    assertEquals("com.first", PhysicalScreenCore.singleVisiblePackage(tasks));
    tasks.add(background);
    assertEquals("", PhysicalScreenCore.singleVisiblePackage(tasks));
    assertEquals("", PhysicalScreenCore.singleVisiblePackage(null));
  }

  @Test
  public void unavailableMetadataTransportDropsTheEndpointWithoutReplay() {
    var channel = new FakeChannel();
    var transport = new FakeTransport();
    var manager = manager(channel, transport);
    assertTrue(manager.info(ACCESS).optBoolean("ok"));
    transport.failRead = true;
    assertEquals("PRIV_CORE_UNREACHABLE", manager.info(ACCESS).optString("error"));
    transport.failRead = false;
    assertTrue(manager.info(ACCESS).optBoolean("ok"));
    assertEquals(2, channel.starts);
  }
}
