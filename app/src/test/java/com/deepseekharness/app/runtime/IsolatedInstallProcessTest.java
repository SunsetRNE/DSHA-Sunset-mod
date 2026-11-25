package com.deepseekharness.app.runtime;

import com.deepseekharness.app.util.ProcessIdentity;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class IsolatedInstallProcessTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static Process process(boolean ended) {
    return process(ended, 0, OutputStream.nullOutputStream());
  }

  private static Process process(boolean ended, int code, OutputStream input) {
    return new Process() {
      public OutputStream getOutputStream() {
        return input;
      }

      public InputStream getInputStream() {
        return InputStream.nullInputStream();
      }

      public InputStream getErrorStream() {
        return InputStream.nullInputStream();
      }

      public int exitValue() {
        if (!ended) throw new IllegalThreadStateException();
        return code;
      }

      public int waitFor() {
        return exitValue();
      }

      public void destroy() {}
    };
  }

  private File executable(String name, int machine) throws Exception {
    File file = temporary.newFile(name);
    boolean wide = machine != 3;
    var header = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    header.putInt(0, 0x464c457f);
    header.put(4, (byte) (wide ? 2 : 1));
    header.put(5, (byte) 1);
    header.put(6, (byte) 1);
    header.putShort(16, (short) 3);
    header.putShort(18, (short) machine);
    header.putInt(20, 1);
    header.putShort(wide ? 52 : 40, (short) (wide ? 64 : 52));
    java.nio.file.Files.write(file.toPath(), header.array());
    return file;
  }

  private static OutputStream broken(java.io.IOException cause, boolean failOnWrite) {
    return new OutputStream() {
      @Override
      public void write(int value) throws java.io.IOException {
        if (failOnWrite) throw cause;
      }

      @Override
      public void flush() throws java.io.IOException {
        throw cause;
      }
    };
  }

  @Test
  public void startConfirmationIsWrittenAndFlushedExactlyOnce() throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    int[] flushed = {0};
    OutputStream gate =
        new java.io.FilterOutputStream(bytes) {
          @Override
          public void flush() throws java.io.IOException {
            flushed[0]++;
            super.flush();
          }
        };
    IsolatedInstallProcess.confirmStart(process(false, 0, gate), null, null);
    assertArrayEquals(
        "DSHA_START\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII), bytes.toByteArray());
    assertEquals(1, flushed[0]);
  }

  @Test
  public void confirmedExitAndTranslatedHelperGiveActionableDiagnosisInAllLanguages()
      throws Exception {
    var helper = executable("libdsha-session.so", 183);
    var android = executable("app_process", 62);
    var cause = new java.io.IOException("synthetic-private-text");
    String previous = com.deepseekharness.app.util.UiText.language();
    try {
      java.util.Set<String> messages = new java.util.HashSet<>();
      for (String language :
          new String[] {"zh", "zh-Hant", "en", "ar", "ru", "ko", "ja", "fr", "pt", "la"}) {
        com.deepseekharness.app.util.UiText.setLanguage(language);
        var failed =
            assertThrows(
                java.io.IOException.class,
                () ->
                    IsolatedInstallProcess.confirmStart(
                        process(true, 137, broken(cause, false)), helper, android));
        assertSame(cause, failed.getCause());
        String text = failed.getMessage();
        assertTrue(text.contains("137"));
        assertTrue(text.contains("ARM64"));
        assertTrue(text.contains("x86"));
        assertFalse(text.contains("synthetic-private-text"));
        assertTrue("missing translation for " + language, messages.add(text));
      }
      com.deepseekharness.app.util.UiText.setLanguage("en");
      var failed =
          assertThrows(
              java.io.IOException.class,
              () ->
                  IsolatedInstallProcess.confirmStart(
                      process(true, 137, broken(cause, false)), helper, android));
      assertTrue(failed.getMessage().contains("supervisor exited with code 137."));
      assertTrue(failed.getMessage().contains("Diagnostics are available in Settings."));
    } finally {
      com.deepseekharness.app.util.UiText.setLanguage(previous);
    }
  }

  @Test
  public void unconfirmedExitOnWriteRemainsFailureWithoutGuessingACodeOrAbi() throws Exception {
    var helper = executable("libdsha-session.so", 183);
    var armSystem = executable("app_process", 183);
    var cause = new java.io.IOException("synthetic-private-text");
    String previous = com.deepseekharness.app.util.UiText.language();
    try {
      com.deepseekharness.app.util.UiText.setLanguage("en");
      var failed =
          assertThrows(
              java.io.IOException.class,
              () ->
                  IsolatedInstallProcess.confirmStart(
                      process(false, 0, broken(cause, true)), helper, armSystem));
      assertSame(cause, failed.getCause());
      assertEquals(
          "Could not send the start confirmation. The installation supervisor’s exit status is unknown.",
          failed.getMessage());
      var x86System = executable("app_process32", 3);
      java.nio.file.Files.write(helper.toPath(), new byte[] {0x7f, 'E', 'L', 'F'});
      var incomplete =
          assertThrows(
              java.io.IOException.class,
              () ->
                  IsolatedInstallProcess.confirmStart(
                      process(true, 42, broken(cause, false)), helper, x86System));
      assertEquals(
          "Could not send the start confirmation. The installation supervisor exited with code 42.",
          incomplete.getMessage());
    } finally {
      com.deepseekharness.app.util.UiText.setLanguage(previous);
    }
  }

  private static ProcessIdentity birth() {
    return ProcessIdentity.fromStat(
        "123 (signed leader) S 99 123 123 " + "0 ".repeat(15) + "456", 123, 99);
  }

  @Test
  public void foregroundPollingLeavesStatusRecordForExplicitClose() throws Exception {
    File status = temporary.newFile();
    java.nio.file.Files.writeString(status.toPath(), "7\n");
    var isolated =
        new IsolatedInstallProcess(
            process(false),
            birth(),
            status,
            new IsolatedInstallProcess.ExitEvidence() {
              public boolean groupGone() {
                return false;
              }

              public boolean sessionEmpty() {
                return false;
              }
            });
    assertEquals(7, isolated.exitValue());
    assertFalse(isolated.isAlive());
    assertEquals(7, isolated.exitValue());
    assertTrue(status.isFile());
    assertEquals("7\n", java.nio.file.Files.readString(status.toPath()));
  }

  @Test
  public void timeoutCannotInventExitWhenSupervisorEndedButGuestSessionRemains() throws Exception {
    File status = new File(temporary.getRoot(), "missing");
    var isolated =
        new IsolatedInstallProcess(
            process(true),
            birth(),
            status,
            new IsolatedInstallProcess.ExitEvidence() {
              public boolean groupGone() {
                return true;
              }

              public boolean sessionEmpty() {
                return false;
              }
            });
    long started = System.nanoTime();
    assertFalse(isolated.waitFor(40, TimeUnit.MILLISECONDS));
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500);
    assertThrows(IllegalThreadStateException.class, isolated::exitValue);
    assertTrue(isolated.isAlive());
  }
}
