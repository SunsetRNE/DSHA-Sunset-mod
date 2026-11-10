package com.deepseekharness.app.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 运行时构造一张只含 color 资源的 {@code resources.arsc} 二进制表。
 *
 * <p>用途：把「莫奈取色」算出的颜色在运行时覆盖到主包同名资源上。Android 没有公开 API 能在运行时
 * 修改 {@code Resources.Theme} 里的自定义属性值，而 DSHA 的界面颜色全部经 {@code ?attr/dsha_pal_*}
 * 间接引用 {@code @color/pal_monet_*}，所以只能走 RRO 那条路：构造一张同 package id、只含目标资源
 * 的小表，交给 {@code ResourcesProvider} + {@code ResourcesLoader}（API 30+，与 minSdk 一致）。
 * Google 的 Material {@code DynamicColors} 内部用的是同一机制。
 *
 * <p>关键约束（任何一处写错，系统侧会静默忽略整张表）：
 *
 * <ul>
 *   <li>资源 id 必须与主包一致：高字节是 package id、次字节是 type id、低两字节是 entry 下标；
 *   <li>条目不按顺序紧凑排列，而是按 entry 下标铺开到 {@code maxEntry + 1}，
 *       未覆盖的槽位写 {@link #NO_ENTRY}，否则会把邻近资源（如别的颜色）顶掉；
 *   <li>字符串池的 offset 相对池内 stringsStart，chunk 的 size 不含后续 chunk 的 padding。
 * </ul>
 *
 * <p>纯逻辑：不 import 任何 Android API，输入是完整资源 id 与颜色，可脱离设备用单测验证字节结构。
 */
public final class ResourcesTable {
  private static final int TYPE_STRING_POOL = 0x0001;
  private static final int TYPE_TABLE = 0x0002;
  private static final int TYPE_PACKAGE = 0x0200;
  private static final int TYPE_TABLE_TYPE = 0x0201;
  private static final int TYPE_TABLE_TYPE_SPEC = 0x0202;

  /** ResTable_type 的 offset 数组用 0xFFFFFFFF 表示「本表没有这个 entry」。 */
  private static final int NO_ENTRY = 0xFFFFFFFF;

  /** UTF-8 字符串池标记；资源键都是 ASCII，UTF-8 池最短。 */
  private static final int FLAG_UTF8 = 0x100;

  private static final int TYPE_INT_COLOR_ARGB8 = 0x1C;
  private static final int RES_VALUE_BYTES = 8;
  private static final int RES_ENTRY_BYTES = 8;
  private static final int CONFIG_BYTES = 64;

  private ResourcesTable() {}

  /**
   * 生成覆盖表。
   *
   * @param packageName 目标包名，必须与主包一致
   * @param packageId 主包 package id，取自任一 {@code R.color.*} 的高字节（通常是 0x7f）
   * @param typeId 主包 color 类型的 type id，取自 {@code R.color.*} 的次字节
   * @param resourceIds 要覆盖的资源 id，取自 {@code R.color.pal_monet_*} 常量
   * @param argb 与 {@code resourceIds} 一一对应的颜色（0xAARRGGBB）
   */
  /** entry 槽位数上限：真实调用只覆盖 33 个 color，给两个数量级的余量；超出说明调用方传错了 id。 */
  private static final int MAX_ENTRY_COUNT = 4096;

  public static byte[] build(
      String packageName, int packageId, int typeId, int[] resourceIds, int[] argb) {
    if (resourceIds.length != argb.length) throw new IllegalArgumentException("RES_TABLE_ARITY");
    if (resourceIds.length == 0) throw new IllegalArgumentException("RES_TABLE_EMPTY");
    // typeId=0 会让类型名池索引成 -1（IndexOutOfBoundsException 而不是可读的入参错误）；
    // >0xFF 则 u8 写入时被截断，生成一张与请求不符的表。
    if (packageName == null) throw new IllegalArgumentException("RES_TABLE_PACKAGE_NAME");
    if (typeId < 1 || typeId > 0xFF) {
      throw new IllegalArgumentException("RES_TABLE_TYPE_ID: " + typeId);
    }
    int maxEntry = -1;
    for (int id : resourceIds) {
      if ((id >>> 24) != packageId || ((id >>> 16) & 0xFF) != typeId) {
        throw new IllegalArgumentException("RES_TABLE_FOREIGN_ID: " + Integer.toHexString(id));
      }
      maxEntry = Math.max(maxEntry, id & 0xFFFF);
    }
    int entryCount = maxEntry + 1;
    // entry=0xFFFF 会让 entryCount=65536：表 500+ KB、4 MiB 堆。结构虽然自洽，但真实调用
    // （R.color 常量）不可能出现这种 id，出现即说明调用方把非 color 资源传了进来。
    if (entryCount > MAX_ENTRY_COUNT) {
      throw new IllegalArgumentException("RES_TABLE_SPARSE: " + entryCount);
    }
    int[] slotToSource = new int[entryCount];
    java.util.Arrays.fill(slotToSource, -1);
    for (int index = 0; index < resourceIds.length; index++) {
      int slot = resourceIds[index] & 0xFFFF;
      if (slotToSource[slot] != -1) throw new IllegalArgumentException("RES_TABLE_DUPLICATE_ID");
      slotToSource[slot] = index;
    }

    Buffer buffer = new Buffer();
    int tableStart = buffer.position();
    writeChunkHeader(buffer, TYPE_TABLE, 12);
    buffer.u32(1);
    writeEmptyStringPool(buffer);

    int packageStart = buffer.position();
    writeChunkHeader(buffer, TYPE_PACKAGE, 288);
    buffer.u32(packageId);
    writePackageName(buffer, packageName);
    int typeStringsAt = buffer.position();
    buffer.u32(0);
    buffer.u32(typeId);
    int keyStringsAt = buffer.position();
    buffer.u32(0);
    buffer.u32(resourceIds.length - 1);
    // ResTable_package 的最后一个字段 typeIdOffset（headerSize 288 = 284 + 4）。
    // 漏写这 4 字节会让 headerSize 与真实 header 长度差 4，按 headerSize 遍历子 chunk 的
    // 解析器（含 aapt2 / ResourcesProvider）会落在错误偏移，整段 typeSpec/type 全被跳过。
    buffer.u32(0);

    // 类型名池必须铺到 typeId 项：AOSP 按 typeStrings[typeId - 1 - typeIdOffset] 取类型名，
    // 池里只有 1 项时 typeId > 1 会索引越界（getResourceTypeName 拿到 null）。前面的槽位留空名。
    List<String> typeNames = new ArrayList<>(typeId);
    for (int index = 0; index < typeId; index++) typeNames.add("");
    typeNames.set(typeId - 1, "color");
    buffer.patchU32(typeStringsAt, buffer.position() - packageStart);
    writeStringPool(buffer, typeNames);
    buffer.patchU32(keyStringsAt, buffer.position() - packageStart);

    List<String> keys = new ArrayList<>(resourceIds.length);
    for (int index = 0; index < resourceIds.length; index++) keys.add("c" + index);
    writeStringPool(buffer, keys);

    int specStart = buffer.position();
    writeChunkHeader(buffer, TYPE_TABLE_TYPE_SPEC, 16);
    buffer.u8(typeId);
    buffer.u8(0);
    buffer.u16(0);
    buffer.u32(entryCount);
    for (int index = 0; index < entryCount; index++) buffer.u32(0);
    buffer.patchChunkSize(specStart);

    int typeStart = buffer.position();
    writeChunkHeader(buffer, TYPE_TABLE_TYPE, 20 + CONFIG_BYTES);
    buffer.u8(typeId);
    buffer.u8(0);
    buffer.u16(0);
    buffer.u32(entryCount);
    int entriesStartAt = buffer.position();
    buffer.u32(0);
    buffer.u32(CONFIG_BYTES);
    for (int index = 4; index < CONFIG_BYTES; index += 4) buffer.u32(0);
    int offsetsStart = buffer.position();
    for (int index = 0; index < entryCount; index++) buffer.u32(0);
    int entriesStart = buffer.position();
    buffer.patchU32(entriesStartAt, entriesStart - typeStart);
    int[] offsets = new int[entryCount];
    java.util.Arrays.fill(offsets, NO_ENTRY);
    for (int slot = 0; slot < entryCount; slot++) {
      int source = slotToSource[slot];
      if (source < 0) continue;
      offsets[slot] = buffer.position() - entriesStart;
      buffer.u16(RES_ENTRY_BYTES);
      buffer.u16(0);
      buffer.u32(source);
      buffer.u16(RES_VALUE_BYTES);
      buffer.u8(0);
      buffer.u8(TYPE_INT_COLOR_ARGB8);
      buffer.u32(argb[source]);
    }
    for (int slot = 0; slot < entryCount; slot++) {
      buffer.patchU32(offsetsStart + slot * 4, offsets[slot]);
    }
    buffer.patchChunkSize(typeStart);
    buffer.patchChunkSize(packageStart);
    buffer.patchChunkSize(tableStart);
    return buffer.toByteArray();
  }

  private static void writeChunkHeader(Buffer buffer, int type, int headerSize) {
    buffer.u16(type);
    buffer.u16(headerSize);
    buffer.u32(0);
  }

  /** 空字符串池：只要 28 字节 header（8 字节 chunk 头 + 5 个 u32 字段）。 */
  private static void writeEmptyStringPool(Buffer buffer) {
    int start = buffer.position();
    writeChunkHeader(buffer, TYPE_STRING_POOL, 28);
    buffer.u32(0);
    buffer.u32(0);
    buffer.u32(0);
    buffer.u32(0);
    buffer.u32(0);
    buffer.patchChunkSize(start);
  }

  /** 包名固定 128 个 UTF-16 码元，不足补 0。 */
  private static void writePackageName(Buffer buffer, String packageName) {
    byte[] utf16 = packageName.getBytes(StandardCharsets.UTF_16LE);
    int usable = Math.min(utf16.length, 256);
    for (int index = 0; index < 256; index += 2) {
      int low = index < usable ? utf16[index] & 0xFF : 0;
      int high = index + 1 < usable ? utf16[index + 1] & 0xFF : 0;
      buffer.u16(low | (high << 8));
    }
  }

  private static void writeStringPool(Buffer buffer, List<String> strings) {
    int start = buffer.position();
    writeChunkHeader(buffer, TYPE_STRING_POOL, 28);
    buffer.u32(strings.size());
    buffer.u32(0);
    buffer.u32(FLAG_UTF8);
    int stringsStartAt = buffer.position();
    buffer.u32(0);
    buffer.u32(0);
    int offsetsStart = buffer.position();
    for (int index = 0; index < strings.size(); index++) buffer.u32(0);
    int stringsStart = buffer.position();
    buffer.patchU32(stringsStartAt, stringsStart - start);
    int[] offsets = new int[strings.size()];
    for (int index = 0; index < strings.size(); index++) {
      offsets[index] = buffer.position() - stringsStart;
      String value = strings.get(index);
      byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
      writeUtf8Length(buffer, value.length());
      writeUtf8Length(buffer, utf8.length);
      for (byte item : utf8) buffer.u8(item & 0xFF);
      buffer.u8(0);
    }
    buffer.pad4();
    buffer.patchChunkSize(start);
    for (int index = 0; index < strings.size(); index++) {
      buffer.patchU32(offsetsStart + index * 4, offsets[index]);
    }
  }

  /** ResStringPool 的 UTF-8 长度前缀：1 字节，或 2 字节（首字节高位为 1）。 */
  private static void writeUtf8Length(Buffer buffer, int length) {
    if (length > 0x7FFF) throw new IllegalArgumentException("RES_TABLE_STRING_TOO_LONG");
    if (length < 0x80) {
      buffer.u8(length);
    } else {
      buffer.u8(0x80 | (length >> 8));
      buffer.u8(length & 0xFF);
    }
  }

  /** 可回填的字节缓冲：arsc 的 size 字段在前、内容在后，必须写完再补。 */
  private static final class Buffer {
    private byte[] data = new byte[8192];
    private int size;

    int position() {
      return size;
    }

    void u8(int value) {
      ensure(1);
      data[size++] = (byte) value;
    }

    void u16(int value) {
      ensure(2);
      data[size++] = (byte) value;
      data[size++] = (byte) (value >>> 8);
    }

    void u32(int value) {
      ensure(4);
      data[size++] = (byte) value;
      data[size++] = (byte) (value >>> 8);
      data[size++] = (byte) (value >>> 16);
      data[size++] = (byte) (value >>> 24);
    }

    void pad4() {
      while (size % 4 != 0) u8(0);
    }

    void patchU32(int at, int value) {
      data[at] = (byte) value;
      data[at + 1] = (byte) (value >>> 8);
      data[at + 2] = (byte) (value >>> 16);
      data[at + 3] = (byte) (value >>> 24);
    }

    /** chunk 的 size 字段固定在 chunk 起点 +4。 */
    void patchChunkSize(int chunkStart) {
      patchU32(chunkStart + 4, size - chunkStart);
    }

    byte[] toByteArray() {
      byte[] out = new byte[size];
      System.arraycopy(data, 0, out, 0, size);
      return out;
    }

    private void ensure(int extra) {
      if (size + extra <= data.length) return;
      byte[] grown = new byte[Math.max(data.length * 2, size + extra)];
      System.arraycopy(data, 0, grown, 0, size);
      data = grown;
    }
  }
}
