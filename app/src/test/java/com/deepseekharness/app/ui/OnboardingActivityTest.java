package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.PermissionGoals;
import com.deepseekharness.app.util.UiText;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

/**
 * 实际 OnboardingActivity 生命周期：首次进入、已就绪直达主界面、旋转保留步骤与用途。
 * 环境检查、部署监视与授权面板以影子替换，不初始化 Ubuntu、不启动任务、不读系统授权。
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = OnboardingActivityTest.App.class,
    shadows = {
      OnboardingActivityTest.Environment.class,
      OnboardingActivityTest.Monitor.class,
      OnboardingActivityTest.Panel.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class OnboardingActivityTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  @Implements(value = OnboardingEnvironment.class, isInAndroidSdk = false)
  public static final class Environment {
    static boolean ready;

    @Implementation
    protected static boolean readyForMain(Context context) {
      return ready;
    }

    @Implementation
    protected static long requiredBytes(Context context) {
      return 3L << 30;
    }

    @Implementation
    protected static long usableBytes(Context context) {
      return 20L << 30;
    }

    @Implementation
    protected static boolean installed(Context context) {
      return false;
    }
  }

  /** 部署监视器：不读 BackupTask、不轮询；始终“未开始、未就绪”。 */
  @Implements(value = DeployMonitor.class, isInAndroidSdk = false)
  public static final class Monitor {
    @Implementation
    protected void __constructor__(
        androidx.lifecycle.LifecycleOwner owner,
        Context context,
        java.util.function.Consumer<com.deepseekharness.app.util.DeployStages.View> listener) {}

    @Implementation
    protected com.deepseekharness.app.util.DeployStages.View current() {
      return com.deepseekharness.app.util.DeployStages.idle();
    }

    @Implementation
    protected boolean environmentReady() {
      return false;
    }

    @Implementation
    protected void refreshNow() {}
  }

  /** 授权面板：不读系统授权，只给出空视图与 0 / 7。 */
  @Implements(value = PermissionPanel.class, isInAndroidSdk = false)
  public static final class Panel {
    private View view;

    @Implementation
    protected void __constructor__(
        androidx.appcompat.app.AppCompatActivity activity,
        java.util.Set<String> goals,
        boolean environmentReady) {
      view = new View(activity);
    }

    @Implementation
    protected View view() {
      return view;
    }

    @Implementation
    protected void refresh() {}

    @Implementation
    protected void setEnvironmentReady(boolean ready) {}

    @Implementation
    protected int grantedCount() {
      return 0;
    }

    @Implementation
    protected java.util.List<String[]> summary() {
      return new java.util.ArrayList<>();
    }
  }

  @Before
  public void prepare() {
    UiText.setLanguage("zh");
    Context app = RuntimeEnvironment.getApplication();
    ConfigStore config = new ConfigStore(app);
    config.setWelcomed(false);
    config.setCheckUpdate(false);
    app.getSharedPreferences(OnboardingActivity.PREFS, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit();
    Environment.ready = false;
  }

  @Test
  public void firstEntryShowsWelcomeStepWithoutStartingAnything() {
    var host = Robolectric.buildActivity(OnboardingActivity.class).create().start().resume();
    OnboardingActivity page = host.get();
    assertFalse(page.isFinishing());
    assertEquals(OnboardingActivity.WELCOME, page.step);
    View root = page.findViewById(android.R.id.content);
    assertTrue(hasText(root, "1 / 4"));
    assertTrue(hasText(root, "开始部署"));
    assertFalse("未点开始前不能写入 welcomed", new ConfigStore(page).isWelcomed());
    var shadow =
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowActivity>extract(page);
    assertNull(shadow.getNextStartedActivity());
    host.pause().stop().destroy();
  }

  @Test
  public void welcomedAndReadyEnvironmentGoesStraightToMain() {
    new ConfigStore(RuntimeEnvironment.getApplication()).setWelcomed(true);
    Environment.ready = true;
    var host = Robolectric.buildActivity(OnboardingActivity.class).create();
    OnboardingActivity page = host.get();
    var shadow =
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowActivity>extract(page);
    Intent next = shadow.getNextStartedActivity();
    assertNotNull(next);
    assertEquals(MainActivity.class.getName(), next.getComponent().getClassName());
    assertFalse("直达主界面不是首次部署完成，不能预约自动启动 Web", next.hasExtra(FirstRunWebStart.EXTRA));
    assertFalse("受限入口标记只由用户显式选择", next.getBooleanExtra("limited_entry", false));
    assertTrue((next.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0);
    assertTrue(page.isFinishing());
    host.destroy();
  }

  @Test
  public void unwelcomedEntryCannotSkipPastWelcomeEvenWithSavedStep() {
    Context app = RuntimeEnvironment.getApplication();
    app.getSharedPreferences(OnboardingActivity.PREFS, Context.MODE_PRIVATE)
        .edit()
        .putInt("step", 3)
        .commit();
    var host = Robolectric.buildActivity(OnboardingActivity.class).create().start().resume();
    OnboardingActivity page = host.get();
    assertEquals(OnboardingActivity.WELCOME, page.step);
    page.show(OnboardingActivity.FINISH);
    assertEquals(OnboardingActivity.WELCOME, page.step);
    host.pause().stop().destroy();
  }

  @Test
  public void rotationKeepsStepAndGoalSelection() {
    new ConfigStore(RuntimeEnvironment.getApplication()).setWelcomed(true);
    var host = Robolectric.buildActivity(OnboardingActivity.class).create().start().resume();
    OnboardingActivity page = host.get();
    page.show(OnboardingActivity.GOALS);
    page.goals.add(PermissionGoals.SCREEN);
    assertEquals(OnboardingActivity.GOALS, page.step);
    Bundle saved = new Bundle();
    host.pause().saveInstanceState(saved).stop().destroy();
    var restoredHost =
        Robolectric.buildActivity(OnboardingActivity.class).create(saved).start().resume();
    OnboardingActivity restored = restoredHost.get();
    assertEquals(OnboardingActivity.GOALS, restored.step);
    assertTrue(restored.goals.contains(PermissionGoals.SCREEN));
    assertTrue("基础用途始终选中", restored.goals.contains(PermissionGoals.BASE));
    assertTrue(hasText(restored.findViewById(android.R.id.content), "2 / 4"));
    restored.getOnBackPressedDispatcher().onBackPressed();
    assertEquals("第 2 步返回上一步而不是退出", OnboardingActivity.WELCOME, restored.step);
    assertFalse(restored.isFinishing());
    restoredHost.pause().stop().destroy();
  }

  private static boolean hasText(View view, String text) {
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        if (hasText(group.getChildAt(i), text)) return true;
    return false;
  }
}
