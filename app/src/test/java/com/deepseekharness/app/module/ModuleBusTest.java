package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 模块间通信：宿主中转、目标存在性、收件箱上限与清理。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleBusTest {

  private Context context;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    ModuleCardStore.importCard(
        context, "{\"schema\":1,\"id\":\"a.one\",\"name\":\"甲\",\"actions\":[]}");
    ModuleCardStore.importCard(
        context, "{\"schema\":1,\"id\":\"b.two\",\"name\":\"乙\",\"actions\":[]}");
  }

  @Test
  public void sendDeliversToTargetInboxOnly() {
    assertEquals("ok", ModuleBus.send(context, "a.one", "b.two", "hello", "world"));
    String two = ModuleBus.poll(context, "b.two");
    assertTrue(two, two.contains("\"event\":\"hello\""));
    assertTrue(two, two.contains("\"payload\":\"world\""));
    assertTrue(two, two.contains("\"from\":\"a.one\""));
    assertEquals("[]", ModuleBus.poll(context, "a.one"));
  }

  @Test
  public void sendRejectsUnknownTargetAndBadEvent() {
    assertTrue(ModuleBus.send(context, "a.one", "no.such", "hello", "x").startsWith("[没有这个模块]"));
    ModuleBus.send(context, "a.one", "b.two", "Bad Event", "x");
    assertEquals("[]", ModuleBus.poll(context, "b.two"));
  }

  @Test
  public void emitDeliversToEveryEnabledModule() {
    int count = ModuleBus.emit(context, "a.one", "broadcast", "hi");
    assertEquals(2, count);
    assertTrue(ModuleBus.poll(context, "a.one").contains("\"event\":\"broadcast\""));
    assertTrue(ModuleBus.poll(context, "b.two").contains("\"event\":\"broadcast\""));
  }

  @Test
  public void inboxIsBoundedAndDropsOldest() throws Exception {
    for (int i = 0; i < 40; i++)
      ModuleBus.send(context, "a.one", "b.two", "tick", String.valueOf(i));
    JSONArray inbox = new JSONArray(ModuleBus.poll(context, "b.two"));
    assertEquals(32, inbox.length());
    assertTrue(inbox.optJSONObject(0).optString("payload").equals("8"));
    assertTrue(inbox.optJSONObject(inbox.length() - 1).optString("payload").equals("39"));
  }

  @Test
  public void clearRemovesInbox() {
    ModuleBus.send(context, "a.one", "b.two", "hello", "world");
    ModuleBus.clear(context, "b.two");
    assertEquals("[]", ModuleBus.poll(context, "b.two"));
  }
}
