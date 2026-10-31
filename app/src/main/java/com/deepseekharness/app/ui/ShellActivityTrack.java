package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;

/**
 * 波浪活动条（设计源 V5 {@code ps-wave-track}）：只表达真实的进行 / 完成 / 需处理状态，不推算安装或启动百分比。
 *
 * <p>底层是一条淡色静态引导波；上层是一条按状态着色的波，只有进行中才缓慢向右漂移，其他状态静止。两端渐隐。
 * 漂移由当前视图的帧回调推进，不持有无限 ValueAnimator；系统关闭动画或视图不可见时保持静态。
 * 高度不足以画出波形时（如 2dp 细条）振幅自动收为 0，退化为直线。颜色全部取自 shell_* token。
 */
public final class ShellActivityTrack extends View {

  /** 没有进行中的真实任务：静态淡色波。 */
  public static final int IDLE = 0;

  /** 正在启动 / 停止 / 核对：强调色波缓慢漂移。 */
  public static final int RUNNING = 1;

  /** 已就绪：静态成功色波。 */
  public static final int SETTLED = 2;

  /** 需要处理（原实例待确认、环境待恢复）：静态提醒色波（设计源 attention = amber）。 */
  public static final int ERROR = 3;

  /** 漂移一个波长的时长：设计源 18s 漂移 10 个波长，即每个波长 1.8s。 */
  private static final long WAVE_PERIOD_MS = 1800L;

  /** 设计源 viewBox 640×24 内 10 个波长，在约 440dp 宽度下每个波长约 44dp。 */
  private static final float WAVELENGTH_DP = 44f;

  /** 设计源二次曲线控制点偏离中线 9/24，曲线实际峰值约为一半：约 4.5dp。 */
  private static final float AMPLITUDE_DP = 4.5f;

  /** 设计源 max-width:440px；更宽的容器只在起始侧绘制这一段。 */
  private static final float MAX_WIDTH_DP = 440f;

  /** 设计源两端 4% 渐隐。 */
  private static final float EDGE_FADE = 0.04f;

  /** 引导波相对前景波的相位错开（设计源 translateX(-1.6%)，约 0.1 个波长）。 */
  private static final float GUIDE_PHASE = 0.1f;

  private final Paint flowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path wave = new Path();
  private final Path shifted = new Path();
  private final float density;
  private final int guideColor;
  private final int idleColor;
  private final int runningColor;
  private final int settledColor;
  private final int errorColor;

  private int state = IDLE;
  private long driftStartMs;
  private int builtWidth = -1, builtHeight = -1;
  private int shaderColor;
  private boolean shaderValid;
  private float wavelength, drawWidth;
  private boolean animationsEnabled = true;
  private boolean windowVisible = true;

  public ShellActivityTrack(Context context) {
    this(context, null);
  }

  public ShellActivityTrack(Context context, AttributeSet attrs) {
    super(context, attrs);
    density = context.getResources().getDisplayMetrics().density;
    guideColor = context.getColor(com.deepseekharness.app.R.color.shell_line_strong);
    idleColor = context.getColor(com.deepseekharness.app.R.color.shell_text_muted);
    runningColor = context.getColor(com.deepseekharness.app.R.color.shell_accent);
    settledColor = context.getColor(com.deepseekharness.app.R.color.shell_good);
    errorColor = context.getColor(com.deepseekharness.app.R.color.shell_warn);
    for (Paint paint : new Paint[] {flowPaint, guidePaint}) {
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
    }
    flowPaint.setStrokeWidth(1.6f * density);
    guidePaint.setStrokeWidth(1f * density);
    refreshAnimationSetting();
  }

  /** 设置展示状态；同一状态重复设置不会重置漂移相位或启动新的重绘循环。 */
  public void setState(int nextState) {
    int normalized =
        nextState == RUNNING || nextState == SETTLED || nextState == ERROR ? nextState : IDLE;
    if (normalized == state) return;
    state = normalized;
    driftStartMs = android.os.SystemClock.uptimeMillis();
    refreshAnimationSetting();
    invalidate();
  }

  public int state() {
    return state;
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    driftStartMs = android.os.SystemClock.uptimeMillis();
    refreshAnimationSetting();
    invalidate();
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    // 退到后台时停掉帧回调；回到可见时下一帧重新按真实状态绘制。
    windowVisible = visibility == VISIBLE;
    invalidate();
  }

  @Override
  protected void onDetachedFromWindow() {
    // 仅用帧回调推进；脱离窗口后不再请求下一帧，只释放缓存的着色器与波形。
    builtWidth = -1;
    shaderValid = false;
    flowPaint.setShader(null);
    guidePaint.setShader(null);
    super.onDetachedFromWindow();
  }

  /** 只在状态或附着状态变化时读取，避免每帧查询系统设置。 */
  private void refreshAnimationSetting() {
    try {
      animationsEnabled =
          Settings.Global.getFloat(
                  getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
              > 0f;
    } catch (RuntimeException ignored) {
      // 读取设置失败按开启处理；仍然不会伪造任何任务状态。
      animationsEnabled = true;
    }
  }

  private int flowColor() {
    return switch (state) {
      case RUNNING -> runningColor;
      case SETTLED -> settledColor;
      case ERROR -> errorColor;
      default -> idleColor;
    };
  }

  /** 尺寸变化时重建波形；颜色变化时只重建渐隐着色器。 */
  private void ensureGeometry(int width, int height) {
    if (width != builtWidth || height != builtHeight) {
      drawWidth = Math.min(width, MAX_WIDTH_DP * density);
      wavelength = WAVELENGTH_DP * density;
      // 留出描边宽度，避免峰谷被视图边界切掉；细条时振幅收为 0，退化为直线。
      float amplitude =
          Math.max(0f, Math.min(AMPLITUDE_DP * density, height / 2f - flowPaint.getStrokeWidth()));
      float mid = height / 2f;
      float quarter = wavelength / 4f;
      wave.rewind();
      // 左侧多画一个波长：漂移时平移 0..1 个波长即可无缝循环。
      float x = -wavelength;
      wave.moveTo(x, mid);
      float end = drawWidth + wavelength;
      while (x < end) {
        // 二次曲线控制点取 2 倍振幅，曲线峰值恰为 amplitude。
        wave.quadTo(x + quarter, mid - amplitude * 2f, x + quarter * 2f, mid);
        wave.quadTo(x + quarter * 3f, mid + amplitude * 2f, x + wavelength, mid);
        x += wavelength;
      }
      builtWidth = width;
      builtHeight = height;
      shaderValid = false;
    }
    int color = flowColor();
    if (!shaderValid || shaderColor != color) {
      flowPaint.setShader(edgeFade(color));
      guidePaint.setShader(edgeFade(guideColor));
      shaderColor = color;
      shaderValid = true;
    }
  }

  /** 两端渐隐的水平着色器，固定在画布坐标，不随波形漂移。 */
  private Shader edgeFade(int color) {
    int clear = color & 0x00FFFFFF;
    return new LinearGradient(
        0f,
        0f,
        drawWidth,
        0f,
        new int[] {clear, color, color, clear},
        new float[] {0f, EDGE_FADE, 1f - EDGE_FADE, 1f},
        Shader.TileMode.CLAMP);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    int width = getWidth();
    int height = getHeight();
    if (width <= 0 || height <= 0) return;
    ensureGeometry(width, height);
    canvas.save();
    // RTL 时镜像整段：起始侧对齐，漂移方向随阅读方向。
    if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) canvas.scale(-1f, 1f, width / 2f, 0f);
    canvas.clipRect(0f, 0f, drawWidth, height);

    guidePaint.setAlpha(153); // 设计源引导波 opacity .6
    wave.offset(-GUIDE_PHASE * wavelength, 0f, shifted);
    canvas.drawPath(shifted, guidePaint);

    boolean moving = state == RUNNING && animationsEnabled && windowVisible && isShown();
    float phase = 0f;
    if (moving) {
      long now = android.os.SystemClock.uptimeMillis();
      if (driftStartMs <= 0) driftStartMs = now;
      phase = (now - driftStartMs) % WAVE_PERIOD_MS / (float) WAVE_PERIOD_MS;
    }
    // 设计源 idle 前景 opacity .45；其余状态不透明。
    flowPaint.setAlpha(state == IDLE ? 115 : 255);
    wave.offset(phase * wavelength, 0f, shifted);
    canvas.drawPath(shifted, flowPaint);
    canvas.restore();
    if (moving) postInvalidateOnAnimation();
  }
}
