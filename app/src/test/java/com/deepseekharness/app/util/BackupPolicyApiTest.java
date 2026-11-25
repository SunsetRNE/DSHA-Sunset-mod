package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Java 调用边界：基线与候选共用，不以 Kotlin 特有辅助成员替代旧签名。 */
public final class BackupPolicyApiTest {
  private static void method(
      Class<?> owner,
      String name,
      Class<?> result,
      boolean isStatic,
      boolean synchronizedMethod,
      Class<?>... parameters)
      throws Exception {
    Method method = owner.getDeclaredMethod(name, parameters);
    assertEquals(result, method.getReturnType());
    assertTrue(Modifier.isPublic(method.getModifiers()));
    assertEquals(isStatic, Modifier.isStatic(method.getModifiers()));
    assertEquals(synchronizedMethod, Modifier.isSynchronized(method.getModifiers()));
    assertFalse(Modifier.isAbstract(method.getModifiers()));
    assertFalse("原 Java 方法不能新增 final: " + method, Modifier.isFinal(method.getModifiers()));
    assertArrayEquals(new Class<?>[0], method.getExceptionTypes());
  }

  private static void field(Class<?> owner, String name, Class<?> type, boolean isStatic)
      throws Exception {
    Field field = owner.getDeclaredField(name);
    assertEquals(type, field.getType());
    assertTrue(Modifier.isPublic(field.getModifiers()));
    assertTrue(Modifier.isFinal(field.getModifiers()));
    assertEquals(isStatic, Modifier.isStatic(field.getModifiers()));
    assertFalse(Modifier.isVolatile(field.getModifiers()));
  }

  @Test
  public void utilityConstructorsAndStaticSignaturesStayCallable() throws Exception {
    for (Class<?> owner :
        new Class<?>[] {
          BackupExportGuidance.class,
          BackupFileNames.class,
          BackupHealth.class,
          BackupScope.class,
          BackupScopes.class,
          BackupTaskKinds.class
        }) {
      assertTrue(Modifier.isPublic(owner.getModifiers()));
      assertTrue(Modifier.isFinal(owner.getModifiers()));
      Constructor<?> constructor = owner.getDeclaredConstructor();
      assertTrue(Modifier.isPrivate(constructor.getModifiers()));
      assertArrayEquals(new Class<?>[0], constructor.getExceptionTypes());
    }
    method(
        BackupExportGuidance.class,
        "classify",
        BackupExportGuidance.Kind.class,
        true,
        false,
        String.class);
    method(
        BackupExportGuidance.class,
        "message",
        String.class,
        true,
        false,
        String.class,
        boolean.class);
    method(BackupFileNames.class, "portableName", String.class, true, false);
    method(BackupFileNames.class, "exportMimeType", String.class, true, false, String.class);
    method(BackupFileNames.class, "discoverable", boolean.class, true, false, String.class);
    method(
        BackupHealth.class,
        "evaluate",
        BackupHealth.Level.class,
        true,
        false,
        long.class,
        String.class,
        boolean.class,
        boolean.class,
        long.class);
    method(BackupHealth.class, "ageDays", long.class, true, false, long.class, long.class);
    method(BackupHealth.class, "severity", int.class, true, false, BackupHealth.Level.class);
    method(BackupScope.class, "excludesNativeGrantState", boolean.class, true, false, String.class);
    for (String name : new String[] {"id", "fileNamePrefix", "label", "describe", "restoreImpact"})
      method(BackupScope.class, name, String.class, true, false, int.class);
    method(BackupScope.class, "fromId", int.class, true, false, String.class);
    method(BackupScope.class, "fromFileName", int.class, true, false, String.class);
    method(BackupScope.class, "archiveName", String.class, true, false, int.class, String.class);
    for (String name :
        new String[] {
          "visibleToLegacyScan",
          "includesWorkdirFiles",
          "includesPluginSrc",
          "needsPublicDataSnapshot"
        }) method(BackupScope.class, name, boolean.class, true, false, int.class);
    method(BackupScope.class, "dshPaths", String[].class, true, false, int.class);
    method(BackupScope.class, "mergeSubdirs", String[].class, true, false, int.class);
    method(BackupTaskKinds.class, "normalize", String.class, true, false, String.class);
    method(
        BackupTaskKinds.class, "display", String.class, true, false, String.class, boolean.class);
    method(BackupTaskKinds.class, "is", boolean.class, true, false, String.class, String.class);
  }

  @Test
  public void publicStaticFieldsRetainTypesValuesAndGenericSignatures() throws Exception {
    field(BackupFileNames.class, "EXTENSION", String.class, true);
    field(BackupFileNames.class, "MIME_TYPE", String.class, true);
    assertEquals(".tar.gz", BackupFileNames.EXTENSION);
    assertEquals("application/gzip", BackupFileNames.MIME_TYPE);
    field(BackupHealth.class, "STALE_DAYS", int.class, true);
    assertEquals(7, BackupHealth.STALE_DAYS);
    for (String name : new String[] {"FULL", "SESSIONS", "PLUGINS", "SETTINGS"})
      field(BackupScope.class, name, int.class, true);
    assertEquals(0, BackupScope.FULL);
    assertEquals(1, BackupScope.SESSIONS);
    assertEquals(2, BackupScope.PLUGINS);
    assertEquals(3, BackupScope.SETTINGS);
    field(BackupScope.class, "ALL", int[].class, true);
    field(BackupScope.class, "PUB_SNAPSHOT_DIR", String.class, true);
    for (String name : new String[] {"APPLICATION", "SESSIONS", "SETTINGS", "PLUGINS", "PROJECTS"})
      field(BackupScopes.class, name, String.class, true);
    field(BackupScopes.class, "ORDERED", List.class, true);
    field(BackupScopes.class, "ALL", Set.class, true);
    assertEquals(
        "java.util.List<java.lang.String>",
        BackupScopes.class.getField("ORDERED").getGenericType().getTypeName());
    assertEquals(
        "java.util.Set<java.lang.String>",
        BackupScopes.class.getField("ALL").getGenericType().getTypeName());
    for (String name :
        new String[] {
          "BACKUP",
          "RESTORE",
          "REBUILD",
          "UPDATE",
          "RECOVER",
          "FACTORY_RESET",
          "ROLLBACK",
          "SELECT_HOME",
          "RESET",
          "REPAIR",
          "REMOVE_PLUGIN",
          "ISOLATE_PLUGIN_JOURNALS",
          "RECORD"
        }) field(BackupTaskKinds.class, name, String.class, true);
  }

  @Test
  public void enumOrderAndNestedTypeShapeRemainStable() {
    assertArrayEquals(
        new String[] {
          "NONE",
          "BUSY",
          "WORKERS_CLOSED",
          "FACTORY_RESET_PENDING",
          "MAINTENANCE_PENDING",
          "INVALID_SELECTION",
          "PASSWORD_INVALID",
          "RETAINED_KEY",
          "SOURCE_COPY",
          "PRIVATE_STORAGE",
          "WORKER_UNAVAILABLE",
          "UNKNOWN"
        },
        names(BackupExportGuidance.Kind.values()));
    assertArrayEquals(
        new String[] {"RUNNING", "PENDING", "FAILED", "NEVER", "STALE", "PROTECTED"},
        names(BackupHealth.Level.values()));
    assertArrayEquals(
        new String[] {
          "IDLE", "RUNNING", "PREVIEW", "SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED"
        },
        names(BackupTaskState.Status.values()));
    for (Class<?> nested :
        new Class<?>[] {
          BackupExportGuidance.Kind.class,
          BackupHealth.Level.class,
          BackupTaskState.Status.class,
          BackupTaskState.Snapshot.class,
          ScheduledBackupOwner.Ticket.class
        }) {
      assertTrue(Modifier.isPublic(nested.getModifiers()));
      assertTrue(Modifier.isStatic(nested.getModifiers()));
      assertTrue(Modifier.isFinal(nested.getModifiers()));
    }
  }

  private static String[] names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
  }

  @Test
  public void taskStateRetainsInstanceMonitorAndSnapshotFields() throws Exception {
    assertTrue(Modifier.isFinal(BackupTaskState.class.getModifiers()));
    assertTrue(Modifier.isPublic(BackupTaskState.class.getDeclaredConstructor().getModifiers()));
    method(BackupTaskState.class, "start", long.class, false, true, String.class);
    method(BackupTaskState.class, "busy", boolean.class, false, true);
    method(
        BackupTaskState.class,
        "update",
        void.class,
        false,
        true,
        long.class,
        BackupTaskState.Status.class,
        String.class);
    method(BackupTaskState.class, "confirm", boolean.class, false, true, long.class);
    method(BackupTaskState.class, "snapshot", BackupTaskState.Snapshot.class, false, true);
    method(BackupTaskState.class, "restoreStage", void.class, false, true, String.class);
    method(
        BackupTaskState.class,
        "restore",
        void.class,
        false,
        true,
        long.class,
        String.class,
        BackupTaskState.Status.class,
        String.class);
    method(BackupTaskState.class, "reset", void.class, false, true);
    field(BackupTaskState.Snapshot.class, "id", long.class, false);
    for (String name : new String[] {"kind", "detail", "lastStage"})
      field(BackupTaskState.Snapshot.class, name, String.class, false);
    field(BackupTaskState.Snapshot.class, "status", BackupTaskState.Status.class, false);
    method(BackupTaskState.Snapshot.class, "busy", boolean.class, false, false);
    Constructor<?> snapshot =
        BackupTaskState.Snapshot.class.getDeclaredConstructor(
            long.class, String.class, BackupTaskState.Status.class, String.class, String.class);
    // 唯一事先批准的可见性差异：Java package-private → Kotlin internal 的 JVM public。
    assertFalse(Modifier.isPrivate(snapshot.getModifiers()));
    assertFalse(Modifier.isProtected(snapshot.getModifiers()));
    assertArrayEquals(new Class<?>[0], snapshot.getExceptionTypes());
    BackupTaskState.Snapshot value = new BackupTaskState.Snapshot(3, null, null, null, null);
    assertEquals(3, value.id);
    assertNull(value.kind);
    assertFalse(value.busy());
    assertSame(
        Object.class, value.getClass().getMethod("equals", Object.class).getDeclaringClass());
  }

  @Test
  public void schedulerRetainsGenericTicketAndNullableJavaCallBoundary() throws Exception {
    Class<?> owner = ScheduledBackupOwner.class;
    Class<?> ticket = ScheduledBackupOwner.Ticket.class;
    assertTrue(Modifier.isFinal(owner.getModifiers()));
    assertTrue(Modifier.isPublic(owner.getDeclaredConstructor().getModifiers()));
    assertEquals("P", owner.getTypeParameters()[0].getName());
    assertEquals("P", ticket.getTypeParameters()[0].getName());
    field(ticket, "parameters", Object.class, false);
    assertEquals("P", ticket.getField("parameters").getGenericType().getTypeName());
    assertTrue(Modifier.isPrivate(ticket.getDeclaredConstructor(Object.class).getModifiers()));
    method(owner, "begin", ticket, false, false, Object.class);
    method(owner, "current", boolean.class, false, false, ticket);
    method(owner, "accept", boolean.class, false, false, ticket, Runnable.class);
    method(owner, "submitted", boolean.class, false, false, ticket, String.class);
    method(owner, "jobId", String.class, false, false, ticket);
    method(owner, "owns", boolean.class, false, false, ticket, String.class);
    method(owner, "finish", boolean.class, false, false, ticket);
    method(owner, "stop", ticket, false, false, Object.class);
    method(owner, "stopCurrent", ticket, false, false);
    assertEquals(
        "P",
        owner.getDeclaredMethod("begin", Object.class).getGenericParameterTypes()[0].getTypeName());
    for (String name : new String[] {"begin", "stop", "stopCurrent"}) {
      Method method =
          name.equals("stopCurrent")
              ? owner.getDeclaredMethod(name)
              : owner.getDeclaredMethod(name, Object.class);
      Type result = method.getGenericReturnType();
      assertTrue(result instanceof ParameterizedType);
      assertEquals("P", ((ParameterizedType) result).getActualTypeArguments()[0].getTypeName());
    }
    assertSame(Object.class, ticket.getMethod("equals", Object.class).getDeclaringClass());
  }
}
