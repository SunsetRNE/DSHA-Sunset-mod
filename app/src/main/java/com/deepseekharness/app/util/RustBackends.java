package com.deepseekharness.app.util;

/**
 * Rust 后端运行层开关（方案 §5.4）。
 *
 * <p>进程内字段默认 <b>false</b>，普通 JVM 单测不会被原生装载状态左右；正式 App 由
 * {@code DshaApp.onCreate()} 显式 {@link #setEnabled(boolean) setEnabled(true)}，运行时默认启用。
 * 开关关闭、库不可用（.so 未随包）或 ABI 不符时一律留在 JVM，并返回类型化原因；加载失败
 * 不重试、不静默伪装成“Rust 成功”。{@code setEnabled(false)} 就是“一键回退”，不需要清数据、
 * 不改变已写数据格式。
 *
 * <p>R1 只有进程内布尔，不接 {@code ConfigStore}、不新增配置键；A3 接入用户配置时把持久化
 * 读取集中到本类，调用点不变。注意选择原因只覆盖“加载/开关”回退：{@code verify_plan} 在
 * 已选 Rust 后的内部错误或双实现分歧仍 fail-closed 中止，不用 JVM 结果覆盖后继续。
 */
public final class RustBackends {
  private static volatile boolean enabled = false;

  private RustBackends() {}

  /** 运行层开关当前值（进程内字段默认 false；正式 App 启动时启用）。 */
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
   * 解析 {@code verify_plan} 的后端（A3 §5.4、§10）：与 {@link #digestSelection()} 同构。
   *
   * <p>正式 App 启动时由 {@code DshaApp} 启用；<b>A3 slice2/3 已接生产</b>：预览期与提交期投影
   * 都由 {@code backup.RestorePlanProjection} 调本方法选择实现（2026-11-14 修订注释）。
   *
   * <p>语义差别：digest 的 Rust 内部错误允许用 JVM 重做一次，而 {@code verify_plan} 的
   * 内部错误/契约不符与两实现分歧**都禁止**回退（fail-closed，见 A3 §3.9 P-5、§10）——
   * Rust 单侧失败按 {@code PLAN_VIEW_UNVERIFIED} 中止，不能用 JVM 结果覆盖后继续；
   * 只有库加载失败/ABI 不符按 §5.4 回退矩阵留在 JVM-only 并记录选择原因。
   */
  public static Selection planSelection() {
    if (!enabled) return new Selection(false, SelectionReason.SWITCH_OFF);
    if (!RustNative.ensureLoaded()) return new Selection(false, nativeReason(RustNative.status()));
    return new Selection(true, SelectionReason.RUST_ENABLED);
  }

  /**
   * 解析 gzip archive（{@code pack}/{@code unpack}）的后端（方案 §4.4/§4.5）：与
   * {@link #digestSelection()} 同构，正式 App 启动时由 {@code DshaApp} 启用。
   *
   * <p>A3 slice4 已接生产：{@code backup.PortableBackupEnvelope} 经
   * {@link RustArchiveCodec#packStream} 编码，{@code backup.LegacyTarReader} 经
   * {@code StrictGzipInputStream.open} 解码；开关关闭或库不可用时留在 JVM 的
   * {@code GZIPOutputStream}/{@code StrictGzipInputStream}。
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
