package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.RestorePlanProjection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A3 slice2：真实投影器产出的 {@code PlanView} 在 JVM 与 Rust 第二实现下必须一致通过。
 *
 * <p>需要主机目标 .so：{@code -Ddsha.rust.hostLib=<repo>/app/build/rust-host/libdsha_rs.so}；
 * 加 {@code -Ddsha.rust.required=true} 时缺少 .so 直接失败（full 单测带该参数，保证 0 skipped）。
 * 本测试断言 Rust 路径真的被执行（{@code backend=JVM_AND_RUST}），避免“全走 JVM 也算通过”。
 */
public class RestorePlanProjectionDifferentialTest {
  static final String HOST_LIB_PROPERTY = "dsha.rust.hostLib";

  static final String REQUIRED_PROPERTY = "dsha.rust.required";

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

  @After
  public void resetSwitch() {
    RustBackends.setEnabled(false);
  }

  @AfterClass
  public static void restoreSwitch() {
    RustBackends.setEnabled(false);
  }

  private static Map<String, Object> manifest() {
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("formatVersion", 5L);
    manifest.put("operation", "EXPORT");
    manifest.put("integrity", "EXTERNAL_CHECKED");
    manifest.put("createdAt", 1_760_000_000_000L);
    manifest.put("appVersion", "0.2.1-rc.5-20261113.1340-dsh0.2.2-rc.1");
    manifest.put("dataFormat", "UNINSPECTED");
    manifest.put("sensitivePolicy", "PASSWORD_ENCRYPTED");
    manifest.put("entries", 42L);
    manifest.put("bytes", 1_024L);
    manifest.put(
        "plugins",
        Map.of("version", 2L, "nodes", Map.of("package-aaaaaaaaaaaaaaaaaaaa", Map.of())));
    return manifest;
  }

  private static byte[] digest() {
    byte[] value = new byte[32];
    for (int index = 0; index < value.length; index++) value[index] = (byte) (index * 7);
    return value;
  }

  @Test
  public void projectedPlanViewIsValidInBothImplementations() throws Exception {
    RustBackends.setEnabled(true);
    RestorePlanProjection.Receipt receipt =
        RestorePlanProjection.project(
            manifest(),
            2,
            digest(),
            Set.of("application", "plugins"),
            RestorePlanProjection.targetMappingKind("LEGACY", "GUEST_HOME"),
            1_760_000_000_500L);
    assertTrue(receipt.projected());
    assertEquals(RestorePlanProjection.BACKEND_JVM_AND_RUST, receipt.backend());
    assertEquals("RUST_ENABLED", receipt.selection());
    assertEquals(RestorePlanProjection.CHECK_VALID, receipt.jvmState());
    assertEquals(RestorePlanProjection.CHECK_VALID, receipt.rustState());
    assertEquals(RestorePlanProjection.VERDICT_VALID, receipt.verdict());
    assertEquals(RestorePlanProjection.DIAGNOSTIC_READY, receipt.diagnostic());
    assertEquals(0, receipt.jvmReasons().length);
    assertEquals(0, receipt.rustReasons().length);
    RestorePlanProjection.requireSubmitSafe(receipt);
  }

  @Test
  public void bothImplementationsRejectTheSameCorruptedViewByte() {
    RustBackends.setEnabled(true);
    RestorePlanProjection.Receipt valid =
        RestorePlanProjection.project(
            manifest(), 1, digest(), Set.of("application"), 1, 1_760_000_000_500L);
    assertTrue(valid.projected());
    // 字段 1（formatVersion，U32）的值区在 48 字节头之后第一个字段：48+8=56。
    byte[] corrupted = valid.view().clone();
    corrupted[56] = 4;
    corrupted[57] = 0;
    corrupted[58] = 0;
    corrupted[59] = 0;
    PlanViewValidator.Result jvm = PlanViewValidator.validate(corrupted);
    assertFalse(jvm.valid());
    int[] jvmReasons = jvm.reasons();
    assertArrayEquals(new int[] {PlanViewValidator.REASON_FIELD_RANGE}, jvmReasons);

    ByteBuffer viewBuffer = ByteBuffer.allocateDirect(corrupted.length);
    viewBuffer.put(corrupted);
    ByteBuffer reasonsBuffer = ByteBuffer.allocateDirect(4 + 4 * 8).order(ByteOrder.LITTLE_ENDIAN);
    int code = RustNative.verifyPlan(viewBuffer, corrupted.length, reasonsBuffer, 8);
    assertEquals(PlanViewValidator.INVALID, code);
    int count = reasonsBuffer.getInt(0);
    int[] rustReasons = new int[count];
    for (int index = 0; index < count; index++) {
      rustReasons[index] = reasonsBuffer.getInt(4 + index * 4);
    }
    assertArrayEquals(jvmReasons, rustReasons);
  }
}
