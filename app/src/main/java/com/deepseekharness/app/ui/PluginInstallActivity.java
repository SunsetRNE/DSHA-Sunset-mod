package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.plugins.PluginManagementEngine;
import com.deepseekharness.app.plugins.PluginOperationQueue;
import com.deepseekharness.app.util.PluginInstallLink;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.UiStateText;
import com.deepseekharness.app.util.UiText;
import java.util.Objects;

/** 网站唤起的安装页只跟踪自己的操作身份；关闭或重建页面不停止后台任务。 */
public final class PluginInstallActivity extends AppCompatActivity {
  private PluginManagementEngine repository;
  private PluginInstallLink request;
  private String requestLink;
  private String operationId;
  private Button install, prepare;
  private TextView status, details;

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    String link =
        saved != null && saved.containsKey("pluginRequestLink")
            ? saved.getString("pluginRequestLink")
            : getIntent().getDataString();
    showRequest(link, saved == null ? null : saved.getString("pluginOperationId"));
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (!Objects.equals(requestLink, intent.getDataString()))
      showRequest(intent.getDataString(), null);
  }

  private void showRequest(String link, String savedId) {
    // 换来源时重建控件，让原页面尚未派发的点击仍绑定原选择。
    setContentView(R.layout.activity_plugin_install);
    PressFeedback.attachButtons(findViewById(android.R.id.content));
    status = findViewById(R.id.link_install_status);
    details = findViewById(R.id.link_install_details);
    install = findViewById(R.id.link_install_confirm);
    prepare = findViewById(R.id.link_install_prepare);
    findViewById(R.id.link_install_close).setOnClickListener(v -> finish());
    findViewById(R.id.link_install_manage).setOnClickListener(v -> openMain(true));
    operationId = savedId == null || savedId.trim().isEmpty() ? null : savedId;
    request = null;
    requestLink = null;
    try {
      request = PluginInstallLink.parse(link);
      requestLink = link;
    } catch (IllegalArgumentException error) {
      status.setText(UiText.text(error.getMessage()));
      install.setEnabled(false);
      return;
    }
    if (!request.builtin.isEmpty()) {
      status.setText(UiText.format("请在插件管理中查看 %s 的安装和启用状态", request.builtin));
      install.setText(UiText.text("打开插件管理"));
      install.setOnClickListener(v -> openMain(true));
      return;
    }
    PluginInstallLink selected = request;
    install.setOnClickListener(v -> confirmInstall(selected));
    if (repository == null) {
      repository = PluginManagementEngine.get(this);
      repository.state().observe(this, ignored -> renderOperation());
    }
    if (operationId == null
        && HarnessController.get(this).isEnvironmentReady()
        && repository.registry().snapshot().source == null) repository.ensureLoaded();
    renderOperation();
  }

  @Override
  protected void onResume() {
    super.onResume();
    renderOperation();
  }

  @Override
  protected void onSaveInstanceState(Bundle saved) {
    super.onSaveInstanceState(saved);
    saved.putString("pluginRequestLink", requestLink);
    if (operationId != null) saved.putString("pluginOperationId", operationId);
  }

  private void confirmInstall(PluginInstallLink selected) {
    if (request != selected || !install.isEnabled() || isFinishing() || isDestroyed()) return;
    if (!HarnessController.get(this).isEnvironmentReady()
        || repository.registry().snapshot().source == null) {
      renderOperation();
      return;
    }
    install.setEnabled(false);
    operationId =
        repository.inspect(selected.source, selected.sha256, selected.name, selected.version);
    renderOperation();
  }

  private void renderOperation() {
    PluginInstallLink selected = request;
    if (selected == null || selected.source == null) return;
    PluginOperationQueue.Operation operation = repository.operation(operationId);
    var preview = repository.operationPreview(operationId);
    var source = selected.source;
    String origin = SensitiveData.redact(source.url.isEmpty() ? source.description() : source.url);
    String description = UiText.format("下载并解析插件包后自动安装、启用。\n\n%s", origin);
    details.setText(preview == null ? description : description + "\n\n" + preview.description());
    prepare.setVisibility(View.GONE);
    prepare.setOnClickListener(null);
    install.setVisibility(View.VISIBLE);
    if (operation != null) {
      UiMotion.crossFadeText(status, UiStateText.render(operation.message));
      boolean installed =
          operation.phase == PluginOperationQueue.Phase.SUCCEEDED
              && operation.transactionId != null
              && !operation.transactionId.isEmpty();
      if (operation.pending() || installed) {
        install.setEnabled(false);
        UiMotion.crossFadeText(install, UiText.text(installed ? "安装完成" : "正在安装"));
        return;
      }
    } else if (operationId != null) {
      UiMotion.crossFadeText(status, UiText.text("应用进程已重建；原任务结果未知，请先到插件管理核对。不会自动重放安装。"));
    } else {
      status.setText("");
    }
    if (!HarnessController.get(this).isEnvironmentReady()) {
      status.setText(UiText.text("请先完成首次初始化"));
      showPreparation(
          UiText.text("初始化 DSHA"),
          () -> {
            if (selected != request) return;
            getSharedPreferences("dsha-install-link", MODE_PRIVATE)
                .edit()
                .putString("pending", requestLink)
                .apply();
            openMain(false);
            finish();
          });
      return;
    }
    var catalog = repository.registry().snapshot();
    if (catalog.source == null) {
      if (operationId == null)
        status.setText(
            catalog.error.isEmpty()
                ? R.string.plugin_catalog_reading
                : R.string.plugin_catalog_failed);
      showPreparation(getText(R.string.ui_m0212), repository::refresh);
      return;
    }
    install.setEnabled(true);
    install.setText(R.string.ui_m0106);
  }

  private void showPreparation(CharSequence label, Runnable action) {
    install.setEnabled(false);
    install.setVisibility(View.GONE);
    prepare.setText(label);
    prepare.setVisibility(View.VISIBLE);
    prepare.setOnClickListener(v -> action.run());
  }

  private void openMain(boolean plugins) {
    startActivity(MainActivity.navigationIntent(this).putExtra("open_plugins", plugins));
  }
}
