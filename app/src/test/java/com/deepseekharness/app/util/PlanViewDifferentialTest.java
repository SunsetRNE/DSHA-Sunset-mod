package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * JVM {@link PlanViewValidator} 与 Rust `verify_plan` 的真实对拍（需要主机目标 .so）。
 *
 * <p>运行方式与 {@link RustDigestDifferentialTest} 相同：{@code -Pdsha.rust.hostLib=<repo>/app/build/rust-host/libdsha_rs.so}，
 * 可加 {@code -Pdsha.rust.required=true} 让缺少 .so 直接失败（CI 差分/全量单测用后者保证 0 skipped）。
 *
 * <p>比较的是 {@code VALID/INVALID} 与**原因码集合**（不是首错），任何分歧都是硬失败
 * （A3 §3.9 P-5：verify_plan 分歧禁止回退）。本测试还断言 Rust 路径确实被执行、同一输入
 * 两次调用完全相同、输入字节未被修改。
 */
public class PlanViewDifferentialTest {
  static final String HOST_LIB_PROPERTY = "dsha.rust.hostLib";
  static final String REQUIRED_PROPERTY = "dsha.rust.required";

  /** 冻结 ABI 错误码（方案 §4.2；测试侧常量，避免扩大生产桥 API）。 */
  static final int INVALID_ARGUMENT = -1;

  static final int OUTPUT_BOUND = -3;

  /** 本测试类真实发生过的 native 调用次数（证明不是“全走 JVM 也算通过”）。 */
  private static int nativeCalls = 0;

  /** 一次 native 调用的返回码与原因集合。 */
  static final class NativeResult {
    final int code;
    final int[] reasons;

    NativeResult(int code, int[] reasons) {
      this.code = code;
      this.reasons = reasons;
    }

    boolean valid() {
      return code == 0;
    }
  }

  @BeforeClass
  public static void loadHostLibrary() {
    String libraryPath = System.getProperty(HOST_LIB_PROPERTY, "").trim();
    boolean required = Boolean.parseBoolean(System.getProperty(REQUIRED_PROPERTY, "false"));
    if (libraryPath.isEmpty()) {
      if (required) fail("RUST_HOST_LIB_REQUIRED: 缺少 -D" + HOST_LIB_PROPERTY);
      Assume.assumeTrue("RUST_HOST_LIB_MISSING: 未提供 -D" + HOST_LIB_PROPERTY, false);
    }
    if (!RustNative.loadFromPath(libraryPath)) {
      if (required) fail("RUST_HOST_LIB_UNUSABLE: " + RustNative.statusCode());
      Assume.assumeTrue("RUST_HOST_LIB_UNUSABLE: " + RustNative.statusCode(), false);
    }
    assertEquals(RustNative.ABI_VERSION, RustNative.abiVersion());
    assertEquals(RustNative.Status.LOADED, RustNative.status());
  }

  @AfterClass
  public static void restoreSwitch() {
    RustBackends.setEnabled(false);
  }

  @After
  public void resetSwitch() {
    RustBackends.setEnabled(false);
  }

  private static int readIntLe(ByteBuffer buffer, int offset) {
    return (buffer.get(offset) & 0xFF)
        | ((buffer.get(offset + 1) & 0xFF) << 8)
        | ((buffer.get(offset + 2) & 0xFF) << 16)
        | ((buffer.get(offset + 3) & 0xFF) << 24);
  }

  /** 用 direct 缓冲调用冻结的 JNI 入口；只解析 0/1 时的原因载荷。 */
  static NativeResult verifyRust(byte[] view, int maxReasons) {
    ByteBuffer viewBuffer = ByteBuffer.allocateDirect(Math.max(view.length, 1));
    viewBuffer.put(view);
    ByteBuffer reasonsBuffer = ByteBuffer.allocateDirect(4 + 4 * Math.max(maxReasons, 0));
    reasonsBuffer.order(ByteOrder.LITTLE_ENDIAN);
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
    return new NativeResult(code, reasons);
  }

  private static PlanViewValidatorTest.Case caseByName(String name) {
    for (PlanViewValidatorTest.Case testCase : PlanViewValidatorTest.allCases()) {
      if (testCase.name.equals(name)) {
        return testCase;
      }
    }
    fail("夹具缺失: " + name);
    return null;
  }

  @Test
  public void jvmAndRustAgreeOnTheFrozenMatrix() {
    List<PlanViewValidatorTest.Case> cases = PlanViewValidatorTest.allCases();
    for (PlanViewValidatorTest.Case testCase : cases) {
      // nowMs 唯一来源 = 视图内嵌值（与 verify_plan 同口径）。
      PlanViewValidator.Result jvm = PlanViewValidator.validate(testCase.view);
      NativeResult rust = verifyRust(testCase.view, 8);
      assertEquals(testCase.name + " valid", jvm.valid(), rust.valid());
      assertArrayEquals(testCase.name + " reasons", jvm.reasons(), rust.reasons);
    }
    assertTrue("native 调用次数应等于用例数", nativeCalls >= cases.size());
  }

  @Test
  public void jvmAndRustAgreeOnDeterministicRandomViews() {
    Random random = new Random(20_261_113L);
    byte[] canonical = PlanViewValidatorTest.canonical();
    for (int iteration = 0; iteration < 256; iteration++) {
      byte[] view;
      int mode = iteration % 3;
      if (mode == 0) {
        view = new byte[random.nextInt(200)];
        random.nextBytes(view);
      } else if (mode == 1) {
        // 合法头部/前缀 + 随机尾巴：让解析走得更深。
        int suffix = random.nextInt(120);
        view = Arrays.copyOf(canonical, Math.min(canonical.length, 48 + suffix));
        for (int index = 48; index < view.length; index++) {
          view[index] = (byte) random.nextInt(256);
        }
      } else {
        view = canonical.clone();
        int mutations = 1 + random.nextInt(4);
        for (int count = 0; count < mutations; count++) {
          view[random.nextInt(view.length)] = (byte) random.nextInt(256);
        }
      }
      PlanViewValidator.Result jvm = PlanViewValidator.validate(view);
      NativeResult rust = verifyRust(view, 8);
      assertEquals("iteration=" + iteration, jvm.valid(), rust.valid());
      assertArrayEquals("iteration=" + iteration, jvm.reasons(), rust.reasons);
    }
  }

  @Test
  public void reEncodedViewsGiveTheSameVerdictOnBothImplementations() {
    // P1 回归：同一计划用 now=1999 / 2000 分别重新编码（planDigest 随 §3.5 重算），
    // JVM 与 Rust 必须对两份字节分别给出 VALID / [PLAN_EXPIRED]；不允许覆盖式 nowMs。
    int callsBefore = nativeCalls;
    byte[] beforeExpiry = PlanViewCodec.encode(PlanViewValidatorTest.expiryView(1_999));
    byte[] atExpiry = PlanViewCodec.encode(PlanViewValidatorTest.expiryView(2_000));
    assertFalse("重编码必须改变视图字节", Arrays.equals(beforeExpiry, atExpiry));

    PlanViewValidator.Result beforeJvm = PlanViewValidator.validate(beforeExpiry);
    PlanViewValidator.Result atJvm = PlanViewValidator.validate(atExpiry);
    NativeResult beforeRust = verifyRust(beforeExpiry, 8);
    NativeResult atRust = verifyRust(atExpiry, 8);

    assertTrue(beforeJvm.valid());
    assertEquals(0, beforeRust.code);
    assertArrayEquals(new int[0], beforeRust.reasons);
    assertFalse(atJvm.valid());
    assertArrayEquals(new int[] {PlanViewValidator.REASON_EXPIRED}, atJvm.reasons());
    assertEquals(1, atRust.code);
    assertArrayEquals(new int[] {PlanViewValidator.REASON_EXPIRED}, atRust.reasons);
    assertEquals("两份字节各调用一次 Rust", 2, nativeCalls - callsBefore);
  }

  @Test
  public void rustCallIsDeterministicAndDoesNotMutateItsInput() {
    byte[] view = PlanViewValidatorTest.canonical();
    byte[] snapshot = view.clone();
    NativeResult first = verifyRust(view, 8);
    NativeResult second = verifyRust(view, 8);
    assertArrayEquals("输入字节不能被 Rust 修改", snapshot, view);
    assertEquals(first.code, second.code);
    assertArrayEquals(first.reasons, second.reasons);
    assertEquals(0, first.code);
    assertTrue(nativeCalls > 0);

    byte[] invalid = caseByName("T29-multi-reason").view;
    NativeResult invalidFirst = verifyRust(invalid, 8);
    NativeResult invalidSecond = verifyRust(invalid, 8);
    assertEquals(1, invalidFirst.code);
    assertArrayEquals(invalidFirst.reasons, invalidSecond.reasons);
    assertArrayEquals(
        new int[] {
          PlanViewValidator.REASON_FIELD_RANGE,
          PlanViewValidator.REASON_POLICY_VERSION,
          PlanViewValidator.REASON_EXPIRED
        },
        invalidFirst.reasons);
  }

  @Test
  public void outputBoundNeverWritesHalfAResult() {
    byte[] view = caseByName("T29-multi-reason").view;
    ByteBuffer viewBuffer = ByteBuffer.allocateDirect(view.length);
    viewBuffer.put(view);
    ByteBuffer reasonsBuffer = ByteBuffer.allocateDirect(4 + 4 * 8);
    for (int index = 0; index < reasonsBuffer.capacity(); index++) {
      reasonsBuffer.put(index, (byte) 0x5A);
    }
    int code = RustNative.verifyPlan(viewBuffer, view.length, reasonsBuffer, 1);
    nativeCalls++;
    assertEquals(OUTPUT_BOUND, code);
    for (int index = 0; index < reasonsBuffer.capacity(); index++) {
      assertEquals("byte " + index + " 不应被写", 0x5A, reasonsBuffer.get(index) & 0xFF);
    }
    // maxReasons=0 且存在原因：同样 OUTPUT_BOUND，缓冲不写。
    ByteBuffer zeroAllowed = ByteBuffer.allocateDirect(4);
    zeroAllowed.putInt(0, 0x5A5A5A5A);
    int zeroCode = RustNative.verifyPlan(viewBuffer, view.length, zeroAllowed, 0);
    nativeCalls++;
    assertEquals(OUTPUT_BOUND, zeroCode);
    assertEquals(0x5A5A5A5A, zeroAllowed.getInt(0));
  }

  @Test
  public void jniBufferAndScalarContractErrorsAreTyped() {
    byte[] view = PlanViewValidatorTest.canonical();
    ByteBuffer directView = ByteBuffer.allocateDirect(view.length);
    directView.put(view);
    ByteBuffer heapView = ByteBuffer.allocate(view.length);
    heapView.put(view);
    ByteBuffer reasons = ByteBuffer.allocateDirect(4 + 4 * 8);
    ByteBuffer tooSmallReasons = ByteBuffer.allocateDirect(4);

    assertEquals(INVALID_ARGUMENT, RustNative.verifyPlan(null, 0, reasons, 8));
    assertEquals(INVALID_ARGUMENT, RustNative.verifyPlan(directView, -1, reasons, 8));
    assertEquals(INVALID_ARGUMENT, RustNative.verifyPlan(directView, view.length, null, 8));
    assertEquals(INVALID_ARGUMENT, RustNative.verifyPlan(directView, view.length, reasons, -1));
    assertEquals(INVALID_ARGUMENT, RustNative.verifyPlan(heapView, view.length, reasons, 8));
    // reasons 容量 < 4 + 4*maxReasons：契约错误，不是 OUTPUT_BOUND。
    assertEquals(
        INVALID_ARGUMENT, RustNative.verifyPlan(directView, view.length, tooSmallReasons, 8));
    // view 容量 < viewLength。
    assertEquals(INVALID_ARGUMENT, RustNative.verifyPlan(directView, view.length + 1, reasons, 8));
    nativeCalls += 7;
  }

  @Test
  public void planSelectionIsOffByDefaultAndFollowsTheRuntimeSwitch() {
    RustBackends.setEnabled(false);
    RustBackends.Selection off = RustBackends.planSelection();
    assertFalse(off.useRust());
    assertEquals(RustBackends.SelectionReason.SWITCH_OFF, off.reason());
    assertEquals("SWITCH_OFF", off.reasonCode());

    RustBackends.setEnabled(true);
    RustBackends.Selection on = RustBackends.planSelection();
    assertTrue(on.useRust());
    assertEquals(RustBackends.SelectionReason.RUST_ENABLED, on.reason());

    RustBackends.setEnabled(false);
    assertFalse(RustBackends.planSelection().useRust());
  }

  @Test
  public void jvmSideIsFailClosedForMissingViews() {
    for (byte[] view : new byte[][] {null, new byte[0], new byte[47]}) {
      PlanViewValidator.Result result = PlanViewValidator.validate(view);
      assertFalse(result.valid());
      assertArrayEquals(new int[] {PlanViewValidator.REASON_STRUCTURE}, result.reasons());
    }
  }
}
