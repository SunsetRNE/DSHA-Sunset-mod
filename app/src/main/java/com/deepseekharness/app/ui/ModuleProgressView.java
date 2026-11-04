package com.deepseekharness.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import java.util.Locale;

/**
 * 模块自己的进度条画布：一张 View 覆盖设计稿里的全部变种。
 *
 * <p>设计图里的效果不是写死到宿主页面，而是模块在 {@code view} 里写一个
 * {@code {"type":"progress","variant":"..."}} 节点就能得到。宿主负责动画与绘制，
 * 模块只声明外观；未知变种回退到细线，不让一张旧宿主被新模块毒死。
 *
 * <p>变种：{@code mixed / shimmer / segmented / roaming / numeric / breath /
 * wave / waveCurve / waveSpring / line}。
 */
public final class ModuleProgressView extends View {

  public static final String[] VARIANTS = {
    "mixed",
    "shimmer",
    "segmented",
    "roaming",
    "numeric",
    "breath",
    "wave",
    "waveCurve",
    "waveSpring",
    "line"
  };

  public static final String DEFAULT_VARIANT = "line";
  public static final int MAX_HEIGHT_DP = 24;
  public static final int MAX_SEGMENTS = 20;

  private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();
  private final Path wave = new Path();

  private String variant = DEFAULT_VARIANT;
  private boolean indeterminate;
  private float targetValue = 0.65f;
  private float shownValue = 0.65f;
  private long durationMs = 1600;
  private int barHeightPx;
  private int cornerPx;
  private int segmentCount = 5;
  private int trackColor = 0x22808080;
  private int fillColor = 0xFFFF5A5F;
  private int fillColor2 = 0x00FFFFFF;
  private int glowColor = 0x66FF5A5F;
  private boolean showValue;
  private boolean glow = true;
  private int stateFill = 0;
  private float phase;
  private float spring;
  private ValueAnimator animator;

  public ModuleProgressView(Context context) {
    super(context);
    setWillNotDraw(false);
    barHeightPx = dp(4);
    cornerPx = dp(2);
    textPaint.setTextSize(dp(10));
    textPaint.setColor(Color.WHITE);
    trackPaint.setColor(trackColor);
    fillPaint.setColor(fillColor);
    glowPaint.setColor(glowColor);
    updateContentDescription();
  }

  /** 模块 JSON 里写了非法变种时回退细线；大小写不敏感，收掉前后空白。 */
  public static String normalizeVariant(String raw) {
    String value = raw == null ? "" : raw.trim();
    for (String variant : VARIANTS) if (variant.equalsIgnoreCase(value)) return variant;
    return DEFAULT_VARIANT;
  }

  /** 同时接受 0-1 和 0-100；NaN/越界都夹到 0-1。 */
  public static float normalizeValue(double raw) {
    if (Double.isNaN(raw) || Double.isInfinite(raw)) return 0f;
    float value = raw > 1.0 ? (float) (raw / 100.0) : (float) raw;
    return Math.max(0f, Math.min(1f, value));
  }

  public String variant() {
    return variant;
  }

  public float value() {
    return targetValue;
  }

  public boolean isIndeterminate() {
    return indeterminate;
  }

  public void configure(
      String variantName,
      boolean indeterminate,
      float value,
      int heightDp,
      int radiusDp,
      int track,
      int fill,
      int fill2,
      int glowTint,
      boolean glowEnabled,
      boolean showValue,
      int segments,
      long duration,
      int successColor,
      int errorColor,
      String state) {
    this.variant = normalizeVariant(variantName);
    this.indeterminate = indeterminate;
    this.targetValue = normalizeValue(value);
    this.shownValue = indeterminate ? 0.35f : this.targetValue;
    this.barHeightPx = dp(Math.max(1, Math.min(MAX_HEIGHT_DP, heightDp)));
    this.cornerPx = dp(Math.max(0, Math.min(MAX_HEIGHT_DP / 2, radiusDp)));
    this.trackColor = track;
    this.fillColor = fill;
    this.fillColor2 = fill2;
    this.glowColor = glowTint;
    this.glow = glowEnabled;
    this.showValue = showValue;
    this.segmentCount = Math.max(2, Math.min(MAX_SEGMENTS, segments));
    this.durationMs = Math.max(200, Math.min(5000, duration));
    this.stateFill = 0;
    String status = state == null ? "" : state.trim();
    if ("success".equalsIgnoreCase(status)) this.stateFill = successColor;
    else if ("error".equalsIgnoreCase(status)) this.stateFill = errorColor;
    trackPaint.setColor(trackColor);
    fillPaint.setColor(this.stateFill != 0 ? this.stateFill : fillColor);
    glowPaint.setColor(this.stateFill != 0 ? this.stateFill : glowColor);
    if (this.barHeightPx > dp(8)) {
      textPaint.setTextSize(dp(10));
      textPaint.setColor(Color.WHITE);
    }
    requestLayout();
    invalidate();
    updateContentDescription();
    restart();
  }

  /** 运行时点一次成功/失败态，模块可以用脚本触发。 */
  public void setStatus(String status) {
    stateFill = 0;
    if ("success".equalsIgnoreCase(status)) stateFill = 0xFF35C759;
    else if ("error".equalsIgnoreCase(status)) stateFill = 0xFFFF453A;
    fillPaint.setColor(stateFill != 0 ? stateFill : fillColor);
    glowPaint.setColor(stateFill != 0 ? stateFill : glowColor);
    invalidate();
    updateContentDescription();
  }

  private void updateContentDescription() {
    String percent = String.format(Locale.US, "%d%%", Math.round(targetValue * 100));
    setContentDescription(indeterminate ? "进行中 " + variant : "进度 " + percent + " " + variant);
  }

  private void restart() {
    stopAnimator();
    if (!isAttachedToWindow()) return;
    ValueAnimator value = ValueAnimator.ofFloat(0f, 1f);
    value.setDuration(durationMs);
    if ("waveSpring".equals(variant)) {
      value.setInterpolator(new OvershootInterpolator(1.2f));
    } else if (indeterminate || "roaming".equals(variant) || "breath".equals(variant)) {
      value.setInterpolator(null);
    } else {
      value.setInterpolator(new DecelerateInterpolator());
    }
    value.setRepeatCount(
        indeterminate
                || "roaming".equals(variant)
                || "breath".equals(variant)
                || "mixed".equals(variant)
                || "shimmer".equals(variant)
                || "segmented".equals(variant)
            ? ValueAnimator.INFINITE
            : 0);
    value.addUpdateListener(
        animation -> {
          phase = (float) animation.getAnimatedValue() % 1f;
          if (indeterminate || "roaming".equals(variant) || "breath".equals(variant)) {
            shownValue = 0.35f;
          } else {
            float raw = (float) animation.getAnimatedValue();
            shownValue = targetValue * Math.min(1.25f, raw);
            spring = 1f - Math.min(1f, raw);
          }
          invalidate();
        });
    animator = value;
    value.start();
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    restart();
  }

  @Override
  protected void onDetachedFromWindow() {
    stopAnimator();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onVisibilityChanged(View changedView, int visibility) {
    super.onVisibilityChanged(changedView, visibility);
    if (visibility == VISIBLE) restart();
    else stopAnimator();
  }

  private void stopAnimator() {
    if (animator != null) {
      animator.cancel();
      animator = null;
    }
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int width = resolveSize(dp(220), widthMeasureSpec);
    int height = resolveSize(barHeightPx + dp(showValue ? 10 : 2), heightMeasureSpec);
    setMeasuredDimension(width, height);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    int left = getPaddingLeft();
    int right = getWidth() - getPaddingRight();
    if (right <= left) return;
    int height = Math.max(dp(2), barHeightPx);
    float top = (getHeight() - height) / 2f - (showValue ? dp(5) : 0);
    float bottom = top + height;
    int width = right - left;
    rect.set(left, top, right, bottom);
    float radius = Math.min(cornerPx, height / 2f);

    if (("wave".equals(variant) || "waveCurve".equals(variant) || "waveSpring".equals(variant))
        && height > dp(4)) drawWave(canvas, left, right, height, top, radius);
    else drawBar(canvas, left, right, height, top, radius, width);
    // numeric 变种自己画右侧胶囊；再画下方百分比会重复两行。
    if (showValue && !"numeric".equals(variant)) drawValue(canvas, left, right, bottom);
  }

  private void drawBar(
      Canvas canvas, int left, int right, int height, float top, float radius, int width) {
    trackPaint.setStyle(Paint.Style.FILL);
    trackPaint.setColor(trackColor);
    canvas.drawRoundRect(new RectF(left, top, right, top + height), radius, radius, trackPaint);

    int[] fillColors =
        stateFill != 0
            ? new int[] {stateFill, stateFill}
            : new int[] {fillColor, fillColor2 == 0 ? fillColor : fillColor2};
    fillPaint.setShader(
        new LinearGradient(
            left, top, right, top + height, fillColors, null, Shader.TileMode.CLAMP));

    switch (variant) {
      case "roaming" -> drawRoaming(canvas, left, right, top, height, radius, fillColors[0]);
      case "breath" -> drawBreath(canvas, left, right, top, height);
      case "segmented" -> drawSegments(canvas, left, right, top, height, radius);
      case "numeric" -> drawNumeric(canvas, left, right, top, height, radius);
      case "shimmer", "mixed" ->
          drawFillWithSweep(canvas, left, right, top, height, radius, fillColors[0]);
      default -> drawFill(canvas, left, right, top, height, radius, fillColors[0]);
    }
    if (glow) {
      glowPaint.setStyle(Paint.Style.FILL);
      glowPaint.setAlpha((int) (0.35f * 255));
      float edge = left + width * shownValue;
      RectF halo = new RectF(edge - dp(8), top - dp(2), edge + dp(2), top + height + dp(2));
      canvas.drawRoundRect(halo, radius + dp(2), radius + dp(2), glowPaint);
      glowPaint.setAlpha(255);
    }
  }

  private void drawFill(
      Canvas canvas, int left, int right, float top, int height, float radius, int solid) {
    float end = left + (right - left) * shownValue;
    if (end <= left) return;
    fillPaint.setShader(null);
    fillPaint.setColor(solid);
    canvas.drawRoundRect(
        new RectF(left, top, Math.max(left + radius, end), top + height),
        radius,
        radius,
        fillPaint);
  }

  private void drawFillWithSweep(
      Canvas canvas, int left, int right, float top, int height, float radius, int solid) {
    drawFill(canvas, left, right, top, height, radius, solid);
    float band = (right - left) * 0.38f;
    float center = left - band + (right - left + band * 2) * phase;
    int color = stateFill != 0 ? stateFill : fillColor;
    sweepPaint.setShader(
        new LinearGradient(
            center - band,
            top,
            center + band,
            top,
            new int[] {Color.TRANSPARENT, (color & 0x00FFFFFF) | 0x66FFFFFF, Color.TRANSPARENT},
            new float[] {0f, 0.5f, 1f},
            Shader.TileMode.CLAMP));
    canvas.save();
    canvas.clipRect(left, top, left + (right - left) * shownValue, top + height);
    canvas.drawRect(left, top, right, top + height, sweepPaint);
    canvas.restore();
  }

  private void drawSegments(
      Canvas canvas, int left, int right, float top, int height, float radius) {
    float gap = dp(3);
    float total = right - left;
    float cell = (total - gap * (segmentCount - 1)) / segmentCount;
    float fractional = shownValue * segmentCount;
    for (int i = 0; i < segmentCount; i++) {
      float start = left + i * (cell + gap);
      float alpha =
          i < Math.floor(fractional)
              ? 1f
              : i == Math.floor(fractional) ? fractional - (float) Math.floor(fractional) : 0f;
      fillPaint.setShader(null);
      fillPaint.setColor((stateFill != 0 ? stateFill : fillColor));
      fillPaint.setAlpha((int) (255 * alpha));
      canvas.drawRoundRect(
          new RectF(start, top, start + cell, top + height), radius, radius, fillPaint);
    }
    fillPaint.setAlpha(255);
  }

  private void drawRoaming(
      Canvas canvas, int left, int right, float top, int height, float radius, int solid) {
    float trackAlpha = 0.35f;
    fillPaint.setShader(null);
    fillPaint.setColor(solid);
    fillPaint.setAlpha((int) (255 * trackAlpha));
    canvas.drawRoundRect(new RectF(left, top, right, top + height), radius, radius, fillPaint);
    float x = left + (right - left) * phase;
    float cy = top + height / 2f;
    for (int i = 0; i < 6; i++) {
      float trail = x - i * dp(5);
      fillPaint.setAlpha((int) (255 * (0.28f - i * 0.045f)));
      canvas.drawCircle(Math.max(left, trail), cy, height * 0.7f, fillPaint);
    }
    fillPaint.setAlpha(255);
    fillPaint.setColor(stateFill != 0 ? stateFill : fillColor);
    canvas.drawCircle(x, cy, height * 0.8f, fillPaint);
  }

  private void drawBreath(Canvas canvas, int left, int right, float top, int height) {
    if (!indeterminate) {
      drawFill(canvas, left, right, top, height, Math.min(cornerPx, height / 2f), fillColor);
    }
    float center = left + (right - left) * (indeterminate ? 0.5f : shownValue);
    float breathe = 0.55f + 0.45f * (float) Math.sin(phase * Math.PI * 2);
    float halo = (right - left) * (indeterminate ? 0.18f : 0.10f) * breathe;
    glowPaint.setStyle(Paint.Style.FILL);
    glowPaint.setColor(fillColor);
    glowPaint.setAlpha((int) (255 * 0.28f));
    canvas.drawCircle(center, top + height / 2f, Math.max(halo, dp(4)), glowPaint);
    glowPaint.setAlpha(255);
  }

  private void drawNumeric(
      Canvas canvas, int left, int right, float top, int height, float radius) {
    drawFill(canvas, left, right, top, height, radius, fillColor);
    if (indeterminate) return;
    String label = Math.round(targetValue * 100) + "%";
    float textWidth = textPaint.measureText(label);
    float pad = dp(6);
    float capsuleWidth = textWidth + pad * 2;
    float capsuleHeight = Math.max(dp(16), height + dp(8));
    float capsuleLeft = Math.max(left, right - capsuleWidth);
    RectF capsule =
        new RectF(
            capsuleLeft,
            top + height / 2f - capsuleHeight / 2f,
            right,
            top + height / 2f + capsuleHeight / 2f);
    Paint capsulePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    capsulePaint.setColor(0xCC101418);
    canvas.drawRoundRect(capsule, capsuleHeight / 2f, capsuleHeight / 2f, capsulePaint);
    float baseline = capsule.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f;
    canvas.drawText(label, capsuleLeft + pad, baseline, textPaint);
  }

  private void drawValue(Canvas canvas, int left, int right, float bottom) {
    String label = indeterminate ? "··" : Math.round(targetValue * 100) + "%";
    textPaint.setColor(0xFFAAB6C8);
    float baseline = bottom + dp(10);
    canvas.drawText(label, right - textPaint.measureText(label), baseline, textPaint);
  }

  private void drawWave(Canvas canvas, int left, int right, int height, float top, float radius) {
    float amplitude = height / 2f;
    if ("waveSpring".equals(variant)) amplitude *= 0.55f + 0.45f * spring;
    wave.reset();
    wave.moveTo(left, top + height / 2f);
    for (int x = 0; x <= right - left; x++) {
      float t = x / (float) Math.max(1, right - left);
      float speed = "waveCurve".equals(variant) ? t * 2f : 1f;
      float waveLength =
          "waveSpring".equals(variant) ? 2f : "waveCurve".equals(variant) ? 1.4f : 1f;
      float y =
          top
              + height / 2f
              + (float) Math.sin((t * Math.PI * 2 * waveLength) + phase * Math.PI * 2 * speed)
                  * amplitude;
      if (x == 0) wave.moveTo(left, y);
      else wave.lineTo(left + x, y);
    }
    if ("waveCurve".equals(variant)) {
      fillPaint.setShader(
          new LinearGradient(left, top, right, top, fillColor, fillColor2, Shader.TileMode.CLAMP));
    } else {
      fillPaint.setShader(null);
      fillPaint.setColor(stateFill != 0 ? stateFill : fillColor);
    }
    fillPaint.setStrokeWidth(dp(2));
    fillPaint.setStyle(Paint.Style.STROKE);
    canvas.drawPath(wave, fillPaint);
    fillPaint.setStyle(Paint.Style.FILL);
    if (!indeterminate && showValue) {
      float end = left + (right - left) * shownValue;
      fillPaint.setShader(null);
      fillPaint.setColor(fillColor);
      fillPaint.setAlpha(40);
      canvas.drawRoundRect(new RectF(left, top, end, top + height), radius, radius, fillPaint);
      fillPaint.setAlpha(255);
    }
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
