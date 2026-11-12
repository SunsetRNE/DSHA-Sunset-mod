package com.deepseekharness.app.contract;

import com.deepseekharness.app.util.SensitiveData;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 契约层唯一脱敏出口：模块不得直写 logcat/日志文件，只能把事件交给本类。
 *
 * <p>默认实现对 {@code detail} 委托 {@link SensitiveData#redact(String)}，默认丢弃事件，
 * 不新增任何日志出口。宿主若需要展示/归档，通过 {@link #forwarding(Consumer)} 注入已脱敏事件。
 */
public final class DiagnosticsSink {
  public record Event(String code, String detail) {}

  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final int MAX_DETAIL_LENGTH = 2048;

  private final Consumer<Event> downstream;

  private DiagnosticsSink(Consumer<Event> downstream) {
    this.downstream = downstream;
  }

  public static DiagnosticsSink discarding() {
    return new DiagnosticsSink(event -> {});
  }

  public static DiagnosticsSink forwarding(Consumer<Event> downstream) {
    return new DiagnosticsSink(Objects.requireNonNull(downstream, "downstream"));
  }

  public void report(String code) {
    report(code, "");
  }

  public void report(String code, String detail) {
    String safeCode =
        code != null && CODE.matcher(code).matches() ? code : ContractCodes.INVALID_DIAGNOSTIC;
    String safeDetail = SensitiveData.redact(detail == null ? "" : detail);
    if (safeDetail == null) safeDetail = "";
    if (safeDetail.length() > MAX_DETAIL_LENGTH)
      safeDetail = safeDetail.substring(0, MAX_DETAIL_LENGTH);
    try {
      downstream.accept(new Event(safeCode, safeDetail));
    } catch (RuntimeException ignored) {
      // 诊断不得影响宿主生命周期；下游故障由宿主自己的日志/导出通道记录。
    }
  }
}
