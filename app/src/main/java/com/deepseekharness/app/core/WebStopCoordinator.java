package com.deepseekharness.app.core;

import com.deepseekharness.app.util.StopCompletion;
import com.deepseekharness.app.util.StopOperationId;
import java.io.IOException;
import com.deepseekharness.app.util.WebStopDiagnostic;
import java.util.ArrayList;
import java.util.List;

/** Stop attempts are evidence; only full guest and owned-launcher confirmation releases the lease. */
public final class WebStopCoordinator {
  public enum Status {
    STOPPED,
    UNCONFIRMED,
    FAILED,
    CANCELLED
  }

  public record Result(Status status, String code, List<WebStopDiagnostic> diagnostics) {
    public Result {
      diagnostics = List.copyOf(diagnostics);
    }

    /** Compatibility for retained/native callers; production ports return typed facts. */
    public Result(Status status, String code, String detail) {
      this(
          status,
          code,
          detail.isEmpty()
              ? List.of()
              : List.of(WebStopDiagnostic.of(WebStopDiagnostic.Code.LEGACY_DETAIL, detail)));
    }

    public String detail() {
      return WebStopText.renderAll(diagnostics);
    }

    public boolean stopped() {
      return status == Status.STOPPED;
    }

    /**
     * 只有“真实发生、但退出未确认”的停止结论才允许准备强制结束候选。
     *
     * <p>显式白名单，不用 {@code !stopped()}：初始占位与新代次占位（WEB_START_PENDING、
     * WEB_STOP_PENDING、WEB_STOP_NOT_REQUESTED）状态也是 UNCONFIRMED，但它们不是失败停止事实，
     * 不能让健康运行中的 Web 获得强停入口（T154 复核）。
     */
    public boolean unconfirmedStop() {
      if (status == Status.STOPPED) return false;
      return switch (code == null ? "" : code) {
        case "WEB_PROCESS_EXIT_UNCONFIRMED",
                "WEB_GUESTS_UNCONFIRMED",
                "WEB_RECORDED_STOP_UNCONFIRMED",
                "WEB_RESIDUAL_STOP_UNCONFIRMED",
                "WEB_START_STOP_UNCONFIRMED",
                "WEB_STOP_FAILED" ->
            true;
        default -> false;
      };
    }
  }

  interface Ports {
    String stopGuest() throws IOException;

    default List<WebStopDiagnostic> stopGuestFacts() throws IOException {
      String detail = stopGuest();
      return detail.isEmpty()
          ? List.of()
          : List.of(WebStopDiagnostic.of(WebStopDiagnostic.Code.LEGACY_DETAIL, detail));
    }

    String stopOwnedLaunchers();

    default List<WebStopDiagnostic> stopOwnedLauncherFacts() {
      String detail = stopOwnedLaunchers();
      return detail.isEmpty()
          ? List.of()
          : List.of(WebStopDiagnostic.of(WebStopDiagnostic.Code.LEGACY_DETAIL, detail));
    }

    boolean confirm() throws IOException;
  }

  static Result stop(Ports ports) {
    List<WebStopDiagnostic> facts = new ArrayList<>();
    try {
      facts.addAll(ports.stopGuestFacts());
    } catch (IOException | RuntimeException error) {
      facts.add(WebStopDiagnostic.failure(WebStopDiagnostic.Code.STOP_FAILED, error));
    }
    try {
      facts.addAll(ports.stopOwnedLauncherFacts());
    } catch (RuntimeException error) {
      facts.add(WebStopDiagnostic.failure(WebStopDiagnostic.Code.STOP_FAILED, error));
    }
    try {
      if (ports.confirm()) return new Result(Status.STOPPED, "WEB_STOPPED", "");
    } catch (IOException | RuntimeException error) {
      facts.add(WebStopDiagnostic.failure(WebStopDiagnostic.Code.STOP_FAILED, error));
    }
    if (Thread.currentThread().isInterrupted())
      return new Result(Status.CANCELLED, "WEB_STOP_CANCELLED", facts);
    if (facts.isEmpty())
      facts.add(WebStopDiagnostic.of(WebStopDiagnostic.Code.EXIT_UNCONFIRMED, ""));
    return new Result(Status.UNCONFIRMED, "WEB_PROCESS_EXIT_UNCONFIRMED", facts);
  }

  /** 平台只投影本次最终事实；没有 scope 的条目仍是未检查。 */
  record Observation(
      StopCompletion.Environment environment,
      List<StopCompletion.Fact> facts,
      boolean objectsUnchanged) {
    Observation {
      facts = List.copyOf(facts);
    }

    static Observation unknown() {
      return new Observation(new StopCompletion.Environment("", "", ""), List.of(), false);
    }
  }

  interface ObservedPorts extends Ports {
    Observation observe() throws IOException;

    void finish(Result result);

    long now();
  }

  record Attempt(Result result, StopCompletion completion) {}

  /** 原停止算法只执行一次；影子事实失败不改变旧结论，同步收尾失败仍使本次停止失败。 */
  static Attempt stopObserved(StopOperationId operation, ObservedPorts ports) {
    Result result = stop(ports);
    StopCompletion.Finish finish = StopCompletion.Finish.SUCCEEDED;
    try {
      ports.finish(result);
    } catch (RuntimeException failure) {
      finish = StopCompletion.Finish.FAILED;
      result =
          new Result(
              Status.FAILED,
              "WEB_STOP_FAILED",
              List.of(WebStopDiagnostic.failure(WebStopDiagnostic.Code.STOP_FAILED, failure)));
    }
    Observation observation;
    try {
      observation = ports.observe();
    } catch (IOException | RuntimeException unknown) {
      observation = Observation.unknown();
    }
    return new Attempt(
        result,
        new StopCompletion(
            operation,
            observation.environment(),
            observation.facts(),
            ports.now(),
            finish,
            observation.objectsUnchanged(),
            result.stopped()));
  }

  private WebStopCoordinator() {}
}
