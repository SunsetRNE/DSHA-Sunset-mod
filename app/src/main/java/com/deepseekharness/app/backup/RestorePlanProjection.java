package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.BackupScopes;
import com.deepseekharness.app.util.PlanViewCodec;
import com.deepseekharness.app.util.PlanViewValidator;
import com.deepseekharness.app.util.RustBackends;
import com.deepseekharness.app.util.RustNative;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A3 slice2/3：把恢复计划投影为 {@code PlanView} v1，并用 JVM 与（可选）Rust 两个实现校验。
 *
 * <p>生产者只读清单与计划决策：清单只读快照（{@link NativeRestorePlan#projectionFacts()}）、计划实际
 * 写入根数、输入归档 SHA-256、选择位图、本机映射两个决策。不读文件、不写日志、不执行命令；视图里
 * 没有路径、命令或文件清单（A3 基线 §3.1）。同一输入 + 同一 {@code nowMs} 产出确定性字节；
 * {@code planDigest} 按 §3.5 计算（字段 15 置零后整视图 SHA-256），用于诊断自校验。
 *
 * <p>校验阶段先跑 JVM {@link PlanViewValidator}，再按 {@link RustBackends#planSelection()} 决定是否
 * 让 Rust {@code verify_plan} 对**同一份字节**复核；两实现必须给出相同的 VALID/INVALID 与原因集合
 * （§3.9 P-5）。提交期闸门 {@link #requireSubmitSafe(Receipt)} 对 INVALID、分歧、单侧失败一律
 * fail-closed；**无法投影**（计划根数 > 64、清单值类型/范围不符）只记类型化诊断，不调 Rust，
 * JVM 权威恢复照常继续（Q1-A、P-4）。预览期只把回执写进 {@code preview.json} 加法键，不改变恢复语义。
 */
public final class RestorePlanProjection {
  /** {@code preview.json} / {@code plan-view.json} 里本回执的加法键名。 */
  public static final String PREVIEW_KEY = "planView";

  // 投影诊断（固定短码；不含路径、URI 或正文）。
  public static final String DIAGNOSTIC_READY = "PLAN_VIEW_READY";
  public static final String DIAGNOSTIC_ROOT_LIMIT = "PLAN_VIEW_ROOT_LIMIT";
  public static final String DIAGNOSTIC_MANIFEST = "PLAN_VIEW_MANIFEST";
  public static final String DIAGNOSTIC_FORMAT_VERSION = "PLAN_VIEW_FORMAT_VERSION";
  public static final String DIAGNOSTIC_OPERATION = "PLAN_VIEW_OPERATION";
  public static final String DIAGNOSTIC_INTEGRITY = "PLAN_VIEW_INTEGRITY";
  public static final String DIAGNOSTIC_COUNTS = "PLAN_VIEW_COUNTS";
  public static final String DIAGNOSTIC_TIMESTAMP = "PLAN_VIEW_TIMESTAMP";
  public static final String DIAGNOSTIC_APP_VERSION = "PLAN_VIEW_APP_VERSION";
  public static final String DIAGNOSTIC_DATA_FORMAT = "PLAN_VIEW_DATA_FORMAT";
  public static final String DIAGNOSTIC_SENSITIVE_POLICY = "PLAN_VIEW_SENSITIVE_POLICY";
  public static final String DIAGNOSTIC_PLUGIN_COUNT = "PLAN_VIEW_PLUGIN_COUNT";
  public static final String DIAGNOSTIC_SELECTED_MASK = "PLAN_VIEW_SELECTED_MASK";
  public static final String DIAGNOSTIC_MAPPING_KIND = "PLAN_VIEW_MAPPING_KIND";
  public static final String DIAGNOSTIC_SOURCE_DIGEST = "PLAN_VIEW_SOURCE_DIGEST";
  public static final String DIAGNOSTIC_ENCODE = "PLAN_VIEW_ENCODE";
  public static final String DIAGNOSTIC_INVALID = "PLAN_VIEW_INVALID";
  public static final String DIAGNOSTIC_DIVERGENT = "PLAN_VIEW_DIVERGENT";
  public static final String DIAGNOSTIC_UNVERIFIED = "PLAN_VIEW_UNVERIFIED";

  // 校验结论、后端来源与单侧状态（固定短码）。
  public static final String VERDICT_VALID = "VALID";
  public static final String VERDICT_INVALID = "INVALID";
  public static final String VERDICT_DIVERGENT = "DIVERGENT";
  public static final String VERDICT_UNVERIFIED = "UNVERIFIED";
  public static final String BACKEND_JVM_ONLY = "JVM_ONLY";
  public static final String BACKEND_JVM_AND_RUST = "JVM_AND_RUST";
  public static final String CHECK_VALID = "VALID";
  public static final String CHECK_INVALID = "INVALID";
  public static final String CHECK_SKIPPED = "SKIPPED";
  public static final String CHECK_NOT_ATTEMPTED = "NOT_ATTEMPTED";
  public static final String CHECK_FAILED = "FAILED";
  public static final String SELECTION_NOT_EVALUATED = "NOT_EVALUATED";
  public static final String REASON_UNKNOWN = "PLAN_REASON_UNKNOWN";

  /** 提交期中止码：写进 {@code operation.json} 的 error，并登记在诊断白名单。 */
  public static final String SUBMIT_INVALID = "PLAN_VIEW_INVALID";

  public static final String SUBMIT_DIVERGENT = "PLAN_VIEW_DIVERGENT";
  public static final String SUBMIT_UNVERIFIED = "PLAN_VIEW_UNVERIFIED";

  /** Rust 原因缓冲上限（§3.7 期望 ≤6 条；8 是 ABI 缓冲余量）。 */
  private static final int RUST_MAX_REASONS = 8;

  /** Rust 返回码不满足 ABI 契约（非 0/1 或原因缓冲越界）时的内部标记。 */
  private static final int RUST_ERROR_CONTRACT = Integer.MIN_VALUE;

  private static final int DIGEST_BYTES = 32;

  private RestorePlanProjection() {}

  /**
   * 一次「投影 + 双实现校验」的只读回执。
   *
   * <p>{@link #facts()} 是写入 {@code preview.json} / {@code plan-view.json} 的加法事实：只含固定
   * 短码、计数与摘要十六进制，不含路径、归档正文或异常消息。
   */
  public static final class Receipt {
    private final boolean projected;
    private final String verdict, diagnostic, backend, selection, jvm, rust;
    private final int[] jvmReasons, rustReasons;
    private final byte[] view, planDigest;
    private final int rootCount, entryCount, pluginCount, targetMappingKind;
    private final long byteCount, selectedMask, nowMs, generatedAtMs;

    private Receipt(Draft draft) {
      this.projected = draft.projected;
      this.verdict = draft.verdict;
      this.diagnostic = draft.diagnostic;
      this.backend = draft.backend;
      this.selection = draft.selection;
      this.jvm = draft.jvm;
      this.rust = draft.rust;
      this.jvmReasons = draft.jvmReasons == null ? new int[0] : draft.jvmReasons.clone();
      this.rustReasons = draft.rustReasons == null ? new int[0] : draft.rustReasons.clone();
      this.view = draft.view == null ? null : draft.view.clone();
      this.planDigest = draft.planDigest == null ? null : draft.planDigest.clone();
      this.rootCount = draft.rootCount;
      this.entryCount = draft.entryCount;
      this.pluginCount = draft.pluginCount;
      this.targetMappingKind = draft.targetMappingKind;
      this.byteCount = draft.byteCount;
      this.selectedMask = draft.selectedMask;
      this.nowMs = draft.nowMs;
      this.generatedAtMs = draft.generatedAtMs;
    }

    /** 是否成功编码出战 PlanView（false = 只记类型化诊断，不调 Rust，恢复继续）。 */
    public boolean projected() {
      return projected;
    }

    /** {@link #VERDICT_VALID} / {@link #VERDICT_INVALID} / {@link #VERDICT_DIVERGENT} / {@link #VERDICT_UNVERIFIED}。 */
    public String verdict() {
      return verdict;
    }

    /** 类型化诊断短码（例如 {@link #DIAGNOSTIC_READY}、{@link #DIAGNOSTIC_ROOT_LIMIT}）。 */
    public String diagnostic() {
      return diagnostic;
    }

    /** {@link #BACKEND_JVM_ONLY} 或 {@link #BACKEND_JVM_AND_RUST}。 */
    public String backend() {
      return backend;
    }

    /** {@link RustBackends.Selection#reasonCode()}（投影失败时为 {@link #SELECTION_NOT_EVALUATED}）。 */
    public String selection() {
      return selection;
    }

    /** JVM 校验结论：{@link #CHECK_VALID} / {@link #CHECK_INVALID} / {@link #CHECK_SKIPPED}。 */
    public String jvmState() {
      return jvm;
    }

    /** Rust 校验结论：VALID/INVALID/{@link #CHECK_NOT_ATTEMPTED}/{@link #CHECK_FAILED}。 */
    public String rustState() {
      return rust;
    }

    /** JVM 原因码（去重升序；通过时为空数组）。 */
    public int[] jvmReasons() {
      return jvmReasons.clone();
    }

    /** Rust 原因码（规范化后；未尝试/失败时为空数组）。 */
    public int[] rustReasons() {
      return rustReasons.clone();
    }

    /** 已编码的视图字节（未投影时为 null）。 */
    public byte[] view() {
      return view == null ? null : view.clone();
    }

    /** §3.5 的 {@code planDigest}（未投影时为 null）。 */
    public byte[] planDigest() {
      return planDigest == null ? null : planDigest.clone();
    }

    /** 写入 {@code preview.json} / {@code plan-view.json} 的加法事实（确定性键序，固定短码）。 */
    public Map<String, Object> facts() {
      Map<String, Object> facts = new LinkedHashMap<>();
      facts.put("viewVersion", (long) PlanViewCodec.VIEW_VERSION);
      facts.put("policyVersion", (long) PlanViewCodec.POLICY_VERSION);
      facts.put("projected", projected);
      facts.put("verdict", label(verdict));
      facts.put("diagnostic", label(diagnostic));
      facts.put("backend", label(backend));
      facts.put("selection", label(selection));
      facts.put("jvm", label(jvm));
      facts.put("rust", label(rust));
      facts.put("jvmReasons", reasonNames(jvmReasons));
      facts.put("rustReasons", reasonNames(rustReasons));
      facts.put("rootCount", (long) rootCount);
      if (!projected) return facts;
      facts.put("entryCount", (long) entryCount);
      facts.put("byteCount", byteCount);
      facts.put("pluginCount", (long) pluginCount);
      facts.put("selectedMask", selectedMask);
      facts.put("targetMappingKind", (long) targetMappingKind);
      facts.put("generatedAtMs", generatedAtMs);
      facts.put("nowMs", nowMs);
      facts.put("planDigest", hex(planDigest));
      facts.put("viewLength", (long) view.length);
      return facts;
    }
  }

  /** 投影中间状态；只在 {@link #project} 内使用，构造 {@link Receipt} 后丢弃。 */
  private static final class Draft {
    boolean projected;
    String verdict, diagnostic, backend, selection, jvm, rust;
    int[] jvmReasons = new int[0], rustReasons = new int[0];
    byte[] view, planDigest;
    int rootCount, entryCount, pluginCount, targetMappingKind;
    long byteCount, selectedMask, nowMs, generatedAtMs;
    // 清单事实（编码前填充）。
    int formatVersion, operation, integrity;
    long createdAtMs;
    String appVersion, dataFormat, sensitivePolicy;
    byte[] sourceDigest;
  }

  /** 投影失败的类型化内部原因（短码；不向调用方抛出）。 */
  private static final class ProjectionFailure extends Exception {
    private static final long serialVersionUID = 1L;
    private final String code;

    private ProjectionFailure(String code) {
      super(code, null, false, false);
      this.code = code;
    }
  }

  /** Rust {@code verify_plan} 的返回码与原因集合；非 0/1 时 reasons 为 null。 */
  private static final class RustResult {
    private final int code;
    private final int[] reasons;

    private RustResult(int code, int[] reasons) {
      this.code = code;
      this.reasons = reasons;
    }
  }

  /**
   * 投影并校验一次恢复计划。
   *
   * @param manifest 归档尾清单（只读；字段类型/范围按 A3 §3.3 检查，不符则放弃投影）
   * @param rootCount 本次计划实际写入根数（{@code plan.groups.size()}，即 {@code preview.restoreRoots}）
   * @param sourceDigest 明文 v5 容器整份 SHA-256（32 字节）
   * @param selected 用户选择的范围集合（{@code NativeDataLocations.SCOPES} 子集、非空）
   * @param targetMappingKind 本机映射 2×2 编码（{@link #targetMappingKind(String, String)}）
   * @param nowMs 调用方当前时刻；编码进视图，JVM/Rust 都只读该内嵌值（JNI 无 nowMs 参数）
   */
  public static Receipt project(
      Map<String, Object> manifest,
      int rootCount,
      byte[] sourceDigest,
      Set<String> selected,
      int targetMappingKind,
      long nowMs) {
    Draft draft = new Draft();
    draft.rootCount = rootCount;
    draft.targetMappingKind = targetMappingKind;
    draft.generatedAtMs = nowMs;
    draft.nowMs = nowMs;
    try {
      if (manifest == null) throw new ProjectionFailure(DIAGNOSTIC_MANIFEST);
      if (sourceDigest == null || sourceDigest.length != DIGEST_BYTES)
        throw new ProjectionFailure(DIAGNOSTIC_SOURCE_DIGEST);
      checkRootCount(rootCount);
      checkMappingKind(targetMappingKind);
      draft.formatVersion = formatVersion(manifest);
      draft.operation = operation(manifest);
      draft.integrity = integrity(manifest);
      draft.entryCount = entryCount(manifest);
      draft.byteCount = byteCount(manifest);
      draft.createdAtMs = number(manifest, "createdAt", DIAGNOSTIC_TIMESTAMP);
      draft.pluginCount = pluginCount(manifest);
      draft.selectedMask = selectedMask(selected);
      draft.appVersion =
          text(manifest, "appVersion", PlanViewCodec.MAX_APP_VERSION_BYTES, DIAGNOSTIC_APP_VERSION);
      draft.dataFormat =
          text(manifest, "dataFormat", PlanViewCodec.MAX_DATA_FORMAT_BYTES, DIAGNOSTIC_DATA_FORMAT);
      draft.sensitivePolicy = sensitivePolicy(manifest);
      draft.sourceDigest = sourceDigest.clone();
      // §3.5：planDigest = SHA-256(字段 15 置零后的整份视图)；字段 15 是最后 32 字节。
      draft.planDigest = sha256(encode(draft, new byte[DIGEST_BYTES]));
      draft.view = encode(draft, draft.planDigest);
      draft.projected = true;
    } catch (ProjectionFailure failure) {
      return failed(draft, failure.code);
    } catch (PlanViewCodec.PlanViewException failure) {
      return failed(draft, DIAGNOSTIC_ENCODE);
    } catch (RuntimeException failure) {
      // 投影是诊断旁路：任何未预期运行时异常都放弃投影并记类型化诊断，不能把恢复拖成失败。
      return failed(draft, DIAGNOSTIC_ENCODE);
    }
    verify(draft);
    return new Receipt(draft);
  }

  /**
   * 提交期闸门：已投影且两实现一致 {@code VALID} 才放行。
   *
   * <p>{@code INVALID}、分歧、单侧失败一律抛类型化 {@link IOException}（fail-closed，§3.9 P-5）；
   * **未投影**回执直接放行——根数 > 64（Q1-A）或清单值类型不符（P-4）时视图不存在，Rust 未被调用，
   * JVM 读者/计划仍是权威路径。
   */
  public static void requireSubmitSafe(Receipt receipt) throws IOException {
    if (receipt == null) throw new IOException(SUBMIT_UNVERIFIED);
    if (!receipt.projected) return;
    if (VERDICT_VALID.equals(receipt.verdict)) return;
    if (VERDICT_DIVERGENT.equals(receipt.verdict)) throw new IOException(SUBMIT_DIVERGENT);
    if (VERDICT_UNVERIFIED.equals(receipt.verdict)) throw new IOException(SUBMIT_UNVERIFIED);
    throw new IOException(SUBMIT_INVALID);
  }

  /** §3.4 推导式：{@code 1 + (homeMode==LEGACY?1:0) + (projectDestination==GUEST_HOME?2:0)}。 */
  public static int targetMappingKind(String homeMode, String projectDestination) {
    int home;
    if ("STABLE".equals(homeMode)) home = 0;
    else if ("LEGACY".equals(homeMode)) home = 1;
    else return 0;
    int projects;
    if ("PRIVATE".equals(projectDestination)) projects = 0;
    else if ("GUEST_HOME".equals(projectDestination)) projects = 2;
    else return 0;
    return 1 + home + projects;
  }

  /** 64 位十六进制 → 32 字节；长度或字符非法返回 null（调用方按投影失败处理）。 */
  public static byte[] digestBytes(String hex) {
    if (hex == null || hex.length() != DIGEST_BYTES * 2) return null;
    byte[] bytes = new byte[DIGEST_BYTES];
    for (int index = 0; index < bytes.length; index++) {
      int high = Character.digit(hex.charAt(index * 2), 16);
      int low = Character.digit(hex.charAt(index * 2 + 1), 16);
      if (high < 0 || low < 0) return null;
      bytes[index] = (byte) ((high << 4) | low);
    }
    return bytes;
  }

  /** 测试专用：构造指定结论的回执，用于验证提交期闸门（不做投影/校验、不碰 Rust）。 */
  static Receipt simulated(boolean projected, String verdict, String diagnostic) {
    Draft draft = new Draft();
    draft.projected = projected;
    draft.verdict = verdict;
    draft.diagnostic = diagnostic;
    draft.backend = BACKEND_JVM_ONLY;
    draft.selection = "TEST";
    draft.jvm = CHECK_SKIPPED;
    draft.rust = CHECK_NOT_ATTEMPTED;
    return new Receipt(draft);
  }

  private static Receipt failed(Draft draft, String diagnostic) {
    draft.projected = false;
    draft.verdict = VERDICT_UNVERIFIED;
    draft.diagnostic = diagnostic;
    draft.backend = BACKEND_JVM_ONLY;
    draft.selection = SELECTION_NOT_EVALUATED;
    draft.jvm = CHECK_SKIPPED;
    draft.rust = CHECK_NOT_ATTEMPTED;
    draft.jvmReasons = new int[0];
    draft.rustReasons = new int[0];
    return new Receipt(draft);
  }

  private static void verify(Draft draft) {
    PlanViewValidator.Result jvm = PlanViewValidator.validate(draft.view);
    draft.jvm = jvm.valid() ? CHECK_VALID : CHECK_INVALID;
    draft.jvmReasons = jvm.reasons();
    RustBackends.Selection selection = RustBackends.planSelection();
    draft.selection = selection.reasonCode();
    if (!selection.useRust()) {
      // 开关关闭或库不可用：JVM 是唯一实现（§5.4 回退矩阵），选择原因写进回执。
      draft.backend = BACKEND_JVM_ONLY;
      draft.rust = CHECK_NOT_ATTEMPTED;
      draft.verdict = jvm.valid() ? VERDICT_VALID : VERDICT_INVALID;
      draft.diagnostic = jvm.valid() ? DIAGNOSTIC_READY : DIAGNOSTIC_INVALID;
      return;
    }
    draft.backend = BACKEND_JVM_AND_RUST;
    RustResult nativeResult = verifyRust(draft.view);
    if (nativeResult.code != 0 && nativeResult.code != 1) {
      draft.rust = CHECK_FAILED;
      draft.rustReasons = new int[0];
      draft.verdict = VERDICT_UNVERIFIED;
      draft.diagnostic = DIAGNOSTIC_UNVERIFIED;
      return;
    }
    int[] reasons = normalize(nativeResult.reasons);
    draft.rustReasons = reasons;
    boolean rustValid = nativeResult.code == 0;
    draft.rust = rustValid ? CHECK_VALID : CHECK_INVALID;
    if (jvm.valid() != rustValid || !Arrays.equals(draft.jvmReasons, reasons)) {
      // §3.9 P-5：两实现分歧 = 缺陷；禁止用 JVM 结果覆盖后继续。
      draft.verdict = VERDICT_DIVERGENT;
      draft.diagnostic = DIAGNOSTIC_DIVERGENT;
      return;
    }
    draft.verdict = jvm.valid() ? VERDICT_VALID : VERDICT_INVALID;
    draft.diagnostic = jvm.valid() ? DIAGNOSTIC_READY : DIAGNOSTIC_INVALID;
  }

  /** 调用 Rust {@code verify_plan}；返回码不支持/缓冲契约不符也按失败处理，绝不回退成 JVM 结论。 */
  private static RustResult verifyRust(byte[] view) {
    try {
      ByteBuffer viewBuffer = ByteBuffer.allocateDirect(view.length);
      viewBuffer.put(view);
      ByteBuffer reasonsBuffer =
          ByteBuffer.allocateDirect(4 + 4 * RUST_MAX_REASONS).order(ByteOrder.LITTLE_ENDIAN);
      int code = RustNative.verifyPlan(viewBuffer, view.length, reasonsBuffer, RUST_MAX_REASONS);
      if (code != 0 && code != 1) return new RustResult(code, null);
      int count = reasonsBuffer.getInt(0);
      if (count < 0 || count > RUST_MAX_REASONS) return new RustResult(RUST_ERROR_CONTRACT, null);
      int[] reasons = new int[count];
      for (int index = 0; index < count; index++)
        reasons[index] = reasonsBuffer.getInt(4 + index * 4);
      return new RustResult(code, reasons);
    } catch (RuntimeException | LinkageError unavailable) {
      return new RustResult(RUST_ERROR_CONTRACT, null);
    }
  }

  private static int[] normalize(int[] reasons) {
    int[] sorted = reasons == null ? new int[0] : reasons.clone();
    Arrays.sort(sorted);
    int unique = 0;
    for (int index = 0; index < sorted.length; index++) {
      if (index == 0 || sorted[index] != sorted[index - 1]) sorted[unique++] = sorted[index];
    }
    return Arrays.copyOf(sorted, unique);
  }

  private static void checkRootCount(int rootCount) throws ProjectionFailure {
    if (rootCount > PlanViewCodec.MAX_ROOT_COUNT)
      throw new ProjectionFailure(DIAGNOSTIC_ROOT_LIMIT);
    if (rootCount < PlanViewCodec.MIN_ROOT_COUNT) throw new ProjectionFailure(DIAGNOSTIC_COUNTS);
  }

  private static void checkMappingKind(int kind) throws ProjectionFailure {
    if (kind < PlanViewCodec.TARGET_MAPPING_STABLE_PRIVATE
        || kind > PlanViewCodec.TARGET_MAPPING_LEGACY_GUEST) {
      throw new ProjectionFailure(DIAGNOSTIC_MAPPING_KIND);
    }
  }

  private static byte[] encode(Draft draft, byte[] planDigest) {
    return PlanViewCodec.encode(
        PlanViewCodec.View.builder()
            .policyVersion(PlanViewCodec.POLICY_VERSION)
            .generatedAtMs(draft.generatedAtMs)
            .nowMs(draft.nowMs)
            .hasExpiry(false)
            .expiresAtMs(0)
            .formatVersion(draft.formatVersion)
            .operation(draft.operation)
            .integrity(draft.integrity)
            .rootCount(draft.rootCount)
            .entryCount(draft.entryCount)
            .byteCount(draft.byteCount)
            .createdAtMs(draft.createdAtMs)
            .sourceDigest(draft.sourceDigest)
            .planDigest(planDigest)
            .selectedMask(draft.selectedMask)
            .targetMappingKind(draft.targetMappingKind)
            .pluginCount(draft.pluginCount)
            .appVersion(draft.appVersion)
            .dataFormat(draft.dataFormat)
            .sensitivePolicy(draft.sensitivePolicy)
            .build());
  }

  private static int formatVersion(Map<String, Object> manifest) throws ProjectionFailure {
    long value = number(manifest, "formatVersion", DIAGNOSTIC_FORMAT_VERSION);
    if (value != PlanViewCodec.FORMAT_VERSION)
      throw new ProjectionFailure(DIAGNOSTIC_FORMAT_VERSION);
    return (int) value;
  }

  private static int operation(Map<String, Object> manifest) throws ProjectionFailure {
    String value = text(manifest, "operation", 16, DIAGNOSTIC_OPERATION);
    if ("EXPORT".equals(value)) return PlanViewCodec.OPERATION_EXPORT;
    if ("MAINTENANCE".equals(value)) return PlanViewCodec.OPERATION_MAINTENANCE;
    if ("RESCUE".equals(value)) return PlanViewCodec.OPERATION_RESCUE;
    throw new ProjectionFailure(DIAGNOSTIC_OPERATION);
  }

  private static int integrity(Map<String, Object> manifest) throws ProjectionFailure {
    String value = text(manifest, "integrity", 16, DIAGNOSTIC_INTEGRITY);
    if ("QUIESCENT".equals(value)) return PlanViewCodec.INTEGRITY_QUIESCENT;
    if ("EXTERNAL_CHECKED".equals(value)) return PlanViewCodec.INTEGRITY_EXTERNAL_CHECKED;
    if ("PARTIAL".equals(value)) return PlanViewCodec.INTEGRITY_PARTIAL;
    if ("BEST_EFFORT".equals(value)) return PlanViewCodec.INTEGRITY_BEST_EFFORT;
    throw new ProjectionFailure(DIAGNOSTIC_INTEGRITY);
  }

  private static int entryCount(Map<String, Object> manifest) throws ProjectionFailure {
    long value = number(manifest, "entries", DIAGNOSTIC_COUNTS);
    if (value < PlanViewCodec.MIN_ENTRY_COUNT || value > PlanViewCodec.MAX_ENTRY_COUNT)
      throw new ProjectionFailure(DIAGNOSTIC_COUNTS);
    return (int) value;
  }

  private static long byteCount(Map<String, Object> manifest) throws ProjectionFailure {
    long value = number(manifest, "bytes", DIAGNOSTIC_COUNTS);
    if (value < 0 || value > PlanViewCodec.MAX_BYTE_COUNT)
      throw new ProjectionFailure(DIAGNOSTIC_COUNTS);
    return value;
  }

  private static int pluginCount(Map<String, Object> manifest) throws ProjectionFailure {
    Object plugins = manifest.get("plugins");
    if (!(plugins instanceof Map)) throw new ProjectionFailure(DIAGNOSTIC_PLUGIN_COUNT);
    Object nodes = ((Map<?, ?>) plugins).get("nodes");
    if (nodes == null) {
      // v1–v4 导入与维护快照没有 v2 插件图（§3.3）：节点数按 0 记录。
      return 0;
    }
    if (!(nodes instanceof Map)) throw new ProjectionFailure(DIAGNOSTIC_PLUGIN_COUNT);
    int count = ((Map<?, ?>) nodes).size();
    if (count > PlanViewCodec.MAX_PLUGIN_COUNT)
      throw new ProjectionFailure(DIAGNOSTIC_PLUGIN_COUNT);
    return count;
  }

  private static long selectedMask(Set<String> selected) throws ProjectionFailure {
    if (selected == null || selected.isEmpty())
      throw new ProjectionFailure(DIAGNOSTIC_SELECTED_MASK);
    long mask = 0;
    for (String scope : selected) {
      int bit = BackupScopes.ORDERED.indexOf(scope);
      if (bit < 0) throw new ProjectionFailure(DIAGNOSTIC_SELECTED_MASK);
      mask |= 1L << bit;
    }
    if (mask < PlanViewCodec.MIN_SELECTED_MASK || mask > PlanViewCodec.MAX_SELECTED_MASK)
      throw new ProjectionFailure(DIAGNOSTIC_SELECTED_MASK);
    return mask;
  }

  private static String sensitivePolicy(Map<String, Object> manifest) throws ProjectionFailure {
    String value =
        text(
            manifest,
            "sensitivePolicy",
            PlanViewCodec.MAX_SENSITIVE_POLICY_BYTES,
            DIAGNOSTIC_SENSITIVE_POLICY);
    if (!PlanViewCodec.SENSITIVE_POLICIES.contains(value))
      throw new ProjectionFailure(DIAGNOSTIC_SENSITIVE_POLICY);
    return value;
  }

  private static long number(Map<String, Object> manifest, String key, String diagnostic)
      throws ProjectionFailure {
    Object value = manifest.get(key);
    if (!(value instanceof Number)) throw new ProjectionFailure(diagnostic);
    try {
      return value instanceof BigDecimal
          ? ((BigDecimal) value).longValueExact()
          : ((Number) value).longValue();
    } catch (ArithmeticException notAnInteger) {
      throw new ProjectionFailure(diagnostic);
    }
  }

  private static String text(
      Map<String, Object> manifest, String key, int maxBytes, String diagnostic)
      throws ProjectionFailure {
    Object value = manifest.get(key);
    if (!(value instanceof String)) throw new ProjectionFailure(diagnostic);
    String text = (String) value;
    byte[] encoded = strictUtf8(text);
    if (encoded == null
        || encoded.length == 0
        || encoded.length > maxBytes
        || encoded.length > PlanViewCodec.MAX_FIELD_BYTES
        || hasNul(encoded)) throw new ProjectionFailure(diagnostic);
    return text;
  }

  /** 严格 UTF-8 编码：孤立代理项等无法无损编码的字符串返回 null（不静默替换成 '?'）。 */
  private static byte[] strictUtf8(String value) {
    CharsetEncoder encoder =
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      ByteBuffer encoded = encoder.encode(CharBuffer.wrap(value));
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return bytes;
    } catch (CharacterCodingException unencodable) {
      return null;
    }
  }

  private static boolean hasNul(byte[] value) {
    for (byte element : value) if (element == 0) return true;
    return false;
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException unsupported) {
      // SHA-256 是 Java 平台的强制算法；缺失说明运行环境损坏，不能伪造摘要。
      throw new IllegalStateException("SHA_256_UNAVAILABLE", unsupported);
    }
  }

  private static String hex(byte[] value) {
    StringBuilder result = new StringBuilder(value.length * 2);
    for (byte element : value)
      result
          .append(Character.forDigit((element >> 4) & 0xF, 16))
          .append(Character.forDigit(element & 0xF, 16));
    return result.toString();
  }

  private static List<String> reasonNames(int[] reasons) {
    List<String> names = new ArrayList<>(reasons.length);
    for (int code : reasons) {
      String name = PlanViewValidator.reasonName(code);
      names.add(name == null ? REASON_UNKNOWN : name);
    }
    return names;
  }

  /** 固定短码收敛（与失败诊断共用同一白名单）；非法输入收敛成 UNKNOWN，不进入回执。 */
  private static String label(String value) {
    return BackupFailureDiagnostic.label(value);
  }
}
