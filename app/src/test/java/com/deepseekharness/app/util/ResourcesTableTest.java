package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * 资源表字节结构回归：这张表交给系统 {@code ResourcesProvider} 后不会报错，只会静默不生效，
 * 所以必须把关键偏移钉死 —— 一旦有人“顺手整理”这些字段，真机上表现为「取色算了但没变色」。
 *
 * <p>两个曾经踩过的坑都写成断言：漏写 {@code ResTable_package.typeIdOffset}（headerSize 与真实
 * header 差 4 字节，按 headerSize 遍历的解析器全部落空）、类型名池没铺到 {@code typeId} 项
 * （真实 type id 不是 1 时取类型名越界）。
 */
public final class ResourcesTableTest {
  private static final String PACKAGE_NAME = "com.deepseekharness.app";
  private static final int PACKAGE_ID = 0x7f;

  /** 故意不用 1：真实 R.color 的 type id 不是 1，用 1 会掩盖类型池铺位问题。 */
  private static final int TYPE_ID = 0x03;

  private static final int[] IDS = {
    id(100), id(107), id(114), id(250), id(300),
  };

  private static final int[] ARGB = {
    0xFF1E50D0, 0xFF6AA4FF, 0x141E50D0, 0xE0FAFCFF, 0xFFAB1C58,
  };

  private static int id(int entry) {
    return (PACKAGE_ID << 24) | (TYPE_ID << 16) | entry;
  }

  @Test
  public void packageHeaderKeepsTheTypeIdOffsetField() {
    byte[] table = ResourcesTable.build(PACKAGE_NAME, PACKAGE_ID, TYPE_ID, IDS, ARGB);
    int packageStart = 40;
    assertEquals(0x0200, u16(table, packageStart));
    assertEquals(288, u16(table, packageStart + 2));
    assertEquals(PACKAGE_ID, u32(table, packageStart + 8));
    // ResTable_package 字段顺序（相对 package chunk 起点，整段 header 共 288 字节）：
    // chunk 头 8 字节 → id@+8 → name 256 字节（到 +268）→ typeStrings@+268 →
    // lastPublicType@+272 → keyStrings@+276 → lastPublicKey@+280 → typeIdOffset@+284。
    // typeIdOffset 是 header 的最后 4 字节；漏写它会让 headerSize 与真实长度差 4。
    assertEquals(0, u32(table, packageStart + 284));
    // 类型名池紧跟在 288 字节 header 之后，所以 typeStrings 必须恰好等于 headerSize。
    assertEquals(288, u32(table, packageStart + 268));
    // 键名池在类型名池之后，只要落在 header 之后即可。
    assertTrue(u32(table, packageStart + 276) >= 288);
    // lastPublicType / lastPublicKey 是「已发布的最后一个下标」，不是偏移，不可能 >= 288。
    assertEquals(TYPE_ID, u32(table, packageStart + 272));
    assertEquals(IDS.length - 1, u32(table, packageStart + 280));
  }

  @Test
  public void typeNamePoolCoversTheRequestedTypeId() {
    byte[] table = ResourcesTable.build(PACKAGE_NAME, PACKAGE_ID, TYPE_ID, IDS, ARGB);
    int packageStart = 40;
    int poolStart = packageStart + (int) u32(table, packageStart + 268);
    assertEquals(TYPE_ID, stringCount(table, poolStart));
    assertEquals("color", stringAt(table, poolStart, TYPE_ID - 1));
  }

  @Test
  public void everyEntryRoundTripsWithItsResourceId() {
    byte[] table = ResourcesTable.build(PACKAGE_NAME, PACKAGE_ID, TYPE_ID, IDS, ARGB);
    int typeStart = typeChunk(table);
    int entryCount = u32(table, typeStart + 12);
    int entriesStart = typeStart + u32(table, typeStart + 16);
    int offsetsStart = typeStart + u16(table, typeStart + 2);
    assertEquals(301, entryCount);
    for (int index = 0; index < IDS.length; index++) {
      int slot = IDS[index] & 0xFFFF;
      int offset = u32(table, offsetsStart + slot * 4);
      assertNotEquals(0xFFFFFFFF, offset);
      int entry = entriesStart + offset;
      assertEquals(8, u16(table, entry));
      // ResTable_entry 占 8 字节（size@+0、flags@+2、key@+4），其后紧跟 Res_value：
      // size@+8、res0@+10、dataType@+11、data@+12。所以类型字节在 entry+11，
      // entry+12 已经是颜色数据的最低字节（0xD0），拿它比 0x1C 只会永远失败。
      assertEquals(8, u16(table, entry + 8));
      assertEquals(0x1C, table[entry + 11] & 0xFF);
      assertEquals(ARGB[index], u32(table, entry + 12));
    }
  }

  /** 未覆盖的槽位必须是 0xFFFFFFFF：写成 0 会指向第一个条目，把邻近资源也顶成同一个色。 */
  @Test
  public void uncoveredSlotsAreMarkedMissing() {
    byte[] table = ResourcesTable.build(PACKAGE_NAME, PACKAGE_ID, TYPE_ID, IDS, ARGB);
    int typeStart = typeChunk(table);
    int offsetsStart = typeStart + u16(table, typeStart + 2);
    assertEquals(0xFFFFFFFF, u32(table, offsetsStart + 101 * 4));
    assertEquals(0xFFFFFFFF, u32(table, offsetsStart + 0));
  }

  @Test
  public void rejectsInputThatWouldCorruptTheTable() {
    expectRejected(new int[] {id(1)}, new int[] {0xFF000000, 0xFF000000});
    expectRejected(new int[] {0x7f040001}, new int[] {0xFF000000});
    expectRejected(new int[] {id(1), id(1)}, new int[] {0xFF000000, 0xFF000001});
    expectRejected(new int[] {}, new int[] {});
  }

  /** typeId=0 会让类型名池索引成 -1；>255 会在 u8 写入时被截断成另一张表。 */
  @Test
  public void rejectsIllegalTypeIdInsteadOfThrowingIndexOutOfBounds() {
    for (int bad : new int[] {0, -1, 256, 0x100}) {
      try {
        ResourcesTable.build(
            PACKAGE_NAME, PACKAGE_ID, bad, new int[] {id(1)}, new int[] {0xFF000000});
        fail("typeId=" + bad + " 必须以 IAE 拒绝");
      } catch (IllegalArgumentException expected) {
        // 预期
      }
    }
  }

  /** entry=0xFFFF 会把表撑到 500+ KB / 4 MiB 堆；真实 R.color 不会这样，出现即传错。 */
  @Test
  public void rejectsUnreasonablySparseEntryIds() {
    try {
      ResourcesTable.build(
          PACKAGE_NAME, PACKAGE_ID, TYPE_ID, new int[] {0x7f03FFFF}, new int[] {0xFF000000});
      fail("entry=0xFFFF 必须以 IAE 拒绝");
    } catch (IllegalArgumentException expected) {
      // 预期
    }
  }

  private static void expectRejected(int[] ids, int[] argb) {
    try {
      ResourcesTable.build(PACKAGE_NAME, PACKAGE_ID, TYPE_ID, ids, argb);
      fail("非法入参必须拒绝，否则会生成错表并被系统静默忽略");
    } catch (IllegalArgumentException expected) {
      // 预期
    }
  }

  /** 找到 type 条目块：沿 package 子 chunk 顺序扫描，跳过 typeSpec。 */
  private static int typeChunk(byte[] table) {
    int packageStart = 40;
    int position = packageStart + u16(table, packageStart + 2);
    int packageEnd = packageStart + u32(table, packageStart + 4);
    while (position + 8 <= packageEnd) {
      int type = u16(table, position);
      int size = u32(table, position + 4);
      if (type == 0x0201) return position;
      if (size <= 0) break;
      position += size;
    }
    throw new IllegalStateException("RES_TABLE_NO_TYPE_CHUNK");
  }

  private static int stringCount(byte[] table, int poolStart) {
    return u32(table, poolStart + 8);
  }

  /** 只支持 UTF-8 池：本表所有键都是 ASCII。 */
  private static String stringAt(byte[] table, int poolStart, int index) {
    int count = stringCount(table, poolStart);
    int stringsStart = poolStart + u32(table, poolStart + 20);
    int offset = u32(table, poolStart + 28 + index * 4);
    int position = stringsStart + offset;
    // 跳过 char 长度与字节长度前缀。
    position += (table[position] & 0x80) != 0 ? 2 : 1;
    int byteLength = table[position] & 0xFF;
    position += 1;
    if ((byteLength & 0x80) != 0) {
      byteLength = ((byteLength & 0x7F) << 8) | (table[position] & 0xFF);
      position += 1;
    }
    StringBuilder out = new StringBuilder(byteLength);
    for (int at = 0; at < byteLength; at++) out.append((char) (table[position + at] & 0xFF));
    return out.toString();
  }

  private static int u16(byte[] data, int at) {
    return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
  }

  private static int u32(byte[] data, int at) {
    return (data[at] & 0xFF)
        | ((data[at + 1] & 0xFF) << 8)
        | ((data[at + 2] & 0xFF) << 16)
        | ((data[at + 3] & 0xFF) << 24);
  }
}
