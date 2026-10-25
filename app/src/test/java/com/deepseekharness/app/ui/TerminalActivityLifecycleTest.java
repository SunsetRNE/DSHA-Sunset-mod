package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import android.os.Bundle;
import android.view.View;
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
}
