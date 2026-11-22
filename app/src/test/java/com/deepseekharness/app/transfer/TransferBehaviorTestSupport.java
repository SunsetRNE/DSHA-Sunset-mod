package com.deepseekharness.app.transfer;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Looper;
import android.provider.MediaStore;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.DocumentStreams;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.FileIntegrity;
import com.deepseekharness.app.util.PreviewPageSession;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;

/** 只隔离 Android 文件/Provider/宿主初始化及执行器边界，三个被测协作者始终使用生产实现。 */
public final class TransferBehaviorTestSupport implements AutoCloseable {
  final Application app = RuntimeEnvironment.getApplication();
  final Media media = new Media();
  final List<DownloadEngine> engines = new ArrayList<>();
  final List<Worker> workers = new ArrayList<>();
  final List<CountDownLatch> releases = new ArrayList<>();

  TransferBehaviorTestSupport() throws Exception {
    Controller.value = Shadow.newInstanceOf(HarnessController.class);
    Controller.listeners.clear();
    Controller.readyReads = 0;
    Controller.identity = null;
    Controller.cookie = null;
    HostFiles.beforeRead = null;
    Documents.afterInputClose = null;
    Documents.beforeQueryReturn = null;
    Documents.store = media;
    field(DownloadEngine.class, "instance").set(null, null);
    ProviderInfo info = new ProviderInfo();
    info.authority = "media;transfer-test";
    info.applicationInfo = app.getApplicationInfo();
    info.packageName = app.getPackageName();
    info.name = Media.class.getName();
    media.attachInfo(app, info);
    ShadowContentResolver.registerProviderInternal("media", media);
    ShadowContentResolver.registerProviderInternal("transfer-test", media);
    startedServices();
  }

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static final class Controller {
    static HarnessController value;
    static int readyReads;
    static volatile PreviewPageSession.Identity identity;
    static volatile String cookie;
    static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    @Implementation
    protected static HarnessController get(Context ignored) {
      return value;
    }

    @Implementation
    protected void addReadyWebPageListener(Runnable listener) {
      listeners.add(listener);
    }

    @Implementation
    protected PreviewPageSession.Identity getReadyWebPageIdentity() {
      readyReads++;
      return identity;
    }

    @Implementation
    protected String exchangeDshAuthCookie() {
      if (cookie == null) throw new AssertionError("本夹具不能获取真实凭据");
      return cookie;
    }
  }

  interface FileHook {
    void run(File file) throws IOException;
  }

  interface UriHook {
    void run(Uri uri) throws IOException;
  }

  /** 使用真实临时文件；保留 BackupFileSystem 默认的 atomic/small/child 算法。 */
  @Implements(value = AndroidBackupFileSystem.class, isInAndroidSdk = false)
  public static final class HostFiles {
    static volatile FileHook beforeRead;

    @Implementation
    protected BackupFileSystem.Node stat(File file) throws IOException {
      if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        return new BackupFileSystem.Node("MISSING", "", 0, 0, 0, 0);
      BasicFileAttributes attrs =
          Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      String type =
          attrs.isSymbolicLink()
              ? "LINK"
              : attrs.isDirectory() ? "DIRECTORY" : attrs.isRegularFile() ? "FILE" : "SPECIAL";
      return new BackupFileSystem.Node(
          type,
          String.valueOf(attrs.fileKey()),
          attrs.size(),
          attrs.lastModifiedTime().toMillis(),
          1,
          attrs.isDirectory() ? 0700 : 0600);
    }

    @Implementation
    protected InputStream read(File file, BackupFileSystem.Node expected) throws IOException {
      if (!expected.type.equals("FILE") || !expected.same(stat(file)))
        throw new IOException("SOURCE_CHANGED");
      FileHook hook = beforeRead;
      if (hook != null) hook.run(file);
      return new FileInputStream(file);
    }

    @Implementation
    protected OutputStream create(File file) throws IOException {
      Files.createFile(file.toPath());
      return new FileOutputStream(file);
    }

    @Implementation
    protected void directory(File file) throws IOException {
      Files.createDirectory(file.toPath());
    }

    @Implementation
    protected void move(File from, File to) throws IOException {
      if (Files.exists(to.toPath(), LinkOption.NOFOLLOW_LINKS))
        throw new IOException("MOVE_TARGET_EXISTS");
      Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE);
    }

    @Implementation
    protected void delete(File file) throws IOException {
      Files.delete(file.toPath());
    }

    @Implementation
    protected void syncDirectory(File ignored) {}
  }

  /** Provider 目标可回读、可损坏；摘要与发布顺序仍由生产 Publisher 自己判断。 */
  @Implements(value = DocumentStreams.class, isInAndroidSdk = false)
  public static final class Documents {
    static Media store;
    static volatile UriHook afterInputClose;
    static volatile UriHook beforeQueryReturn;

    @Implementation
    protected static OutputStream output(ContentResolver ignored, Uri uri, BackupControl control)
        throws IOException {
      control.check();
      Row row = store.require(uri);
      store.events.add("output-open");
      return new ByteArrayOutputStream() {
        @Override
        public void close() throws IOException {
          super.close();
          row.bytes = toByteArray();
          store.events.add("output-close");
        }
      };
    }

    @Implementation
    protected static InputStream input(ContentResolver ignored, Uri uri, BackupControl control)
        throws IOException {
      control.check();
      Row row = store.require(uri);
      store.events.add("input-open");
      byte[] bytes = row.readback == null ? row.bytes : row.readback;
      return new FilterInputStream(new ByteArrayInputStream(bytes)) {
        @Override
        public void close() throws IOException {
          super.close();
          store.events.add("input-close");
          UriHook hook = afterInputClose;
          if (hook != null) hook.run(uri);
        }
      };
    }

    @Implementation
    protected static Cursor query(
        ContentResolver resolver, Uri uri, String[] columns, BackupControl control)
        throws IOException {
      control.check();
      Cursor cursor = resolver.query(uri, columns, null, null, null);
      UriHook hook = beforeQueryReturn;
      if (hook != null) hook.run(uri);
      return cursor;
    }
  }

  static final class Row {
    final ContentValues values;
    volatile byte[] bytes = new byte[0];
    volatile byte[] readback;

    Row(ContentValues values) {
      this.values = new ContentValues(values);
    }

    int pending() {
      Integer value = values.getAsInteger(MediaStore.MediaColumns.IS_PENDING);
      return value == null ? 0 : value;
    }
  }

  public static final class Media extends ContentProvider {
    final Map<Uri, Row> rows = new ConcurrentHashMap<>();
    final List<String> events = new CopyOnWriteArrayList<>();
    final List<String> insertedNames = new CopyOnWriteArrayList<>();
    final List<Uri> deleted = new CopyOnWriteArrayList<>();
    volatile Uri last;
    volatile String actualName;
    volatile byte[] readback;
    volatile int updateCount = 1;
    volatile Object requiredGate;
    volatile Runnable afterUpdate;
    private int next;

    @Override
    public boolean onCreate() {
      return true;
    }

    @Override
    public Uri insert(Uri collection, ContentValues values) {
      events.add("insert");
      insertedNames.add(values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
      Uri uri = Uri.withAppendedPath(collection, String.valueOf(++next));
      Row row = new Row(values);
      if (actualName != null) row.values.put(MediaStore.MediaColumns.DISPLAY_NAME, actualName);
      row.readback = readback;
      rows.put(uri, row);
      last = uri;
      return uri;
    }

    Uri document(String name, byte[] bytes) {
      Uri uri = Uri.parse("content://transfer-test/document/" + ++next);
      ContentValues values = new ContentValues();
      values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
      Row row = new Row(values);
      row.bytes = bytes.clone();
      rows.put(uri, row);
      return uri;
    }

    Row require(Uri uri) throws IOException {
      Row row = rows.get(uri);
      if (row == null) throw new IOException("FILE_TEST_DESTINATION_MISSING");
      return row;
    }

    @Override
    public Cursor query(Uri uri, String[] columns, String selection, String[] args, String order) {
      events.add("query");
      MatrixCursor cursor = new MatrixCursor(columns);
      Row row = rows.get(uri);
      if (row != null) {
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++)
          values[index] = row.values.get(columns[index]);
        cursor.addRow(values);
      }
      return cursor;
    }

    @Override
    public int update(Uri uri, ContentValues values, String where, String[] args) {
      if (requiredGate != null) assertTrue("公开可见性切换必须持有提交锁", Thread.holdsLock(requiredGate));
      events.add("visible");
      Row row = rows.get(uri);
      if (updateCount != 1 || row == null) return updateCount;
      row.values.putAll(values);
      Runnable hook = afterUpdate;
      if (hook != null) hook.run();
      return 1;
    }

    @Override
    public int delete(Uri uri, String where, String[] args) {
      events.add("delete");
      deleted.add(uri);
      return rows.remove(uri) == null ? 0 : 1;
    }

    @Override
    public String getType(Uri uri) {
      return "application/octet-stream";
    }
  }

  /** 只控制何时调度，执行内容仍是 Engine 原有的 drain。 */
  static final class ManualExecutor extends AbstractExecutorService {
    final Queue<Runnable> queue = new ArrayDeque<>();
    boolean shutdown;

    @Override
    public synchronized void execute(Runnable runnable) {
      if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
      queue.add(runnable);
    }

    synchronized int size() {
      return queue.size();
    }

    synchronized Runnable take() {
      assertFalse("应有真实 Engine 工作等待调度", queue.isEmpty());
      return queue.remove();
    }

    void runAll() {
      while (size() > 0) take().run();
    }

    @Override
    public synchronized void shutdown() {
      shutdown = true;
    }

    @Override
    public synchronized List<Runnable> shutdownNow() {
      shutdown = true;
      List<Runnable> remaining = new ArrayList<>(queue);
      queue.clear();
      return remaining;
    }

    @Override
    public synchronized boolean isShutdown() {
      return shutdown;
    }

    @Override
    public synchronized boolean isTerminated() {
      return shutdown && queue.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return isTerminated();
    }
  }

  static final class Worker {
    final AtomicReference<Throwable> error = new AtomicReference<>();
    final Thread thread;

    Worker(Runnable action) {
      thread =
          new Thread(
              () -> {
                try {
                  action.run();
                } catch (Throwable failure) {
                  error.set(failure);
                }
              },
              "transfer-test-worker");
      thread.setDaemon(true);
      thread.start();
    }

    void await() throws Exception {
      thread.join(5000);
      assertFalse("测试工作线程必须结束", thread.isAlive());
      if (error.get() != null) throw new AssertionError("测试工作线程失败", error.get());
    }
  }

  DownloadEngine engine() throws Exception {
    Constructor<DownloadEngine> constructor =
        DownloadEngine.class.getDeclaredConstructor(Context.class);
    constructor.setAccessible(true);
    DownloadEngine engine = constructor.newInstance(app);
    for (String name : List.of("worker", "releaseWorker")) {
      Field member = field(DownloadEngine.class, name);
      ((ExecutorService) member.get(engine)).shutdownNow();
      member.set(engine, new ManualExecutor());
    }
    engines.add(engine);
    return engine;
  }

  void singleton(DownloadEngine engine) throws Exception {
    field(DownloadEngine.class, "instance").set(null, engine);
  }

  static Field field(Class<?> type, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  static Object field(Object value, String name) throws Exception {
    return field(value.getClass(), name).get(value);
  }

  static ManualExecutor queue(DownloadEngine engine) throws Exception {
    return (ManualExecutor) field(engine, "worker");
  }

  static Object gate(DownloadEngine engine) throws Exception {
    return field(engine, "gate");
  }

  File partial(String id) {
    return new File(app.getFilesDir(), "transfers/" + id + "/partial");
  }

  void partial(String id, byte[] bytes) throws IOException {
    Files.write(partial(id).toPath(), bytes);
  }

  JSONObject journal(String id) throws Exception {
    return new JSONObject(
        Files.readString(
            new File(app.getFilesDir(), "transfers/" + id + "/task.json").toPath(),
            StandardCharsets.UTF_8));
  }

  File source(byte[] bytes) throws IOException {
    File source = new File(app.getCacheDir(), "publisher-source");
    Files.write(source.toPath(), bytes);
    return source;
  }

  static TransferArtifact artifact(String id, String name, byte[] bytes) throws Exception {
    FileIntegrity.Result hash =
        FileIntegrity.copy(new ByteArrayInputStream(bytes), null, bytes.length);
    return new TransferArtifact(
        id, name, "application/octet-stream", bytes.length, hash.sha256, "v1");
  }

  List<Intent> startedServices() {
    List<Intent> started = new ArrayList<>();
    Intent intent;
    while ((intent = Shadow.<ShadowApplication>extract(app).getNextStartedService()) != null)
      started.add(intent);
    return started;
  }

  static void idle() {
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
  }

  CountDownLatch blockRead(File target) {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    releases.add(release);
    HostFiles.beforeRead =
        file -> {
          if (!file.equals(target)) return;
          entered.countDown();
          try {
            if (!release.await(5, TimeUnit.SECONDS))
              throw new IOException("FILE_TEST_RELEASE_TIMEOUT");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
          }
        };
    return entered;
  }

  void unblock() {
    for (CountDownLatch release : releases) release.countDown();
  }

  Worker start(Runnable action) {
    Worker worker = new Worker(action);
    workers.add(worker);
    return worker;
  }

  static void await(CountDownLatch entered) throws InterruptedException {
    assertTrue("应到达受控的真实 I/O 边界", entered.await(2, TimeUnit.SECONDS));
  }

  @Override
  public void close() throws Exception {
    unblock();
    for (Worker worker : workers) worker.await();
    for (DownloadEngine engine : engines)
      for (String name : List.of("worker", "releaseWorker"))
        ((ExecutorService) field(engine, name)).shutdownNow();
    field(DownloadEngine.class, "instance").set(null, null);
    idle();
    HostFiles.beforeRead = null;
    Documents.afterInputClose = null;
    Documents.beforeQueryReturn = null;
    Controller.listeners.clear();
  }
}
