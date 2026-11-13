package com.deepseekharness.app.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * {@code PlanView} v1 规范化编解码（A3 任务基线 §3.2–§3.5、§4.2）。
 *
 * <p>本类只做**字节层**：把不可变 {@link View} 编码成确定性小端字节（同一输入同输出），
 * 以及把自洽的结构解码回 {@link View}。它不做语义裁决（枚举/范围/有效期归
 * {@link PlanViewValidator}），不解析 JSON、不读文件/时钟/环境、不写日志、无静态可变状态。
 *
 * <p>布局（§3.2，紧凑无填充）：48 字节头 + 15 个字段（{@code u16 id, u8 type,
 * u8 flags, u32 length, value[length]}），字段 id 必须升序 1..15，字段 {@code flags} 必须 0。
 * 字段表见 {@link #FIELD_FORMAT_VERSION} … {@link #FIELD_PLAN_DIGEST}；{@code flags} 位 0
 * 表示带有效期。
 *
 * <p>编码不截断：宽度放不下的整数、长度不是 32 的摘要、超过 {@link #MAX_FIELD_BYTES} 的串
 * 都抛类型化 {@link PlanViewException}。字段值越界（例如 {@code rootCount=65}）仍可编码，
 * 因为“可编码”与“合法”是两件事，校验器才是裁决者。
 */
public final class PlanViewCodec {
  /** 视图版本；首期只接受 1。 */
  public static final int VIEW_VERSION = 1;

  /** 唯一允许的策略版本（§3.2）。 */
  public static final int POLICY_VERSION = 1;

  /** 头部固定 48 字节。 */
  public static final int HEADER_BYTES = 48;

  /** 单份视图最大长度（2 MiB）。 */
  public static final int MAX_VIEW_BYTES = 2 * 1024 * 1024;

  /** v1 必须恰好包含的字段数。 */
  public static final int FIELD_COUNT = 15;

  /** 字段数上限（§3.2；超过即 PLAN_LIMIT）。 */
  public static final int MAX_FIELDS = 64;

  /** 每字段长度上限（§3.2）。 */
  public static final int MAX_FIELD_BYTES = 4_096;

  /** 头部 flags 的 bit0：带有效期。 */
  public static final int HEADER_FLAG_HAS_EXPIRY = 1;

  // 类型码（§3.2 冻结）。
  public static final int TYPE_U8 = 1;
  public static final int TYPE_U32 = 2;
  public static final int TYPE_U64 = 3;
  public static final int TYPE_BYTES32 = 4;
  public static final int TYPE_UTF8 = 5;

  // 字段 id（§3.3 冻结）。
  public static final int FIELD_FORMAT_VERSION = 1;
  public static final int FIELD_OPERATION = 2;
  public static final int FIELD_INTEGRITY = 3;
  public static final int FIELD_ROOT_COUNT = 4;
  public static final int FIELD_ENTRY_COUNT = 5;
  public static final int FIELD_BYTE_COUNT = 6;
  public static final int FIELD_CREATED_AT_MS = 7;
  public static final int FIELD_SOURCE_DIGEST = 8;
  public static final int FIELD_SELECTED_MASK = 9;
  public static final int FIELD_TARGET_MAPPING_KIND = 10;
  public static final int FIELD_APP_VERSION = 11;
  public static final int FIELD_DATA_FORMAT = 12;
  public static final int FIELD_SENSITIVE_POLICY = 13;
  public static final int FIELD_PLUGIN_COUNT = 14;
  public static final int FIELD_PLAN_DIGEST = 15;

  // 枚举码（§3.4 冻结）。
  public static final int OPERATION_EXPORT = 1;
  public static final int OPERATION_MAINTENANCE = 2;
  public static final int OPERATION_RESCUE = 3;

  public static final int INTEGRITY_QUIESCENT = 1;
  public static final int INTEGRITY_EXTERNAL_CHECKED = 2;
  public static final int INTEGRITY_PARTIAL = 3;
  public static final int INTEGRITY_BEST_EFFORT = 4;

  public static final int TARGET_MAPPING_STABLE_PRIVATE = 1;
  public static final int TARGET_MAPPING_LEGACY_PRIVATE = 2;
  public static final int TARGET_MAPPING_STABLE_GUEST = 3;
  public static final int TARGET_MAPPING_LEGACY_GUEST = 4;

  // 取值范围（§3.3 冻结）。
  public static final int FORMAT_VERSION = 5;
  public static final int MIN_ROOT_COUNT = 1;
  public static final int MAX_ROOT_COUNT = 64;
  public static final int MIN_ENTRY_COUNT = 1;
  public static final int MAX_ENTRY_COUNT = 100_000;
  public static final long MAX_BYTE_COUNT = 16L * 1024 * 1024 * 1024;
  public static final int MIN_SELECTED_MASK = 1;
  public static final int MAX_SELECTED_MASK = 31;
  public static final int MAX_PLUGIN_COUNT = 4_096;
  public static final int MAX_APP_VERSION_BYTES = 256;
  public static final int MAX_DATA_FORMAT_BYTES = 64;
  public static final int MAX_SENSITIVE_POLICY_BYTES = 64;

  /** {@code sensitivePolicy} v1 闭集（Q2-A；不可变列表）。 */
  public static final List<String> SENSITIVE_POLICIES =
      List.of(
          "UNENCRYPTED",
          "PASSWORD_ENCRYPTED",
          "APP_PRIVATE_STAGING",
          "LEGACY_PLAINTEXT",
          "PRIVATE_LOCAL_NATIVE_STATE_IN_PLACE");

  private PlanViewCodec() {}

  /** 类型化编解码错误（短码，不含路径/内容）；继承 {@link RuntimeException} 便于纯函数入口。 */
  public static final class PlanViewException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    PlanViewException(String code, String message) {
      super(message);
      this.code = code;
    }

    /** 类型化短码（诊断用，例如 {@code PLAN_VIEW_STRUCTURE}）。 */
    public String code() {
      return code;
    }
  }

  /** 不可变投影输入：只含计数/枚举/位图/摘要/短串，没有路径或文件清单。 */
  public static final class View {
    public final int policyVersion;
    public final long generatedAtMs;
    public final long expiresAtMs;
    public final long nowMs;
    public final boolean hasExpiry;
    public final int formatVersion;
    public final int operation;
    public final int integrity;
    public final int rootCount;
    public final int entryCount;
    public final long byteCount;
    public final long createdAtMs;
    public final byte[] sourceDigest;
    public final byte[] planDigest;
    public final long selectedMask;
    public final int targetMappingKind;
    public final int pluginCount;
    public final String appVersion;
    public final String dataFormat;
    public final String sensitivePolicy;

    private View(Builder builder) {
      this.policyVersion = builder.policyVersion;
      this.generatedAtMs = builder.generatedAtMs;
      this.expiresAtMs = builder.expiresAtMs;
      this.nowMs = builder.nowMs;
      this.hasExpiry = builder.hasExpiry;
      this.formatVersion = builder.formatVersion;
      this.operation = builder.operation;
      this.integrity = builder.integrity;
      this.rootCount = builder.rootCount;
      this.entryCount = builder.entryCount;
      this.byteCount = builder.byteCount;
      this.createdAtMs = builder.createdAtMs;
      this.sourceDigest = builder.sourceDigest.clone();
      this.planDigest = builder.planDigest.clone();
      this.selectedMask = builder.selectedMask;
      this.targetMappingKind = builder.targetMappingKind;
      this.pluginCount = builder.pluginCount;
      this.appVersion = builder.appVersion;
      this.dataFormat = builder.dataFormat;
      this.sensitivePolicy = builder.sensitivePolicy;
    }

    /** 新构建器（默认值是一份合法视图，便于测试/诊断改造单个字段）。 */
    public static Builder builder() {
      return new Builder();
    }
  }

  /** {@link View} 构建器：只做宽度/摘要长度/字符串非空检查，不做语义裁决。 */
  public static final class Builder {
    private int policyVersion = POLICY_VERSION;
    private long generatedAtMs = 1_760_000_000_500L;
    private long expiresAtMs = 0;
    private long nowMs = 1_760_000_000_500L;
    private boolean hasExpiry = false;
    private int formatVersion = FORMAT_VERSION;
    private int operation = OPERATION_EXPORT;
    private int integrity = INTEGRITY_QUIESCENT;
    private int rootCount = 1;
    private int entryCount = 42;
    private long byteCount = 1_024L;
    private long createdAtMs = 1_760_000_000_000L;
    private byte[] sourceDigest = patternedDigest(0);
    private byte[] planDigest = patternedDigest(0xA0);
    private long selectedMask = 3;
    private int targetMappingKind = TARGET_MAPPING_STABLE_PRIVATE;
    private int pluginCount = 12;
    private String appVersion = "0.2.1-rc.5-20261113.1000-dsh0.2.2-rc.1";
    private String dataFormat = "UNINSPECTED";
    private String sensitivePolicy = "PRIVATE_LOCAL_NATIVE_STATE_IN_PLACE";

    private Builder() {}

    public Builder policyVersion(int value) {
      this.policyVersion = value;
      return this;
    }

    public Builder generatedAtMs(long value) {
      this.generatedAtMs = value;
      return this;
    }

    public Builder expiresAtMs(long value) {
      this.expiresAtMs = value;
      return this;
    }

    public Builder nowMs(long value) {
      this.nowMs = value;
      return this;
    }

    public Builder hasExpiry(boolean value) {
      this.hasExpiry = value;
      return this;
    }

    public Builder formatVersion(int value) {
      this.formatVersion = value;
      return this;
    }

    public Builder operation(int value) {
      this.operation = value;
      return this;
    }

    public Builder integrity(int value) {
      this.integrity = value;
      return this;
    }

    public Builder rootCount(int value) {
      this.rootCount = value;
      return this;
    }

    public Builder entryCount(int value) {
      this.entryCount = value;
      return this;
    }

    public Builder byteCount(long value) {
      this.byteCount = value;
      return this;
    }

    public Builder createdAtMs(long value) {
      this.createdAtMs = value;
      return this;
    }

    public Builder sourceDigest(byte[] value) {
      this.sourceDigest = value;
      return this;
    }

    public Builder planDigest(byte[] value) {
      this.planDigest = value;
      return this;
    }

    public Builder selectedMask(long value) {
      this.selectedMask = value;
      return this;
    }

    public Builder targetMappingKind(int value) {
      this.targetMappingKind = value;
      return this;
    }

    public Builder pluginCount(int value) {
      this.pluginCount = value;
      return this;
    }

    public Builder appVersion(String value) {
      this.appVersion = value;
      return this;
    }

    public Builder dataFormat(String value) {
      this.dataFormat = value;
      return this;
    }

    public Builder sensitivePolicy(String value) {
      this.sensitivePolicy = value;
      return this;
    }

    /**
     * 构造不可变视图。
     *
     * @throws PlanViewException 摘要不是 32 字节、字符串为 null
     */
    public View build() {
      if (sourceDigest == null || sourceDigest.length != 32) {
        throw new PlanViewException("PLAN_VIEW_DIGEST", "sourceDigest 必须为 32 字节");
      }
      if (planDigest == null || planDigest.length != 32) {
        throw new PlanViewException("PLAN_VIEW_DIGEST", "planDigest 必须为 32 字节");
      }
      if (appVersion == null || dataFormat == null || sensitivePolicy == null) {
        throw new PlanViewException("PLAN_VIEW_STRING_NULL", "字符串字段不能为 null");
      }
      return new View(this);
    }
  }

  private static byte[] patternedDigest(int seed) {
    byte[] digest = new byte[32];
    for (int index = 0; index < digest.length; index++) {
      digest[index] = (byte) (seed + index);
    }
    return digest;
  }

  /**
   * 编码为确定性小端字节；超限抛类型化异常，绝不截断。
   *
   * @throws PlanViewException 宽度放不下、摘要长度不为 32、字符串为 null 或超字段上限
   */
  public static byte[] encode(View view) {
    if (view == null) {
      throw new PlanViewException("PLAN_VIEW_NULL", "view 不能为 null");
    }
    byte[][] values = new byte[FIELD_COUNT][];
    int total = HEADER_BYTES;
    for (int index = 0; index < FIELD_COUNT; index++) {
      values[index] = encodeValue(view, index + 1);
      total += 8 + values[index].length;
      if (total < 0 || total > MAX_VIEW_BYTES) {
        throw new PlanViewException("PLAN_VIEW_LIMIT", "视图超过 2 MiB 上限");
      }
    }
    if (view.policyVersion < 0 || view.policyVersion > 0xFFFF) {
      throw new PlanViewException("PLAN_VIEW_RANGE", "policyVersion 不是 u16");
    }
    ByteBuffer buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
    buffer.put((byte) 'D');
    buffer.put((byte) 'S');
    buffer.put((byte) 'H');
    buffer.put((byte) 'P');
    buffer.putShort((short) VIEW_VERSION);
    buffer.putShort((short) view.policyVersion);
    buffer.putInt(total);
    buffer.putInt(FIELD_COUNT);
    buffer.putInt(view.hasExpiry ? HEADER_FLAG_HAS_EXPIRY : 0);
    buffer.putLong(view.generatedAtMs);
    buffer.putLong(view.expiresAtMs);
    buffer.putLong(view.nowMs);
    buffer.putInt(0);
    for (int index = 0; index < FIELD_COUNT; index++) {
      byte[] value = values[index];
      buffer.putShort((short) (index + 1));
      buffer.put((byte) expectedType(index + 1));
      buffer.put((byte) 0);
      buffer.putInt(value.length);
      buffer.put(value);
    }
    return buffer.array();
  }

  /**
   * 解码一段结构自洽的视图；只做结构/UTF-8 校验，不做语义裁决。
   *
   * @throws PlanViewException 结构、类型、长度、UTF-8 不自洽
   */
  public static View decode(byte[] bytes) throws PlanViewException {
    if (bytes == null) {
      throw new PlanViewException("PLAN_VIEW_STRUCTURE", "视图为 null");
    }
    if (bytes.length < HEADER_BYTES) {
      throw new PlanViewException("PLAN_VIEW_STRUCTURE", "视图短于 48 字节头");
    }
    if (bytes.length > MAX_VIEW_BYTES) {
      throw new PlanViewException("PLAN_VIEW_LIMIT", "视图超过 2 MiB 上限");
    }
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    if (bytes[0] != 'D' || bytes[1] != 'S' || bytes[2] != 'H' || bytes[3] != 'P') {
      throw new PlanViewException("PLAN_VIEW_STRUCTURE", "magic 不是 DSHP");
    }
    if ((buffer.getShort(4) & 0xFFFF) != VIEW_VERSION) {
      throw new PlanViewException("PLAN_VIEW_STRUCTURE", "viewVersion 不是 1");
    }
    if ((buffer.getInt(8) & 0xFFFF_FFFFL) != bytes.length) {
      throw new PlanViewException("PLAN_VIEW_STRUCTURE", "totalLength 与视图长度不符");
    }
    if ((buffer.getInt(12) & 0xFFFF_FFFFL) != FIELD_COUNT) {
      throw new PlanViewException("PLAN_VIEW_STRUCTURE", "fieldCount 不是 15");
    }
    int flags = buffer.getInt(16);
    if ((flags & ~HEADER_FLAG_HAS_EXPIRY) != 0) {
      throw new PlanViewException("PLAN_VIEW_STRUCTURE", "头部 flags 含保留位");
    }
    if (buffer.getInt(44) != 0) {
      throw new PlanViewException("PLAN_VIEW_STRUCTURE", "reserved 不为 0");
    }
    boolean hasExpiry = (flags & HEADER_FLAG_HAS_EXPIRY) != 0;
    long expiresAtMs = buffer.getLong(28);
    if (!hasExpiry && expiresAtMs != 0) {
      throw new PlanViewException("PLAN_VIEW_STRUCTURE", "无有效期时 expiresAtMs 必须为 0");
    }
    Builder builder =
        View.builder()
            .policyVersion(buffer.getShort(6) & 0xFFFF)
            .generatedAtMs(buffer.getLong(20))
            .expiresAtMs(expiresAtMs)
            .nowMs(buffer.getLong(36))
            .hasExpiry(hasExpiry);
    int offset = HEADER_BYTES;
    for (int expectedId = 1; expectedId <= FIELD_COUNT; expectedId++) {
      if (offset + 8 > bytes.length) {
        throw new PlanViewException("PLAN_VIEW_STRUCTURE", "字段头截断");
      }
      int id = buffer.getShort(offset) & 0xFFFF;
      int typeCode = bytes[offset + 2] & 0xFF;
      int fieldFlags = bytes[offset + 3] & 0xFF;
      long length = buffer.getInt(offset + 4) & 0xFFFF_FFFFL;
      if (id != expectedId) {
        throw new PlanViewException("PLAN_VIEW_STRUCTURE", "字段 id 乱序/重复/缺失");
      }
      if (fieldFlags != 0) {
        throw new PlanViewException("PLAN_VIEW_STRUCTURE", "字段 flags 不为 0");
      }
      if (typeCode != expectedType(id)) {
        throw new PlanViewException("PLAN_VIEW_STRUCTURE", "字段类型码不符");
      }
      if (!typeLengthOk(typeCode, length)) {
        throw new PlanViewException("PLAN_VIEW_STRUCTURE", "字段长度与类型不符");
      }
      int valueStart = offset + 8;
      int valueLength = (int) length;
      int valueEnd = valueStart + valueLength;
      if (valueEnd > bytes.length) {
        throw new PlanViewException("PLAN_VIEW_STRUCTURE", "字段值越界");
      }
      applyDecodedValue(builder, id, bytes, valueStart, valueLength);
      offset = valueEnd;
    }
    if (offset != bytes.length) {
      throw new PlanViewException("PLAN_VIEW_STRUCTURE", "字段区后存在尾随字节");
    }
    return builder.build();
  }

  /**
   * 返回把字段 15 的 32 字节值置零后的视图副本（§3.5 自校验用）。
   *
   * @throws PlanViewException 结构非法或缺少字段 15
   */
  public static byte[] withZeroPlanDigest(byte[] view) {
    if (view == null) {
      throw new PlanViewException("PLAN_VIEW_NULL", "view 不能为 null");
    }
    byte[] copy = view.clone();
    int offset = HEADER_BYTES;
    while (offset + 8 <= copy.length) {
      int id = (copy[offset] & 0xFF) | ((copy[offset + 1] & 0xFF) << 8);
      int typeCode = copy[offset + 2] & 0xFF;
      long length =
          (copy[offset + 4] & 0xFFL)
              | ((copy[offset + 5] & 0xFFL) << 8)
              | ((copy[offset + 6] & 0xFFL) << 16)
              | ((copy[offset + 7] & 0xFFL) << 24);
      int valueStart = offset + 8;
      if (length > copy.length - valueStart) {
        throw new PlanViewException("PLAN_VIEW_STRUCTURE", "字段值越界");
      }
      if (id == FIELD_PLAN_DIGEST) {
        if (typeCode != TYPE_BYTES32 || length != 32) {
          throw new PlanViewException("PLAN_VIEW_STRUCTURE", "字段 15 不是 32 字节摘要");
        }
        Arrays.fill(copy, valueStart, valueStart + 32, (byte) 0);
        return copy;
      }
      offset = valueStart + (int) length;
    }
    throw new PlanViewException("PLAN_VIEW_STRUCTURE", "缺少字段 15");
  }

  private static byte[] encodeValue(View view, int id) {
    switch (id) {
      case FIELD_FORMAT_VERSION:
        return u32(view.formatVersion);
      case FIELD_OPERATION:
        return u8(view.operation, "operation");
      case FIELD_INTEGRITY:
        return u8(view.integrity, "integrity");
      case FIELD_ROOT_COUNT:
        return u32(view.rootCount);
      case FIELD_ENTRY_COUNT:
        return u32(view.entryCount);
      case FIELD_BYTE_COUNT:
        return u64(view.byteCount);
      case FIELD_CREATED_AT_MS:
        return u64(view.createdAtMs);
      case FIELD_SOURCE_DIGEST:
        return bytes32(view.sourceDigest, "sourceDigest");
      case FIELD_SELECTED_MASK:
        return u32FromLong(view.selectedMask, "selectedMask");
      case FIELD_TARGET_MAPPING_KIND:
        return u8(view.targetMappingKind, "targetMappingKind");
      case FIELD_APP_VERSION:
        return utf8(view.appVersion, "appVersion");
      case FIELD_DATA_FORMAT:
        return utf8(view.dataFormat, "dataFormat");
      case FIELD_SENSITIVE_POLICY:
        return utf8(view.sensitivePolicy, "sensitivePolicy");
      case FIELD_PLUGIN_COUNT:
        return u32(view.pluginCount);
      case FIELD_PLAN_DIGEST:
        return bytes32(view.planDigest, "planDigest");
      default:
        throw new PlanViewException("PLAN_VIEW_STRUCTURE", "未知字段 id");
    }
  }

  private static int expectedType(int id) {
    switch (id) {
      case FIELD_FORMAT_VERSION:
      case FIELD_ROOT_COUNT:
      case FIELD_ENTRY_COUNT:
      case FIELD_SELECTED_MASK:
      case FIELD_PLUGIN_COUNT:
        return TYPE_U32;
      case FIELD_OPERATION:
      case FIELD_INTEGRITY:
      case FIELD_TARGET_MAPPING_KIND:
        return TYPE_U8;
      case FIELD_BYTE_COUNT:
      case FIELD_CREATED_AT_MS:
        return TYPE_U64;
      case FIELD_SOURCE_DIGEST:
      case FIELD_PLAN_DIGEST:
        return TYPE_BYTES32;
      case FIELD_APP_VERSION:
      case FIELD_DATA_FORMAT:
      case FIELD_SENSITIVE_POLICY:
        return TYPE_UTF8;
      default:
        return -1;
    }
  }

  private static boolean typeLengthOk(int typeCode, long length) {
    switch (typeCode) {
      case TYPE_U8:
        return length == 1;
      case TYPE_U32:
        return length == 4;
      case TYPE_U64:
        return length == 8;
      case TYPE_BYTES32:
        return length == 32;
      case TYPE_UTF8:
        return length >= 1 && length <= MAX_FIELD_BYTES;
      default:
        return false;
    }
  }

  private static byte[] u8(int value, String name) {
    if (value < 0 || value > 0xFF) {
      throw new PlanViewException("PLAN_VIEW_RANGE", name + " 不是 u8");
    }
    return new byte[] {(byte) value};
  }

  private static byte[] u32(int value) {
    return new byte[] {
      (byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)
    };
  }

  private static byte[] u32FromLong(long value, String name) {
    if (value < 0 || value > 0xFFFF_FFFFL) {
      throw new PlanViewException("PLAN_VIEW_RANGE", name + " 不是 u32");
    }
    int raw = (int) value;
    return u32(raw);
  }

  private static byte[] u64(long value) {
    return new byte[] {
      (byte) value,
      (byte) (value >>> 8),
      (byte) (value >>> 16),
      (byte) (value >>> 24),
      (byte) (value >>> 32),
      (byte) (value >>> 40),
      (byte) (value >>> 48),
      (byte) (value >>> 56)
    };
  }

  private static byte[] bytes32(byte[] value, String name) {
    if (value == null || value.length != 32) {
      throw new PlanViewException("PLAN_VIEW_DIGEST", name + " 必须为 32 字节");
    }
    return value.clone();
  }

  private static byte[] utf8(String value, String name) {
    if (value == null) {
      throw new PlanViewException("PLAN_VIEW_STRING_NULL", name + " 不能为 null");
    }
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    if (encoded.length > MAX_FIELD_BYTES) {
      throw new PlanViewException("PLAN_VIEW_LIMIT", name + " 超过 4096 字节");
    }
    return encoded;
  }

  private static void applyDecodedValue(
      Builder builder, int id, byte[] bytes, int offset, int length) {
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    switch (id) {
      case FIELD_FORMAT_VERSION:
        builder.formatVersion(buffer.getInt(offset));
        return;
      case FIELD_OPERATION:
        builder.operation(bytes[offset] & 0xFF);
        return;
      case FIELD_INTEGRITY:
        builder.integrity(bytes[offset] & 0xFF);
        return;
      case FIELD_ROOT_COUNT:
        builder.rootCount(buffer.getInt(offset));
        return;
      case FIELD_ENTRY_COUNT:
        builder.entryCount(buffer.getInt(offset));
        return;
      case FIELD_BYTE_COUNT:
        builder.byteCount(buffer.getLong(offset));
        return;
      case FIELD_CREATED_AT_MS:
        builder.createdAtMs(buffer.getLong(offset));
        return;
      case FIELD_SOURCE_DIGEST:
        builder.sourceDigest(Arrays.copyOfRange(bytes, offset, offset + length));
        return;
      case FIELD_SELECTED_MASK:
        builder.selectedMask(buffer.getInt(offset) & 0xFFFF_FFFFL);
        return;
      case FIELD_TARGET_MAPPING_KIND:
        builder.targetMappingKind(bytes[offset] & 0xFF);
        return;
      case FIELD_APP_VERSION:
        builder.appVersion(decodeUtf8(bytes, offset, length));
        return;
      case FIELD_DATA_FORMAT:
        builder.dataFormat(decodeUtf8(bytes, offset, length));
        return;
      case FIELD_SENSITIVE_POLICY:
        builder.sensitivePolicy(decodeUtf8(bytes, offset, length));
        return;
      case FIELD_PLUGIN_COUNT:
        builder.pluginCount(buffer.getInt(offset));
        return;
      case FIELD_PLAN_DIGEST:
        builder.planDigest(Arrays.copyOfRange(bytes, offset, offset + length));
        return;
      default:
        throw new PlanViewException("PLAN_VIEW_STRUCTURE", "未知字段 id");
    }
  }

  private static String decodeUtf8(byte[] bytes, int offset, int length) {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      return decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString();
    } catch (CharacterCodingException error) {
      throw new PlanViewException("PLAN_VIEW_UTF8", "字段不是合法 UTF-8");
    }
  }
}
