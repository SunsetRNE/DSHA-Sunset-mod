package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import org.junit.Test;

/**
 * {@link PlanViewCodec} 的字节层测试（A3 §3.2–§3.5、§4.2）。
 *
 * <p>重点：编码是确定性字节并与 Rust 单测 pin 的规范视图逐字节相等；解码只判结构/UTF-8，
 * 不代替 {@link PlanViewValidator} 的语义裁决；{@code withZeroPlanDigest} 只清零字段 15。
 */
public class PlanViewCodecTest {
  /** 规范视图 pin：与 Rust `plan.rs` 的 `CANONICAL_VIEW_HEX` 完全一致（跨实现字节冻结）。 */
  private static final String CANONICAL_HEX =
      "4453485001000100630100000f00000000000000f4c12cc8990100000000000000000000f4c12cc8990100000000000001000200040000000500000002000100010000000103000100010000000104000200040000000100000005000200040000002a00000006000300080000000004000000000000070003000800000000c02cc8990100000800040020000000000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0900020004000000030000000a00010001000000010b00050026000000302e322e312d72632e352d32303236313131332e313030302d647368302e322e322d72632e310c0005000b000000554e494e535045435445440d00050023000000505249564154455f4c4f43414c5f4e41544956455f53544154455f494e5f504c4143450e000200040000000c0000000f00040020000000a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf";

  private static byte[] canonical() {
    return PlanViewCodec.encode(PlanViewCodec.View.builder().build());
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      out.append(Character.forDigit((value >> 4) & 0x0F, 16));
      out.append(Character.forDigit(value & 0x0F, 16));
    }
    return out.toString();
  }

  private static int fieldValueOffset(byte[] view, int id) {
    int offset = PlanViewCodec.HEADER_BYTES;
    while (offset + 8 <= view.length) {
      int fieldId = (view[offset] & 0xFF) | ((view[offset + 1] & 0xFF) << 8);
      int length =
          (view[offset + 4] & 0xFF)
              | ((view[offset + 5] & 0xFF) << 8)
              | ((view[offset + 6] & 0xFF) << 16)
              | ((view[offset + 7] & 0xFF) << 24);
      if (fieldId == id) {
        return offset + 8;
      }
      offset += 8 + length;
    }
    return -1;
  }

  private static byte[] patchU16(byte[] view, int offset, int value) {
    byte[] copy = view.clone();
    ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, (short) value);
    return copy;
  }

  private static byte[] patchU32(byte[] view, int offset, int value) {
    byte[] copy = view.clone();
    ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
    return copy;
  }

  @Test
  public void canonicalBytesArePinnedAndMatchTheRustFixture() {
    byte[] view = canonical();
    assertEquals(CANONICAL_HEX, hex(view));
    // 头部：48 字节，字段区恰好结束。
    assertEquals(355, view.length);
    ByteBuffer buffer = ByteBuffer.wrap(view).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(1, buffer.getShort(4));
    assertEquals(1, buffer.getShort(6));
    assertEquals(view.length, buffer.getInt(8));
    assertEquals(15, buffer.getInt(12));
    assertEquals(0, buffer.getInt(16));
    assertEquals(0, buffer.getInt(44));
    assertEquals(0, PlanViewValidator.validate(view).reasons().length);
  }

  @Test
  public void decodeRoundTripPreservesEveryField() throws Exception {
    byte[] sourceDigest = new byte[32];
    byte[] planDigest = new byte[32];
    for (int index = 0; index < 32; index++) {
      sourceDigest[index] = (byte) (0xC0 + index);
      planDigest[index] = (byte) (0x10 + index);
    }
    PlanViewCodec.View original =
        PlanViewCodec.View.builder()
            .policyVersion(1)
            .generatedAtMs(1_700_000_000_123L)
            .expiresAtMs(1_700_000_060_000L)
            .nowMs(1_700_000_001_000L)
            .hasExpiry(true)
            .formatVersion(5)
            .operation(3)
            .integrity(4)
            .rootCount(64)
            .entryCount(100_000)
            .byteCount(PlanViewCodec.MAX_BYTE_COUNT)
            .createdAtMs(-1L)
            .sourceDigest(sourceDigest)
            .planDigest(planDigest)
            .selectedMask(31)
            .targetMappingKind(4)
            .pluginCount(4_096)
            .appVersion("a".repeat(256))
            .dataFormat("LEGACY_3")
            .sensitivePolicy("PRIVATE_LOCAL_NATIVE_STATE_IN_PLACE")
            .build();
    PlanViewCodec.View decoded = PlanViewCodec.decode(PlanViewCodec.encode(original));
    assertEquals(original.policyVersion, decoded.policyVersion);
    assertEquals(original.generatedAtMs, decoded.generatedAtMs);
    assertEquals(original.expiresAtMs, decoded.expiresAtMs);
    assertEquals(original.nowMs, decoded.nowMs);
    assertEquals(original.hasExpiry, decoded.hasExpiry);
    assertEquals(original.formatVersion, decoded.formatVersion);
    assertEquals(original.operation, decoded.operation);
    assertEquals(original.integrity, decoded.integrity);
    assertEquals(original.rootCount, decoded.rootCount);
    assertEquals(original.entryCount, decoded.entryCount);
    assertEquals(original.byteCount, decoded.byteCount);
    assertEquals(original.createdAtMs, decoded.createdAtMs);
    assertArrayEquals(original.sourceDigest, decoded.sourceDigest);
    assertArrayEquals(original.planDigest, decoded.planDigest);
    assertEquals(original.selectedMask, decoded.selectedMask);
    assertEquals(original.targetMappingKind, decoded.targetMappingKind);
    assertEquals(original.pluginCount, decoded.pluginCount);
    assertEquals(original.appVersion, decoded.appVersion);
    assertEquals(original.dataFormat, decoded.dataFormat);
    assertEquals(original.sensitivePolicy, decoded.sensitivePolicy);
    // 编码确定性：同一输入两次输出字节完全相等。
    assertArrayEquals(PlanViewCodec.encode(original), PlanViewCodec.encode(original));
  }

  @Test
  public void encodeRejectsUnrepresentableInputsWithTypedCodes() {
    assertCodecCode("PLAN_VIEW_NULL", () -> PlanViewCodec.encode(null));
    assertCodecCode(
        "PLAN_VIEW_RANGE",
        () -> PlanViewCodec.encode(PlanViewCodec.View.builder().operation(256).build()));
    assertCodecCode(
        "PLAN_VIEW_RANGE",
        () -> PlanViewCodec.encode(PlanViewCodec.View.builder().operation(-1).build()));
    assertCodecCode(
        "PLAN_VIEW_RANGE",
        () -> PlanViewCodec.encode(PlanViewCodec.View.builder().policyVersion(0x1_0000).build()));
    assertCodecCode(
        "PLAN_VIEW_RANGE",
        () -> PlanViewCodec.encode(PlanViewCodec.View.builder().selectedMask(-1).build()));
    assertCodecCode(
        "PLAN_VIEW_DIGEST",
        () ->
            PlanViewCodec.encode(PlanViewCodec.View.builder().sourceDigest(new byte[31]).build()));
    assertCodecCode(
        "PLAN_VIEW_LIMIT",
        () ->
            PlanViewCodec.encode(
                PlanViewCodec.View.builder().appVersion("a".repeat(4_097)).build()));
  }

  private static void assertCodecCode(String expected, Runnable action) {
    try {
      action.run();
      fail("期望 PlanViewException(" + expected + ")");
    } catch (PlanViewCodec.PlanViewException error) {
      assertEquals(expected, error.code());
    }
  }

  @Test
  public void decodeRejectsStructuralBreaks() {
    byte[] view = canonical();

    byte[] badMagic = view.clone();
    badMagic[0] = 'X';
    assertDecodeFails(badMagic, "PLAN_VIEW_STRUCTURE");

    byte[] badVersion = patchU16(view, 4, 2);
    assertDecodeFails(badVersion, "PLAN_VIEW_STRUCTURE");

    byte[] badTotal = patchU32(view, 8, view.length + 1);
    assertDecodeFails(badTotal, "PLAN_VIEW_STRUCTURE");

    byte[] badCount = patchU32(view, 12, 14);
    assertDecodeFails(badCount, "PLAN_VIEW_STRUCTURE");

    byte[] badFlags = patchU32(view, 16, 2);
    assertDecodeFails(badFlags, "PLAN_VIEW_STRUCTURE");

    byte[] badReserved = patchU32(view, 44, 1);
    assertDecodeFails(badReserved, "PLAN_VIEW_STRUCTURE");

    byte[] truncated = Arrays.copyOf(view, view.length - 1);
    assertDecodeFails(truncated, "PLAN_VIEW_STRUCTURE");

    byte[] trailing = Arrays.copyOf(view, view.length + 1);
    assertDecodeFails(trailing, "PLAN_VIEW_STRUCTURE");

    byte[] badFieldType = view.clone();
    badFieldType[PlanViewCodec.HEADER_BYTES + 2] = 9;
    assertDecodeFails(badFieldType, "PLAN_VIEW_STRUCTURE");

    byte[] badFieldFlags = view.clone();
    badFieldFlags[PlanViewCodec.HEADER_BYTES + 3] = 1;
    assertDecodeFails(badFieldFlags, "PLAN_VIEW_STRUCTURE");

    int appVersionValue = fieldValueOffset(view, PlanViewCodec.FIELD_APP_VERSION);
    byte[] badUtf8 = view.clone();
    badUtf8[appVersionValue] = (byte) 0xFF;
    badUtf8[appVersionValue + 1] = (byte) 0xFE;
    assertDecodeFails(badUtf8, "PLAN_VIEW_UTF8");

    assertDecodeFails(null, "PLAN_VIEW_STRUCTURE");
    assertDecodeFails(new byte[PlanViewCodec.MAX_VIEW_BYTES + 1], "PLAN_VIEW_LIMIT");
    assertDecodeFails(new byte[47], "PLAN_VIEW_STRUCTURE");
  }

  private static void assertDecodeFails(byte[] view, String expected) {
    try {
      PlanViewCodec.decode(view);
      fail("期望 PlanViewException(" + expected + ")");
    } catch (PlanViewCodec.PlanViewException error) {
      assertEquals(expected, error.code());
    }
  }

  @Test
  public void decodeKeepsPolicyVersionForTheValidatorToJudge() {
    // 策略版本是语义裁决，不是结构错误：decode 接受，validator 给 PLAN_POLICY_VERSION。
    byte[] view = patchU16(canonical(), 6, 2);
    PlanViewCodec.View decoded = PlanViewCodec.decode(view);
    assertEquals(2, decoded.policyVersion);
    PlanViewValidator.Result result = PlanViewValidator.validate(view);
    assertArrayEquals(new int[] {PlanViewValidator.REASON_POLICY_VERSION}, result.reasons());
  }

  @Test
  public void withZeroPlanDigestOnlyZeroesField15() throws NoSuchAlgorithmException {
    byte[] view = canonical();
    byte[] snapshot = view.clone();
    byte[] zeroed = PlanViewCodec.withZeroPlanDigest(view);
    assertArrayEquals("输入不能被修改", snapshot, view);
    int planValue = fieldValueOffset(view, PlanViewCodec.FIELD_PLAN_DIGEST);
    assertEquals(32, view.length - planValue);
    for (int index = 0; index < view.length; index++) {
      int expected = index >= planValue && index < planValue + 32 ? 0 : view[index];
      assertEquals("byte " + index, expected, zeroed[index]);
    }
    // §3.5 口径：置零后整份视图的 SHA-256 与原始视图不同，且可重复。
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    String zeroedDigest = hex(digest.digest(zeroed));
    MessageDigest again = MessageDigest.getInstance("SHA-256");
    assertEquals(zeroedDigest, hex(again.digest(PlanViewCodec.withZeroPlanDigest(view))));
    assertNotEquals(hex(digest.digest(view)), zeroedDigest);
    // 字段 15 内容 v1 不校验：清零后仍是 VALID。
    assertTrue(PlanViewValidator.validate(zeroed).valid());
  }

  @Test
  public void withZeroPlanDigestRejectsMissingPlanDigest() {
    byte[] view = canonical();
    byte[] missing = Arrays.copyOf(view, view.length - 40);
    try {
      PlanViewCodec.withZeroPlanDigest(missing);
      fail("期望 PlanViewException");
    } catch (PlanViewCodec.PlanViewException error) {
      assertEquals("PLAN_VIEW_STRUCTURE", error.code());
    }
  }
}
