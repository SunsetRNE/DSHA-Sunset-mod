package com.deepseekharness.app.device;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.deepseekharness.app.util.RootModulePolicy;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** F07 专用只读 fd 适配器；生产入口只认固定模块根，不接受调用方路径。 */
public final class RootModuleFiles {
  private static final int O_PATH = 0x200000;
  private static final int O_CLOEXEC = 0x80000;
  private static final int TYPE_MASK = 0170000;
  private static final int DIRECTORY = 0040000;
  private static final int REGULAR = 0100000;
  private static final AtomicBoolean PRODUCTION_READING = new AtomicBoolean();

  /** 原始属性只交给纯 parser，不在设备侧解释脚本、转义或可执行配置。 */
  public record Entry(
      String id,
      byte[] properties,
      boolean disabled,
      boolean removePending,
      boolean updatePending,
      long modifiedMillis) {
    public Entry {
      Objects.requireNonNull(id, "id");
      properties = properties.clone();
    }

    @Override
    public byte[] properties() {
      return properties.clone();
    }
  }

  /** partial 时 reason 必须是具体失败码；不能被调用方改写成 UNKNOWN 或折成空完整清单。 */
  public record Scan(List<Entry> entries, boolean complete, RootModulePolicy.Code reason) {
    public Scan {
      entries = List.copyOf(entries);
      if (complete ? reason != null : !RootModulePolicy.failureReason(reason))
        throw new IllegalArgumentException("ROOT_MODULE_SCAN_INVALID");
    }

    public String code() {
      return complete ? "LIST_COMPLETE" : "LIST_PARTIAL";
    }
  }

  /** 仅供同包宿主夹具注入；生产构造器不能切换根或实际 Android 端口。 */
  interface Handle extends AutoCloseable {
    @Override
    void close() throws IOException;
  }

  interface Names extends AutoCloseable {
    boolean hasNext() throws IOException;

    String next() throws IOException;

    @Override
    void close() throws IOException;
  }

  enum OpenKind {
    DIRECTORY,
    PROPERTY,
    EXECUTABLE
  }

  interface Port {
    int uid();

    Handle root() throws IOException;

    Handle child(Handle parent, String name, OpenKind kind) throws IOException;

    Node stat(Handle handle) throws IOException;

    Node at(Handle parent, String name) throws IOException;

    Names names(Handle directory) throws IOException;

    int read(Handle handle, byte[] bytes, int offset, int count) throws IOException;

    void execute(Handle executable, String[] argv) throws IOException;
  }

  /** 不含 atime，读取自身引起的访问时间变化不伪装成内容变更。 */
  record Node(
      long device,
      long inode,
      int mode,
      int uid,
      int gid,
      long links,
      long size,
      long modifiedSeconds,
      long modifiedNanos,
      long changedSeconds,
      long changedNanos) {
    boolean directory() {
      return (mode & TYPE_MASK) == DIRECTORY;
    }

    boolean regular() {
      return (mode & TYPE_MASK) == REGULAR;
    }

    boolean object(Node other) {
      return other != null
          && device == other.device
          && inode == other.inode
          && mode == other.mode
          && uid == other.uid
          && gid == other.gid
          && links == other.links;
    }

    long modifiedMillis() {
      try {
        return Math.addExact(
            Math.multiplyExact(modifiedSeconds, 1000L), modifiedNanos / 1_000_000L);
      } catch (ArithmeticException invalid) {
        return -1;
      }
    }
  }

  private final Port port;
  private final LongSupplier clock;
  private final AtomicBoolean reading;

  public RootModuleFiles() {
    this(new AndroidPort(), SystemClock::elapsedRealtime, PRODUCTION_READING);
  }

  RootModuleFiles(Port port, LongSupplier clock) {
    this(port, clock, new AtomicBoolean());
  }

  private RootModuleFiles(Port port, LongSupplier clock, AtomicBoolean reading) {
    this.port = Objects.requireNonNull(port, "port");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.reading = reading;
  }

  /** deadline 是调用方共享的单调时钟截止；本层最多再接纳十秒，不自动重试。 */
  public Scan scan(long deadlineElapsedMillis) throws IOException {
    if (port.uid() != 0) throw failure("ROOT_IDENTITY_UNVERIFIED");
    if (!reading.compareAndSet(false, true)) throw failure("ROOT_MODULE_READ_BUSY");
    try {
      long now = clock.getAsLong();
      long limit =
          now > Long.MAX_VALUE - RootModulePolicy.DEADLINE_MS
              ? Long.MAX_VALUE
              : now + RootModulePolicy.DEADLINE_MS;
      long deadline = Math.min(deadlineElapsedMillis, limit);
      checkDeadline(deadline);
      try (Chain root = chain(RootModulePolicy.MODULE_ROOT, OpenKind.DIRECTORY)) {
        Node rootBefore = port.stat(root.last());
        requireOwnedDirectory(rootBefore);
        root.verify();
        List<Entry> entries = new ArrayList<>();
        List<Receipt> receipts = new ArrayList<>();
        int attempted = 0, propertyBytes = 0;
        boolean complete = true;
        RootModulePolicy.Code reason = null;
        try (Names names = port.names(root.last())) {
          while (true) {
            checkDeadline(deadline);
            if (!names.hasNext()) break;
            checkDeadline(deadline);
            String id = names.next();
            if (++attempted > RootModulePolicy.MAX_ENTRIES) {
              complete = false;
              reason = RootModulePolicy.Code.MODULE_ENTRY_LIMIT;
              break;
            }
            if (!validId(id)) {
              complete = false;
              reason = RootModulePolicy.Code.MODULE_ID_INVALID;
              break;
            }
            try {
              Receipt receipt =
                  readEntry(
                      root.last(), id, deadline, RootModulePolicy.MAX_TOTAL_BYTES - propertyBytes);
              propertyBytes += receipt.entry.properties.length;
              entries.add(receipt.entry);
              receipts.add(receipt);
            } catch (InterruptedIOException cancelled) {
              throw cancelled;
            } catch (IOException rejected) {
              complete = false;
              reason = failureReason(rejected);
              break;
            }
          }
        } catch (InterruptedIOException cancelled) {
          throw cancelled;
        } catch (IOException rejected) {
          complete = false;
          reason = RootModulePolicy.Code.MODULE_ROOT_UNREADABLE;
        }
        // 任何父链、根目录或已读条目的变更都不能附带 complete 的旧正文。
        try {
          checkDeadline(deadline);
          root.verify();
          if (!rootBefore.equals(port.stat(root.last()))) throw failure("MODULE_SOURCE_CHANGED");
          for (Receipt receipt : receipts) {
            checkDeadline(deadline);
            verifyEntry(root.last(), receipt);
          }
        } catch (InterruptedIOException cancelled) {
          throw cancelled;
        } catch (IOException changed) {
          return new Scan(List.of(), false, failureReason(changed));
        }
        entries.sort(Comparator.comparing(Entry::id));
        return new Scan(entries, complete, complete ? null : reason);
      } catch (InterruptedIOException cancelled) {
        throw cancelled;
      } catch (IOException unreadable) {
        // deadline/换链是可判定的失败原因，不能折成根目录不可读；其余错误不暴露真实路径。
        if (deadlineOrSourceChanged(unreadable)) throw unreadable;
        throw failure("MODULE_ROOT_UNREADABLE");
      }
    } finally {
      reading.set(false);
    }
  }

  private Receipt readEntry(Handle root, String id, long deadline, int remaining)
      throws IOException {
    checkDeadline(deadline);
    Node directoryBefore = port.at(root, id);
    requireOwnedDirectory(directoryBefore);
    try (Handle directory = port.child(root, id, OpenKind.DIRECTORY)) {
      if (!directoryBefore.equals(port.stat(directory))) throw failure("MODULE_SOURCE_CHANGED");
      Node propertyBefore = port.at(directory, "module.prop");
      requireOwnedFile(propertyBefore, false);
      if (propertyBefore.size < 0
          || propertyBefore.size > RootModulePolicy.MAX_PROP_BYTES
          || propertyBefore.size > remaining) throw failure("MODULE_PROP_LIMIT");
      byte[] properties = new byte[(int) propertyBefore.size];
      try (Handle property = port.child(directory, "module.prop", OpenKind.PROPERTY)) {
        if (!propertyBefore.equals(port.stat(property))) throw failure("MODULE_SOURCE_CHANGED");
        int offset = 0;
        while (offset < properties.length) {
          checkDeadline(deadline);
          int count = port.read(property, properties, offset, properties.length - offset);
          checkDeadline(deadline);
          if (count <= 0 || count > properties.length - offset)
            throw failure("MODULE_SOURCE_CHANGED");
          offset += count;
        }
        if (!propertyBefore.equals(port.stat(property))
            || !propertyBefore.equals(port.at(directory, "module.prop")))
          throw failure("MODULE_SOURCE_CHANGED");
      }
      Node disable = marker(directory, "disable"),
          remove = marker(directory, "remove"),
          update = marker(directory, "update");
      if (!directoryBefore.equals(port.stat(directory))
          || !directoryBefore.equals(port.at(root, id))) throw failure("MODULE_SOURCE_CHANGED");
      return new Receipt(
          new Entry(
              id,
              properties,
              disable != null,
              remove != null,
              update != null,
              directoryBefore.modifiedMillis()),
          directoryBefore,
          propertyBefore,
          disable,
          remove,
          update);
    }
  }

  private record Receipt(
      Entry entry, Node directory, Node property, Node disable, Node remove, Node update) {}

  private void verifyEntry(Handle root, Receipt receipt) throws IOException {
    String id = receipt.entry.id;
    if (!receipt.directory.equals(port.at(root, id))) throw failure("MODULE_SOURCE_CHANGED");
    try (Handle directory = port.child(root, id, OpenKind.DIRECTORY)) {
      if (!receipt.directory.equals(port.stat(directory))
          || !receipt.property.equals(port.at(directory, "module.prop"))
          || !Objects.equals(receipt.disable, marker(directory, "disable"))
          || !Objects.equals(receipt.remove, marker(directory, "remove"))
          || !Objects.equals(receipt.update, marker(directory, "update")))
        throw failure("MODULE_SOURCE_CHANGED");
    }
  }

  private Node marker(Handle directory, String name) throws IOException {
    Node value = port.at(directory, name);
    if (value != null) requireOwnedFile(value, false);
    return value;
  }

  private void checkDeadline(long deadline) throws IOException {
    if (Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("ROOT_MODULE_SCAN_CANCELLED");
    long now = clock.getAsLong();
    if (now < 0 || now >= deadline) throw failure("MODULE_DEADLINE_EXCEEDED");
  }

  /** 设备侧开放字符串不能当原因透传；只接受枚举中的具体失败码。 */
  private static RootModulePolicy.Code failureReason(IOException error) {
    String message = error.getMessage();
    if (message != null) {
      try {
        RootModulePolicy.Code code = RootModulePolicy.Code.valueOf(message);
        if (RootModulePolicy.failureReason(code)) return code;
      } catch (IllegalArgumentException unknown) {
        // 未知开放字符串统一折成目录不可读，不回显路径。
      }
    }
    return RootModulePolicy.Code.MODULE_ROOT_UNREADABLE;
  }

  private static boolean deadlineOrSourceChanged(IOException error) {
    String message = error.getMessage();
    return "MODULE_DEADLINE_EXCEEDED".equals(message) || "MODULE_SOURCE_CHANGED".equals(message);
  }

  private static boolean validId(String id) {
    return id != null && id.matches("[A-Za-z][A-Za-z0-9._-]{0,127}");
  }

  private static void requireTrustedDirectory(Node node) throws IOException {
    if (node == null
        || !node.directory()
        || !trusted(node.uid)
        || !trusted(node.gid)
        || (node.mode & 0002) != 0) throw failure("MODULE_SOURCE_UNSAFE");
  }

  private static boolean trusted(int uid) {
    return uid == 0 || uid == 1000;
  }

  private static void requireOwnedDirectory(Node node) throws IOException {
    requireTrustedDirectory(node);
    if (node.uid != 0 || (node.mode & 0022) != 0) throw failure("MODULE_SOURCE_UNSAFE");
  }

  private static void requireOwnedFile(Node node, boolean executable) throws IOException {
    if (node == null
        || !node.regular()
        || node.uid != 0
        || !trusted(node.gid)
        || node.links != 1
        || (node.mode & 0022) != 0
        || executable && ((node.mode & 0111) == 0 || node.size <= 0))
      throw failure("MODULE_SOURCE_UNSAFE");
  }

  private Chain chain(String fixedPath, OpenKind leafKind) throws IOException {
    if (!fixedPath.equals(RootModulePolicy.MODULE_ROOT)
        && java.util.Arrays.stream(RootModulePolicy.Manager.values())
            .noneMatch(manager -> RootModulePolicy.candidate(manager).equals(fixedPath)))
      throw failure("ROOT_MODULE_FIXED_PATH_REQUIRED");
    Chain chain = new Chain();
    try {
      Handle root = port.root();
      chain.handles.add(root);
      Node rootNode = port.stat(root);
      requireTrustedDirectory(rootNode);
      chain.nodes.add(rootNode);
      String[] names = fixedPath.substring(1).split("/");
      for (int i = 0; i < names.length; i++) {
        String name = names[i];
        if (name.isEmpty() || name.equals(".") || name.equals(".."))
          throw failure("ROOT_MODULE_FIXED_PATH_REQUIRED");
        Handle parent = chain.last();
        Node before = port.at(parent, name);
        OpenKind kind = i + 1 == names.length ? leafKind : OpenKind.DIRECTORY;
        if (kind == OpenKind.DIRECTORY) requireTrustedDirectory(before);
        else requireOwnedFile(before, kind == OpenKind.EXECUTABLE);
        Handle next = port.child(parent, name, kind);
        chain.handles.add(next);
        chain.names.add(name);
        Node actual = port.stat(next);
        chain.nodes.add(actual);
        if (!before.equals(actual)) throw failure("MODULE_SOURCE_CHANGED");
      }
      chain.verify();
      return chain;
    } catch (IOException | RuntimeException failure) {
      try {
        chain.close();
      } catch (IOException closing) {
        failure.addSuppressed(closing);
      }
      throw failure;
    }
  }

  private final class Chain implements AutoCloseable {
    final List<Handle> handles = new ArrayList<>();
    final List<Node> nodes = new ArrayList<>();
    final List<String> names = new ArrayList<>();

    Handle last() {
      return handles.get(handles.size() - 1);
    }

    void verify() throws IOException {
      for (int i = 0; i < handles.size(); i++) {
        if (!nodes.get(i).object(port.stat(handles.get(i)))) throw failure("MODULE_SOURCE_CHANGED");
        if (i > 0 && !nodes.get(i).object(port.at(handles.get(i - 1), names.get(i - 1))))
          throw failure("MODULE_SOURCE_CHANGED");
      }
    }

    @Override
    public void close() throws IOException {
      IOException first = null;
      for (int i = handles.size() - 1; i >= 0; i--) {
        try {
          handles.get(i).close();
        } catch (IOException error) {
          if (first == null) first = error;
          else first.addSuppressed(error);
        }
      }
      handles.clear();
      if (first != null) throw first;
    }
  }

  /** 仅固定 probe 子入口调用；fd 一直由本调用持有，exec 不重新解析原候选路径。 */
  static void execProbe(RootModulePolicy.Manager manager, RootModulePolicy.Probe probe)
      throws IOException {
    execProbe(new AndroidPort(), manager, probe);
  }

  static void execProbe(Port port, RootModulePolicy.Manager manager, RootModulePolicy.Probe probe)
      throws IOException {
    if (port.uid() != 0) throw failure("ROOT_IDENTITY_UNVERIFIED");
    List<String> argv = RootModulePolicy.argv(manager, probe);
    RootModuleFiles files = new RootModuleFiles(port, () -> 0);
    try (Chain executable = files.chain(RootModulePolicy.candidate(manager), OpenKind.EXECUTABLE)) {
      executable.verify();
      Node before = executable.nodes.get(executable.nodes.size() - 1);
      if (!before.equals(port.stat(executable.last()))) throw failure("MODULE_SOURCE_CHANGED");
      port.execute(executable.last(), argv.toArray(new String[0]));
      throw failure("ROOT_MODULE_PROBE_EXEC_RETURNED");
    }
  }

  private static IOException failure(String code) {
    return new IOException(code);
  }

  private static final class AndroidHandle implements Handle {
    private FileDescriptor fd;

    AndroidHandle(FileDescriptor fd) {
      this.fd = fd;
    }

    FileDescriptor fd() throws IOException {
      if (fd == null) throw failure("ROOT_MODULE_FD_CLOSED");
      return fd;
    }

    @Override
    public void close() throws IOException {
      FileDescriptor closing = fd;
      fd = null;
      if (closing != null) {
        try {
          Os.close(closing);
        } catch (ErrnoException error) {
          throw failure("ROOT_MODULE_FD_CLOSE_FAILED");
        }
      }
    }
  }

  private static final class AndroidPort implements Port {
    @Override
    public int uid() {
      return android.os.Process.myUid();
    }

    private FileDescriptor fd(Handle handle) throws IOException {
      if (!(handle instanceof AndroidHandle android)) throw failure("ROOT_MODULE_FD_INVALID");
      return android.fd();
    }

    @Override
    public Handle root() throws IOException {
      try {
        return new AndroidHandle(Os.open("/", O_PATH | O_CLOEXEC | OsConstants.O_NOFOLLOW, 0));
      } catch (ErrnoException error) {
        throw failure("ROOT_MODULE_OPEN_FAILED");
      }
    }

    @Override
    public Handle child(Handle parent, String name, OpenKind kind) throws IOException {
      if (name.isEmpty()
          || name.contains("/")
          || name.equals(".")
          || name.equals("..")
          || name.indexOf('\0') >= 0) throw failure("ROOT_MODULE_NAME_INVALID");
      int flags =
          O_CLOEXEC
              | OsConstants.O_NOFOLLOW
              | (kind == OpenKind.PROPERTY
                  ? OsConstants.O_RDONLY | OsConstants.O_NONBLOCK
                  : O_PATH);
      try (ParcelFileDescriptor directory = ParcelFileDescriptor.dup(fd(parent))) {
        return new AndroidHandle(
            Os.open("/proc/self/fd/" + directory.getFd() + "/" + name, flags, 0));
      } catch (ErrnoException error) {
        throw failure("ROOT_MODULE_OPEN_FAILED");
      }
    }

    @Override
    public Node stat(Handle handle) throws IOException {
      try {
        return node(Os.fstat(fd(handle)));
      } catch (ErrnoException error) {
        throw failure("ROOT_MODULE_STAT_FAILED");
      }
    }

    @Override
    public Node at(Handle parent, String name) throws IOException {
      try (ParcelFileDescriptor directory = ParcelFileDescriptor.dup(fd(parent))) {
        try {
          return node(Os.lstat("/proc/self/fd/" + directory.getFd() + "/" + name));
        } catch (ErrnoException error) {
          if (error.errno == OsConstants.ENOENT) return null;
          throw failure("ROOT_MODULE_STAT_FAILED");
        }
      }
    }

    @Override
    public Names names(Handle directory) throws IOException {
      ParcelFileDescriptor retained = ParcelFileDescriptor.dup(fd(directory));
      DirectoryStream<Path> stream;
      try {
        stream = Files.newDirectoryStream(Paths.get("/proc/self/fd/" + retained.getFd()));
      } catch (IOException | RuntimeException error) {
        retained.close();
        throw failure("ROOT_MODULE_DIRECTORY_UNREADABLE");
      }
      Iterator<Path> iterator;
      try {
        iterator = stream.iterator();
      } catch (RuntimeException invalid) {
        try {
          stream.close();
        } finally {
          retained.close();
        }
        throw failure("ROOT_MODULE_DIRECTORY_UNREADABLE");
      }
      return new Names() {
        @Override
        public boolean hasNext() throws IOException {
          try {
            return iterator.hasNext();
          } catch (RuntimeException error) {
            throw failure("ROOT_MODULE_DIRECTORY_UNREADABLE");
          }
        }

        @Override
        public String next() throws IOException {
          try {
            return iterator.next().getFileName().toString();
          } catch (RuntimeException error) {
            throw failure("ROOT_MODULE_DIRECTORY_UNREADABLE");
          }
        }

        @Override
        public void close() throws IOException {
          try {
            stream.close();
          } finally {
            retained.close();
          }
        }
      };
    }

    @Override
    public int read(Handle handle, byte[] bytes, int offset, int count) throws IOException {
      try {
        return Os.read(fd(handle), bytes, offset, count);
      } catch (ErrnoException error) {
        throw failure("ROOT_MODULE_READ_FAILED");
      }
    }

    @Override
    public void execute(Handle executable, String[] argv) throws IOException {
      try (ParcelFileDescriptor retained = ParcelFileDescriptor.dup(fd(executable))) {
        Os.fcntlInt(retained.getFileDescriptor(), OsConstants.F_SETFD, OsConstants.FD_CLOEXEC);
        Os.execv("/proc/self/fd/" + retained.getFd(), argv);
      } catch (ErrnoException error) {
        throw failure("ROOT_MODULE_PROBE_EXEC_FAILED");
      }
    }

    private static Node node(StructStat value) {
      return new Node(
          value.st_dev,
          value.st_ino,
          value.st_mode,
          value.st_uid,
          value.st_gid,
          value.st_nlink,
          value.st_size,
          value.st_mtim.tv_sec,
          value.st_mtim.tv_nsec,
          value.st_ctim.tv_sec,
          value.st_ctim.tv_nsec);
    }
  }
}
