package com.deepseekharness.app.ui;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.LanPassword;
import com.deepseekharness.app.util.UiText;

/** 统一的 LAN 密码设置入口；保存完成后由调用方刷新实际监听和地址状态。 */
final class LanPasswordDialog {
  private LanPasswordDialog() {}

  static void show(Context context, ConfigStore config, Runnable onSaved) {
    LinearLayout fields = new LinearLayout(context);
    fields.setOrientation(LinearLayout.VERTICAL);
    int padding = Math.round(20 * context.getResources().getDisplayMetrics().density);
    fields.setPadding(padding, 0, padding, 0);
    EditText password = new EditText(context), confirmation = new EditText(context);
    password.setHint(UiText.choose("新密码（8–128 字符）", "New password (8–128 characters)"));
    confirmation.setHint(UiText.choose("再次输入密码", "Confirm password"));
    for (EditText field : new EditText[] {password, confirmation}) {
      field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
      field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
      fields.addView(field, new LinearLayout.LayoutParams(-1, -2));
    }
    android.widget.ScrollView scroll = new android.widget.ScrollView(context);
    scroll.addView(fields);
    var dialog =
        new DshaDialogBuilder(context)
            .setTitle(UiText.choose("局域网访问密码", "LAN access password"))
            .setMessage(
                UiText.choose(
                    "用于网页局域网访问；不会用于 SSH 登录。",
                    "Used for Web LAN access; it is not the SSH login password."))
            .setView(scroll)
            .setNegativeButton(UiText.choose("取消", "Cancel"), null)
            .setPositiveButton(UiText.choose("保存", "Save"), null)
            .create();
    dialog.show();
    dialog
        .getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        .setOnClickListener(
            ignored -> {
              String value = password.getText().toString();
              if (!LanPassword.validPassword(value)
                  || !value.equals(confirmation.getText().toString())) {
                confirmation.setError(
                    UiText.choose("密码不合法或两次输入不同", "Invalid password or confirmation mismatch"));
                return;
              }
              dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setEnabled(false);
              new Thread(
                      () -> {
                        boolean saved = false;
                        try {
                          config.setLanPassword(value);
                          saved = true;
                        } catch (RuntimeException ignoredFailure) {
                          // Do not log exception details: the input is a secret.
                        }
                        boolean success = saved;
                        new android.os.Handler(android.os.Looper.getMainLooper())
                            .post(
                                () -> {
                                  if (!success) {
                                    dialog
                                        .getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                                        .setEnabled(true);
                                    confirmation.setError(
                                        UiText.choose(
                                            "本机密钥暂时不可用，密码未保存",
                                            "Device key unavailable; password not saved"));
                                    Toast.makeText(
                                            context,
                                            UiText.choose("局域网密码未保存", "LAN password was not saved"),
                                            Toast.LENGTH_LONG)
                                        .show();
                                    return;
                                  }
                                  password.setText("");
                                  confirmation.setText("");
                                  dialog.dismiss();
                                  if (onSaved != null) onSaved.run();
                                });
                      },
                      "dsha-lan-password")
                  .start();
            });
  }
}
