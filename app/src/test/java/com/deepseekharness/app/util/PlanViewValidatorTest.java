package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * {@link PlanViewValidator} 的规则矩阵（A3 任务基线 §5.2 T1–T32）。
 *
 * <p>每条规则至少一正一反；期望值按 §3.2–§3.7 冻结。这里同时定义 {@link #allCases()}，
 * 供 {@link PlanViewDifferentialTest} 与 Rust 第二实现逐条对拍（比较原因集合而不是首错）。
 */
public class PlanViewValidatorTest {
  /** 一份合法夹具：与 Rust 单测/差分夹具使用同一组字段值。 */
  static byte[] canonical() {
    return PlanViewCodec.encode(PlanViewCodec.View.builder().build());
  }

  /** 一个用例：字节视图 + 期望的 VALID/原因集合。 */
  static final class Case {
    final String name;
    final byte[] view;
    final boolean expectedValid;
    final int[] expectedReasons;

    Case(String name, byte[] view, boolean expectedValid, int... expectedReasons) {
      this.name = name;
      this.view = view;
      this.expectedValid = expectedValid;
      this.expectedReasons = expectedReasons;
    }
  }

  /** 测试用原始字节构建器（头部/字段区可任意破坏；不参与生产代码）。 */
  static final class Raw {
    byte[] magic = {'D', 'S', 'H', 'P'};
    int viewVersion = PlanViewCodec.VIEW_VERSION;
    int policyVersion = PlanViewCodec.POLICY_VERSION;
    int totalLengthOverride = -1;
    int fieldCount = PlanViewCodec.FIELD_COUNT;
    long flags = 0;
    long generatedAtMs = 1_760_000_000_500L;
    long expiresAtMs = 0;
    long nowMs = 1_760_000_000_500L;
    int reserved = 0;
    List<byte[]> fields = defaultFields();

    byte[] build() {
      int total = PlanViewCodec.HEADER_BYTES;
      for (byte[] field : fields) {
        total += field.length;
      }
      int declared = totalLengthOverride >= 0 ? totalLengthOverride : total;
      ByteBuffer buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
      buffer.put(magic);
      buffer.putShort((short) viewVersion);
      buffer.putShort((short) policyVersion);
      buffer.putInt(declared);
      buffer.putInt(fieldCount);
      buffer.putInt((int) flags);
      buffer.putLong(generatedAtMs);
      buffer.putLong(expiresAtMs);
      buffer.putLong(nowMs);
      buffer.putInt(reserved);
      for (byte[] field : fields) {
        buffer.put(field);
      }
      return buffer.array();
    }
  }

  static byte[] field(int id, int typeCode, int fieldFlags, byte[] value) {
    ByteBuffer buffer = ByteBuffer.allocate(8 + value.length).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putShort((short) id);
    buffer.put((byte) typeCode);
    buffer.put((byte) fieldFlags);
    buffer.putInt(value.length);
    buffer.put(value);
    return buffer.array();
  }

  static byte[] u8(int value) {
    return new byte[] {(byte) value};
  }

  static byte[] u32(long value) {
    ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt((int) value);
    return buffer.array();
  }

  static byte[] u64(long value) {
    ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putLong(value);
    return buffer.array();
  }

  static byte[] text(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  static byte[] patterned(int length) {
    byte[] bytes = new byte[length];
    Arrays.fill(bytes, (byte) 'x');
    return bytes;
  }

  static List<byte[]> defaultFields() {
    List<byte[]> fields = new ArrayList<>();
    fields.add(field(1, PlanViewCodec.TYPE_U32, 0, u32(5)));
    fields.add(field(2, PlanViewCodec.TYPE_U8, 0, u8(PlanViewCodec.OPERATION_EXPORT)));
    fields.add(field(3, PlanViewCodec.TYPE_U8, 0, u8(PlanViewCodec.INTEGRITY_QUIESCENT)));
    fields.add(field(4, PlanViewCodec.TYPE_U32, 0, u32(1)));
    fields.add(field(5, PlanViewCodec.TYPE_U32, 0, u32(42)));
    fields.add(field(6, PlanViewCodec.TYPE_U64, 0, u64(1_024)));
    fields.add(field(7, PlanViewCodec.TYPE_U64, 0, u64(1_760_000_000_000L)));
    fields.add(field(8, PlanViewCodec.TYPE_BYTES32, 0, patterned(32)));
    fields.add(field(9, PlanViewCodec.TYPE_U32, 0, u32(3)));
    fields.add(field(10, PlanViewCodec.TYPE_U8, 0, u8(1)));
    fields.add(
        field(11, PlanViewCodec.TYPE_UTF8, 0, text("0.2.1-rc.5-20261113.1000-dsh0.2.2-rc.1")));
    fields.add(field(12, PlanViewCodec.TYPE_UTF8, 0, text("UNINSPECTED")));
    fields.add(field(13, PlanViewCodec.TYPE_UTF8, 0, text("PRIVATE_LOCAL_NATIVE_STATE_IN_PLACE")));
    fields.add(field(14, PlanViewCodec.TYPE_U32, 0, u32(12)));
    fields.add(field(15, PlanViewCodec.TYPE_BYTES32, 0, patterned(32)));
    return fields;
  }

  /** 只替换单个字段（id 即下标 + 1）的用例。 */
  static byte[] withField(int id, int typeCode, byte[] value) {
    Raw raw = new Raw();
    raw.fields.set(id - 1, field(id, typeCode, 0, value));
    return raw.build();
  }

  static byte[] withUtf8(int id, String value) {
    return withField(id, PlanViewCodec.TYPE_UTF8, text(value));
  }

  static byte[] append(byte[] view, byte[] suffix) {
    byte[] extended = Arrays.copyOf(view, view.length + suffix.length);
    System.arraycopy(suffix, 0, extended, view.length, suffix.length);
    return extended;
  }

  /** T1–T32（除 T30/T32 需要在原生缓冲/接线层验证）的全部正反例。 */
  static List<Case> allCases() {
    List<Case> cases = new ArrayList<>();
    cases.add(new Case("T0-valid-canonical", canonical(), true));

    byte[] badMagic = canonical();
    badMagic[0] = 'X';
    cases.add(new Case("T1-magic", badMagic, false, 20));

    byte[] viewVersion = canonical();
    viewVersion[4] = 2;
    cases.add(new Case("T2-view-version", viewVersion, false, 20));

    byte[] policyVersion = canonical();
    policyVersion[6] = 2;
    cases.add(new Case("T3-policy-version", policyVersion, false, 22));

    byte[] totalLength = canonical();
    totalLength[8]++;
    cases.add(new Case("T4-total-length", totalLength, false, 20));

    cases.add(new Case("T5-view-limit", new byte[PlanViewCodec.MAX_VIEW_BYTES + 1], false, 24));
    // 恰好 2 MiB 不触发 LIMIT：全 0 内容照常报 policyVersion/结构/未知字段。
    cases.add(
        new Case(
            "T5-view-limit-positive", new byte[PlanViewCodec.MAX_VIEW_BYTES], false, 20, 22, 25));

    Raw fieldCount14 = new Raw();
    fieldCount14.fieldCount = 14;
    cases.add(new Case("T6-field-count-14", fieldCount14.build(), false, 20));
    Raw fieldCount16 = new Raw();
    fieldCount16.fieldCount = 16;
    cases.add(new Case("T6-field-count-16", fieldCount16.build(), false, 20));
    Raw fieldCount65 = new Raw();
    fieldCount65.fieldCount = 65;
    cases.add(new Case("T6-field-count-65", fieldCount65.build(), false, 20, 24));

    Raw headerFlags = new Raw();
    headerFlags.flags = 2;
    cases.add(new Case("T7-header-flags", headerFlags.build(), false, 20));
    Raw reserved = new Raw();
    reserved.reserved = 1;
    cases.add(new Case("T7-reserved", reserved.build(), false, 20));

    Raw swapped = new Raw();
    List<byte[]> swap = swapped.fields;
    byte[] first = swap.get(0);
    swap.set(0, swap.get(1));
    swap.set(1, first);
    cases.add(new Case("T8-order", swapped.build(), false, 20));

    Raw duplicated = new Raw();
    duplicated.fields.add(2, duplicated.fields.get(1));
    cases.add(new Case("T8-duplicate", duplicated.build(), false, 20));

    Raw unknownField = new Raw();
    unknownField.fields.add(field(16, PlanViewCodec.TYPE_U32, 0, u32(7)));
    cases.add(new Case("T9-unknown-field", unknownField.build(), false, 20, 25));

    Raw fieldFlags = new Raw();
    fieldFlags.fields.set(1, field(2, PlanViewCodec.TYPE_U8, 1, u8(1)));
    cases.add(new Case("T10-field-flags", fieldFlags.build(), false, 20));

    cases.add(
        new Case("T11-u8-length-2", withField(2, PlanViewCodec.TYPE_U8, new byte[2]), false, 20));
    cases.add(new Case("T11-unknown-type", withField(4, 9, u32(1)), false, 20));
    cases.add(new Case("T11-wrong-type", withField(4, PlanViewCodec.TYPE_U8, u8(1)), false, 20));

    cases.add(new Case("T12-trailing-byte", append(canonical(), new byte[] {0}), false, 20));
    byte[] truncated = canonical();
    truncated = Arrays.copyOf(truncated, truncated.length - 1);
    cases.add(new Case("T12-truncated", truncated, false, 20));

    cases.add(
        new Case("T13-format-version", withField(1, PlanViewCodec.TYPE_U32, u32(4)), false, 21));
    cases.add(new Case("T14-operation-0", withField(2, PlanViewCodec.TYPE_U8, u8(0)), false, 21));
    cases.add(new Case("T14-operation-4", withField(2, PlanViewCodec.TYPE_U8, u8(4)), false, 21));
    cases.add(new Case("T14-operation-2", withField(2, PlanViewCodec.TYPE_U8, u8(2)), true));
    cases.add(new Case("T14-operation-3", withField(2, PlanViewCodec.TYPE_U8, u8(3)), true));
    cases.add(new Case("T15-integrity-0", withField(3, PlanViewCodec.TYPE_U8, u8(0)), false, 21));
    cases.add(new Case("T15-integrity-5", withField(3, PlanViewCodec.TYPE_U8, u8(5)), false, 21));
    cases.add(new Case("T15-integrity-2", withField(3, PlanViewCodec.TYPE_U8, u8(2)), true));
    cases.add(new Case("T15-integrity-4", withField(3, PlanViewCodec.TYPE_U8, u8(4)), true));
    cases.add(new Case("T16-root-0", withField(4, PlanViewCodec.TYPE_U32, u32(0)), false, 21));
    cases.add(new Case("T16-root-64", withField(4, PlanViewCodec.TYPE_U32, u32(64)), true));
    cases.add(new Case("T16-root-65", withField(4, PlanViewCodec.TYPE_U32, u32(65)), false, 21));
    cases.add(new Case("T17-entry-0", withField(5, PlanViewCodec.TYPE_U32, u32(0)), false, 21));
    cases.add(
        new Case("T17-entry-100000", withField(5, PlanViewCodec.TYPE_U32, u32(100_000)), true));
    cases.add(
        new Case(
            "T17-entry-100001", withField(5, PlanViewCodec.TYPE_U32, u32(100_001)), false, 21));
    cases.add(new Case("T18-byte-0", withField(6, PlanViewCodec.TYPE_U64, u64(0)), true));
    cases.add(
        new Case(
            "T18-byte-16g",
            withField(6, PlanViewCodec.TYPE_U64, u64(PlanViewCodec.MAX_BYTE_COUNT)),
            true));
    cases.add(
        new Case(
            "T18-byte-16g-plus-1",
            withField(6, PlanViewCodec.TYPE_U64, u64(PlanViewCodec.MAX_BYTE_COUNT + 1)),
            false,
            21));
    cases.add(new Case("T19-created-0", withField(7, PlanViewCodec.TYPE_U64, u64(0)), true));
    cases.add(new Case("T19-created-max", withField(7, PlanViewCodec.TYPE_U64, u64(-1L)), true));
    cases.add(
        new Case(
            "T20-source-31", withField(8, PlanViewCodec.TYPE_BYTES32, patterned(31)), false, 20));
    cases.add(
        new Case(
            "T20-source-33", withField(8, PlanViewCodec.TYPE_BYTES32, patterned(33)), false, 20));
    cases.add(new Case("T21-mask-0", withField(9, PlanViewCodec.TYPE_U32, u32(0)), false, 21));
    cases.add(new Case("T21-mask-31", withField(9, PlanViewCodec.TYPE_U32, u32(31)), true));
    cases.add(new Case("T21-mask-32", withField(9, PlanViewCodec.TYPE_U32, u32(32)), false, 21));
    cases.add(new Case("T22-mapping-0", withField(10, PlanViewCodec.TYPE_U8, u8(0)), false, 21));
    cases.add(new Case("T22-mapping-4", withField(10, PlanViewCodec.TYPE_U8, u8(4)), true));
    cases.add(new Case("T22-mapping-5", withField(10, PlanViewCodec.TYPE_U8, u8(5)), false, 21));

    cases.add(new Case("T23-app-44", withUtf8(11, "a".repeat(44)), true));
    cases.add(new Case("T23-app-256", withUtf8(11, "a".repeat(256)), true));
    cases.add(new Case("T23-app-257", withUtf8(11, "a".repeat(257)), false, 21));
    cases.add(new Case("T23-app-empty", withUtf8(11, ""), false, 20, 21));
    cases.add(new Case("T23-app-nul", withUtf8(11, "a\u0000b"), false, 21));
    cases.add(
        new Case(
            "T23-app-bad-utf8",
            withField(11, PlanViewCodec.TYPE_UTF8, new byte[] {(byte) 0xff, (byte) 0xfe}),
            false,
            20));

    for (String format : new String[] {"UNINSPECTED", "LEGACY_3", "LOCAL_ORIGINAL_LAYOUT"}) {
      cases.add(new Case("T24-data-format-" + format, withUtf8(12, format), true));
    }
    cases.add(new Case("T24-data-empty", withUtf8(12, ""), false, 20, 21));
    cases.add(new Case("T24-data-65", withUtf8(12, "a".repeat(65)), false, 21));

    for (String policy : PlanViewCodec.SENSITIVE_POLICIES) {
      cases.add(new Case("T25-policy-" + policy, withUtf8(13, policy), true));
    }
    cases.add(new Case("T25-policy-unknown", withUtf8(13, "PLAINTEXTISH"), false, 21));
    cases.add(new Case("T25-policy-empty", withUtf8(13, ""), false, 20, 21));

    cases.add(new Case("T26-plugin-0", withField(14, PlanViewCodec.TYPE_U32, u32(0)), true));
    cases.add(new Case("T26-plugin-4096", withField(14, PlanViewCodec.TYPE_U32, u32(4_096)), true));
    cases.add(
        new Case("T26-plugin-4097", withField(14, PlanViewCodec.TYPE_U32, u32(4_097)), false, 21));
    cases.add(
        new Case(
            "T27-plan-digest-31",
            withField(15, PlanViewCodec.TYPE_BYTES32, patterned(31)),
            false,
            20));
    cases.add(
        new Case(
            "T27-plan-digest-content",
            withField(15, PlanViewCodec.TYPE_BYTES32, new byte[32]),
            true));

    Raw expiry = new Raw();
    expiry.flags = PlanViewCodec.HEADER_FLAG_HAS_EXPIRY;
    expiry.generatedAtMs = 1_000;
    expiry.expiresAtMs = 2_000;
    expiry.nowMs = 1_999;
    cases.add(new Case("T28-expiry-before", expiry.build(), true));
    expiry.nowMs = 2_000;
    cases.add(new Case("T28-expiry-at", expiry.build(), false, 23));
    Raw degenerate = new Raw();
    degenerate.flags = PlanViewCodec.HEADER_FLAG_HAS_EXPIRY;
    degenerate.generatedAtMs = 5_000;
    degenerate.expiresAtMs = 5_000;
    degenerate.nowMs = 4_000;
    cases.add(new Case("T28-expiry-degenerate", degenerate.build(), false, 23));
    Raw orphanExpiry = new Raw();
    orphanExpiry.expiresAtMs = 1;
    orphanExpiry.nowMs = 1_000;
    cases.add(new Case("T28-expiry-orphan", orphanExpiry.build(), false, 20));

    Raw multi = new Raw();
    multi.policyVersion = 2;
    multi.fields.set(3, field(4, PlanViewCodec.TYPE_U32, 0, u32(0)));
    multi.flags = PlanViewCodec.HEADER_FLAG_HAS_EXPIRY;
    multi.generatedAtMs = 10;
    multi.expiresAtMs = 20;
    multi.nowMs = 20;
    cases.add(new Case("T29-multi-reason", multi.build(), false, 21, 22, 23));

    return cases;
  }

  static void assertCase(Case testCase) {
    PlanViewValidator.Result result = PlanViewValidator.validate(testCase.view);
    assertEquals(testCase.name + " valid", testCase.expectedValid, result.valid());
    assertEquals(
        testCase.name + " reasons",
        Arrays.toString(testCase.expectedReasons),
        Arrays.toString(result.reasons()));
    if (result.valid()) {
      assertEquals(PlanViewValidator.DIAGNOSTIC_VALID, result.diagnostic());
      assertEquals(PlanViewValidator.VALID, 0);
    } else {
      assertEquals(PlanViewValidator.DIAGNOSTIC_INVALID, result.diagnostic());
      assertEquals(PlanViewValidator.INVALID, 1);
    }
  }

  @Test
  public void everyMatrixCaseMatchesItsExpectedReasonSet() {
    List<Case> cases = allCases();
    assertTrue("用例矩阵不应为空", cases.size() >= 60);
    for (Case testCase : cases) {
      assertCase(testCase);
    }
  }

  @Test
  public void reasonCodesAndNamesAreFrozen() {
    assertEquals(20, PlanViewValidator.REASON_STRUCTURE);
    assertEquals(21, PlanViewValidator.REASON_FIELD_RANGE);
    assertEquals(22, PlanViewValidator.REASON_POLICY_VERSION);
    assertEquals(23, PlanViewValidator.REASON_EXPIRED);
    assertEquals(24, PlanViewValidator.REASON_LIMIT);
    assertEquals(25, PlanViewValidator.REASON_UNKNOWN_FIELD);
    assertEquals("PLAN_STRUCTURE", PlanViewValidator.reasonName(20));
    assertEquals("PLAN_UNKNOWN_FIELD", PlanViewValidator.reasonName(25));
    assertNull(PlanViewValidator.reasonName(19));
    assertNull(PlanViewValidator.reasonName(26));
  }

  @Test
  public void nullAndShortViewsFailClosedWithoutThrowing() {
    for (byte[] view : new byte[][] {null, new byte[0], new byte[47]}) {
      PlanViewValidator.Result result = PlanViewValidator.validate(view);
      assertFalse(result.valid());
      assertArrayEquals(new int[] {PlanViewValidator.REASON_STRUCTURE}, result.reasons());
    }
  }

  @Test
  public void validateIsPureAndDoesNotMutateTheInput() {
    byte[] view = canonical();
    byte[] snapshot = view.clone();
    PlanViewValidator.Result first = PlanViewValidator.validate(view);
    PlanViewValidator.Result second = PlanViewValidator.validate(view);
    assertArrayEquals(snapshot, view);
    assertEquals(first.valid(), second.valid());
    assertArrayEquals(first.reasons(), second.reasons());
    assertEquals(first.diagnostic(), second.diagnostic());
    // 返回的原因是副本：调用方修改它不能污染结果。
    int[] reasons = second.reasons();
    if (reasons.length > 0) {
      reasons[0] = -1;
      assertFalse(second.hasReason(-1));
    }
  }

  @Test
  public void embeddedNowMsIsTheOnlyClockSource() {
    // nowMs 只认视图内嵌值：同一计划用两个时刻分别重新编码，再各自校验。
    byte[] at1999 = PlanViewCodec.encode(expiryView(1_999));
    byte[] at2000 = PlanViewCodec.encode(expiryView(2_000));
    assertTrue(PlanViewValidator.validate(at1999).valid());
    PlanViewValidator.Result expired = PlanViewValidator.validate(at2000);
    assertFalse(expired.valid());
    assertArrayEquals(new int[] {PlanViewValidator.REASON_EXPIRED}, expired.reasons());
    // 不存在 byte[]+long 的公开重载：validate 只读视图内嵌时间，防止双口径回归。
    for (java.lang.reflect.Method method : PlanViewValidator.class.getMethods()) {
      if (method.getName().equals("validate")) {
        assertEquals("validate 只允许 (byte[])", 1, method.getParameterCount());
      }
    }
  }

  /**
   * 带有效期的最小合法视图（generated=1000、expires=2000、now=参数值）。
   *
   * <p>`planDigest` 按 §3.5 口径随 `nowMs` 重算：先把字段 15 置零编码、再对置零后的
   * 整份视图求 SHA-256——这正是“重新编码视图（含重算 planDigest）”的生产路径。
   */
  static PlanViewCodec.View expiryView(long nowMs) {
    PlanViewCodec.View unsigned =
        PlanViewCodec.View.builder()
            .hasExpiry(true)
            .generatedAtMs(1_000L)
            .expiresAtMs(2_000L)
            .nowMs(nowMs)
            .planDigest(new byte[32])
            .build();
    byte[] digest;
    try {
      digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(PlanViewCodec.withZeroPlanDigest(PlanViewCodec.encode(unsigned)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new AssertionError(error);
    }
    return PlanViewCodec.View.builder()
        .hasExpiry(true)
        .generatedAtMs(1_000L)
        .expiresAtMs(2_000L)
        .nowMs(nowMs)
        .planDigest(digest)
        .build();
  }
}
