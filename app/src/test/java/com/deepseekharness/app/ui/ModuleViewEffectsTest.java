package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.module.ModuleCard;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 模块自绘动效组件：设计稿里的每个进度条变种都必须由模块 JSON 触发，
 * 未知变种与非法数值只能回退，不能拖垮整张卡。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleViewEffectsTest {

  private Context context;
  private ModuleCard card;
  private LinearLayout parent;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    card =
        ModuleCard.parse(
            "{\"schema\":1,\"id\":\"fx.demo\",\"name\":\"动效\",\"accent\":\"#7C9CFF\",\"actions\":[]}");
    parent = new LinearLayout(context);
  }

  @Test
  public void variantNamesAreCaseInsensitiveAndUnknownFallsBack() {
    assertEquals("waveCurve", ModuleProgressView.normalizeVariant(" WAVECURVE "));
    assertEquals("line", ModuleProgressView.normalizeVariant("sparkles"));
    assertEquals("line", ModuleProgressView.normalizeVariant(null));
  }

  @Test
  public void valuesAcceptBothUnitAndPercentAndClamp() {
    assertEquals(0.65f, ModuleProgressView.normalizeValue(65), 0.0001f);
    assertEquals(0.2f, ModuleProgressView.normalizeValue(0.2), 0.0001f);
    assertEquals(0f, ModuleProgressView.normalizeValue(-5), 0.0001f);
    assertEquals(1f, ModuleProgressView.normalizeValue(150), 0.0001f);
    assertEquals(0f, ModuleProgressView.normalizeValue(Double.NaN), 0.0001f);
  }

  @Test
  public void everyDesignVariantRendersAsProgressView() throws Exception {
    for (String variant : ModuleProgressView.VARIANTS) {
      JSONObject node = new JSONObject();
      node.put("type", "progress");
      node.put("variant", variant);
      node.put("value", 65);
      node.put("indeterminate", variant.equals("roaming") || variant.equals("breath"));
      node.put("height", 4);
      node.put("showValue", variant.equals("numeric"));
      render(node);
      ModuleProgressView view = (ModuleProgressView) parent.getChildAt(parent.getChildCount() - 1);
      assertEquals(variant, view.variant());
      assertTrue(view.isIndeterminate() == (variant.equals("roaming") || variant.equals("breath")));
      view.measure(
          android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.EXACTLY),
          android.view.View.MeasureSpec.makeMeasureSpec(
              0, android.view.View.MeasureSpec.UNSPECIFIED));
      view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }
  }

  @Test
  public void unknownVariantAndBadNumbersFallBackSafely() throws Exception {
    JSONObject node = new JSONObject();
    node.put("type", "progress");
    node.put("variant", "does-not-exist");
    node.put("value", 999);
    node.put("height", 999);
    node.put("duration", -1);
    node.put("segments", 999);
    node.put("track", "not-a-color");
    render(node);
    ModuleProgressView view = (ModuleProgressView) parent.getChildAt(parent.getChildCount() - 1);
    assertEquals("line", view.variant());
    assertEquals(1f, view.value(), 0.0001f);
  }

  @Test
  public void segmentedTabsAreBoundedAndClickable() throws Exception {
    JSONArray options = new JSONArray();
    for (int i = 0; i < 8; i++)
      options.put(new JSONObject().put("label", "T" + i).put("selected", i == 1));
    JSONObject node = new JSONObject();
    node.put("type", "segmented");
    node.put("options", options);
    node.put("selected", "#35C759");
    render(node);
    LinearLayout bar = (LinearLayout) parent.getChildAt(parent.getChildCount() - 1);
    assertEquals(6, bar.getChildCount());
    assertEquals("T1", ((TextView) bar.getChildAt(1)).getText().toString());
  }

  @Test
  public void cardBadgeAndLinkComposeWithoutValidationError() throws Exception {
    JSONArray children = new JSONArray();
    children.put(new JSONObject().put("type", "badge").put("text", "12"));
    children.put(new JSONObject().put("type", "link").put("text", "查看全部"));
    JSONObject cardNode = new JSONObject();
    cardNode.put("type", "card");
    cardNode.put("title", "插件");
    cardNode.put("children", children);
    render(cardNode);
    LinearLayout box = (LinearLayout) parent.getChildAt(parent.getChildCount() - 1);
    assertTrue(box.getChildCount() >= 3);
    assertNotNull(box.getBackground());
    assertFalse(box.getChildAt(0) instanceof ModuleProgressView);
  }

  private void render(JSONObject node) {
    JSONArray nodes = new JSONArray();
    nodes.put(node);
    ModuleViewRenderer.render(context, card, nodes, parent, (type, value) -> {});
  }
}
