package com.deepseekharness.app.util;

/**
 * Rust 后端运行层开关（方案 §5.4）。
 *
 * <p>默认 <b>关闭</b>：只有显式 {@link #setEnabled(boolean)} 才尝试走 Rust。开关关闭、库不可用
 * 或 ABI 不符时一律留在 JVM，并返回类型化原因；加载失败不重试、不静默伪装成“Rust 成功”。
 * {@code setEnabled(false)} 就是“一键回退”，不需要清数据、不改变已写数据格式。
 *
 * <p>R1 只有进程内布尔，不接 {@code ConfigStore}、不新增配置键；A3 接入用户配置时把持久化
 * 读取集中到本类，调用点不变。
 */
public final class RustBackends {
  private static volatile boolean enabled = false;

  private RustBackends() {}

  /** 运行层开关当前值（默认 false）。 */
  public static boolean enabled() {
    return enabled;
  }

  /** 显式设置运行层开关；false = 立即回 JVM。 */
  public static void setEnabled(boolean value) {
    enabled = value;
  }

  /** 后端选择原因的冻结名字（类型化；不含路径/异常文本）。 */
  public enum SelectionReason {
    SWITCH_OFF,
    RUST_ENABLED,
    NOT_LOADED,
    LIBRARY_MISSING,
    LOAD_FAILED,
    ABI_MISMATCH
  }

  /** 一次后端选择的只读结果。 */
  public static final class Selection {
    private final boolean useRust;
    private final SelectionReason reason;

    private Selection(boolean useRust, SelectionReason reason) {
      this.useRust = useRust;
      this.reason = reason;
    }

    public boolean useRust() {
      return useRust;
    }

    public SelectionReason reason() {
      return reason;
    }

    /** 原因码（枚举名；写进诊断回执）。 */
    public String reasonCode() {
      return reason.name();
    }
  }

  /**
   * 解析 digest 的后端：开关关闭 → JVM；开启时尝试加载，失败按类型化原因留在 JVM。
   *
   * <p>加载失败不会抛异常、也不会每次重试（{@link RustNative#ensureLoaded()} 幂等）。
   */
  public static Selection digestSelection() {
    if (!enabled) return new Selection(false, SelectionReason.SWITCH_OFF);
    if (!RustNative.ensureLoaded()) return new Selection(false, nativeReason(RustNative.status()));
    return new Selection(true, SelectionReason.RUST_ENABLED);
  }

  /**
   * 解析 {@code verify_plan} 的后端（A3 §5.4）：与 {@link #digestSelection()} 同构。
   *
   * <p>默认关闭；R2 只提供选择函数，不接生产调用点。注意语义差别：digest 的 Rust 内部错误
   * 允许用 JVM 重做一次，而 {@code verify_plan} 的两实现分歧**禁止**回退（fail-closed，
   * 见 A3 §3.9 P-5），本方法只负责“这次走哪个实现”。
   */
  public static Selection planSelection() {
    if (!enabled) return new Selection(false, SelectionReason.SWITCH_OFF);
    if (!RustNative.ensureLoaded()) return new Selection(false, nativeReason(RustNative.status()));
    return new Selection(true, SelectionReason.RUST_ENABLED);
  }

  /**
   * 解析 gzip archive（{@code pack}/{@code unpack}）的后端（方案 §4.4/§4.5）：与
   * {@link #digestSelection()} 同构，默认关闭。
   *
   * <p>R3 只提供选择函数，不接生产调用点；A3 slice4 接线时用
   * {@link RustArchiveCodec}/{@link RustGzipOutputStream}/{@link RustGzipInputStream}，
   * 开关关闭或库不可用时留在 JVM 的 {@code GZIPOutputStream}/{@code StrictGzipInputStream}。
   */
  public static Selection archiveSelection() {
    if (!enabled) return new Selection(false, SelectionReason.SWITCH_OFF);
    if (!RustNative.ensureLoaded()) return new Selection(false, nativeReason(RustNative.status()));
    return new Selection(true, SelectionReason.RUST_ENABLED);
  }

  private static SelectionReason nativeReason(RustNative.Status status) {
    switch (status) {
      case LIBRARY_MISSING:
        return SelectionReason.LIBRARY_MISSING;
      case LOAD_FAILED:
        return SelectionReason.LOAD_FAILED;
      case ABI_MISMATCH:
        return SelectionReason.ABI_MISMATCH;
      default:
        return SelectionReason.NOT_LOADED;
    }
  }
}
