package com.deepseekharness.app.ui;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.DeviceAuthorization;
import com.deepseekharness.app.util.RootModulePolicy;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.button.MaterialButton;
import org.json.JSONObject;

/** 用户显式读取的 F07 原生页；无自动探测、后台轮询、助手端点或模块写操作。 */
public final class RootModulesFragment extends Fragment {
  private final Handler main = new Handler(Looper.getMainLooper());
  private TextView status, entries;
  private MaterialButton refresh;
  private Thread worker;
  private long pageGeneration;

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    Context context = requireContext();
    ScrollView scroll = new ScrollView(context);
    scroll.setFillViewport(true);
    LinearLayout content = new LinearLayout(context);
    content.setOrientation(LinearLayout.VERTICAL);
    int padding = GrantsUi.dp(context, 16);
    content.setPadding(padding, padding, padding, padding);
    scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
    TextView title = new TextView(context, null, 0, R.style.ShellRowTitle);
    title.setText(UiText.choose("Root 模块清单", "Root module inventory"));
    title.setGravity(Gravity.CENTER);
    content.addView(title, new LinearLayout.LayoutParams(-1, -2));
    TextView explanation = new TextView(context, null, 0, R.style.ShellRowTitle);
    explanation.setText(
        UiText.choose(
            "仅在点击读取后申请本机 Root，读取已支持管理器的模块元数据。未知版本或无法核验时显示不可用；此页没有模块写操作。",
            "Root is requested only after you tap Read. This page reads module metadata for supported managers; unverified versions remain unavailable. It has no module write actions."));
    content.addView(explanation, new LinearLayout.LayoutParams(-1, -2));
    refresh = new MaterialButton(context);
    refresh.setText(UiText.choose("读取清单", "Read inventory"));
    refresh.setGravity(Gravity.CENTER);
    refresh.setIncludeFontPadding(false);
    content.addView(refresh, new LinearLayout.LayoutParams(-1, -2));
    status = new TextView(context, null, 0, R.style.ShellRowTitle);
    status.setText(UiText.choose("尚未读取", "Not read yet"));
    content.addView(status, new LinearLayout.LayoutParams(-1, -2));
    entries = new TextView(context, null, 0, R.style.ShellRowTitle);
    entries.setTextIsSelectable(true);
    content.addView(entries, new LinearLayout.LayoutParams(-1, -2));
    refresh.setOnClickListener(view -> readInventory());
    return scroll;
  }

  private void readInventory() {
    if (!isResumed() || refresh == null || worker != null && worker.isAlive()) return;
    Context app = requireContext().getApplicationContext();
    DeviceAuthorization.Snapshot authorization = ConfigStore.deviceAuthorization(app);
    if (!authorization.rootAllowed()) {
      status.setText(UiText.choose("请先允许 Root Shell", "Enable Root shell first"));
      entries.setText("");
      return;
    }
    long requestedPage = ++pageGeneration;
    refresh.setEnabled(false);
    entries.setText("");
    status.setText(UiText.choose("正在读取…", "Reading…"));
    worker =
        new Thread(
            () -> {
              String reply = RootShell.readRootModules(app);
              Thread completed = Thread.currentThread();
              main.post(
                  () -> {
                    if (worker == completed) worker = null;
                    if (!isAdded() || status == null || refresh == null) return;
                    applyReadResult(requestedPage, authorization, reply);
                  });
            },
            "dsha-root-module-read");
    worker.setDaemon(true);
    worker.start();
  }

  /** 迟到/暂停/授权变化的回调只能被丢弃，不能用旧结果覆盖当前页面。 */
  void applyReadResult(
      long requestedPage, DeviceAuthorization.Snapshot authorization, String reply) {
    if (!acceptsResult(isResumed(), requestedPage, pageGeneration)) return;
    if (status == null || refresh == null || entries == null) return;
    refresh.setEnabled(worker == null);
    if (!DeviceAuthorization.shared().current(authorization)) {
      showAuthorizationChanged();
      return;
    }
    render(reply);
  }

  /** 只有仍在前台且请求代次未推进的页面可以接收旧结果。 */
  static boolean acceptsResult(boolean resumed, long requestedPage, long currentPage) {
    return resumed && requestedPage == currentPage;
  }

  void showAuthorizationChanged() {
    entries.setText("");
    status.setText(UiText.choose("授权已变化，请重新核对", "Authorization changed. Check it again."));
  }

  private void render(String reply) {
    try {
      if (reply == null || reply.length() > 400_000) throw new IllegalArgumentException();
      JSONObject result = new JSONObject(reply);
      boolean complete = result.optBoolean("complete", false);
      String code = result.optString("code", "UNKNOWN");
      if (!result.optBoolean("ok") || !result.optBoolean("readOnly")) {
        entries.setText("");
        status.setText(
            switch (code) {
              case "ROOT_DISABLED" -> UiText.choose("请先允许 Root Shell", "Enable Root shell first");
              case "UNSUPPORTED_MANAGER_VERSION", "MANAGER_AMBIGUOUS" ->
                  UiText.choose("管理器版本尚不支持或无法核验", "Manager version unsupported or unverified");
              case "MODULE_ROOT_UNREADABLE" ->
                  UiText.choose("无法读取模块目录", "Module directory is unreadable");
              default -> UiText.choose("读取结果未知或不可用", "Inventory result unknown or unavailable");
            });
        return;
      }
      var modules = result.getJSONArray("modules");
      if (modules.length() > RootModulePolicy.MAX_ENTRIES) throw new IllegalArgumentException();
      status.setText(
          complete
              ? UiText.choose("清单读取完整", "Inventory complete")
              : UiText.choose("清单不完整", "Inventory incomplete"));
      if (complete && modules.length() == 0) {
        entries.setText(UiText.choose("没有模块", "No modules"));
        return;
      }
      StringBuilder text = new StringBuilder();
      for (int index = 0; index < modules.length(); index++) {
        JSONObject entry = modules.getJSONObject(index);
        String id = entry.getString("id");
        text.append(entry.optString("name", id));
        text.append("\n").append(id);
        String version = entry.optString("version");
        if (!version.isEmpty()) text.append(" · ").append(version);
        text.append("\n")
            .append(
                entry.optBoolean("removing")
                    ? UiText.choose("等待移除", "Removal pending")
                    : entry.optBoolean("disabled")
                        ? UiText.choose("已停用", "Disabled")
                        : UiText.choose("已启用", "Enabled"));
        text.append("\n\n");
      }
      entries.setText(UiText.raw(text.toString()));
    } catch (Exception invalid) {
      status.setText(UiText.choose("读取结果未知或不可用", "Inventory result unknown or unavailable"));
      entries.setText("");
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    if (refresh != null) refresh.setEnabled(worker == null || !worker.isAlive());
    // 恢复只允许用户重新发起独立读取，不自动重放旧请求或显示旧清单。
  }

  @Override
  public void onPause() {
    pageGeneration++;
    if (worker != null) worker.interrupt();
    if (entries != null) entries.setText("");
    if (status != null) status.setText(UiText.choose("尚未读取", "Not read yet"));
    super.onPause();
  }

  @Override
  public void onDestroyView() {
    pageGeneration++;
    refresh = null;
    status = null;
    entries = null;
    super.onDestroyView();
  }
}
