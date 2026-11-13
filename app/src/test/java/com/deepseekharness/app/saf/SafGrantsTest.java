package com.deepseekharness.app.saf;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.provider.DocumentsContract;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

/**
 * 授权簿的持久 URI 授权语义。
 *
 * <p>系统的持久 URI 授权按「App + URI」记，不分功能；这里验证本功能只释放自己新拿到的读/写位，
 * 备份等其它功能已有的授权不会因为「移除」或满额回滚而被一起撤销，以及进程中途死亡留下的
 * 待确认授权会在下次访问时按「原有位」清理。
 */
@RunWith(SafGrantsTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public final class SafGrantsTest {
  private static final String PREFS = "saf_grants";

  /** 与 Robolectric 4.15 在 AGP 之外的运行方式一致：不依赖 Gradle 注入的 manifest 路径。 */
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected Properties getBuildSystemApiProperties() {
      return new Properties();
    }
  }

  private static final String LABEL_AUTH = "test.dsha.saf.labels";
  private Context context;
  private LabelProvider labelProvider;
  private long previousLabelDeadline;

  /** 只处理目录名查询的 DocumentsProvider；可配置响应内容与阻塞行为。 */
  public static final class LabelProvider extends ContentProvider {
    int queries;
    String label = "shared-folder";
    volatile CountDownLatch hung;
    boolean blockUntilCanceled;
    boolean ignoreCancel;

    @Override
    public boolean onCreate() {
      return true;
    }

    @Override
    public Cursor query(
        Uri uri, String[] projection, String selection, String[] args, String order) {
      return query(uri, projection, selection, args, order, null);
    }

    @Override
    public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] args,
        String order,
        CancellationSignal signal) {
      queries++;
      if (blockUntilCanceled) {
        CountDownLatch release = new CountDownLatch(1);
        hung = release;
        if (signal != null) signal.setOnCancelListener(release::countDown);
        try {
          release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        throw new OperationCanceledException();
      }
      if (ignoreCancel) {
        // 故意不注册取消监听：验证文档声明的「忽略取消的 Provider 仍会占用调用线程」。
        try {
          Thread.sleep(400);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      MatrixCursor cursor =
          new MatrixCursor(new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME});
      cursor.addRow(new Object[] {label});
      return cursor;
    }

    @Override
    public String getType(Uri uri) {
      return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
      throw new UnsupportedOperationException();
    }
  }

  private Uri labelTree(String name) {
    return Uri.parse("content://" + LABEL_AUTH + "/tree/" + name);
  }

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    prefs().edit().clear().commit();
    context
        .getSharedPreferences(SafLedgerStore.NAMESPACE, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit();
    labelProvider = new LabelProvider();
    ProviderInfo info = new ProviderInfo();
    info.authority = LABEL_AUTH;
    info.exported = true;
    info.grantUriPermissions = true;
    labelProvider.attachInfo(context, info);
    ShadowContentResolver.registerProviderInternal(LABEL_AUTH, labelProvider);
    previousLabelDeadline = SafGrants.labelDeadlineMs;
  }

  @org.junit.After
  public void tearDown() {
    SafGrants.labelDeadlineMs = previousLabelDeadline;
  }

  private android.content.SharedPreferences prefs() {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  private static Uri tree(String name) {
    return Uri.parse("content://test.dsha.saf." + name + "/tree/" + name);
  }

  private int flags(Uri tree) {
    int flags = 0;
    for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
      if (!tree.equals(permission.getUri())) continue;
      if (permission.isReadPermission()) flags |= SafGrants.READ;
      if (permission.isWritePermission()) flags |= SafGrants.WRITE;
    }
    return flags;
  }

  private void take(Uri tree, boolean read, boolean write) {
    int flags = 0;
    if (read) flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
    if (write) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    context.getContentResolver().takePersistableUriPermission(tree, flags);
  }

  @Test
  public void forgetKeepsReadPermissionAnotherFeatureAlreadyHeld() {
    Uri shared = tree("backup-read");
    take(shared, true, false); // 备份等其它功能先对同一目录拿到了读授权
    SafGrants.AddResult added = SafGrants.add(context, shared, true);
    assertTrue(added.added);
    assertTrue(added.readable);
    assertTrue(added.writable);
    assertEquals(SafGrants.READ | SafGrants.WRITE, flags(shared));

    SafGrants.forget(context, shared);

    assertEquals("其它功能已有的读授权必须保留", SafGrants.READ, flags(shared));
    assertTrue(SafGrants.list(context).isEmpty());
  }

  @Test
  public void forgetKeepsPreExistingReadWritePermission() {
    Uri shared = tree("backup-read-write");
    take(shared, true, true);
    SafGrants.AddResult added = SafGrants.add(context, shared, true);
    assertTrue(added.added);

    SafGrants.forget(context, shared);

    assertEquals("本功能没有新增任何权限，移除时不能撤销其它功能已有的授权", SafGrants.READ | SafGrants.WRITE, flags(shared));
  }

  @Test
  public void forgetReleasesOnlyWhatThisFeatureTook() {
    Uri own = tree("own-only");
    assertTrue(SafGrants.add(context, own, true).added);
    assertEquals(SafGrants.READ | SafGrants.WRITE, flags(own));

    SafGrants.forget(context, own);

    assertEquals(0, flags(own));
    assertTrue(SafGrants.list(context).isEmpty());
  }

  @Test
  public void limitDoesNotTakeNewSystemPermission() {
    for (int i = 0; i <= SafGrants.MAX_TREES; i++) {
      SafGrants.AddResult result = SafGrants.add(context, tree("limit-" + i), true);
      if (i < SafGrants.MAX_TREES) {
        assertTrue("第 " + i + " 个授权应成功", result.added);
      } else {
        assertFalse(result.added);
        assertEquals("LIMIT", result.failure);
        assertEquals("满额时不得先占系统授权再回滚", 0, flags(tree("limit-" + i)));
      }
    }
  }

  @Test
  public void pendingRecordOnlyReleasesTheNewlyTakenBitsAtNextStartup() {
    Uri shared = tree("pending");
    take(shared, true, false); // take 之前已有其它功能的读授权
    Set<String> pending = new HashSet<>();
    pending.add(shared.toString());
    prefs()
        .edit()
        .putStringSet(SafGrants.KEY_PENDING, pending)
        .putString(SafGrants.KEY_PENDING_BEFORE + shared, Integer.toString(SafGrants.READ))
        .putString(
            SafGrants.KEY_PENDING_INTENT + shared,
            Integer.toString(SafGrants.READ | SafGrants.WRITE))
        .commit();
    take(shared, false, true); // 进程在 take 之后、remember 之前死亡

    SafGrants.cleanupPending(context);

    assertEquals("清理只还本次新拿到的写位，原有读授权保留", SafGrants.READ, flags(shared));
    assertTrue(SafGrants.list(context).isEmpty());
    // 旧命名空间只读兼容：迁移后不再改写它（降级版仍能读到自己那份记录）。
    assertTrue(
        prefs().getStringSet(SafGrants.KEY_PENDING, new HashSet<>()).contains(shared.toString()));
    assertTrue(prefs().contains(SafGrants.KEY_PENDING_BEFORE + shared));
  }

  @Test
  public void writeOnlyGrantIsValidAndReleasable() {
    Uri writeOnly = tree("write-only");
    // 提供方只给写权限：系统里只有写位（例如网盘的「仅上传」目录）。
    take(writeOnly, false, true);
    Set<String> saved = new HashSet<>();
    saved.add(writeOnly.toString());
    prefs()
        .edit()
        .putStringSet("uris", saved)
        .putString(SafGrants.KEY_ACQUIRED + writeOnly, "w")
        .commit();

    SafGrants.Grant grant = SafGrants.find(context, SafGrants.id(writeOnly));
    assertNotNull(grant);
    assertTrue("只写授权也是有效授权", grant.valid);
    assertFalse("只写授权没有读位", grant.readable);
    assertTrue("只写授权可以写", grant.canWrite);
    assertTrue(grant.writable);

    SafGrants.forget(context, writeOnly);

    assertEquals("只写授权也必须撤得干净", 0, flags(writeOnly));
  }

  @Test
  public void listLightUsesCachedLabelsAndNeverQueriesProvider() {
    Uri tree = labelTree("cached");
    SafGrants.AddResult added = SafGrants.add(context, tree, true);
    assertTrue(added.added);
    int queriesAfterAdd = labelProvider.queries;
    assertTrue("add 时应缓存目录名", queriesAfterAdd >= 1 && "shared-folder".equals(cachedLabel(tree)));

    java.util.List<SafGrants.Grant> grants = SafGrants.listLight(context);

    assertEquals(queriesAfterAdd, labelProvider.queries);
    assertEquals(1, grants.size());
    assertEquals("shared-folder", grants.get(0).label);
  }

  @Test
  public void labelQueryReturnsAtDeadlineForCancellableProvider() {
    SafGrants.labelDeadlineMs = 300;
    labelProvider.blockUntilCanceled = true;
    Uri tree = labelTree("hung");
    long started = System.nanoTime();
    SafGrants.AddResult added = SafGrants.add(context, tree, true);
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertTrue(added.added);
    assertTrue("Provider 不响应时必须按本地截止返回，实际 " + elapsedMs + "ms", elapsedMs < 5000);
    assertEquals(1, labelProvider.queries);
    assertEquals("", cachedLabel(tree));
  }

  @Test
  public void labelQueryForIgnoringProviderBlocksForProviderButIsDocumented() {
    SafGrants.labelDeadlineMs = 100;
    labelProvider.ignoreCancel = true;
    Uri tree = labelTree("ignoring");
    long started = System.nanoTime();
    assertTrue(SafGrants.add(context, tree, true).added);
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    // 该 Provider 不响应取消：线程会被占用到它返回为止（400ms），但查询只在后台 add/list 发生。
    assertTrue("应观察到 Provider 实际耗时，实际 " + elapsedMs + "ms", elapsedMs >= 300);
    assertTrue("不应无限等待，实际 " + elapsedMs + "ms", elapsedMs < 5000);
  }

  private String cachedLabel(Uri tree) {
    SafGrantRecord record =
        new SafLedgerStore(
                context.getSharedPreferences(SafLedgerStore.NAMESPACE, Context.MODE_PRIVATE))
            .load()
            .findByUri(tree.toString());
    return record == null ? "" : record.label();
  }

  @Test
  public void pendingWithoutSystemPermissionIsClearedWithoutReleasingAnything() {
    Uri never = tree("never-taken");
    Set<String> pending = new HashSet<>();
    pending.add(never.toString());
    prefs()
        .edit()
        .putStringSet(SafGrants.KEY_PENDING, pending)
        .putString(SafGrants.KEY_PENDING_BEFORE + never, "0")
        .putString(SafGrants.KEY_PENDING_INTENT + never, "3")
        .commit();

    SafGrants.cleanupPending(context);

    assertTrue(SafGrants.list(context).isEmpty());
    assertEquals(0, flags(never));
    // 旧命名空间保持原样（只读兼容）；账本已初始化，不会再次迁移出可用授权。
    assertTrue(
        prefs().getStringSet(SafGrants.KEY_PENDING, new HashSet<>()).contains(never.toString()));
  }

  @Test
  public void legacyGrantsAreMigratedOnceAndLegacyKeysStayReadOnly() {
    Uri shared = tree("migrated");
    take(shared, true, true);
    Set<String> saved = new HashSet<>();
    saved.add(shared.toString());
    prefs()
        .edit()
        .putStringSet("uris", saved)
        .putString(SafGrants.KEY_ACQUIRED + shared, "rw")
        .putBoolean("writable:" + shared, false)
        .putString(SafGrants.KEY_LABEL + shared, "旧目录")
        .commit();

    java.util.List<SafGrants.Grant> grants = SafGrants.list(context);
    assertEquals(1, grants.size());
    assertEquals("旧目录", grants.get(0).label);
    assertFalse("用户写入偏好应随迁移保留", grants.get(0).writable);
    assertTrue(
        "旧命名空间不得被迁移改写", prefs().getStringSet("uris", new HashSet<>()).contains(shared.toString()));

    SafGrants.forget(context, shared);

    assertEquals(0, flags(shared));
    assertTrue(SafGrants.list(context).isEmpty());
    assertTrue(
        "初始化标记存在时旧记录不得被再次迁移回来",
        prefs().getStringSet("uris", new HashSet<>()).contains(shared.toString()));
  }

  @Test
  public void ledgerStoreWriteFailureFallsBackToLegacyAndKeepsGrants() {
    Uri shared = tree("fallback");
    take(shared, true, true);
    Set<String> saved = new HashSet<>();
    saved.add(shared.toString());
    prefs()
        .edit()
        .putStringSet("uris", saved)
        .putString(SafGrants.KEY_ACQUIRED + shared, "rw")
        .putBoolean("writable:" + shared, true)
        .putString(SafGrants.KEY_LABEL + shared, "回退目录")
        .commit();
    Context failing = new FailingLedgerContext(context);

    List<SafGrants.Grant> grants = SafGrants.list(failing);
    assertEquals("账本写失败时必须回退旧路径，授权不丢", 1, grants.size());
    assertFalse(
        "写失败时不得留下半份账本初始化",
        new SafLedgerStore(
                context.getSharedPreferences(SafLedgerStore.NAMESPACE, Context.MODE_PRIVATE))
            .initialized());

    Uri another = tree("fallback-add");
    assertTrue("回退旧路径仍能新增授权", SafGrants.add(failing, another, true).added);
    assertTrue(
        "回退新增必须写进旧命名空间",
        prefs().getStringSet("uris", new HashSet<>()).contains(another.toString()));
  }

  @Test
  public void systemRevokedForgetRemovesLedgerRecordAndAllowsReAdd() {
    Uri tree = tree("sysrevoked");
    seedActive(tree, "应用", "目录", SafGrantRecord.READ | SafGrantRecord.WRITE);
    ControlledContext revoked = new ControlledContext(context);
    revoked.resolver.revoked = true;

    SafGrants.forget(revoked, tree);

    assertNull("系统撤权后账本记录必须删除，不能永久 RELEASING", ledgerStore().load().findByUri(tree.toString()));
    assertTrue(SafGrants.list(revoked).isEmpty());

    revoked.resolver.revoked = false;
    assertTrue("同一 URI 必须可以重新添加", SafGrants.add(revoked, tree, true).added);
    assertNotNull(ledgerStore().load().findByUri(tree.toString()));
  }

  @Test
  public void staleReleasingRecordWithNoHeldBitsIsFinishedByCleanup() {
    Uri tree = tree("stale-release");
    seedActive(tree, "应用", "目录", SafGrantRecord.READ | SafGrantRecord.WRITE);
    SafLedgerStore store = ledgerStore();
    assertTrue(store.save(store.load().beginRelease(tree.toString()).ledger));
    ControlledContext revoked = new ControlledContext(context);
    revoked.resolver.revoked = true;

    SafGrants.cleanupPending(revoked);

    assertNull("系统里已无位时 RELEASING 必须完成，不得反复 release 卡死", store.load().findByUri(tree.toString()));
  }

  @Test
  public void releaseSucceededButSaveFailedDoesNotStick() {
    Uri tree = tree("release-save-fail");
    seedActive(tree, "应用", "目录", SafGrantRecord.READ | SafGrantRecord.WRITE);
    take(tree, true, true);
    ToggleFailContext failing = new ToggleFailContext(context);

    SafGrants.forget(failing, tree);
    assertEquals("释放本身成功", 0, flags(tree));
    assertNotNull("保存失败时磁盘保留可重试状态", ledgerStore().load().findByUri(tree.toString()));

    failing.failCommits = false;
    SafGrants.forget(failing, tree);

    assertNull("第二次 forget 应按交集为 0 完成撤销", ledgerStore().load().findByUri(tree.toString()));
  }

  @Test
  public void failedRetakeKeepsCachedAppAndLabel() {
    Uri tree = tree("retake-meta");
    seedActive(tree, "旧应用", "旧目录", SafGrantRecord.READ | SafGrantRecord.WRITE);
    ControlledContext revoked = new ControlledContext(context);
    revoked.resolver.revoked = true;

    SafGrants.AddResult result = SafGrants.add(revoked, tree, true);

    assertFalse(result.added);
    assertEquals("NO_PERMISSION", result.failure);
    SafGrantRecord record = ledgerStore().load().findByUri(tree.toString());
    assertNotNull(record);
    assertEquals(SafGrantRecord.State.ACTIVE, record.state());
    assertEquals("旧应用", record.app());
    assertEquals("旧目录", record.label());
  }

  @Test
  public void fallbackListDoesNotHoldLockDuringProviderQuery() throws Exception {
    Uri tree = labelTree("slow-fallback");
    Set<String> saved = new HashSet<>();
    saved.add(tree.toString());
    prefs().edit().putStringSet("uris", saved).commit();
    FailingLedgerContext failing = new FailingLedgerContext(context);
    SafGrants.labelDeadlineMs = 1_000;
    labelProvider.blockUntilCanceled = true;
    Thread slow = new Thread(() -> SafGrants.list(failing), "slow-list");
    slow.start();
    long elapsedMs;
    try {
      long waitUntil = System.currentTimeMillis() + 2_000;
      while (labelProvider.hung == null && System.currentTimeMillis() < waitUntil) Thread.sleep(10);
      assertNotNull("Provider 查询应已开始", labelProvider.hung);
      long started = System.nanoTime();
      SafGrants.listLight(failing);
      elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    } finally {
      labelProvider.blockUntilCanceled = false;
      slow.join(5_000);
    }
    assertTrue("回退路径不得在 LOCK 内查询 Provider（实测 " + elapsedMs + "ms）", elapsedMs < 600);
  }

  @Test
  public void addOnReleasingWithUnreadablePersistedStateReturnsTypedFailure() {
    Uri tree = tree("releasing-unreadable");
    seedActive(tree, "应用", "目录", SafGrantRecord.READ | SafGrantRecord.WRITE);
    SafLedgerStore store = ledgerStore();
    assertTrue(store.save(store.load().beginRelease(tree.toString()).ledger));
    ControlledContext unreadable = new ControlledContext(context);
    unreadable.resolver.throwOnPersisted = true;

    SafGrants.AddResult result = SafGrants.add(unreadable, tree, true);

    assertFalse(result.added);
    assertEquals("系统券不可读必须返回类型化失败，不能 NPE", "PERMISSION_STATE_UNAVAILABLE", result.failure);
    assertEquals(SafGrantRecord.State.RELEASING, store.load().findByUri(tree.toString()).state());
  }

  @Test
  public void forgetOnPendingWithUnreadableStateKeepsPendingForCleanup() {
    Uri tree = tree("pending-unreadable");
    seedPendingRetake(
        tree, SafGrantRecord.READ, SafGrantRecord.READ, SafGrantRecord.READ | SafGrantRecord.WRITE);
    ControlledContext unreadable = new ControlledContext(context);
    unreadable.resolver.throwOnPersisted = true;

    SafGrants.forget(unreadable, tree);

    SafGrantRecord pending = ledgerStore().load().findByUri(tree.toString());
    assertNotNull(pending);
    assertEquals("状态不可读时不得丢 before/intent", SafGrantRecord.State.PENDING, pending.state());

    unreadable.resolver.throwOnPersisted = false;
    SafGrants.cleanupPending(unreadable);
    SafGrantRecord restored = ledgerStore().load().findByUri(tree.toString());
    assertNotNull(restored);
    assertEquals(SafGrantRecord.State.ACTIVE, restored.state());
  }

  @Test
  public void forgetOnPendingWithReleaseFailureKeepsPendingForCleanup() {
    Uri tree = tree("pending-release-fail");
    seedPendingRetake(tree, 0, 0, SafGrantRecord.READ);
    take(tree, true, false);
    ControlledContext failing = new ControlledContext(context);
    failing.resolver.failRelease = true;

    SafGrants.cleanupPending(failing);
    SafGrants.forget(failing, tree);

    SafGrantRecord pending = ledgerStore().load().findByUri(tree.toString());
    assertNotNull(pending);
    assertEquals(SafGrantRecord.State.PENDING, pending.state());

    failing.resolver.failRelease = false;
    SafGrants.cleanupPending(failing);
    assertEquals(0, flags(tree));
    SafGrants.forget(failing, tree);
    assertNull(ledgerStore().load().findByUri(tree.toString()));
  }

  @Test
  public void forgetAfterCrashPendingReleasesDeltaAndRemovesRestoredGrant() {
    Uri tree = tree("pending-crash-forget");
    seedActive(tree, "应用", "目录", SafGrantRecord.READ);
    take(tree, true, false);
    SafLedgerStore store = ledgerStore();
    assertTrue(
        store.save(
            store
                .load()
                .beginTake(
                    tree.toString(),
                    "应用",
                    "目录",
                    SafGrantRecord.READ,
                    SafGrantRecord.READ | SafGrantRecord.WRITE,
                    System.currentTimeMillis())
                .ledger));
    take(tree, false, true);

    SafGrants.forget(context, tree);

    SafGrantRecord record = store.load().findByUri(tree.toString());
    assertNull("恢复旧 ACTIVE 后仍按用户意图移除", record);
    assertEquals(0, flags(tree));
  }

  private void seedPendingRetake(Uri tree, int oldFlags, int before, int intent) {
    seedActive(tree, "应用", "目录", oldFlags);
    SafLedgerStore store = ledgerStore();
    assertTrue(
        store.save(
            store
                .load()
                .beginTake(tree.toString(), "应用", "目录", before, intent, System.currentTimeMillis())
                .ledger));
  }

  private SafLedgerStore ledgerStore() {
    return new SafLedgerStore(
        context.getSharedPreferences(SafLedgerStore.NAMESPACE, Context.MODE_PRIVATE));
  }

  private SafGrantRecord seedActive(Uri tree, String app, String label, int flags) {
    SafLedgerStore store = ledgerStore();
    long now = System.currentTimeMillis();
    SafGrantLedger ledger = store.load();
    SafGrantLedger begun = ledger.beginTake(tree.toString(), app, label, 0, flags, now).ledger;
    SafGrantLedger committed = begun.commitTake(tree.toString(), flags, flags, true, now).ledger;
    assertTrue(store.save(committed));
    return committed.findByUri(tree.toString());
  }

  /** 可控系统券：revoked=true 时看不到任何持久授权，且 release 抛 SecurityException。 */
  private static final class ControlledResolver extends ContentResolver {
    private final ContentResolver real;
    boolean revoked;
    boolean throwOnPersisted;
    boolean failRelease;

    ControlledResolver(Context base) {
      super(base);
      real = base.getContentResolver();
    }

    @Override
    public List<UriPermission> getPersistedUriPermissions() {
      if (throwOnPersisted) throw new IllegalStateException("PERMISSION_STATE_UNAVAILABLE");
      return revoked ? List.of() : real.getPersistedUriPermissions();
    }

    @Override
    public void releasePersistableUriPermission(Uri uri, int modeFlags) {
      if (failRelease) throw new SecurityException("RELEASE_FAILED");
      if (revoked) throw new SecurityException("REVOKED");
      real.releasePersistableUriPermission(uri, modeFlags);
    }

    @Override
    public void takePersistableUriPermission(Uri uri, int modeFlags) {
      real.takePersistableUriPermission(uri, modeFlags);
    }
  }

  private static final class ControlledContext extends ContextWrapper {
    final ControlledResolver resolver;

    ControlledContext(Context base) {
      super(base);
      resolver = new ControlledResolver(base);
    }

    @Override
    public ContentResolver getContentResolver() {
      return resolver;
    }
  }

  /** 首次 commit 失败、之后成功的账本命名空间；用于「release 成功但保存失败」。 */
  private static final class ToggleFailContext extends ContextWrapper {
    private final SharedPreferences realLedger;
    volatile boolean failCommits = true;

    ToggleFailContext(Context base) {
      super(base);
      realLedger = base.getSharedPreferences(SafLedgerStore.NAMESPACE, Context.MODE_PRIVATE);
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
      if (!SafLedgerStore.NAMESPACE.equals(name)) return super.getSharedPreferences(name, mode);
      return new ToggleFailPreferences(realLedger, this);
    }
  }

  private static final class ToggleFailPreferences implements SharedPreferences {
    private final SharedPreferences delegate;
    private final ToggleFailContext owner;

    ToggleFailPreferences(SharedPreferences delegate, ToggleFailContext owner) {
      this.delegate = delegate;
      this.owner = owner;
    }

    @Override
    public Map<String, ?> getAll() {
      return delegate.getAll();
    }

    @Override
    public String getString(String key, String defValue) {
      return delegate.getString(key, defValue);
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
      return delegate.getStringSet(key, defValues);
    }

    @Override
    public int getInt(String key, int defValue) {
      return delegate.getInt(key, defValue);
    }

    @Override
    public long getLong(String key, long defValue) {
      return delegate.getLong(key, defValue);
    }

    @Override
    public float getFloat(String key, float defValue) {
      return delegate.getFloat(key, defValue);
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
      return delegate.getBoolean(key, defValue);
    }

    @Override
    public boolean contains(String key) {
      return delegate.contains(key);
    }

    @Override
    public Editor edit() {
      SharedPreferences.Editor editor = delegate.edit();
      return new Editor() {
        @Override
        public Editor putString(String key, String value) {
          editor.putString(key, value);
          return this;
        }

        @Override
        public Editor putStringSet(String key, Set<String> values) {
          editor.putStringSet(key, values);
          return this;
        }

        @Override
        public Editor putInt(String key, int value) {
          editor.putInt(key, value);
          return this;
        }

        @Override
        public Editor putLong(String key, long value) {
          editor.putLong(key, value);
          return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
          editor.putFloat(key, value);
          return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
          editor.putBoolean(key, value);
          return this;
        }

        @Override
        public Editor remove(String key) {
          editor.remove(key);
          return this;
        }

        @Override
        public Editor clear() {
          editor.clear();
          return this;
        }

        @Override
        public boolean commit() {
          if (owner.failCommits) return false;
          return editor.commit();
        }

        @Override
        public void apply() {
          if (!owner.failCommits) editor.apply();
        }
      };
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(
        OnSharedPreferenceChangeListener listener) {}

    @Override
    public void unregisterOnSharedPreferenceChangeListener(
        OnSharedPreferenceChangeListener listener) {}
  }

  /** 只让账本命名空间写失败的 Context；其它命名空间（旧 saf_grants）保持真实读写。 */
  private static final class FailingLedgerContext extends ContextWrapper {
    private final SharedPreferences realLedger;

    FailingLedgerContext(Context base) {
      super(base);
      realLedger = base.getSharedPreferences(SafLedgerStore.NAMESPACE, Context.MODE_PRIVATE);
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
      if (SafLedgerStore.NAMESPACE.equals(name)) return new ReadOnlyPreferences(realLedger);
      return super.getSharedPreferences(name, mode);
    }
  }

  private static final class ReadOnlyPreferences implements SharedPreferences {
    private final SharedPreferences delegate;

    ReadOnlyPreferences(SharedPreferences delegate) {
      this.delegate = delegate;
    }

    @Override
    public Map<String, ?> getAll() {
      return delegate.getAll();
    }

    @Override
    public String getString(String key, String defValue) {
      return delegate.getString(key, defValue);
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
      return delegate.getStringSet(key, defValues);
    }

    @Override
    public int getInt(String key, int defValue) {
      return delegate.getInt(key, defValue);
    }

    @Override
    public long getLong(String key, long defValue) {
      return delegate.getLong(key, defValue);
    }

    @Override
    public float getFloat(String key, float defValue) {
      return delegate.getFloat(key, defValue);
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
      return delegate.getBoolean(key, defValue);
    }

    @Override
    public boolean contains(String key) {
      return delegate.contains(key);
    }

    @Override
    public Editor edit() {
      return new FailingEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(
        OnSharedPreferenceChangeListener listener) {}

    @Override
    public void unregisterOnSharedPreferenceChangeListener(
        OnSharedPreferenceChangeListener listener) {}
  }

  private static final class FailingEditor implements SharedPreferences.Editor {
    @Override
    public SharedPreferences.Editor putString(String key, String value) {
      return this;
    }

    @Override
    public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
      return this;
    }

    @Override
    public SharedPreferences.Editor putInt(String key, int value) {
      return this;
    }

    @Override
    public SharedPreferences.Editor putLong(String key, long value) {
      return this;
    }

    @Override
    public SharedPreferences.Editor putFloat(String key, float value) {
      return this;
    }

    @Override
    public SharedPreferences.Editor putBoolean(String key, boolean value) {
      return this;
    }

    @Override
    public SharedPreferences.Editor remove(String key) {
      return this;
    }

    @Override
    public SharedPreferences.Editor clear() {
      return this;
    }

    @Override
    public boolean commit() {
      return false;
    }

    @Override
    public void apply() {}
  }
}
