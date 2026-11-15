package com.deepseekharness.app.device;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.RootModulePolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** 真实只读适配器算法的内存 OS 事实夹具；不调用 Android Os 或设备命令。 */
public final class RootModuleFilesTest {
  private static final int DIRECTORY = 0040000;
  private static final int REGULAR = 0100000;

  private static final class FileFact {
    final Map<String, FileFact> children = new LinkedHashMap<>();
    RootModuleFiles.Node node;
    byte[] content = new byte[0];

    FileFact(RootModuleFiles.Node node) {
      this.node = node;
    }
  }

  private static final class MemoryHandle implements RootModuleFiles.Handle {
    final FakePort owner;
    final FileFact file;
    int offset;
    boolean closed;

    MemoryHandle(FakePort owner, FileFact file) {
      this.owner = owner;
      this.file = file;
      owner.opened++;
    }

    @Override
    public void close() {
      assertFalse("fd must be closed exactly once", closed);
      closed = true;
      owner.closed++;
    }
  }

  private static final class FakePort implements RootModuleFiles.Port {
    long nextInode = 10;
    final FileFact root = directory(0, 0, 0755);
    final FileFact data = directory(1000, 1000, 0771);
    final FileFact adb = directory(0, 0, 0700);
    final FileFact modules = directory(0, 0, 0700);
    int actualUid, opened, closed, namesRead, namesOpened, namesClosed, bytesRead, execs;
    Runnable afterRead, onNames, beforeExec;
    String[] executedArgv;
    FileFact executedFile;
    boolean rootDenied;

    FakePort() {
      root.children.put("data", data);
      data.children.put("adb", adb);
      adb.children.put("modules", modules);
    }

    RootModuleFiles.Node node(int mode, int uid, int gid, long links, long size) {
      return new RootModuleFiles.Node(1, nextInode++, mode, uid, gid, links, size, 100, 7, 100, 9);
    }

    FileFact directory(int uid, int gid, int bits) {
      return new FileFact(node(DIRECTORY | bits, uid, gid, 2, 0));
    }

    FileFact regular(byte[] value) {
      FileFact file = new FileFact(node(REGULAR | 0644, 0, 0, 1, value.length));
      file.content = value.clone();
      return file;
    }

    FileFact module(String id, int bytes) {
      FileFact directory = directory(0, 0, 0755);
      byte[] body = new byte[bytes];
      Arrays.fill(body, (byte) 'x');
      directory.children.put("module.prop", regular(body));
      modules.children.put(id, directory);
      return directory;
    }

    FileFact executable(RootModulePolicy.Manager manager) {
      String[] path = RootModulePolicy.candidate(manager).substring(1).split("/");
      FileFact cursor = root;
      for (int i = 0; i < path.length - 1; i++) {
        FileFact next = cursor.children.get(path[i]);
        if (next == null) {
          next = directory(0, 0, 0755);
          cursor.children.put(path[i], next);
        }
        cursor = next;
      }
      FileFact binary = regular(new byte[] {0x7f, 'E', 'L', 'F'});
      binary.node = replace(binary.node, REGULAR | 0755, 0, 0, 1, 4, 7);
      cursor.children.put(path[path.length - 1], binary);
      return binary;
    }

    MemoryHandle checked(RootModuleFiles.Handle handle) {
      MemoryHandle result = (MemoryHandle) handle;
      assertFalse(result.closed);
      return result;
    }

    @Override
    public int uid() {
      return actualUid;
    }

    @Override
    public RootModuleFiles.Handle root() throws IOException {
      if (rootDenied) throw new IOException("PRIVATE_PATH_MUST_NOT_ESCAPE");
      return new MemoryHandle(this, root);
    }

    @Override
    public RootModuleFiles.Handle child(
        RootModuleFiles.Handle parent, String name, RootModuleFiles.OpenKind kind)
        throws IOException {
      assertFalse(name.contains("/"));
      assertFalse(name.equals(".."));
      FileFact value = checked(parent).file.children.get(name);
      if (value == null) throw new IOException("CONTROLLED_MISSING");
      return new MemoryHandle(this, value);
    }

    @Override
    public RootModuleFiles.Node stat(RootModuleFiles.Handle handle) {
      return checked(handle).file.node;
    }

    @Override
    public RootModuleFiles.Node at(RootModuleFiles.Handle parent, String name) {
      FileFact file = checked(parent).file.children.get(name);
      return file == null ? null : file.node;
    }

    @Override
    public RootModuleFiles.Names names(RootModuleFiles.Handle directory) {
      assertSame(modules, checked(directory).file);
      namesOpened++;
      var iterator = new ArrayList<>(modules.children.keySet()).iterator();
      return new RootModuleFiles.Names() {
        @Override
        public boolean hasNext() {
          if (onNames != null) {
            var callback = onNames;
            onNames = null;
            callback.run();
          }
          return iterator.hasNext();
        }

        @Override
        public String next() {
          namesRead++;
          return iterator.next();
        }

        @Override
        public void close() {
          namesClosed++;
        }
      };
    }

    @Override
    public int read(RootModuleFiles.Handle handle, byte[] bytes, int offset, int count) {
      MemoryHandle current = checked(handle);
      int length = Math.min(count, current.file.content.length - current.offset);
      if (length > 0) {
        System.arraycopy(current.file.content, current.offset, bytes, offset, length);
        current.offset += length;
        bytesRead += length;
      }
      if (afterRead != null) {
        var callback = afterRead;
        afterRead = null;
        callback.run();
      }
      return Math.max(length, 0);
    }

    @Override
    public void execute(RootModuleFiles.Handle executable, String[] argv) {
      if (beforeExec != null) beforeExec.run();
      execs++;
      executedFile = checked(executable).file;
      executedArgv = argv.clone();
    }

    void allClosed() {
      assertEquals("all owned fds must close", opened, closed);
      assertEquals("directory iteration must close", namesOpened, namesClosed);
    }
  }

  private static RootModuleFiles.Node replace(
      RootModuleFiles.Node node, int mode, int uid, int gid, long links, long size, long nanos) {
    return new RootModuleFiles.Node(
        node.device(),
        node.inode(),
        mode,
        uid,
        gid,
        links,
        size,
        node.modifiedSeconds(),
        nanos,
        node.changedSeconds(),
        nanos);
  }

  private static RootModuleFiles files(FakePort port) {
    return new RootModuleFiles(port, () -> 1000);
  }

  @Test
  public void fixedRootAllowsSystemDataAncestorAndReadsOnlyPropertiesAndMarkerMetadata()
      throws Exception {
    FakePort port = new FakePort();
    FileFact module = port.module("sample", 6);
    module.children.get("module.prop").content = "id=one".getBytes(StandardCharsets.UTF_8);
    module.children.put("disable", port.regular(new byte[] {99, 98, 97}));
    module.children.put("action.sh", port.regular(new byte[2000]));
    RootModuleFiles.Scan scan = files(port).scan(11_000);
    assertTrue(scan.complete());
    assertEquals("LIST_COMPLETE", scan.code());
    assertEquals(1, scan.entries().size());
    var entry = scan.entries().get(0);
    assertEquals("sample", entry.id());
    assertArrayEquals("id=one".getBytes(StandardCharsets.UTF_8), entry.properties());
    assertTrue(entry.disabled());
    assertFalse(entry.removePending());
    assertFalse(entry.updatePending());
    assertEquals(6, port.bytesRead);
    byte[] copy = entry.properties();
    copy[0] = 0;
    assertEquals('i', entry.properties()[0]);
    port.allClosed();
  }

  @Test
  public void onlyCompleteVerifiedEnumerationCanProduceAnEmptyList() throws Exception {
    FakePort port = new FakePort();
    var empty = files(port).scan(11_000);
    assertTrue(empty.complete());
    assertTrue(empty.entries().isEmpty());
    port.rootDenied = true;
    IOException error = assertThrows(IOException.class, () -> files(port).scan(11_000));
    assertEquals("MODULE_ROOT_UNREADABLE", error.getMessage());
    assertFalse(error.getMessage().contains("PRIVATE"));
    port.allClosed();
  }

  @Test
  public void appOwnedWritableOrLinkedAncestorsCannotBeTraversed() throws Exception {
    for (int kind = 0; kind < 3; kind++) {
      FakePort port = new FakePort();
      var old = port.data.node;
      port.data.node =
          switch (kind) {
            case 0 -> replace(old, DIRECTORY | 0771, 10001, 1000, 2, 0, 7);
            case 1 -> replace(old, DIRECTORY | 0777, 1000, 1000, 2, 0, 7);
            default -> replace(old, 0120000 | 0777, 0, 0, 1, 4, 7);
          };
      assertEquals(
          "MODULE_ROOT_UNREADABLE",
          assertThrows(IOException.class, () -> files(port).scan(11_000)).getMessage());
      assertEquals(0, port.namesRead);
      assertEquals(0, port.bytesRead);
      port.allClosed();
    }
  }

  @Test
  public void linkedHardlinkedFifoAndSpecialPropertiesAreNeverRead() throws Exception {
    for (int kind = 0; kind < 4; kind++) {
      FakePort port = new FakePort();
      FileFact property = port.module("one", 8).children.get("module.prop");
      property.node =
          replace(
              property.node,
              kind == 0
                  ? 0120000 | 0777
                  : kind == 2 ? 0010000 | 0600 : kind == 3 ? 0020000 | 0600 : REGULAR | 0644,
              0,
              0,
              kind == 1 ? 2 : 1,
              8,
              7);
      var result = files(port).scan(11_000);
      assertFalse(result.complete());
      assertTrue(result.entries().isEmpty());
      assertEquals(0, port.bytesRead);
      port.allClosed();
    }
  }

  @Test
  public void moduleAndMarkerSymlinksAreNotFollowedAndMarkerBodyIsNeverRead() throws Exception {
    FakePort port = new FakePort();
    FileFact module = port.module("one", 4);
    var marker = port.regular(new byte[] {1, 2, 3});
    marker.node = replace(marker.node, 0120000 | 0777, 0, 0, 1, 3, 7);
    module.children.put("remove", marker);
    var result = files(port).scan(11_000);
    assertFalse(result.complete());
    assertTrue(result.entries().isEmpty());
    assertEquals(4, port.bytesRead);
    port.allClosed();
    port = new FakePort();
    module = port.module("one", 4);
    module.node = replace(module.node, 0120000 | 0777, 0, 0, 1, 4, 7);
    result = files(port).scan(11_000);
    assertFalse(result.complete());
    assertEquals(0, port.bytesRead);
    port.allClosed();
  }

  @Test
  public void enumerationStopsAtThe257thNameWithoutOpeningIt() throws Exception {
    FakePort port = new FakePort();
    for (int i = 0; i < 300; i++) port.module("module" + i, 1);
    var result = files(port).scan(11_000);
    assertFalse(result.complete());
    assertEquals(RootModulePolicy.Code.MODULE_ENTRY_LIMIT, result.reason());
    assertEquals(256, result.entries().size());
    assertEquals(257, port.namesRead);
    assertEquals(256, port.bytesRead);
    port.allClosed();
  }

  @Test
  public void propertyAndAggregateLimitsAreCheckedBeforeReadingBeyondTheBudget() throws Exception {
    FakePort large = new FakePort();
    large.module("large", RootModulePolicy.MAX_PROP_BYTES + 1);
    assertFalse(files(large).scan(11_000).complete());
    assertEquals(0, large.bytesRead);
    large.allClosed();
    FakePort total = new FakePort();
    for (int i = 0; i < 17; i++) total.module("module" + i, RootModulePolicy.MAX_PROP_BYTES);
    var result = files(total).scan(11_000);
    assertFalse(result.complete());
    assertEquals(16, result.entries().size());
    assertEquals(RootModulePolicy.MAX_TOTAL_BYTES, total.bytesRead);
    total.allClosed();
  }

  @Test
  public void malformedIdNeverBecomesAPathOperand() throws Exception {
    for (String bad : List.of("../escape", "one/two", ".", "..", "-option", "bad\0id")) {
      FakePort port = new FakePort();
      port.module(bad, 4);
      var result = files(port).scan(11_000);
      assertFalse(result.complete());
      assertEquals(RootModulePolicy.Code.MODULE_ID_INVALID, result.reason());
      assertTrue(result.entries().isEmpty());
      assertEquals(0, port.bytesRead);
      port.allClosed();
    }
  }

  @Test
  public void propertyMutationAfterReadDoesNotReturnItsOldBytesAsComplete() throws Exception {
    FakePort port = new FakePort();
    FileFact property = port.module("one", 4).children.get("module.prop");
    port.afterRead = () -> property.node = replace(property.node, REGULAR | 0644, 0, 0, 1, 4, 8);
    var result = files(port).scan(11_000);
    assertFalse(result.complete());
    assertEquals(RootModulePolicy.Code.MODULE_SOURCE_CHANGED, result.reason());
    assertTrue(result.entries().isEmpty());
    port.allClosed();
  }

  @Test
  public void directoryReplacementAfterCaptureInvalidatesEvenThePreviouslyReadEntries()
      throws Exception {
    FakePort port = new FakePort();
    port.module("one", 4);
    port.afterRead = () -> port.adb.children.put("modules", port.directory(0, 0, 0700));
    var result = files(port).scan(11_000);
    assertFalse(result.complete());
    assertEquals(RootModulePolicy.Code.MODULE_SOURCE_CHANGED, result.reason());
    assertTrue(result.entries().isEmpty());
    port.allClosed();
  }

  @Test
  public void exactRootMtimeChangeCannotBeReportedAsACompleteEnumeration() throws Exception {
    FakePort port = new FakePort();
    port.module("one", 4);
    port.afterRead =
        () -> port.modules.node = replace(port.modules.node, DIRECTORY | 0700, 0, 0, 2, 0, 8);
    var result = files(port).scan(11_000);
    assertFalse(result.complete());
    assertEquals(RootModulePolicy.Code.MODULE_SOURCE_CHANGED, result.reason());
    assertTrue(result.entries().isEmpty());
    port.allClosed();
  }

  @Test
  public void deadlineAndReentrantBusyReadHaveNoSilentRetry() throws Exception {
    FakePort expired = new FakePort();
    assertEquals(
        "MODULE_DEADLINE_EXCEEDED",
        assertThrows(IOException.class, () -> files(expired).scan(1000)).getMessage());
    assertEquals(0, expired.opened);
    FakePort port = new FakePort();
    AtomicLong clock = new AtomicLong(1000);
    RootModuleFiles files = new RootModuleFiles(port, clock::get);
    port.module("one", 4);
    port.afterRead =
        () -> {
          assertEquals(
              "ROOT_MODULE_READ_BUSY",
              assertThrows(IOException.class, () -> files.scan(11_000)).getMessage());
          clock.set(11_001);
        };
    var result = files.scan(11_000);
    assertFalse(result.complete());
    assertEquals(RootModulePolicy.Code.MODULE_DEADLINE_EXCEEDED, result.reason());
    assertTrue(result.entries().isEmpty());
    assertEquals(1, port.namesRead);
    port.allClosed();
  }

  @Test
  public void interruptionIsNotFoldedIntoARootUnreadablePartial() throws Exception {
    FakePort port = new FakePort();
    port.module("one", 4);
    port.afterRead = () -> Thread.currentThread().interrupt();
    try {
      IOException cancelled = assertThrows(IOException.class, () -> files(port).scan(11_000));
      assertEquals("ROOT_MODULE_SCAN_CANCELLED", cancelled.getMessage());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    port.allClosed();
  }

  @Test
  public void scanRejectsAmbiguousOrIncompleteReasonWithoutFailureCode() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RootModuleFiles.Scan(List.of(), false, RootModulePolicy.Code.LIST_PARTIAL));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RootModuleFiles.Scan(List.of(), true, RootModulePolicy.Code.UNKNOWN));
  }

  @Test
  public void rootIdentityIsRequiredBeforeAnyFilesAreOpened() {
    FakePort port = new FakePort();
    port.actualUid = 2000;
    assertEquals(
        "ROOT_IDENTITY_UNVERIFIED",
        assertThrows(IOException.class, () -> files(port).scan(11_000)).getMessage());
    assertEquals(0, port.opened);
  }

  @Test
  public void probeExecReceivesOnlyTheHeldCandidateAndPolicyGeneratedArgv() throws Exception {
    for (RootModulePolicy.Manager manager : RootModulePolicy.Manager.values()) {
      for (RootModulePolicy.Probe probe : RootModulePolicy.probes(manager)) {
        FakePort port = new FakePort();
        FileFact binary = port.executable(manager);
        assertEquals(
            "ROOT_MODULE_PROBE_EXEC_RETURNED",
            assertThrows(IOException.class, () -> RootModuleFiles.execProbe(port, manager, probe))
                .getMessage());
        assertEquals(1, port.execs);
        assertSame(binary, port.executedFile);
        assertArrayEquals(
            RootModulePolicy.argv(manager, probe).toArray(String[]::new), port.executedArgv);
        port.allClosed();
      }
    }
  }

  @Test
  public void probeRejectsLinkedWritableHardlinkedOrNonExecutableCandidate() throws Exception {
    for (int kind = 0; kind < 4; kind++) {
      FakePort port = new FakePort();
      FileFact binary = port.executable(RootModulePolicy.Manager.APATCH);
      binary.node =
          replace(
              binary.node,
              kind == 0
                  ? 0120000 | 0777
                  : kind == 1 ? REGULAR | 0775 : kind == 3 ? REGULAR | 0644 : REGULAR | 0755,
              0,
              0,
              kind == 2 ? 2 : 1,
              4,
              7);
      assertThrows(
          IOException.class,
          () ->
              RootModuleFiles.execProbe(
                  port, RootModulePolicy.Manager.APATCH, RootModulePolicy.Probe.VERSION));
      assertEquals(0, port.execs);
      port.allClosed();
    }
  }

  @Test
  public void probeInvocationIsClosedAndInstallationMustBeVerifiedBeforeExec() throws Exception {
    List<String> calls = new ArrayList<>();
    RootModuleProbeMain.Invocation invocation =
        new RootModuleProbeMain.Invocation() {
          @Override
          public int executionUid() {
            return 0;
          }

          @Override
          public void verifyInstallation(String name, int uid) {
            calls.add("identity:" + name + ":" + uid);
          }

          @Override
          public void execute(RootModulePolicy.Manager manager, RootModulePolicy.Probe probe) {
            calls.add(manager.name() + ":" + probe.name());
          }
        };
    assertEquals(
        "ROOT_MODULE_PROBE_EXEC_RETURNED",
        assertThrows(
                IOException.class,
                () ->
                    RootModuleProbeMain.invoke(
                        new String[] {"com.example.test", "10123", "KERNELSU", "VERSION"},
                        invocation))
            .getMessage());
    assertEquals(List.of("identity:com.example.test:10123", "KERNELSU:VERSION"), calls);
    for (String[] bad :
        List.of(
            new String[] {"com." + "x".repeat(160), "10123", "APATCH", "VERSION"},
            new String[] {"com.example.test", "10123", "APATCH", "LOCAL_VERSION"},
            new String[] {"com.example.test", "2000", "APATCH", "VERSION"},
            new String[] {"com.example.test", "10123", "/data/adb/apd", "VERSION"},
            new String[] {"com.example.test", "10123", "APATCH", "-V"},
            new String[] {"com.example.test", "10123", "APATCH", "VERSION", "module list"})) {
      calls.clear();
      assertThrows(IOException.class, () -> RootModuleProbeMain.invoke(bad, invocation));
      assertTrue(calls.isEmpty());
    }
  }

  @Test
  public void probeNeverExecutesWhenActualUidOrCurrentInstallationIsUnverified() {
    for (int kind = 0; kind < 2; kind++) {
      final int rejection = kind;
      RootModuleProbeMain.Invocation invocation =
          new RootModuleProbeMain.Invocation() {
            @Override
            public int executionUid() {
              return rejection == 0 ? 2000 : 0;
            }

            @Override
            public void verifyInstallation(String name, int uid) throws IOException {
              throw new IOException("SELF_PACKAGE_SOURCE_UNVERIFIED");
            }

            @Override
            public void execute(RootModulePolicy.Manager manager, RootModulePolicy.Probe probe) {
              fail("unverified invocation must not execute");
            }
          };
      assertThrows(
          IOException.class,
          () ->
              RootModuleProbeMain.invoke(
                  new String[] {"com.example.test", "10123", "APATCH", "VERSION"}, invocation));
    }
  }
}
