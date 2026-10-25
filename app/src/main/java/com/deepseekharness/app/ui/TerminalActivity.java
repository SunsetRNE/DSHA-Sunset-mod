package com.deepseekharness.app.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.TerminalRuntimeProtection;
import com.deepseekharness.app.core.TerminalSessionOwner;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.TerminalTabs;
import com.deepseekharness.app.util.TerminalWorkspacePolicy;
import com.deepseekharness.app.util.UiText;

/** 同 task 的终端工作台。页面只持有视图，结束进程必须明确指定会话并核验。 */
public class TerminalActivity extends AppCompatActivity implements TerminalPane.Host {
  private static final String WORKBENCH = "terminal_workbench";
  private static final String SESSIONS_OPEN = "sessions_open";
  private final TerminalSessionOwner owner = TerminalSessionOwner.shared();
  private FrameLayout root, content, drawerHost;
  private LinearLayout column, toolbar, body, sessionPanel, sessionList;
  private View sessionScrim;
  private TextView title, status, menuButton, sessionClose, sessionSummary;
  private TerminalFloatingMenu ball;
  private AlertDialog panel;
  private boolean workbench, imeVisible, previousProcess, changingMode, expanded, drawerOpen;
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
    drawerOpen = saved != null && !previousProcess && saved.getBoolean(SESSIONS_OPEN, false);
    buildWorkspace();
    if (drawerOpen) renderSessions();
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
                if (drawerOpen) {
                  closeDrawer();
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
    root.setForceDarkAllowed(false);
    column = new LinearLayout(this);
    column.setOrientation(LinearLayout.VERTICAL);
    root.addView(column, new FrameLayout.LayoutParams(-1, -1));
    toolbar = new LinearLayout(this);
    toolbar.setId(R.id.terminal_workspace_toolbar);
    toolbar.setGravity(Gravity.CENTER_VERTICAL);
    toolbar.setBackgroundColor(getColor(R.color.surface));
    toolbar.addView(button(UiText.choose("返回", "Back"), this::leavePage));
    title = label("");
    title.setMaxLines(2);
    toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    menuButton = button(UiText.choose("菜单", "Menu"), this::toggleSessions);
    menuButton.setId(R.id.terminal_workspace_menu);
    menuButton.setContentDescription(UiText.choose("打开会话侧栏", "Open sessions sidebar"));
    toolbar.addView(menuButton);
    column.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
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
    sessionScrim.setOnClickListener(v -> closeDrawer());
    sessionScrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    drawerHost.addView(sessionScrim, new FrameLayout.LayoutParams(-1, -1));
    drawerHost.addView(sessionPanel, new FrameLayout.LayoutParams(dp(280), -1, Gravity.START));
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

  /** 侧栏内容只由宿主渲染；抽屉与宽屏内联复用同一份视图。 */
  private LinearLayout buildSessionsPanel() {
    LinearLayout panel = new LinearLayout(this);
    panel.setId(R.id.terminal_sessions_panel);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setBackgroundColor(getColor(R.color.raised));
    panel.setFocusable(true);
    panel.setVisibility(View.GONE);
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(16), dp(12), dp(8), dp(6));
    LinearLayout headings = new LinearLayout(this);
    headings.setOrientation(LinearLayout.VERTICAL);
    TextView heading = new TextView(this);
    heading.setText(UiText.text("会话"));
    heading.setTextColor(getColor(R.color.text));
    heading.setTextSize(18);
    heading.setTypeface(Typeface.DEFAULT_BOLD);
    TextView subtitle = new TextView(this);
    subtitle.setText(
        UiText.choose("独立会话 · 返回不结束进程", "Independent sessions · back keeps processes"));
    subtitle.setTextColor(getColor(R.color.text_secondary));
    subtitle.setTextSize(11);
    subtitle.setPadding(0, dp(2), 0, 0);
    headings.addView(heading);
    headings.addView(subtitle);
    header.addView(headings, new LinearLayout.LayoutParams(0, -2, 1));
    sessionClose = button("×", this::closeDrawer);
    sessionClose.setId(R.id.terminal_sessions_close);
    sessionClose.setTextSize(22);
    sessionClose.setContentDescription(UiText.choose("收起会话侧栏", "Close sessions sidebar"));
    header.addView(sessionClose, new LinearLayout.LayoutParams(dp(48), dp(48)));
    panel.addView(header, new LinearLayout.LayoutParams(-1, -2));
    panel.addView(divider(), new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
    ScrollView sessionScroll = new ScrollView(this);
    sessionList = new LinearLayout(this);
    sessionList.setOrientation(LinearLayout.VERTICAL);
    sessionList.setPadding(dp(8), dp(4), dp(8), dp(12));
    sessionScroll.addView(sessionList, new FrameLayout.LayoutParams(-1, -2));
    panel.addView(sessionScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout footer = new LinearLayout(this);
    footer.setOrientation(LinearLayout.VERTICAL);
    footer.setPadding(dp(8), dp(6), dp(8), dp(10));
    sessionSummary = new TextView(this);
    sessionSummary.setId(R.id.terminal_sessions_summary);
    sessionSummary.setTextColor(getColor(R.color.text_secondary));
    sessionSummary.setTextSize(11);
    sessionSummary.setPadding(dp(8), 0, dp(8), dp(4));
    footer.addView(sessionSummary, new LinearLayout.LayoutParams(-1, -2));
    addFooterRow(
        footer,
        UiText.choose("返回主界面", "Home"),
        () -> {
          closeDrawer();
          leavePage();
        },
        UiText.choose("显示设置", "Display"),
        this::showDisplaySettings);
    addFooterRow(
        footer,
        UiText.choose("显示键盘", "Keyboard"),
        () -> {
          closeDrawer();
          if (pane() != null) pane().showKeyboard();
        },
        UiText.choose("粘贴文本", "Paste"),
        () -> {
          closeDrawer();
          if (pane() != null) pane().paste();
        });
    addFooterRow(
        footer,
        UiText.choose("回到输入", "Focus input"),
        () -> {
          closeDrawer();
          if (pane() != null) pane().restoreInputFocus();
        },
        UiText.choose("说明", "Help"),
        this::showSessionHelp);
    panel.addView(footer, new LinearLayout.LayoutParams(-1, -2));
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
    row.addView(button(leftText, leftAction), new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(button(rightText, rightAction), new LinearLayout.LayoutParams(0, -2, 1));
    footer.addView(row, new LinearLayout.LayoutParams(-1, -2));
  }

  private View divider() {
    View divider = new View(this);
    divider.setBackgroundColor(getColor(R.color.line));
    return divider;
  }

  private TerminalPane pane() {
    Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.terminal_content);
    return fragment instanceof TerminalPane ? (TerminalPane) fragment : null;
  }

  private void applyPresentation() {
    if (root == null || sessionPanel == null || menuButton == null) return;
    int width =
        Math.round(
            (root.getWidth() - root.getPaddingLeft() - root.getPaddingRight())
                / getResources().getDisplayMetrics().density);
    boolean nextExpanded = TerminalWorkspacePolicy.sidebar(workbench, width);
    expanded = nextExpanded;
    toolbar.setVisibility(workbench ? View.VISIBLE : View.GONE);
    ball.setVisibility(workbench ? View.GONE : View.VISIBLE);
    menuButton.setVisibility(nextExpanded ? View.GONE : View.VISIBLE);
    if (expanded) {
      drawerOpen = false;
      showInlinePanel();
    } else if (drawerOpen) {
      showDrawer(false);
    } else {
      hideDrawer();
    }
    ball.setSessionsExpanded(drawerOpen && !expanded);
  }

  private void showInlinePanel() {
    if (sessionPanel.getParent() != body) {
      removeSessionPanel();
      body.addView(sessionPanel, 0, new LinearLayout.LayoutParams(dp(260), -1));
      renderSessions();
    }
    sessionPanel.setVisibility(View.VISIBLE);
    sessionPanel.setElevation(0f);
    sessionPanel.setTranslationX(0f);
    sessionPanel.setAccessibilityViewIsModal(false);
    sessionClose.setVisibility(View.GONE);
    drawerHost.setVisibility(View.GONE);
  }

  private void showDrawer(boolean animate) {
    if (expanded) return;
    if (sessionPanel.getParent() != drawerHost) {
      removeSessionPanel();
      drawerHost.addView(
          sessionPanel, new FrameLayout.LayoutParams(drawerWidth(), -1, Gravity.START));
      renderSessions();
    }
    drawerHost.setVisibility(View.VISIBLE);
    sessionScrim.setVisibility(View.VISIBLE);
    sessionPanel.setVisibility(View.VISIBLE);
    sessionClose.setVisibility(View.VISIBLE);
    sessionPanel.setElevation(dp(8));
    sessionPanel.setAccessibilityViewIsModal(true);
    ViewCompat.setAccessibilityPaneTitle(sessionPanel, UiText.text("会话侧栏"));
    if (ball != null) ball.setSessionsExpanded(true);
    updateDrawerWidth();
    if (animate) {
      sessionPanel.setTranslationX(drawerHiddenTranslation());
      sessionScrim.setAlpha(0f);
      drawerHost.post(
          () -> {
            if (!drawerOpen || expanded) return;
            sessionPanel.animate().translationX(0f).setDuration(180).start();
            sessionScrim.animate().alpha(1f).setDuration(180).start();
          });
    } else {
      sessionPanel.animate().cancel();
      sessionScrim.animate().cancel();
      sessionPanel.setTranslationX(0f);
      sessionScrim.setAlpha(1f);
    }
  }

  private void hideDrawer() {
    if (sessionPanel.getParent() instanceof ViewGroup parent && parent != drawerHost)
      parent.removeView(sessionPanel);
    if (drawerHost != null) drawerHost.setVisibility(View.GONE);
    sessionPanel.animate().cancel();
    sessionPanel.setVisibility(View.GONE);
    sessionPanel.setTranslationX(0f);
    sessionPanel.setElevation(0f);
    sessionPanel.setAccessibilityViewIsModal(false);
    if (sessionScrim != null) {
      sessionScrim.animate().cancel();
      sessionScrim.setAlpha(1f);
      sessionScrim.setVisibility(View.VISIBLE);
    }
    if (ball != null) ball.setSessionsExpanded(false);
  }

  private void removeSessionPanel() {
    if (sessionPanel.getParent() instanceof ViewGroup parent) parent.removeView(sessionPanel);
  }

  private void updateDrawerWidth() {
    if (sessionPanel.getParent() != drawerHost) return;
    ViewGroup.LayoutParams params = sessionPanel.getLayoutParams();
    if (params == null) return;
    int width = drawerWidth();
    if (params.width != width) {
      params.width = width;
      sessionPanel.setLayoutParams(params);
    }
  }

  private int drawerWidth() {
    int available =
        root == null ? 0 : root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
    return TerminalWorkspacePolicy.drawerWidth(available, dp(280), dp(32));
  }

  private int drawerHiddenTranslation() {
    return getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
        ? drawerWidth()
        : -drawerWidth();
  }

  private void toggleSessions() {
    if (expanded) return;
    if (drawerOpen) closeDrawer();
    else openDrawer();
  }

  private void openDrawer() {
    if (expanded || drawerOpen) return;
    drawerOpen = true;
    renderSessions();
    showDrawer(true);
    if (sessionClose.getVisibility() == View.VISIBLE) sessionClose.requestFocus();
  }

  private void closeDrawer() {
    if (!drawerOpen || expanded) return;
    drawerOpen = false;
    hideDrawer();
    restoreSurfaceFocus();
  }

  private void restoreSurfaceFocus() {
    if (ball.getVisibility() == View.VISIBLE) ball.requestFocus();
    else if (menuButton.getVisibility() == View.VISIBLE) menuButton.requestFocus();
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
      var process = (com.deepseekharness.app.PtySession) tab.value;
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

  private void showDisplaySettings() {
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

  private void showSessionHelp() {
    panel =
        new DshaDialogBuilder(this)
            .setTitle(UiText.choose("会话侧栏", "Sessions sidebar"))
            .setMessage(
                UiText.choose(
                    "点击悬浮球或顶栏“菜单”打开会话侧栏；在这里新建、切换、重命名或结束会话。返回只关闭页面，结束进程必须在会话行明确确认并核验。",
                    "Tap the floating ball or the top menu to open the sessions sidebar. Create, switch, rename or end sessions here. Back only closes the page; ending a process requires an explicit confirmed and verified action on its row."))
            .setPositiveButton(UiText.choose("知道了", "Got it"), null)
            .show();
  }

  private void renderSessions() {
    if (sessionList == null) return;
    sessionList.removeAllViews();
    var ptyTabs = owner.ptyTabs().snapshot();
    var simpleTabs = owner.simpleTabs().snapshot();
    sessionList.addView(
        button(UiText.choose("新建 PTY", "New PTY"), () -> createInMode(true)),
        new LinearLayout.LayoutParams(-1, -2));
    sessionList.addView(
        button(UiText.choose("新建简易终端", "New simple terminal"), () -> createInMode(false)),
        new LinearLayout.LayoutParams(-1, -2));
    addGroupHeader(UiText.choose("PTY · 交互会话", "PTY · interactive sessions"), ptyTabs.size());
    for (var tab : ptyTabs) addSessionRow(true, tab);
    addGroupHeader(UiText.choose("简易 · 按行命令", "Simple · line commands"), simpleTabs.size());
    for (var tab : simpleTabs) addSessionRow(false, tab);
    if (ptyTabs.isEmpty() && simpleTabs.isEmpty())
      sessionList.addView(
          label(
              UiText.choose(
                  "暂无会话；新建后才会启动进程。", "No sessions. A process starts only when you create one.")));
    if (sessionSummary != null)
      sessionSummary.setText(
          UiText.format(
              UiText.choose("%d 个会话 · PTY %d / 简易 %d", "%d sessions · PTY %d / simple %d"),
              ptyTabs.size() + simpleTabs.size(),
              ptyTabs.size(),
              simpleTabs.size()));
  }

  private void addGroupHeader(String text, int count) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(8), dp(10), dp(8), dp(4));
    TextView heading = label(text);
    heading.setTextSize(12);
    heading.setTextColor(getColor(R.color.text_secondary));
    heading.setTypeface(Typeface.DEFAULT_BOLD);
    heading.setPadding(0, 0, 0, 0);
    TextView number = label(String.valueOf(count));
    number.setTextSize(12);
    number.setTextColor(getColor(R.color.text_secondary));
    number.setPadding(0, 0, 0, 0);
    row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(number);
    sessionList.addView(row, new LinearLayout.LayoutParams(-1, -2));
  }

  private void addSessionRow(boolean pty, TerminalTabs.Tab<?> tab) {
    TextView select =
        button(
            sessionLabel(pty, tab) + "\n" + sessionState(pty, tab),
            () -> {
              closeDrawer();
              dismissPanel();
              if (pty) owner.selectPty(tab.id);
              else owner.selectSimple(tab.id);
              switchTerminalMode(pty);
              root.post(
                  () -> {
                    if (pane() != null) pane().restoreInputFocus();
                  });
            });
    sessionList.addView(select, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout actions = new LinearLayout(this);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    TextView rename = button(UiText.choose("重命名", "Rename"), () -> rename(pty, tab.id));
    TextView close =
        button(
            tab.isCloseUnconfirmed()
                ? UiText.choose("核验并结束", "Verify and end")
                : UiText.choose("结束进程", "End process"),
            () -> requestCloseSession(pty, tab.id));
    rename.setEnabled(!tab.isClosing());
    close.setEnabled(!tab.isClosing());
    actions.addView(rename, new LinearLayout.LayoutParams(0, -2, 1));
    actions.addView(close, new LinearLayout.LayoutParams(0, -2, 1));
    sessionList.addView(actions, new LinearLayout.LayoutParams(-1, -2));
    if (!tab.closeError().isEmpty())
      sessionList.addView(label(SensitiveData.redact(tab.closeError())));
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

  private void createInMode(boolean pty) {
    if (changingMode
        || getSupportFragmentManager().isStateSaved()
        || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
      return;
    closeDrawer();
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
  protected void onSaveInstanceState(Bundle out) {
    out.putString("terminal_owner", owner.ownerEpoch());
    out.putBoolean(SESSIONS_OPEN, drawerOpen && !expanded);
    if (ball != null) {
      out.putBoolean("ball_right", ball.dockRight());
      out.putFloat("ball_fraction", ball.dockFraction());
    }
    super.onSaveInstanceState(out);
  }

  private TextView label(String text) {
    TextView value = new TextView(this);
    value.setText(text);
    value.setTextColor(getColor(R.color.text));
    value.setTextSize(14);
    value.setPadding(dp(12), dp(6), dp(12), dp(6));
    return value;
  }

  private TextView button(String text, Runnable action) {
    TextView value = label(text);
    value.setGravity(Gravity.CENTER);
    value.setMinHeight(dp(48));
    value.setMinWidth(dp(48));
    value.setFocusable(true);
    value.setClickable(true);
    android.util.TypedValue background = new android.util.TypedValue();
    getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true);
    value.setBackgroundResource(background.resourceId);
    value.setOnClickListener(v -> action.run());
    return value;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
