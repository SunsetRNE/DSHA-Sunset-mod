package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** 定时任务记录的 JSON 往返：落盘什么，重启后读回什么。 */
public final class ModuleScheduleEntryTest {

  @Test
  public void jsonRoundTripKeepsAllFields() throws Exception {
    ModuleScheduler.Entry entry =
        new ModuleScheduler.Entry("pet.a#abcd1234", "pet.a", "晨检", 7, 30, "exec", "uname -m");
    JSONObject json = new JSONObject(entry.toJson().toString());
    ModuleScheduler.Entry back = ModuleScheduler.Entry.fromJson(entry.id, json);
    assertEquals(entry.id, back.id);
    assertEquals(entry.cardId, back.cardId);
    assertEquals(entry.label, back.label);
    assertEquals(entry.hour, back.hour);
    assertEquals(entry.minute, back.minute);
    assertEquals(entry.kind, back.kind);
    assertEquals(entry.value, back.value);
  }

  @Test
  public void fromJsonToleratesMissingKeys() {
    ModuleScheduler.Entry entry = ModuleScheduler.Entry.fromJson("x", new JSONObject());
    assertEquals("x", entry.id);
    assertEquals("", entry.cardId);
    assertEquals("", entry.label);
    assertEquals(-1, entry.hour);
    assertEquals(-1, entry.minute);
    assertEquals("", entry.kind);
    assertEquals("", entry.value);
    // hour<0 的记录由 arm() 拒绝登记闹钟；这里确认坏 JSON 缺键不会让读取方挂掉。
    assertTrue(entry.hour < 0 || entry.minute < 0);
  }
}
