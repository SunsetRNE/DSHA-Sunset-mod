package com.deepseekharness.app.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.PtySession;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.TerminalRuntimeProtection;
import com.deepseekharness.app.core.TerminalSessionOwner;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.TerminalSession;
import com.deepseekharness.app.util.TerminalTabs;
import com.deepseekharness.app.util.TerminalWorkspacePolicy;
import com.deepseekharness.app.util.UiText;
import java.util.concurrent.atomic.AtomicInteger;

/** 同 task 的终端工作台。页面只持有视图，结束进程必须明确指定会话并核验。 */
public class TerminalActivity extends AppCompatActivity implements TerminalPane.Host {
  private static final String WORKBENCH = "terminal_workbench";
  private static final String SESSIONS_OPEN = "sessions_open";
  private final TerminalSessionOwner owner = TerminalSessionOwner.shared();
  private FrameLayout root, content, drawerHost;
  private LinearLayout column,
      toolbar,
      body,
      sessionPanel,
      sessionList,
      sessionHeader,
      sessionFooter;
  private LinearLayout footerActionsWide, footerActionsDense;
  private ScrollView sessionScroll;
  private View sessionScrim, inlineDivider, toolbarDivider;
  private TextView title, status, menuButton, sessionClose, sessionSummary, sessionSubtitle;
  private TextView panelSecondary,
      panelNotice,
      newSessionButton,
      closeAllButton,
      settingsButton,
      helpButton;
  private TerminalFloatingMenu ball;
  private AlertDialog panel;
  private boolean workbench, imeVisible, previousProcess, changingMode, expanded, sessionsRequested;
  private boolean compactHeight, denseFooter, sessionsRendered, presentationScheduled;
  private volatile boolean closeAllRunning, closeAllDispatching;
  private final AtomicInteger closeAllPending = new AtomicInteger();
  private volatile String closeAllEpoch = "";
  private int closeAllTotal, closeAllRun;
  private final java.util.Set<Long> closeAllPtyIds = new java.util.HashSet<>();
  private final java.util.Set<Long> closeAllSimpleIds = new java.util.HashSet<>();
  private String closeAllNotice = "";
  private Runnable closeAllWatchdog;
  private final Runnable protectionChanged = () -> runOnUiThread(this::onTerminalPaneChanged);

  @Override
  protected void onCreate(@Nullable Bundle saved) {
    // 新进程不恢复旧会话的Fragment参数或旧异步输入身份。
    previousProcess =
        saved != null && !owner.ownerEpoch().equals(saved.getString("terminal_owner"));
    super.onCreate(previousProcess ? null : saved);
    if (!new ConfigStore(this).isWelcomed()) {
      startActivity(new Intent(this, MainActivity.class));
      finish();
      return;
    }
    workbench = getSharedPreferences("deepseekharness", MODE_PRIVATE).getBoolean(WORKBENCH, false);
    sessionsRequested = saved != null && !previousProcess && saved.getBoolean(SESSIONS_OPEN, false);
    buildWorkspace();
    renderSessions();
    if (saved != null)
      ball.restore(saved.getBoolean("ball_right", true), saved.getFloat("ball_fraction", .6f));
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new androidx.activity.OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                if (panel != null && panel.isShowing()) {
                  panel.dismiss();
                  return;
                }
                if (sessionsDismissible()) {
                  closeSessions();
                  return;
                }
                TerminalPane pane = pane();
                if (pane != null && pane.selectionBack()) return;
                if (imeVisible) {
                  WindowCompat.getInsetsController(getWindow(), root)
                      .hide(WindowInsetsCompat.Type.ime());
                  return;
                }
                leavePage(); // 仅关闭视图；绝不shutdown、exit、Ctrl+D或stopWeb。
              }
            });
    if (getSupportFragmentManager().findFragmentById(R.id.terminal_content) == null) {
      Fragment fragment = createPane(PtyTerminalFragment.preferred(this), previousProcess);
      getSupportFragmentManager()
          .beginTransaction()
          .replace(R.id.terminal_content, fragment)
          .commitNow();
    }
    onTerminalPaneChanged();
  }

  private void buildWorkspace() {
    root = new FrameLayout(this);
    root.setId(R.id.terminal_workspace);
    root.setBackgroundColor(getColor(R.color.terminal_surface));
    if (android.os.Build.VERSION.SDK_INT >= 29) root.setForceDarkAllowed(false);
    column = new LinearLayout(this);
    column.setOrientation(LinearLayout.VERTICAL);
    root.addView(column, new FrameLayout.LayoutParams(-1, -1));
    toolbar = new LinearLayout(this);
    toolbar.setId(R.id.terminal_workspace_toolbar);
    toolbar.setGravity(Gravity.CENTER_VERTICAL);
    toolbar.setBackgroundColor(getColor(R.color.terminal_panel));
    toolbar.addView(toolbarButton(UiText.choose("返回", "Back"), this::leavePage));
    title = toolbarLabel("");
    toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    settingsButton = toolbarButton(UiText.choose("设置", "Display"), this::showDisplaySettings);
    settingsButton.setId(R.id.terminal_workspace_settings);
    settingsButton.setContentDescription(UiText.choose("终端显示设置", "Terminal display settings"));
    toolbar.addView(settingsButton);
    helpButton = toolbarButton(UiText.choose("说明", "Help"), this::showSessionHelp);
    helpButton.setId(R.id.terminal_workspace_help);
    helpButton.setContentDescription(UiText.choose("终端与会话说明", "Terminal and sessions help"));
    toolbar.addView(helpButton);
    menuButton = toolbarButton(UiText.choose("菜单", "Menu"), this::toggleSessions);
    menuButton.setId(R.id.terminal_workspace_menu);
    menuButton.setContentDescription(UiText.choose("打开会话侧栏", "Open sessions sidebar"));
    toolbar.addView(menuButton);
    column.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
    toolbarDivider = divider(R.color.terminal_line);
    column.addView(toolbarDivider, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
    status = label("");
    status.setId(R.id.terminal_workspace_status);
    status.setTextColor(getColor(R.color.terminal_foreground));
    status.setPadding(dp(8), dp(4), dp(8), dp(4));
    column.addView(status, new LinearLayout.LayoutParams(-1, -2));
    body = new LinearLayout(this);
    body.setOrientation(LinearLayout.HORIZONTAL);
    column.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    content = new FrameLayout(this);
    content.setId(R.id.terminal_content);
    content.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    body.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
    sessionPanel = buildSessionsPanel();
    ball = new TerminalFloatingMenu(this);
    ball.setOnClickListener(v -> toggleSessions());
    drawerHost = new FrameLayout(this);
    drawerHost.setId(R.id.terminal_sessions_host);
    drawerHost.setVisibility(View.GONE);
    sessionScrim = new View(this);
    sessionScrim.setId(R.id.terminal_sessions_scrim);
    sessionScrim.setBackgroundColor(getColor(R.color.terminal_scrim));
    sessionScrim.setClickable(true);
    sessionScrim.setOnClickListener(v -> closeSessions());
    sessionScrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    drawerHost.addView(sessionScrim, new FrameLayout.LayoutParams(-1, -1));
    drawerHost.addView(
        sessionPanel,
        new FrameLayout.LayoutParams(
            dp(TerminalWorkspacePolicy.drawerMaxWidth()), -1, Gravity.START));
    root.addView(ball);
    root.addView(drawerHost, new FrameLayout.LayoutParams(-1, -1));
    setContentView(root);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
    getWindow().setNavigationBarColor(getColor(R.color.terminal_surface));
    var bars = WindowCompat.getInsetsController(getWindow(), root);
    bars.setAppearanceLightStatusBars(false);
    bars.setAppearanceLightNavigationBars(false);
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (view, insets) -> {
          var safe =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          int keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
          imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
          root.setPadding(safe.left, safe.top, safe.right, Math.max(safe.bottom, keyboard));
          ball.reserveBottom(root.getPaddingBottom() + dp(100));
          ball.constrain(); // IME仅改padding时外框未变，也必须重约束菜单球。
          // IME 只改 padding 时根视图 bounds 不变，onLayoutChange 不可依赖；短窗形态必须重判。
          schedulePresentation();
          return WindowInsetsCompat.CONSUMED;
        });
    root.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> {
          applyPresentation();
          // 球的坐标系是根窗口，底部安全区/键盘和两排按键都留出。
          ball.reserveBottom(root.getPaddingBottom() + dp(100));
          ball.constrain();
        });
    ViewCompat.requestApplyInsets(root);
    applyPresentation();
  }

  /** 侧栏内容只由宿主渲染；抽屉、平板内联与宽屏常驻复用同一份视图。 */
  private LinearLayout buildSessionsPanel() {
    LinearLayout panel = new LinearLayout(this);
    panel.setId(R.id.terminal_sessions_panel);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setBackgroundColor(getColor(R.color.terminal_panel));
    panel.setFocusable(true);
    panel.setVisibility(View.GONE);
    sessionHeader = new LinearLayout(this);
    sessionHeader.setGravity(Gravity.CENTER_VERTICAL);
    sessionHeader.setPaddingRelative(dp(14), dp(12), dp(6), dp(8));
    LinearLayout headings = new LinearLayout(this);
    headings.setOrientation(LinearLayout.VERTICAL);
    TextView heading =
        panelText(UiText.text("会话"), 17, getColor(R.color.terminal_foreground), true);
    sessionSubtitle =
        panelText(
            UiText.choose("独立会话 · 返回不结束进程", "Independent sessions · back keeps processes"),
            11,
            getColor(R.color.terminal_muted),
            false);
    sessionSubtitle.setId(R.id.terminal_sessions_subtitle);
    sessionSubtitle.setPadding(0, dp(2), 0, 0);
    headings.addView(heading);
    headings.addView(sessionSubtitle);
    sessionHeader.addView(headings, new LinearLayout.LayoutParams(0, -2, 1));
    sessionClose =
        panelIcon("×", UiText.choose("收起会话侧栏", "Close sessions sidebar"), this::closeSessions);
    sessionClose.setId(R.id.terminal_sessions_close);
    sessionClose.setTextSize(22);
    sessionClose.setFocusableInTouchMode(true);
    sessionHeader.addView(sessionClose, new LinearLayout.LayoutParams(dp(48), dp(48)));
    panel.addView(sessionHeader, new LinearLayout.LayoutParams(-1, -2));
    newSessionButton =
        panelAction(
            UiText.choose("＋ 新建会话", "+ New session"),
            getColor(R.color.terminal_on_accent),
            14,
            this::showCreateChooser);
    newSessionButton.setId(R.id.terminal_sessions_create);
    newSessionButton.setTypeface(Typeface.DEFAULT_BOLD);
    newSessionButton.setBackgroundResource(R.drawable.bg_terminal_primary);
    LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(-1, -2);
    createParams.setMargins(dp(12), dp(4), dp(12), dp(10));
    panel.addView(newSessionButton, createParams);
    sessionScroll = new ScrollView(this);
    sessionScroll.setId(R.id.terminal_sessions_scroll);
    sessionScroll.setFillViewport(true);
    sessionList = new LinearLayout(this);
    sessionList.setOrientation(LinearLayout.VERTICAL);
    sessionList.setPadding(dp(6), dp(4), dp(6), dp(12));
    sessionScroll.addView(sessionList, new FrameLayout.LayoutParams(-1, -2));
    panel.addView(sessionScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    panel.addView(
        divider(R.color.terminal_line), new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
    sessionFooter = new LinearLayout(this);
    sessionFooter.setOrientation(LinearLayout.VERTICAL);
    sessionFooter.setPadding(dp(8), dp(6), dp(8), dp(10));
    LinearLayout summaryRow = new LinearLayout(this);
    summaryRow.setGravity(Gravity.CENTER_VERTICAL);
    summaryRow.setPadding(dp(8), 0, dp(8), 0);
    sessionSummary = panelText("", 11, getColor(R.color.terminal_muted), false);
    sessionSummary.setId(R.id.terminal_sessions_summary);
    sessionSummary.setMaxLines(2);
    sessionSummary.setEllipsize(TextUtils.TruncateAt.END);
    summaryRow.addView(sessionSummary, new LinearLayout.LayoutParams(0, -2, 1));
    closeAllButton =
        panelAction(
            UiText.choose("结束全部", "End all"),
            getColor(R.color.terminal_danger),
            12,
            this::confirmCloseAll);
    closeAllButton.setId(R.id.terminal_sessions_close_all);
    closeAllButton.setContentDescription(
        UiText.choose("结束全部终端会话并核验", "End all terminal sessions and verify"));
    closeAllButton.setVisibility(View.GONE);
    summaryRow.addView(closeAllButton);
    sessionFooter.addView(summaryRow, new LinearLayout.LayoutParams(-1, -2));
    panelNotice = panelText("", 11, getColor(R.color.terminal_warning), false);
    panelNotice.setId(R.id.terminal_sessions_notice);
    panelNotice.setPadding(dp(8), dp(2), dp(8), 0);
    panelNotice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    panelNotice.setVisibility(View.GONE);
    sessionFooter.addView(panelNotice, new LinearLayout.LayoutParams(-1, -2));
    panelSecondary =
        panelText(
            UiText.choose(
                "返回只关闭页面 · 结束进程单独核验", "Back closes the page only · exits are verified separately"),
            11,
            getColor(R.color.terminal_muted),
            false);
    panelSecondary.setId(R.id.terminal_sessions_secondary);
    panelSecondary.setPadding(dp(8), dp(2), dp(8), dp(2));
    sessionFooter.addView(panelSecondary, new LinearLayout.LayoutParams(-1, -2));
    footerActionsWide = new LinearLayout(this);
    footerActionsWide.setOrientation(LinearLayout.VERTICAL);
    footerActionsWide.setId(R.id.terminal_sessions_actions);
    addFooterRow(
        footerActionsWide,
        UiText.choose("返回主界面", "Home"),
        () -> {
          closeSessions();
          leavePage();
        },
        UiText.choose("显示设置", "Display"),
        this::showDisplaySettings);
    addFooterRow(
        footerActionsWide,
        UiText.choose("显示键盘", "Keyboard"),
        () -> {
          closeSessions();
          if (pane() != null) pane().showKeyboard();
        },
        UiText.choose("粘贴文本", "Paste"),
        () -> {
          closeSessions();
          if (pane() != null) pane().paste();
        });
    addFooterRow(
        footerActionsWide,
        UiText.choose("回到输入", "Focus input"),
        () -> {
          closeSessions();
          if (pane() != null) pane().restoreInputFocus();
        },
        UiText.choose("说明", "Help"),
        this::showSessionHelp);
    sessionFooter.addView(footerActionsWide, new LinearLayout.LayoutParams(-1, -2));
    footerActionsDense = new LinearLayout(this);
    footerActionsDense.setOrientation(LinearLayout.VERTICAL);
    footerActionsDense.setId(R.id.terminal_sessions_actions_dense);
    addFooterRow(
        footerActionsDense,
        UiText.choose("返回主界面", "Home"),
        () -> {
          closeSessions();
          leavePage();
        },
        UiText.choose("显示设置", "Display"),
        this::showDisplaySettings,
        UiText.choose("显示键盘", "Keyboard"),
        () -> {
          closeSessions();
          if (pane() != null) pane().showKeyboard();
        });
    addFooterRow(
        footerActionsDense,
        UiText.choose("粘贴文本", "Paste"),
        () -> {
          closeSessions();
          if (pane() != null) pane().paste();
        },
        UiText.choose("回到输入", "Focus input"),
        () -> {
          closeSessions();
          if (pane() != null) pane().restoreInputFocus();
        },
        UiText.choose("说明", "Help"),
        this::showSessionHelp);
    footerActionsDense.setVisibility(View.GONE);
    sessionFooter.addView(footerActionsDense, new LinearLayout.LayoutParams(-1, -2));
    panel.addView(sessionFooter, new LinearLayout.LayoutParams(-1, -2));
    return panel;
  }

  private void addFooterRow(
      LinearLayout footer,
      String leftText,
      Runnable leftAction,
      String rightText,
      Runnable rightAction) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.addView(footerButton(leftText, leftAction, 13), new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(footerButton(rightText, rightAction, 13), new LinearLayout.LayoutParams(0, -2, 1));
    footer.addView(row, new LinearLayout.LayoutParams(-1, -2));
  }

  private void addFooterRow(
      LinearLayout footer,
      String firstText,
      Runnable firstAction,
      String secondText,
      Runnable secondAction,
      String thirdText,
      Runnable thirdAction) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.addView(footerButton(firstText, firstAction, 11), new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(
        footerButton(secondText, secondAction, 11), new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(footerButton(thirdText, thirdAction, 11), new LinearLayout.LayoutParams(0, -2, 1));
    footer.addView(row, new LinearLayout.LayoutParams(-1, -2));
  }

  private View divider(int colorRes) {
    View divider = new View(this);
    divider.setBackgroundColor(getColor(colorRes));
    return divider;
  }

  private TerminalPane pane() {
    Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.terminal_content);
    return fragment instanceof TerminalPane ? (TerminalPane) fragment : null;
  }

  /** 宽度决定内联/抽屉，工作台与极简只在“何时显示”上不同；旋转和分屏都必须重新判定。 */
  private void applyPresentation() {
    if (root == null || sessionPanel == null || menuButton == null || isDestroyed()) return;
    int widthDp = workspaceWidthDp();
    int heightDp = workspaceHeightDp();
    boolean canInline = TerminalWorkspacePolicy.canInline(widthDp);
    expanded = canInline && (workbench || sessionsRequested);
    compactHeight = TerminalWorkspacePolicy.compactHeight(heightDp);
    denseFooter = TerminalWorkspacePolicy.denseFooter(heightDp);
    toolbar.setVisibility(workbench ? View.VISIBLE : View.GONE);
    toolbarDivider.setVisibility(workbench ? View.VISIBLE : View.GONE);
    // 极简内联时面板本身带关闭按钮；隐藏球，避免 RTL/左吸边时叠在侧栏上。
    ball.setVisibility(workbench || (expanded && !workbench) ? View.GONE : View.VISIBLE);
    menuButton.setVisibility(workbench && !expanded ? View.VISIBLE : View.GONE);
    settingsButton.setVisibility(workbench && expanded ? View.VISIBLE : View.GONE);
    helpButton.setVisibility(workbench && expanded ? View.VISIBLE : View.GONE);
    applyCompactChrome();
    if (expanded) {
      showInlinePanel();
    } else if (sessionsRequested) {
      if (drawerShowing()) updateDrawerPresentation();
      else showDrawer(false);
    } else {
      hideSessionsPanel();
    }
    ball.setSessionsExpanded(expanded || sessionsRequested);
  }

  private void schedulePresentation() {
    if (root == null || presentationScheduled) return;
    presentationScheduled = true;
    root.post(
        () -> {
          presentationScheduled = false;
          applyPresentation();
        });
  }

  private void applyCompactChrome() {
    sessionSubtitle.setVisibility(compactHeight ? View.GONE : View.VISIBLE);
    panelSecondary.setVisibility(compactHeight ? View.GONE : View.VISIBLE);
    sessionHeader.setPaddingRelative(
        dp(14), dp(compactHeight ? 8 : 12), dp(6), dp(compactHeight ? 4 : 8));
    sessionFooter.setPadding(dp(8), dp(compactHeight ? 4 : 6), dp(8), dp(compactHeight ? 6 : 10));
    sessionList.setPadding(dp(6), dp(compactHeight ? 0 : 4), dp(6), dp(compactHeight ? 6 : 12));
    footerActionsWide.setVisibility(denseFooter ? View.GONE : View.VISIBLE);
    footerActionsDense.setVisibility(denseFooter ? View.VISIBLE : View.GONE);
  }

  private void showInlinePanel() {
    if (sessionPanel.getParent() != body) {
      sessionPanel.animate().cancel();
      removeSessionPanel();
      body.addView(sessionPanel, 0, new LinearLayout.LayoutParams(inlineWidthPx(), -1));
      ensureInlineDivider();
      renderSessions();
    } else {
      updateInlineWidth();
      ensureInlineDivider();
    }
    sessionPanel.setVisibility(View.VISIBLE);
    sessionPanel.setElevation(0f);
    sessionPanel.setTranslationX(0f);
    sessionClose.setVisibility(workbench ? View.GONE : View.VISIBLE);
    drawerHost.setVisibility(View.GONE);
    setDrawerAccessibility(false);
    ViewCompat.setAccessibilityPaneTitle(sessionPanel, UiText.text("会话侧栏"));
  }

  private void ensureInlineDivider() {
    if (inlineDivider == null) inlineDivider = divider(R.color.terminal_line);
    if (inlineDivider.getParent() != body) {
      body.addView(
          inlineDivider,
          Math.min(1, body.getChildCount()),
          new LinearLayout.LayoutParams(Math.max(1, dp(1)), -1));
    }
    inlineDivider.setVisibility(View.VISIBLE);
  }

  private void updateInlineWidth() {
    if (sessionPanel.getParent() != body) return;
    ViewGroup.LayoutParams params = sessionPanel.getLayoutParams();
    if (params == null) return;
    int width = inlineWidthPx();
    if (params.width != width) {
      params.width = width;
      sessionPanel.setLayoutParams(params);
    }
  }

  private void showDrawer(boolean animate) {
    if (expanded) return;
    removeInlineDivider();
    if (sessionPanel.getParent() != drawerHost) {
      sessionPanel.animate().cancel();
      removeSessionPanel();
      drawerHost.addView(
          sessionPanel, new FrameLayout.LayoutParams(drawerWidthPx(), -1, Gravity.START));
      renderSessions();
    }
    drawerHost.setVisibility(View.VISIBLE);
    sessionScrim.setVisibility(View.VISIBLE);
    sessionPanel.setVisibility(View.VISIBLE);
    sessionClose.setVisibility(View.VISIBLE);
    sessionPanel.setElevation(dp(8));
    setDrawerAccessibility(true);
    ViewCompat.setAccessibilityPaneTitle(sessionPanel, UiText.text("会话侧栏"));
    ball.setSessionsExpanded(true);
    updateDrawerWidth();
    if (animate) {
      sessionPanel.setTranslationX(drawerHiddenTranslation());
      sessionScrim.setAlpha(0f);
      drawerHost.post(
          () -> {
            if (!sessionsRequested || expanded) return;
            sessionPanel.animate().translationX(0f).setDuration(180).start();
            sessionScrim.animate().alpha(1f).setDuration(180).start();
          });
    } else if (sessionPanel.getTranslationX() != 0f) {
      sessionPanel.setTranslationX(0f);
      sessionScrim.setAlpha(1f);
    }
  }

  private boolean drawerShowing() {
    return drawerHost != null
        && drawerHost.getVisibility() == View.VISIBLE
        && sessionPanel.getParent() == drawerHost
        && sessionPanel.getVisibility() == View.VISIBLE;
  }

  private void updateDrawerPresentation() {
    sessionClose.setVisibility(View.VISIBLE);
    sessionPanel.setElevation(dp(8));
    setDrawerAccessibility(true);
    updateDrawerWidth();
    ball.setSessionsExpanded(true);
  }

  /** Android 没有 iOS 的 accessibilityViewIsModal；抽屉打开时隐藏背景的可访问性。 */
  private void setDrawerAccessibility(boolean open) {
    int mode =
        open
            ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO;
    content.setImportantForAccessibility(mode);
    toolbar.setImportantForAccessibility(mode);
    status.setImportantForAccessibility(
        open ? View.IMPORTANT_FOR_ACCESSIBILITY_NO : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    ball.setImportantForAccessibility(
        open ? View.IMPORTANT_FOR_ACCESSIBILITY_NO : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    content.setDescendantFocusability(
        open ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_BEFORE_DESCENDANTS);
    toolbar.setDescendantFocusability(
        open ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_BEFORE_DESCENDANTS);
    ball.setFocusable(!open);
    ball.setFocusableInTouchMode(!open);
  }

  private void hideSessionsPanel() {
    if (sessionPanel == null) return;
    sessionPanel.animate().cancel();
    removeInlineDivider();
    removeSessionPanel();
    if (drawerHost != null) drawerHost.setVisibility(View.GONE);
    sessionPanel.setVisibility(View.GONE);
    sessionPanel.setTranslationX(0f);
    sessionPanel.setElevation(0f);
    setDrawerAccessibility(false);
    if (sessionScrim != null) {
      sessionScrim.animate().cancel();
      sessionScrim.setAlpha(1f);
      sessionScrim.setVisibility(View.VISIBLE);
    }
    if (ball != null) ball.setSessionsExpanded(false);
  }

  private void removeInlineDivider() {
    if (inlineDivider != null && inlineDivider.getParent() instanceof ViewGroup parent)
      parent.removeView(inlineDivider);
  }

  private void removeSessionPanel() {
    if (sessionPanel.getParent() instanceof ViewGroup parent) parent.removeView(sessionPanel);
  }

  private void updateDrawerWidth() {
    if (sessionPanel.getParent() != drawerHost) return;
    ViewGroup.LayoutParams params = sessionPanel.getLayoutParams();
    if (params == null) return;
    int width = drawerWidthPx();
    if (params.width != width) {
      params.width = width;
      sessionPanel.setLayoutParams(params);
    }
  }

  private int drawerWidthPx() {
    int available =
        root == null ? 0 : root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
    return TerminalWorkspacePolicy.drawerWidth(
        available, dp(TerminalWorkspacePolicy.drawerMaxWidth()), dp(32));
  }

  private int inlineWidthPx() {
    int widthDp = workspaceWidthDp();
    return dp(TerminalWorkspacePolicy.inlineWidth(widthDp > 0 ? widthDp : 840));
  }

  private int workspaceWidthDp() {
    if (root == null) return 0;
    int width = Math.max(0, root.getWidth() - root.getPaddingLeft() - root.getPaddingRight());
    return Math.round(width / getResources().getDisplayMetrics().density);
  }

  private int workspaceHeightDp() {
    if (root == null) return 0;
    int height = Math.max(0, root.getHeight() - root.getPaddingTop() - root.getPaddingBottom());
    return Math.round(height / getResources().getDisplayMetrics().density);
  }

  private int drawerHiddenTranslation() {
    return getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
        ? drawerWidthPx()
        : -drawerWidthPx();
  }

  private void toggleSessions() {
    if (sessionsRequested) {
      closeSessions();
      return;
    }
    if (expanded) return;
    openSessions();
  }

  private void openSessions() {
    if (sessionsRequested) return;
    sessionsRequested = true;
    renderSessions();
    applyPresentation();
    if (!expanded) showDrawer(true);
    if (sessionClose.getVisibility() == View.VISIBLE)
      sessionClose.post(
          () -> {
            if (sessionsRequested && sessionClose.getVisibility() == View.VISIBLE) {
              sessionClose.requestFocus();
              sessionClose.performAccessibilityAction(
                  AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
            }
          });
  }

  private void closeSessions() {
    if (workbench && expanded) return;
    if (!sessionsRequested && !expanded) return;
    sessionsRequested = false;
    applyPresentation();
    restoreSurfaceFocus();
  }

  private boolean sessionsDismissible() {
    return sessionsRequested && !(workbench && expanded);
  }

  private void restoreSurfaceFocus() {
    View target =
        ball.getVisibility() == View.VISIBLE
            ? ball
            : menuButton.getVisibility() == View.VISIBLE ? menuButton : null;
    if (target != null) target.post(target::requestFocus);
  }

  @Override
  public void onTerminalPaneChanged() {
    if (root == null || isDestroyed()) return;
    TerminalPane pane = pane();
    boolean pty = pane == null ? PtyTerminalFragment.preferred(this) : pane.isPty();
    var tab = pty ? owner.currentPty() : owner.currentSimple();
    title.setText(tab == null ? UiText.text("暂无终端") : sessionLabel(pty, tab));
    String warning =
        previousProcess
            ? UiText.choose(
                "应用进程已重建；旧会话不能恢复，请手动新建。",
                "The app process restarted. Old sessions cannot be restored; create a new session explicitly.")
            : "";
    if (tab != null && tab.isCloseUnconfirmed())
      warning =
          UiText.choose(
              "退出待核验；会话和环境保护仍保留。",
              "Exit unconfirmed; the session and runtime protection are retained.");
    if (tab != null && tab.isClosing())
      warning = UiText.choose("正在结束进程并核验退出…", "Ending the process and verifying exit…");
    if (owner.protectionSnapshot().state == TerminalRuntimeProtection.State.LOST)
      warning =
          UiText.choose(
              "后台运行保护已丢失；会话未被自动结束，请查看会话状态。",
              "Background protection was lost. Sessions were not automatically ended; inspect their status.");
    status.setText(warning);
    status.setVisibility(warning.isEmpty() ? View.GONE : View.VISIBLE);
    if (sessionPanel != null && sessionPanel.getVisibility() == View.VISIBLE) renderSessions();
  }

  private String sessionLabel(boolean pty, TerminalTabs.Tab<?> tab) {
    String name = tab.customTitle();
    return UiText.format(
        "%s · %s",
        pty ? "PTY" : UiText.choose("简易", "Simple"),
        name.isEmpty() ? UiText.format("终端 %s", tab.number) : name);
  }

  private String sessionState(boolean pty, TerminalTabs.Tab<?> tab) {
    if (tab.isClosing()) return UiText.choose("正在结束并核验", "Ending and verifying");
    if (tab.isCloseUnconfirmed()) return UiText.choose("退出待核验", "Exit unconfirmed");
    if (pty) {
      var process = (PtySession) tab.value;
      if (process.isRunning()) return UiText.choose("运行中", "Running");
      return process.isCleanupPending()
          ? UiText.choose("进程已退出 · 清理待核验", "Process exited · cleanup unverified")
          : UiText.choose("已核验退出 · 历史保留", "Exit verified · history retained");
    }
    var simple = (TerminalSessionOwner.SimpleTab) tab.value;
    return switch (simple.session.state()) {
      case STARTING -> UiText.choose("启动中", "Starting");
      case READY -> UiText.choose("就绪", "Ready");
      case BUSY -> UiText.choose("执行中", "Busy");
      case STOPPING -> UiText.choose("停止中", "Stopping");
      case FAILED -> UiText.choose("失败 · 请检查", "Failed · inspect session");
      default -> UiText.choose("未运行", "Not running");
    };
  }

  private boolean sessionRunning(boolean pty, TerminalTabs.Tab<?> tab) {
    if (tab.isClosing() || tab.isCloseUnconfirmed()) return false;
    if (pty) return ((PtySession) tab.value).isRunning();
    var state = ((TerminalSessionOwner.SimpleTab) tab.value).session.state();
    return state == TerminalSession.State.READY || state == TerminalSession.State.BUSY;
  }

  private void showDisplaySettings() {
    dismissPanel();
    String[] choices = {
      UiText.choose("字号减小", "Smaller text"),
      UiText.choose("字号增大", "Larger text"),
      workbench
          ? UiText.choose("切换极简模式", "Use minimal mode")
          : UiText.choose("切换工作台模式", "Use workbench mode"),
      UiText.choose("重置菜单位置", "Reset menu position")
    };
    panel =
        new DshaDialogBuilder(this)
            .setTitle(UiText.choose("显示设置", "Display settings"))
            .setItems(
                choices,
                (dialog, index) -> {
                  if (index < 2 && pane() != null) pane().changeFont(index == 0 ? -1 : 1);
                  if (index == 2) {
                    workbench = !workbench;
                    getSharedPreferences("deepseekharness", MODE_PRIVATE)
                        .edit()
                        .putBoolean(WORKBENCH, workbench)
                        .apply();
                    applyPresentation();
                  }
                  if (index == 3) ball.resetPosition();
                })
            .show();
  }

  private void showCreateChooser() {
    dismissPanel();
    String[] choices = {
      UiText.choose("PTY · 交互会话", "PTY · interactive session"),
      UiText.choose("简易 · 按行命令", "Simple · line commands")
    };
    panel =
        new DshaDialogBuilder(this)
            .setTitle(UiText.choose("新建会话", "New session"))
            .setItems(
                choices,
                (dialog, index) -> {
                  dismissPanel();
                  createInMode(index == 0);
                })
            .setNegativeButton(UiText.text("取消"), null)
            .show();
  }

  private void showSessionHelp() {
    dismissPanel();
    panel =
        new DshaDialogBuilder(this)
            .setTitle(UiText.choose("会话侧栏", "Sessions sidebar"))
            .setMessage(
                UiText.choose(
                    "点击悬浮球或顶栏“菜单”打开会话侧栏。平板宽屏在侧栏打开时内联显示并保留终端，工作台宽屏常驻；窄窗口用抽屉。返回只关闭页面，结束进程必须在会话行明确确认并核验。",
                    "Tap the floating ball or the top menu to open the sessions sidebar. On wide tablets the sidebar stays inline beside the terminal when opened, and is persistent in workbench mode; narrow windows use a drawer. Back only closes the page; ending a process requires an explicit confirmed and verified action on its row."))
            .setPositiveButton(UiText.choose("知道了", "Got it"), null)
            .show();
  }

  private void renderSessions() {
    if (sessionList == null) return;
    int scrollY = sessionsRendered && sessionScroll != null ? sessionScroll.getScrollY() : -1;
    sessionList.removeAllViews();
    var ptyTabs = owner.ptyTabs().snapshot();
    var simpleTabs = owner.simpleTabs().snapshot();
    addGroupHeader(UiText.choose("PTY · 交互会话", "PTY · interactive sessions"), ptyTabs.size());
    if (ptyTabs.isEmpty()) addGroupEmpty();
    for (var tab : ptyTabs) addSessionRow(true, tab);
    addGroupHeader(UiText.choose("简易 · 按行命令", "Simple · line commands"), simpleTabs.size());
    if (simpleTabs.isEmpty()) addGroupEmpty();
    for (var tab : simpleTabs) addSessionRow(false, tab);
    int total = ptyTabs.size() + simpleTabs.size();
    if (sessionSummary != null)
      sessionSummary.setText(
          UiText.format(
              UiText.choose("%d 个会话 · PTY %d / 简易 %d", "%d sessions · PTY %d / simple %d"),
              total,
              ptyTabs.size(),
              simpleTabs.size()));
    if (closeAllButton != null) closeAllButton.setVisibility(total > 0 ? View.VISIBLE : View.GONE);
    sessionsRendered = true;
    if (scrollY > 0 && sessionScroll != null)
      sessionScroll.post(() -> sessionScroll.scrollTo(0, scrollY));
    refreshCloseAllNotice();
  }

  private void addGroupHeader(String text, int count) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(8), dp(10), dp(8), dp(4));
    TextView heading = panelText(text, 11, getColor(R.color.terminal_muted), true);
    TextView number = panelText(String.valueOf(count), 11, getColor(R.color.terminal_muted), false);
    row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(number);
    sessionList.addView(row, new LinearLayout.LayoutParams(-1, -2));
  }

  private void addGroupEmpty() {
    TextView empty =
        panelText(
            UiText.choose("暂无会话", "No sessions"), 11, getColor(R.color.terminal_muted), false);
    empty.setPadding(dp(10), dp(2), dp(10), dp(6));
    sessionList.addView(empty, new LinearLayout.LayoutParams(-1, -2));
  }

  private void addSessionRow(boolean pty, TerminalTabs.Tab<?> tab) {
    TerminalTabs.Tab<?> current = pty ? owner.currentPty() : owner.currentSimple();
    boolean active = current != null && current.id == tab.id;
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(56));
    if (active) row.setBackgroundResource(R.drawable.bg_terminal_session);
    View accent = new View(this);
    accent.setBackgroundColor(getColor(R.color.terminal_accent));
    accent.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
    row.addView(accent, new LinearLayout.LayoutParams(Math.max(1, dp(2)), -1));
    LinearLayout text = new LinearLayout(this);
    text.setOrientation(LinearLayout.VERTICAL);
    text.setGravity(Gravity.CENTER_VERTICAL);
    text.setPadding(dp(10), dp(6), dp(4), dp(6));
    text.setFocusable(true);
    text.setFocusableInTouchMode(true);
    text.setClickable(true);
    text.setBackgroundResource(R.drawable.bg_terminal_touch);
    LinearLayout nameRow = new LinearLayout(this);
    nameRow.setOrientation(LinearLayout.HORIZONTAL);
    nameRow.setGravity(Gravity.CENTER_VERTICAL);
    View dot = new View(this);
    dot.setBackgroundResource(R.drawable.bg_terminal_dot);
    dot.setVisibility(sessionRunning(pty, tab) ? View.VISIBLE : View.INVISIBLE);
    nameRow.addView(dot, new LinearLayout.LayoutParams(dp(6), dp(6)));
    TextView name =
        panelText(
            sessionLabel(pty, tab),
            13,
            getColor(active ? R.color.terminal_accent : R.color.terminal_foreground),
            active);
    name.setSingleLine(true);
    name.setEllipsize(TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
    nameParams.setMarginStart(dp(6));
    nameRow.addView(name, nameParams);
    TextView state = panelText(sessionState(pty, tab), 11, getColor(R.color.terminal_muted), false);
    state.setSingleLine(true);
    state.setEllipsize(TextUtils.TruncateAt.END);
    state.setPadding(0, dp(2), 0, 0);
    text.addView(nameRow, new LinearLayout.LayoutParams(-1, -2));
    text.addView(state, new LinearLayout.LayoutParams(-1, -2));
    String description = UiText.format("%s · %s", sessionLabel(pty, tab), sessionState(pty, tab));
    if (active)
      description = UiText.format(UiText.choose("当前会话 · %s", "Current session · %s"), description);
    text.setContentDescription(description);
    text.setOnClickListener(v -> selectSession(pty, tab.id));
    row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
    TextView rename =
        panelIcon(
            "✎",
            UiText.format(UiText.choose("重命名 %s", "Rename %s"), sessionLabel(pty, tab)),
            () -> rename(pty, tab.id));
    rename.setEnabled(!tab.isClosing());
    rename.setAlpha(rename.isEnabled() ? 1f : .35f);
    row.addView(rename, new LinearLayout.LayoutParams(dp(48), dp(48)));
    TextView close =
        panelIcon(
            tab.isCloseUnconfirmed() ? "⟳" : "✕",
            UiText.format(
                tab.isCloseUnconfirmed()
                    ? UiText.choose("核验并结束 %s", "Verify and end %s")
                    : UiText.choose("结束 %s 的进程", "End process for %s"),
                sessionLabel(pty, tab)),
            () -> requestCloseSession(pty, tab.id));
    close.setEnabled(!tab.isClosing());
    close.setAlpha(close.isEnabled() ? 1f : .35f);
    row.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
    rowParams.bottomMargin = dp(2);
    sessionList.addView(row, rowParams);
    if (!tab.closeError().isEmpty()) {
      TextView error =
          panelText(
              SensitiveData.redact(tab.closeError()), 11, getColor(R.color.terminal_danger), false);
      error.setPadding(dp(10), 0, dp(10), dp(6));
      sessionList.addView(error, new LinearLayout.LayoutParams(-1, -2));
    }
  }

  private void selectSession(boolean pty, long id) {
    closeSessions();
    dismissPanel();
    if (pty) owner.selectPty(id);
    else owner.selectSimple(id);
    switchTerminalMode(pty);
    root.post(
        () -> {
          if (pane() != null) pane().restoreInputFocus();
        });
  }

  private void rename(boolean pty, long id) {
    var tab = pty ? owner.ptyTabs().find(id) : owner.simpleTabs().find(id);
    if (tab == null) return;
    dismissPanel();
    EditText input = new EditText(this);
    input.setSingleLine();
    input.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(80)});
    input.setText(tab.customTitle());
    panel =
        new DshaDialogBuilder(this)
            .setTitle(UiText.choose("重命名会话", "Rename session"))
            .setView(input)
            .setNegativeButton(UiText.text("取消"), null)
            .setPositiveButton(
                UiText.text("保存"),
                (d, w) -> {
                  if (pty) owner.renamePty(id, input.getText().toString());
                  else owner.renameSimple(id, input.getText().toString());
                  onTerminalPaneChanged();
                })
            .show();
  }

  @Override
  public void requestCloseSession(boolean pty, long id) {
    var tab = pty ? owner.ptyTabs().find(id) : owner.simpleTabs().find(id);
    if (tab == null || tab.isClosing()) return;
    dismissPanel();
    final String epoch = owner.ownerEpoch();
    panel =
        new DshaDialogBuilder(this)
            .setTitle(UiText.choose("结束此会话的进程？", "End this session's process?"))
            .setMessage(
                UiText.format(
                    "%s\n%s",
                    sessionLabel(pty, tab),
                    UiText.choose(
                        "这不是返回页面。只结束此会话，核验退出成功后才移除；其他会话和 Web 不受影响。",
                        "This is not page navigation. Only this session is ended and removed after verified exit; other sessions and Web are unaffected.")))
            .setNegativeButton(UiText.text("取消"), null)
            .setPositiveButton(
                UiText.choose("结束并核验", "End and verify"),
                (d, w) -> {
                  if (!epoch.equals(owner.ownerEpoch())) return;
                  // 不切页、不重放输入；后台关闭结果由owner保留，宿主离开不会抹掉结果。
                  TerminalSessionOwner.CloseCallback completed =
                      (closedEpoch, kind, closedId, error) -> {
                        if (epoch.equals(closedEpoch)) runOnUiThread(this::onTerminalPaneChanged);
                      };
                  if (pty) owner.closePtyAsync(id, 5000, completed);
                  else owner.closeSimpleAsync(id, 5000, completed);
                  onTerminalPaneChanged();
                })
            .show();
  }

  /**
   * 尽力关闭：每个会话仍走 owner 的关闭与核验，不在宿主遍历裸 PID，也不把未确认伪装成已关闭。
   * 维护流程仍使用其原有的 fail-fast closeAll，不与此入口混用。
   */
  private void confirmCloseAll() {
    if (closeAllRunning) return;
    var ptyTabs = owner.ptyTabs().snapshot();
    var simpleTabs = owner.simpleTabs().snapshot();
    int total = ptyTabs.size() + simpleTabs.size();
    if (total == 0) return;
    dismissPanel();
    panel =
        new DshaDialogBuilder(this)
            .setTitle(UiText.choose("结束全部终端？", "End all terminal sessions?"))
            .setMessage(
                UiText.format(
                    UiText.choose(
                        "将结束 %1$d 个会话（PTY %2$d / 简易 %3$d）。\n这不是返回页面：每个进程分别结束并核验，未确认的会话会保留并标记，Web 和其他功能不受影响。",
                        "This ends %1$d sessions (PTY %2$d / simple %3$d). This is not page navigation: each process is ended and verified separately. Unconfirmed sessions remain marked, and Web or other features are unaffected."),
                    total,
                    ptyTabs.size(),
                    simpleTabs.size()))
            .setNegativeButton(UiText.text("取消"), null)
            .setPositiveButton(
                UiText.choose("全部结束并核验", "End all and verify"), (d, w) -> closeAllBestEffort())
            .show();
  }

  private void closeAllBestEffort() {
    if (closeAllRunning) return;
    var ptyTabs = owner.ptyTabs().snapshot();
    var simpleTabs = owner.simpleTabs().snapshot();
    int total = ptyTabs.size() + simpleTabs.size();
    if (total == 0) return;
    final int run = ++closeAllRun;
    closeAllRunning = true;
    closeAllDispatching = true;
    closeAllEpoch = owner.ownerEpoch();
    closeAllTotal = total;
    closeAllPending.set(0);
    closeAllNotice = "";
    closeAllPtyIds.clear();
    for (var tab : ptyTabs) closeAllPtyIds.add(tab.id);
    closeAllSimpleIds.clear();
    for (var tab : simpleTabs) closeAllSimpleIds.add(tab.id);
    final TerminalSessionOwner.CloseCallback callback =
        (epoch, kind, id, error) -> {
          if (run != closeAllRun) return;
          if (epoch == null || !epoch.equals(closeAllEpoch)) return;
          closeAllPending.decrementAndGet();
          runOnUiThread(
              () -> {
                if (isDestroyed() || run != closeAllRun) return;
                if (closeAllRunning && !closeAllDispatching && closeAllPending.get() <= 0)
                  finishCloseAll();
                else {
                  refreshCloseAllNotice();
                  renderSessions();
                }
              });
        };
    for (var tab : ptyTabs) {
      closeAllPending.incrementAndGet();
      if (!owner.closePtyAsync(tab.id, 5000, callback)) closeAllPending.decrementAndGet();
    }
    for (var tab : simpleTabs) {
      closeAllPending.incrementAndGet();
      if (!owner.closeSimpleAsync(tab.id, 5000, callback)) closeAllPending.decrementAndGet();
    }
    closeAllDispatching = false;
    if (closeAllPending.get() <= 0) {
      finishCloseAll();
      return;
    }
    if (root != null) {
      if (closeAllWatchdog != null) root.removeCallbacks(closeAllWatchdog);
      closeAllWatchdog =
          () -> {
            if (run == closeAllRun && closeAllRunning && !isDestroyed()) finishCloseAll();
          };
      root.postDelayed(closeAllWatchdog, 5000L * Math.max(1, total) + 5000);
    }
    refreshCloseAllNotice();
    renderSessions();
    onTerminalPaneChanged();
  }

  private void finishCloseAll() {
    if (!closeAllRunning) return;
    closeAllRunning = false;
    closeAllDispatching = false;
    if (root != null && closeAllWatchdog != null) root.removeCallbacks(closeAllWatchdog);
    refreshCloseAllNotice();
    onTerminalPaneChanged();
  }

  /** 只统计本轮发起时快照的会话；新建会话不会被误算进未确认。 */
  private int[] closeAllCounts() {
    int closing = 0, unconfirmed = 0;
    for (var tab : owner.ptyTabs().snapshot()) {
      if (!closeAllPtyIds.contains(tab.id)) continue;
      if (tab.isClosing()) closing++;
      else unconfirmed++;
    }
    for (var tab : owner.simpleTabs().snapshot()) {
      if (!closeAllSimpleIds.contains(tab.id)) continue;
      if (tab.isClosing()) closing++;
      else unconfirmed++;
    }
    int present = closing + unconfirmed;
    return new int[] {Math.max(0, closeAllTotal - present), closing, unconfirmed};
  }

  private void refreshCloseAllNotice() {
    if (closeAllTotal <= 0) {
      closeAllNotice = "";
    } else {
      int[] counts = closeAllCounts();
      int closed = counts[0], closing = counts[1], unconfirmed = counts[2];
      if (closeAllRunning) {
        closeAllNotice =
            UiText.format(
                UiText.choose(
                    "正在结束全部：已关闭 %1$d / %2$d，正在结束 %3$d，未确认 %4$d…",
                    "Ending all: %1$d of %2$d closed, %3$d closing, %4$d unconfirmed…"),
                closed,
                closeAllTotal,
                closing,
                unconfirmed);
      } else if (closing > 0) {
        closeAllNotice =
            UiText.format(
                UiText.choose(
                    "结束全部仍在核验：已关闭 %1$d，正在结束 %2$d，未确认 %3$d。",
                    "End all still verifying: %1$d closed, %2$d closing, %3$d unconfirmed."),
                closed,
                closing,
                unconfirmed);
      } else if (unconfirmed > 0) {
        closeAllNotice =
            UiText.format(
                UiText.choose(
                    "结束全部完成：已关闭 %1$d，未确认 %2$d。未确认会话保留在列表中，可逐个核验。",
                    "End all finished: %1$d closed, %2$d unconfirmed. Unconfirmed sessions remain for individual verification."),
                closed,
                unconfirmed);
      } else {
        closeAllNotice =
            UiText.format(
                UiText.choose("结束全部完成：已关闭 %1$d。", "End all finished: %1$d closed."), closed);
      }
    }
    if (panelNotice != null) {
      panelNotice.setText(closeAllNotice);
      panelNotice.setVisibility(closeAllNotice.isEmpty() ? View.GONE : View.VISIBLE);
    }
    if (closeAllButton != null) {
      closeAllButton.setEnabled(!closeAllRunning);
      closeAllButton.setAlpha(closeAllRunning ? .55f : 1f);
      closeAllButton.setText(
          closeAllRunning ? UiText.choose("正在结束…", "Ending…") : UiText.choose("结束全部", "End all"));
    }
    if (newSessionButton != null) {
      newSessionButton.setEnabled(!closeAllRunning);
      newSessionButton.setAlpha(closeAllRunning ? .55f : 1f);
    }
  }

  private void resetCloseAllReport() {
    closeAllRun++;
    closeAllTotal = 0;
    closeAllNotice = "";
    closeAllPtyIds.clear();
    closeAllSimpleIds.clear();
    if (panelNotice != null) panelNotice.setVisibility(View.GONE);
  }

  private void createInMode(boolean pty) {
    if (changingMode
        || getSupportFragmentManager().isStateSaved()
        || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
      return;
    if (!closeAllRunning) resetCloseAllReport();
    closeSessions();
    dismissPanel();
    previousProcess = false;
    // 禁止模式首次自动创建与菜单新建叠加：显式新建的目标Fragment跳过默认创建。
    replacePane(pty, true);
    if (pane() != null && pane().isPty() == pty) pane().newSession();
  }

  @Override
  public void switchTerminalMode(boolean pty) {
    replacePane(pty, false);
  }

  private void replacePane(boolean pty, boolean explicitCreate) {
    if (changingMode || getSupportFragmentManager().isStateSaved()) return;
    TerminalPane current = pane();
    if (current != null && current.isPty() == pty) {
      onTerminalPaneChanged();
      return;
    }
    changingMode = true;
    try {
      getSharedPreferences("deepseekharness", MODE_PRIVATE)
          .edit()
          .putBoolean(PtyTerminalFragment.KEY_PTY, pty)
          .apply();
      Fragment fragment = createPane(pty, explicitCreate || previousProcess);
      getSupportFragmentManager()
          .beginTransaction()
          .replace(R.id.terminal_content, fragment)
          .commitNow();
    } finally {
      changingMode = false;
    }
    onTerminalPaneChanged();
  }

  /** 测试可替换视图端口，但生产始终复用原生终端组件。 */
  protected Fragment createPane(boolean pty, boolean skipInitial) {
    Fragment fragment = pty ? new PtyTerminalFragment() : new TerminalFragment();
    Bundle args = new Bundle();
    args.putBoolean("terminal_skip_initial_create", skipInitial);
    fragment.setArguments(args);
    return fragment;
  }

  private void leavePage() {
    // 通知在Main已不存在时也只补同task原生主页，不创建独立最近任务卡片。
    if (isTaskRoot())
      startActivity(
          new Intent(this, MainActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    finish();
  }

  private void dismissPanel() {
    if (panel != null) panel.dismiss();
    panel = null;
  }

  @Override
  protected void onResume() {
    super.onResume();
    owner.protection().addObserver(protectionChanged);
    onTerminalPaneChanged();
  }

  @Override
  protected void onPause() {
    owner.protection().removeObserver(protectionChanged);
    dismissPanel();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    if (root != null && closeAllWatchdog != null) root.removeCallbacks(closeAllWatchdog);
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putString("terminal_owner", owner.ownerEpoch());
    out.putBoolean(SESSIONS_OPEN, sessionsRequested);
    if (ball != null) {
      out.putBoolean("ball_right", ball.dockRight());
      out.putFloat("ball_fraction", ball.dockFraction());
    }
    super.onSaveInstanceState(out);
  }

  private TextView toolbarLabel(String text) {
    TextView value = new TextView(this);
    value.setText(text);
    value.setTextColor(getColor(R.color.terminal_foreground));
    value.setTextSize(16);
    value.setTypeface(Typeface.DEFAULT_BOLD);
    value.setMaxLines(2);
    value.setEllipsize(TextUtils.TruncateAt.END);
    value.setPadding(dp(8), dp(4), dp(8), dp(4));
    return value;
  }

  private TextView toolbarButton(String text, Runnable action) {
    TextView value = new TextView(this);
    value.setText(text);
    value.setTextColor(getColor(R.color.terminal_foreground));
    value.setTextSize(14);
    value.setGravity(Gravity.CENTER);
    value.setMinHeight(dp(48));
    value.setMinWidth(dp(48));
    value.setPadding(dp(10), 0, dp(10), 0);
    value.setFocusable(true);
    value.setFocusableInTouchMode(true);
    value.setClickable(true);
    value.setBackgroundResource(R.drawable.bg_terminal_touch);
    value.setOnClickListener(v -> action.run());
    return value;
  }

  private TextView panelText(String text, float sizeSp, int color, boolean bold) {
    TextView value = new TextView(this);
    value.setText(text);
    value.setTextSize(sizeSp);
    value.setTextColor(color);
    value.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    return value;
  }

  private TextView panelAction(String text, int color, float sizeSp, Runnable action) {
    TextView value = panelText(text, sizeSp, color, false);
    value.setGravity(Gravity.CENTER);
    value.setMinHeight(dp(48));
    value.setMinWidth(dp(48));
    value.setPadding(dp(6), 0, dp(6), 0);
    value.setFocusable(true);
    value.setFocusableInTouchMode(true);
    value.setClickable(true);
    value.setBackgroundResource(R.drawable.bg_terminal_touch);
    value.setOnClickListener(v -> action.run());
    return value;
  }

  private TextView footerButton(String text, Runnable action, float sizeSp) {
    TextView value = panelAction(text, getColor(R.color.terminal_foreground), sizeSp, action);
    value.setPadding(dp(2), 0, dp(2), 0);
    value.setSingleLine(true);
    value.setEllipsize(TextUtils.TruncateAt.END);
    return value;
  }

  private TextView panelIcon(String glyph, String description, Runnable action) {
    TextView value = panelText(glyph, 18, getColor(R.color.terminal_foreground), false);
    value.setGravity(Gravity.CENTER);
    value.setMinWidth(dp(48));
    value.setMinHeight(dp(48));
    value.setFocusable(true);
    value.setFocusableInTouchMode(true);
    value.setClickable(true);
    value.setBackgroundResource(R.drawable.bg_terminal_touch);
    value.setContentDescription(description);
    value.setOnClickListener(v -> action.run());
    return value;
  }

  private TextView label(String text) {
    TextView value = new TextView(this);
    value.setText(text);
    value.setTextColor(getColor(R.color.text));
    value.setTextSize(14);
    value.setPadding(dp(12), dp(6), dp(12), dp(6));
    return value;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
