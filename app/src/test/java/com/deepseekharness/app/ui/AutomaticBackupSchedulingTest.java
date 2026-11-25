package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.backup.AutomaticBackups;
import com.deepseekharness.app.backup.PostUpgradeCleanupService;
import com.deepseekharness.app.util.UiText;
import java.util.List;
import org.junit.After;
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
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowJobScheduler;

/** 从自动备份实际按钮检查反馈与系统作业，并用隔离调度器模拟启动时的系统拒绝。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = AutomaticBackupSchedulingTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AutomaticBackupSchedulingTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private String previousLanguage;

  @Before
  public void chinese() {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
  }

  @After
  public void restoreLanguage() {
    UiText.setLanguage(previousLanguage);
  }

  @Test
  public void nowReportsRejectionAndLeavesNoPendingJob() {
    try (var host = Robolectric.buildActivity(AutomaticBackupActivity.class).setup()) {
      var activity = host.get();
      var scheduler = activity.getSystemService(JobScheduler.class);
      assertNotNull(scheduler);
      var root = activity.findViewById(android.R.id.content);
      View create = findText(root, "现在创建自动备份");
      assertTrue(create instanceof Button);
      Shadow.<ShadowJobScheduler>extract(scheduler).failOnJob(AutomaticBackups.NOW);
      assertTrue(create.performClick());
      assertNull(scheduler.getPendingJob(AutomaticBackups.NOW));
      assertNull(findText(root, "已安排，稍后查看结果"));
      assertNotNull(findText(root, "系统未接受备份任务，请稍后再试"));
    }
  }

  @Test
  public void nowReportsScheduledWithActualAcceptedJob() {
    try (var host = Robolectric.buildActivity(AutomaticBackupActivity.class).setup()) {
      var activity = host.get();
      var scheduler = activity.getSystemService(JobScheduler.class);
      assertNotNull(scheduler);
      var root = activity.findViewById(android.R.id.content);
      View create = findText(root, "现在创建自动备份");
      assertTrue(create instanceof Button);
      assertTrue(create.performClick());
      JobInfo work = scheduler.getPendingJob(AutomaticBackups.NOW);
      assertNotNull(work);
      assertTrue(work.getExtras().getBoolean("manual"));
      assertEquals(1500, work.getMinLatencyMillis());
      assertNotNull(findText(root, "已安排，稍后查看结果"));
    }
  }

  @Test
  @Config(shadows = RejectingScheduler.class)
  public void periodicBackupCanFailWithoutAbortingStartup() {
    Context app = RuntimeEnvironment.getApplication();
    var scheduler = app.getSystemService(JobScheduler.class);
    var rejecting = Shadow.<RejectingScheduler>extract(scheduler);
    AutomaticBackups.schedule(app);
    assertEquals(List.of(AutomaticBackups.JOB), rejecting.attempted);
    assertTrue(scheduler.getAllPendingJobs().isEmpty());
  }

  @Test
  @Config(shadows = RejectingScheduler.class)
  public void postUpgradeCleanupCanFailWithoutAbortingStartup() {
    Context app = RuntimeEnvironment.getApplication();
    var scheduler = app.getSystemService(JobScheduler.class);
    var rejecting = Shadow.<RejectingScheduler>extract(scheduler);
    PostUpgradeCleanupService.schedule(app);
    assertEquals(List.of(9139), rejecting.attempted);
    assertTrue(scheduler.getAllPendingJobs().isEmpty());
  }

  private static View findText(View root, String label) {
    if (root instanceof TextView text && label.contentEquals(text.getText())) return root;
    if (root instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findText(group.getChildAt(i), label);
        if (found != null) return found;
      }
    }
    return null;
  }

  @Implements(className = "android.app.JobSchedulerImpl")
  public static final class RejectingScheduler extends ShadowJobScheduler.ShadowJobSchedulerImpl {
    final List<Integer> attempted = new java.util.ArrayList<>();

    @Implementation
    @Override
    public int schedule(JobInfo job) {
      attempted.add(job.getId());
      if (job.getId() == AutomaticBackups.JOB) throw new IllegalStateException("schedule denied");
      throw new SecurityException("schedule denied");
    }
  }
}
