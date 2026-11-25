package com.deepseekharness.app;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.DeviceAuthorization;
import com.deepseekharness.app.util.DeviceShellPolicy;
import java.io.ByteArrayOutputStream;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Root 清单生产门禁：未允许 Root 时必须在 su/probe/模块目录之前拒绝。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class RootShellTest {
  @Test
  public void revokingThenRestoringRootWhileSuStartsLeavesHelperWithoutPayload() throws Exception {
    var authority = new DeviceAuthorization();
    var enabled = new java.util.concurrent.atomic.AtomicBoolean(true);
    java.util.function.Supplier<DeviceAuthorization.Values> grants =
        () -> new DeviceAuthorization.Values(enabled.get(), DeviceShellPolicy.Mode.STRICT, false);
    var beforeStart = authority.snapshot(grants);
    var sink = new ByteArrayOutputStream();
    byte[] payload = {1, 2, 3};
    assertTrue(authority.change(() -> {}, () -> enabled.compareAndSet(true, false)));
    assertFalse(authority.snapshot(grants).rootAllowed());
    assertTrue(authority.change(() -> {}, () -> enabled.compareAndSet(false, true)));
    assertFalse(
        RootShell.writeRequestIfCurrent(sink, payload, () -> authority.current(beforeStart)));
    assertEquals(0, sink.size());
    var next = authority.snapshot(grants);
    assertTrue(next.rootAllowed());
    assertTrue(RootShell.writeRequestIfCurrent(sink, payload, () -> authority.current(next)));
    assertArrayEquals(payload, sink.toByteArray());
  }

  @Test
  public void readRootModulesReturnsRootDisabledBeforeAnySuProbeOrModuleAccess() throws Exception {
    JSONObject reply =
        new JSONObject(RootShell.readRootModules(RuntimeEnvironment.getApplication()));
    assertFalse(reply.getBoolean("ok"));
    assertTrue(reply.getBoolean("readOnly"));
    assertEquals("ROOT_DISABLED", reply.getString("code"));
    assertFalse(reply.getBoolean("complete"));
    assertEquals(0, reply.getJSONArray("modules").length());
    assertFalse(reply.has("executionUid"));
    assertFalse(reply.has("manager"));
  }
}
