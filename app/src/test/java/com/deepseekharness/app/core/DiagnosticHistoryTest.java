package com.deepseekharness.app.core;

import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticHistoryTest {
  private static ScheduledThreadPoolExecutor manualWriter() {
    return new ScheduledThreadPoolExecutor(1) {
      @Override
      public ScheduledFuture<?> schedule(Runnable work, long delay, TimeUnit unit) {
        return super.schedule(work, 1, TimeUnit.DAYS);
      }
    };
  }

  private static class MemoryStore implements DiagnosticHistory.Store {
    String persisted = "old\n";
    int writes;

    @Override
    public String read() {
      return persisted;
    }

    @Override
    public void publish(String value) throws IOException {
      writes++;
      persisted = value;
    }

    @Override
    public void syncPublished() throws IOException {}
  }

  @Test
  public void exportIncludesPendingSanitizedRowsInSubmissionOrder() throws Exception {
    var worker = manualWriter();
    try {
      var store = new MemoryStore();
      var writer = new DiagnosticHistory.Writer(worker, store);
      String first = DiagnosticLog.line("WEB_JS\n]", "api_key=secret1234\nnext");
      String second = DiagnosticLog.line("WEB_ROUTE", "second");
      writer.add(first);
      writer.add(second);
      assertFalse(first.contains("secret1234"));
      assertTrue(first.contains("[WEB_JS]"));
      assertEquals(1, first.chars().filter(c -> c == '\n').count());
      assertEquals(0, store.writes);
      assertEquals("old\n" + first + second, writer.read());
      assertTrue(writer.flush());
      assertEquals(1, store.writes);
      assertEquals(store.persisted, writer.read());
    } finally {
      worker.shutdownNow();
    }
  }

  @Test
  public void submissionDoesNotWaitForAnInFlightPublication() throws Exception {
    CountDownLatch writing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var worker = manualWriter();
    var caller = Executors.newSingleThreadExecutor();
    try {
      var store =
          new MemoryStore() {
            @Override
            public void publish(String value) throws IOException {
              writing.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("TEST_TIMEOUT");
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
              }
              super.publish(value);
            }
          };
      var writer = new DiagnosticHistory.Writer(worker, store);
      writer.add("first\n");
      var publishing = worker.submit(writer::flush);
      assertTrue(writing.await(3, TimeUnit.SECONDS));
      caller.submit(() -> writer.add("second\n")).get(3, TimeUnit.SECONDS);
      release.countDown();
      assertTrue(publishing.get(3, TimeUnit.SECONDS));
      assertEquals("old\nfirst\n", store.persisted);
      assertEquals("old\nfirst\nsecond\n", writer.read());
      assertTrue(writer.flush());
      assertEquals("old\nfirst\nsecond\n", store.persisted);
    } finally {
      release.countDown();
      caller.shutdownNow();
      worker.shutdownNow();
    }
  }

  @Test
  public void failuresBeforeAndAfterPublicationPreserveRowsWithoutDuplicates() throws Exception {
    var worker = manualWriter();
    try {
      var store =
          new MemoryStore() {
            int attempts;
            int syncs;

            @Override
            public void publish(String value) throws IOException {
              if (++attempts == 1) throw new IOException("BEFORE_MOVE");
              super.publish(value);
              if (attempts == 2) throw new IOException("AFTER_MOVE");
            }

            @Override
            public void syncPublished() throws IOException {
              if (++syncs == 1) throw new IOException("SYNC_RETRY");
            }
          };
      var writer = new DiagnosticHistory.Writer(worker, store);
      writer.add("first\n");
      assertFalse(writer.flush());
      assertEquals("old\nfirst\n", writer.read());
      assertFalse(writer.flush());
      writer.add("second\n");
      assertEquals("old\nfirst\nsecond\n", writer.read());
      assertEquals(0, store.syncs);
      assertFalse(writer.flush());
      assertEquals("old\nfirst\n", store.persisted);
      assertTrue(writer.flush());
      assertEquals(2, store.syncs);
      assertEquals("old\nfirst\nsecond\n", store.persisted);
    } finally {
      worker.shutdownNow();
    }
  }

  @Test
  public void pendingQueueRetainsTheSameBoundedTailAsTheFile() throws Exception {
    var worker = manualWriter();
    try {
      var store = new MemoryStore();
      var writer = new DiagnosticHistory.Writer(worker, store);
      String expected = store.persisted;
      for (int i = 0; i < 300; i++) {
        String line = i + " 诊断🙂".repeat(80) + "\n";
        writer.add(line);
        expected = DiagnosticHistory.append(expected, line);
      }
      assertEquals(expected, writer.read());
      assertTrue(writer.flush());
      assertEquals(expected, store.persisted);
    } finally {
      worker.shutdownNow();
    }
  }

  @Test
  public void tenThousandEventsRetainLatestCompleteLinesWithinBudget() {
    String value = "";
    for (int i = 0; i < 10000; i++) value = DiagnosticHistory.append(value, i + " 诊断事件\n");
    assertTrue(value.getBytes(StandardCharsets.UTF_8).length <= DiagnosticHistory.MAX_BYTES);
    assertTrue(value.endsWith("9999 诊断事件\n"));
    assertFalse(value.startsWith("0 诊断事件\n") || value.contains("\n0 诊断事件\n"));
  }

  @Test
  public void utf8BudgetKeepsCompleteLinesEvenWhenAlmostEveryCharacterIsMultibyte() {
    String previous = ("中文🙂".repeat(6000) + "\n");
    String value = DiagnosticHistory.append(previous, "latest 中文🙂\n");
    assertEquals("latest 中文🙂\n", value);
    assertTrue(value.getBytes(StandardCharsets.UTF_8).length <= DiagnosticHistory.MAX_BYTES);
    assertFalse(value.contains("\uFFFD"));
    String oversized = DiagnosticHistory.append("old\n", "🙂".repeat(10000));
    assertEquals("old\n[DIAGNOSTIC_EVENT_OVERSIZED]\n", oversized);
  }

  @Test
  public void symbolicLinkTargetCannotReplaceAnExternalFile() throws Exception {
    File directory = Files.createTempDirectory("diagnostic-link").toFile();
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    try {
      File target = new File(directory, "original.txt");
      Files.writeString(target.toPath(), "original\n");
      try {
        Files.createSymbolicLink(
            new File(directory, "diagnostic-events.txt").toPath(), target.toPath());
      } catch (java.nio.file.FileSystemException unavailable) {
        if (!System.getProperty("os.name").startsWith("Windows")) throw unavailable;
        org.junit.Assume.assumeNoException(
            "Windows host lacks symlink creation capability; LINK refusal has a portable test",
            unavailable);
      }
      assertThrows(
          IOException.class, () -> DiagnosticHistory.write(fs, directory, "replacement\n"));
      assertEquals("original\n", Files.readString(target.toPath()));
    } finally {
      fs.removeOwned(directory.getParentFile(), directory.getName());
    }
  }

  @Test
  public void linkMetadataRefusesTheWriteBeforeAnyTargetFileIsOpened() throws Exception {
    File directory = Files.createTempDirectory("diagnostic-link-metadata").toFile();
    JvmBackupFileSystem cleanup = new JvmBackupFileSystem();
    File target = new File(directory, "original.txt");
    try {
      Files.writeString(target.toPath(), "original\n");
      JvmBackupFileSystem fs =
          new JvmBackupFileSystem() {
            public com.deepseekharness.app.backup.BackupFileSystem.Node stat(File file)
                throws IOException {
              return file.getName().equals("diagnostic-events.txt")
                  ? new com.deepseekharness.app.backup.BackupFileSystem.Node(
                      "LINK", "link", 0, 0, 0, 0777)
                  : super.stat(file);
            }

            public java.io.InputStream read(
                File file, com.deepseekharness.app.backup.BackupFileSystem.Node expected) {
              throw new AssertionError("the linked target must never be opened");
            }
          };
      assertThrows(
          IOException.class, () -> DiagnosticHistory.write(fs, directory, "replacement\n"));
      assertEquals("original\n", Files.readString(target.toPath()));
    } finally {
      cleanup.removeOwned(directory.getParentFile(), directory.getName());
    }
  }

  @Test
  public void interruptedPublicationReadsPreviousAndNextWritePreservesHistory() throws Exception {
    File directory = Files.createTempDirectory("diagnostic-history").toFile();
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    try {
      Files.writeString(
          new File(directory, "diagnostic-events.txt.previous").toPath(), "complete old\n");
      assertEquals("complete old\n", DiagnosticHistory.read(fs, directory));
      DiagnosticHistory.write(fs, directory, "new\n");
      assertEquals("complete old\nnew\n", DiagnosticHistory.read(fs, directory));
    } finally {
      fs.removeOwned(directory.getParentFile(), directory.getName());
    }
  }

  @Test
  public void failedNewPublicationRetainsTheCompleteOldRecord() throws Exception {
    File directory = Files.createTempDirectory("diagnostic-failure").toFile();
    JvmBackupFileSystem cleanup = new JvmBackupFileSystem();
    try {
      Files.writeString(new File(directory, "diagnostic-events.txt").toPath(), "old\n");
      JvmBackupFileSystem fs =
          new JvmBackupFileSystem() {
            @Override
            public void move(File from, File to) throws IOException {
              if (from.getName().contains(".tmp-"))
                throw new IOException("INJECTED_PUBLICATION_FAILURE");
              super.move(from, to);
            }
          };
      try {
        DiagnosticHistory.write(fs, directory, "lost\n");
        fail();
      } catch (IOException expected) {
        assertEquals("INJECTED_PUBLICATION_FAILURE", expected.getMessage());
      }
      assertEquals("old\n", DiagnosticHistory.read(cleanup, directory));
    } finally {
      cleanup.removeOwned(directory.getParentFile(), directory.getName());
    }
  }

  @Test
  public void exportReadsStoredOperationRowsOnceWithoutDisplayCompaction() throws Exception {
    File directory = Files.createTempDirectory("diagnostic-export-tail").toFile();
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    try {
      StringBuilder raw = new StringBuilder();
      for (int i = 0; i < 80; i++)
        raw.append("10-23 20:30:39 [RECOVERY_FAILED] evidence-")
            .append(i)
            .append("x".repeat(120))
            .append('\n');
      raw.append("10-23 20:30:40 [STARTUP_ERROR] YAMLException: bad indentation (92:1)\n")
          .append(" 92 | private-business-config\n");
      Files.writeString(new File(directory, "diagnostic-events.txt").toPath(), raw);
      String exported = DiagnosticLog.exportTail(fs, directory);
      String[] records =
          new String(
                  com.deepseekharness.app.backup.DiagnosticExportArchive.operationEvents(exported),
                  StandardCharsets.UTF_8)
              .strip()
              .split("\\n");
      assertEquals(81, records.length);
      assertFalse(exported.contains("private-business-config"));
      assertFalse(exported.contains("Failure clues") || exported.contains("上下文"));
      assertTrue(exported.contains("evidence-0") && exported.contains("evidence-79"));
    } finally {
      fs.removeOwned(directory.getParentFile(), directory.getName());
    }
  }
}
