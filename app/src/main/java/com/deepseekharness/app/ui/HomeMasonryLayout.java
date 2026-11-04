package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主页便签流式网格：每个 tile 的宽度是 0.25–1.0 的连续比例，高度按内容自然测量；
 * 一行放不下就换行，顺序/尺寸变化时其它 tile 用弹性动画滑到新位置。
 *
 * <p>不是 RecyclerView：主页上的控件必须保持原实例，否则启动按钮、日志跟随等
 * 业务引用会断。这里只做测量、排布和位移动画。
 *
 * <p>排版算法是第二十二轮起多轮真机验证过的行式排版：按 order 顺序把卡往当前行里塞，
 * 放不下就换行；整行卡（fraction ≥ 0.999）先落定当前行再独占一行。
 * 每张卡的高度 = 自己的自然高度，<b>绝不拉伸</b>——拉伸容器不拉伸内容正是
 * 「选区框比卡大一圈」和「大卡被压扁」的根源（2026-11-04/05 多轮返工的结论）。
 * 在此之上只保留两个被真机验证过有益的改动：place() 对 floating（正在拖的卡）
 * 跳过位移动画（跟手的真正修复），以及 HomeTile 的四角 L 形角标。
 */
final class HomeMasonryLayout extends ViewGroup {

  static final class LayoutParams extends ViewGroup.LayoutParams {
    float widthFraction = 1f;
    int span = 2;

    /** 自由位置：横向比例 + 纵向 dp；hasPosition=false 时走默认流式排版。 */
    float xFraction;

    float yDp;
    boolean hasPosition;

    LayoutParams(float widthFraction) {
      super(MATCH_PARENT, WRAP_CONTENT);
      this.widthFraction = clamp(widthFraction);
      this.span = widthFraction >= 0.75f ? 2 : 1;
    }

    /** 兼容旧调用：1=半宽，2=整行（仅用于两列默认布局）。 */
    LayoutParams(int span) {
      this(span <= 1 ? 0.5f : 1f);
    }

    void setPosition(float x, float y) {
      xFraction = Math.max(0f, Math.min(1f, x));
      yDp = Math.max(0f, y);
      hasPosition = true;
    }

    private static float clamp(float value) {
      return Math.max(0.25f, Math.min(1f, value));
    }
  }

  private final int gapPx;
  private final int columns;
  private final Map<View, int[]> positions = new HashMap<>();
  private final Set<View> laidOut = new HashSet<>();
  private List<View> flow = new ArrayList<>();
  private View floating;
  private boolean animateChanges = true;
  private int contentHeight;
  private int lastMeasuredWidth = -1;

  /** onMeasure 中是否有自由位置的卡片因为会压住别人而被顺到下面。 */
  private boolean adjusted;

  private Runnable positionsAdjusted;

  /** 用户正在拖动/缩放的卡：和别的卡冲突时它保位，其它卡让位。 */
  private View anchor;

  private final OvershootInterpolator spring = new OvershootInterpolator(0.9f);

  HomeMasonryLayout(Context context, int gapPx, int columns) {
    super(context);
    this.gapPx = gapPx;
    this.columns = Math.max(1, columns);
    setClipChildren(false);
    setClipToPadding(false);
  }

  void setFlow(List<View> ordered) {
    flow = ordered == null ? new ArrayList<>() : new ArrayList<>(ordered);
    requestLayout();
  }

  List<View> flow() {
    return new ArrayList<>(flow);
  }

  void setFloating(View view) {
    floating = view;
    requestLayout();
  }

  View floating() {
    return floating;
  }

  void setAnimateChanges(boolean value) {
    animateChanges = value;
  }

  /** 自由位置被布局顺开后回调：主页画布借此把真实落点写回偏好。 */
  void setOnPositionsAdjusted(Runnable listener) {
    positionsAdjusted = listener;
  }

  /** 本轮 onMeasure 算出的卡片位置：{x, y, width, height}；没排到返回 null。 */
  int[] positionOf(View child) {
    return positions.get(child);
  }

  /**
   * 指定「用户当前操作的那张卡」。
   *
   * <p>自由摆放出现重叠时，普通卡片按顺序让位；被指定的卡保持用户放下的位置，
   * 这样拖进空位不会动邻居，压在别人身上时是别人向下让开，而不是自己的卡被弹走。
   */
  void setAnchor(View child) {
    anchor = child;
  }

  public int contentHeight() {
    return contentHeight;
  }

  /** 手柄换算宽度比例用的整行可用宽度。 */
  int usableWidth() {
    return Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int width = MeasureSpec.getSize(widthMeasureSpec);
    // 宽度第一次确定或旋转后重排：这一轮是「初始落位」，不能把旧坐标当动画起点，
    // 否则卡片会从屏幕左上角飞进来，甚至短暂重叠。
    if (width != lastMeasuredWidth) {
      lastMeasuredWidth = width;
      laidOut.clear();
    }
    int usable = Math.max(0, width - getPaddingLeft() - getPaddingRight());
    positions.clear();
    float density = getResources().getDisplayMetrics().density;

    int bottom = getPaddingTop();
    boolean anyPinned = false;
    adjusted = false;
    // 先放已经固定到自由位置的卡片：它们各自独立，不参与流式排版；
    // 但它们彼此之间也不能压住。用户正在拖/缩放的那张卡先排，保它、让别人让位。
    List<Rect> placedPinned = new ArrayList<>();
    List<View> pinnedOrder = new ArrayList<>();
    for (View child : flow) {
      if (paramsOf(child).hasPosition) pinnedOrder.add(child);
    }
    if (anchor != null && pinnedOrder.contains(anchor)) {
      pinnedOrder.remove(anchor);
      pinnedOrder.add(0, anchor);
    }
    for (View child : pinnedOrder) {
      LayoutParams params = paramsOf(child);
      anyPinned = true;
      int childWidth =
          params.widthFraction >= 0.999f
              ? usable
              : Math.max(1, Math.round(clampFraction(params.widthFraction) * usable));
      int x =
          getPaddingLeft()
              + Math.max(
                  0,
                  Math.min(
                      Math.round(params.xFraction * usable), Math.max(0, usable - childWidth)));
      int y = getPaddingTop() + Math.max(0, Math.round(params.yDp * density));
      child.measure(
          MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
          MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
      int childHeight = child.getMeasuredHeight();
      Rect rect = new Rect(x, y, x + childWidth, y + childHeight);
      // 自由摆放不是「随便重叠」：后落位的卡如果压到已经放好的卡，就顺到它下面。
      // 先摆好的卡永远不动，所以用户拖进空位时邻居不会被反过来推走。
      boolean moved = false;
      int guard = placedPinned.size() + 1;
      while (guard-- > 0) {
        int nextY = y;
        for (Rect placed : placedPinned) {
          if (Rect.intersects(rect, placed)) nextY = Math.max(nextY, placed.bottom + gapPx);
        }
        if (nextY == y) break;
        y = nextY;
        rect.offsetTo(x, y);
        moved = true;
      }
      if (moved) {
        params.yDp = Math.max(0f, (float) (y - getPaddingTop()) / density);
        adjusted = true;
      }
      positions.put(child, new int[] {x, y, childWidth, childHeight});
      placedPinned.add(rect);
      bottom = Math.max(bottom, y + childHeight);
    }

    int rowTop = anyPinned ? bottom + gapPx : getPaddingTop();
    List<View> row = new ArrayList<>();
    List<LayoutParams> rowParams = new ArrayList<>();
    float rowSum = 0f;

    for (View child : flow) {
      LayoutParams params = paramsOf(child);
      if (params.hasPosition) continue;
      float fraction = clampFraction(params.widthFraction);

      // 整行卡：先把当前行落定（bottom 必须在这里更新——漏掉正是「叠卡」的来源），
      // 再自己独占一行。
      if (fraction >= 0.999f) {
        if (!row.isEmpty()) {
          int rowHeight = placeRow(row, rowParams, rowTop, usable);
          bottom = Math.max(bottom, rowTop + rowHeight);
          rowTop += rowHeight + gapPx;
          row.clear();
          rowParams.clear();
          rowSum = 0f;
        }
        child.measure(
            MeasureSpec.makeMeasureSpec(usable, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int childHeight = child.getMeasuredHeight();
        positions.put(child, new int[] {getPaddingLeft(), rowTop, usable, childHeight});
        bottom = Math.max(bottom, rowTop + childHeight);
        rowTop += childHeight + gapPx;
        continue;
      }

      // 这一行放不下了：落定当前行，换新行。
      if (!row.isEmpty() && rowSum + fraction > 1.005f) {
        int rowHeight = placeRow(row, rowParams, rowTop, usable);
        bottom = Math.max(bottom, rowTop + rowHeight);
        rowTop += rowHeight + gapPx;
        row.clear();
        rowParams.clear();
        rowSum = 0f;
      }
      row.add(child);
      rowParams.add(params);
      rowSum += fraction;
    }
    if (!row.isEmpty()) {
      int rowHeight = placeRow(row, rowParams, rowTop, usable);
      bottom = Math.max(bottom, rowTop + rowHeight);
    }

    // 浮动项保持拖动前的宽度，不能因为换行临时变宽/变窄。
    if (floating != null && floating.getVisibility() != GONE) {
      LayoutParams params = paramsOf(floating);
      int widthPx =
          floating.getWidth() > 0
              ? floating.getWidth()
              : Math.max(1, Math.round(clampFraction(params.widthFraction) * usable));
      floating.measure(
          MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
          MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
      if (!positions.containsKey(floating)) {
        positions.put(
            floating, new int[] {getPaddingLeft(), 0, widthPx, floating.getMeasuredHeight()});
      }
    }
    contentHeight = Math.max(0, bottom - getPaddingTop());
    setMeasuredDimension(width, contentHeight + getPaddingTop() + getPaddingBottom());
  }

  /**
   * 排一行：行内可用宽度扣掉这一行实际需要的 gap，再按比例分给每张卡。
   *
   * <p>这样 3 个 1/3 卡正好占满一行，不会因为多算一个 gap 把第三张挤到下一行。
   * 每张卡保持自己的自然高度；返回行高（最高那张），只用于推进下一行起点。
   *
   * @return 这一行的行高
   */
  private int placeRow(List<View> row, List<LayoutParams> rowParams, int rowTop, int usable) {
    int count = row.size();
    int available = Math.max(1, usable - gapPx * (count - 1));
    int x = getPaddingLeft();
    int rowHeight = 0;
    int right = getPaddingLeft() + usable;
    for (int i = 0; i < count; i++) {
      float fraction = clampFraction(rowParams.get(i).widthFraction);
      int width = Math.max(1, Math.round(fraction * (float) available));
      width = Math.min(width, Math.max(1, right - x));
      View child = row.get(i);
      child.measure(
          MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
          MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
      int height = child.getMeasuredHeight();
      positions.put(child, new int[] {x, rowTop, width, height});
      x += width + gapPx;
      rowHeight = Math.max(rowHeight, height);
    }
    return rowHeight;
  }

  private static float clampFraction(float value) {
    return Math.max(0.25f, Math.min(1f, value));
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    for (View child : flow) {
      int[] pos = positions.get(child);
      if (pos == null) continue;
      place(child, pos[0], pos[1], pos[2], pos[3]);
    }
    if (floating != null && !laidOut.contains(floating)) {
      int[] pos = positions.get(floating);
      if (pos != null) {
        floating.layout(pos[0], pos[1], pos[0] + pos[2], pos[1] + pos[3]);
        laidOut.add(floating);
      }
    }
    if (adjusted) {
      adjusted = false;
      Runnable listener = positionsAdjusted;
      if (listener != null) listener.run();
    }
  }

  private void place(View child, int x, int y, int width, int height) {
    // 被拖动的卡（floating）**绝对不能**走这里的动画：
    // 它的视觉位置由手指的 translation 决定，一旦被 animate().translationX(0) 拉回去，
    // 就表现为「拖不动 / 不跟手 / 手指和卡片脱节」——这是之前跟手问题的真正根因。
    if (child == floating) {
      child.layout(x, y, x + width, y + height);
      laidOut.add(child);
      return;
    }
    int oldX = child.getLeft();
    int oldY = child.getTop();
    // 上一次动画可能还没跑完：用「当前视觉位置 + 新旧布局差」重新起跑，
    // 连续换位时不会先跳回旧落点，再弹一次，看起来就像被手指推着走。
    float currentX = child.getTranslationX();
    float currentY = child.getTranslationY();
    boolean wasLaidOut = laidOut.contains(child);
    child.layout(x, y, x + width, y + height);
    laidOut.add(child);
    if (!animateChanges || !wasLaidOut) {
      if (!wasLaidOut) {
        child.setTranslationX(0f);
        child.setTranslationY(0f);
      }
      return;
    }
    if (oldX == x && oldY == y && currentX == 0f && currentY == 0f) return;
    child.animate().cancel();
    // 落手后的拖动块仍带位移；即使新落点与旧落点相同，也要弹回 0。
    child.setTranslationX(currentX + oldX - x);
    child.setTranslationY(currentY + oldY - y);
    child
        .animate()
        .translationX(0f)
        .translationY(0f)
        .setDuration(320)
        .setInterpolator(spring)
        .start();
  }

  private LayoutParams paramsOf(View child) {
    ViewGroup.LayoutParams value = child.getLayoutParams();
    if (value instanceof LayoutParams) return (LayoutParams) value;
    LayoutParams params = new LayoutParams(1);
    child.setLayoutParams(params);
    return params;
  }
}
