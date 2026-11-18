package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.junit.After;
import org.junit.Test;

/** 迁移前先在 Java 基线上运行；只冻结既有可调用契约，不需要或假装加载 Rust。 */
public class RustBridgeApiGoldenTest {
  @After
  public void resetSwitch() {
    RustBackends.setEnabled(false);
  }

  @Test
  public void nativeOwnerDescriptorsAndClassMonitorStayFrozen() throws Exception {
    Class<?> owner = RustNative.class;
    String[] names = {
      "abiVersion",
      "digest",
      "archivePackCreate",
      "archivePackUpdate",
      "archivePackFinish",
      "archivePackAbort",
      "archiveUnpackCreate",
      "archiveUnpackWrite",
      "archiveUnpackRead",
      "archiveUnpackAbort",
      "verifyPlan"
    };
    Class<?>[][] parameters = {
      {},
      {int.class, long.class, long.class, int.class, ByteBuffer.class},
      {int.class},
      {long.class, ByteBuffer.class, ByteBuffer.class},
      {long.class, ByteBuffer.class},
      {long.class},
      {},
      {long.class, ByteBuffer.class},
      {long.class, ByteBuffer.class},
      {long.class},
      {ByteBuffer.class, int.class, ByteBuffer.class, int.class}
    };
    Class<?>[] returns = {
      int.class,
      long.class,
      long.class,
      long.class,
      long.class,
      int.class,
      long.class,
      long.class,
      long.class,
      int.class,
      int.class
    };
    Set<String> nativeNames = new HashSet<>();
    for (Method method : owner.getDeclaredMethods()) {
      if (Modifier.isNative(method.getModifiers())) nativeNames.add(method.getName());
    }
    assertEquals(new HashSet<>(Arrays.asList(names)), nativeNames);
    for (int index = 0; index < names.length; index++) {
      Method method = owner.getDeclaredMethod(names[index], parameters[index]);
      assertEquals(Modifier.PUBLIC | Modifier.STATIC | Modifier.NATIVE, method.getModifiers());
      assertEquals(returns[index], method.getReturnType());
      assertEquals(0, method.getExceptionTypes().length);
    }
    assertEquals(
        Modifier.PUBLIC | Modifier.STATIC | Modifier.SYNCHRONIZED,
        owner.getDeclaredMethod("ensureLoaded").getModifiers());
    assertEquals(
        Modifier.PUBLIC | Modifier.STATIC | Modifier.SYNCHRONIZED,
        owner.getDeclaredMethod("status").getModifiers());
    assertEquals(
        Modifier.STATIC | Modifier.SYNCHRONIZED,
        owner.getDeclaredMethod("loadFromPath", String.class).getModifiers());
    assertEquals(1, RustNative.ABI_VERSION);
    assertEquals("dsha_rs", RustNative.LIBRARY_NAME);
    // ACC_STATIC + ACC_SYNCHRONIZED 锁定声明类 Class，而非 Companion/INSTANCE。
    assertSame(RustNative.class, owner.getDeclaredMethod("ensureLoaded").getDeclaringClass());
  }

  @Test
  public void staticHelpersVolatileSwitchAndPrivateConstructionRemain() throws Exception {
    for (Class<?> owner :
        new Class<?>[] {
          RustArchiveCodec.class, RustBackends.class, RustDigest.class, RustNative.class
        }) {
      assertTrue(Modifier.isFinal(owner.getModifiers()));
      Constructor<?> constructor = owner.getDeclaredConstructor();
      assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    }
    Field enabled = RustBackends.class.getDeclaredField("enabled");
    assertEquals(Modifier.PRIVATE | Modifier.STATIC | Modifier.VOLATILE, enabled.getModifiers());
    assertStatic(RustArchiveCodec.class, "packBound", int.class);
    assertStatic(RustArchiveCodec.class, "canonicalJvmMessage", String.class);
    assertStatic(RustBackends.class, "setEnabled", boolean.class);
    assertStatic(RustBackends.class, "digestSelection");
    assertStatic(
        RustDigest.class, "digestJvm", FileChannel.class, long.class, long.class, int.class);
    assertStatic(
        RustDigest.class,
        "settleRustResult",
        RustDigest.Result.class,
        FileChannel.class,
        long.class,
        long.class,
        int.class);
    assertStatic(RustDigest.class, "shouldRedoOnJvm", RustDigest.Failure.class);
    assertStatic(RustDigest.class, "digestBytes", int.class);
    assertStatic(RustDigest.Result.class, "simulatedFailure", RustDigest.Failure.class, int.class);
    assertTrue(
        Modifier.isPrivate(
            RustBackends.Selection.class
                .getDeclaredConstructor(boolean.class, RustBackends.SelectionReason.class)
                .getModifiers()));
    assertTrue(
        Modifier.isPrivate(
            RustDigest.Result.class
                .getDeclaredConstructor(
                    RustDigest.Backend.class,
                    byte[].class,
                    long.class,
                    int.class,
                    RustDigest.Failure.class,
                    boolean.class,
                    String.class)
                .getModifiers()));
  }

  @Test
  public void checkedIoExceptionsRemainVisibleToJava() throws Exception {
    assertArrayEquals(
        new Class<?>[] {IOException.class},
        RustArchiveCodec.class
            .getDeclaredMethod("packStream", OutputStream.class, long.class)
            .getExceptionTypes());
    assertArrayEquals(
        new Class<?>[] {IOException.class},
        RustGzipInputStream.class.getDeclaredConstructor(InputStream.class).getExceptionTypes());
    assertArrayEquals(
        new Class<?>[] {IOException.class},
        RustGzipOutputStream.class.getDeclaredConstructor(OutputStream.class).getExceptionTypes());
    assertArrayEquals(
        new Class<?>[] {IOException.class},
        RustGzipOutputStream.class
            .getDeclaredConstructor(OutputStream.class, int.class)
            .getExceptionTypes());
    for (String method : new String[] {"finish", "flush", "close"}) {
      assertArrayEquals(
          new Class<?>[] {IOException.class},
          RustGzipOutputStream.class.getDeclaredMethod(method).getExceptionTypes());
    }
    for (Class<?> owner : new Class<?>[] {RustGzipInputStream.class, RustGzipOutputStream.class}) {
      String method = owner == RustGzipInputStream.class ? "read" : "write";
      assertArrayEquals(
          new Class<?>[] {IOException.class},
          owner.getDeclaredMethod(method, byte[].class, int.class, int.class).getExceptionTypes());
    }
    assertEquals(
        0, RustGzipInputStream.class.getDeclaredMethod("available").getExceptionTypes().length);
  }

  @Test
  public void nullAndInvalidLevelPriorityIsNotCompilerGenerated() throws Exception {
    assertFailure(NullPointerException.class, "source", () -> new RustGzipInputStream(null));
    assertFailure(NullPointerException.class, "target", () -> new RustGzipOutputStream(null, -1));
    assertFailure(
        NullPointerException.class, "target", () -> RustArchiveCodec.packStream(null, -1));
    assertFailure(
        IOException.class,
        "INVALID_ARGUMENT",
        () -> new RustGzipOutputStream(new ByteArrayOutputStream(), -1));
    assertNull(RustArchiveCodec.canonicalJvmMessage(null));
    assertEquals(Integer.MIN_VALUE, RustArchiveCodec.errorCodeForJvmMessage(null));
    assertFalse(RustDigest.shouldRedoOnJvm(null));
  }

  @Test
  public void constantsAndSignedPackingKeepExactJvmValues() {
    assertEquals(295040, RustArchiveCodec.MAX_PACK_OUTPUT);
    assertEquals(17448304640L, RustArchiveCodec.MAX_TOTAL_BYTES);
    assertEquals("RUST_ERROR_", RustArchiveCodec.RUST_ERROR_PREFIX);
    assertEquals(-1, RustArchiveCodec.consumed(0xffff_ffff_0000_0000L));
    assertEquals(-1, RustArchiveCodec.produced(0x0000_0000_ffff_ffffL));
    assertEquals(-1, RustArchiveCodec.unpackProduced(0xffff_ffff_0000_0000L));
    assertEquals(-1, RustArchiveCodec.unpackFlags(0x0000_0000_ffff_ffffL));
    assertEquals("RUST_ERROR_-9", RustArchiveCodec.message(-9));
    assertEquals("OUTPUT_BOUND", RustArchiveCodec.message(-3));
    assertEquals("ARCHIVE_LIMIT", RustArchiveCodec.unpackMessage(-3));
    assertEquals("GZIP_HEADER", RustArchiveCodec.canonicalJvmMessage("GZIP_FLAGS"));
    assertEquals(RustDigest.Failure.UNKNOWN, RustDigest.mapFailure(0));
  }

  @Test
  public void resultUsesIdentityAndDefensiveBytesAndBorrowedChannel() throws Exception {
    Path file = Files.createTempFile("rust-bridge-golden-", ".bin");
    try {
      Files.write(file, "abc".getBytes(StandardCharsets.US_ASCII));
      try (FileChannel channel = FileChannel.open(file)) {
        channel.position(2);
        RustDigest.Result first = RustDigest.digestJvm(channel, 0, 3, 1);
        RustDigest.Result second = RustDigest.digestJvm(channel, 0, 3, 1);
        assertTrue(first.ok());
        assertNotSame(first, second);
        assertFalse(first.equals(second));
        byte[] bytes = first.digest();
        bytes[0] ^= 0x7f;
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", first.digestHex());
        assertNotSame(first.digest(), first.digest());
        assertEquals(2, channel.position());
        assertTrue(channel.isOpen());
        assertSame(first, RustDigest.settleRustResult(first, channel, 0, 3, 1));
      }
    } finally {
      Files.delete(file);
    }
  }

  @Test
  public void onlyInternalOrPanicMayRedoAndNoSourcePreservesTypedResult() {
    for (RustDigest.Failure failure : RustDigest.Failure.values()) {
      RustDigest.Result rust = RustDigest.Result.simulatedFailure(failure, -91);
      RustDigest.Result settled = RustDigest.settleRustResult(rust, null, 0, 1, 1);
      if (failure == RustDigest.Failure.INTERNAL || failure == RustDigest.Failure.PANIC) {
        assertFalse(settled.fellBack());
        assertEquals("JVM_SOURCE_UNAVAILABLE", settled.fallbackReason());
        assertEquals(failure, settled.failure());
        assertEquals(-91, settled.errorCode());
      } else {
        assertSame(rust, settled);
      }
    }
    assertEquals(RustDigest.INVALID_ARGUMENT, RustDigest.digestJvm(null, -1, 0, 99).errorCode());
    assertEquals(
        RustDigest.UNSUPPORTED_ALGORITHM, RustDigest.digestJvm(null, 0, 1, 99).errorCode());
  }

  @Test
  public void jvmFactoryBorrowsTargetAndDoesNotReplaceSelection() throws Exception {
    RustBackends.setEnabled(false);
    CountingOutput target = new CountingOutput();
    OutputStream encoder = RustArchiveCodec.packStream(target, -1);
    encoder.write(new byte[] {0, 1, 2, (byte) 255});
    encoder.close();
    encoder.close();
    assertEquals(0, target.closes);
    try (GZIPInputStream input =
        new GZIPInputStream(new ByteArrayInputStream(target.toByteArray()))) {
      assertArrayEquals(new byte[] {0, 1, 2, (byte) 255}, input.readAllBytes());
    }
    RustBackends.Selection first = RustBackends.archiveSelection();
    assertFalse(first.useRust());
    assertEquals("SWITCH_OFF", first.reasonCode());
    assertNotSame(first, RustBackends.archiveSelection());
    assertFalse(first.equals(RustBackends.archiveSelection()));
  }

  private static void assertStatic(Class<?> owner, String name, Class<?>... parameters)
      throws Exception {
    int modifiers = owner.getDeclaredMethod(name, parameters).getModifiers();
    assertTrue(Modifier.isStatic(modifiers));
    assertFalse(Modifier.isFinal(modifiers));
  }

  private interface Throwing {
    void run() throws Exception;
  }

  private static void assertFailure(Class<? extends Throwable> type, String message, Throwing work)
      throws Exception {
    try {
      work.run();
      fail("Expected " + type.getSimpleName());
    } catch (Throwable error) {
      assertEquals(type, error.getClass());
      assertEquals(message, error.getMessage());
    }
  }

  private static final class CountingOutput extends ByteArrayOutputStream {
    int closes;

    @Override
    public void close() {
      closes++;
    }
  }
}
