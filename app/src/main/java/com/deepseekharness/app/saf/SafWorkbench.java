package com.deepseekharness.app.saf;

import android.content.Context;
import android.os.SystemClock;
import com.deepseekharness.app.util.RuntimeWorkPort;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** 原生文件工作台：每次 IO 重新查授权；对话框只持有内存中的限时能力，不持有可复用的树。 */
public final class SafWorkbench {
  /** 只在当前工作台实例内有效，不进 Bundle、Intent、传输记录或桥协议。 */
  public static final class Ticket {
    public final String treeId;
    public final String path;
    public final SafCapabilityHandles.Operation operation;
    private final String token;

    private Ticket(
        String treeId, String path, SafCapabilityHandles.Operation operation, String token) {
      this.treeId = treeId;
      this.path = path;
      this.operation = operation;
      this.token = token;
    }
  }

  private final Context context;
  private final java.util.function.Supplier<File> rootfs;
  private final SafCapabilityHandles handles = new SafCapabilityHandles();

  public SafWorkbench(Context context, java.util.function.Supplier<File> rootfs) {
    this.context = context.getApplicationContext();
    this.rootfs = rootfs;
  }

  public Ticket prepare(String treeId, String path, boolean importing) throws SafTree.Failure {
    return issue(
        treeId,
        path,
        importing ? SafCapabilityHandles.Operation.READ : SafCapabilityHandles.Operation.WRITE);
  }

  /** 输入目标文件名后仍复核面板打开时的代次，不能用重授权悄悄续期旧选择。 */
  public Ticket retarget(Ticket selection, String path) throws SafTree.Failure {
    SafGrantRecord expected = check(selection, false);
    SafGrantLedger ledger = SafGrants.capabilitySnapshot(context, selection.treeId);
    SafGrantRecord current = ledger.findById(selection.treeId);
    if (current == null || current.epoch() != expected.epoch())
      throw new SafTree.Failure("HANDLE_REVOKED", null);
    String clean = SafTree.normalize(path);
    SafCapabilityHandles.Issued issued =
        handles.issue(
            ledger, selection.treeId, selection.operation, clean, SystemClock.elapsedRealtime());
    if (!issued.ok()) throw new SafTree.Failure("HANDLE_" + issued.result.name(), null);
    Ticket result = new Ticket(selection.treeId, clean, selection.operation, issued.token);
    cancel(selection);
    return result;
  }

  public void cancel(Ticket ticket) {
    if (ticket != null) handles.discard(ticket.token);
  }

  private Ticket issue(String treeId, String path, SafCapabilityHandles.Operation operation)
      throws SafTree.Failure {
    String clean = SafTree.normalize(path);
    SafCapabilityHandles.Issued issued =
        handles.issue(
            SafGrants.capabilitySnapshot(context, treeId),
            treeId,
            operation,
            clean,
            SystemClock.elapsedRealtime());
    if (!issued.ok()) throw new SafTree.Failure("HANDLE_" + issued.result.name(), null);
    return new Ticket(treeId, clean, operation, issued.token);
  }

  private SafGrantRecord check(Ticket ticket, boolean consume) throws SafTree.Failure {
    if (ticket == null) throw new SafTree.Failure("HANDLE_UNKNOWN", null);
    SafGrantLedger ledger = SafGrants.capabilitySnapshot(context, ticket.treeId);
    SafCapabilityHandles.Result result =
        consume
            ? handles.consume(
                ledger, ticket.token, ticket.operation, ticket.path, SystemClock.elapsedRealtime())
            : handles.check(
                ledger, ticket.token, ticket.operation, ticket.path, SystemClock.elapsedRealtime());
    if (result != SafCapabilityHandles.Result.OK)
      throw new SafTree.Failure("HANDLE_" + result.name(), null);
    return ledger.findById(ticket.treeId);
  }

  private SafTree tree(SafGrantRecord grant) throws SafTree.Failure {
    return new SafTree(context.getContentResolver(), android.net.Uri.parse(grant.uri()));
  }

  public SafTree.Page list(String treeId, String path) throws SafTree.Failure {
    Ticket ticket = issue(treeId, path, SafCapabilityHandles.Operation.READ);
    try {
      SafTree.Page result =
          tree(check(ticket, false)).list(ticket.path, SafCapabilityLimits.MAX_ENTRIES);
      check(ticket, false);
      return result;
    } finally {
      cancel(ticket);
    }
  }

  public byte[] read(String treeId, String path, int max) throws SafTree.Failure {
    Ticket ticket = issue(treeId, path, SafCapabilityHandles.Operation.READ);
    try {
      SafTree grant = tree(check(ticket, false));
      byte[] data = grant.read(ticket.path, 0, max);
      check(ticket, false);
      return data;
    } finally {
      cancel(ticket);
    }
  }

  /** 一次性消费本次传输能力；失败或中断不会自动重放，重新操作必须重新签发。 */
  public long transfer(Ticket ticket, String guestPath, boolean importing) throws Exception {
    if (ticket == null
        || ticket.operation
            != (importing
                ? SafCapabilityHandles.Operation.READ
                : SafCapabilityHandles.Operation.WRITE))
      throw new SafTree.Failure("HANDLE_MISMATCH", null);
    if (!SafTransferGate.TRANSFERS.tryAcquire()) throw new SafTree.Failure("BUSY", null);
    try (RuntimeWorkPort.Work ignored = RuntimeWorkPort.begin("跨应用文件传输")) {
      SafGrantRecord grant = check(ticket, true);
      long deadline = SystemClock.elapsedRealtime() + SafCapabilityLimits.DEADLINE_MS;
      File local = guestFile(guestPath);
      return importing
          ? pull(ticket, grant, local, deadline)
          : push(ticket, grant, local, deadline);
    } finally {
      SafTransferGate.TRANSFERS.release();
    }
  }

  private void running(Ticket ticket, SafGrantRecord expected, long deadline) throws IOException {
    if (Thread.currentThread().isInterrupted()) throw new IOException("CANCELLED");
    if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("TIMEOUT");
    try {
      SafGrantRecord actual =
          SafGrants.capabilitySnapshot(context, ticket.treeId).findById(ticket.treeId);
      if (actual == null || actual.epoch() != expected.epoch())
        throw new IOException("HANDLE_REVOKED");
      if (ticket.operation == SafCapabilityHandles.Operation.READ
          ? !actual.canRead()
          : !actual.usableWrite()) throw new IOException("PERMISSION_DENIED");
    } catch (SafTree.Failure failure) {
      throw new IOException(failure.code, failure);
    }
  }

  private long push(Ticket ticket, SafGrantRecord grant, File source, long deadline)
      throws Exception {
    long size = source.length();
    if (size > SafCapabilityLimits.MAX_WRITE_BYTES) throw new IOException("TOO_LARGE");
    running(ticket, grant, deadline);
    try (InputStream checked =
        new FilterInputStream(SafLocalFiles.openRead(source, pathCheck())) {
          @Override
          public int read(byte[] bytes, int offset, int count) throws IOException {
            running(ticket, grant, deadline);
            return super.read(bytes, offset, count);
          }
        }) {
      long bytes = tree(grant).writeStream(ticket.path, checked, size, true);
      running(ticket, grant, deadline);
      return bytes;
    }
  }

  private long pull(Ticket ticket, SafGrantRecord grant, File target, long deadline)
      throws Exception {
    SafTree tree = tree(grant);
    SafTree.Entry source = tree.stat(ticket.path);
    if (source.directory) throw new IOException("IS_DIRECTORY");
    if (source.size > SafCapabilityLimits.MAX_WRITE_BYTES) throw new IOException("TOO_LARGE");
    File parent = target.getParentFile();
    if (parent == null || target.isDirectory()) throw new IOException("BAD_PATH");
    String temporary = ".saf-workbench-" + UUID.randomUUID() + ".part";
    long total = 0;
    try (SafLocalFiles.AnchoredDirectory directory =
        SafLocalFiles.ensureAnchoredDirectory(parent, pathCheck())) {
      boolean published = false;
      try {
        try (OutputStream output =
            SafLocalFiles.createInDirectory(directory, temporary, pathCheck())) {
          while (true) {
            running(ticket, grant, deadline);
            int cap =
                (int)
                    Math.min(
                        SafCapabilityLimits.MAX_READ_BYTES,
                        SafCapabilityLimits.MAX_WRITE_BYTES - total + 1);
            byte[] data = tree.read(ticket.path, total, cap);
            if (data.length == 0) break;
            if (total + data.length > SafCapabilityLimits.MAX_WRITE_BYTES)
              throw new IOException("TOO_LARGE");
            output.write(data);
            total += data.length;
            if (data.length < cap) break;
          }
        }
        if (source.size >= 0 && total != source.size) throw new IOException("SIZE_MISMATCH");
        SafTree.Entry after = tree.stat(ticket.path);
        if (!source.docId.equals(after.docId)
            || source.size != after.size
            || source.modified != after.modified) throw new IOException("SOURCE_CHANGED");
        running(ticket, grant, deadline);
        SafLocalFiles.publishInDirectory(directory, temporary, target.getName());
        published = true;
        return total;
      } finally {
        if (!published) {
          try {
            SafLocalFiles.deleteInDirectory(directory, temporary);
          } catch (IOException ignored) {
            // 只清理这次在已锚定目录里创建的临时文件，保留原始失败。
          }
        }
      }
    }
  }

  private File guestFile(String raw) throws IOException {
    String path = raw == null ? "" : raw.trim();
    String problem = SafCapabilityPolicy.localSourceProblem(path);
    if (problem != null) throw new IOException(problem);
    File configured = rootfs == null ? null : rootfs.get();
    if (configured == null) throw new IOException("LOCAL_OUTSIDE_ROOTFS");
    File root = configured.getCanonicalFile();
    File resolved = new File(root, path.substring(1)).getCanonicalFile();
    problem = SafCapabilityPolicy.localCanonicalProblem(resolved.getPath(), root.getPath());
    if (problem != null) throw new IOException(problem);
    if (SafLocalFiles.link2SymlinkPath(path) || SafLocalFiles.link2SymlinkPath(resolved.getPath()))
      throw new IOException("FORBIDDEN_LINK2SYMLINK");
    return resolved;
  }

  private SafLocalFiles.PathCheck pathCheck() throws IOException {
    File configured = rootfs == null ? null : rootfs.get();
    String root = configured == null ? null : configured.getCanonicalPath();
    return real -> SafCapabilityPolicy.localCanonicalProblem(real, root) != null;
  }
}
