package com.deepseekharness.app.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * R2 主机 JVM 差分探针：加载主机目标 `libdsha_rs.so`，用生产类
 * {@link PlanViewCodec}/{@link PlanViewValidator} 与 Rust `verify_plan` 对拍。
 *
 * <p>由 `tools/rust/host-jvm-differential.py` 以 {@code -Xcheck:jni} 运行，参数为
 * `<libdsha_rs.so> <plan-fixtures.txt>`（corpus 由 `tools/rust/make-fixtures.py`
 * 从 `tools/rust/fixtures.json` 确定性生成）。只证明“同一源码的主机实现与 JVM 一致”，
 * 不等于 Android `.so` 已加载（后者由 R3 签名 `.dev` 包与 APK 静态门禁覆盖）。
 *
 * <p>输出：每个检查一行 JSON，最后一行为汇总。
 */
public final class PlanViewHostProbe {
  private static final int INVALID_ARGUMENT = -1;
  private static final int OUTPUT_BOUND = -3;

  private static int failures = 0;
  private static int checks = 0;
  private static int nativeCalls = 0;
  private static int corpusCases = 0;

  private PlanViewHostProbe() {}

  private static void check(String name, boolean value, String detail) {
    checks++;
    System.out.println(
        "{\"check\":\""
            + name
            + "\",\"status\":\""
            + (value ? "PASS" : "FAIL")
            + "\""
            + (detail == null || detail.isEmpty() ? "" : ",\"detail\":\"" + detail + "\"")
            + "}");
    if (!value) {
      failures++;
    }
  }

  private static final class Native {
    final int code;
    final int[] reasons;

    Native(int code, int[] reasons) {
      this.code = code;
      this.reasons = reasons;
    }

    boolean valid() {
      return code == 0;
    }
  }

  private static int readIntLe(ByteBuffer buffer, int offset) {
    return (buffer.get(offset) & 0xFF)
        | ((buffer.get(offset + 1) & 0xFF) << 8)
        | ((buffer.get(offset + 2) & 0xFF) << 16)
        | ((buffer.get(offset + 3) & 0xFF) << 24);
  }

  private static Native verifyRust(byte[] view, int maxReasons) {
    ByteBuffer viewBuffer = ByteBuffer.allocateDirect(Math.max(view.length, 1));
    viewBuffer.put(view);
    ByteBuffer reasonsBuffer = ByteBuffer.allocateDirect(4 + 4 * Math.max(maxReasons, 0));
    int code = RustNative.verifyPlan(viewBuffer, view.length, reasonsBuffer, maxReasons);
    nativeCalls++;
    int[] reasons = new int[0];
    if (code == 0 || code == 1) {
      int count = readIntLe(reasonsBuffer, 0);
      reasons = new int[count];
      for (int index = 0; index < count; index++) {
        reasons[index] = readIntLe(reasonsBuffer, 4 + index * 4);
      }
    }
    return new Native(code, reasons);
  }

  private static int readU16le(byte[] view, int offset) {
    return (view[offset] & 0xFF) | ((view[offset + 1] & 0xFF) << 8);
  }

  private static int readU32le(byte[] view, int offset) {
    return (view[offset] & 0xFF)
        | ((view[offset + 1] & 0xFF) << 8)
        | ((view[offset + 2] & 0xFF) << 16)
        | ((view[offset + 3] & 0xFF) << 24);
  }

  private static void writeU16le(byte[] view, int offset, int value) {
    view[offset] = (byte) value;
    view[offset + 1] = (byte) (value >>> 8);
  }

  private static void writeU32le(byte[] view, int offset, long value) {
    view[offset] = (byte) value;
    view[offset + 1] = (byte) (value >>> 8);
    view[offset + 2] = (byte) (value >>> 16);
    view[offset + 3] = (byte) (value >>> 24);
  }

  private static void writeU64le(byte[] view, int offset, long value) {
    for (int index = 0; index < 8; index++) {
      view[offset + index] = (byte) (value >>> (index * 8));
    }
  }

  private static int fieldValueOffset(byte[] view, int id) {
    int offset = PlanViewCodec.HEADER_BYTES;
    while (offset + 8 <= view.length) {
      int fieldId = readU16le(view, offset);
      int length = readU32le(view, offset + 4);
      if (fieldId == id) {
        return offset + 8;
      }
      offset += 8 + length;
    }
    return -1;
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      out.append(Character.forDigit((value >> 4) & 0x0F, 16));
      out.append(Character.forDigit(value & 0x0F, 16));
    }
    return out.toString();
  }

  private static byte[] unhex(String text) {
    if (text.length() % 2 != 0) {
      return new byte[0];
    }
    byte[] bytes = new byte[text.length() / 2];
    for (int index = 0; index < bytes.length; index++) {
      bytes[index] =
          (byte) Integer.parseInt(text.substring(index * 2, index * 2 + 2), 16);
    }
    return bytes;
  }

  private static boolean contains(int[] reasons, int code) {
    for (int reason : reasons) {
      if (reason == code) {
        return true;
      }
    }
    return false;
  }

  private static int[] parseExpected(String text) {
    if (text.isEmpty()) {
      return new int[0];
    }
    String[] parts = text.split(",");
    int[] reasons = new int[parts.length];
    for (int index = 0; index < parts.length; index++) {
      reasons[index] = Integer.parseInt(parts[index].trim());
    }
    return reasons;
  }

  private static boolean sameReasons(int[] first, int[] second) {
    return Arrays.equals(first, second);
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("用法: PlanViewHostProbe <libdsha_rs.so 绝对路径> <plan-fixtures.txt>");
      System.exit(2);
    }
    if (!RustNative.loadFromPath(args[0])) {
      check("planLoadLibrary", false, "status=" + RustNative.statusCode());
      finish();
      return;
    }
    check("planLoadLibrary", RustNative.isAvailable(), "status=" + RustNative.statusCode());
    check(
        "planAbiVersion",
        RustNative.abiVersion() == RustNative.ABI_VERSION,
        "abi=" + RustNative.abiVersion());

    RustBackends.setEnabled(false);
    RustBackends.Selection off = RustBackends.planSelection();
    check(
        "planSelectionDefaultOff",
        !off.useRust() && "SWITCH_OFF".equals(off.reasonCode()),
        "reason=" + off.reasonCode());
    RustBackends.setEnabled(true);
    RustBackends.Selection on = RustBackends.planSelection();
    check(
        "planSelectionOnUsesRust",
        on.useRust() && RustBackends.SelectionReason.RUST_ENABLED == on.reason(),
        "reason=" + on.reasonCode());
    RustBackends.setEnabled(false);
    check(
        "planOneKeyRollback",
        !RustBackends.planSelection().useRust(),
        "reason=" + RustBackends.planSelection().reasonCode());

    byte[] canonical = PlanViewCodec.encode(PlanViewCodec.View.builder().build());
    List<String> lines = Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8);
    String corpusCanonical = null;
    for (String line : lines) {
      String[] parts = line.split("\\|", -1);
      if (parts.length == 3 && "valid-full-view".equals(parts[0])) {
        corpusCanonical = parts[2];
      }
    }
    check(
        "planCanonicalBytesAgree",
        corpusCanonical != null && corpusCanonical.equals(hex(canonical)),
        "javaBytes=" + canonical.length);

    PlanViewValidator.Result canonicalJvm = PlanViewValidator.validate(canonical);
    Native canonicalRust = verifyRust(canonical, 8);
    check(
        "planValidCanonical",
        canonicalJvm.valid() && canonicalRust.valid() && canonicalRust.reasons.length == 0,
        "jvmReasons=" + canonicalJvm.reasons().length + " rustCode=" + canonicalRust.code);

    int mismatches = 0;
    String firstMismatch = "";
    for (String line : lines) {
      if (line.isEmpty()) {
        continue;
      }
      String[] parts = line.split("\\|", -1);
      if (parts.length != 3) {
        check("planFixtureCorpusShape", false, "line 不是三段");
        finish();
        return;
      }
      corpusCases++;
      byte[] view = unhex(parts[2]);
      PlanViewValidator.Result jvmResult = PlanViewValidator.validate(view);
      Native rustResult = verifyRust(view, 8);
      boolean ok =
          jvmResult.valid() == rustResult.valid()
              && sameReasons(jvmResult.reasons(), rustResult.reasons);
      if (ok && "VALID".equals(parts[1])) {
        ok = jvmResult.valid();
      } else if (ok && !"JVM_RUST".equals(parts[1])) {
        int[] expected = parseExpected(parts[1]);
        ok = sameReasons(jvmResult.reasons(), expected);
      }
      if (!ok) {
        mismatches++;
        if (firstMismatch.isEmpty()) {
          firstMismatch =
              parts[0]
                  + " jvm="
                  + Arrays.toString(jvmResult.reasons())
                  + " rust="
                  + Arrays.toString(rustResult.reasons);
        }
      }
    }
    check(
        "planFixtureCorpus",
        corpusCases > 0 && mismatches == 0,
        "cases=" + corpusCases + " mismatches=" + mismatches + " first=" + firstMismatch);

    // 多原因集合：policy 2 + rootCount 0 + 已过期（T29）。
    byte[] multi = canonical.clone();
    writeU16le(multi, 6, 2);
    int rootValue = fieldValueOffset(multi, PlanViewCodec.FIELD_ROOT_COUNT);
    writeU32le(multi, rootValue, 0);
    writeU32le(multi, 16, PlanViewCodec.HEADER_FLAG_HAS_EXPIRY);
    writeU64le(multi, 20, 10);
    writeU64le(multi, 28, 20);
    writeU64le(multi, 36, 20);
    PlanViewValidator.Result multiJvm = PlanViewValidator.validate(multi);
    Native multiRust = verifyRust(multi, 8);
    int[] expectedMulti = {
      PlanViewValidator.REASON_FIELD_RANGE,
      PlanViewValidator.REASON_POLICY_VERSION,
      PlanViewValidator.REASON_EXPIRED
    };
    check(
        "planReasonSetOrderDedup",
        sameReasons(multiJvm.reasons(), expectedMulti)
            && sameReasons(multiRust.reasons, expectedMulti)
            && multiRust.code == 1,
        "jvm=" + Arrays.toString(multiJvm.reasons()));

    // 缓冲契约：maxReasons 不足 → OUTPUT_BOUND 且一个字节都不写。
    ByteBuffer multiView = ByteBuffer.allocateDirect(multi.length);
    multiView.put(multi);
    ByteBuffer reasonsBuffer = ByteBuffer.allocateDirect(4 + 4 * 8);
    for (int index = 0; index < reasonsBuffer.capacity(); index++) {
      reasonsBuffer.put(index, (byte) 0x5A);
    }
    int boundCode = RustNative.verifyPlan(multiView, multi.length, reasonsBuffer, 1);
    nativeCalls++;
    boolean untouched = true;
    for (int index = 0; index < reasonsBuffer.capacity(); index++) {
      untouched &= (reasonsBuffer.get(index) & 0xFF) == 0x5A;
    }
    ByteBuffer zeroAllowed = ByteBuffer.allocateDirect(4);
    zeroAllowed.putInt(0, 0x5A5A5A5A);
    int zeroCode = RustNative.verifyPlan(multiView, multi.length, zeroAllowed, 0);
    nativeCalls++;
    check(
        "planOutputBoundNoPartialWrite",
        boundCode == OUTPUT_BOUND
            && untouched
            && zeroCode == OUTPUT_BOUND
            && zeroAllowed.getInt(0) == 0x5A5A5A5A,
        "bound=" + boundCode + " zero=" + zeroCode);

    ByteBuffer canonicalView = ByteBuffer.allocateDirect(canonical.length);
    canonicalView.put(canonical);
    ByteBuffer heapView = ByteBuffer.allocate(canonical.length);
    heapView.put(canonical);
    ByteBuffer okReasons = ByteBuffer.allocateDirect(4 + 4 * 8);
    ByteBuffer smallReasons = ByteBuffer.allocateDirect(4);
    boolean contract =
        RustNative.verifyPlan(null, 0, okReasons, 8) == INVALID_ARGUMENT
            && RustNative.verifyPlan(canonicalView, -1, okReasons, 8) == INVALID_ARGUMENT
            && RustNative.verifyPlan(canonicalView, canonical.length, null, 8)
                == INVALID_ARGUMENT
            && RustNative.verifyPlan(canonicalView, canonical.length, okReasons, -1)
                == INVALID_ARGUMENT
            && RustNative.verifyPlan(heapView, canonical.length, okReasons, 8)
                == INVALID_ARGUMENT
            && RustNative.verifyPlan(
                    canonicalView, canonical.length, smallReasons, 8)
                == INVALID_ARGUMENT
            && RustNative.verifyPlan(canonicalView, canonical.length + 1, okReasons, 8)
                == INVALID_ARGUMENT;
    nativeCalls += 7;
    check("planJniBufferContract", contract, "7 个契约错误码");

    // 视图上限：2 MiB+1 只报 PLAN_LIMIT；恰好 2 MiB 不报 LIMIT。
    byte[] oversize = new byte[PlanViewCodec.MAX_VIEW_BYTES + 1];
    Native oversizeRust = verifyRust(oversize, 8);
    byte[] boundary = new byte[PlanViewCodec.MAX_VIEW_BYTES];
    Native boundaryRust = verifyRust(boundary, 8);
    PlanViewValidator.Result boundaryJvm = PlanViewValidator.validate(boundary);
    check(
        "planViewLimit",
        oversizeRust.code == 1
            && sameReasons(oversizeRust.reasons, new int[] {PlanViewValidator.REASON_LIMIT})
            && !contains(boundaryRust.reasons, PlanViewValidator.REASON_LIMIT)
            && sameReasons(boundaryJvm.reasons(), boundaryRust.reasons),
        "oversize=" + oversizeRust.code + " boundaryReasons=" + boundaryRust.reasons.length);

    // 幂等 + 输入不变：同一字节两次调用完全一致。
    byte[] before = canonical.clone();
    Native first = verifyRust(canonical, 8);
    Native second = verifyRust(canonical, 8);
    check(
        "planDeterministicRepeats",
        first.code == second.code
            && sameReasons(first.reasons, second.reasons)
            && Arrays.equals(before, canonical),
        "code=" + first.code);

    // 负向自检（评审 P2-5）：差分比较器必须能检出 mismatch，而不是恒真。
    int rootValueOffset = fieldValueOffset(canonical, PlanViewCodec.FIELD_ROOT_COUNT);
    byte[] root65 = canonical.clone();
    writeU32le(root65, rootValueOffset, 65);
    PlanViewValidator.Result root65Jvm = PlanViewValidator.validate(root65);
    Native root65Rust = verifyRust(root65, 8);
    boolean realAgreement =
        root65Jvm.reasons().length == 1
            && root65Jvm.reasons()[0] == PlanViewValidator.REASON_FIELD_RANGE
            && sameReasons(root65Jvm.reasons(), root65Rust.reasons)
            && root65Rust.code == 1;
    boolean detectsFabricatedMismatch =
        !sameReasons(root65Rust.reasons, new int[] {PlanViewValidator.REASON_FIELD_RANGE,
            PlanViewValidator.REASON_EXPIRED});
    boolean detectsDifferentViews = !sameReasons(canonicalJvm.reasons(), root65Rust.reasons);
    check(
        "planNegativeControlDetectsMismatch",
        realAgreement && detectsFabricatedMismatch && detectsDifferentViews,
        "fabricated=" + detectsFabricatedMismatch + " distinctViews=" + detectsDifferentViews);

    finish();
  }

  private static void finish() {
    RustBackends.setEnabled(false);
    System.out.println(
        "{\"status\":\""
            + (failures == 0 ? "PASS" : "FAIL")
            + "\",\"failures\":"
            + failures
            + ",\"checks\":"
            + checks
            + ",\"nativeCalls\":"
            + nativeCalls
            + ",\"corpusCases\":"
            + corpusCases
            + ",\"javaVersion\":\""
            + System.getProperty("java.version")
            + "\"}");
    if (failures != 0) {
      System.exit(1);
    }
  }
}
