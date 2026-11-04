package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.HomeLayoutStore;
import com.deepseekharness.app.util.UiText;

/**
 * 主页便签 tile：内容可以纵向缩放，选中后像编辑图片/窗口一样拖边角改宽高。
 *
 * <p>未选中时卡片顶上没有任何角标或删除图标；点一下选中才出现蓝色边框、八个
 * 缩放手柄，以及「恢复默认 / 收起」两个明确动作。宽度是连续比例，高度是纵向
 * 缩放，二者可以捏成任意长宽比。
 */
final class HomeTile extends FrameLayout {

  /** 选中卡片上的缩放/复位动作；由主页画布提供真实写回。 */
  interface Listener {
    void onResizeStart(HomeTile tile);

    void onResize(HomeTile tile, float widthFraction, float zoom);

    void onResizeEnd(HomeTile tile);
  }

  private static final int TOOLBAR_TOP_DP = 2;

  private final View content;
  private float zoom = 1f;
  private int chromeTop;
  private boolean selected;
  private ResizeOverlay overlay;
  private LinearLayout toolbar;

  /** 编辑态覆盖在内容上的透明层：让整张卡都能拖动/选中，又不误触业务按钮。 */
  private View bodyTouch;

  HomeTile(Context context, View content) {
    super(context);
    this.content = content;
    setClipChildren(false);
    setClipToPadding(false);
    addView(
        content,
        new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
  }

  /** 编辑态在卡片内容上方留一条空带：选中后「恢复默认/收起」放这里，不盖内容。 */
  void setChromeTop(int px) {
    chromeTop = Math.max(0, px);
    requestLayout();
  }

  float zoom() {
    return zoom;
  }

  void setZoom(float value) {
    zoom = Math.max(HomeLayoutStore.ZOOM_MIN, Math.min(HomeLayoutStore.ZOOM_MAX, value));
    requestLayout();
  }

  View content() {
    return content;
  }

  /** 内容在「未缩放」状态下的自然高度，用来把手指的纵向位移换算成缩放。 */
  int naturalContentHeight() {
    int measured = content.getMeasuredHeight();
    if (measured > 0) return measured;
    return Math.max(1, Math.round(getHeight() / Math.max(0.01f, zoom)));
  }

  boolean isResizeSelected() {
    return selected;
  }

  ResizeOverlay overlay() {
    return overlay;
  }

  /** 编辑态接入整卡触摸；覆盖层在内容之上、手柄/工具条之下。 */
  void enableEditBody(OnTouchListener listener) {
    if (bodyTouch == null) {
      bodyTouch = new View(getContext());
      bodyTouch.setClickable(true);
      addView(
          bodyTouch,
          new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }
    bodyTouch.setOnTouchListener(listener);
    requestLayout();
  }

  /**
   * 选中/取消选中。选中时只出现边框、八个手柄和两个动作，不覆盖业务按钮。
   *
   * @param listener 手柄缩放期间的回调；取消选中可以传 null
   * @param reset 这张卡恢复默认大小
   * @param hide 收起这张卡
   */
  void setSelectedForResize(boolean value, Listener listener, Runnable reset, Runnable hide) {
    if (selected == value) {
      if (value && overlay != null && listener != null) overlay.setListener(listener);
      return;
    }
    selected = value;
    if (!value) {
      if (overlay != null) removeView(overlay);
      if (toolbar != null) removeView(toolbar);
      overlay = null;
      toolbar = null;
      requestLayout();
      return;
    }
    overlay = new ResizeOverlay(getContext(), this, listener);
    addView(
        overlay,
        new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    toolbar = buildToolbar(reset, hide);
    addView(toolbar);
    requestLayout();
    if (UiMotion.animationsEnabled(getContext())) {
      overlay.setAlpha(0f);
      overlay.setScaleX(0.98f);
      overlay.setScaleY(0.98f);
      overlay
          .animate()
          .alpha(1f)
          .scaleX(1f)
          .scaleY(1f)
          .setDuration(220)
          .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
          .start();
      toolbar.setAlpha(0f);
      toolbar.setTranslationY(-dp(6));
      toolbar
          .animate()
          .alpha(1f)
          .translationY(0f)
          .setStartDelay(30)
          .setDuration(240)
          .setInterpolator(UiMotion.standard())
          .start();
    }
  }

  private LinearLayout buildToolbar(Runnable reset, Runnable hide) {
    LinearLayout bar = new LinearLayout(getContext());
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER);
    TextView hideChip =
        actionChip(UiText.choose("收起", "Hide"), UiText.choose("把这张卡收起到下方", "Hide this card"), hide);
    hideChip.setTag("home_hide");
    bar.addView(hideChip);
    LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
    gap.leftMargin = dp(6);
    TextView resetChip =
        actionChip(
            UiText.choose("恢复默认", "Reset"),
            UiText.choose("恢复这张卡的默认大小", "Reset this card to its default size"),
            reset);
    resetChip.setTag("home_reset");
    bar.addView(resetChip, gap);
    return bar;
  }

  private TextView actionChip(String label, String description, Runnable action) {
    TextView chip = new TextView(getContext());
    chip.setText(label);
    chip.setTextSize(12);
    chip.setGravity(Gravity.CENTER);
    chip.setMinHeight(dp(30));
    chip.setPadding(dp(12), 0, dp(12), 0);
    chip.setTextColor(getContext().getColor(R.color.shell_accent));
    chip.setBackgroundResource(R.drawable.bg_shell_edit_bar);
    chip.setElevation(dp(4));
    chip.setContentDescription(description);
    chip.setOnClickListener(v -> action.run());
    UiMotion.pressScale(chip);
    return chip;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int width = MeasureSpec.getSize(widthMeasureSpec);
    int contentWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
    // 两遍测量：某些容器（含 weight/折叠行结构）在 UNSPECIFIED size=0 下会把自己
    // 算成很小甚至 0 —— 「快捷入口/启动操作被压成一条缝」的根因。
    // 第一遍 UNSPECIFIED 拿自然高；第二遍以它为上限 AT_MOST 再量一次取较大值。
    // 对正常容器第二遍结果不变（无害），对塌陷容器能恢复真实高度。
    int widthSpec = MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY);
    content.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    int firstPass = Math.max(content.getMeasuredHeight(), content.getMinimumHeight());
    content.measure(
        widthSpec, MeasureSpec.makeMeasureSpec(Math.max(1, firstPass), MeasureSpec.AT_MOST));
    int contentHeight = Math.max(firstPass, content.getMeasuredHeight());
    // 自然高度 = 顶部 chrome + 内容高 × zoom + padding（zoom 是用户捏合/拖出来的缩放）。
    int naturalHeight =
        getPaddingTop() + chromeTop + Math.round(contentHeight * zoom) + getPaddingBottom();
    // 照 Launcher3 的做法：格子给的尺寸就是唯一真相。
    //   - 父容器给 EXACTLY 时，卡片高度**必须**等于它（不再自己按内容算），
    //     这样选区框和卡片本体用的是同一个矩形，天生不可能不符。
    //   - 没给（例如被 ScrollView 直接测量）时才退回自然高度。
    // 高度只认自然高度：**绝不**因为外部要求而拉伸。
    // 拉伸会让容器比内容高，而选区框按容器画 → 必然出现「框比卡片大一圈」。
    // 外部要求更高时也不采纳，保持内容自洽。
    int measuredHeight = naturalHeight;
    // 内容区可用高度 = 卡片总高 - 顶部 chrome - padding；内容按它布局，多余留白。
    int contentAreaHeight =
        Math.max(0, measuredHeight - getPaddingTop() - chromeTop - getPaddingBottom());
    if (bodyTouch != null) {
      bodyTouch.measure(
          MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
          MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY));
    }
    if (overlay != null) {
      overlay.measure(
          MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
          MeasureSpec.makeMeasureSpec(contentAreaHeight, MeasureSpec.EXACTLY));
    }
    if (toolbar != null) {
      toolbar.measure(
          MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.AT_MOST),
          MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }
    setMeasuredDimension(width, measuredHeight);
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    int padLeft = getPaddingLeft();
    int padTop = getPaddingTop();
    // onLayout 的 left/right 是「相对父容器」的坐标；子视图必须用本卡自己的宽度。
    int width = right - left - padLeft - getPaddingRight();
    int contentHeight = content.getMeasuredHeight();
    content.layout(
        padLeft, padTop + chromeTop, padLeft + width, padTop + chromeTop + contentHeight);
    content.setPivotX(0f);
    content.setPivotY(0f);
    content.setScaleY(zoom);
    if (bodyTouch != null) {
      bodyTouch.layout(0, 0, right - left, bottom - top);
    }
    if (overlay != null) {
      int contentAreaHeight = Math.max(1, bottom - top - padTop - chromeTop - getPaddingBottom());
      overlay.layout(0, padTop + chromeTop, right - left, padTop + chromeTop + contentAreaHeight);
    }
    if (toolbar != null) {
      int barWidth = toolbar.getMeasuredWidth();
      int barHeight = toolbar.getMeasuredHeight();
      int localRight = right - left;
      int x = Math.max(padLeft, localRight - getPaddingRight() - barWidth - dp(6));
      toolbar.layout(
          x, padTop + dp(TOOLBAR_TOP_DP), x + barWidth, padTop + dp(TOOLBAR_TOP_DP) + barHeight);
    }
  }

  /**
   * 选中卡片上的半透明边框 + 八个手柄。
   *
   * <p>只有按在手柄的命中范围内才接管触摸；按在卡片其它位置仍交给整卡拖动/换位。
   */
  static final class ResizeOverlay extends View {

    enum Handle {
      LEFT,
      RIGHT,
      TOP,
      BOTTOM,
      TOP_LEFT,
      TOP_RIGHT,
      BOTTOM_LEFT,
      BOTTOM_RIGHT
    }

    private static final float HANDLE_INSET_DP = 10f;

    /** 只有四角可拖：与 L 形角标的视觉一致。 */
    private static final Handle[] CORNER_HANDLES = {
      Handle.TOP_LEFT, Handle.TOP_RIGHT, Handle.BOTTOM_LEFT, Handle.BOTTOM_RIGHT
    };

    private static final float HIT_RADIUS_DP = 22f;

    private final HomeTile tile;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accent;
    private Listener listener;

    private Handle active;
    private float startRawX;
    private float startRawY;
    private float startFraction;
    private float startZoom;
    private float startUsable;
    private float startBaseHeight;
    private boolean moved;

    ResizeOverlay(Context context, HomeTile tile, Listener listener) {
      super(context);
      this.tile = tile;
      this.listener = listener;
      this.accent = context.getColor(R.color.shell_accent);
      paint.setStrokeWidth(dp(2));
      setWillNotDraw(false);
      setContentDescription(UiText.choose("拖动边角调整这张卡的大小", "Drag the handles to resize this card"));
    }

    void setListener(Listener value) {
      listener = value;
    }

    private int dp(float value) {
      return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * 选区视觉：1dp 细边框 + 四角 **L 形角标**。
     *
     * <p>画法参考 CanHub/Android-Image-Cropper 的 `CropOverlayView.drawLineShape`：
     * 每个角画两条短线组成 L，不用八个白底圆圈。八个圆圈在小卡片上会糊成一团、
     * 还会盖住内容；L 形角标只在角上占一点点，既能看出可拖，又不挡东西。
     */
    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      int w = getWidth();
      int h = getHeight();
      if (w <= 0 || h <= 0) return;
      float stroke = dp(1.5f);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(stroke);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setColor(accent);
      float radius = dp(12);
      // 边框缩进半个描边宽，避免被裁掉一半。
      float edge = stroke / 2f;
      canvas.drawRoundRect(edge, edge, w - edge, h - edge, radius, radius, paint);

      // L 形角标：每角两条短线，长度随卡片尺寸自适应，但不短于 14dp、不长于 26dp。
      float arm = Math.min(dp(26), Math.max(dp(14), Math.min(w, h) * 0.16f));
      // 往外挪一点点，让角标压在边框上而不是侵入内容。
      float out = dp(1f);
      paint.setStrokeWidth(dp(2.5f));
      // 左上
      canvas.drawLine(edge, edge - out + arm, edge, edge, paint);
      canvas.drawLine(edge, edge, edge + arm, edge, paint);
      // 右上
      canvas.drawLine(w - edge - arm, edge, w - edge, edge, paint);
      canvas.drawLine(w - edge, edge, w - edge, edge + arm, paint);
      // 左下
      canvas.drawLine(edge, h - edge - arm, edge, h - edge, paint);
      canvas.drawLine(edge, h - edge, edge + arm, h - edge, paint);
      // 右下
      canvas.drawLine(w - edge - arm, h - edge, w - edge, h - edge, paint);
      canvas.drawLine(w - edge, h - edge - arm, w - edge, h - edge, paint);
    }

    private float centerX(Handle handle, float inset) {
      int width = getWidth();
      switch (handle) {
        case LEFT:
        case TOP_LEFT:
        case BOTTOM_LEFT:
          return inset;
        case RIGHT:
        case TOP_RIGHT:
        case BOTTOM_RIGHT:
          return width - inset;
        default:
          return width / 2f;
      }
    }

    private float centerY(Handle handle, float inset) {
      int height = getHeight();
      switch (handle) {
        case TOP:
        case TOP_LEFT:
        case TOP_RIGHT:
          return inset;
        case BOTTOM:
        case BOTTOM_LEFT:
        case BOTTOM_RIGHT:
          return height - inset;
        default:
          return height / 2f;
      }
    }

    /**
     * 命中判定只认**四个角**：L 形角标画在角上，边中点没有视觉提示，
     * 若还能拖会让人以为「随便点边上都能改」。命中半径 22dp，比角标本身大，
     * 手指不用点得特别准。
     */
    private Handle hitHandle(float x, float y) {
      float inset = Math.min(dp(HANDLE_INSET_DP), Math.min(getWidth(), getHeight()) / 3f);
      float radius = dp(HIT_RADIUS_DP);
      Handle best = null;
      float bestDistance = radius;
      for (Handle handle : CORNER_HANDLES) {
        float dx = x - centerX(handle, inset);
        float dy = y - centerY(handle, inset);
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance <= bestDistance) {
          bestDistance = distance;
          best = handle;
        }
      }
      return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          active = hitHandle(event.getX(), event.getY());
          if (active == null) return false;
          moved = false;
          startRawX = event.getRawX();
          startRawY = event.getRawY();
          startFraction = widthFraction();
          startZoom = tile.zoom();
          startUsable = usableWidth();
          startBaseHeight = Math.max(1f, tile.naturalContentHeight());
          // 竖向拖手柄时外层 ScrollView 会抢触摸；抓住手柄后先禁止它拦截。
          if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
          if (listener != null) listener.onResizeStart(tile);
          return true;
        case MotionEvent.ACTION_MOVE:
          if (active == null) return false;
          if (Math.abs(event.getRawX() - startRawX) > dp(2)
              || Math.abs(event.getRawY() - startRawY) > dp(2)) moved = true;
          float dx = event.getRawX() - startRawX;
          float dy = event.getRawY() - startRawY;
          float fraction = startFraction;
          if (active == Handle.LEFT || active == Handle.TOP_LEFT || active == Handle.BOTTOM_LEFT)
            fraction -= dx / startUsable;
          if (active == Handle.RIGHT || active == Handle.TOP_RIGHT || active == Handle.BOTTOM_RIGHT)
            fraction += dx / startUsable;
          float zoom = startZoom;
          if (active == Handle.TOP || active == Handle.TOP_LEFT || active == Handle.TOP_RIGHT)
            zoom -= dy / startBaseHeight;
          if (active == Handle.BOTTOM
              || active == Handle.BOTTOM_LEFT
              || active == Handle.BOTTOM_RIGHT) zoom += dy / startBaseHeight;
          fraction =
              Math.max(HomeLayoutStore.WIDTH_MIN, Math.min(HomeLayoutStore.WIDTH_MAX, fraction));
          zoom = Math.max(HomeLayoutStore.ZOOM_MIN, Math.min(HomeLayoutStore.ZOOM_MAX, zoom));
          if (listener != null) listener.onResize(tile, fraction, zoom);
          return true;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          if (active == null) return false;
          active = null;
          if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
          if (listener != null) listener.onResizeEnd(tile);
          if (!moved) performClick();
          return true;
        default:
          return false;
      }
    }

    private float widthFraction() {
      return (float) tile.getWidth() / usableWidth();
    }

    private float usableWidth() {
      View parent = (View) tile.getParent();
      if (parent instanceof HomeMasonryLayout) {
        return Math.max(1f, ((HomeMasonryLayout) parent).usableWidth());
      }
      return Math.max(1f, (float) getWidth());
    }
  }
}
