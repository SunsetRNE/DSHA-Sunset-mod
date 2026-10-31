package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.BackupTask;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.BackupTaskState;
import com.deepseekharness.app.util.DeployStages;
import com.deepseekharness.app.util.UiText;

/**
 * 运行环境维护详情页（与部署胶囊面板同一风格）：只展示应用级维护任务；旋转、返回或进程重建均不
 * 自动重复覆盖环境。自动开始与手动重试统一经 {@link RuntimeMaintenanceStarter}；维护成功文案不代替
 * 就绪检查，未就绪时留在本页、不自动跳转。设备审计会反射 render()、lastRender、automaticEntry 与
 * enter，并按历史 id 读取视图，名称不得改动。
 */
public class ExtractActivity extends AppCompatActivity {
  private final Handler main = new Handler(Looper.getMainLooper());
  private BackupTask task;
  private HarnessController controller;
  private OnboardingKit kit;
  private TextView status, detail, error, pill, lead, stageLine, detailLabel;
  private ViewGroup segments, stages;
  private View stageCard;
  private ProgressBar spinner, progress;
  private Button retry, enter;
  private long taskId;
  private boolean automaticEntry;
  private boolean automaticDeclined;
  private boolean rebuildRequested;
  private String lastRender = "";
  private Boolean cachedReady;
  private long cachedReadyTaskId = -1;
  private BackupTaskState.Status cachedReadyStatus;
  private boolean cachedReadyBusy, cachedReadyPending;
  private long cachedReadyAt;
  private boolean cachedInstalled, cachedCanUpdate;

  @Override
  protected void onCreate(@Nullable Bundle saved) {
    super.onCreate(saved);
    setContentView(R.layout.activity_extract);
    controller = HarnessController.get(this);
    task = BackupTask.get(this);
    kit = new OnboardingKit(this);
    rebuildRequested =
        saved != null
            ? saved.getBoolean("rebuild_requested", false)
            : getIntent().getBooleanExtra("force_extract", false)
                || com.deepseekharness.app.util.BackupTaskKinds.is(
                    com.deepseekharness.app.util.BackupTaskKinds.REBUILD, task.snapshot().kind);
    bindViews();
    taskId =
        saved == null
            ? getIntent().getLongExtra("data_task_id", 0)
            : saved.getLong("data_task_id", 0);
    automaticEntry = saved != null && saved.getBoolean("automatic_entry", false);
    automaticDeclined = saved != null && saved.getBoolean("automatic_declined", false);
    if (task.maintenanceBusy()) taskId = task.snapshot().id;
    else if (saved == null
        && taskId == 0
        && !task.pendingMaintenance()
        && !getIntent().getBooleanExtra("review_only", false)) {
      boolean force = getIntent().getBooleanExtra("force_extract", false);
      if (!force
          && controller.isEnvironmentReady()
          && !com.deepseekharness.app.core.EnvironmentAccess.requiresMaintenanceBeforeStart(
              controller)) {
        proceed();
        return;
      }
      if (!force) {
        // 同一候选只自动尝试一次（attempted_identity 先记录再启动），逻辑与主界面共用。
        RuntimeMaintenanceStarter.Auto started = RuntimeMaintenanceStarter.autoStart(this);
        if (started == RuntimeMaintenanceStarter.Auto.STARTED) {
          taskId = task.snapshot().id;
          automaticEntry = true;
        } else if (started == RuntimeMaintenanceStarter.Auto.DECLINED) automaticDeclined = true;
      }
    }
    // 从受限主界面重新查看时也展示上次失败；自动重试判定已完成，不能因此阻止修复包升级。
    BackupTaskState.Snapshot previous = task.snapshot();
    if (taskId == 0
        && (!controller.isEnvironmentReady()
            || !com.deepseekharness.app.core.EnvironmentAccess.runtimeLatest(controller))
        && (previous.status == BackupTaskState.Status.FAILED
            || previous.status == BackupTaskState.Status.INTERRUPTED)) taskId = previous.id;
    render();
  }

  private void bindViews() {
    status = findViewById(R.id.extract_status);
    detail = findViewById(R.id.extract_detail);
    error = findViewById(R.id.extract_error);
    pill = findViewById(R.id.extract_pill);
    lead = findViewById(R.id.extract_lead);
    stageLine = findViewById(R.id.extract_stage_line);
    detailLabel = findViewById(R.id.extract_detail_label);
    segments = findViewById(R.id.extract_segments);
    stages = findViewById(R.id.extract_stages);
    stageCard = findViewById(R.id.extract_stage_card);
    spinner = findViewById(R.id.extract_bar);
    progress = findViewById(R.id.extract_progress);
    ((TextView) findViewById(R.id.extract_title)).setText(UiText.text("运行环境维护"));
    retry = findViewById(R.id.extract_retry);
    enter = findViewById(R.id.extract_enter);
    Button emergency = findViewById(R.id.extract_emergency);
    Button logs = findViewById(R.id.extract_logs);
    emergency.setText(UiText.choose("启动应急 DSH", "Start emergency DSH"));
    emergency.setOnClickListener(v -> startActivity(new Intent(this, RecoveryActivity.class)));
    logs.setText(UiText.choose("查看本次维护记录", "View maintenance record"));
    logs.setOnClickListener(v -> startActivity(DiagnosticActivity.downloadLogs(this)));
    retry.setOnClickListener(
        v ->
            RuntimeMaintenanceStarter.confirmRetry(
                this,
                () -> {
                  taskId = task.snapshot().id;
                  automaticDeclined = false;
                  cachedReady = null;
                  render();
                },
                rebuildRequested));
    enter.setText(UiText.text("进入主界面"));
    enter.setOnClickListener(v -> proceed());
    // 宽屏（≥ 600dp）把正文与底栏收在约 640dp 宽的居中列内。
    if (kit.wide()) {
      int width = getResources().getDisplayMetrics().widthPixels;
      int side = Math.max(kit.dp(20), (width - kit.dp(640)) / 2);
      View content = findViewById(R.id.extract_content);
      content.setPadding(side, kit.dp(28), side, kit.dp(24));
      View footer = findViewById(R.id.extract_footer);
      footer.setPadding(side, footer.getPaddingTop(), side, footer.getPaddingBottom());
    }
  }

  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          render();
          if (!isFinishing() && !isDestroyed()) main.postDelayed(this, 1_000);
        }
      };

  private void render() {
    if (isFinishing() || isDestroyed()) return;
    BackupTaskState.Snapshot s = task.snapshot();
    boolean busy = task.maintenanceBusy(),
        pending = task.pendingMaintenanceForUi(),
        executionBusy = task.busy();
    boolean ready = environmentReady(s, busy, pending);
    String renderKey =
        s.id
            + ":"
            + s.status
            + ":"
            + s.detail
            + ":"
            + busy
            + ":"
            + executionBusy
            + ":"
            + pending
            + ":"
            + ready
            + ":"
            + taskId
            + ":"
            + automaticDeclined
            + ":"
            + UiText.language();
    if (renderKey.equals(lastRender)) return;
    lastRender = renderKey;
    if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    // 维护进行中才显示忙状态；插件查询、终端等通用锁不冒充数据维护。
    spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
    progress.setVisibility(View.GONE);
    ((TextView) findViewById(R.id.extract_title)).setText(UiText.text("运行环境维护"));
    boolean mine = taskId != 0 && taskId == s.id;
    DeployStages.Phase phase =
        busy
            ? DeployStages.Phase.RUNNING
            : pending
                ? DeployStages.Phase.PENDING
                : mine
                    ? DeployStages.phase(s.status, false, false, ready)
                    : DeployStages.Phase.IDLE;
    DeployStages.View view = DeployMonitor.stagesView(s, phase, cachedInstalled);
    boolean failed = mine && view.phase == DeployStages.Phase.FAILED;
    boolean incomplete = mine && s.status == BackupTaskState.Status.SUCCEEDED && !ready && !busy;
    renderHeader(s, view, busy, pending);
    renderStages(view);
    renderMessages(s, view, failed, incomplete, pending);
    boolean showLog = (mine || busy) && !s.detail.isEmpty();
    detailLabel.setVisibility(showLog ? View.VISIBLE : View.GONE);
    detailLabel.setText(UiText.choose("本次维护记录", "This maintenance record"));
    detail.setVisibility(showLog ? View.VISIBLE : View.GONE);
    detail.setText(
        showLog ? com.deepseekharness.app.util.MaintenanceErrorText.render(s.detail) : "");
    RuntimeMaintenanceStarter.Retry kind =
        pending
            ? RuntimeMaintenanceStarter.Retry.RECOVER
            : !rebuildRequested && cachedCanUpdate
                ? RuntimeMaintenanceStarter.Retry.UPDATE
                : !cachedInstalled
                    ? RuntimeMaintenanceStarter.Retry.DEPLOY
                    : RuntimeMaintenanceStarter.Retry.REBUILD;
    retry.setText(RuntimeMaintenanceStarter.retryLabel(kind, failed));
    retry.setEnabled(!executionBusy);
    enter.setText(
        !busy && !pending && ready ? UiText.text("进入主界面") : UiText.text("进入受限主界面 · 查看日志与配置"));
    if (com.deepseekharness.app.util.EnvironmentIdentity.mayAdvanceAfterMaintenance(
        automaticEntry, mine, s.status == BackupTaskState.Status.SUCCEEDED, busy, pending, ready)) {
      automaticEntry = false;
      proceed();
    }
  }

  private void renderHeader(
      BackupTaskState.Snapshot s, DeployStages.View view, boolean busy, boolean pending) {
    status.setText(
        busy
            ? com.deepseekharness.app.util.UiStateText.render(s.kind)
            : pending
                ? UiText.text("上次维护未完成，请先恢复原环境")
                : view.visible() ? view.label() : UiText.text("环境维护"));
    OnboardingKit.Tone tone = OnboardingKit.tone(view.phase);
    pill.setVisibility(view.visible() ? View.VISIBLE : View.GONE);
    if (view.visible()) {
      pill.setText(DeployCard.pillText(view));
      pill.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f);
      pill.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
      pill.setSingleLine(true);
      pill.setIncludeFontPadding(false);
      pill.setBackgroundResource(OnboardingKit.pillBackground(tone));
      pill.setTextColor(
          getColor(
              tone == OnboardingKit.Tone.NEUTRAL
                  ? R.color.shell_text_muted
                  : OnboardingKit.toneColor(tone)));
      pill.setPadding(kit.dp(9), kit.dp(3), kit.dp(9), kit.dp(3));
    }
    lead.setText(
        view.visible() && view.mode != DeployStages.Mode.GENERIC
            ? DeployTaskSheet.description(view.mode, false)
            : UiText.text("相同基础环境只更新 dsh 与内置插件，个人数据保持原位；基础环境变更时先保护数据再重建。验证失败可恢复原环境。"));
  }

  private void renderStages(DeployStages.View view) {
    segments.removeAllViews();
    stages.removeAllViews();
    boolean show = view.visible() && view.total() > 0;
    stageCard.setVisibility(show ? View.VISIBLE : View.GONE);
    if (!show) return;
    stageLine.setText(DeployCard.line(view));
    segments.addView(kit.segmentBar(view));
    stages.addView(kit.stageList(view));
  }

  private void renderMessages(
      BackupTaskState.Snapshot s,
      DeployStages.View view,
      boolean failed,
      boolean incomplete,
      boolean pending) {
    String message;
    boolean warn;
    if (failed) {
      message =
          UiText.format(
                  "未完成的操作：%s\n最后记录阶段：%s\n\n%s\n\n原件状态：尚未确认恢复完成。请查看记录后使用“恢复中断维护”；也可进入受限主界面导出可读副本。",
                  com.deepseekharness.app.util.UiStateText.render(s.kind),
                  s.lastStage.isEmpty()
                      ? UiText.choose(
                          "旧记录未提供；请查看维护日志",
                          "Not available in the old record; inspect maintenance logs")
                      : com.deepseekharness.app.util.LogCompactor.compactReason(
                          com.deepseekharness.app.util.UiStateText.render(s.lastStage), 240),
                  com.deepseekharness.app.util.MaintenanceErrorText.render(s.detail))
              + "\n\n"
              + UiText.text("任务未完成。请按上方原因处理后重试；本页不会自动覆盖环境。");
      warn = false;
    } else if (incomplete) {
      message =
          UiText.choose(
              "维护步骤已结束，但环境就绪检查未通过。已停止自动跳转，请查看原因或进入受限主界面。",
              "Maintenance ended, but readiness checks did not pass. Automatic navigation stopped. Review the reason or enter the limited interface.");
      warn = true;
    } else if (automaticDeclined) {
      message =
          UiText.choose(
              "本次自动维护未能启动。已有任务结束后可手动重试，不会反复启动维护。",
              "Automatic maintenance could not start. Retry manually after the current task ends; maintenance will not start repeatedly.");
      warn = true;
    } else if (pending) {
      message = DeployTaskSheet.message(view);
      warn = false;
    } else {
      message = "";
      warn = false;
    }
    error.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    error.setText(message);
    error.setBackgroundResource(warn ? R.drawable.bg_onb_hint_warn : R.drawable.bg_onb_hint_bad);
    error.setTextColor(getColor(warn ? R.color.shell_warn : R.color.shell_bad));
  }

  /**
   * 就绪检查会读取并解析运行时描述、健康回执和 Bash ELF。维护页每秒刷新进度，但这些文件只会在
   * 任务代次/状态变化时改变；逐帧重复检查会让失败页在静止时仍持续分配对象并触发 GC。状态变化
   * 立即重查，闲置时每 15 秒兜底重查。安装状态与可否原位更新随同一缓存刷新（决定重试文案）。
   */
  private boolean environmentReady(
      BackupTaskState.Snapshot snapshot, boolean busy, boolean pending) {
    long now = android.os.SystemClock.elapsedRealtime();
    if (cachedReady == null
        || cachedReadyTaskId != snapshot.id
        || cachedReadyStatus != snapshot.status
        || cachedReadyBusy != busy
        || cachedReadyPending != pending
        || now - cachedReadyAt >= 15_000) {
      cachedReady = controller.isEnvironmentReady();
      cachedInstalled = controller.proot().isEnvironmentInstalled();
      cachedCanUpdate = controller.proot().canUpdateManagedRuntime();
      cachedReadyTaskId = snapshot.id;
      cachedReadyStatus = snapshot.status;
      cachedReadyBusy = busy;
      cachedReadyPending = pending;
      cachedReadyAt = now;
    }
    return cachedReady;
  }

  private void proceed() {
    boolean limited =
        task.maintenanceBusy() || task.pendingMaintenance() || !controller.isEnvironmentReady();
    if (limited)
      controller
          .config()
          .allowLimitedEntry(
              com.deepseekharness.app.core.EnvironmentAccess.limitedEntryKey(controller));
    Intent intent =
        FirstRunWebStart.mainIntent(
            this, getIntent().getBooleanExtra("first_setup", false) && !limited);
    intent.putExtra("limited_entry", limited);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    startActivity(intent);
    finish();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putLong("data_task_id", taskId);
    state.putBoolean("automatic_entry", automaticEntry);
    state.putBoolean("automatic_declined", automaticDeclined);
    state.putBoolean("rebuild_requested", rebuildRequested);
    super.onSaveInstanceState(state);
  }

  @Override
  protected void onResume() {
    super.onResume();
    cachedReady = null;
    main.post(refresh);
  }

  @Override
  protected void onPause() {
    main.removeCallbacks(refresh);
    super.onPause();
  }
}
