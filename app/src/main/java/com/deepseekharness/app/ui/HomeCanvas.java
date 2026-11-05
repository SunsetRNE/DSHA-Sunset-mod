package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.module.HomeModule;
import com.deepseekharness.app.util.HomeLayoutStore;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主页模块画布：主页上的每一块都是可编辑模块，直接在主页里拖动、改尺寸、收起、复位。
 *
 * <p>模块的真实 View 由 {@code fragment_launch} 提供并保留原有 id，这里只负责把它们
 * 摆到行列里、包一层编辑装饰。<b>不重建、不替换任何业务控件</b>，所以启动按钮、
 * 状态轮询和日志跟随都照旧工作。
 *
 * <p>编辑态与运行态分开：非编辑时画布只是一个按偏好排序的垂直列表，长按任意模块进入编辑；
 * 编辑态下整块被触摸监听接管（不会误触真实业务按钮），拖动落点写回 {@link HomeLayoutStore}。
 */
final class HomeCanvas {

  /** 画布宿主：编辑态变化 + 卡片模块要跳内置入口时交回。 */
  interface Host extends ModuleCardView.Host {
    void onEditModeChanged(boolean editing);

    /** 「全部恢复」清空布局后回调：宿主顺便重建底栏菜单，避免停在旧顺序。 */
    default void onLayoutReset() {}
  }

  private final Context context;
  private final View root;
  private final LinearLayout sections;
  private final LinearLayout editBar;
  private final Host host;
  private final int touchSlop;
  private final HomeMasonryLayout masonry;
  private final android.view.ScaleGestureDetector scaleDetector;

  private String resizingId;
  private float zoomStart = 1f;
  private float downRawX;

  /** 模块 id → 真实业务 View；跨多次渲染复用同一实例。 */
  private final Map<String, View> modules = new LinkedHashMap<>();

  /** 当前可见模块的包装层，拖动时用来取位置。 */
  private final Map<String, View> tiles = new LinkedHashMap<>();

  /** 本轮渲染用的卡片皮肤来源模块；状态刷新后重套文字色时复用。 */
  private com.deepseekharness.app.module.ModuleCard skinCard;

  private boolean editing;
  private String draggingId;

  /** 编辑态当前选中的卡片；未选中时卡片顶上不出现任何图标。 */
  private String selectedId;

  private float downRawY;
  private TextView hiddenButton;

  /** 拖动中的临时顺序：拖动时实时换位，松手才写回偏好。 */
  private final List<String> dragOrder = new ArrayList<>();

  /** 拖动时占位的虚拟空槽：手指还没松，其它卡就围着它让开。 */
  private DragPlaceholder dragPlaceholder;

  /** 长按多久才拿起卡片；在这之前竖向滑动仍然交给外层正常翻页。 */
  private static final long DRAG_HOLD_MS = 400;

  private String pendingDragId;
  private View pendingDragTile;

  private final android.widget.ScrollView scroller;
  private float dragRawX;
  private float dragRawY;

  private int autoScrollDir;

  HomeCanvas(View root, Host host) {
    this.root = root;
    this.context = root.getContext();
    this.sections = root.findViewById(R.id.launch_sections);
    this.editBar = root.findViewById(R.id.launch_edit_bar);
    this.scroller = root.findViewById(R.id.launch_panel);
    this.host = host;
    this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    this.masonry = new HomeMasonryLayout(context, dp(12), 2);
    this.masonry.setClickable(true);
    this.masonry.setOnClickListener(v -> select(null));
    this.scaleDetector =
        new android.view.ScaleGestureDetector(
            context,
            new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
              @Override
              public boolean onScaleBegin(android.view.ScaleGestureDetector detector) {
                if (!editing || resizingId == null || draggingId != null) return false;
                HomeTile tile = tileOf(resizingId);
                if (tile == null) return false;
                cancelDragArm();
                select(resizingId);
                zoomStart = tile.zoom();
                masonry.setAnimateChanges(false);
                return true;
              }

              @Override
              public boolean onScale(android.view.ScaleGestureDetector detector) {
                HomeTile tile = tileOf(resizingId);
                if (tile == null) return false;
                tile.setZoom(zoomStart * detector.getScaleFactor());
                masonry.requestLayout();
                return true;
              }

              @Override
              public void onScaleEnd(android.view.ScaleGestureDetector detector) {
                HomeTile tile = tileOf(resizingId);
                if (tile == null) return;
                HomeLayoutStore.setZoom(context, resizingId, tile.zoom());
                masonry.setAnimateChanges(true);
                masonry.requestLayout();
              }
            });
    collect();
    collectUserCards();
    bindEditBar();
    render();
  }

  private int dp(int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  private HomeTile tileOf(String id) {
    View view = tiles.get(id);
    return view instanceof HomeTile ? (HomeTile) view : null;
  }

  private void collect() {
    modules.put("actions", root.findViewById(R.id.launch_actions));
    // 三格状态拆成三个独立便签：每格都能单独拖动、缩放，不再被一行绑死。
    modules.put("metric-environment", root.findViewById(R.id.launch_metric_environment));
    modules.put("metric-workbench", root.findViewById(R.id.launch_metric_workbench));
    modules.put("metric-port", root.findViewById(R.id.launch_metric_port));
    modules.put("shortcuts-title", root.findViewById(R.id.launch_shortcuts_title));
    modules.put("shortcuts", root.findViewById(R.id.launch_shortcuts));
    modules.put("logs", root.findViewById(R.id.launch_log_panel));
    modules.values().removeAll(java.util.Collections.singleton(null));
  }

  /** 把用户导入的卡片模块也变成画布上的模块，和内置区块同等对待。 */
  private void collectUserCards() {
    for (com.deepseekharness.app.module.ModuleCard card :
        com.deepseekharness.app.module.ModuleCardStore.load(context)) {
      ModuleCardView view = new ModuleCardView(context, card, host);
      modules.put(card.layoutId(), view);
    }
  }

  private void bindEditBar() {
    TextView title = editBar.findViewById(R.id.launch_edit_title);
    title.setText(UiText.choose("拖边角改大小 · 拖卡换位", "Drag handles · drag to move"));

    hiddenButton = new TextView(context);
    hiddenButton.setTextSize(12);
    hiddenButton.setGravity(android.view.Gravity.CENTER);
    hiddenButton.setMinHeight(dp(30));
    hiddenButton.setPadding(dp(10), 0, dp(10), 0);
    hiddenButton.setTextColor(context.getColor(R.color.shell_accent));
    hiddenButton.setBackgroundResource(R.drawable.bg_shell_edit_bar);
    hiddenButton.setElevation(dp(4));
    hiddenButton.setVisibility(View.GONE);
    LinearLayout.LayoutParams hiddenParams =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    hiddenParams.rightMargin = dp(6);
    editBar.addView(hiddenButton, 1, hiddenParams);
    hiddenButton.setOnClickListener(v -> showHidden());
    UiMotion.pressScale(hiddenButton);

    TextView reset = editBar.findViewById(R.id.launch_edit_reset);
    reset.setText(UiText.choose("全部恢复", "Reset all"));
    UiMotion.pressScale(reset);
    reset.setOnClickListener(
        v -> {
          select(null);
          HomeLayoutStore.reset(context);
          // 「全部恢复」同时清掉底栏顺序与缩放：只重画卡片会让底栏停在旧顺序，
          // 用户会以为恢复没生效。宿主负责重建底栏菜单。
          if (host != null) host.onLayoutReset();
          render();
        });
    TextView done = editBar.findViewById(R.id.launch_edit_done);
    done.setText(UiText.choose("完成", "Done"));
    done.setContentDescription(UiText.choose("退出编辑模式", "Exit edit mode"));
    UiMotion.pressScale(done);
    done.setOnClickListener(v -> setEditing(false));
  }

  private void updateHiddenButton(int count) {
    if (hiddenButton == null) return;
    hiddenButton.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
    hiddenButton.setText(
        UiText.format(UiText.choose("已收起 %s", "Hidden %s"), String.valueOf(count)));
    hiddenButton.setContentDescription(
        UiText.choose("查看已收起的模块并放回主页", "Show hidden modules and restore them"));
  }

  private List<String> hiddenIds() {
    List<String> hidden = new ArrayList<>();
    for (String id : HomeLayoutStore.order(context)) {
      if (modules.containsKey(id) && HomeLayoutStore.isHidden(context, id)) hidden.add(id);
    }
    return hidden;
  }

  private void showHidden() {
    List<String> hidden = hiddenIds();
    if (hidden.isEmpty()) return;
    String[] names = new String[hidden.size()];
    String[] ids = hidden.toArray(new String[0]);
    for (int i = 0; i < ids.length; i++) {
      HomeModule meta = HomeModule.of(ids[i]);
      names[i] = meta != null ? UiText.choose(meta.titleZh, meta.titleEn) : userCardName(ids[i]);
    }
    new DshaDialogBuilder(context, true)
        .setTitle(UiText.choose("已收起 · 点一项放回主页", "Hidden · tap to restore"))
        .setItems(
            names,
            (dialog, which) -> {
              HomeLayoutStore.setHidden(context, ids[which], false);
              render();
            })
        .setNegativeButton(UiText.choose("关闭", "Close"), null)
        .show();
  }

  boolean isEditing() {
    return editing;
  }

  /** 外部（例如系统返回键）请求退出编辑。 */
  boolean exitEditing() {
    if (!editing) return false;
    setEditing(false);
    return true;
  }

  void setEditing(boolean value) {
    if (editing == value) return;
    editing = value;
    selectedId = null;
    showEditBar(value);
    render();
    if (value) playEnterMotion();
    if (host != null) host.onEditModeChanged(value);
  }

  /** 编辑工具条滑入/淡出，退出时不拖泥带水。 */
  private void showEditBar(boolean visible) {
    if (visible) {
      editBar.setVisibility(View.VISIBLE);
      if (UiMotion.animationsEnabled(context)) {
        editBar.setAlpha(0f);
        editBar.setTranslationY(-dp(10));
        editBar
            .animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240)
            .setInterpolator(new android.view.animation.OvershootInterpolator(1.1f))
            .start();
      } else {
        editBar.setAlpha(1f);
        editBar.setTranslationY(0f);
      }
      return;
    }
    if (UiMotion.animationsEnabled(context)) {
      editBar
          .animate()
          .alpha(0f)
          .translationY(-dp(10))
          .setDuration(150)
          .setInterpolator(UiMotion.standard())
          .start();
      editBar.postDelayed(
          () -> {
            if (!editing) {
              editBar.setVisibility(View.GONE);
              editBar.setAlpha(1f);
              editBar.setTranslationY(0f);
            }
          },
          160);
    } else {
      editBar.setVisibility(View.GONE);
      editBar.setAlpha(1f);
      editBar.setTranslationY(0f);
    }
  }

  /** 容器里新写的卡片进来后重建：先清掉旧卡片视图，再重新收一次。 */
  void reloadUserCards() {
    java.util.List<String> stale = new ArrayList<>();
    for (String id : modules.keySet())
      if (com.deepseekharness.app.module.ModuleCard.isUserModule(id)) stale.add(id);
    for (String id : stale) modules.remove(id);
    collectUserCards();
    render();
  }

  /** 按当前偏好重建画布。可在任意时刻重复调用。 */
  void render() {
    resetDragArtifacts();
    detachModules();
    // 编辑工具条悬浮在内容上方，编辑态给画布补一段顶部留白，避免压住第一个模块。
    int baseTop = context.getResources().getDimensionPixelSize(R.dimen.shell_launch_page_top);
    int baseBottom = context.getResources().getDimensionPixelSize(R.dimen.shell_launch_page_bottom);
    sections.setPaddingRelative(
        sections.getPaddingStart(),
        editing ? baseTop + dp(56) : baseTop,
        sections.getPaddingEnd(),
        baseBottom);
    sections.removeAllViews();
    tiles.clear();

    // 新导入的卡片还没进 order，追加到末尾，让它立刻出现在主页。
    List<String> order = new ArrayList<>(HomeLayoutStore.order(context));
    boolean appended = false;
    for (String id : modules.keySet()) {
      if (!order.contains(id)) {
        order.add(id);
        appended = true;
      }
    }
    if (appended) HomeLayoutStore.setOrder(context, order);

    List<String> visible = new ArrayList<>();
    List<String> hidden = new ArrayList<>();
    for (String id : order) {
      if (!modules.containsKey(id)) continue;
      (HomeLayoutStore.isHidden(context, id) ? hidden : visible).add(id);
    }

    // 默认是流式便签：卡片始终紧凑排布，不会因为某张被拖走而留下大片空白。
    if (selectedId != null && !visible.contains(selectedId)) selectedId = null;
    // 卡片皮肤：取第一个启用的 ui.skin 模块，随每轮渲染应用到新 tile。
    skinCard = com.deepseekharness.app.module.ModuleCardStore.skinCard(context);
    List<View> ordered = new ArrayList<>();
    for (String id : visible) {
      HomeTile tile = buildTile(id);
      HomeCardSkin.apply(context, skinCard, id, tile.content());
      HomeMasonryLayout.LayoutParams params =
          new HomeMasonryLayout.LayoutParams(HomeLayoutStore.widthFraction(context, id));
      masonry.addView(tile, params);
      ordered.add(tile);
    }
    masonry.setAnimateChanges(false);
    masonry.setFlow(ordered);
    masonry.post(() -> masonry.setAnimateChanges(true));
    sections.addView(
        masonry,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    // 收起的模块仍留在视图树里（GONE 容器）：LaunchFragment 后续还要按 id 绑定它们，
    // 直接分离会让 findViewById 返回 null，主页一隐藏模块就崩。
    LinearLayout parked = new LinearLayout(context);
    parked.setOrientation(LinearLayout.VERTICAL);
    parked.setVisibility(View.GONE);
    for (Map.Entry<String, View> entry : modules.entrySet()) {
      if (tiles.containsKey(entry.getKey())) continue;
      parked.addView(
          entry.getValue(),
          new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
    sections.addView(
        parked,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    if (editing) updateHiddenButton(hidden.size());
  }

  /**
   * 状态轮询每秒会把工作台值文字改回主题默认色；主页状态刷新后调用这里，
   * 只重套文字与图标颜色（背景不动），保证深色皮肤上的文字一直可读。
   */
  void reapplyCardSkins() {
    if (skinCard == null || skinCard.skin == null || skinCard.skin.homeCards.isEmpty()) return;
    for (Map.Entry<String, View> entry : tiles.entrySet()) {
      View module =
          entry.getValue() instanceof HomeTile ? ((HomeTile) entry.getValue()).content() : null;
      if (module != null) HomeCardSkin.applyTextAndTint(context, skinCard, entry.getKey(), module);
    }
  }

  private void detachModules() {
    List<View> all = new ArrayList<>(modules.values());
    all.addAll(tiles.values());
    for (View view : all) {
      ViewGroup parent = (ViewGroup) view.getParent();
      if (parent != null) parent.removeView(view);
    }
  }

  private HomeTile buildTile(String id) {
    View module = modules.get(id);
    HomeTile tile = new HomeTile(context, module);
    tile.setZoom(HomeLayoutStore.zoom(context, id));
    tiles.put(id, tile);

    if (!editing) {
      // 非编辑态：长按任意模块直接进编辑，不改变任何原有点击行为。
      tile.setLongClickable(true);
      tile.setOnLongClickListener(
          v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            setEditing(true);
            return true;
          });
      return tile;
    }

    // 编辑态用透明层接管整卡触摸，业务按钮不会被误触；
    // 所有卡片预留一条空带放选中后的动作，未选中时不显示任何图标。
    tile.setChromeTop(dp(36));
    tile.enableEditBody((v, event) -> onTileTouch(id, tile, event));
    if (id.equals(selectedId))
      tile.setSelectedForResize(true, resizeListener(id), () -> resetCard(id), () -> hideCard(id));
    return tile;
  }

  /** 选中/取消选中一张卡；未选中时所有卡片都保持干净的原始外观。 */
  private void select(String id) {
    if (!editing || java.util.Objects.equals(selectedId, id)) return;
    HomeTile previous = tileOf(selectedId);
    if (previous != null) previous.setSelectedForResize(false, null, null, null);
    selectedId = id;
    HomeTile tile = tileOf(id);
    if (tile == null) return;
    tile.setSelectedForResize(true, resizeListener(id), () -> resetCard(id), () -> hideCard(id));
    tile.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
  }

  /** 手柄实时改宽高：只更新内存和布局，松手才写回偏好。 */
  private HomeTile.Listener resizeListener(String id) {
    return new HomeTile.Listener() {
      @Override
      public void onResizeStart(HomeTile tile) {
        masonry.setAnimateChanges(false);
      }

      @Override
      public void onResize(HomeTile tile, float widthFraction, float zoom) {
        HomeMasonryLayout.LayoutParams params =
            (HomeMasonryLayout.LayoutParams) tile.getLayoutParams();
        if (params != null) {
          params.widthFraction =
              Math.max(
                  HomeLayoutStore.WIDTH_MIN, Math.min(HomeLayoutStore.WIDTH_MAX, widthFraction));
          params.span = params.widthFraction >= 0.75f ? 2 : 1;
        }
        tile.setZoom(zoom);
        masonry.requestLayout();
      }

      @Override
      public void onResizeEnd(HomeTile tile) {
        HomeMasonryLayout.LayoutParams params =
            (HomeMasonryLayout.LayoutParams) tile.getLayoutParams();
        if (params != null) HomeLayoutStore.setWidthFraction(context, id, params.widthFraction);
        HomeLayoutStore.setZoom(context, id, tile.zoom());
        // 松手后其它卡片用弹簧动画落到最终位置，拖动过程中则即时跟手。
        masonry.setAnimateChanges(true);
        masonry.requestLayout();
      }
    };
  }

  private void resetCard(String id) {
    HomeLayoutStore.resetCard(context, id);
    render();
  }

  private void hideCard(String id) {
    HomeLayoutStore.setHidden(context, id, true);
    selectedId = null;
    render();
  }

  /** 进入编辑态时卡片依次轻微弹起，像从桌面被「拿起来」，而不是瞬间换一套木头视图。 */
  private void playEnterMotion() {
    if (!UiMotion.animationsEnabled(context)) return;
    final float lift = dp(16);
    masonry.post(
        () -> {
          int delay = 0;
          for (View tile : tiles.values()) {
            if (tile.getVisibility() != View.VISIBLE) continue;
            tile.setScaleX(0.92f);
            tile.setScaleY(0.92f);
            tile.setAlpha(0.35f);
            tile.setTranslationY(lift);
            tile.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(420)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                .start();
            delay += 30;
          }
        });
  }

  // ---------------------------------------------------------------- 拖动

  /**
   * 编辑态下由整卡透明层接管触摸：轻点选中，按住 0.4 秒才拿起卡片；
   * 按住之前的竖向滑动仍交给外层正常翻页。边角手柄由 {@link HomeTile.ResizeOverlay}
   * 先接管，不会和换位打架。
   */
  private boolean onTileTouch(String id, View tile, MotionEvent event) {
    if (editing) scaleDetector.onTouchEvent(event);
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        downRawY = event.getRawY();
        downRawX = event.getRawX();
        resizingId = editing ? id : null;
        // 按住 0.4 秒才拿起卡片；在此之前竖向滑动仍能正常翻页。
        // 不能在按下时就锁父容器 —— 那会让编辑态彻底滑不动（真实踩过）。
        // 只有真正拿起（beginDrag）之后才锁，那时才需要独占手势。
        if (editing) scheduleDragArm(id, tile);
        return true;
      case MotionEvent.ACTION_POINTER_DOWN:
        cancelDragArm();
        return true;
      case MotionEvent.ACTION_MOVE:
        if (scaleDetector.isInProgress()) return true;
        if (draggingId == null) {
          if (Math.abs(event.getRawX() - downRawX) > touchSlop
              || Math.abs(event.getRawY() - downRawY) > touchSlop) {
            // 还没到长按时长就滑动了：这是翻页/滑动，取消拿起。
            cancelDragArm();
          }
          return true;
        }
        updateDrag(event.getRawX(), event.getRawY(), tile);
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        cancelDragArm();
        if (draggingId != null) {
          finishDrag();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP
            && Math.abs(event.getRawX() - downRawX) <= touchSlop
            && Math.abs(event.getRawY() - downRawY) <= touchSlop) {
          select(id);
        }
        resizingId = null;
        if (tile.getParent() != null) tile.getParent().requestDisallowInterceptTouchEvent(false);
        return true;
      default:
        return false;
    }
  }

  private void scheduleDragArm(String id, View tile) {
    cancelDragArm();
    pendingDragId = id;
    pendingDragTile = tile;
    masonry.postDelayed(armDragRunnable, DRAG_HOLD_MS);
  }

  private void cancelDragArm() {
    pendingDragId = null;
    pendingDragTile = null;
    masonry.removeCallbacks(armDragRunnable);
  }

  private final Runnable armDragRunnable =
      new Runnable() {
        @Override
        public void run() {
          String id = pendingDragId;
          View tile = pendingDragTile;
          pendingDragId = null;
          pendingDragTile = null;
          if (id == null || tile == null || !editing || draggingId != null) return;
          beginDrag(id, tile);
        }
      };

  private void beginDrag(String id, View tile) {
    if (!editing || draggingId != null) return;
    // 桌面式拖动：被拖的卡浮起，其它卡实时让位（不等松手）。
    masonry.setAnimateChanges(true);
    draggingId = id;
    dragOrder.clear();
    dragOrder.addAll(HomeLayoutStore.order(context));
    if (!dragOrder.contains(id)) dragOrder.add(id);
    // 虚拟空槽和卡片同宽同高：其它卡围着它让开，松手前就能看到落点。
    HomeMasonryLayout.LayoutParams draggedParams =
        (HomeMasonryLayout.LayoutParams) tile.getLayoutParams();
    float widthFraction = draggedParams != null ? draggedParams.widthFraction : 1f;
    dragPlaceholder = new DragPlaceholder(context, context.getColor(R.color.shell_accent));
    // 空槽记住被拖卡的自然高度：流式排版用 UNSPECIFIED 测它时，没有这个高度
    // 它会塌成 1px，其它卡就压进空槽里（拖动中叠卡的来源之一）。
    int naturalHeight =
        tile instanceof HomeTile
            ? ((HomeTile) tile).naturalContentHeight()
            : Math.max(1, tile.getHeight());
    dragPlaceholder.setForcedHeight(naturalHeight + tile.getPaddingTop() + tile.getPaddingBottom());
    masonry.addView(dragPlaceholder, new HomeMasonryLayout.LayoutParams(widthFraction));
    // 卡片留在原容器里（触摸链路不变），由 masonry 标记为 floating：
    // place() 对 floating 不做任何位移动画，所以它的 translation 只由手指决定。
    masonry.setFloating(tile);
    applyDragFlow();
    tile.setElevation(dp(20));
    if (UiMotion.animationsEnabled(context)) {
      tile.animate()
          .scaleX(1.04f)
          .scaleY(1.04f)
          .alpha(0.96f)
          .setDuration(160)
          .setInterpolator(UiMotion.standard())
          .start();
    } else {
      tile.setScaleX(1.04f);
      tile.setScaleY(1.04f);
      tile.setAlpha(0.96f);
    }
    tile.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    if (tile.getParent() != null) tile.getParent().requestDisallowInterceptTouchEvent(true);
  }

  /**
   * 把被拖的卡片从原布局搬到悬浮层（照 Launcher3 的 DragLayer 做法）。
   *
   * <p>关键点：先记下它在**屏幕上的绝对矩形**，再用这个矩形在新父容器里定位。
   * 从此它的位置只由手指决定，占位空槽让邻卡重排也不会带偏它。
   */
  private void updateDrag(float rawX, float rawY, View tile) {
    // 跟手：位移量 = 手指相对按下点的位移。因为 place() 对 floating 不再做动画，
    // getLeft() 在拖动期间也不会被弹走，这个基准是稳定的。
    tile.setTranslationX(rawX - downRawX);
    tile.setTranslationY(rawY - downRawY);
    dragRawX = rawX;
    dragRawY = rawY;
    updateAutoScroll(rawY);
    reorderLive();
  }

  /** 只把「除被拖卡以外的视图」按临时顺序排；被拖卡的位置由占位空槽代替。 */
  private void applyDragFlow() {
    List<View> ordered = new ArrayList<>();
    for (String orderId : dragOrder) {
      if (orderId.equals(draggingId)) {
        if (dragPlaceholder != null) ordered.add(dragPlaceholder);
        continue;
      }
      View tile = tiles.get(orderId);
      if (tile != null) ordered.add(tile);
    }
    masonry.setFlow(ordered);
  }

  /**
   * 手指移动时实时决定插到哪一位，并把占位空槽挪过去，其它卡立刻让开。
   *
   * <p>判断用当前布局，不用拖动起点的旧坐标：手指还在空槽里就不动（不来回跳），
   * 一旦离开空槽就按最近的那张卡重新决定位置。插前/插后按**拖动方向**定：
   * 向左/上拖 → 插到它前面（空槽正好落在你压住的卡的位置）；向右/下拖 → 插到它后面。
   * 这样无论往哪个方向压到邻居身上，虚线空槽都在手指下面。
   */
  private void reorderLive() {
    if (draggingId == null || dragPlaceholder == null || dragOrder.isEmpty()) return;
    int[] origin = new int[2];
    masonry.getLocationOnScreen(origin);
    float dropX = dragRawX - origin[0];
    float dropY = dragRawY - origin[1];
    Rect gap =
        new Rect(
            dragPlaceholder.getLeft(),
            dragPlaceholder.getTop(),
            dragPlaceholder.getLeft() + dragPlaceholder.getWidth(),
            dragPlaceholder.getTop() + dragPlaceholder.getHeight());
    int slack = dp(8);
    gap.inset(-slack, -slack);
    if (gap.contains((int) dropX, (int) dropY)) return;
    String nearest = null;
    double nearestDistance = Double.MAX_VALUE;
    for (String otherId : visibleOrder()) {
      if (otherId.equals(draggingId)) continue;
      View other = tiles.get(otherId);
      if (other == null || other.getWidth() <= 0 || other.getHeight() <= 0) continue;
      Rect rect =
          new Rect(
              other.getLeft(),
              other.getTop(),
              other.getLeft() + other.getWidth(),
              other.getTop() + other.getHeight());
      double distance = Math.pow(rect.centerX() - dropX, 2) + Math.pow(rect.centerY() - dropY, 2);
      if (distance < nearestDistance) {
        nearestDistance = distance;
        nearest = otherId;
      }
    }
    int current = dragOrder.indexOf(draggingId);
    if (current < 0) return;
    dragOrder.remove(current);
    int index = dragOrder.size();
    if (nearest != null) {
      int at = dragOrder.indexOf(nearest);
      if (at >= 0) {
        boolean horizontal = Math.abs(dragRawX - downRawX) >= Math.abs(dragRawY - downRawY);
        boolean after = horizontal ? dragRawX > downRawX : dragRawY > downRawY;
        index = after ? at + 1 : at;
      }
    }
    index = Math.max(0, Math.min(dragOrder.size(), index));
    dragOrder.add(index, draggingId);
    if (index != current) applyDragFlow();
  }

  /** 手指靠近滚动区域上下边缘时持续滚动，卡片才能被拖到最底下/最高处。 */
  private void updateAutoScroll(float rawY) {
    if (scroller == null || scroller.getHeight() <= 0) return;
    int[] loc = new int[2];
    scroller.getLocationOnScreen(loc);
    int top = loc[1];
    int bottom = top + scroller.getHeight();
    int edge = dp(80);
    int dir = rawY < top + edge ? -1 : rawY > bottom - edge ? 1 : 0;
    if (dir == autoScrollDir) return;
    autoScrollDir = dir;
    masonry.removeCallbacks(autoScrollTick);
    if (dir != 0) masonry.postDelayed(autoScrollTick, 20);
  }

  private final Runnable autoScrollTick =
      new Runnable() {
        @Override
        public void run() {
          if (draggingId == null || autoScrollDir == 0 || scroller == null) return;
          int before = scroller.getScrollY();
          scroller.scrollBy(0, autoScrollDir * dp(12));
          if (scroller.getScrollY() == before) {
            autoScrollDir = 0;
            return;
          }
          reorderLive();
          masonry.postDelayed(this, 50);
        }
      };

  private void stopAutoScroll() {
    autoScrollDir = 0;
    masonry.removeCallbacks(autoScrollTick);
  }

  /** 画布重建或退出编辑时，把拖动的临时状态（空槽、浮动视图、临时顺序）全部清掉。 */
  private void resetDragArtifacts() {
    cancelDragArm();
    stopAutoScroll();
    if (dragPlaceholder != null) {
      masonry.removeView(dragPlaceholder);
      dragPlaceholder = null;
    }
    masonry.setFloating(null);
    draggingId = null;
    dragOrder.clear();
  }

  private void finishDrag() {
    String id = draggingId;
    HomeTile tile = tileOf(id);
    stopAutoScroll();
    if (dragPlaceholder != null) {
      masonry.removeView(dragPlaceholder);
      dragPlaceholder = null;
    }
    masonry.setFloating(null);
    if (tile == null) {
      draggingId = null;
      dragOrder.clear();
      return;
    }
    if (!dragOrder.isEmpty()) {
      if (!dragOrder.contains(id)) dragOrder.add(id);
      HomeLayoutStore.setOrder(context, dragOrder);
      // 旧版本写下的绝对位置全部清掉：本轮起只认顺序。
      for (String visibleId : visibleOrder()) HomeLayoutStore.clearPosition(context, visibleId);
    }
    List<View> ordered = new ArrayList<>();
    for (String orderId : dragOrder) {
      View view = tiles.get(orderId);
      if (view != null) ordered.add(view);
    }
    masonry.setFlow(ordered);
    tile.setElevation(0f);
    tile.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    if (tile.getParent() != null) tile.getParent().requestDisallowInterceptTouchEvent(false);
    draggingId = null;
    dragOrder.clear();
    // 交给网格 place() 带当前位置弹到新槽位；收尾时再补一个缩放回弹。
    masonry.setAnimateChanges(true);
    masonry.requestLayout();
    tile.postDelayed(
        () -> {
          if (tile.getParent() == null) return;
          if (UiMotion.animationsEnabled(context)) {
            tile.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(260)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.1f))
                .start();
          } else {
            tile.setScaleX(1f);
            tile.setScaleY(1f);
            tile.setAlpha(1f);
          }
        },
        340);
  }

  private String userCardName(String id) {
    if (com.deepseekharness.app.module.ModuleCard.isUserModule(id)) {
      for (com.deepseekharness.app.module.ModuleCard card :
          com.deepseekharness.app.module.ModuleCardStore.load(context)) {
        if (card.layoutId().equals(id)) return card.name;
      }
    }
    return id;
  }

  private List<String> visibleOrder() {
    List<String> visible = new ArrayList<>();
    for (String id : HomeLayoutStore.order(context)) {
      if (!modules.containsKey(id) || HomeLayoutStore.isHidden(context, id)) continue;
      visible.add(id);
    }
    return visible;
  }

  /** 供无障碍/自动化定位当前可见模块顺序。 */
  List<String> visibleIds() {
    return visibleOrder();
  }

  Rect boundsOf(String id) {
    View tile = tiles.get(id);
    if (tile == null) return new Rect();
    int[] pos = new int[2];
    tile.getLocationOnScreen(pos);
    return new Rect(pos[0], pos[1], pos[0] + tile.getWidth(), pos[1] + tile.getHeight());
  }

  /** 编辑态下由测试/自动化直接拿到卡片外壳（不是里面的业务控件）。 */
  View tileView(String id) {
    return tiles.get(id);
  }

  String selectedId() {
    return selectedId;
  }

  /** 仅供测试：长按是否已经把卡片拿起。 */
  boolean isDraggingForTest() {
    return draggingId != null;
  }

  /** 拖动时占位的虚线空槽：它参与流式布局，其它卡就会提前让开，松手前也能看到落点。 */
  private static final class DragPlaceholder extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 被拖卡的自然高度：行式排版用 UNSPECIFIED 测本视图时用这个高度顶上。 */
    private int forcedHeight;

    DragPlaceholder(Context context, int accent) {
      super(context);
      paint.setColor(accent);
      paint.setAlpha(90);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 1.5f);
      setWillNotDraw(false);
    }

    void setForcedHeight(int height) {
      forcedHeight = Math.max(1, height);
    }

    /**
     * EXACTLY 时格子说了算（行式排版不会给 EXACTLY 高，但保留该约定）；
     * 其余情况用被拖卡的自然高度——空槽必须真的占住那块空间，其它卡才会让位。
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
      int width = MeasureSpec.getSize(widthMeasureSpec);
      int height =
          MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
              ? MeasureSpec.getSize(heightMeasureSpec)
              : forcedHeight;
      setMeasuredDimension(width, Math.max(1, height));
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float inset = getResources().getDisplayMetrics().density * 2f;
      float radius = getResources().getDisplayMetrics().density * 10f;
      canvas.drawRoundRect(
          inset, inset, getWidth() - inset, getHeight() - inset, radius, radius, paint);
    }
  }
}
