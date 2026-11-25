package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.ContentResolver;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;

/** 状态文本淡入与数值过渡：正常时按 150/200ms 生效，系统关闭动画时直接落到静态值。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = UiMotionTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class UiMotionTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private ContentResolver resolver;

  @Before
  public void setUp() {
    resolver = RuntimeEnvironment.getApplication().getContentResolver();
    animationScales(1f);
  }

  @After
  public void tearDown() {
    animationScales(1f);
  }

  private void animationScales(float value) {
    Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, value);
    Settings.Global.putFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, value);
  }

  private static TextView view(String text) {
    TextView view = new TextView(RuntimeEnvironment.getApplication());
    view.setText(text);
    return view;
  }

  @Test
  public void crossFadeTextLeavesEqualTextUntouched() {
    TextView view = view("DSH 已就绪");
    UiMotion.crossFadeText(view, "DSH 已就绪");
    assertEquals("DSH 已就绪", view.getText().toString());
    assertEquals(1f, view.getAlpha(), 0.001f);
  }

  @Test
  public void crossFadeTextFallsBackToStaticWhenAnimationsDisabled() {
    animationScales(0f);
    TextView view = view("启动中");
    UiMotion.crossFadeText(view, "已就绪");
    assertEquals("已就绪", view.getText().toString());
    assertEquals(1f, view.getAlpha(), 0.001f);
  }

  @Test
  public void crossFadeTextWritesNewValueImmediatelyAndFadesIn() {
    TextView view = view("启动中");
    UiMotion.crossFadeText(view, "已就绪");
    // 先写值再淡入：刷新很快时也不会停在旧文字上。
    assertEquals("已就绪", view.getText().toString());
    assertEquals(0f, view.getAlpha(), 0.001f);
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    assertEquals(1f, view.getAlpha(), 0.001f);
  }

  @Test
  public void crossFadeTextToleratesNullViewAndText() {
    UiMotion.crossFadeText(null, "任意文字");
    TextView view = view("旧值");
    UiMotion.crossFadeText(view, null);
    assertEquals("", view.getText().toString());
  }

  @Test
  public void animateCounterFallsBackToStaticWhenAnimationsDisabled() {
    animationScales(0f);
    TextView view = view("2");
    UiMotion.animateCounter(view, 10);
    assertEquals("10", view.getText().toString());
  }

  @Test
  public void animateCounterSkipsEqualValue() {
    TextView view = view("10");
    animationScales(0f);
    UiMotion.animateCounter(view, 10);
    assertEquals("10", view.getText().toString());
  }

  @Test
  public void animateCounterWritesValueDirectlyWhenCurrentTextIsNotANumber() {
    TextView view = view("—");
    UiMotion.animateCounter(view, 7);
    assertEquals("7", view.getText().toString());
  }

  @Test
  public void animateCounterStepsThroughValuesAndEndsOnTarget() {
    TextView view = view("0");
    UiMotion.animateCounter(view, 10);
    assertEquals("0", view.getText().toString());
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    assertEquals("10", view.getText().toString());
  }

  @Test
  public void animateCounterSupportsDecimals() {
    TextView view = view("1.0");
    UiMotion.animateCounter(view, 2.5f);
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    assertEquals("2.5", view.getText().toString());
  }

  @Test
  public void timerTextWritesDigitsImmediatelyWithoutFading() {
    TextView view = view("仅检查 · 总耗时 3 秒 · 当前阶段 1 秒");
    UiMotion.crossFadeTimerText(view, "仅检查 · 总耗时 4 秒 · 当前阶段 2 秒");
    // 只有数字变化：不淡入，避免每秒刷新变成呼吸闪烁。
    assertEquals("仅检查 · 总耗时 4 秒 · 当前阶段 2 秒", view.getText().toString());
    assertEquals(1f, view.getAlpha(), 0.001f);
  }

  @Test
  public void timerTextFadesWhenWordingChanges() {
    TextView view = view("仅检查 · 总耗时 3 秒");
    UiMotion.crossFadeTimerText(view, "检查与按需修复 · 总耗时 3 秒");
    assertEquals("检查与按需修复 · 总耗时 3 秒", view.getText().toString());
    assertEquals(0f, view.getAlpha(), 0.001f);
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    assertEquals(1f, view.getAlpha(), 0.001f);
  }

  @Test
  public void timerTextAvoidsRepeatedWritesAndDoesNotFadeAtDigitBoundary() {
    CountingTextView view = new CountingTextView();
    view.setText("已用 9 秒");
    int before = view.writes;
    for (int index = 0; index < 30; index++) UiMotion.crossFadeTimerText(view, "已用 9 秒");
    UiMotion.crossFadeTimerText(view, "已用 10 秒");
    assertEquals("已用 10 秒", view.getText().toString());
    assertEquals("text writes across 31 refreshes", 1, view.writes - before);
    assertEquals(1f, view.getAlpha(), 0.001f);
  }

  @Test
  public void smallCounterOnlyWritesWhenTheRenderedValueChanges() {
    CountingTextView view = new CountingTextView();
    view.setText("0");
    int before = view.writes;
    UiMotion.animateCounter(view, 1);
    ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(300));
    assertEquals("1", view.getText().toString());
    assertEquals("text writes across a 0→1 animation", 1, view.writes - before);
  }

  @Test
  public void disabledMotionOnReleaseCancelsAnAlreadyShrunkButton() {
    TextView view = view("运行");
    view.setClickable(true);
    UiMotion.pressScale(view);
    sendTouch(view, MotionEvent.ACTION_DOWN);
    ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(120));
    assertEquals(0.985f, view.getScaleX(), 0.001f);
    // The transition scale can change while a button is held.
    Settings.Global.putFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 0f);
    sendTouch(view, MotionEvent.ACTION_CANCEL);
    assertEquals(1f, view.getScaleX(), 0.001f);
    assertEquals(1f, view.getScaleY(), 0.001f);
  }

  private static void sendTouch(TextView view, int action) {
    MotionEvent event = MotionEvent.obtain(0L, 0L, action, 5f, 5f, 0);
    try {
      view.dispatchTouchEvent(event);
    } finally {
      event.recycle();
    }
  }

  private static final class CountingTextView extends TextView {
    int writes;

    CountingTextView() {
      super(RuntimeEnvironment.getApplication());
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
      super.setText(text, type);
      writes++;
    }
  }

  @Test
  public void revealFallsBackToFullyVisibleWhenAnimationsDisabled() {
    animationScales(0f);
    TextView view = view("内容");
    view.setTranslationY(12f);
    UiMotion.reveal(view);
    assertEquals(1f, view.getAlpha(), 0.001f);
    assertEquals(0f, view.getTranslationY(), 0.001f);
  }

  @Test
  public void flashStaysOpaqueWhenAnimationsDisabled() {
    animationScales(0f);
    TextView view = view("段位");
    UiMotion.flash(view);
    assertEquals(1f, view.getAlpha(), 0.001f);
  }

  @Test
  public void textColorTransitionFallsBackToStaticWhenAnimationsDisabled() {
    animationScales(0f);
    TextView view = view("状态");
    int target = 0xFF112233;
    UiMotion.crossFadeTextColor(view, target);
    assertEquals(target, view.getCurrentTextColor());
  }
}
