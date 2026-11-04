package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.module.ModuleCard;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 自绘视图按钮的样式 token：默认外观与开放 token 之前逐项一致；
 * token 只收颜色字符串与数字，严格限值；非法值回退默认而不是挂掉整张卡。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleViewRendererButtonTest {

  /** 与渲染器一致的卡面强调色：本测试的卡片固定 accent #7C9CFF。 */
  private static final int ACCENT = 0xFF7C9CFF;

  /** 默认填充：前景去 alpha 压上 8% 不透明度（0x14）。 */
  private static final int DIM_FILL = (ACCENT & 0x00FFFFFF) | 0x14000000;

  private Context context;
  private ModuleCard card;
  private LinearLayout parent;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    try {
      card =
          ModuleCard.parse(
              "{\"schema\":1,\"id\":\"pet.view\",\"name\":\"视图\",\"accent\":\"#7C9CFF\",\"actions\":[]}");
    } catch (Exception error) {
      throw new AssertionError(error);
    }
    parent = new LinearLayout(context);
  }

  private TextView renderButton(String nodeJson) {
    return renderButton(nodeJson, (type, value) -> {});
  }

  private TextView renderButton(String nodeJson, ModuleViewRenderer.ActionSink sink) {
    JSONArray nodes = new JSONArray();
    try {
      nodes.put(new JSONObject(nodeJson));
    } catch (Exception error) {
      throw new AssertionError(error);
    }
    ModuleViewRenderer.render(context, card, nodes, parent, sink);
    return (TextView) parent.getChildAt(parent.getChildCount() - 1);
  }

  private static GradientDrawable shape(TextView button) {
    return (GradientDrawable) button.getBackground();
  }

  private int dp(int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  private float sp(float value) {
    return value * context.getResources().getDisplayMetrics().scaledDensity;
  }

  // ------------------------------------------------------------ 旧观感（一个 token 都没写）

  @Test
  public void defaultButtonKeepsTheOldLook() {
    TextView button =
        renderButton("{\"type\":\"button\",\"label\":\"刷新\",\"action\":\"toast:hi\"}");
    assertEquals("刷新", button.getText().toString());
    assertEquals(sp(12), button.getTextSize(), 0.5f);
    assertEquals(dp(14), button.getPaddingLeft());
    assertEquals(dp(8), button.getPaddingTop());
    assertEquals(dp(14), button.getPaddingRight());
    assertEquals(dp(8), button.getPaddingBottom());
    // 胶囊形（半径 999dp）与强调色淡染填充。
    assertEquals(dp(999), Math.round(shape(button).getCornerRadius()));
    assertEquals(ACCENT, button.getCurrentTextColor());
    assertEquals(DIM_FILL, shape(button).getColor().getDefaultColor());
  }

  // ------------------------------------------------------------ token 范围

  @Test
  public void radiusTokenIsClampedAndIllegalFallsBackToCapsule() {
    assertEquals(
        dp(32),
        Math.round(
            shape(
                    renderButton(
                        "{\"type\":\"button\",\"label\":\"A\",\"action\":\"toast:x\",\"radius\":100}"))
                .getCornerRadius()));
    assertEquals(
        0f,
        shape(
                renderButton(
                    "{\"type\":\"button\",\"label\":\"A\",\"action\":\"toast:x\",\"radius\":0}"))
            .getCornerRadius(),
        0f);
    // 负数回退默认胶囊。
    assertEquals(
        dp(999),
        Math.round(
            shape(
                    renderButton(
                        "{\"type\":\"button\",\"label\":\"A\",\"action\":\"toast:x\",\"radius\":-5}"))
                .getCornerRadius()));
  }

  @Test
  public void paddingTokensAreClamped() {
    TextView padded =
        renderButton(
            "{\"type\":\"button\",\"label\":\"A\",\"action\":\"toast:x\",\"pad\":100,\"padY\":0}");
    assertEquals(dp(28), padded.getPaddingLeft());
    assertEquals(dp(2), padded.getPaddingTop());
    assertEquals(dp(28), padded.getPaddingRight());
    assertEquals(dp(2), padded.getPaddingBottom());
  }

  @Test
  public void textSizeTokenIsClamped() {
    TextView huge =
        renderButton("{\"type\":\"button\",\"label\":\"A\",\"action\":\"toast:x\",\"size\":50}");
    assertEquals(sp(20), huge.getTextSize(), 0.5f);
    TextView small =
        renderButton("{\"type\":\"button\",\"label\":\"A\",\"action\":\"toast:x\",\"size\":5}");
    assertEquals(sp(9), small.getTextSize(), 0.5f);
  }

  @Test
  public void colorTokensParseAndInvalidOnesFallBack() {
    TextView colored =
        renderButton(
            "{\"type\":\"button\",\"label\":\"A\",\"action\":\"toast:x\",\"fg\":\"#FF0000\",\"bg\":\"#00FF00\"}");
    assertEquals(0xFFFF0000, colored.getCurrentTextColor());
    assertEquals(0xFF00FF00, shape(colored).getColor().getDefaultColor());
    // 非法颜色串回退默认，不抛异常。
    TextView invalid =
        renderButton(
            "{\"type\":\"button\",\"label\":\"A\",\"action\":\"toast:x\",\"fg\":\"#ZZZZZZ\",\"bg\":\"not-a-color\"}");
    assertEquals(ACCENT, invalid.getCurrentTextColor());
    assertEquals(DIM_FILL, shape(invalid).getColor().getDefaultColor());
  }

  // ------------------------------------------------------------ 动作语义

  @Test
  public void clickDispatchesActionToHostSink() {
    AtomicReference<String> fired = new AtomicReference<>();
    TextView button =
        renderButton(
            "{\"type\":\"button\",\"label\":\"跑\",\"action\":\"toast:你好\"}",
            (type, value) -> fired.set(type + ":" + value));
    button.performClick();
    assertEquals("toast:你好", fired.get());
  }

  @Test
  public void buttonWithoutActionDoesNothingOnClick() {
    AtomicReference<String> fired = new AtomicReference<>();
    TextView button = renderButton("{\"type\":\"button\",\"label\":\"哑\"}", (t, v) -> fired.set(t));
    button.performClick();
    assertEquals(null, fired.get());
  }
}
