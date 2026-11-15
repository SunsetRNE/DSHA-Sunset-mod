package com.deepseekharness.app;

import static org.junit.Assert.*;

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
