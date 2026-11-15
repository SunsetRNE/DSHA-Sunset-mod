package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.widget.TextView;
import com.deepseekharness.app.util.UiText;
import java.lang.reflect.Field;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 真实原生页的状态/正文呈现与暂停围栏；不启动 su、请求权限或使用设备。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class RootModulesFragmentTest {
  private RootModulesFragment page;
  private TextView status, entries;

  @Before
  public void setUp() throws Exception {
    UiText.setLanguage("en");
    page = new RootModulesFragment();
    status = new TextView(RuntimeEnvironment.getApplication());
    entries = new TextView(RuntimeEnvironment.getApplication());
    field("status").set(page, status);
    field("entries").set(page, entries);
  }

  @Test
  public void newPageDoesNotStartAReadWorker() throws Exception {
    assertNull(field("worker").get(page));
    assertEquals(0L, field("pageGeneration").getLong(page));
  }

  @Test
  public void onlyVerifiedCompleteEmptyInventoryCanSayNoModules() throws Exception {
    render(
        new JSONObject()
            .put("ok", true)
            .put("readOnly", true)
            .put("complete", true)
            .put("modules", new JSONArray()));
    assertEquals("Inventory complete", status.getText().toString());
    assertEquals("No modules", entries.getText().toString());
    render(
        new JSONObject()
            .put("ok", false)
            .put("readOnly", true)
            .put("complete", true)
            .put("code", "UNSUPPORTED_MANAGER_VERSION")
            .put("modules", new JSONArray()));
    assertNotEquals("Inventory complete", status.getText().toString());
    assertEquals("", entries.getText().toString());
  }

  @Test
  public void partialEmptyAndUnreadableRootNeverBecomeCompleteEmptySuccess() throws Exception {
    render(
        new JSONObject()
            .put("ok", true)
            .put("readOnly", true)
            .put("complete", false)
            .put("code", "LIST_PARTIAL")
            .put("modules", new JSONArray()));
    assertEquals("Inventory incomplete", status.getText().toString());
    assertEquals("", entries.getText().toString());
    render(
        new JSONObject()
            .put("ok", false)
            .put("readOnly", true)
            .put("complete", false)
            .put("code", "MODULE_ROOT_UNREADABLE"));
    assertEquals("Module directory is unreadable", status.getText().toString());
    assertEquals("", entries.getText().toString());
  }

  @Test
  public void unknownAndMalformedRepliesErasePreviousModuleBody() throws Exception {
    for (String reply :
        new String[] {
          "{\"ok\":false,\"readOnly\":true,\"code\":\"UNKNOWN\"}",
          "not-json",
          "{\"ok\":true,\"readOnly\":false,\"complete\":true,\"modules\":[]}"
        }) {
      entries.setText("private old module name");
      var method = RootModulesFragment.class.getDeclaredMethod("render", String.class);
      method.setAccessible(true);
      method.invoke(page, reply);
      assertEquals("", entries.getText().toString());
      assertEquals("Inventory result unknown or unavailable", status.getText().toString());
    }
  }

  @Test
  public void moduleFieldsRemainRawAndReadonlyStatesAreVisible() throws Exception {
    JSONObject module =
        new JSONObject()
            .put("id", "example")
            .put("name", "下一步")
            .put("version", "1.2")
            .put("disabled", true);
    render(
        new JSONObject()
            .put("ok", true)
            .put("readOnly", true)
            .put("complete", false)
            .put("modules", new JSONArray().put(module)));
    assertTrue(entries.getText().toString().contains("下一步"));
    assertFalse(entries.getText().toString().contains("Next"));
    assertTrue(entries.getText().toString().contains("Disabled"));
    assertEquals("Inventory incomplete", status.getText().toString());
  }

  @Test
  public void pauseFencesOldPageAndErasesSensitiveBodyWithoutStartingAnotherRead()
      throws Exception {
    entries.setText("private module inventory");
    field("pageGeneration").setLong(page, 7);
    page.onPause();
    assertEquals(8L, field("pageGeneration").getLong(page));
    assertEquals("", entries.getText().toString());
    assertEquals("Not read yet", status.getText().toString());
    assertNull(field("worker").get(page));
  }

  @Test
  public void rootDisabledReplyShowsTheEnableHintInsteadOfAnUnknownResult() throws Exception {
    render(
        new JSONObject()
            .put("ok", false)
            .put("readOnly", true)
            .put("complete", false)
            .put("code", "ROOT_DISABLED"));
    assertEquals("Enable Root shell first", status.getText().toString());
    assertEquals("", entries.getText().toString());
  }

  @Test
  public void lateCallbackFenceRejectsPausedOrAdvancedPage() {
    assertTrue("当前前台且代次一致", RootModulesFragment.acceptsResult(true, 7, 7));
    assertFalse("迟到回调不能覆盖新页面", RootModulesFragment.acceptsResult(true, 6, 7));
    assertFalse("暂停页面不能渲染旧结果", RootModulesFragment.acceptsResult(false, 7, 7));
  }

  @Test
  public void authorizationChangedCallbackDiscardsOldBodyBeforeRendering() throws Exception {
    entries.setText("private old module name");
    var method = RootModulesFragment.class.getDeclaredMethod("showAuthorizationChanged");
    method.setAccessible(true);
    method.invoke(page);
    assertEquals("Authorization changed. Check it again.", status.getText().toString());
    assertEquals("", entries.getText().toString());
  }

  private void render(JSONObject reply) throws Exception {
    var method = RootModulesFragment.class.getDeclaredMethod("render", String.class);
    method.setAccessible(true);
    method.invoke(page, reply.toString());
  }

  private static Field field(String name) throws Exception {
    Field result = RootModulesFragment.class.getDeclaredField(name);
    result.setAccessible(true);
    return result;
  }
}
