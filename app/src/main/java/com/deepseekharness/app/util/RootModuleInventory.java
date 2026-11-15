package com.deepseekharness.app.util;

import com.deepseekharness.app.util.RootModulePolicy.Code;
import com.deepseekharness.app.util.RootModulePolicy.Manager;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 只解析已受控读取的 F07 元数据；不访问文件、路径、进程或 Android API。 */
public final class RootModuleInventory {
  private RootModuleInventory() {}

  public enum NodeKind {
    MISSING,
    DIRECTORY,
    REGULAR,
    SYMLINK,
    SPECIAL,
    UNREADABLE
  }

  /** 描述符适配器的事实，不能由目录名或成功文案推断 noFollowVerified/stable。 */
  public record SourceFacts(
      NodeKind directory,
      NodeKind property,
      boolean noFollowVerified,
      boolean stable,
      long propertyLinks,
      long modifiedMillis) {}

  /** marker 只取类型与存在性；链接、目录、设备和不可读状态都不是普通标记。 */
  public record MarkerFacts(NodeKind disable, NodeKind remove, NodeKind update) {
    public static MarkerFacts none() {
      return new MarkerFacts(NodeKind.MISSING, NodeKind.MISSING, NodeKind.MISSING);
    }
  }

  public record Entry(
      String id,
      String name,
      String version,
      String versionCode,
      boolean disabled,
      boolean removing,
      boolean updatePending,
      long modifiedMillis) {
    public Entry {
      if (!safeId(id)
          || !plainField(name)
          || !plainField(version)
          || !validVersionCode(versionCode)
          || modifiedMillis < -1) throw new IllegalArgumentException("ROOT_MODULE_ENTRY_INVALID");
    }
  }

  public static final class Rejected extends Exception {
    private static final long serialVersionUID = 1L;
    private final Code code;

    private Rejected(Code code) {
      super(code.name());
      this.code = code;
    }

    public Code code() {
      return code;
    }
  }

  public record Parsed(Code code, Entry module) {
    public Parsed {
      Objects.requireNonNull(code, "code");
      if ((code == Code.LIST_COMPLETE) != (module != null))
        throw new IllegalArgumentException("ROOT_MODULE_PARSE_RESULT_INVALID");
    }

    public boolean accepted() {
      return module != null;
    }
  }

  /** 无原始 prop、脚本或未知字段；complete 只来自显式完整枚举，不从空列表推导。 */
  public record Result(
      Code code,
      Code reason,
      Manager manager,
      List<Entry> modules,
      int attemptedEntries,
      long propertyBytes) {
    public Result {
      Objects.requireNonNull(code, "code");
      modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
      if (attemptedEntries < modules.size()
          || propertyBytes < 0
          || modules.size() > RootModulePolicy.MAX_ENTRIES
          || code == Code.LIST_COMPLETE && (manager == null || reason != null)
          || code == Code.LIST_PARTIAL && (manager == null || !failureReason(reason))
          || code != Code.LIST_COMPLETE
              && code != Code.LIST_PARTIAL
              && (!modules.isEmpty() || reason != null || manager != null))
        throw new IllegalArgumentException("ROOT_MODULE_RESULT_INVALID");
    }

    public boolean complete() {
      return code == Code.LIST_COMPLETE;
    }
  }

  /** 直接供固定根 reader 使用；调用方先核实际 fd/来源，不把此解析成功当作授权。 */
  public static Entry parse(
      String directoryId,
      byte[] property,
      boolean disabled,
      boolean removing,
      boolean updatePending,
      long modifiedMillis)
      throws Rejected {
    if (!safeId(directoryId)) throw new Rejected(Code.MODULE_ID_INVALID);
    if (property == null) throw new Rejected(Code.MODULE_PROP_INVALID);
    if (property.length > RootModulePolicy.MAX_PROP_BYTES)
      throw new Rejected(Code.MODULE_PROP_LIMIT);
    if (modifiedMillis < -1) throw new Rejected(Code.MODULE_SOURCE_UNSAFE);
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(property))
              .toString();
    } catch (CharacterCodingException invalid) {
      throw new Rejected(Code.MODULE_PROP_UTF8);
    }
    validateDocument(text);
    Map<String, String> selected = new HashMap<>();
    Set<String> seen = new HashSet<>();
    for (String raw : text.split("\n", -1)) {
      String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
      line = spaces(line);
      if (line.isEmpty() || line.startsWith("#")) continue;
      if (line.endsWith("\\")) throw new Rejected(Code.MODULE_PROP_INVALID);
      int separator = line.indexOf('=');
      if (separator < 1) throw new Rejected(Code.MODULE_PROP_INVALID);
      String key = spaces(line.substring(0, separator));
      if (!key.matches("[A-Za-z][A-Za-z0-9._-]{0,63}"))
        throw new Rejected(Code.MODULE_PROP_INVALID);
      if (!seen.add(key)) throw new Rejected(Code.MODULE_PROP_DUPLICATE);
      if (!Set.of("id", "name", "version", "versionCode").contains(key)) continue;
      String value = spaces(line.substring(separator + 1));
      if (value.codePointCount(0, value.length()) > RootModulePolicy.MAX_FIELD_CODEPOINTS)
        throw new Rejected(Code.MODULE_FIELD_LIMIT);
      if (!plainField(value)) throw new Rejected(Code.MODULE_PROP_INVALID);
      selected.put(key, value);
    }
    String id = selected.get("id");
    if (id == null) throw new Rejected(Code.MODULE_PROP_INVALID);
    if (!directoryId.equals(id)) throw new Rejected(Code.MODULE_ID_MISMATCH);
    String versionCode = selected.getOrDefault("versionCode", "");
    if (!validVersionCode(versionCode)) throw new Rejected(Code.MODULE_PROP_INVALID);
    return new Entry(
        id,
        selected.getOrDefault("name", ""),
        selected.getOrDefault("version", ""),
        versionCode,
        disabled,
        removing,
        updatePending,
        modifiedMillis);
  }

  public static Parsed inspect(
      String directoryId, byte[] property, SourceFacts source, MarkerFacts markers) {
    Code unsafe = sourceRejection(source, markers);
    if (unsafe != null) return new Parsed(unsafe, null);
    try {
      return new Parsed(
          Code.LIST_COMPLETE,
          parse(
              directoryId,
              property,
              markers.disable() == NodeKind.REGULAR,
              markers.remove() == NodeKind.REGULAR,
              markers.update() == NodeKind.REGULAR,
              source.modifiedMillis()));
    } catch (Rejected rejected) {
      return new Parsed(rejected.code(), null);
    }
  }

  public static Code sourceRejection(SourceFacts source, MarkerFacts markers) {
    if (source == null || markers == null) return Code.MODULE_SOURCE_UNSAFE;
    if (source.directory() == NodeKind.UNREADABLE
        || source.property() == NodeKind.UNREADABLE
        || markers.disable() == NodeKind.UNREADABLE
        || markers.remove() == NodeKind.UNREADABLE
        || markers.update() == NodeKind.UNREADABLE) return Code.MODULE_ROOT_UNREADABLE;
    if (!source.stable()) return Code.MODULE_SOURCE_CHANGED;
    if (source.directory() != NodeKind.DIRECTORY
        || source.property() != NodeKind.REGULAR
        || !source.noFollowVerified()
        || source.propertyLinks() != 1
        || source.modifiedMillis() < -1
        || !marker(markers.disable())
        || !marker(markers.remove())
        || !marker(markers.update())) return Code.MODULE_SOURCE_UNSAFE;
    return null;
  }

  /** 流式 reader 每项交付一次，预算含所有原始 prop 字节（包括被忽略的未知键）。 */
  public static final class Builder {
    private final Manager manager;
    private final List<Entry> modules = new ArrayList<>();
    private final Set<String> ids = new HashSet<>();
    private int attemptedEntries;
    private long propertyBytes;
    private Code failure;
    private boolean finished;

    public Builder(Manager manager) {
      this.manager = Objects.requireNonNull(manager, "manager");
    }

    public boolean add(String id, byte[] property, SourceFacts source, MarkerFacts markers) {
      if (!budget(property == null ? 0 : property.length)) return false;
      Parsed parsed = inspect(id, property, source, markers);
      if (!parsed.accepted()) return reject(parsed.code());
      return addParsed(parsed.module());
    }

    /** reader 已逐项核验来源并调用 parse 时使用，byteCount 必须是该 prop 的原始长度。 */
    public boolean add(Entry entry, int byteCount) {
      Objects.requireNonNull(entry, "entry");
      if (!budget(byteCount)) return false;
      return addParsed(entry);
    }

    public Code failure() {
      return failure;
    }

    public Result finish(boolean enumerationComplete, Code incompleteReason) {
      if (finished) throw new IllegalStateException("ROOT_MODULE_BUILDER_FINISHED");
      finished = true;
      if (failure == null && !enumerationComplete) {
        if (!failureReason(incompleteReason))
          throw new IllegalArgumentException("ROOT_MODULE_INCOMPLETE_REASON_REQUIRED");
        failure = incompleteReason;
      }
      if (failure == null && incompleteReason != null)
        throw new IllegalArgumentException("ROOT_MODULE_COMPLETE_HAS_FAILURE");
      return failure == null
          ? new Result(Code.LIST_COMPLETE, null, manager, modules, attemptedEntries, propertyBytes)
          : new Result(
              Code.LIST_PARTIAL, failure, manager, modules, attemptedEntries, propertyBytes);
    }

    private boolean budget(int bytes) {
      if (finished) throw new IllegalStateException("ROOT_MODULE_BUILDER_FINISHED");
      if (failure != null) return false;
      attemptedEntries++;
      if (attemptedEntries > RootModulePolicy.MAX_ENTRIES) return reject(Code.MODULE_ENTRY_LIMIT);
      if (bytes < 0) return reject(Code.MODULE_PROP_INVALID);
      propertyBytes += bytes;
      if (bytes > RootModulePolicy.MAX_PROP_BYTES) return reject(Code.MODULE_PROP_LIMIT);
      if (propertyBytes > RootModulePolicy.MAX_TOTAL_BYTES) return reject(Code.MODULE_TOTAL_LIMIT);
      return true;
    }

    private boolean addParsed(Entry entry) {
      if (!ids.add(entry.id())) return reject(Code.MODULE_DUPLICATE_ID);
      modules.add(entry);
      return true;
    }

    private boolean reject(Code code) {
      failure = code;
      return false;
    }
  }

  public static Result unavailable(Code code) {
    if (code != Code.ROOT_DISABLED
        && code != Code.SU_UNAVAILABLE
        && code != Code.ROOT_NOT_GRANTED
        && code != Code.ROOT_IDENTITY_UNVERIFIED
        && code != Code.UNSUPPORTED_MANAGER_VERSION
        && code != Code.MANAGER_AMBIGUOUS
        && code != Code.MODULE_ROOT_UNREADABLE
        && code != Code.MODULE_DEADLINE_EXCEEDED
        && code != Code.MODULE_SOURCE_CHANGED
        && code != Code.UNKNOWN)
      throw new IllegalArgumentException("ROOT_MODULE_UNAVAILABLE_CODE_INVALID");
    return new Result(code, null, null, List.of(), 0, 0);
  }

  private static boolean marker(NodeKind kind) {
    return kind == NodeKind.MISSING || kind == NodeKind.REGULAR;
  }

  private static boolean safeId(String value) {
    return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
  }

  private static boolean plainField(String value) {
    if (value == null
        || value.codePointCount(0, value.length()) > RootModulePolicy.MAX_FIELD_CODEPOINTS
        || value.indexOf('\\') >= 0
        || value.indexOf('`') >= 0
        || value.contains("$(")
        || value.contains("${")) return false;
    for (int index = 0; index < value.length(); ) {
      int point = value.codePointAt(index);
      if (Character.isISOControl(point)
          || Character.getType(point) == Character.FORMAT
          || point == 0x2028
          || point == 0x2029) return false;
      index += Character.charCount(point);
    }
    return true;
  }

  private static boolean validVersionCode(String value) {
    if (value == null) return false;
    if (value.isEmpty()) return true;
    if (!value.matches("[0-9]{1,19}")) return false;
    try {
      return Long.parseLong(value) >= 0;
    } catch (NumberFormatException outsideRange) {
      return false;
    }
  }

  private static void validateDocument(String text) throws Rejected {
    for (int index = 0; index < text.length(); ) {
      int point = text.codePointAt(index);
      if (point == '\r') {
        if (index + 1 >= text.length() || text.charAt(index + 1) != '\n')
          throw new Rejected(Code.MODULE_PROP_INVALID);
      } else if (point != '\n'
          && (Character.isISOControl(point)
              || Character.getType(point) == Character.FORMAT
              || point == 0x2028
              || point == 0x2029)) throw new Rejected(Code.MODULE_PROP_INVALID);
      index += Character.charCount(point);
    }
  }

  private static boolean failureReason(Code code) {
    return RootModulePolicy.failureReason(code);
  }

  private static String spaces(String value) {
    int start = 0, end = value.length();
    while (start < end && value.charAt(start) == ' ') start++;
    while (end > start && value.charAt(end - 1) == ' ') end--;
    return value.substring(start, end);
  }
}
