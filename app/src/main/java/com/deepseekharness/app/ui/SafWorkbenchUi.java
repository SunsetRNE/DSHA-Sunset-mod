package com.deepseekharness.app.ui;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.saf.SafTransferHistory;
import com.deepseekharness.app.util.UiText;
import java.util.List;

/** 工作台开关、结构化错误与只读记录视图；不开关任何系统授权或助手写入偏好。 */
final class SafWorkbenchUi {
  private SafWorkbenchUi() {}

  static boolean enabled(Context context) {
    try {
      return context
          .getSharedPreferences("saf_workbench_ui", Context.MODE_PRIVATE)
          .getBoolean("enabled", false);
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  static boolean setEnabled(Context context, boolean enabled) {
    try {
      return context
          .getSharedPreferences("saf_workbench_ui", Context.MODE_PRIVATE)
          .edit()
          .putBoolean("enabled", enabled)
          .commit();
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  static void history(FragmentActivity activity) {
    Context app = activity.getApplicationContext();
    new Thread(
            () -> {
              List<SafTransferHistory.Record> records = SafTransferHistory.open(app).list();
              activity.runOnUiThread(
                  () -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    ScrollView scroll = new ScrollView(activity);
                    LinearLayout body = new LinearLayout(activity);
                    body.setOrientation(LinearLayout.VERTICAL);
                    int pad = Math.round(16 * activity.getResources().getDisplayMetrics().density);
                    body.setPadding(pad, pad, pad, pad);
                    scroll.addView(body);
                    TextView note =
                        row(
                            activity,
                            UiText.choose(
                                "最近 40 条传输记录，不含文件内容或完整路径。",
                                "The latest 40 transfers, without file contents or full paths."));
                    body.addView(note);
                    if (records.isEmpty())
                      body.addView(row(activity, UiText.choose("还没有传输记录。", "No transfers yet.")));
                    for (SafTransferHistory.Record record : records) {
                      String direction =
                          record.importing
                              ? UiText.choose("导入到工作区", "Import to workspace")
                              : UiText.choose("导出到授权目录", "Export to granted folder");
                      String state =
                          "INTERRUPTED".equals(record.code)
                              ? UiText.choose("传输中断", "Transfer interrupted")
                              : TransferText.status(record.status);
                      String metadata =
                          UiText.format(
                              UiText.choose("%s · %s · %s", "%s · %s · %s"),
                              direction,
                              state,
                              TransferText.size(activity, record.bytes));
                      body.addView(row(activity, record.name));
                      body.addView(row(activity, metadata));
                      body.addView(row(activity, TransferText.fullTime(activity, record.time)));
                      if (!record.code.isEmpty()) body.addView(row(activity, problem(record.code)));
                    }
                    new DshaDialogBuilder(activity)
                        .setTitle(UiText.choose("传输记录", "Transfer history"))
                        .setView(scroll)
                        .setPositiveButton(UiText.choose("关闭", "Close"), null)
                        .show();
                  });
            },
            "saf-transfer-history")
        .start();
  }

  private static TextView row(Context context, String text) {
    TextView view = new TextView(context);
    view.setText(text);
    view.setTextColor(context.getColor(R.color.text_secondary));
    view.setTextIsSelectable(true);
    int pad = Math.round(4 * context.getResources().getDisplayMetrics().density);
    view.setPadding(0, pad, 0, pad);
    return view;
  }

  static String problem(String code) {
    return switch (code) {
      case "HANDLE_EXPIRED" ->
          UiText.choose("操作已过期，请重新选择文件。", "This action expired. Select the file again.");
      case "NO_GRANT",
              "PERMISSION_DENIED",
              "HANDLE_REVOKED",
              "HANDLE_UNKNOWN",
              "HANDLE_NOT_PERMITTED",
              "PERMISSION_STATE_UNAVAILABLE",
              "STATE_UNAVAILABLE" ->
          UiText.choose(
              "授权已失效或被系统拒绝：回到上一页移除它再重新选。",
              "Permission lost or denied: remove the grant and pick it again.");
      case "READ_ONLY" -> UiText.choose("只读（已关闭助手写入）", "Read only (assistant writes off)");
      case "BUSY" ->
          UiText.choose("传输槽位已满，请稍后重试。", "All transfer slots are busy. Try again later.");
      case "TOO_LARGE" -> UiText.choose("单个文件不能超过 32 MiB。", "A file cannot exceed 32 MiB.");
      case "BAD_NAME", "BAD_PATH", "LOCAL_NOT_ABSOLUTE" ->
          UiText.choose(
              "请填写完整的工作区文件路径和有效文件名。",
              "Enter an absolute workspace file path and a valid file name.");
      case "LOCAL_CREDENTIAL", "LOCAL_OUTSIDE_ROOTFS", "FORBIDDEN_LINK2SYMLINK" ->
          UiText.choose(
              "该路径不可用于文件传输，请选择普通工作文件。",
              "This path cannot be transferred. Choose a regular work file.");
      case "SOURCE_CHANGED" ->
          UiText.choose(
              "文件发生了变化，请等待写入完成后刷新并重试。",
              "The file changed. Wait for writing to finish, then refresh and retry.");
      case "TIMEOUT" ->
          UiText.choose(
              "目标应用超时：稍后重试或先在后台打开那个应用。",
              "The target app timed out. Retry later or open that app in the background.");
      case "NOT_FOUND" -> UiText.choose("目标不存在：应用可能已卸载。", "Not found: the app may be uninstalled.");
      case "PROVIDER_ERROR" ->
          UiText.choose(
              "目标应用没有响应：先打开那个应用再刷新。", "The app did not respond: open it first, then refresh.");
      case "UNSUPPORTED" ->
          UiText.choose("这个文档服务不支持该操作。", "This document provider does not support it.");
      case "SCAN_LIMIT" ->
          UiText.choose(
              "目录项超过 500，无法定位目标；请在浏览器/助手里直接指定更短路径。",
              "More than 500 entries; the target cannot be located. Use a shorter path.");
      case "WORKBENCH_DISABLED" ->
          UiText.choose("请先在授权页开启文件工作台。", "Enable the file workbench on the access page first.");
      case "HISTORY_UNAVAILABLE", "HISTORY_FULL" ->
          UiText.choose("无法保存传输记录，请稍后重试。", "Transfer history could not be saved. Try again later.");
      case "INTERRUPTED", "CANCELLED" -> UiText.choose("传输中断", "Transfer interrupted");
      default ->
          UiText.choose(
              "传输未完成，请检查目标文件后再操作。外部应用中的目标可能不完整。",
              "The transfer did not complete. Check the destination before continuing; an external file may be incomplete.");
    };
  }
}
