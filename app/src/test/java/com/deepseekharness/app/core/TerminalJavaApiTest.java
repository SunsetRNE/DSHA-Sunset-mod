package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.PtySession;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.runtime.TerminalProcessCloser;
import com.deepseekharness.app.util.ProcessIdentity;
import com.deepseekharness.app.util.TerminalExitPolicy;
import com.deepseekharness.app.util.TerminalReady;
import com.deepseekharness.app.util.TerminalSession;
import com.deepseekharness.app.util.TerminalTabs;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.junit.Test;

/** 基线 127ae7bf 的 Java 黄金契约；迁移前后复用，不依赖 Kotlin 反射或真实 guest。 */
public final class TerminalJavaApiTest {
  @Test
  public void staticEntrypointsAndConstantsRemainOnTheirJavaClasses() throws Exception {
    staticMethod(TerminalSessionOwner.class, "shared", TerminalSessionOwner.class);
    staticMethod(TerminalRuntimeProtection.class, "shared", TerminalRuntimeProtection.class);
    staticMethod(
        SimpleTerminalBackend.class, "create", TerminalSession.Backend.class, ProotBootstrap.class);
    staticMethod(SimpleTerminalBackend.class, "blockMessage", String.class, ProotBootstrap.class);
    staticMethod(
        TerminalProcessCloser.class, "captureSimpleLeader", ProcessIdentity.class, int.class, long.class);
    for (String name : new String[] {"close", "closeSimple"}) {
      staticMethod(TerminalProcessCloser.class, name, void.class, ProcessIdentity.class, long.class);
    }
    staticMethod(TerminalProcessCloser.class, "closeProot", void.class, ProcessIdentity.class);
    staticMethod(TerminalProcessCloser.class, "requireSessionEmpty", void.class, int.class);
    staticMethod(
        TerminalExitPolicy.class,
        "requestProotExit",
        void.class,
        ProcessIdentity.class,
        TerminalExitPolicy.Ports.class);
    staticMethod(
        TerminalExitPolicy.class,
        "close",
        void.class,
        ProcessIdentity.class,
        long.class,
        TerminalExitPolicy.Ports.class);
    staticMethod(
        TerminalExitPolicy.class,
        "requireSessionEmpty",
        void.class,
        int.class,
        TerminalExitPolicy.Ports.class);
    field(TerminalRuntimeProtection.class, "PROMOTION_TIMEOUT_MS", long.class, true, true);
    field(TerminalRuntimeProtection.class, "PREPARATION_TIMEOUT_MS", long.class, true, true);
    // 反射读值也能发现 const 被内联后，声明字段缺失或数值悄然变化。
    assertEquals(8_000L, TerminalRuntimeProtection.class.getField("PROMOTION_TIMEOUT_MS").getLong(null));
    assertEquals(
        120_000L, TerminalRuntimeProtection.class.getField("PREPARATION_TIMEOUT_MS").getLong(null));
    assertEquals(8_000L, TerminalRuntimeProtection.PROMOTION_TIMEOUT_MS);
    assertEquals(120_000L, TerminalRuntimeProtection.PREPARATION_TIMEOUT_MS);
    assertSame(TerminalSessionOwner.shared(), TerminalSessionOwner.shared());
    assertSame(TerminalRuntimeProtection.shared(), TerminalRuntimeProtection.shared());
    assertSame(TerminalRuntimeProtection.shared(), TerminalSessionOwner.shared().protection());
  }

  @Test
  public void publicFieldsKeepJvmFieldEquivalentTypesAndMutability() throws Exception {
    field(TerminalRuntimeProtection.Snapshot.class, "state", TerminalRuntimeProtection.State.class, false, true);
    for (String name : new String[] {"preparing", "creating", "held"}) {
      field(TerminalRuntimeProtection.Snapshot.class, name, int.class, false, true);
    }
    field(TerminalRuntimeProtection.Snapshot.class, "error", String.class, false, true);
    field(TerminalTabs.Tab.class, "id", long.class, false, true);
    field(TerminalTabs.Tab.class, "number", int.class, false, true);
    field(TerminalTabs.Tab.class, "value", Object.class, false, true);
    field(TerminalTabs.CloseSnapshot.class, "state", TerminalTabs.CleanupState.class, false, true);
    field(TerminalTabs.CloseSnapshot.class, "generation", long.class, false, true);
    field(TerminalTabs.CloseSnapshot.class, "error", String.class, false, true);
    field(TerminalSessionOwner.SimpleTab.class, "session", TerminalSession.class, false, true);
    field(TerminalSessionOwner.SimpleTab.class, "buffer", StringBuilder.class, false, true);
    field(TerminalSessionOwner.SimpleTab.class, "draft", String.class, false, false);
    field(TerminalSessionOwner.SimpleTab.class, "cursorStart", int.class, false, false);
    field(TerminalSessionOwner.SimpleTab.class, "cursorEnd", int.class, false, false);

    TerminalRuntimeProtection.Snapshot snapshot =
        new TerminalRuntimeProtection.Snapshot(TerminalRuntimeProtection.State.LOST, 1, 2, 3, "lost");
    assertEquals(TerminalRuntimeProtection.State.LOST, snapshot.state);
    assertEquals(1, snapshot.preparing);
    assertEquals(2, snapshot.creating);
    assertEquals(3, snapshot.held);
    assertEquals("lost", snapshot.error);
    TerminalSessionOwner owner = new TerminalSessionOwner();
    TerminalTabs.Tab<TerminalSessionOwner.SimpleTab> tab = owner.addSimple(neverOpen());
    try {
      tab.value.draft = "未发送";
      tab.value.cursorStart = 1;
      tab.value.cursorEnd = 2;
      tab.value.buffer.append("输出");
      assertEquals("未发送", tab.value.draft);
      assertEquals(1, tab.value.cursorStart);
      assertEquals(2, tab.value.cursorEnd);
      assertEquals("输出", tab.value.output());
      assertNotNull(tab.value.session);
      assertSame(tab.value, owner.currentSimple().value);
    } finally {
      owner.closeSimple(tab.id, 1000);
    }
  }

  @Test
  public void nestedTypesRetainInnerOwnershipAndJavaConstructionShape() throws Exception {
    for (Class<?> type :
        new Class<?>[] {
          TerminalSessionOwner.class,
          TerminalRuntimeProtection.class,
          SimpleTerminalBackend.class,
          TerminalProcessCloser.class,
          TerminalTabs.class,
          TerminalSession.class,
          TerminalExitPolicy.class
        }) {
      assertTrue(type.getName(), Modifier.isPublic(type.getModifiers()));
      assertTrue(type.getName(), Modifier.isFinal(type.getModifiers()));
    }
    inner(TerminalSessionOwner.SimpleTab.class, TerminalSessionOwner.class, false);
    inner(TerminalRuntimeProtection.Ticket.class, TerminalRuntimeProtection.class, false);
    for (Class<?> type :
        new Class<?>[] {TerminalTabs.Tab.class, TerminalTabs.Reservation.class, TerminalTabs.CloseSnapshot.class}) {
      inner(type, TerminalTabs.class, true);
    }
    inner(TerminalRuntimeProtection.Snapshot.class, TerminalRuntimeProtection.class, true);
    inner(TerminalSession.EnvironmentUnavailable.class, TerminalSession.class, true);
    inner(TerminalSession.UncertainStart.class, TerminalSession.class, true);
    inner(TerminalExitPolicy.Failure.class, TerminalExitPolicy.class, true);
    assertTrue(AutoCloseable.class.isAssignableFrom(TerminalRuntimeProtection.Ticket.class));
    assertEquals(IOException.class, TerminalSession.EnvironmentUnavailable.class.getSuperclass());
    assertEquals(IOException.class, TerminalSession.UncertainStart.class.getSuperclass());
    assertEquals(IOException.class, TerminalExitPolicy.Failure.class.getSuperclass());
    // 非 static 内部类的 JVM 构造参数必须保留所属外层实例。
    assertNotNull(
        TerminalSessionOwner.SimpleTab.class
            .getDeclaredConstructor(TerminalSessionOwner.class, TerminalSession.Backend.class));
    assertNotNull(
        TerminalRuntimeProtection.Ticket.class
            .getDeclaredConstructor(
                TerminalRuntimeProtection.class,
                String.class,
                TerminalRuntimeProtection.Kind.class,
                TerminalRuntimeProtection.Admission.class));
    assertNotNull(TerminalTabs.class.getConstructor());
    assertNotNull(TerminalRuntimeProtection.class.getConstructor(LongSupplier.class));
    assertNotNull(
        TerminalSession.class.getConstructor(TerminalSession.Backend.class, Consumer.class, Consumer.class));
    assertNotNull(TerminalSession.EnvironmentUnavailable.class.getConstructor(String.class));
    assertNotNull(TerminalSession.UncertainStart.class.getConstructor(Throwable.class));
    instance(TerminalExitPolicy.Failure.class, "code", TerminalExitPolicy.Code.class);
    IOException cause = new IOException("fork result unknown");
    assertSame(cause, new TerminalSession.UncertainStart(cause).getCause());
    assertEquals("blocked", new TerminalSession.EnvironmentUnavailable("blocked").getMessage());
    enumOrder(TerminalRuntimeProtection.Kind.values(), "PTY", "SIMPLE");
    enumOrder(TerminalRuntimeProtection.State.values(), "PREPARING", "ACTIVE", "LOST", "RELEASED");
    enumOrder(TerminalSession.State.values(), "STOPPED", "STARTING", "READY", "BUSY", "STOPPING", "FAILED");
    enumOrder(TerminalTabs.CleanupState.values(), "NOT_REQUESTED", "CLOSING", "VERIFIED", "UNCONFIRMED");
    enumOrder(TerminalExitPolicy.Signal.values(), "STOP", "CONTINUE", "KILL", "QUIT");
    enumOrder(
        TerminalExitPolicy.Code.values(),
        "INVALID_IDENTITY",
        "IDENTITY_CHANGED",
        "FOREIGN_UID",
        "SCAN_UNAVAILABLE",
        "SESSION_NOT_EMPTY",
        "EXIT_TIMEOUT");
  }

  @Test
  public void backendHasRealJavaDefaultMethodsAndTheirOriginalThrows() throws Exception {
    for (Method method :
        new Method[] {
          instance(TerminalSession.Backend.class, "beginLifetime", AutoCloseable.class),
          instance(TerminalSession.Backend.class, "onReady", void.class, Process.class, TerminalReady.class),
          instance(TerminalSession.Backend.class, "write", void.class, Process.class, String.class)
        }) {
      assertTrue(method.toString(), method.isDefault());
      assertFalse(Modifier.isAbstract(method.getModifiers()));
    }
    Method open = instance(TerminalSession.Backend.class, "open", Process.class);
    Method terminate =
        instance(TerminalSession.Backend.class, "terminate", void.class, Process.class, long.class);
    assertTrue(Modifier.isAbstract(open.getModifiers()));
    assertTrue(Modifier.isAbstract(terminate.getModifiers()));
    exceptions(open, Exception.class);
    exceptions(terminate, Exception.class);
    exceptions(TerminalSession.Backend.class.getMethod("beginLifetime"), Exception.class);
    exceptions(
        TerminalSession.Backend.class.getMethod("onReady", Process.class, TerminalReady.class),
        Exception.class);
    exceptions(
        TerminalSession.Backend.class.getMethod("write", Process.class, String.class), IOException.class);

    // 只实现两个抽象方法；Java 源码与运行时都必须能继承其余三个默认实现。
    TerminalSession.Backend backend = neverOpen();
    BufferProcess process = new BufferProcess();
    assertNull(backend.beginLifetime());
    backend.onReady(process, null);
    backend.write(process, "中文😀\n");
    assertArrayEquals("中文😀\n".getBytes(StandardCharsets.UTF_8), process.bytes.toByteArray());
    assertEquals(1, process.flushes);
  }

  @Test
  public void ownerPublicAndSamePackageEntrypointsRemainUnmangled() throws Exception {
    instance(TerminalSessionOwner.class, "protection", TerminalRuntimeProtection.class);
    instance(TerminalSessionOwner.class, "protectionSnapshot", TerminalRuntimeProtection.Snapshot.class);
    instance(TerminalSessionOwner.class, "ownerEpoch", String.class);
    instance(TerminalSessionOwner.class, "epoch", String.class);
    instance(TerminalSessionOwner.class, "ptyTabs", TerminalTabs.ReadOnly.class);
    instance(TerminalSessionOwner.class, "simpleTabs", TerminalTabs.ReadOnly.class);
    instance(TerminalSessionOwner.class, "currentPty", TerminalTabs.Tab.class);
    instance(TerminalSessionOwner.class, "currentSimple", TerminalTabs.Tab.class);
    instance(TerminalSessionOwner.class, "attachPty", void.class, TerminalSessionOwner.PtyObserver.class);
    instance(TerminalSessionOwner.class, "detachPty", void.class, TerminalSessionOwner.PtyObserver.class);
    instance(TerminalSessionOwner.class, "attachSimple", void.class, TerminalSessionOwner.SimpleObserver.class);
    instance(TerminalSessionOwner.class, "detachSimple", void.class, TerminalSessionOwner.SimpleObserver.class);
    instance(TerminalSessionOwner.class, "addSimpleStateObserver", void.class, Runnable.class);
    instance(TerminalSessionOwner.class, "removeSimpleStateObserver", void.class, Runnable.class);
    for (String kind : new String[] {"Pty", "Simple"}) {
      instance(TerminalSessionOwner.class, "claim" + kind + "InitialCreation", boolean.class);
      instance(TerminalSessionOwner.class, "rename" + kind, boolean.class, long.class, String.class);
      instance(TerminalSessionOwner.class, "is" + kind + "InputAllowed", boolean.class, long.class);
      instance(TerminalSessionOwner.class, "is" + kind + "ProtectionLost", boolean.class, long.class);
      instance(TerminalSessionOwner.class, "select" + kind, boolean.class, long.class);
      instance(TerminalSessionOwner.class, "beginClose" + kind, boolean.class, long.class);
      instance(
          TerminalSessionOwner.class,
          "close" + kind + "Async",
          boolean.class,
          long.class,
          long.class,
          TerminalSessionOwner.CloseCallback.class);
      instance(TerminalSessionOwner.class, "request" + kind + "Shutdown", void.class);
    }
    instance(TerminalSessionOwner.class, "appendSimple", void.class, TerminalSessionOwner.SimpleTab.class, String.class);
    instance(TerminalSessionOwner.SimpleTab.class, "output", String.class);
    instance(TerminalSessionOwner.SimpleTab.class, "clearOutput", void.class);
    instance(TerminalSessionOwner.PtyObserver.class, "onTabsChanged", void.class);
    instance(TerminalSessionOwner.SimpleObserver.class, "onTabsChanged", void.class);
    instance(TerminalSessionOwner.SimpleObserver.class, "onOutput", void.class, TerminalSessionOwner.SimpleTab.class);
    instance(TerminalSessionOwner.SimpleObserver.class, "onState", void.class, TerminalSessionOwner.SimpleTab.class);
    instance(
        TerminalSessionOwner.CloseCallback.class,
        "onClosed",
        void.class,
        String.class,
        TerminalRuntimeProtection.Kind.class,
        long.class,
        String.class);

    packageConstructor(TerminalSessionOwner.class);
    packageConstructor(TerminalSessionOwner.class, TerminalRuntimeProtection.class);
    packageEntry(TerminalSessionOwner.class, "addPty", TerminalTabs.Tab.class, PtySession.class);
    packageEntry(TerminalSessionOwner.class, "addSimple", TerminalTabs.Tab.class, TerminalSession.Backend.class);
    packageEntry(
        TerminalSessionOwner.class,
        "createPty",
        TerminalTabs.Tab.class,
        TerminalRuntimeProtection.Ticket.class,
        TerminalSessionOwner.PtyStart.class);
    packageEntry(
        TerminalSessionOwner.class,
        "createPty",
        TerminalTabs.Tab.class,
        TerminalRuntimeProtection.Ticket.class,
        String[].class,
        TerminalSessionOwner.PtyStart.class);
    packageEntry(
        TerminalSessionOwner.class,
        "closeSimple",
        void.class,
        long.class,
        long.class,
        TerminalSessionOwner.SimpleStop.class);
    instance(
        TerminalSessionOwner.PtyStart.class,
        "start",
        PtySession.class,
        Runnable.class,
        Runnable.class,
        Runnable.class,
        Consumer.class,
        String[].class);
    exceptions(
        instance(TerminalSessionOwner.SimpleStop.class, "stop", boolean.class, TerminalSessionOwner.SimpleTab.class, long.class),
        InterruptedException.class);

    TerminalSessionOwner owner = new TerminalSessionOwner();
    TerminalSessionOwner.PtyStart start =
        (verified, changed, beforeFork, allocated, command) -> {
          throw new AssertionError("不得进入真实 PTY 启动路径");
        };
    // 同包 Java 调用编译也属于契约；不能只靠反射找到 Kotlin 的重命名入口。
    assertThrows(IOException.class, () -> owner.createPty(null, start));
    assertThrows(IOException.class, () -> owner.createPty(null, new String[] {"fixture"}, start));
    assertThrows(IllegalArgumentException.class, () -> owner.addPty(null));
    assertNotNull(new TerminalSessionOwner(new TerminalRuntimeProtection(() -> 0L)).protection());
  }

  @Test
  public void protectionSessionAndTabsInstanceSignaturesRemainJavaCallable() throws Exception {
    instance(
        TerminalRuntimeProtection.class,
        "prepare",
        TerminalRuntimeProtection.Ticket.class,
        String.class,
        TerminalRuntimeProtection.Kind.class,
        TerminalRuntimeProtection.Admission.class);
    instance(TerminalRuntimeProtection.class, "matches", boolean.class, String.class);
    instance(TerminalRuntimeProtection.class, "promoted", boolean.class, String.class, Object.class, BooleanSupplier.class);
    instance(TerminalRuntimeProtection.class, "isProtected", boolean.class, TerminalRuntimeProtection.Ticket.class);
    instance(TerminalRuntimeProtection.class, "expire", void.class, TerminalRuntimeProtection.Ticket.class);
    instance(TerminalRuntimeProtection.class, "reject", void.class, String.class, String.class);
    instance(TerminalRuntimeProtection.class, "serviceDestroyed", void.class, Object.class);
    instance(TerminalRuntimeProtection.class, "hasDemand", boolean.class);
    instance(TerminalRuntimeProtection.class, "needsService", boolean.class, Object.class);
    instance(TerminalRuntimeProtection.class, "snapshot", TerminalRuntimeProtection.Snapshot.class);
    instance(TerminalRuntimeProtection.class, "addObserver", void.class, Runnable.class);
    instance(TerminalRuntimeProtection.class, "removeObserver", void.class, Runnable.class);
    for (String name : new String[] {"retain", "finishCreate"}) {
      packageEntry(TerminalRuntimeProtection.class, name, void.class, TerminalRuntimeProtection.Ticket.class);
    }
    instance(TerminalRuntimeProtection.Ticket.class, "id", String.class);
    instance(TerminalRuntimeProtection.Ticket.class, "ownerEpoch", String.class);
    instance(TerminalRuntimeProtection.Ticket.class, "kind", TerminalRuntimeProtection.Kind.class);
    instance(TerminalRuntimeProtection.Ticket.class, "generation", long.class);
    instance(TerminalRuntimeProtection.Ticket.class, "hasStarted", boolean.class);
    instance(TerminalRuntimeProtection.Ticket.class, "state", TerminalRuntimeProtection.State.class);
    instance(TerminalRuntimeProtection.Ticket.class, "error", String.class);
    instance(TerminalRuntimeProtection.Ticket.class, "cancel", boolean.class);
    exceptions(instance(TerminalRuntimeProtection.Ticket.class, "close", void.class));
    instance(TerminalRuntimeProtection.Admission.class, "onReady", void.class, TerminalRuntimeProtection.Ticket.class);
    instance(TerminalRuntimeProtection.Admission.class, "onRejected", void.class, TerminalRuntimeProtection.Ticket.class, String.class);

    instance(TerminalSession.class, "state", TerminalSession.State.class);
    for (String name : new String[] {"ensureStarted", "cancelAndRestart", "shutdown"}) {
      instance(TerminalSession.class, name, void.class);
    }
    instance(TerminalSession.class, "submit", boolean.class, String.class);
    instance(TerminalTabs.class, "readOnly", TerminalTabs.ReadOnly.class);
    instance(TerminalTabs.class, "reserveIdentity", TerminalTabs.Reservation.class);
    instance(TerminalTabs.class, "cancelIdentity", void.class, TerminalTabs.Reservation.class);
    instance(TerminalTabs.class, "add", TerminalTabs.Tab.class, Object.class);
    instance(TerminalTabs.class, "publish", TerminalTabs.Tab.class, TerminalTabs.Reservation.class, Object.class);
    for (Class<?> type : new Class<?>[] {TerminalTabs.class, TerminalTabs.ReadOnly.class}) {
      instance(type, "snapshot", List.class);
      instance(type, "current", TerminalTabs.Tab.class);
      instance(type, "find", TerminalTabs.Tab.class, long.class);
      instance(type, "wasInitialized", boolean.class);
    }
    instance(TerminalTabs.class, "select", boolean.class, long.class);
    instance(TerminalTabs.class, "beginClose", boolean.class, long.class);
    instance(TerminalTabs.class, "closeFailed", void.class, long.class);
    instance(TerminalTabs.class, "closeFailed", void.class, long.class, String.class);
    instance(TerminalTabs.class, "closeFailed", boolean.class, long.class, long.class, String.class);
    instance(TerminalTabs.class, "rename", boolean.class, long.class, String.class);
    instance(TerminalTabs.class, "remove", void.class, long.class);
    for (String name : new String[] {"isClosing", "isUnconfirmed", "isCloseUnconfirmed"}) {
      instance(TerminalTabs.Tab.class, name, boolean.class);
    }
    instance(TerminalTabs.Tab.class, "cleanupState", TerminalTabs.CleanupState.class);
    instance(TerminalTabs.Tab.class, "closeSnapshot", TerminalTabs.CloseSnapshot.class);
    for (String name : new String[] {"error", "closeError", "customTitle"}) {
      instance(TerminalTabs.Tab.class, name, String.class);
    }
  }

  @Test
  public void checkedExceptionDeclarationsSurviveJavaToKotlinMigration() throws Exception {
    for (String name : new String[] {"closePty", "closeSimple"}) {
      exceptions(
          instance(TerminalSessionOwner.class, name, void.class, long.class, long.class),
          IOException.class,
          InterruptedException.class);
    }
    for (String name : new String[] {"closeAllAndConfirm", "closeAllPtyAndConfirm", "closeAllSimpleAndConfirm"}) {
      exceptions(
          instance(TerminalSessionOwner.class, name, void.class, long.class),
          IOException.class,
          InterruptedException.class);
    }
    exceptions(
        packageEntry(TerminalSessionOwner.class, "closeSimple", void.class, long.class, long.class, TerminalSessionOwner.SimpleStop.class),
        IOException.class,
        InterruptedException.class);
    for (Class<?>[] parameters :
        new Class<?>[][] {
          {TerminalRuntimeProtection.Ticket.class, ProotBootstrap.class, int.class, int.class, PtySession.Listener.class},
          {TerminalRuntimeProtection.Ticket.class, ProotBootstrap.class, int.class, int.class, PtySession.Listener.class, String[].class},
          {TerminalRuntimeProtection.Ticket.class, TerminalSessionOwner.PtyStart.class},
          {TerminalRuntimeProtection.Ticket.class, String[].class, TerminalSessionOwner.PtyStart.class}
        }) {
      Method method = TerminalSessionOwner.class.getDeclaredMethod("createPty", parameters);
      assertEquals(TerminalTabs.Tab.class, method.getReturnType());
      assertFalse(Modifier.isStatic(method.getModifiers()));
      assertFalse(Modifier.isPrivate(method.getModifiers()));
      if (parameters[1] == ProotBootstrap.class) assertTrue(Modifier.isPublic(method.getModifiers()));
      exceptions(method, IOException.class);
    }
    exceptions(
        instance(TerminalSessionOwner.class, "createSimple", TerminalTabs.Tab.class, TerminalRuntimeProtection.Ticket.class, TerminalSession.Backend.class),
        IOException.class);
    exceptions(
        instance(TerminalRuntimeProtection.class, "beginCreate", void.class, TerminalRuntimeProtection.Ticket.class, String.class, TerminalRuntimeProtection.Kind.class),
        IOException.class);
    exceptions(
        instance(TerminalRuntimeProtection.class, "requireHeld", void.class, TerminalRuntimeProtection.Ticket.class),
        IOException.class);
    exceptions(instance(TerminalRuntimeProtection.class, "releaseVerified", void.class, TerminalRuntimeProtection.Ticket.class));
    for (String name : new String[] {"shutdownAndWait", "disposeAndWait"}) {
      exceptions(instance(TerminalSession.class, name, boolean.class, long.class), InterruptedException.class);
    }
    exceptions(TerminalProcessCloser.class.getMethod("captureSimpleLeader", int.class, long.class), IOException.class);
    exceptions(TerminalProcessCloser.class.getMethod("closeProot", ProcessIdentity.class), IOException.class);
    exceptions(TerminalProcessCloser.class.getMethod("requireSessionEmpty", int.class), IOException.class);
    for (String name : new String[] {"close", "closeSimple"}) {
      exceptions(TerminalProcessCloser.class.getMethod(name, ProcessIdentity.class, long.class), IOException.class, InterruptedException.class);
    }
    exceptions(TerminalExitPolicy.class.getMethod("requestProotExit", ProcessIdentity.class, TerminalExitPolicy.Ports.class), IOException.class);
    exceptions(TerminalExitPolicy.class.getMethod("requireSessionEmpty", int.class, TerminalExitPolicy.Ports.class), IOException.class);
    exceptions(TerminalExitPolicy.class.getMethod("close", ProcessIdentity.class, long.class, TerminalExitPolicy.Ports.class), IOException.class, InterruptedException.class);
    Class<?> ports = TerminalExitPolicy.Ports.class;
    exceptions(instance(ports, "read", ProcessIdentity.class, int.class, int.class), IOException.class);
    exceptions(instance(ports, "belongsToSession", boolean.class, int.class, int.class), IOException.class);
    exceptions(instance(ports, "uid", int.class, int.class), IOException.class);
    exceptions(instance(ports, "pids", int[].class), IOException.class);
    exceptions(instance(ports, "signal", void.class, int.class, TerminalExitPolicy.Signal.class), IOException.class);
    exceptions(instance(ports, "pause", void.class, long.class), InterruptedException.class);
    exceptions(instance(ports, "ownUid", int.class));
    exceptions(instance(ports, "now", long.class));
  }

  @Test
  public void synchronizedMethodsRetainTheirJvmMonitorFlags() throws Exception {
    synchronizedMethod(TerminalSessionOwner.class, "claimPtyInitialCreation");
    synchronizedMethod(TerminalSessionOwner.class, "claimSimpleInitialCreation");
    synchronizedMethod(TerminalSessionOwner.SimpleTab.class, "clearOutput");
    synchronizedMethod(TerminalSessionOwner.SimpleTab.class, "output");
    synchronizedMethod(TerminalSession.class, "disposeAndWait", long.class);
    synchronizedMethod(TerminalRuntimeProtection.class, "matches", String.class);
    synchronizedMethod(TerminalRuntimeProtection.class, "requireHeld", TerminalRuntimeProtection.Ticket.class);
    synchronizedMethod(TerminalRuntimeProtection.class, "isProtected", TerminalRuntimeProtection.Ticket.class);
    synchronizedMethod(TerminalRuntimeProtection.class, "hasDemand");
    synchronizedMethod(TerminalRuntimeProtection.class, "needsService", Object.class);
    synchronizedMethod(TerminalRuntimeProtection.class, "snapshot");
    synchronizedMethod(TerminalTabs.class, "reserveIdentity");
    synchronizedMethod(TerminalTabs.class, "cancelIdentity", TerminalTabs.Reservation.class);
    synchronizedMethod(TerminalTabs.class, "add", Object.class);
    synchronizedMethod(TerminalTabs.class, "publish", TerminalTabs.Reservation.class, Object.class);
    synchronizedMethod(TerminalTabs.class, "snapshot");
    synchronizedMethod(TerminalTabs.class, "current");
    synchronizedMethod(TerminalTabs.class, "find", long.class);
    synchronizedMethod(TerminalTabs.class, "select", long.class);
    synchronizedMethod(TerminalTabs.class, "beginClose", long.class);
    synchronizedMethod(TerminalTabs.class, "closeFailed", long.class);
    synchronizedMethod(TerminalTabs.class, "closeFailed", long.class, String.class);
    synchronizedMethod(TerminalTabs.class, "closeFailed", long.class, long.class, String.class);
    synchronizedMethod(TerminalTabs.class, "rename", long.class, String.class);
    synchronizedMethod(TerminalTabs.class, "remove", long.class);
    synchronizedMethod(TerminalTabs.class, "wasInitialized");
  }

  @Test(timeout = 5000)
  public void externalObjectMonitorsRemainReentrantForJavaCallersAndSuppliers() throws Exception {
    AtomicReference<TerminalRuntimeProtection> holder = new AtomicReference<>();
    AtomicInteger underMonitor = new AtomicInteger();
    TerminalRuntimeProtection protection =
        new TerminalRuntimeProtection(
            () -> {
              TerminalRuntimeProtection value = holder.get();
              assertTrue("clock 回调必须持有原 protection 对象监视器", Thread.holdsLock(value));
              value.hasDemand(); // 同一监视器内再次进入 synchronized 方法。
              underMonitor.incrementAndGet();
              return 1L;
            });
    holder.set(protection);
    TerminalRuntimeProtection.Ticket ticket;
    synchronized (protection) {
      ticket = protection.prepare("owner", TerminalRuntimeProtection.Kind.SIMPLE, admission());
      assertTrue(protection.matches(ticket.id()));
      assertEquals(1, protection.snapshot().preparing);
    }
    assertTrue(underMonitor.get() >= 2);
    BooleanSupplier current =
        () -> {
          assertTrue("服务身份回调必须持有 protection 对象监视器", Thread.holdsLock(protection));
          assertTrue(protection.hasDemand());
          assertFalse(ticket.hasStarted()); // Ticket 也重入所属 protection 的同一监视器。
          return true;
        };
    assertTrue(protection.promoted(ticket.id(), new Object(), current));
    assertTrue(protection.isProtected(ticket));
    assertEquals(TerminalRuntimeProtection.State.ACTIVE, protection.snapshot().state);
    assertTrue(ticket.cancel());

    TerminalTabs<String> tabs = new TerminalTabs<>();
    synchronized (tabs) {
      TerminalTabs.Tab<String> tab = tabs.add("value"); // add -> publish/reserveIdentity 嵌套进入。
      assertSame(tab, tabs.current()); // current -> find 嵌套进入。
      assertTrue(tabs.beginClose(tab.id));
      tabs.closeFailed(tab.id, "unknown");
      assertSame(tab, tabs.readOnly().find(tab.id));
      tabs.remove(tab.id);
    }
    TerminalSessionOwner owner = new TerminalSessionOwner();
    synchronized (owner) {
      assertTrue(owner.claimPtyInitialCreation());
      assertFalse(owner.claimPtyInitialCreation());
      assertTrue(owner.claimSimpleInitialCreation());
    }
    TerminalTabs.Tab<TerminalSessionOwner.SimpleTab> tab = owner.addSimple(neverOpen());
    try {
      synchronized (tab.value) {
        owner.appendSimple(tab.value, "before");
        assertEquals("before", tab.value.output());
        tab.value.clearOutput();
        assertEquals("", tab.value.output());
      }
      synchronized (tab.value.session) {
        assertTrue(tab.value.session.disposeAndWait(1000));
        assertTrue(tab.value.session.disposeAndWait(1000));
      }
    } finally {
      owner.closeSimple(tab.id, 1000);
    }
  }

  @Test
  public void cancellationCallbackCanPrepareReplacementWithoutLatePromotionResurrectingOldTicket() {
    TerminalRuntimeProtection protection = new TerminalRuntimeProtection(() -> 1L);
    AtomicInteger ready = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    AtomicReference<TerminalRuntimeProtection.Ticket> replacement = new AtomicReference<>();
    TerminalRuntimeProtection.Ticket old =
        protection.prepare(
            "owner",
            TerminalRuntimeProtection.Kind.SIMPLE,
            new TerminalRuntimeProtection.Admission() {
              @Override
              public void onReady(TerminalRuntimeProtection.Ticket ticket) {
                ready.incrementAndGet();
              }

              @Override
              public void onRejected(TerminalRuntimeProtection.Ticket ticket, String reason) {
                assertEquals("TERMINAL_CREATION_CANCELLED", reason);
                assertEquals(TerminalRuntimeProtection.State.RELEASED, ticket.state());
                rejected.incrementAndGet();
                replacement.set(protection.prepare("owner", TerminalRuntimeProtection.Kind.SIMPLE, admission()));
              }
            });
    assertTrue(old.cancel());
    old.close();
    assertFalse(old.cancel());
    assertFalse(protection.promoted(old.id(), new Object(), () -> true));
    protection.reject(old.id(), "late rejection");
    assertEquals(0, ready.get());
    assertEquals(1, rejected.get());
    TerminalRuntimeProtection.Ticket fresh = replacement.get();
    assertNotNull(fresh);
    try {
      assertTrue(fresh.generation() > old.generation());
      assertNotEquals(old.id(), fresh.id());
      assertTrue(protection.matches(fresh.id()));
      assertTrue(protection.promoted(fresh.id(), new Object(), () -> true));
      assertEquals(TerminalRuntimeProtection.State.ACTIVE, fresh.state());
      assertEquals(1, protection.snapshot().preparing);
    } finally {
      fresh.close();
    }
    assertFalse(protection.hasDemand());
  }

  @Test
  public void unconfirmedCloseKeepsIdentityAndVerifiedDuplicateNeverReplaysStop() throws Exception {
    TerminalSessionOwner owner = new TerminalSessionOwner();
    TerminalTabs.Tab<TerminalSessionOwner.SimpleTab> tab = owner.addSimple(neverOpen());
    AtomicInteger attempts = new AtomicInteger();
    TerminalSessionOwner.SimpleStop unknown =
        (value, timeout) -> {
          assertSame(tab.value, value);
          assertEquals(7L, timeout);
          attempts.incrementAndGet();
          return false;
        };
    try {
      assertTrue(owner.beginCloseSimple(tab.id));
      TerminalTabs.CloseSnapshot first = tab.closeSnapshot();
      assertFalse(owner.beginCloseSimple(tab.id));
      assertThrows(IOException.class, () -> owner.closeSimple(tab.id, 7, unknown));
      assertEquals(1, attempts.get());
      assertSame(tab, owner.simpleTabs().find(tab.id));
      assertEquals(TerminalTabs.CleanupState.UNCONFIRMED, tab.cleanupState());
      assertFalse(owner.isSimpleInputAllowed(tab.id));
      assertEquals(TerminalTabs.CleanupState.CLOSING, first.state); // 已发出的快照不能被原地修改。
      assertTrue(owner.beginCloseSimple(tab.id));
      assertTrue(tab.closeSnapshot().generation > first.generation);
      TerminalSessionOwner.SimpleStop verified =
          (value, timeout) -> {
            attempts.incrementAndGet();
            return true;
          };
      owner.closeSimple(tab.id, 7, verified);
      owner.closeSimple(tab.id, 7, verified);
      assertEquals(2, attempts.get());
      assertFalse(owner.beginCloseSimple(tab.id));
      assertNull(owner.simpleTabs().find(tab.id));
      assertEquals(TerminalTabs.CleanupState.VERIFIED, tab.cleanupState());
      assertEquals(1, tab.number);
    } finally {
      // 注入关闭证明不处置 TerminalSession 自身；本夹具从未启动后端，显式收束其 executor。
      assertTrue(tab.value.session.disposeAndWait(1000));
      owner.closeSimple(tab.id, 1000);
    }
  }

  @Test
  public void unknownSecondEmptyScanPropagatesOriginalFailureWithoutSignalOrAutomaticRetry() {
    IOException unknown = new IOException("second scan unavailable");
    AtomicInteger scans = new AtomicInteger();
    AtomicInteger pauses = new AtomicInteger();
    TerminalExitPolicy.Ports ports =
        new TerminalExitPolicy.Ports() {
          @Override
          public ProcessIdentity read(int pid, int session) {
            throw new AssertionError("空快照不应读取进程");
          }

          @Override
          public boolean belongsToSession(int pid, int session) {
            throw new AssertionError("空快照不应检查 SID");
          }

          @Override
          public int uid(int pid) {
            throw new AssertionError("空快照不应检查 UID");
          }

          @Override
          public int ownUid() {
            throw new AssertionError("空快照不应检查 UID");
          }

          @Override
          public int[] pids() throws IOException {
            if (scans.incrementAndGet() == 1) return new int[0];
            throw unknown;
          }

          @Override
          public void signal(int pid, TerminalExitPolicy.Signal signal) {
            throw new AssertionError("UNKNOWN 不授权发送信号");
          }

          @Override
          public long now() {
            return 0;
          }

          @Override
          public void pause(long millis) {
            assertEquals(20L, millis);
            pauses.incrementAndGet();
          }
        };
    assertSame(
        unknown,
        assertThrows(IOException.class, () -> TerminalExitPolicy.requireSessionEmpty(31, ports)));
    assertEquals(2, scans.get());
    assertEquals(1, pauses.get());
  }

  private static Method instance(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
      throws Exception {
    Method method = owner.getDeclaredMethod(name, parameters);
    assertEquals(method.toString(), result, method.getReturnType());
    assertTrue(method.toString(), Modifier.isPublic(method.getModifiers()));
    assertFalse(method.toString(), Modifier.isStatic(method.getModifiers()));
    return method;
  }

  private static void staticMethod(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
      throws Exception {
    Method method = owner.getDeclaredMethod(name, parameters);
    assertEquals(method.toString(), result, method.getReturnType());
    assertTrue(method.toString(), Modifier.isPublic(method.getModifiers()));
    assertTrue(method.toString(), Modifier.isStatic(method.getModifiers()));
  }

  private static Method packageEntry(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
      throws Exception {
    Method method = owner.getDeclaredMethod(name, parameters);
    assertEquals(method.toString(), result, method.getReturnType());
    assertFalse(method.toString(), Modifier.isStatic(method.getModifiers()));
    assertFalse(method.toString(), Modifier.isPrivate(method.getModifiers()));
    assertFalse(method.toString(), Modifier.isProtected(method.getModifiers()));
    return method;
  }

  private static void packageConstructor(Class<?> owner, Class<?>... parameters) throws Exception {
    Constructor<?> constructor = owner.getDeclaredConstructor(parameters);
    assertFalse(Modifier.isPrivate(constructor.getModifiers()));
    assertFalse(Modifier.isProtected(constructor.getModifiers()));
  }

  private static void exceptions(Method method, Class<?>... expected) {
    assertArrayEquals(method.toString(), expected, method.getExceptionTypes());
  }

  private static void field(Class<?> owner, String name, Class<?> type, boolean isStatic, boolean isFinal)
      throws Exception {
    Field field = owner.getField(name);
    assertSame(owner, field.getDeclaringClass());
    assertEquals(field.toString(), type, field.getType());
    assertTrue(field.toString(), Modifier.isPublic(field.getModifiers()));
    assertEquals(field.toString(), isStatic, Modifier.isStatic(field.getModifiers()));
    assertEquals(field.toString(), isFinal, Modifier.isFinal(field.getModifiers()));
  }

  private static void inner(Class<?> type, Class<?> owner, boolean isStatic) {
    assertTrue(type.isMemberClass());
    assertSame(owner, type.getDeclaringClass());
    assertEquals(type.getName(), isStatic, Modifier.isStatic(type.getModifiers()));
    assertTrue(type.getName(), Modifier.isFinal(type.getModifiers()));
    assertTrue(type.getName(), Modifier.isPublic(type.getModifiers()));
  }

  private static void synchronizedMethod(Class<?> owner, String name, Class<?>... parameters)
      throws Exception {
    Method method = owner.getDeclaredMethod(name, parameters);
    assertTrue(method.toString(), Modifier.isSynchronized(method.getModifiers()));
    assertFalse(method.toString(), Modifier.isStatic(method.getModifiers()));
  }

  private static void enumOrder(Enum<?>[] values, String... names) {
    assertEquals(names.length, values.length);
    for (int index = 0; index < names.length; index++) {
      assertEquals(names[index], values[index].name());
      assertEquals(index, values[index].ordinal());
    }
  }

  private static TerminalRuntimeProtection.Admission admission() {
    return new TerminalRuntimeProtection.Admission() {
      @Override
      public void onReady(TerminalRuntimeProtection.Ticket ticket) {}

      @Override
      public void onRejected(TerminalRuntimeProtection.Ticket ticket, String reason) {}
    };
  }

  private static TerminalSession.Backend neverOpen() {
    return new TerminalSession.Backend() {
      @Override
      public Process open() {
        throw new AssertionError("Java 契约测试不得启动 guest");
      }

      @Override
      public void terminate(Process process, long group) {
        throw new AssertionError("未启动的夹具不得回收进程");
      }
    };
  }

  private static final class BufferProcess extends Process {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int flushes;
    final OutputStream stdin =
        new OutputStream() {
          @Override
          public void write(int value) {
            bytes.write(value);
          }

          @Override
          public void flush() {
            flushes++;
          }
        };

    @Override
    public OutputStream getOutputStream() {
      return stdin;
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {
      throw new AssertionError("字节缓冲夹具不得进入进程销毁路径");
    }
  }
}
