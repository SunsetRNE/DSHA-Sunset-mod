package com.deepseekharness.app.module;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 模块清单的纯数据契约。解析时严格拒绝未知字段、越权动作和超出上限的内容。
 * 该类不负责加载模块，也不执行清单中的任何动作。
 */
public final class ModuleManifest {
  public enum Type {
    FRONTEND("frontend"),
    RUNTIME("runtime"),
    ENGINE("engine");

    private final String wireValue;

    Type(String wireValue) {
      this.wireValue = wireValue;
    }

    static Type parse(String value) {
      for (Type type : values()) {
        if (type.wireValue.equals(value)) return type;
      }
      throw invalid("type 只允许 frontend、runtime 或 engine");
    }

    public String wireValue() {
      return wireValue;
    }
  }

  public enum Renderer {
    LAUNCH("launch"),
    PLUGINS("plugins"),
    SETTINGS("settings"),
    PAGE_LIST("page-list");

    private final String wireValue;

    Renderer(String wireValue) {
      this.wireValue = wireValue;
    }

    static Renderer parse(String value) {
      for (Renderer renderer : values()) {
        if (renderer.wireValue.equals(value)) return renderer;
      }
      throw invalid("renderer 只允许 launch、plugins、settings 或 page-list");
    }

    public String wireValue() {
      return wireValue;
    }
  }

  public enum ActionType {
    OPEN("open"),
    MANAGE("manage");

    private final String wireValue;

    ActionType(String wireValue) {
      this.wireValue = wireValue;
    }

    static ActionType parse(String value) {
      for (ActionType action : values()) {
        if (action.wireValue.equals(value)) return action;
      }
      throw invalid("action.type 只允许 open 或 manage");
    }

    public String wireValue() {
      return wireValue;
    }
  }

  public static final class Action {
    private final ActionType type;
    private final String target;

    private Action(ActionType type, String target) {
      this.type = type;
      this.target = target;
    }

    public ActionType type() {
      return type;
    }

    public String target() {
      return target;
    }
  }

  public static final class Page {
    private final String id;
    private final String title;
    private final Renderer renderer;
    private final Action action;

    private Page(String id, String title, Renderer renderer, Action action) {
      this.id = id;
      this.title = title;
      this.renderer = renderer;
      this.action = action;
    }

    public String id() {
      return id;
    }

    public String title() {
      return title;
    }

    public Renderer renderer() {
      return renderer;
    }

    public Action action() {
      return action;
    }
  }

  public static final class Navigation {
    private final String id;
    private final String title;
    private final String icon;
    private final Action action;

    private Navigation(String id, String title, String icon, Action action) {
      this.id = id;
      this.title = title;
      this.icon = icon;
      this.action = action;
    }

    public String id() {
      return id;
    }

    public String title() {
      return title;
    }

    public String icon() {
      return icon;
    }

    public Action action() {
      return action;
    }
  }

  public static final class Row {
    private final String id;
    private final String title;
    private final String subtitle;
    private final String icon;
    private final Action action;

    private Row(String id, String title, String subtitle, String icon, Action action) {
      this.id = id;
      this.title = title;
      this.subtitle = subtitle;
      this.icon = icon;
      this.action = action;
    }

    public String id() {
      return id;
    }

    public String title() {
      return title;
    }

    public String subtitle() {
      return subtitle;
    }

    public String icon() {
      return icon;
    }

    public Action action() {
      return action;
    }
  }

  public static final class Section {
    private final String id;
    private final String title;
    private final String page;
    private final List<Row> rows;

    private Section(String id, String title, String page, List<Row> rows) {
      this.id = id;
      this.title = title;
      this.page = page;
      this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
    }

    public String id() {
      return id;
    }

    public String title() {
      return title;
    }

    public String page() {
      return page;
    }

    public List<Row> rows() {
      return rows;
    }
  }

  private static final Pattern ID = Pattern.compile("[a-z][a-z0-9._-]{0,63}");
  private static final Pattern SEMVER =
      Pattern.compile(
          "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
              + "(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?");
  private static final Pattern PAGE_TARGET =
      Pattern.compile("page:[a-z][a-z0-9._-]{0,63}/[a-z][a-z0-9._-]{0,63}");
  private static final Set<String> TOKEN_KEYS =
      Set.of(
          "bg",
          "surface",
          "raised",
          "line",
          "text",
          "muted",
          "subtle",
          "accent",
          "onAccent",
          "good",
          "well",
          "radius",
          "gap",
          "font",
          "bgImage");
  private static final Set<String> SAFE_SYSTEM_TARGETS =
      Set.of("system:terminal", "system:backup", "system:diagnostics");
  private static final Set<String> CAPABILITY_KEYS =
      Set.of(
          "network.loopback", "network.lan", "runtime.proot", "runtime.ssh", "storage.workspace");
  private static final Set<String> ARCHITECTURES = Set.of("any", "aarch64", "x86_64");

  private final int schema;
  private final String id;
  private final String name;
  private final String version;

  /** 作者自述；与卡片一样不是身份认证。 */
  private final String author;

  /** builtin / user；由宿主按来源记录，不接受清单自称。 */
  private final String source;

  /** 原始 module.json 的 SHA-256；一致性识别用，不是签名。 */
  private final String digest;

  private final Type type;
  private final boolean builtin;
  private final int minApi;
  private final String architecture;
  private final List<String> capabilities;
  private final List<Page> pages;
  private final List<Navigation> navigation;
  private final List<Section> sections;
  private final Map<String, String> tokens;

  private ModuleManifest(
      int schema,
      String id,
      String name,
      String version,
      String author,
      String source,
      String digest,
      Type type,
      boolean builtin,
      int minApi,
      String architecture,
      List<String> capabilities,
      List<Page> pages,
      List<Navigation> navigation,
      List<Section> sections,
      Map<String, String> tokens) {
    this.schema = schema;
    this.id = id;
    this.name = name;
    this.version = version;
    this.author = author;
    this.source = source;
    this.digest = digest;
    this.type = type;
    this.builtin = builtin;
    this.minApi = minApi;
    this.architecture = architecture;
    this.capabilities = Collections.unmodifiableList(new ArrayList<>(capabilities));
    this.pages = Collections.unmodifiableList(new ArrayList<>(pages));
    this.navigation = Collections.unmodifiableList(new ArrayList<>(navigation));
    this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
    this.tokens = Collections.unmodifiableMap(new LinkedHashMap<>(tokens));
  }

  /** 按模块来源解析；builtin 不以清单内自称为准。 */
  public static ModuleManifest parse(String source, boolean builtinSource) {
    if (source == null || source.trim().isEmpty()) throw invalid("清单不能为空");
    try {
      JSONObject root = new JSONObject(source);
      only(
          root,
          "schema",
          "id",
          "name",
          "version",
          "author",
          "type",
          "builtin",
          "requires",
          "contributes");

      int schema = requiredInt(root, "schema");
      if (schema != 1) throw invalid("schema 只支持 1");
      String id = requiredString(root, "id");
      checkId(id, "id");
      String name = requiredString(root, "name");
      checkText(name, "name", 128);
      String version = requiredString(root, "version");
      if (!SEMVER.matcher(version).matches()) throw invalid("version 必须是三段式 semver");
      String author = root.optString("author", "local").trim();
      if (author.isEmpty() || author.length() > 48 || !author.matches("[A-Za-z0-9._@-]+"))
        throw invalid("author 只能是 1-48 个安全字符");
      Type type = Type.parse(requiredString(root, "type"));

      boolean declaredBuiltin = root.optBoolean("builtin", false);
      if (root.has("builtin") && declaredBuiltin != builtinSource) {
        throw invalid("builtin 必须由模块来源决定，不能由清单自称");
      }

      JSONObject requires = requiredObject(root, "requires");
      only(requires, "minApi", "architecture", "capabilities");
      int minApi = requiredInt(requires, "minApi");
      if (minApi < 1 || minApi > 100) throw invalid("requires.minApi 超出范围");
      String architecture = optionalArchitecture(requires);
      List<String> capabilities = parseCapabilities(requires.optJSONArray("capabilities"));

      JSONObject contributes = requiredObject(root, "contributes");
      only(contributes, "pages", "nav", "sections", "tokens");
      List<Page> pages = parsePages(contributes.optJSONArray("pages"), id);
      List<Navigation> navigation = parseNavigation(contributes.optJSONArray("nav"), id);
      List<Section> sections = parseSections(contributes.optJSONArray("sections"), id);
      Map<String, String> tokens = parseTokens(contributes.optJSONObject("tokens"));
      if (pages.isEmpty() && navigation.isEmpty() && sections.isEmpty() && tokens.isEmpty()) {
        throw invalid("contributes 不能为空");
      }

      return new ModuleManifest(
          schema,
          id,
          name,
          version,
          author,
          builtinSource ? "builtin" : "user",
          digestOf(source),
          type,
          builtinSource,
          minApi,
          architecture,
          capabilities,
          pages,
          navigation,
          sections,
          tokens);
    } catch (JSONException error) {
      throw invalid("JSON 格式错误：" + error.getMessage());
    }
  }

  public static ModuleManifest parse(String source) {
    return parse(source, false);
  }

  public int schema() {
    return schema;
  }

  public String id() {
    return id;
  }

  public String name() {
    return name;
  }

  public String version() {
    return version;
  }

  /** 作者自述；不是签名身份。 */
  public String author() {
    return author;
  }

  /** builtin / user；宿主记录的事实。 */
  public String source() {
    return source;
  }

  /** 原始清单内容的 SHA-256；不是作者签名。 */
  public String digest() {
    return digest;
  }

  public String digestShort() {
    return digest.length() <= 12 ? digest : digest.substring(0, 12) + "…";
  }

  public String identity() {
    return author + "/" + id + "@" + version;
  }

  public Type type() {
    return type;
  }

  public boolean builtin() {
    return builtin;
  }

  public int minApi() {
    return minApi;
  }

  /** CPU ABI required by a runtime/provider module; null means architecture-independent. */
  public String architecture() {
    return architecture;
  }

  /** Capability names are declarations only; the registry never grants them implicitly. */
  public List<String> capabilities() {
    return capabilities;
  }

  public List<Page> pages() {
    return pages;
  }

  public List<Navigation> navigation() {
    return navigation;
  }

  public List<Section> sections() {
    return sections;
  }

  public Map<String, String> tokens() {
    return tokens;
  }

  private static String digestOf(String value) {
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte item : digest)
        hex.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static String optionalArchitecture(JSONObject requires) throws JSONException {
    if (!requires.has("architecture") || requires.isNull("architecture")) return null;
    String value = requiredString(requires, "architecture");
    if (!ARCHITECTURES.contains(value))
      throw invalid("requires.architecture 只允许 any、aarch64 或 x86_64");
    return "any".equals(value) ? null : value;
  }

  private static List<String> parseCapabilities(JSONArray array) throws JSONException {
    if (array == null) return Collections.emptyList();
    if (array.length() > 8) throw invalid("requires.capabilities 数量不能超过 8");
    List<String> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < array.length(); i++) {
      Object value = array.get(i);
      if (!(value instanceof String) || !CAPABILITY_KEYS.contains(value))
        throw invalid("requires.capabilities 含未允许的能力");
      if (!seen.add((String) value)) throw invalid("requires.capabilities 不得重复");
      result.add((String) value);
    }
    return result;
  }

  private static List<Page> parsePages(JSONArray array, String moduleId) throws JSONException {
    List<Page> result = new ArrayList<>();
    if (array == null) return result;
    if (array.length() > 8) throw invalid("pages 数量不能超过 8");
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject value = objectAt(array, i, "pages[" + i + "]");
      only(value, "id", "title", "renderer", "action");
      String id = requiredString(value, "id");
      checkId(id, "pages[" + i + "].id");
      if (!ids.add(id)) throw invalid("pages 中 id 重复：" + id);
      String title = optionalText(value, "title", "pages[" + i + "].title", 128);
      Renderer renderer = Renderer.parse(requiredString(value, "renderer"));
      Action action =
          value.has("action") ? parseAction(value.getJSONObject("action"), moduleId) : null;
      result.add(new Page(id, title, renderer, action));
    }
    return result;
  }

  private static List<Navigation> parseNavigation(JSONArray array, String moduleId)
      throws JSONException {
    List<Navigation> result = new ArrayList<>();
    if (array == null) return result;
    if (array.length() > 6) throw invalid("nav 数量不能超过 6");
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject value = objectAt(array, i, "nav[" + i + "]");
      only(value, "id", "title", "icon", "action");
      String id = requiredString(value, "id");
      checkId(id, "nav[" + i + "].id");
      if (!ids.add(id)) throw invalid("nav 中 id 重复：" + id);
      String title = requiredString(value, "title");
      checkText(title, "nav[" + i + "].title", 128);
      String icon = optionalPath(value, "icon", "nav[" + i + "].icon");
      Action action = parseAction(requiredObject(value, "action"), moduleId);
      result.add(new Navigation(id, title, icon, action));
    }
    return result;
  }

  private static List<Section> parseSections(JSONArray array, String moduleId)
      throws JSONException {
    List<Section> result = new ArrayList<>();
    if (array == null) return result;
    if (array.length() > 12) throw invalid("sections 数量不能超过 12");
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject value = objectAt(array, i, "sections[" + i + "]");
      only(value, "id", "title", "page", "rows");
      String id = requiredString(value, "id");
      checkId(id, "sections[" + i + "].id");
      if (!ids.add(id)) throw invalid("sections 中 id 重复：" + id);
      String title = optionalText(value, "title", "sections[" + i + "].title", 128);
      String page = optionalPageReference(value, "page", "sections[" + i + "].page");
      JSONArray rowsArray = requiredArray(value, "rows");
      if (rowsArray.length() > 24) throw invalid("每个 section 的 rows 数量不能超过 24");
      List<Row> rows = new ArrayList<>();
      Set<String> rowIds = new HashSet<>();
      for (int rowIndex = 0; rowIndex < rowsArray.length(); rowIndex++) {
        JSONObject row =
            objectAt(rowsArray, rowIndex, "sections[" + i + "].rows[" + rowIndex + "]");
        only(row, "id", "title", "subtitle", "icon", "action");
        String rowId = requiredString(row, "id");
        checkId(rowId, "rows[" + rowIndex + "].id");
        if (!rowIds.add(rowId)) throw invalid("section 内 row id 重复：" + rowId);
        String rowTitle = requiredString(row, "title");
        checkText(rowTitle, "rows[" + rowIndex + "].title", 128);
        String subtitle = optionalText(row, "subtitle", "rows[" + rowIndex + "].subtitle", 256);
        String icon = optionalPath(row, "icon", "rows[" + rowIndex + "].icon");
        Action action =
            row.has("action") ? parseAction(row.getJSONObject("action"), moduleId) : null;
        rows.add(new Row(rowId, rowTitle, subtitle, icon, action));
      }
      if (rows.isEmpty()) throw invalid("section 必须至少包含一行");
      result.add(new Section(id, title, page, rows));
    }
    return result;
  }

  private static Map<String, String> parseTokens(JSONObject object) throws JSONException {
    if (object == null) return Collections.emptyMap();
    Map<String, String> result = new LinkedHashMap<>();
    Iterator<String> keys = object.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (!TOKEN_KEYS.contains(key)) throw invalid("tokens 含未允许的 key：" + key);
      Object value = object.get(key);
      if (!(value instanceof String)) throw invalid("tokens." + key + " 必须是字符串");
      String text = (String) value;
      checkText(text, "tokens." + key, 512);
      validateTokenValue(key, text);
      result.put(key, text);
    }
    return result;
  }

  private static Action parseAction(JSONObject object, String moduleId) throws JSONException {
    only(object, "type", "target");
    ActionType type = ActionType.parse(requiredString(object, "type"));
    String target = object.has("target") ? requiredString(object, "target") : "";
    if (type == ActionType.OPEN) {
      if (!PAGE_TARGET.matcher(target).matches() && !SAFE_SYSTEM_TARGETS.contains(target)) {
        throw invalid("open action 的 target 不在允许范围内：" + target);
      }
      if (target.startsWith("page:") && !target.startsWith("page:" + moduleId + "/")) {
        throw invalid("open action 不能跳转到其他模块的页面：" + target);
      }
    } else if (!target.isEmpty()) {
      throw invalid("manage action 不允许带 target");
    }
    return new Action(type, target.isEmpty() ? null : target);
  }

  private static JSONObject objectAt(JSONArray array, int index, String path) throws JSONException {
    Object value = array.get(index);
    if (!(value instanceof JSONObject)) throw invalid(path + " 必须是对象");
    return (JSONObject) value;
  }

  private static JSONObject requiredObject(JSONObject object, String key) throws JSONException {
    Object value = object.opt(key);
    if (!(value instanceof JSONObject)) throw invalid(key + " 必须是对象");
    return (JSONObject) value;
  }

  private static JSONArray requiredArray(JSONObject object, String key) throws JSONException {
    Object value = object.opt(key);
    if (!(value instanceof JSONArray)) throw invalid(key + " 必须是数组");
    return (JSONArray) value;
  }

  private static int requiredInt(JSONObject object, String key) throws JSONException {
    Object value = object.opt(key);
    if (!(value instanceof Number) || ((Number) value).doubleValue() % 1 != 0) {
      throw invalid(key + " 必须是整数");
    }
    return ((Number) value).intValue();
  }

  private static String requiredString(JSONObject object, String key) throws JSONException {
    Object value = object.opt(key);
    if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
      throw invalid(key + " 必须是非空字符串");
    }
    return (String) value;
  }

  private static String optionalText(JSONObject object, String key, String path, int max)
      throws JSONException {
    if (!object.has(key) || object.isNull(key)) return null;
    Object value = object.get(key);
    if (!(value instanceof String)) throw invalid(path + " 必须是字符串");
    String text = (String) value;
    checkText(text, path, max);
    return text;
  }

  private static String optionalPath(JSONObject object, String key, String path)
      throws JSONException {
    String value = optionalText(object, key, path, 256);
    if (value == null) return null;
    if (value.indexOf('"') >= 0
        || value.indexOf('\'') >= 0
        || value.contains("..")
        || value.startsWith("/")
        || value.contains("\\")) {
      throw invalid(path + " 含有不安全路径");
    }
    return value;
  }

  private static String optionalPageReference(JSONObject object, String key, String path)
      throws JSONException {
    String value = optionalText(object, key, path, 128);
    if (value == null) return null;
    if (!PAGE_TARGET.matcher("page:" + value).matches() && !ID.matcher(value).matches()) {
      throw invalid(path + " 不是合法页面引用");
    }
    return value;
  }

  private static void checkId(String value, String path) {
    if (!ID.matcher(value).matches()) throw invalid(path + " 只能使用小写字母、数字、点、下划线和短横线");
  }

  private static void checkText(String value, String path, int max) {
    if (value == null || value.trim().isEmpty()) throw invalid(path + " 不能为空");
    if (value.length() > max) throw invalid(path + " 超过长度上限");
    for (int i = 0; i < value.length(); i++) {
      if (Character.isISOControl(value.charAt(i))) throw invalid(path + " 含控制字符");
    }
    if (looksLikeExecutableOrRemote(value)) throw invalid(path + " 含有不允许的脚本或外链");
  }

  private static void validateTokenValue(String key, String value) {
    String lower = value.toLowerCase(Locale.ROOT);
    if (key.equals("bg")
        || key.equals("surface")
        || key.equals("raised")
        || key.equals("text")
        || key.equals("muted")
        || key.equals("subtle")
        || key.equals("accent")
        || key.equals("onAccent")
        || key.equals("good")
        || key.equals("well")) {
      if (!lower.matches("(?:#[0-9a-f]{3,8}|rgba?\\([0-9.,% \\-]+\\))")) {
        throw invalid("tokens." + key + " 不是允许的颜色值");
      }
    } else if (key.equals("radius") || key.equals("gap")) {
      if (!lower.matches("[0-9]+(?:\\.[0-9]+)?(?:px|dp|rem|%)")) {
        throw invalid("tokens." + key + " 不是允许的尺寸值");
      }
    } else if (key.equals("bgImage")) {
      if (!(lower.equals("none")
          || lower.startsWith("linear-gradient(")
          || lower.startsWith("url(assets/"))) {
        throw invalid("tokens.bgImage 只能使用 none、内置渐变或 assets/ 下的资源");
      }
    } else if (key.equals("font") && value.length() > 256) {
      throw invalid("tokens.font 超过长度上限");
    }
    if (lower.contains("javascript:") || lower.contains("<script") || lower.contains("@import")) {
      throw invalid("tokens." + key + " 含有不允许的脚本");
    }
  }

  private static boolean looksLikeExecutableOrRemote(String value) {
    String lower = value.toLowerCase(Locale.ROOT);
    return lower.contains("javascript:")
        || lower.contains("<script")
        || lower.contains("</script")
        || lower.contains("@import")
        || lower.contains("http://")
        || lower.contains("https://")
        || lower.contains("file:")
        || lower.contains("../");
  }

  private static void only(JSONObject object, String... allowed) {
    Set<String> keys = new HashSet<>();
    Collections.addAll(keys, allowed);
    Iterator<String> iterator = object.keys();
    while (iterator.hasNext()) {
      String key = iterator.next();
      if (!keys.contains(key)) throw invalid("不允许的字段：" + key);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("模块清单无效：" + message);
  }
}
