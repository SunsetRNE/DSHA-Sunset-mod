package com.deepseekharness.app.screen;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.util.SparseArray;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.deepseekharness.app.DshaAccessibilityService;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityNodeInfo;
import org.robolectric.shadows.ShadowAccessibilityService;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;

/** 虚拟屏窗口证据与实际无障碍窗口对象的接线；不改变唯一窗口选择规则。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = android.app.Application.class,
    shadows = ScreenTargetResolverEvidenceTest.FreshWindows.class)
public final class ScreenTargetResolverEvidenceTest {
  private static final int DISPLAY = 8;

  @Implements(AccessibilityService.class)
  public static class FreshWindows extends ShadowAccessibilityService {
    static SparseArray<List<AccessibilityWindowInfo>> inventory = new SparseArray<>();

    static Integer freshPhysical;

    @Implementation
    protected SparseArray<List<AccessibilityWindowInfo>> getWindowsOnAllDisplays() {
      if (freshPhysical != null) physicalWindows(freshPhysical);
      return inventory;
    }
  }

  @org.junit.After
  public void clearWindowSource() {
    FreshWindows.freshPhysical = null;
  }

  private static AccessibilityWindowInfo window(
      int display,
      int id,
      int type,
      int layer,
      boolean focused,
      boolean active,
      String pkg,
      String title) {
    var window = AccessibilityWindowInfo.obtain();
    ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
    shadow.setDisplayId(display);
    shadow.setId(id);
    shadow.setType(type);
    shadow.setLayer(layer);
    shadow.setFocused(focused);
    shadow.setActive(active);
    Rect bounds = display == 0 ? new Rect(2, 1, 10, 7) : new Rect(0, 0, 1008, 1792);
    shadow.setBoundsInScreen(bounds);
    shadow.setTitle(title);
    var root = AccessibilityNodeInfo.obtain(new View(RuntimeEnvironment.getApplication()));
    root.setPackageName(pkg);
    root.setBoundsInScreen(bounds);
    if (display == 0) root.setText(pkg + ".fixture");
    root.setVisibleToUser(true);
    Shadow.<ShadowAccessibilityNodeInfo>extract(root).setAccessibilityWindowInfo(window);
    shadow.setRoot(root);
    return window;
  }

  private static final class CaptureService extends DshaAccessibilityService {
    int capturedWindow = -1, capturedDisplay = -1;
    boolean successfulWindow;

    CaptureService() {
      attachBaseContext(RuntimeEnvironment.getApplication());
    }

    @Override
    public void takeScreenshotOfWindow(
        int id, java.util.concurrent.Executor executor, TakeScreenshotCallback callback) {
      capturedWindow = id;
      if (successfulWindow) {
        var buffer =
            android.hardware.HardwareBuffer.create(
                8,
                6,
                android.hardware.HardwareBuffer.RGBA_8888,
                1,
                android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        var result =
            org.robolectric.util.ReflectionHelpers.callConstructor(
                AccessibilityService.ScreenshotResult.class,
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(
                    android.hardware.HardwareBuffer.class, buffer),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(
                    android.graphics.ColorSpace.class,
                    android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(long.class, 1L));
        executor.execute(() -> callback.onSuccess(result));
      } else executor.execute(() -> callback.onFailure(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR));
    }

    @Override
    public void takeScreenshot(
        int id, java.util.concurrent.Executor executor, TakeScreenshotCallback callback) {
      capturedDisplay = id;
      executor.execute(() -> callback.onFailure(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR));
    }
  }

  private static ScreenRequestContext authorized(CaptureService service) {
    var connection =
        new com.deepseekharness.app.util.ScreenConnectionEpoch<DshaAccessibilityService>();
    connection.connect(service);
    var broker = new com.deepseekharness.app.capabilities.CapabilityBroker(() -> 0L);
    var request =
        com.deepseekharness.app.capabilities.CapabilityRequest.create(
            "screen",
            "screenshot",
            new com.deepseekharness.app.core.RuntimeSnapshot.Epoch("install", 1, "run"),
            "install",
            "bridge",
            new com.deepseekharness.app.capabilities.ScreenTargetEvidence(
                0, 20, "com.example.target"),
            0,
            broker.epoch("screen"),
            10_000);
    assertTrue(broker.authorize(request));
    return new ScreenRequestContext(broker, request, () -> true, connection);
  }

  private static void physicalWindows(int secondaryType) {
    var windows = new java.util.ArrayList<AccessibilityWindowInfo>();
    windows.add(
        window(
            0,
            20,
            AccessibilityWindowInfo.TYPE_APPLICATION,
            2,
            true,
            true,
            "com.example.target",
            "Synthetic target"));
    if (secondaryType != 0)
      windows.add(
          window(
              0,
              30,
              secondaryType,
              1,
              false,
              false,
              "com.example.other",
              "Synthetic second window"));
    FreshWindows.inventory = new SparseArray<>();
    FreshWindows.inventory.put(0, windows);
  }

  @Implements(android.graphics.Bitmap.class)
  public static class SyntheticWindowBitmap extends org.robolectric.shadows.ShadowNativeBitmap {
    @Implementation(minSdk = 34)
    protected static android.graphics.Bitmap wrapHardwareBuffer(
        android.hardware.HardwareBuffer buffer, android.graphics.ColorSpace colorSpace) {
      var window =
          android.graphics.Bitmap.createBitmap(8, 6, android.graphics.Bitmap.Config.ARGB_8888);
      window.eraseColor(android.graphics.Color.GREEN);
      return window;
    }
  }

  @Test
  @Config(
      sdk = 34,
      shadows = {FreshWindows.class, SyntheticWindowBitmap.class})
  @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
  public void authorizedWindowCallbackPublishesPngAndFreshCoordinateObservation() throws Exception {
    CaptureService service = new CaptureService();
    service.successfulWindow = true;
    FreshWindows.freshPhysical = AccessibilityWindowInfo.TYPE_APPLICATION;
    var ctx = authorized(service);
    var method =
        MainScreenActions.class.getDeclaredMethod(
            "screenshot", ScreenRequestContext.class, String.class, int.class);
    method.setAccessible(true);
    var actions = new MainScreenActions(new com.deepseekharness.app.util.ScreenConnectionEpoch<>());
    var result = (org.json.JSONObject) method.invoke(actions, ctx, "mcp", 1024 * 1024);
    assertTrue(result.getBoolean("ok"));
    assertTrue(result.getBoolean("coordinatesAvailable"));
    assertFalse(result.getString("observationId").isEmpty());
    assertEquals(20, service.capturedWindow);
    assertEquals(-1, service.capturedDisplay);
    byte[] png = android.util.Base64.decode(result.getString("data"), android.util.Base64.DEFAULT);
    var bitmap = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
    try {
      assertEquals(android.graphics.Color.BLACK, bitmap.getPixel(0, 0));
      assertEquals(android.graphics.Color.GREEN, bitmap.getPixel(2, 1));
      assertEquals(android.graphics.Color.GREEN, bitmap.getPixel(9, 6));
      assertEquals(android.graphics.Color.BLACK, bitmap.getPixel(10, 7));
    } finally {
      bitmap.recycle();
    }
  }

  @Test
  public void android14CaptureUsesOnlyTheAuthorizedWindowEvenWhenDisplayHasTwoApps() {
    CaptureService service = new CaptureService();
    var context = authorized(service);
    physicalWindows(AccessibilityWindowInfo.TYPE_APPLICATION);
    var failure =
        assertThrows(
            ScreenScreenshotCapture.CaptureFailure.class,
            () -> new ScreenScreenshotCapture().capture(context));
    assertEquals(
        "SCREENSHOT_SYSTEM_" + AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR,
        failure.getMessage());
    assertEquals(20, service.capturedWindow);
    assertEquals(-1, service.capturedDisplay);
  }

  @Test
  @Config(sdk = {30, 31, 32, 33})
  public void olderAndroidUsesAuthorizedSemanticWindowAndNeverReadsCompositePixels()
      throws Exception {
    CaptureService service = new CaptureService();
    var context = authorized(service);
    for (int secondary :
        new int[] {
          0,
          AccessibilityWindowInfo.TYPE_APPLICATION,
          AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
          AccessibilityWindowInfo.TYPE_SYSTEM
        }) {
      FreshWindows.freshPhysical = secondary;
      assertEquals(
          "SCREEN_WINDOW_CAPTURE_UNSUPPORTED",
          assertThrows(
                  IllegalStateException.class, () -> new ScreenScreenshotCapture().capture(context))
              .getMessage());
      assertEquals(-1, service.capturedDisplay);
      var leases =
          new com.deepseekharness.app.util.ScreenNodeLeaseStore<ScreenSemanticNode>(
              () -> 0L, ScreenSemanticNode::close);
      try {
        var tree = new ScreenSnapshotReader().read(context, leases);
        assertTrue(tree.optBoolean("ok"));
        assertEquals("com.example.target", tree.getJSONObject("target").getString("packageName"));
        assertEquals(20, tree.getJSONObject("target").getInt("windowId"));
        assertFalse(tree.getString("treeId").isEmpty());
        assertTrue(tree.getString("text").contains("com.example.target.fixture"));
        assertFalse(tree.getString("text").contains("com.example.other.fixture"));
        assertFalse(tree.has("data"));
      } finally {
        leases.clear();
      }
    }
  }

  @Test
  public void readableVirtualDisplayCarriesTargetWindowEvidence() {
    var service = new DshaAccessibilityService();
    FreshWindows.inventory = new SparseArray<>();
    FreshWindows.inventory.put(
        DISPLAY,
        List.of(
            window(
                DISPLAY,
                20,
                AccessibilityWindowInfo.TYPE_APPLICATION,
                1,
                true,
                true,
                "com.example.mail",
                "Inbox"),
            window(
                DISPLAY,
                21,
                AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                2,
                false,
                false,
                "",
                "Keyboard")));
    try (var result = ScreenTargetResolver.resolve(service, DISPLAY)) {
      assertEquals("READABLE", result.diagnostic);
      assertNotNull(result.root);
      assertNotNull(result.evidence);
      assertEquals(0, result.evidence.selectedIndex);
      assertEquals("com.example.mail", result.evidence.targetPackage);
      var fields = result.evidence.fields();
      assertEquals(Integer.valueOf(20), fields.get("targetWindowId"));
      assertFalse(fields.containsKey("targetWindowTitle"));
      assertFalse(fields.containsValue("Inbox"));
      assertEquals("com.example.mail", fields.get("targetWindowPackage"));
      assertEquals(Integer.valueOf(1), fields.get("applicationWindowCount"));
      assertEquals(Integer.valueOf(1), fields.get("inputMethodWindowCount"));
      assertEquals(Boolean.FALSE, fields.get("windowAmbiguous"));
    }
  }

  @Test
  public void ambiguousApplicationWindowsExposeNoTargetWindow() {
    var service = new DshaAccessibilityService();
    FreshWindows.inventory = new SparseArray<>();
    FreshWindows.inventory.put(
        DISPLAY,
        List.of(
            window(
                DISPLAY,
                20,
                AccessibilityWindowInfo.TYPE_APPLICATION,
                1,
                true,
                true,
                "com.example.a",
                "A"),
            window(
                DISPLAY,
                21,
                AccessibilityWindowInfo.TYPE_APPLICATION,
                1,
                true,
                true,
                "com.example.b",
                "B")));
    try (var result = ScreenTargetResolver.resolve(service, DISPLAY)) {
      assertEquals("NO_UNIQUE_APPLICATION_WINDOW", result.diagnostic);
      assertNull(result.root);
      assertNotNull(result.evidence);
      assertTrue(result.evidence.ambiguous);
      assertEquals(-1, result.evidence.selectedIndex);
      assertEquals(2, result.evidence.applicationWindows);
      assertNull(result.evidence.target);
      var fields = result.evidence.fields();
      assertEquals(Boolean.TRUE, fields.get("windowAmbiguous"));
      assertFalse(fields.containsKey("targetWindowId"));
      assertFalse(fields.containsKey("targetWindowPackage"));
    }
  }

  @Test
  public void emptyVirtualDisplayHasZeroCounts() {
    var service = new DshaAccessibilityService();
    FreshWindows.inventory = new SparseArray<>();
    FreshWindows.inventory.put(DISPLAY, List.of());
    try (var result = ScreenTargetResolver.resolve(service, DISPLAY)) {
      assertEquals("NO_WINDOWS", result.diagnostic);
      assertNull(result.root);
      assertNotNull(result.evidence);
      assertEquals(0, result.evidence.applicationWindows);
      assertNull(result.evidence.target);
    }
  }
}
