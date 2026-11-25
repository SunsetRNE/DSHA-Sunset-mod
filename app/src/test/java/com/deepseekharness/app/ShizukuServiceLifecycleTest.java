package com.deepseekharness.app;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowProcess;
import rikka.shizuku.Shizuku;

/** SDK 34 simulated Binder and manager. Never starts a shell or an Android UserService. */
@RunWith(DocumentRowsSnapshotTest.NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    shadows = ShizukuServiceLifecycleTest.Manager.class)
public final class ShizukuServiceLifecycleTest {
  private Context context;

  @Implements(value = Shizuku.class, isInAndroidSdk = false)
  public static class Manager {
    static IBinder manager;

    @Implementation
    protected static IBinder getBinder() {
      return manager;
    }

    static boolean permitted;
    static Shizuku.UserServiceArgs args;
    static ServiceConnection pending;
    static List<Shizuku.UserServiceArgs> removed = new ArrayList<>();
    static List<Shizuku.UserServiceArgs> detached = new ArrayList<>();
    static boolean rejectRemoval;
    static boolean rejectDetach;

    @Implementation
    protected static boolean pingBinder() {
      return true;
    }

    @Implementation
    protected static int checkSelfPermission() {
      return permitted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
    }

    @Implementation
    protected static void addBinderDeadListener(Shizuku.OnBinderDeadListener listener) {}

    @Implementation
    protected static void addBinderReceivedListenerSticky(
        Shizuku.OnBinderReceivedListener listener) {}

    @Implementation
    protected static void bindUserService(
        Shizuku.UserServiceArgs options, ServiceConnection callback) {
      args = options;
      pending = callback;
    }

    @Implementation
    protected static void unbindUserService(
        Shizuku.UserServiceArgs options, ServiceConnection callback, boolean remove) {
      (remove ? removed : detached).add(options);
      if ((remove && rejectRemoval) || (!remove && rejectDetach))
        throw new SecurityException("revoked");
    }
  }

  private static final class Endpoint extends IShellService.Stub {
    final CountDownLatch closed = new CountDownLatch(1);
    int executions;

    public void destroy() {
      closed.countDown();
    }

    public void destroyFromManager() {
      closed.countDown();
    }

    public String exec(String value) {
      executions++;
      return "fixture";
    }

    public String execVirtualScreen(String value) {
      executions++;
      return "fixture";
    }
  }

  @Before
  public void prepare() {
    ShadowProcess.setUid(10234);
    ShadowProcess.setPid(1234);
    ShadowBinder.setCallingUid(10234);
    ShadowBinder.setCallingPid(1234);
    context = RuntimeEnvironment.getApplication();
    Manager.permitted = false;
    Manager.rejectRemoval = false;
    Manager.rejectDetach = false;
    ShizukuShell.ensureBound(context);
    idle();
    Manager.manager = new android.os.Binder();
    Manager.permitted = true;
    ShizukuShell.ensureBound(context);
    Manager.permitted = false;
    ShizukuShell.ensureBound(context);
    idle();
    Manager.args = null;
    Manager.pending = null;
    Manager.removed.clear();
    Manager.detached.clear();
    Manager.permitted = true;
  }

  private static void idle() {
    ShadowLooper.idleMainLooper();
  }

  private static void timeout() {
    ShadowLooper.idleMainLooper(15, TimeUnit.SECONDS);
  }

  private static ComponentName name() {
    return new ComponentName(BuildConfig.APPLICATION_ID, ShellService.class.getName());
  }

  private static String field(Shizuku.UserServiceArgs args, String name) throws Exception {
    var f = args.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return (String) f.get(args);
  }

  private static boolean transaction(ShellService service, int number, boolean oneWay)
      throws Exception {
    Parcel data = Parcel.obtain(), reply = oneWay ? null : Parcel.obtain();
    try {
      data.writeInterfaceToken(IShellService.DESCRIPTOR);
      data.setDataPosition(0);
      boolean handled = service.onTransact(number, data, reply, oneWay ? IBinder.FLAG_ONEWAY : 0);
      if (handled && reply != null) {
        reply.setDataPosition(0);
        reply.readException();
      }
      return handled;
    } finally {
      data.recycle();
      if (reply != null) reply.recycle();
    }
  }

  @Test
  public void legacyCommandTransactionNumbersAreStable() throws Exception {
    Endpoint endpoint = new Endpoint();
    for (int code : new int[] {1, 2}) {
      Parcel data = Parcel.obtain(), reply = Parcel.obtain();
      try {
        data.writeInterfaceToken(IShellService.DESCRIPTOR);
        data.writeString("fixture");
        data.setDataPosition(0);
        assertTrue(endpoint.onTransact(code, data, reply, 0));
        reply.setDataPosition(0);
        reply.readException();
        assertEquals("fixture", reply.readString());
      } finally {
        data.recycle();
        reply.recycle();
      }
    }
    assertEquals(2, endpoint.executions);
  }

  @Test
  public void badDescriptorNeverDispatchesManagerClose() {
    ShellService service = new ShellService();
    Parcel data = Parcel.obtain();
    try {
      data.writeInterfaceToken("example.fixture.other");
      data.setDataPosition(0);
      assertThrows(
          SecurityException.class,
          () -> service.onTransact(16777115, data, null, IBinder.FLAG_ONEWAY));
    } finally {
      data.recycle();
    }
    assertTrue(service.exec(" ").endsWith("[EXIT=126]"));
  }

  /** Tests can observe the exit decision without exposing the test VM to System.exit. */
  private static final class ServiceProbe extends ShellService {
    String name = BuildConfig.APPLICATION_ID + ":shizuku_10234";
    int exits;

    String processName() {
      return name;
    }

    void exitUserServiceProcess() {
      exits++;
    }
  }

  @Test
  public void managerRemoteOneWayRequestsExitOnlyInDedicatedProcess() throws Exception {
    ShadowProcess.setUid(2000);
    ShadowBinder.setCallingUid(2000);
    ShadowBinder.setCallingPid(0);
    ServiceProbe dedicated = new ServiceProbe(), unrelated = new ServiceProbe();
    unrelated.name = BuildConfig.APPLICATION_ID + ":other";
    assertTrue(transaction(dedicated, 16777115, true));
    assertTrue(transaction(dedicated, 16777115, true));
    assertTrue(transaction(unrelated, 16777115, true));
    assertEquals(1, dedicated.exits);
    assertEquals(0, unrelated.exits);
  }

  @Test
  public void noContextServiceUsesOwnerUidInProcessName() {
    ServiceProbe service = new ServiceProbe();
    ShadowBinder.setCallingPid(3456);
    ShadowBinder.setCallingUid(10234);
    assertTrue(service.exec(" ").endsWith("[EXIT=126]"));
    ShadowBinder.setCallingUid(110234);
    assertThrows(SecurityException.class, () -> service.exec(" "));
  }

  private static final class Child extends Process {
    int stops;

    @Override
    public void destroy() {
      stops++;
    }

    @Override
    public int exitValue() {
      if (stops == 0) throw new IllegalThreadStateException();
      return 0;
    }

    @Override
    public int waitFor() {
      return exitValue();
    }

    @Override
    public java.io.OutputStream getOutputStream() {
      return java.io.OutputStream.nullOutputStream();
    }

    @Override
    public java.io.InputStream getInputStream() {
      return java.io.InputStream.nullInputStream();
    }

    @Override
    public java.io.InputStream getErrorStream() {
      return java.io.InputStream.nullInputStream();
    }
  }

  // Install synthetic process handles without starting any real process.
  @SuppressWarnings("unchecked")
  private static Child registerFixtureChild(ShellService owner) throws Exception {
    var field =
        java.util.Arrays.stream(ShellService.class.getDeclaredFields())
            .filter(f -> f.getType() == java.util.Set.class)
            .findFirst()
            .orElseThrow();
    field.setAccessible(true);
    Child child = new Child();
    ((java.util.Set<Process>) field.get(owner)).add(child);
    return child;
  }

  @Test
  public void duplicateLocalReleaseReapsOnlyHandlesRegisteredWithItsInstance() throws Exception {
    ShellService first = new ShellService(), second = new ShellService();
    Child owned = registerFixtureChild(first), other = registerFixtureChild(second);
    first.destroy();
    first.destroy();
    assertEquals(1, owned.stops);
    assertEquals(0, other.stops);
    second.destroy();
    assertEquals(1, other.stops);
  }

  @Test
  public void managerOneWayClosesOnlyThatServiceAndCanRepeat() throws Exception {
    ShellService one = new ShellService(), other = new ShellService();
    assertTrue(transaction(one, 16777115, true));
    assertTrue(transaction(one, 16777115, true));
    assertTrue(one.exec(" ").endsWith("[EXIT=124]"));
    assertTrue(other.exec(" ").endsWith("[EXIT=126]"));
  }

  @Test
  public void legacyClientCloseHasSynchronousReplyAndIsIdempotent() throws Exception {
    ShellService service = new ShellService();
    assertTrue(transaction(service, 16777114, false));
    assertTrue(transaction(service, 16777114, false));
    assertTrue(service.exec(" ").endsWith("[EXIT=124]"));
  }

  @Test
  public void wrongUidCannotCloseOrExecute() {
    ShellService service = new ShellService();
    ShadowBinder.setCallingPid(4321);
    ShadowBinder.setCallingUid(110234);
    assertThrows(SecurityException.class, () -> service.exec(" "));
    assertThrows(SecurityException.class, service::destroy);
    ShadowBinder.setCallingPid(1234);
    assertTrue(service.exec(" ").endsWith("[EXIT=126]"));
  }

  @Test
  public void managerWireRequiresMatchingPrivilegeUid() throws Exception {
    ShellService service = new ShellService();
    ShadowBinder.setCallingPid(0);
    ShadowBinder.setCallingUid(10234);
    assertThrows(SecurityException.class, () -> transaction(service, 16777115, true));
    ShadowBinder.setCallingPid(1234);
    assertTrue(service.exec(" ").endsWith("[EXIT=126]"));
  }

  @Test
  public void timeoutRemovesPendingRecordAndLateConnectionCannotReplaceNewBind() throws Exception {
    ShizukuShell.ensureBound(context);
    ServiceConnection first = Manager.pending;
    var firstArgs = Manager.args;
    timeout();
    assertEquals(List.of(firstArgs), Manager.removed);
    assertEquals(List.of(firstArgs), Manager.detached);
    ShizukuShell.ensureBound(context);
    var secondArgs = Manager.args;
    assertNotEquals(field(firstArgs, "tag"), field(secondArgs, "tag"));
    Endpoint oldEndpoint = new Endpoint(), fresh = new Endpoint();
    first.onServiceConnected(name(), oldEndpoint);
    Manager.pending.onServiceConnected(name(), fresh);
    idle();
    assertTrue(oldEndpoint.closed.await(2, TimeUnit.SECONDS));
    assertEquals(1L, fresh.closed.getCount());
    assertEquals("fixture", ShizukuShell.exec("fixture"));
    assertEquals(0, oldEndpoint.executions);
    assertEquals(1, fresh.executions);
    first.onBindingDied(name());
    idle();
    timeout();
    assertEquals(List.of(firstArgs), Manager.removed);
  }

  @Test
  public void revokedPendingCallbackIsNeverPublished() throws Exception {
    ShizukuShell.ensureBound(context);
    var expected = Manager.args;
    Manager.permitted = false;
    Endpoint endpoint = new Endpoint();
    Manager.pending.onServiceConnected(name(), endpoint);
    idle();
    assertTrue(endpoint.closed.await(2, TimeUnit.SECONDS));
    assertFalse(ShizukuShell.isReady());
    assertTrue(ShizukuShell.exec("fixture").endsWith("[EXIT=124]"));
    assertEquals(0, endpoint.executions);
    assertEquals(List.of(expected), Manager.removed);
  }

  @Test
  public void revokedBoundConnectionReleasesOnlyReturnedBinderWhenRemovalIsDenied()
      throws Exception {
    ShizukuShell.ensureBound(context);
    var expected = Manager.args;
    Endpoint endpoint = new Endpoint();
    Manager.pending.onServiceConnected(name(), endpoint);
    idle();
    assertTrue(ShizukuShell.isReady());
    Manager.permitted = false;
    Manager.rejectRemoval = true;
    assertTrue(ShizukuShell.exec("fixture").endsWith("[EXIT=124]"));
    assertTrue(endpoint.closed.await(2, TimeUnit.SECONDS));
    ShizukuShell.ensureBound(context);
    idle();
    assertEquals(List.of(expected), Manager.removed);
    assertEquals(List.of(expected), Manager.detached);
    assertEquals(0, endpoint.executions);
  }

  @Test
  public void replacedManagerCannotReusePreviouslyReadyService() throws Exception {
    ShizukuShell.ensureBound(context);
    Endpoint previous = new Endpoint();
    Manager.pending.onServiceConnected(name(), previous);
    idle();
    assertTrue(ShizukuShell.isReady());
    Manager.manager = new android.os.Binder();
    assertFalse(ShizukuShell.isReady());
    ShizukuShell.ensureBound(context);
    assertTrue(previous.closed.await(2, TimeUnit.SECONDS));
    assertTrue(Manager.removed.isEmpty());
    assertEquals(0, previous.executions);
    Endpoint fresh = new Endpoint();
    Manager.pending.onServiceConnected(name(), fresh);
    idle();
    assertTrue(ShizukuShell.isReady());
  }

  @Test
  public void grantReturnRetriesPreviouslyRejectedPendingRemovalBeforeBinding() {
    ShizukuShell.ensureBound(context);
    var old = Manager.args;
    Manager.rejectRemoval = true;
    Manager.rejectDetach = true;
    Manager.permitted = false;
    ShizukuShell.ensureBound(context);
    assertEquals(List.of(old), Manager.removed);
    assertEquals(List.of(old), Manager.detached);
    Manager.permitted = true;
    Manager.rejectRemoval = false;
    Manager.rejectDetach = false;
    ShizukuShell.ensureBound(context);
    ShizukuShell.ensureBound(context);
    assertEquals(List.of(old, old), Manager.removed);
    assertEquals(List.of(old, old), Manager.detached);
    assertNotSame(old, Manager.args);
  }

  @Test
  public void profilesUseOwnTagsAndProcessSuffix() throws Exception {
    ShizukuShell.ensureBound(context);
    var first = Manager.args;
    timeout();
    ShadowProcess.setUid(110234);
    ShizukuShell.ensureBound(context);
    assertEquals("shizuku_10234", field(first, "processName"));
    assertEquals("shizuku_110234", field(Manager.args, "processName"));
    assertTrue(field(Manager.args, "tag").startsWith("dsha-device-shell-110234-"));
    assertNotEquals(field(first, "tag"), field(Manager.args, "tag"));
  }
}
