package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.ScreenFramePolicy;
import com.deepseekharness.app.util.UiText;

/** 显式按应用启用动态坐标观察；编辑或选择应用本身不保存、不授予屏幕权限。 */
final class ScreenFrameSettingsDialog {
  private ScreenFrameSettingsDialog() {}

  static String title() {
    return UiText.choose("动态画面点按", "Taps on animated screens");
  }

  static void show(Context context, Runnable changed) {
    var config = new ConfigStore(context);
    float density = context.getResources().getDisplayMetrics().density;
    java.util.function.IntUnaryOperator dp = value -> Math.round(value * density);
    LinearLayout content = new LinearLayout(context);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp.applyAsInt(20), dp.applyAsInt(8), dp.applyAsInt(20), dp.applyAsInt(8));
    TextView explanation = new TextView(context);
    explanation.setTextSize(14);
    explanation.setTextColor(context.getColor(R.color.shell_text_secondary));
    explanation.setText(
        UiText.choose(
            "仅对你添加的应用，允许使用5秒内观察到的动态画面点按或滑动。其他应用保持严格校验。此设置不授予屏幕权限，也不跳过现有确认；仅保存在本机。",
            "For apps you add, allow taps and swipes using an animated screen observed within 5 seconds. Other apps keep strict checks. This does not grant screen access or skip existing confirmations, and stays on this device."));
    content.addView(explanation, new LinearLayout.LayoutParams(-1, -2));
    var packages = new androidx.appcompat.widget.AppCompatEditText(context);
    packages.setTextSize(14);
    packages.setMinLines(3);
    packages.setMaxLines(7);
    packages.setGravity(Gravity.TOP | Gravity.START);
    packages.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    packages.setHint(
        UiText.choose(
            "每行一个完整包名；留空恢复全部严格",
            "One full package name per line; leave empty for strict checks everywhere"));
    packages.setText(config.screenAnimatedPackages());
    content.addView(packages, new LinearLayout.LayoutParams(-1, -2));
    var picker = new com.google.android.material.button.MaterialButton(context);
    picker.setText(UiText.choose("选择应用", "Choose app"));
    picker.setGravity(Gravity.CENTER);
    picker.setOnClickListener(
        v -> {
          java.util.Map<String, String> selected = new java.util.HashMap<>();
          try {
            for (String entry : ScreenFramePolicy.packages(String.valueOf(packages.getText())))
              selected.put(entry, title());
          } catch (IllegalArgumentException ignored) {
            // 用户仍可在保存前修正未完成的输入。
          }
          ScreenAppPicker.show(
              context,
              selected,
              entry -> {
                String previous = String.valueOf(packages.getText()).trim();
                packages.setText(previous.isEmpty() ? entry : previous + "\n" + entry);
              },
              packages::requestFocus);
        });
    content.addView(picker, new LinearLayout.LayoutParams(-1, -2));
    ScrollView scroll = new ScrollView(context);
    scroll.addView(content);
    var dialog =
        new DshaDialogBuilder(context)
            .setTitle(title())
            .setView(scroll)
            .setPositiveButton(UiText.choose("保存配置", "Save settings"), null)
            .setNegativeButton(UiText.choose("取消", "Cancel"), null)
            .create();
    dialog.setOnShowListener(
        ignored ->
            dialog
                .getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      try {
                        if (!config.setScreenAnimatedPackages(String.valueOf(packages.getText()))) {
                          packages.setError(
                              UiText.choose("配置保存失败，请重试", "Could not save settings. Try again"));
                          return;
                        }
                        changed.run();
                        dialog.dismiss();
                      } catch (IllegalArgumentException invalid) {
                        packages.setError(
                            UiText.choose(
                                "请输入有效的完整包名，最多100个应用",
                                "Enter valid full package names, up to 100 apps"));
                      }
                    }));
    dialog.show();
  }
}
