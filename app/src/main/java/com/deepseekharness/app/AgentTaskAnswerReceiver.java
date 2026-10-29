package com.deepseekharness.app;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 通知/岛上的回答动作：选项按钮、多选切换与“自己写”都从这里回到网页侧。
 *
 * <p>独立于通知渲染与前台 Activity，用户不必先打开 App；端点、令牌与 callId 都来自
 * 本应用自己发布的 PendingIntent。令牌只用于本机回环请求，答案是否有效仍由网页侧按
 * 当前真正待答的问题再次校验。
 */
public final class AgentTaskAnswerReceiver extends BroadcastReceiver {
  private static final String TAG = "DSHA";
  private static final int TIMEOUT_MS = 4000;

  @Override
  public void onReceive(Context context, Intent intent) {
    if ("dsha-task-toggle".equals(intent.getAction())) {
      String rowAction = intent.getStringExtra("rowAction");
      String label = intent.getStringExtra("label");
      if (rowAction != null && label != null)
        AgentTaskNotifications.toggleSelection(context, rowAction, label);
      return;
    }
    int port = intent.getIntExtra("port", -1);
    String path = intent.getStringExtra("path");
    String token = intent.getStringExtra("token");
    String callId = intent.getStringExtra("callId");
    String questionId = intent.getStringExtra("questionId");
    String rowAction = intent.getStringExtra("rowAction");
    boolean multi = intent.getBooleanExtra("multi", false);
    String custom = "";
    if (intent.getBooleanExtra("custom", false)) {
      var results = RemoteInput.getResultsFromIntent(intent);
      CharSequence typed =
          results == null ? null : results.getCharSequence(AgentTaskNotifications.REPLY_KEY);
      custom = typed == null ? "" : typed.toString().trim();
      if (custom.codePointCount(0, custom.length()) > 2000) return;
    }
    List<String> selected = new ArrayList<>();
    if (multi && rowAction != null)
      selected.addAll(AgentTaskNotifications.selection(context, rowAction));
    else {
      String label = intent.getStringExtra("label");
      if (label != null && !label.isEmpty()) selected.add(label);
    }
    if (selected.size() > 8) return;
    for (String label : selected)
      if (label == null || label.isEmpty() || label.codePointCount(0, label.length()) > 80) return;
    if (selected.isEmpty() && custom.isEmpty()) return;
    if (port < 1
        || port > 65535
        || path == null
        || !path.startsWith("/")
        || path.length() > 64
        || token == null
        || !token.matches("[a-f0-9]{64}")
        || callId == null
        || callId.isEmpty()
        || callId.length() > 128
        || questionId == null
        || questionId.isEmpty()
        || questionId.length() > 128) return;
    final String answerText = custom;
    final List<String> answerSelection = selected;
    PendingResult pending = goAsync();
    new Thread(
            () -> {
              try {
                answer(port, path, token, callId, questionId, answerSelection, answerText);
              } catch (Throwable failed) {
                Log.w(TAG, "TASK_ANSWER_FAILED", failed);
              } finally {
                pending.finish();
              }
            },
            "dsha-task-answer")
        .start();
  }

  private static void answer(
      int port,
      String path,
      String token,
      String callId,
      String questionId,
      List<String> selected,
      String custom)
      throws Exception {
    var answer = new JSONObject().put("id", questionId).put("selected", new JSONArray(selected));
    if (!custom.isEmpty()) answer.put("custom", custom);
    var body =
        new JSONObject()
            .put("token", token)
            .put("callId", callId)
            .put("answers", new JSONArray().put(answer))
            .toString();
    var connection =
        (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
    try {
      connection.setRequestMethod("POST");
      connection.setConnectTimeout(TIMEOUT_MS);
      connection.setReadTimeout(TIMEOUT_MS);
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json");
      byte[] payload = body.getBytes(StandardCharsets.UTF_8);
      connection.setFixedLengthStreamingMode(payload.length);
      try (var stream = connection.getOutputStream()) {
        stream.write(payload);
      }
      int status = connection.getResponseCode();
      if (status != HttpURLConnection.HTTP_OK) Log.w(TAG, "TASK_ANSWER_REJECTED " + status);
    } finally {
      connection.disconnect();
    }
  }
}
