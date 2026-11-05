package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.ContainerSshService;
import com.deepseekharness.app.util.ContainerSshPolicy;
import com.deepseekharness.app.util.LanAddressPolicy;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.materialswitch.MaterialSwitch;

/** 独立的容器远程调试设置模块；服务状态不由偏好开关伪造。 */
public final class ContainerSshActivity extends AppCompatActivity {
  private ConfigStore config;
  private MaterialSwitch enabled, lan;
  private TextView state, connection;
  private boolean rendering;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          render();
          handler.postDelayed(this, 1000);
        }
      };

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    config = new ConfigStore(this);
    CardPage page =
        new CardPage(
            this,
            t("容器 SSH 调试", "Container SSH debugging"),
            t(
                "电脑使用 SSH 连接 Ubuntu。默认关闭，仅本机监听；与网页局域网密码独立。",
                "Connect to Ubuntu from a computer. Off by default, loopback only; independent of the Web LAN password."));
    LinearLayout card = page.card();
    enabled = new MaterialSwitch(this);
    enabled.setText(t("启用容器 SSH", "Enable container SSH"));
    enabled.setMinimumHeight(page.dp(56));
    enabled.setContentDescription(enabled.getText());
    UiMotion.pressScale(enabled);
    card.addView(enabled, new LinearLayout.LayoutParams(-1, -2));
    lan = new MaterialSwitch(this);
    lan.setText(t("允许局域网连接", "Allow LAN connections"));
    lan.setMinimumHeight(page.dp(56));
    lan.setContentDescription(lan.getText());
    UiMotion.pressScale(lan);
    card.addView(lan, new LinearLayout.LayoutParams(-1, -2));
    state = page.text("", 13, R.color.shell_text_secondary);
    card.addView(state);
    // page.button 返回的是 AppCompatButton，强转 MaterialButton 会在打开本页时直接崩。
    page.button(card, t("设置 SSH 密码", "Set SSH password"), false, this::passwordDialog);
    LinearLayout connectionCard = page.card();
    connection = page.text("", 13, R.color.shell_text);
    connection.setTextIsSelectable(true);
    connectionCard.addView(connection);
    page.button(
        connectionCard, t("设置网页局域网密码", "Set Web LAN password"), false, this::lanPasswordDialog);
    page.button(page.footer, t("返回", "Back"), false, this::finish);
    enabled.setOnCheckedChangeListener(
        (button, checked) -> {
          if (rendering) return;
          if (checked && (!config.hasSshPassword() || !ContainerSshService.supported(this))) {
            Toast.makeText(
                    this,
                    t("请先安装服务端并设置密码", "Set a password after the SSH module is available"),
                    Toast.LENGTH_LONG)
                .show();
            render();
            return;
          }
          config.setSshEnabled(checked);
          if (checked && !ContainerSshService.start(this)) {
            config.setSshEnabled(false);
            Toast.makeText(
                    this,
                    t("环境未就绪或缺少局域网权限", "Runtime not ready or LAN permission missing"),
                    Toast.LENGTH_LONG)
                .show();
          }
          render();
        });
    lan.setOnCheckedChangeListener(
        (button, checked) -> {
          if (rendering) return;
          if (checked && !com.deepseekharness.app.bridge.LocalNetworkAccess.granted(this)) {
            Toast.makeText(this, t("请先授予局域网权限", "Grant LAN access first"), Toast.LENGTH_LONG)
                .show();
            render();
            return;
          }
          config.setSshEnabled(false);
          config.setSshLanEnabled(checked);
          render();
        });
    setContentView(page.root);
    render();
  }

  private void render() {
    if (enabled == null) return;
    rendering = true;
    enabled.setChecked(
        ContainerSshService.isActive()
            || config.isSshEnabled() && !"stopped".equals(ContainerSshService.status()));
    enabled.setEnabled(ContainerSshService.supported(this));
    lan.setChecked(config.isSshLanEnabled());
    lan.setEnabled(!ContainerSshService.isActive());
    state.setText(
        !ContainerSshService.supported(this)
            ? t(
                "当前 Ubuntu 尚未安装 OpenSSH 服务端。安装后再开启。",
                "OpenSSH server is not installed in this Ubuntu runtime.")
            : t("服务状态：", "Service status: ")
                + ContainerSshService.status()
                + "\n"
                + (config.hasSshPassword()
                    ? t("SSH 密码已保存", "SSH password saved")
                    : t("尚未设置 SSH 密码", "No SSH password set"))
                + "\n"
                + (config.hasLanPassword()
                    ? t("网页局域网密码已保存", "Web LAN password saved")
                    : t("尚未设置网页局域网密码", "No Web LAN password set")));
    connection.setText(
        connectionText(config.isSshLanEnabled(), LanAddressPolicy.currentDiscovery().addresses()));
    rendering = false;
  }

  static String connectionText(boolean lanEnabled, java.util.List<String> addresses) {
    StringBuilder text =
        new StringBuilder(
            t("本机转发地址（仅 adb forward）", "Local forwarding (adb forward only)")
                + "\nadb forward tcp:22222 tcp:22222\nssh -p 22222 dsha@127.0.0.1");
    if (addresses != null && !addresses.isEmpty()) {
      text.append("\n\n").append(t("手机局域网地址：", "Phone LAN addresses:"));
      for (String address : addresses) {
        text.append("\nssh -p 22222 dsha@").append(address);
      }
      if (!lanEnabled)
        text.append("\n\n").append(t("当前 SSH 局域网开关已关闭。", "SSH LAN access is currently off."));
    } else {
      text.append("\n\n")
          .append(
              t(
                  "未发现 Wi‑Fi/以太网私有 IPv4 地址；连接局域网后刷新。",
                  "No private Wi‑Fi/Ethernet IPv4 address found; connect to a LAN and refresh."));
    }
    return text.append("\n\n")
        .append(
            t(
                "127.0.0.1 仅用于 adb forward；关闭 SSH 会结束远程会话。维护前请先关闭。",
                "127.0.0.1 is for adb forward only; stopping SSH closes remote sessions."))
        .toString();
  }

  private void lanPasswordDialog() {
    LanPasswordDialog.show(this, config, this::render);
  }

  private void passwordDialog() {
    LinearLayout fields = new LinearLayout(this);
    fields.setOrientation(LinearLayout.VERTICAL);
    int padding = Math.round(20 * getResources().getDisplayMetrics().density);
    fields.setPadding(padding, 0, padding, 0);
    EditText password = new EditText(this), confirm = new EditText(this);
    password.setHint(t("新密码（8–128 字符）", "New password (8–128 characters)"));
    confirm.setHint(t("再次输入密码", "Confirm password"));
    for (EditText field : new EditText[] {password, confirm}) {
      field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
      field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
      fields.addView(field, new LinearLayout.LayoutParams(-1, -2));
    }
    android.widget.ScrollView scroll = new android.widget.ScrollView(this);
    scroll.addView(fields);
    var dialog =
        new DshaDialogBuilder(this)
            .setTitle(t("SSH 密码", "SSH password"))
            .setView(scroll)
            .setNegativeButton(t("取消", "Cancel"), null)
            .setPositiveButton(t("保存", "Save"), null)
            .create();
    dialog.show();
    dialog
        .getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        .setOnClickListener(
            view -> {
              String value = password.getText().toString();
              if (!ContainerSshPolicy.validPassword(value)
                  || !value.equals(confirm.getText().toString())) {
                confirm.setError(t("密码不合法或两次输入不同", "Invalid password or confirmation mismatch"));
                return;
              }
              if (config.isSshEnabled() || ContainerSshService.isActive()) {
                config.setSshEnabled(false);
                Toast.makeText(
                        this,
                        t(
                            "正在关闭会话，请稍后再保存密码",
                            "Stopping SSH; save the password again after sessions close"),
                        Toast.LENGTH_LONG)
                    .show();
                render();
                return;
              }
              boolean saved = config.setSshPassword(value);
              password.setText("");
              confirm.setText("");
              if (saved) dialog.dismiss();
              else
                confirm.setError(
                    t("本机密钥暂时不可用，密码未保存", "Device key unavailable; password not saved"));
              render();
            });
  }

  @Override
  protected void onResume() {
    super.onResume();
    handler.post(refresh);
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(refresh);
    super.onPause();
  }

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }
}
