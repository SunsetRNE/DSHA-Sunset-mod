package com.deepseekharness.app.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * {@code PlanView} v1 的 JVM 校验器（A3 任务基线 §3、§4.3）。
 *
 * <p>与 Rust {@code plan::validate} 是同一份契约的两个实现：只做结构/范围/策略版本/有效期
 * 校验，**不证明计划安全**、不替代 {@code BackupArchive.validateSummary}。纯 Java、零
 * Android import：不解析 JSON、不读文件/时钟/环境、不写日志、无静态可变状态，可并发调用；
 * 输入数组不被修改。
 *
 * <p>多原因规则（§3.7）：除硬上限（视图短于 48 字节、超过 2 MiB）外不因首错提前返回；
 * 原因集合去重、升序，v1 最多 6 条。任何无法判定的结构都 fail-closed 成
 * {@code INVALID + PLAN_STRUCTURE}，不向调用方抛异常。
 */
public final class PlanViewValidator {
  /** 校验通过（原因集合为空）。 */
  public static final int VALID = 0;

  /** 校验失败（原因集合非空）。 */
  public static final int INVALID = 1;

  /** 原因码（§3.7；与 ABI 的 {@code ERR_PLAN_*} 绝对值一一对应）。 */
  public static final int REASON_STRUCTURE = 20;

  public static final int REASON_FIELD_RANGE = 21;
  public static final int REASON_POLICY_VERSION = 22;
  public static final int REASON_EXPIRED = 23;
  public static final int REASON_LIMIT = 24;
  public static final int REASON_UNKNOWN_FIELD = 25;

  /** 类型化诊断短码（不含路径/内容）。 */
  public static final String DIAGNOSTIC_VALID = "PLAN_VALID";

  public static final String DIAGNOSTIC_INVALID = "PLAN_INVALID";

  private static final int REASON_COUNT = 6;

  private PlanViewValidator() {}

  /** 一次校验的只读结果：{@link #valid()} 与去重升序的 {@link #reasons()}。 */
  public static final class Result {
    private final boolean valid;
    private final int[] reasons;
    private final String diagnostic;

    private Result(boolean valid, int[] reasons, String diagnostic) {
      this.valid = valid;
      this.reasons = reasons;
      this.diagnostic = diagnostic;
    }

    /** 是否通过（等价于原因集合为空）。 */
    public boolean valid() {
      return valid;
    }

    /** 去重、升序的原因码副本（通过时为空数组）。 */
    public int[] reasons() {
      return reasons.clone();
    }

    /** 类型化诊断短码：{@link #DIAGNOSTIC_VALID} 或 {@link #DIAGNOSTIC_INVALID}。 */
    public String diagnostic() {
      return diagnostic;
    }

    /** 是否包含某个原因码。 */
    public boolean hasReason(int code) {
      for (int reason : reasons) {
        if (reason == code) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * 用视图内嵌的 {@code nowMs} 校验（§3.6）。
   *
   * <p>{@code nowMs} 的**唯一来源**是视图内嵌值：{@link RustNative#verifyPlan} 也只读
   * 内嵌时间，JVM 与 Rust 必须对同一份已编码字节给出一致结论。需要“新的当前时刻”时，
   * 调用方必须用 {@link PlanViewCodec#encode} 重新编码视图（含重算 {@code planDigest}），
   * 而不是覆盖入参；本类**不提供** nowMs 覆盖重载，避免“同一字节两条口径”形成有效期绕过。
   *
   * @param view 规范化视图字节；{@code null} 视为结构错误（fail-closed）
   */
  public static Result validate(byte[] view) {
    return validateInternal(view);
  }

  /** 原因码的冻结名字（诊断用）；未知码返回 {@code null}。 */
  public static String reasonName(int code) {
    switch (code) {
      case REASON_STRUCTURE:
        return "PLAN_STRUCTURE";
      case REASON_FIELD_RANGE:
        return "PLAN_FIELD_RANGE";
      case REASON_POLICY_VERSION:
        return "PLAN_POLICY_VERSION";
      case REASON_EXPIRED:
        return "PLAN_EXPIRED";
      case REASON_LIMIT:
        return "PLAN_LIMIT";
      case REASON_UNKNOWN_FIELD:
        return "PLAN_UNKNOWN_FIELD";
      default:
        return null;
    }
  }

  private static Result validateInternal(byte[] view) {
    boolean[] found = new boolean[REASON_COUNT];
    try {
      if (view == null || view.length < PlanViewCodec.HEADER_BYTES) {
        mark(found, REASON_STRUCTURE);
        return result(found);
      }
      if (view.length > PlanViewCodec.MAX_VIEW_BYTES) {
        // 硬上限：不继续遍历超大输入（与 Rust 实现同序）。
        mark(found, REASON_LIMIT);
        return result(found);
      }
      checkHeader(view, found);
      checkFields(view, found);
      return result(found);
    } catch (RuntimeException error) {
      // fail-closed：任何无法判定的结构都归 STRUCTURE，绝不向调用方抛出。
      boolean[] fallback = new boolean[REASON_COUNT];
      fallback[0] = true;
      return result(fallback);
    }
  }

  private static void checkHeader(byte[] view, boolean[] found) {
    if (view[0] != 'D' || view[1] != 'S' || view[2] != 'H' || view[3] != 'P') {
      mark(found, REASON_STRUCTURE);
    }
    if (readU16(view, 4) != PlanViewCodec.VIEW_VERSION) {
      mark(found, REASON_STRUCTURE);
    }
    if (readU16(view, 6) != PlanViewCodec.POLICY_VERSION) {
      mark(found, REASON_POLICY_VERSION);
    }
    if (readU32(view, 8) != view.length) {
      mark(found, REASON_STRUCTURE);
    }
    long fieldCount = readU32(view, 12);
    if (fieldCount > PlanViewCodec.MAX_FIELDS) {
      mark(found, REASON_LIMIT);
    }
    if (fieldCount != PlanViewCodec.FIELD_COUNT) {
      mark(found, REASON_STRUCTURE);
    }
    long flags = readU32(view, 16);
    if ((flags & ~PlanViewCodec.HEADER_FLAG_HAS_EXPIRY) != 0) {
      mark(found, REASON_STRUCTURE);
    }
    if (readU32(view, 44) != 0) {
      mark(found, REASON_STRUCTURE);
    }
    long generatedAtMs = readU64(view, 20);
    long expiresAtMs = readU64(view, 28);
    // nowMs 只取视图内嵌值（JNI verifyPlan 同样如此），不读系统时钟、不接受覆盖。
    long nowMs = readU64(view, 36);
    boolean hasExpiry = (flags & PlanViewCodec.HEADER_FLAG_HAS_EXPIRY) != 0;
    if (!hasExpiry && expiresAtMs != 0) {
      mark(found, REASON_STRUCTURE);
    }
    // Q3-A：expires <= generated 与 now >= expires 同归 PLAN_EXPIRED（u64 无符号比较）。
    if (hasExpiry
        && (Long.compareUnsigned(expiresAtMs, generatedAtMs) <= 0
            || Long.compareUnsigned(nowMs, expiresAtMs) >= 0)) {
      mark(found, REASON_EXPIRED);
    }
  }

  private static void checkFields(byte[] view, boolean[] found) {
    int offset = PlanViewCodec.HEADER_BYTES;
    long parsedFields = 0;
    int expectedId = 1;
    while (offset < view.length) {
      if (view.length - offset < 8) {
        mark(found, REASON_STRUCTURE);
        break;
      }
      int id = readU16(view, offset);
      int typeCode = readU8(view, offset + 2);
      int fieldFlags = readU8(view, offset + 3);
      long length = readU32(view, offset + 4);
      int valueStart = offset + 8;
      if (length > view.length - valueStart) {
        mark(found, REASON_STRUCTURE);
        break;
      }
      int valueLength = (int) length;
      offset = valueStart + valueLength;
      parsedFields++;
      if (fieldFlags != 0) {
        mark(found, REASON_STRUCTURE);
      }
      int expectedType = expectedType(id);
      if (expectedType < 0) {
        mark(found, REASON_UNKNOWN_FIELD);
      }
      if (id != expectedId) {
        mark(found, REASON_STRUCTURE);
      } else {
        expectedId++;
      }
      boolean shapeOk = typeLengthOk(typeCode, length);
      if (!shapeOk) {
        mark(found, REASON_STRUCTURE);
      }
      if (expectedType < 0) {
        continue;
      }
      if (typeCode != expectedType) {
        mark(found, REASON_STRUCTURE);
        continue;
      }
      // id 已知且类型匹配：UTF8 的字符串规则即便类型长度失败也可独立判定。
      if (typeCode == PlanViewCodec.TYPE_UTF8) {
        checkUtf8Field(id, view, valueStart, valueLength, found);
      } else if (shapeOk) {
        checkValue(id, view, valueStart, found);
      }
    }
    if (parsedFields != PlanViewCodec.FIELD_COUNT) {
      mark(found, REASON_STRUCTURE);
    }
    if (expectedId != PlanViewCodec.FIELD_COUNT + 1) {
      mark(found, REASON_STRUCTURE);
    }
  }

  private static void checkValue(int id, byte[] view, int offset, boolean[] found) {
    switch (id) {
      case PlanViewCodec.FIELD_FORMAT_VERSION:
        if (readU32(view, offset) != PlanViewCodec.FORMAT_VERSION) {
          mark(found, REASON_FIELD_RANGE);
        }
        return;
      case PlanViewCodec.FIELD_OPERATION:
        {
          int code = readU8(view, offset);
          if (code < PlanViewCodec.OPERATION_EXPORT || code > PlanViewCodec.OPERATION_RESCUE) {
            mark(found, REASON_FIELD_RANGE);
          }
          return;
        }
      case PlanViewCodec.FIELD_INTEGRITY:
        {
          int code = readU8(view, offset);
          if (code < PlanViewCodec.INTEGRITY_QUIESCENT
              || code > PlanViewCodec.INTEGRITY_BEST_EFFORT) {
            mark(found, REASON_FIELD_RANGE);
          }
          return;
        }
      case PlanViewCodec.FIELD_ROOT_COUNT:
        {
          long count = readU32(view, offset);
          if (count < PlanViewCodec.MIN_ROOT_COUNT || count > PlanViewCodec.MAX_ROOT_COUNT) {
            mark(found, REASON_FIELD_RANGE);
          }
          return;
        }
      case PlanViewCodec.FIELD_ENTRY_COUNT:
        {
          long count = readU32(view, offset);
          if (count < PlanViewCodec.MIN_ENTRY_COUNT || count > PlanViewCodec.MAX_ENTRY_COUNT) {
            mark(found, REASON_FIELD_RANGE);
          }
          return;
        }
      case PlanViewCodec.FIELD_BYTE_COUNT:
        if (Long.compareUnsigned(readU64(view, offset), PlanViewCodec.MAX_BYTE_COUNT) > 0) {
          mark(found, REASON_FIELD_RANGE);
        }
        return;
      case PlanViewCodec.FIELD_CREATED_AT_MS:
        // 任意 u64；无跨字段规则（§3.3）。
        return;
      case PlanViewCodec.FIELD_SOURCE_DIGEST:
      case PlanViewCodec.FIELD_PLAN_DIGEST:
        // 摘要内容 v1 不校验（§3.5），长度由类型层约束。
        return;
      case PlanViewCodec.FIELD_SELECTED_MASK:
        {
          long mask = readU32(view, offset);
          if (mask < PlanViewCodec.MIN_SELECTED_MASK || mask > PlanViewCodec.MAX_SELECTED_MASK) {
            mark(found, REASON_FIELD_RANGE);
          }
          return;
        }
      case PlanViewCodec.FIELD_TARGET_MAPPING_KIND:
        {
          int code = readU8(view, offset);
          if (code < PlanViewCodec.TARGET_MAPPING_STABLE_PRIVATE
              || code > PlanViewCodec.TARGET_MAPPING_LEGACY_GUEST) {
            mark(found, REASON_FIELD_RANGE);
          }
          return;
        }
      case PlanViewCodec.FIELD_PLUGIN_COUNT:
        if (readU32(view, offset) > PlanViewCodec.MAX_PLUGIN_COUNT) {
          mark(found, REASON_FIELD_RANGE);
        }
        return;
      default:
        // UTF8 字段在 checkUtf8Field 处理；未知 id 不会走到这里。
        return;
    }
  }

  private static void checkUtf8Field(int id, byte[] view, int offset, int length, boolean[] found) {
    if (length == 0) {
      // 空串：类型长度 1..=4096（调用点已记 STRUCTURE）与非空规则同时失败。
      mark(found, REASON_FIELD_RANGE);
      return;
    }
    if (length > maxUtf8Bytes(id)) {
      mark(found, REASON_FIELD_RANGE);
    }
    if (length > PlanViewCodec.MAX_FIELD_BYTES) {
      return;
    }
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    String text;
    try {
      text = decoder.decode(ByteBuffer.wrap(view, offset, length)).toString();
    } catch (CharacterCodingException error) {
      mark(found, REASON_STRUCTURE);
      return;
    }
    if (hasNul(view, offset, length)) {
      mark(found, REASON_FIELD_RANGE);
    }
    if (id == PlanViewCodec.FIELD_SENSITIVE_POLICY
        && !PlanViewCodec.SENSITIVE_POLICIES.contains(text)) {
      mark(found, REASON_FIELD_RANGE);
    }
  }

  private static int maxUtf8Bytes(int id) {
    switch (id) {
      case PlanViewCodec.FIELD_APP_VERSION:
        return PlanViewCodec.MAX_APP_VERSION_BYTES;
      case PlanViewCodec.FIELD_DATA_FORMAT:
        return PlanViewCodec.MAX_DATA_FORMAT_BYTES;
      case PlanViewCodec.FIELD_SENSITIVE_POLICY:
        return PlanViewCodec.MAX_SENSITIVE_POLICY_BYTES;
      default:
        return PlanViewCodec.MAX_FIELD_BYTES;
    }
  }

  private static boolean hasNul(byte[] view, int offset, int length) {
    for (int index = 0; index < length; index++) {
      if (view[offset + index] == 0) {
        return true;
      }
    }
    return false;
  }

  private static int expectedType(int id) {
    switch (id) {
      case PlanViewCodec.FIELD_FORMAT_VERSION:
      case PlanViewCodec.FIELD_ROOT_COUNT:
      case PlanViewCodec.FIELD_ENTRY_COUNT:
      case PlanViewCodec.FIELD_SELECTED_MASK:
      case PlanViewCodec.FIELD_PLUGIN_COUNT:
        return PlanViewCodec.TYPE_U32;
      case PlanViewCodec.FIELD_OPERATION:
      case PlanViewCodec.FIELD_INTEGRITY:
      case PlanViewCodec.FIELD_TARGET_MAPPING_KIND:
        return PlanViewCodec.TYPE_U8;
      case PlanViewCodec.FIELD_BYTE_COUNT:
      case PlanViewCodec.FIELD_CREATED_AT_MS:
        return PlanViewCodec.TYPE_U64;
      case PlanViewCodec.FIELD_SOURCE_DIGEST:
      case PlanViewCodec.FIELD_PLAN_DIGEST:
        return PlanViewCodec.TYPE_BYTES32;
      case PlanViewCodec.FIELD_APP_VERSION:
      case PlanViewCodec.FIELD_DATA_FORMAT:
      case PlanViewCodec.FIELD_SENSITIVE_POLICY:
        return PlanViewCodec.TYPE_UTF8;
      default:
        return -1;
    }
  }

  private static boolean typeLengthOk(int typeCode, long length) {
    switch (typeCode) {
      case PlanViewCodec.TYPE_U8:
        return length == 1;
      case PlanViewCodec.TYPE_U32:
        return length == 4;
      case PlanViewCodec.TYPE_U64:
        return length == 8;
      case PlanViewCodec.TYPE_BYTES32:
        return length == 32;
      case PlanViewCodec.TYPE_UTF8:
        return length >= 1 && length <= PlanViewCodec.MAX_FIELD_BYTES;
      default:
        return false;
    }
  }

  private static void mark(boolean[] found, int code) {
    int index = code - REASON_STRUCTURE;
    if (index >= 0 && index < REASON_COUNT) {
      found[index] = true;
    }
  }

  private static Result result(boolean[] found) {
    int count = 0;
    for (boolean value : found) {
      if (value) {
        count++;
      }
    }
    int[] reasons = new int[count];
    int index = 0;
    for (int code = REASON_STRUCTURE; code <= REASON_UNKNOWN_FIELD; code++) {
      if (found[code - REASON_STRUCTURE]) {
        reasons[index++] = code;
      }
    }
    boolean valid = count == 0;
    return new Result(valid, reasons, valid ? DIAGNOSTIC_VALID : DIAGNOSTIC_INVALID);
  }

  private static int readU8(byte[] view, int offset) {
    return view[offset] & 0xFF;
  }

  private static int readU16(byte[] view, int offset) {
    return (view[offset] & 0xFF) | ((view[offset + 1] & 0xFF) << 8);
  }

  private static long readU32(byte[] view, int offset) {
    return (view[offset] & 0xFFL)
        | ((view[offset + 1] & 0xFFL) << 8)
        | ((view[offset + 2] & 0xFFL) << 16)
        | ((view[offset + 3] & 0xFFL) << 24);
  }

  private static long readU64(byte[] view, int offset) {
    long value = 0;
    for (int index = 0; index < 8; index++) {
      value |= (view[offset + index] & 0xFFL) << (index * 8);
    }
    return value;
  }

  /** 视图里出现过的原因名字（诊断/测试用；空数组表示通过）。 */
  public static String[] reasonNames(int[] reasons) {
    String[] names = new String[reasons.length];
    for (int index = 0; index < reasons.length; index++) {
      names[index] = reasonName(reasons[index]);
    }
    return names;
  }
}
