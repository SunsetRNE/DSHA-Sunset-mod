package com.deepseekharness.app.plugins;

import com.deepseekharness.app.util.PluginOutput;
import com.deepseekharness.app.util.SensitiveData;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

/** 只解释一次真实命令结果；未知/超时/截断不因输出里有成功 JSON 而变成已完成。 */
public final class PluginOperationResult {
  private PluginOperationResult() {}

  public enum Kind {
    FAILED,
    TIMEOUT,
    CANCELLED,
    INVALIDATED,
    UNCONFIRMED
  }

  public record Detail(String errorCode, String stage, String application, String restartReason) {
    public static final Detail EMPTY = new Detail("", "", "", "");

    public Detail {
      errorCode = token(errorCode);
      stage = token(stage);
      application = token(application);
      restartReason = token(restartReason);
    }

    private static String token(String value) {
      return value != null && value.matches("[A-Za-z0-9_.:-]{0,128}") ? value : "unknown";
    }
  }

  public static final class Failure extends IOException {
    public final Kind kind;
    public final Detail detail;
    public final boolean exitUnknown;

    public Failure(Kind kind, String code, String message) {
      this(kind, new Detail(code, "", "", ""), message, false);
    }

    public Failure(Kind kind, Detail detail, String message) {
      this(kind, detail, message, false);
    }

    private Failure(Kind kind, Detail detail, String message, boolean exitUnknown) {
      super(SensitiveData.redact(message));
      this.kind = kind;
      this.detail = detail;
      this.exitUnknown = exitUnknown;
    }
  }

  public record Receipt(
      String status,
      String message,
      Detail detail,
      boolean cleanupVerified,
      boolean executionUnknown,
      boolean exitUnknown) {}

  /** 返回回执字段只作投影；操作类型和目标还须由原有精确读回校验。 */
  public static Receipt parse(String output) throws Failure {
    String json = PluginOutput.resultJson(output);
    if (json.isEmpty() || json.length() > 8 * 1024 * 1024)
      throw unknown("PLUGIN_RESULT_MISSING", "没有收到可核验的插件管理结果");
    String status = null, message = null, code = "", stage = "", application = "", restart = "";
    boolean cleanup = false, executionUnknown = false;
    Boolean exitUnknown = null;
    Set<String> fields = new HashSet<>();
    try (JsonReader reader = new JsonReader(new StringReader(json))) {
      reader.setStrictness(Strictness.STRICT);
      reader.beginObject();
      while (reader.hasNext()) {
        String name = reader.nextName();
        if (!fields.add(name)) throw new IOException("duplicate field");
        switch (name) {
          case "status", "message", "errorCode", "stage", "application", "restartReason" -> {
            if (reader.peek() != JsonToken.STRING) throw new IOException("field type");
            String value = reader.nextString();
            switch (name) {
              case "status" -> status = value;
              case "message" -> message = value;
              case "errorCode" -> code = value;
              case "stage" -> stage = value;
              case "application" -> application = value;
              case "restartReason" -> restart = value;
            }
          }
          case "cleanupVerified", "executionUnknown", "exitUnknown" -> {
            if (reader.peek() != JsonToken.BOOLEAN) throw new IOException("field type");
            boolean value = reader.nextBoolean();
            if (name.equals("cleanupVerified")) cleanup = value;
            else if (name.equals("executionUnknown")) executionUnknown = value;
            else exitUnknown = value;
          }
          default -> reader.skipValue();
        }
      }
      reader.endObject();
      if (reader.peek() != JsonToken.END_DOCUMENT || status == null || message == null)
        throw new IOException("incomplete result");
    } catch (IOException | RuntimeException malformed) {
      throw unknown("PLUGIN_RESULT_INVALID", "插件管理回执不完整或格式无法核验");
    }
    return new Receipt(
        status,
        SensitiveData.redact(message),
        new Detail(code, stage, application, restart),
        cleanup,
        executionUnknown,
        exitUnknown != null
            ? exitUnknown
            : executionUnknown
                || "unknown".equals(status)
                || "PLUGIN_EXECUTION_UNKNOWN".equals(code)
                || !Set.of("ok", "error", "cancelled", "pending", "partial").contains(status));
  }

  public static Receipt requireCompleted(
      int exitCode, boolean timedOut, boolean truncated, String output) throws Failure {
    if (timedOut) throw unknownExit("PLUGIN_COMMAND_TIMEOUT", "插件命令等待超时，退出和提交结果尚未确认");
    if (exitCode < 0) throw unknownExit("PLUGIN_COMMAND_EXIT_UNKNOWN", "插件命令退出尚未确认");
    if (truncated)
      throw new Failure(
          Kind.UNCONFIRMED,
          new Detail("PLUGIN_OUTPUT_TRUNCATED", "", "", ""),
          "插件命令输出不完整，结果尚未确认",
          exitCode != 0);
    Receipt receipt;
    try {
      receipt = parse(output);
    } catch (Failure malformed) {
      if (exitCode != 0)
        throw new Failure(Kind.UNCONFIRMED, malformed.detail, malformed.getMessage(), true);
      throw malformed;
    }
    if (receipt.exitUnknown)
      throw new Failure(Kind.UNCONFIRMED, receipt.detail, receipt.message, true);
    if (receipt.executionUnknown
        || Set.of("pending", "partial", "unknown").contains(receipt.status))
      throw new Failure(Kind.UNCONFIRMED, receipt.detail, receipt.message);
    if (receipt.status.equals("cancelled"))
      throw new Failure(
          receipt.cleanupVerified ? Kind.CANCELLED : Kind.UNCONFIRMED,
          receipt.detail,
          receipt.message);
    if (receipt.status.equals("error")) {
      if (receipt.detail.errorCode.equals("PLUGIN_COMMAND_TIMEOUT_CONFIRMED"))
        throw new Failure(
            receipt.cleanupVerified ? Kind.TIMEOUT : Kind.UNCONFIRMED,
            receipt.detail,
            receipt.message);
      if (receipt.detail.errorCode.equals("PLUGIN_EXECUTION_UNKNOWN"))
        throw new Failure(Kind.UNCONFIRMED, receipt.detail, receipt.message);
      if (Set.of(
                  "PLUGIN_TARGET_VERSION_CHANGED",
                  "PLUGIN_SOURCE_CHANGED",
                  "PLUGIN_CANDIDATE_CHANGED",
                  "PLUGIN_COMPATIBILITY_CHANGED")
              .contains(receipt.detail.errorCode)
          || receipt.detail.errorCode.isEmpty()
              && receipt.message.equals("PLUGIN_TARGET_VERSION_CHANGED"))
        throw new Failure(Kind.INVALIDATED, receipt.detail, receipt.message);
      throw new Failure(Kind.FAILED, receipt.detail, receipt.message);
    }
    if (!receipt.status.equals("ok") || exitCode != 0)
      throw unknown("PLUGIN_COMMAND_EXIT", "插件命令没有正常完成，结果尚未确认");
    return receipt;
  }

  public static Failure findFailure(Throwable error) {
    Failure first = null;
    for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
      if (error instanceof Failure failure) {
        if (failure.exitUnknown) return failure;
        if (first == null) first = failure;
      }
    }
    return first;
  }

  public static Failure unknownExit(String code, String message) {
    return new Failure(Kind.UNCONFIRMED, new Detail(code, "", "", ""), message, true);
  }

  public static Failure unknown(String code, String message) {
    return new Failure(Kind.UNCONFIRMED, code, message);
  }
}
