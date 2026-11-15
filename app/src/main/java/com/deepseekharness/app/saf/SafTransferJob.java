package com.deepseekharness.app.saf;

/** 一次传输的顺序与收尾（纯逻辑）：先确认目标和记录，再写入；失败不自动重放。 */
public final class SafTransferJob {
  private SafTransferJob() {}

  /** Android 适配器只提供 IO；顺序、失败映射与记录收尾均由本类统一测试。 */
  public interface Ports<T> {
    boolean enabled();

    void openHistory() throws Exception;

    T retarget(T selection, String path) throws Exception;

    String path(T ticket);

    String beginRecord(String name, boolean importing, long time) throws Exception;

    long transfer(T ticket, String guestPath, boolean importing) throws Exception;

    boolean finishRecord(String record, long bytes, String code) throws Exception;

    void cancel(T ticket);

    default String failureCode(Exception error) {
      return SafTransferJob.failureCode(error);
    }
  }

  public static final class Result {
    public final long bytes;
    public final String code;

    private Result(long bytes, String code) {
      this.bytes = bytes;
      this.code = code;
    }
  }

  public static <T> Result run(
      Ports<T> ports,
      T selection,
      String remotePath,
      String guestPath,
      boolean importing,
      long time) {
    T ticket = selection;
    String record = null;
    long bytes = 0;
    String code;
    try {
      if (!ports.enabled()) throw new IllegalStateException("WORKBENCH_DISABLED");
      ports.openHistory();
      if (!importing) ticket = ports.retarget(selection, remotePath);
      String path = ports.path(ticket);
      record = ports.beginRecord(path.substring(path.lastIndexOf('/') + 1), importing, time);
      bytes = ports.transfer(ticket, guestPath, importing);
      code = "OK";
    } catch (Exception failure) {
      code = ports.failureCode(failure);
    } finally {
      ports.cancel(selection);
      if (ticket != selection) ports.cancel(ticket);
    }
    if (record != null) {
      boolean saved;
      try {
        saved = ports.finishRecord(record, bytes, code);
      } catch (Exception unavailable) {
        saved = false;
      }
      if (!saved && "OK".equals(code)) code = "OK_HISTORY_UNAVAILABLE";
    }
    return new Result(bytes, code);
  }

  /** 只接收完整短码，不把异常里可能含路径/凭据的正文当作可持久化错误。 */
  public static String failureCode(Exception error) {
    String code = error.getMessage();
    return code != null && code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "IO_ERROR";
  }
}
