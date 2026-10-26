package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.TerminalSessionOwner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** 实际Activity视图/Back/重建；fake只替代终端输入面，不运行真实guest。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = TerminalNavigationTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TerminalActivityLifecycleTest {
  public static class Host extends TerminalActivity {
    @Override
    protected Fragment createPane(boolean pty, boolean skipInitial) {
      var pane = new FakePane();
      Bundle args = new Bundle();
      args.putBoolean("pty", pty);
      args.putBoolean("terminal_skip_initial_create", skipInitial);
      pane.setArguments(args);
      return pane;
    }
  }

  public static class FakePane extends Fragment implements TerminalPane {
    static int creates, closes, input;
    boolean selection;

    public FakePane() {
      super(android.R.layout.simple_list_item_1);
    }

    public void newSession() {
      creates++;
    }

    public boolean selectSession(long id) {
      return true;
    }

    public void closeSession(long id) {
      closes++;
    }

    public void changeFont(int value) {}

    public void showKeyboard() {}

    public void paste() {
      input++;
    }

    public void restoreInputFocus() {}

    public boolean selectionBack() {
      boolean before = selection;
      selection = false;
      return before;
    }

    public boolean isPty() {
      return getArguments().getBoolean("pty");
    }
  }

  @Before
  public void prepare() {
    new ConfigStore(RuntimeEnvironment.getApplication()).setWelcomed(true);
    RuntimeEnvironment.getApplication()
        .getSharedPreferences("deepseekharness", 0)
        .edit()
        .putBoolean("terminal_workbench", false)
        .apply();
    FakePane.creates = FakePane.closes = FakePane.input = 0;
  }

  @Test
  public void minimalHostKeepsTerminalAndPageBackDoesNotCloseOrWrite() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    assertEquals(
        View.GONE, host.get().findViewById(R.id.terminal_workspace_toolbar).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    assertEquals(View.VISIBLE, host.get().findViewById(R.id.terminal_menu_ball).getVisibility());
    host.get().getOnBackPressedDispatcher().onBackPressed();
    assertTrue(host.get().isFinishing());
    host.pause().stop().destroy();
    assertEquals(0, FakePane.closes);
    assertEquals(0, FakePane.input);
    assertEquals(0, FakePane.creates);
  }

  @Test
  public void floatingBallOpensSessionsDrawerAndBackClosesItFirst() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    measure(host.get(), host.get().findViewById(R.id.terminal_workspace), 400, 900);
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    host.get().findViewById(R.id.terminal_menu_ball).performClick();
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_scrim).getVisibility());
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        host.get().findViewById(R.id.terminal_content).getImportantForAccessibility());
    assertEquals(
        ViewGroup.FOCUS_BLOCK_DESCENDANTS,
        ((ViewGroup) host.get().findViewById(R.id.terminal_content)).getDescendantFocusability());
    assertFalse(host.get().isFinishing());
    host.get().findViewById(R.id.terminal_sessions_close).performClick();
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
        host.get().findViewById(R.id.terminal_content).getImportantForAccessibility());
    assertEquals(
        ViewGroup.FOCUS_BEFORE_DESCENDANTS,
        ((ViewGroup) host.get().findViewById(R.id.terminal_content)).getDescendantFocusability());
    host.get().findViewById(R.id.terminal_menu_ball).performClick();
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    host.get().getOnBackPressedDispatcher().onBackPressed();
    assertFalse(host.get().isFinishing());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    host.get().getOnBackPressedDispatcher().onBackPressed();
    assertTrue(host.get().isFinishing());
    host.pause().stop().destroy();
    assertEquals(0, FakePane.closes);
    assertEquals(0, FakePane.input);
    assertEquals(0, FakePane.creates);
  }

  @Test
  public void workbenchMenuOpensDrawerBelowExpandedBreakpoint() {
    RuntimeEnvironment.getApplication()
        .getSharedPreferences("deepseekharness", 0)
        .edit()
        .putBoolean("terminal_workbench", true)
        .apply();
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var workspace = host.get().findViewById(R.id.terminal_workspace);
    float density = host.get().getResources().getDisplayMetrics().density;
    int width = Math.round(400 * density), height = Math.round(1200 * density);
    workspace.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    workspace.layout(0, 0, width, height);
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_workspace_toolbar).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_menu_ball).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_workspace_menu).getVisibility());
    host.get().findViewById(R.id.terminal_workspace_menu).performClick();
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    host.pause().stop().destroy();
  }

  @Test
  public void expandedWorkbenchUsesInlineSidebarAndNoDrawer() {
    RuntimeEnvironment.getApplication()
        .getSharedPreferences("deepseekharness", 0)
        .edit()
        .putBoolean("terminal_workbench", true)
        .apply();
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var workspace = host.get().findViewById(R.id.terminal_workspace);
    float density = host.get().getResources().getDisplayMetrics().density;
    int width = Math.round(900 * density), height = Math.round(1200 * density);
    workspace.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    workspace.layout(0, 0, width, height);
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_menu_ball).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_workspace_menu).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    host.pause().stop().destroy();
  }

  @Test
  public void openDrawerStateSurvivesSameProcessRecreateWithoutCreatingSessions() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    host.get().findViewById(R.id.terminal_menu_ball).performClick();
    Bundle saved = new Bundle();
    host.pause().saveInstanceState(saved).stop().destroy();
    var next = Robolectric.buildActivity(Host.class).create(saved).start().resume();
    assertEquals(
        View.VISIBLE, next.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    assertEquals(0, FakePane.creates);
    assertEquals(0, FakePane.closes);
    assertEquals(0, FakePane.input);
    next.pause().stop().destroy();
  }

  @Test
  public void selectionBackAndImeBackEachConsumeOnlyOneLayer() throws Exception {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var pane =
        (FakePane) host.get().getSupportFragmentManager().findFragmentById(R.id.terminal_content);
    pane.selection = true;
    host.get().getOnBackPressedDispatcher().onBackPressed();
    assertFalse(host.get().isFinishing());
    assertFalse(pane.selection);
    var ime = TerminalActivity.class.getDeclaredField("imeVisible");
    ime.setAccessible(true);
    ime.setBoolean(host.get(), true);
    host.get().getOnBackPressedDispatcher().onBackPressed();
    assertFalse(host.get().isFinishing());
    assertEquals(0, FakePane.input);
    ime.setBoolean(host.get(), false);
    host.pause().stop().destroy();
  }

  @Test
  public void sameProcessRecreateKeepsOwnerAndDoesNotCreateCommands() {
    var owner = TerminalSessionOwner.shared();
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    Bundle saved = new Bundle();
    host.pause().saveInstanceState(saved).stop().destroy();
    var next = Robolectric.buildActivity(Host.class).create(saved).start().resume();
    assertSame(owner, TerminalSessionOwner.shared());
    assertEquals(0, FakePane.creates);
    assertEquals(0, FakePane.closes);
    assertEquals(0, FakePane.input);
    next.pause().stop().destroy();
  }

  @Test
  public void minimalTabletWideOpensInlineSidebarAndKeepsTerminalAccessible() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var workspace = host.get().findViewById(R.id.terminal_workspace);
    measure(host.get(), workspace, 900, 700);
    host.get().findViewById(R.id.terminal_menu_ball).performClick();
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_close).getVisibility());
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
        host.get().findViewById(R.id.terminal_content).getImportantForAccessibility());
    assertFalse(host.get().isFinishing());
    host.get().getOnBackPressedDispatcher().onBackPressed();
    assertFalse(host.get().isFinishing());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    host.get().findViewById(R.id.terminal_menu_ball).performClick();
    host.get().findViewById(R.id.terminal_sessions_close).performClick();
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    host.pause().stop().destroy();
  }

  @Test
  @Config(
      sdk = 34,
      application = TerminalNavigationTest.App.class,
      qualifiers = "w900dp-h700dp-ldrtl")
  public void minimalRtlInlineHidesBallInsteadOfOverlappingPanel() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var workspace = host.get().findViewById(R.id.terminal_workspace);
    measure(host.get(), workspace, 900, 700);
    host.get().findViewById(R.id.terminal_menu_ball).performClick();
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_menu_ball).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_close).getVisibility());
    host.get().findViewById(R.id.terminal_sessions_close).performClick();
    assertEquals(View.VISIBLE, host.get().findViewById(R.id.terminal_menu_ball).getVisibility());
    host.pause().stop().destroy();
  }

  @Test
  public void minimalTabletPortraitUsesModalDrawer() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var workspace = host.get().findViewById(R.id.terminal_workspace);
    measure(host.get(), workspace, 640, 900);
    host.get().findViewById(R.id.terminal_menu_ball).performClick();
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        host.get().findViewById(R.id.terminal_content).getImportantForAccessibility());
    assertEquals(
        View.GONE, host.get().findViewById(R.id.terminal_sessions_close_all).getVisibility());
    host.get().findViewById(R.id.terminal_sessions_close).performClick();
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    host.pause().stop().destroy();
  }

  @Test
  public void restoredSidebarSwitchesBetweenDrawerAndInlineWithoutCreatingSessions() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var workspace = host.get().findViewById(R.id.terminal_workspace);
    measure(host.get(), workspace, 640, 900);
    host.get().findViewById(R.id.terminal_menu_ball).performClick();
    Bundle saved = new Bundle();
    host.pause().saveInstanceState(saved).stop().destroy();
    var next = Robolectric.buildActivity(Host.class).create(saved).start().resume();
    var nextWorkspace = next.get().findViewById(R.id.terminal_workspace);
    measure(next.get(), nextWorkspace, 900, 700);
    assertEquals(
        View.VISIBLE, next.get().findViewById(R.id.terminal_sessions_panel).getVisibility());
    assertEquals(View.GONE, next.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
        next.get().findViewById(R.id.terminal_content).getImportantForAccessibility());
    measure(next.get(), nextWorkspace, 640, 900);
    assertEquals(
        View.VISIBLE, next.get().findViewById(R.id.terminal_sessions_host).getVisibility());
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        next.get().findViewById(R.id.terminal_content).getImportantForAccessibility());
    assertEquals(0, FakePane.creates);
    assertEquals(0, FakePane.closes);
    assertEquals(0, FakePane.input);
    next.pause().stop().destroy();
  }

  @Test
  public void workbenchExpandedAddsSettingsAndHelpToTheTopbar() {
    RuntimeEnvironment.getApplication()
        .getSharedPreferences("deepseekharness", 0)
        .edit()
        .putBoolean("terminal_workbench", true)
        .apply();
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var workspace = host.get().findViewById(R.id.terminal_workspace);
    measure(host.get(), workspace, 900, 700);
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_workspace_settings).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_workspace_help).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_workspace_menu).getVisibility());
    measure(host.get(), workspace, 400, 700);
    assertEquals(
        View.GONE, host.get().findViewById(R.id.terminal_workspace_settings).getVisibility());
    assertEquals(View.GONE, host.get().findViewById(R.id.terminal_workspace_help).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_workspace_menu).getVisibility());
    host.pause().stop().destroy();
  }

  @Test
  public void shortWindowsHideSecondaryChromeAndUseDenseFooterActions() {
    var host = Robolectric.buildActivity(Host.class).create().start().resume();
    var workspace = host.get().findViewById(R.id.terminal_workspace);
    measure(host.get(), workspace, 400, 470);
    assertEquals(
        View.GONE, host.get().findViewById(R.id.terminal_sessions_subtitle).getVisibility());
    assertEquals(
        View.GONE, host.get().findViewById(R.id.terminal_sessions_secondary).getVisibility());
    assertEquals(
        View.VISIBLE,
        host.get().findViewById(R.id.terminal_sessions_actions_dense).getVisibility());
    assertEquals(
        View.GONE, host.get().findViewById(R.id.terminal_sessions_actions).getVisibility());
    measure(host.get(), workspace, 400, 700);
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_subtitle).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_secondary).getVisibility());
    assertEquals(
        View.GONE, host.get().findViewById(R.id.terminal_sessions_actions_dense).getVisibility());
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_sessions_actions).getVisibility());
    host.pause().stop().destroy();
  }

  @Test
  public void staleProcessBundleDoesNotRecoverOldIdentityOrAutocreate() {
    Bundle saved = new Bundle();
    saved.putString("terminal_owner", "old-process");
    var host = Robolectric.buildActivity(Host.class).create(saved).start().resume();
    Fragment pane = host.get().getSupportFragmentManager().findFragmentById(R.id.terminal_content);
    assertTrue(pane.getArguments().getBoolean("terminal_skip_initial_create"));
    assertEquals(
        View.VISIBLE, host.get().findViewById(R.id.terminal_workspace_status).getVisibility());
    assertEquals(0, FakePane.creates);
    assertEquals(0, FakePane.input);
    host.pause().stop().destroy();
  }

  private static void measure(
      android.app.Activity activity, View workspace, int widthDp, int heightDp) {
    float density = activity.getResources().getDisplayMetrics().density;
    int width = Math.round(widthDp * density), height = Math.round(heightDp * density);
    workspace.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    workspace.layout(0, 0, width, height);
  }
}
