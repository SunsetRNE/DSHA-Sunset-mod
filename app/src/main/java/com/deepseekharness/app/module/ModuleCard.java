package com.deepseekharness.app.module;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 用户自己往主页加的卡片模块（第一版模块契约）。
 *
 * <p>纯声明式卡片（标题/副标题/emoji/强调色/按钮）能力全部由宿主实现，模块只能从
 * 白名单里挑动作；另有带 {@code script} 的卡片走 {@link ModuleScript} 的 WebView 沙箱
 * （断网、断文件、执行仍要逐卡授权）。两种卡片都不加载 Java，也不直接访问网络与文件。
 *
 * <p>典型来源有三个：用户在模块中心选一个 card.json；或者容器里的 dsh 把同样的
 * JSON 写到 {@code ~/.dsha-modules/}，宿主下次回主页时自动收进来；以及广场（gallery）。
 */
public final class ModuleCard {
  public static final int SCHEMA = 1;
  public static final int MAX_ACTIONS = 4;
  public static final int MAX_NAME = 24;
  public static final int MAX_SUBTITLE = 64;
  public static final int MAX_AUTHOR = 48;
  public static final int MAX_VERSION = 32;

  private static final java.util.regex.Pattern VERSION =
      java.util.regex.Pattern.compile(
          "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?");

  /** 导入来源（安装时由宿主记录，不是模块自述；无法判定时是 unknown）。 */
  public static final String SOURCE_CONTAINER = "container";

  public static final String SOURCE_LOCAL = "local";
  public static final String SOURCE_GALLERY = "gallery";
  public static final String SOURCE_UNKNOWN = "unknown";

  public static final List<String> SOURCES =
      Collections.unmodifiableList(
          Arrays.asList(SOURCE_CONTAINER, SOURCE_LOCAL, SOURCE_GALLERY, SOURCE_UNKNOWN));

  /** 按钮动作白名单：每一条都由宿主实现，模块无法自行扩展。 */
  public static final List<String> ACTIONS =
      Collections.unmodifiableList(
          Arrays.asList(
              "toast",
              "copy",
              "url",
              "settings",
              "modules",
              "terminal",
              "exec",
              "sequence",
              "script",
              "page"));

  /** 脚本动作的长度上限；比普通动作的 200 字宽得多，因为它是代码。 */
  public static final int MAX_SCRIPT = 4000;

  /** 宿主已实现的扩展点。模块可以写别的名字，只是宿主不会渲染。 */
  public static final String OVERLAY_SURFACE = "ui.overlay";

  public static final String THEME_SURFACE = "ui.theme";
  public static final String CARD_SURFACE = "home.card";
  public static final String NAV_SURFACE = "ui.nav";
  public static final String APP_SURFACE = "ui.app";
  public static final String WEB_SURFACE = "module.web";
  public static final String SKIN_SURFACE = "ui.skin";
  public static final String LIFECYCLE_SURFACE = "module.lifecycle";
  public static final String PROGRAM_SURFACE = "module.program";

  /** 宿主当前真正认识的扩展点；其余原样保留但在模块中心标「未支持」。 */
  public static final java.util.Set<String> KNOWN_SURFACES =
      java.util.Collections.unmodifiableSet(
          new java.util.LinkedHashSet<>(
              java.util.Arrays.asList(
                  CARD_SURFACE,
                  OVERLAY_SURFACE,
                  THEME_SURFACE,
                  NAV_SURFACE,
                  APP_SURFACE,
                  WEB_SURFACE,
                  SKIN_SURFACE,
                  LIFECYCLE_SURFACE,
                  PROGRAM_SURFACE)));

  public static final int MAX_PROVIDES = 8;
  public static final int MAX_PROVIDES_BYTES = 4096;
  public static final int MAX_VIEW_NODES = 40;
  public static final int MAX_APP_VIEW_NODES = 60;

  /** 动态数据槽位：卡片上最多三行能绑定真实状态。 */
  public static final List<String> SLOTS =
      Collections.unmodifiableList(Arrays.asList("subtitle", "line1", "line2"));

  /** 输入控件类型。 */
  public static final List<String> FIELD_TYPES =
      Collections.unmodifiableList(Arrays.asList("text", "toggle"));

  public static final int MAX_FIELDS = 3;
  public static final int MAX_PAGES = 8;
  public static final int MAX_PAGE_VIEW_NODES = 40;

  /** 卡片二级页面：模块自己的一个可打开页面。 */
  public static final class Page {
    public final String id;
    public final String title;
    public final JSONArray view;

    Page(String id, String title, JSONArray view) {
      this.id = id;
      this.title = title;
      this.view = view;
    }
  }

  /** 卡片里的一个输入控件；动作 value 里用 {{key}} 取它的值。 */
  public static final class Field {
    public final String key;
    public final String label;
    public final String type;
    public final String placeholder;
    public final String defaultValue;

    Field(String key, String label, String type, String placeholder, String defaultValue) {
      this.key = key;
      this.label = label;
      this.type = type;
      this.placeholder = placeholder;
      this.defaultValue = defaultValue;
    }
  }

  /**
   * 悬浮层：模块可以在整个 App 界面上跑一个小东西（宠物、挂件）。
   *
   * <p>它不属于主页的滚动内容，而是画在 {@code android.R.id.content} 上盖住全部页面，
   * 所以切到设置、终端也还在。只有它自己那方块区域接收点击，不会挡住别处。
   */
  public static final class Overlay {
    public final String image;

    /** 边长（dp）；40-160 之间，太小看不清，太大挡内容。 */
    public final int size;

    /** 点它时说什么：普通文字，或 {@code script:} 开头的 JS。空表示说话内容取自副标题。 */
    public final String say;

    Overlay(String image, int size, String say) {
      this.image = image;
      this.size = size;
      this.say = say;
    }
  }

  /**
   * 主题层：模块可以换掉整个 App 的背景图与透明度。
   *
   * <p>只动背景这一层，不重排任何控件 —— 改控件颜色/圆角那是另一套主题 token，不在这一版里。
   */
  /**
   * 主题配置：背景图 + 透明度 + 底色，以及一组可选的全局控件 token。
   *
   * <p>token 只收颜色、圆角、字号倍率和图片文件名；不收资源名、类名、脚本或任意 drawable
   * 路径。宿主不认识或非法的 token 安全回退，不让一个坏字段毁掉整页。
   */
  public static final class Theme {
    public final String image;

    /** 背景图不透明度 0-1；0.15-0.95。 */
    public final float opacity;

    /** 图上再压一层底色 {@code #RRGGBB}，让文字仍然读得清；可空。 */
    public final String scrim;

    public final Tokens tokens;

    Theme(String image, float opacity, String scrim, Tokens tokens) {
      this.image = image;
      this.opacity = opacity;
      this.scrim = scrim;
      this.tokens = tokens == null ? Tokens.EMPTY : tokens;
    }
  }

  /** 全局控件 token；空字符串/负数表示「不覆盖，沿用原样」。 */
  public static final class Tokens {
    public static final Tokens EMPTY = new Tokens("", "", "", "", "", "", "", "", "", -1, -1, 1f);

    public final String accent;
    public final String surface;
    public final String surfaceAlt;
    public final String text;
    public final String textSecondary;
    public final String buttonBg;
    public final String buttonFg;
    public final String navBg;
    public final String navIcon;
    public final int radius;
    public final int buttonRadius;
    public final float fontScale;

    Tokens(
        String accent,
        String surface,
        String surfaceAlt,
        String text,
        String textSecondary,
        String buttonBg,
        String buttonFg,
        String navBg,
        String navIcon,
        int radius,
        int buttonRadius,
        float fontScale) {
      this.accent = accent;
      this.surface = surface;
      this.surfaceAlt = surfaceAlt;
      this.text = text;
      this.textSecondary = textSecondary;
      this.buttonBg = buttonBg;
      this.buttonFg = buttonFg;
      this.navBg = navBg;
      this.navIcon = navIcon;
      this.radius = radius;
      this.buttonRadius = buttonRadius;
      this.fontScale = fontScale;
    }

    public boolean isEmpty() {
      return this == EMPTY
          || accent.isEmpty()
              && surface.isEmpty()
              && surfaceAlt.isEmpty()
              && text.isEmpty()
              && textSecondary.isEmpty()
              && buttonBg.isEmpty()
              && buttonFg.isEmpty()
              && navBg.isEmpty()
              && navIcon.isEmpty()
              && radius < 0
              && buttonRadius < 0
              && Math.abs(fontScale - 1f) < 0.001f;
    }

    static Tokens parse(JSONObject json) {
      if (json == null) return EMPTY;
      if (json.toString().length() > 4096)
        throw new IllegalArgumentException("theme.tokens 配置太长（上限 4096 字节）");
      String accent = color(json, "accent", "theme.tokens.accent");
      String surface = color(json, "surface", "theme.tokens.surface");
      String surfaceAlt = color(json, "surfaceAlt", "theme.tokens.surfaceAlt");
      String text = color(json, "text", "theme.tokens.text");
      String textSecondary = color(json, "textSecondary", "theme.tokens.textSecondary");
      String buttonBg = color(json, "buttonBg", "theme.tokens.buttonBg");
      String buttonFg = color(json, "buttonFg", "theme.tokens.buttonFg");
      String navBg = color(json, "navBg", "theme.tokens.navBg");
      String navIcon = color(json, "navIcon", "theme.tokens.navIcon");
      int radius = boundedInt(json, "radius", -1, 0, 32);
      int buttonRadius = boundedInt(json, "buttonRadius", -1, 0, 32);
      double scale = json.optDouble("fontScale", 1.0);
      if (Double.isNaN(scale) || scale < 0.8 || scale > 1.3)
        throw new IllegalArgumentException("theme.tokens.fontScale 要在 0.8-1.3 之间");
      return new Tokens(
          accent,
          surface,
          surfaceAlt,
          text,
          textSecondary,
          buttonBg,
          buttonFg,
          navBg,
          navIcon,
          radius,
          buttonRadius,
          (float) scale);
    }

    private static String color(JSONObject json, String key, String field) {
      String value = json.optString(key, "").trim();
      if (!value.isEmpty() && !value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))
        throw new IllegalArgumentException(field + " 必须是 #RRGGBB 或 #AARRGGBB");
      return value;
    }

    private static int boundedInt(JSONObject json, String key, int fallback, int min, int max) {
      if (!json.has(key)) return fallback;
      int value = json.optInt(key, Integer.MIN_VALUE);
      if (value < min || value > max)
        throw new IllegalArgumentException(
            "theme.tokens." + key + " 要在 " + min + "-" + max + " 之间");
      return value;
    }
  }

  /** 供宿主内部构造动作（声明式视图里的按钮就是 "type:参数" 字符串）。 */
  public static Action action(String label, String type, String value) {
    return new Action(
        label == null || label.isEmpty() ? "·" : label,
        type == null ? "" : type,
        value == null ? "" : value);
  }

  public static final class Action {
    public final String label;
    public final String type;
    public final String value;

    Action(String label, String type, String value) {
      this.label = label;
      this.type = type;
      this.value = value;
    }
  }

  public final String id;

  /** 模块作者标识；未声明时表示本地用户生成。 */
  public final String author;

  /** 模块版本；用于更新和广场排序，不参与运行时能力判断。 */
  public final String version;

  /**
   * 安装来源（host 记录）：container / local / gallery / unknown。
   *
   * <p>来源是宿主在安装时记录的事实，不是 JSON 自述字段；模块自己写 source 无效。
   * 旧版本装的模块没有记录时是 {@link #SOURCE_UNKNOWN}。
   */
  public final String source;

  /**
   * 原始 card.json 的 SHA-256（小写十六进制）；内容一致性识别用，不是作者签名。
   */
  public final String digest;

  public final String name;
  public final String emoji;
  public final String subtitle;

  /** {@code #RRGGBB}，空串表示使用主题强调色。 */
  public final String accent;

  /**
   * 立绘文件名（与 card.json 同目录）。空表示没有图，退回 emoji 显示。
   *
   * <p>只接受文件名，不接受路径：不允许 {@code ../}，也不允许子目录。
   * 图片由宿主在导入时一起拷进私有目录，卡片运行时只读自己的那一份。
   */
  public final String image;

  /** 可选分离签名文件名（.sig）；内容覆盖 card.json 与全部素材，见 ModuleSignature。 */
  public final String signature;

  public final List<Action> actions;

  /** 槽位 → 数据源 key（见 ModuleData.SOURCES）。空表示该槽位是静态文字。 */
  public final java.util.Map<String, String> bind;

  /** 槽位 → 显示前缀（"今日启动" 之类），让单独一个数字也能看懂。 */
  public final java.util.Map<String, String> bindLabels;

  public final List<Field> fields;

  /**
   * 扩展点表：<b>surface 名 → 配置</b>。
   *
   * <p>这是整套模块体系的开放点。宿主认识 {@code ui.overlay / ui.theme / home.card}，
   * 但不限制模块只能声明这几个：写了宿主不认识的 surface 不会报错，会原样保留并在模块中心
   * 标成「未支持」。这样以后加能力只改宿主、不用改契约，老版本也不会因为新模块而整卡失效。
   */
  public final java.util.Map<String, JSONObject> provides;

  /**
   * 自绘界面：一串节点（text / image / row / column / card / space / button / link / badge /
   * segmented / progress），由宿主渲染。
   *
   * <p>有了它，模块想要什么版式就写什么版式，不需要我来加字段。
   * 空表示用默认卡片版式（立绘 + 标题 + 按钮）。
   */
  public final JSONArray view;

  /** 悬浮层配置（{@code ui.overlay} 的解析结果）；null 表示没有悬浮层。 */
  public final Overlay overlay;

  /** 主题层配置（{@code ui.theme} 的解析结果）；null 表示不改 App 外观。 */
  public final Theme theme;

  /** 底栏外观配置（{@code ui.nav} 的解析结果）；null 表示沿用默认图标和文字。 */
  public final Nav nav;

  /**
   * 模块接管主页的界面（{@code ui.app}）；null 表示不接管。
   *
   * <p>只允许接管主页内容区，不允许覆盖模块中心/设置等安全入口：用户必须始终能撤销模块。
   */
  public final App app;

  /** 模块自带网页资源（{@code module.web}）；null 表示没有。 */
  public final Web web;

  /** 宿主控件换肤（{@code ui.skin}）；null 表示不改原生控件。 */
  public final Skin skin;

  /** 二级页面：page id → 页面；空表示只有卡片本身。 */
  public final java.util.Map<String, Page> pages;

  /** 通用生命周期：模块可见/隐藏时跑脚本，可选前台 tick。 */
  public final Lifecycle lifecycle;

  /** 受管 ARM/x86 程序：program id → 程序声明；空表示没有程序能力。 */
  public final java.util.Map<String, Program> programs;

  /** 一个受管程序；宿主只按固定 argv 执行，不把 args 拼进 shell。 */
  public static final class Program {
    public final String id;
    public final String file;
    public final String sha256;
    public final String abi;
    public final String entry;
    public final java.util.List<String> args;
    public final int timeoutSeconds;

    Program(
        String id,
        String file,
        String sha256,
        String abi,
        String entry,
        java.util.List<String> args,
        int timeoutSeconds) {
      this.id = id;
      this.file = file;
      this.sha256 = sha256;
      this.abi = abi;
      this.entry = entry;
      this.args = Collections.unmodifiableList(new ArrayList<>(args));
      this.timeoutSeconds = timeoutSeconds;
    }
  }

  /** 宿主级生命周期脚本；只在前台卡片可见时执行，不进入后台常驻。 */
  public static final class Lifecycle {
    public final String onShow;
    public final String onHide;
    public final int tickEverySeconds;
    public final String tick;

    Lifecycle(String onShow, String onHide, int tickEverySeconds, String tick) {
      this.onShow = onShow;
      this.onHide = onHide;
      this.tickEverySeconds = tickEverySeconds;
      this.tick = tick;
    }
  }

  /** 底栏固定五个目的地；模块只能改外观，不能替换宿主路由。 */
  public static final List<String> NAV_KEYS =
      Collections.unmodifiableList(
          Arrays.asList("launch", "module", "plugins", "settings", "terminal"));

  /** 底栏一项的自定义外观。 */
  public static final class NavItem {
    public final String label;
    public final String image;

    NavItem(String label, String image) {
      this.label = label;
      this.image = image;
    }
  }

  /** {@code ui.nav} 的解析结果：key → label/image，以及底栏磨砂开关。 */
  public static final class Nav {
    public final java.util.Map<String, NavItem> items;

    /** 模块自己开启底栏实时磨砂；宿主不再内置这个开关。 */
    public final boolean blur;

    Nav(java.util.Map<String, NavItem> items, boolean blur) {
      this.items = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(items));
      this.blur = blur;
    }
  }

  /**
   * 模块网页：模块自带 HTML/CSS/JS，宿主用沙箱 WebView 渲染。
   *
   * <p>这是“没有节点表也能做”的通用层：布局、Canvas、SVG、CSS 动画都由模块自己写，
   * 宿主只提供离线资源服务器和 {@code dsha} 桥。网络与文件访问仍关闭，能力调用仍走权限。
   */
  public static final class Web {
    public final String entry;
    public final java.util.List<String> files;

    Web(String entry, java.util.List<String> files) {
      this.entry = entry;
      this.files = Collections.unmodifiableList(new ArrayList<>(files));
    }
  }

  /**
   * 模块主页：接管主页内容区的一段声明式界面。
   *
   * <p>这是「用户会写模块就能改变 App」的入口之一：模块可以自己排版、放进度动效、
   * 分段选择、徽标、链接和按钮；宿主只负责渲染与权限。底栏、设置、模块中心仍由宿主
   * 保留，确保用户随时能撤销模块。
   */
  public static final class App {
    public final String title;
    public final String subtitle;
    public final String background;
    public final String exitLabel;
    public final JSONArray view;

    /** 可接管的宿主页面：home / settings / plugins；模块中心和终端永远不开放。 */
    public final java.util.List<String> targets;

    App(
        String title,
        String subtitle,
        String background,
        String exitLabel,
        JSONArray view,
        java.util.List<String> targets) {
      this.title = title;
      this.subtitle = subtitle;
      this.background = background;
      this.exitLabel = exitLabel;
      this.view = view;
      this.targets = Collections.unmodifiableList(new ArrayList<>(targets));
    }
  }

  /** 允许模块接管的宿主页面；module 与 terminal 是安全出口，不开放覆盖。 */
  public static final java.util.List<String> APP_TARGETS =
      Collections.unmodifiableList(Arrays.asList("home", "settings", "plugins"));

  /**
   * 原生控件换肤：让模块改「已有的」宿主控件，而不是另画一个。
   *
   * <p>目前支持 {@code home.progress}（主页启动状态条）：粗细、圆角、四种状态色、
   * 光带宽度和扫光时长；以及 {@code home.cards}（主页原生卡片皮肤）：底色、背景图、
   * 圆角、文字/次级文字/图标颜色。以后新增槽位只加字段，模块契约不变。
   */
  public static final class Skin {
    public final Progress homeProgress;

    /** 卡片皮肤：键是卡片 id（metric-environment / logs / user:<id>…），"*" 是默认。 */
    public final java.util.Map<String, CardTokens> homeCards;

    Skin(Progress homeProgress, java.util.Map<String, CardTokens> homeCards) {
      this.homeProgress = homeProgress;
      this.homeCards =
          homeCards == null
              ? java.util.Collections.emptyMap()
              : java.util.Collections.unmodifiableMap(homeCards);
    }

    /** 取某张卡片的皮肤；没有专属就用 "*"，都没有返回 null。 */
    public CardTokens cardTokens(String cardId) {
      CardTokens specific = homeCards.get(cardId);
      if (specific != null) return specific;
      return homeCards.get("*");
    }

    /** 一张原生卡片的皮肤 token；全部可选，写了才覆盖。 */
    public static final class CardTokens {
      public final String bg;
      public final String image;
      public final float imageOpacity;
      public final int radiusDp;
      public final String text;
      public final String sub;
      public final String iconTint;

      CardTokens(
          String bg,
          String image,
          float imageOpacity,
          int radiusDp,
          String text,
          String sub,
          String iconTint) {
        this.bg = bg;
        this.image = image;
        this.imageOpacity = imageOpacity;
        this.radiusDp = radiusDp;
        this.text = text;
        this.sub = sub;
        this.iconTint = iconTint;
      }
    }

    public static final class Progress {
      public final int heightDp;
      public final int radiusDp;
      public final String idle;
      public final String running;
      public final String success;
      public final String error;
      public final float bandFraction;
      public final int sweepMs;

      Progress(
          int heightDp,
          int radiusDp,
          String idle,
          String running,
          String success,
          String error,
          float bandFraction,
          int sweepMs) {
        this.heightDp = heightDp;
        this.radiusDp = radiusDp;
        this.idle = idle;
        this.running = running;
        this.success = success;
        this.error = error;
        this.bandFraction = bandFraction;
        this.sweepMs = sweepMs;
      }
    }
  }

  private ModuleCard(
      String id,
      String author,
      String version,
      String source,
      String digest,
      String name,
      String emoji,
      String subtitle,
      String accent,
      String image,
      String signature,
      List<Action> actions,
      java.util.Map<String, String> bind,
      java.util.Map<String, String> bindLabels,
      List<Field> fields,
      java.util.Map<String, JSONObject> provides,
      JSONArray view,
      Overlay overlay,
      Theme theme,
      Nav nav,
      Web web,
      Skin skin,
      App app,
      java.util.Map<String, Page> pages,
      Lifecycle lifecycle,
      java.util.Map<String, Program> programs) {
    this.id = id;
    this.author = author;
    this.version = version;
    this.source = source;
    this.digest = digest;
    this.name = name;
    this.emoji = emoji;
    this.subtitle = subtitle;
    this.accent = accent;
    this.image = image;
    this.signature = signature;
    this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
    this.bind = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(bind));
    this.bindLabels = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(bindLabels));
    this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
    this.provides = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(provides));
    this.view = view;
    this.overlay = overlay;
    this.theme = theme;
    this.nav = nav;
    this.web = web;
    this.skin = skin;
    this.app = app;
    this.pages = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(pages));
    this.lifecycle = lifecycle;
    this.programs = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(programs));
  }

  /** 严格解析；任何不合规直接抛，附带人话原因，不做「尽量猜」。来源按本地导入记录。 */
  public static ModuleCard parse(String raw) throws IllegalArgumentException {
    return parse(raw, SOURCE_LOCAL);
  }

  /**
   * 带安装来源解析。来源由宿主在安装时记录（容器收取 / 本地导入 / 广场），
   * 不是模块 JSON 自己声明的字段 —— 模块写什么 source 都会被忽略。
   */
  public static ModuleCard parse(String raw, String source) throws IllegalArgumentException {
    final String recordedSource = source == null || source.isEmpty() ? SOURCE_UNKNOWN : source;
    if (!SOURCES.contains(recordedSource))
      throw new IllegalArgumentException("来源不合法：" + recordedSource);
    JSONObject json;
    try {
      json = new JSONObject(raw);
    } catch (Exception error) {
      throw new IllegalArgumentException("不是合法 JSON");
    }
    if (json.optInt("schema", 0) != SCHEMA) throw new IllegalArgumentException("schema 必须是 1");

    String id = text(json, "id", 1, 48);
    if (!id.matches("[a-z0-9][a-z0-9._-]{0,47}"))
      throw new IllegalArgumentException("id 只能小写字母数字和 ._-");

    String author = json.optString("author", "local").trim();
    if (author.isEmpty() || author.length() > MAX_AUTHOR || !author.matches("[A-Za-z0-9._@-]+"))
      throw new IllegalArgumentException("author 只能是 1-48 个安全字符");
    String version = json.optString("version", "0.1.0").trim();
    if (version.length() > MAX_VERSION || !VERSION.matcher(version).matches())
      throw new IllegalArgumentException("version 必须是 SemVer");
    String name = text(json, "name", 1, MAX_NAME);
    String emoji = json.optString("emoji", "").trim();
    if (emoji.length() > 8) throw new IllegalArgumentException("emoji 太长");
    String subtitle = json.optString("subtitle", "").trim();
    if (subtitle.length() > MAX_SUBTITLE) throw new IllegalArgumentException("副标题太长");

    String accent = json.optString("accent", "").trim();
    if (!accent.isEmpty() && !accent.matches("#[0-9a-fA-F]{6}"))
      throw new IllegalArgumentException("accent 必须是 #RRGGBB");

    String image = json.optString("image", "").trim();
    // 只接受文件名 + 图片扩展名：挡掉 ../、子目录和非图片类型（例如让宿主去存 .exe）。
    if (!image.isEmpty()) requireImageName(image, "image");

    String signature = json.optString("signature", "").trim();
    if (!signature.isEmpty()) requireSignatureName(signature, "signature");

    // 二级页面：先解析，动作里的 page:<id> 才能校验目标存在。
    java.util.Map<String, Page> pages = new java.util.LinkedHashMap<>();
    JSONArray pagesJson = json.optJSONArray("pages");
    if (pagesJson != null) {
      if (pagesJson.length() > MAX_PAGES)
        throw new IllegalArgumentException("二级页面最多 " + MAX_PAGES + " 个");
      for (int i = 0; i < pagesJson.length(); i++) {
        JSONObject item = pagesJson.optJSONObject(i);
        if (item == null) throw new IllegalArgumentException("pages[" + i + "] 必须是对象");
        String pageId = item.optString("id", "").trim();
        if (!pageId.matches("[a-z0-9][a-z0-9._-]{0,31}"))
          throw new IllegalArgumentException("页面 id 只能小写字母数字和 ._-（1-32 字）");
        if (pages.containsKey(pageId)) throw new IllegalArgumentException("页面 id 重复：" + pageId);
        String title = item.optString("title", "").trim();
        if (title.isEmpty() || title.length() > 32)
          throw new IllegalArgumentException("页面标题 1-32 字");
        JSONArray pageView = item.optJSONArray("view");
        if (pageView == null || pageView.length() == 0 || pageView.length() > MAX_PAGE_VIEW_NODES)
          throw new IllegalArgumentException(
              "pages[" + pageId + "].view 节点数量要在 1-" + MAX_PAGE_VIEW_NODES + " 之间");
        pages.put(pageId, new Page(pageId, title, pageView));
      }
    }

    List<Action> actions = new ArrayList<>();
    JSONArray list = json.optJSONArray("actions");
    if (list != null) {
      if (list.length() > MAX_ACTIONS)
        throw new IllegalArgumentException("按钮最多 " + MAX_ACTIONS + " 个");
      for (int i = 0; i < list.length(); i++) {
        JSONObject item = list.optJSONObject(i);
        if (item == null) throw new IllegalArgumentException("按钮格式不对");
        String label = item.optString("label", "").trim();
        if (label.isEmpty() || label.length() > 12)
          throw new IllegalArgumentException("按钮文字 1-12 字");
        String type = item.optString("type", "").trim();
        if (!ACTIONS.contains(type)) throw new IllegalArgumentException("不支持的动作：" + type);
        String value = item.optString("value", "").trim();
        if ("script".equals(type)) {
          if (value.isEmpty()) throw new IllegalArgumentException("script 需要 value（一段 JS）");
          if (value.length() > MAX_SCRIPT)
            throw new IllegalArgumentException("脚本太长（上限 " + MAX_SCRIPT + " 字符）");
        } else if (value.length() > 200) {
          throw new IllegalArgumentException("动作参数太长");
        }
        boolean noValue =
            "terminal".equals(type) || "settings".equals(type) || "modules".equals(type);
        if (!noValue && value.isEmpty()) throw new IllegalArgumentException(type + " 需要 value");
        if ("page".equals(type) && !pages.containsKey(value))
          throw new IllegalArgumentException("page 目标不存在：" + value);
        if ("sequence".equals(type)) {
          // 一串步骤，每行 "type:参数"，用来把多个动作编成一个按钮。
          for (String step : value.split("\n")) {
            if (step.trim().isEmpty()) continue;
            int colon = step.indexOf(':');
            if (colon <= 0) throw new IllegalArgumentException("步骤格式应为 type:参数 → " + step);
            String stepType = step.substring(0, colon).trim();
            if (!ACTIONS.contains(stepType) || "sequence".equals(stepType))
              throw new IllegalArgumentException("步骤里不能用：" + stepType);
            if (stepType.length() + 1 >= step.length())
              throw new IllegalArgumentException("步骤缺少内容：" + step);
            if ("page".equals(stepType) && !pages.containsKey(step.substring(colon + 1).trim()))
              throw new IllegalArgumentException(
                  "步骤里的 page 目标不存在：" + step.substring(colon + 1).trim());
          }
        }
        actions.add(new Action(label, type, value));
      }
    }

    java.util.Map<String, String> bind = new java.util.LinkedHashMap<>();
    java.util.Map<String, String> bindLabels = new java.util.LinkedHashMap<>();
    JSONObject bindJson = json.optJSONObject("bind");
    if (bindJson != null) {
      // 先校验键名：写了 line9 这种不存在的槽位要立刻报错，不能静默忽略。
      java.util.Iterator<String> slotKeys = bindJson.keys();
      while (slotKeys.hasNext()) {
        String slot = slotKeys.next();
        if (!SLOTS.contains(slot)) throw new IllegalArgumentException("不认识的槽位：" + slot);
      }
      for (String slot : SLOTS) {
        String spec = bindJson.optString(slot, "").trim();
        if (spec.isEmpty()) continue;
        // 支持 "今日启动|dsh.runs"：前半是给人看的标签，后半才是数据源。
        String label = "";
        String dataSource = spec;
        int bar = spec.indexOf('|');
        if (bar > 0) {
          label = spec.substring(0, bar).trim();
          dataSource = spec.substring(bar + 1).trim();
        }
        if (!com.deepseekharness.app.module.ModuleData.SOURCES.contains(dataSource))
          throw new IllegalArgumentException("不认识的数据源：" + dataSource);
        bind.put(slot, dataSource);
        if (!label.isEmpty()) {
          if (label.length() > 10) throw new IllegalArgumentException("数据标签太长");
          bindLabels.put(slot, label);
        }
      }
    }

    List<Field> fields = new ArrayList<>();
    JSONArray fieldJson = json.optJSONArray("fields");
    if (fieldJson != null) {
      if (fieldJson.length() > MAX_FIELDS)
        throw new IllegalArgumentException("输入控件最多 " + MAX_FIELDS + " 个");
      for (int i = 0; i < fieldJson.length(); i++) {
        JSONObject item = fieldJson.optJSONObject(i);
        if (item == null) throw new IllegalArgumentException("输入控件格式不对");
        String key = item.optString("key", "").trim();
        if (!key.matches("[a-z][a-z0-9_]{0,15}"))
          throw new IllegalArgumentException("输入控件 key 只能小写字母数字下划线");
        String label = item.optString("label", "").trim();
        if (label.isEmpty() || label.length() > 10)
          throw new IllegalArgumentException("输入控件标题 1-10 字");
        String type = item.optString("type", "text").trim();
        if (!FIELD_TYPES.contains(type)) throw new IllegalArgumentException("不支持的输入类型：" + type);
        String placeholder = item.optString("placeholder", "").trim();
        if (placeholder.length() > 24) throw new IllegalArgumentException("占位文字太长");
        String defaultValue = item.optString("default", "").trim();
        if (defaultValue.length() > 200) throw new IllegalArgumentException("默认值太长");
        fields.add(new Field(key, label, type, placeholder, defaultValue));
      }
    }
    // 扩展点：新写法 provides 与旧写法（顶层 overlay/theme）合并，旧的折进 provides。
    java.util.Map<String, JSONObject> provides = new java.util.LinkedHashMap<>();
    JSONObject providesJson = json.optJSONObject("provides");
    if (providesJson != null) {
      if (providesJson.length() > MAX_PROVIDES)
        throw new IllegalArgumentException("扩展点最多 " + MAX_PROVIDES + " 个");
      java.util.Iterator<String> keys = providesJson.keys();
      while (keys.hasNext()) {
        String surface = keys.next();
        if (!surface.matches("[a-z][a-z0-9.]{1,40}"))
          throw new IllegalArgumentException("扩展点名不合法：" + surface);
        JSONObject config = providesJson.optJSONObject(surface);
        if (config == null) throw new IllegalArgumentException("扩展点 " + surface + " 的配置必须是对象");
        if (config.toString().length() > MAX_PROVIDES_BYTES)
          throw new IllegalArgumentException("扩展点 " + surface + " 的配置太长");
        provides.put(surface, config);
      }
    }
    JSONObject legacyOverlay = json.optJSONObject("overlay");
    if (legacyOverlay != null) provides.put(OVERLAY_SURFACE, legacyOverlay);
    JSONObject legacyTheme = json.optJSONObject("theme");
    if (legacyTheme != null) provides.put(THEME_SURFACE, legacyTheme);

    JSONArray view = json.optJSONArray("view");
    if (view != null) {
      if (view.length() == 0 || view.length() > MAX_VIEW_NODES)
        throw new IllegalArgumentException("view 节点数量要在 1-" + MAX_VIEW_NODES + " 之间");
    }

    Overlay overlay = null;
    JSONObject overlayJson = provides.get(OVERLAY_SURFACE);
    if (overlayJson != null) {
      String art = overlayJson.optString("image", "").trim();
      if (art.isEmpty()) throw new IllegalArgumentException("overlay 需要 image（立绘文件名）");
      requireImageName(art, "overlay.image");
      int size = overlayJson.optInt("size", 84);
      if (size < 40 || size > 160) throw new IllegalArgumentException("overlay.size 要在 40-160 之间");
      String say = overlayJson.optString("say", "").trim();
      if (say.length() > 500) throw new IllegalArgumentException("overlay.say 太长（上限 500 字）");
      overlay = new Overlay(art, size, say);
    }

    Theme theme = null;
    JSONObject themeJson = provides.get(THEME_SURFACE);
    if (themeJson != null) {
      String art = themeJson.optString("image", "").trim();
      if (art.isEmpty()) throw new IllegalArgumentException("theme 需要 image（背景图文件名）");
      requireImageName(art, "theme.image");
      double opacity = themeJson.optDouble("opacity", 0.35);
      if (Double.isNaN(opacity) || opacity < 0.15 || opacity > 0.95)
        throw new IllegalArgumentException("theme.opacity 要在 0.15-0.95 之间");
      String scrim = themeJson.optString("scrim", "").trim();
      if (!scrim.isEmpty() && !scrim.matches("#[0-9a-fA-F]{6}"))
        throw new IllegalArgumentException("theme.scrim 必须是 #RRGGBB");
      Tokens tokens = Tokens.parse(themeJson.optJSONObject("tokens"));
      theme = new Theme(art, (float) opacity, scrim, tokens);
    }

    Nav nav = null;
    JSONObject navJson = provides.get(NAV_SURFACE);
    if (navJson != null) {
      java.util.Map<String, NavItem> parsed = new java.util.LinkedHashMap<>();
      JSONObject items = navJson.optJSONObject("items");
      if (items != null) {
        if (items.length() > NAV_KEYS.size())
          throw new IllegalArgumentException("ui.nav.items 最多 " + NAV_KEYS.size() + " 项");
        java.util.Iterator<String> keys = items.keys();
        while (keys.hasNext()) {
          String key = keys.next();
          if (!NAV_KEYS.contains(key)) throw new IllegalArgumentException("ui.nav 不认识的底栏项：" + key);
          JSONObject item = items.optJSONObject(key);
          if (item == null) throw new IllegalArgumentException("ui.nav." + key + " 必须是对象");
          String label = item.optString("label", "").trim();
          if (label.length() > 8)
            throw new IllegalArgumentException("ui.nav." + key + ".label 最多 8 字");
          String art = item.optString("image", "").trim();
          if (!art.isEmpty()) requireImageName(art, "ui.nav." + key + ".image");
          parsed.put(key, new NavItem(label, art));
        }
      }
      boolean blur = navJson.optBoolean("blur", false);
      if (!parsed.isEmpty() || blur) nav = new Nav(parsed, blur);
    }

    Web web = null;
    JSONObject webJson = provides.get(WEB_SURFACE);
    if (webJson != null) {
      String entry = webJson.optString("entry", "index.html").trim();
      requireWebName(entry, "module.web.entry");
      if (!entry.toLowerCase(java.util.Locale.ROOT).endsWith(".html"))
        throw new IllegalArgumentException("module.web.entry 必须是 .html 文件");
      JSONArray fileList = webJson.optJSONArray("files");
      if (fileList == null || fileList.length() == 0 || fileList.length() > 32)
        throw new IllegalArgumentException("module.web.files 要有 1-32 个文件");
      java.util.List<String> files = new java.util.ArrayList<>();
      for (int i = 0; i < fileList.length(); i++) {
        String fileName = String.valueOf(fileList.opt(i)).trim();
        requireWebName(fileName, "module.web.files[" + i + "]");
        if (!files.contains(fileName)) files.add(fileName);
      }
      if (!files.contains(entry)) files.add(entry);
      web = new Web(entry, files);
    }

    Skin skin = null;
    JSONObject skinJson = provides.get(SKIN_SURFACE);
    if (skinJson != null) {
      JSONObject progressJson = skinJson.optJSONObject("home.progress");
      Skin.Progress progress = null;
      if (progressJson != null) {
        int height = progressJson.optInt("height", 2);
        if (height < 1 || height > 16)
          throw new IllegalArgumentException("ui.skin.home.progress.height 要在 1-16 之间");
        int radius = progressJson.optInt("radius", 1);
        if (radius < 0 || radius > 8)
          throw new IllegalArgumentException("ui.skin.home.progress.radius 要在 0-8 之间");
        String idle = skinColor(progressJson, "idle", "ui.skin.home.progress");
        String running = skinColor(progressJson, "running", "ui.skin.home.progress");
        String success = skinColor(progressJson, "success", "ui.skin.home.progress");
        String error = skinColor(progressJson, "error", "ui.skin.home.progress");
        double band = progressJson.optDouble("band", 0.34);
        if (Double.isNaN(band) || band < 0.05 || band > 1)
          throw new IllegalArgumentException("ui.skin.home.progress.band 要在 0.05-1 之间");
        int sweep = progressJson.optInt("sweep", 2600);
        if (sweep < 300 || sweep > 10000)
          throw new IllegalArgumentException("ui.skin.home.progress.sweep 要在 300-10000 ms");
        progress =
            new Skin.Progress(height, radius, idle, running, success, error, (float) band, sweep);
      }
      java.util.Map<String, Skin.CardTokens> homeCards = new java.util.LinkedHashMap<>();
      JSONObject cardsJson = skinJson.optJSONObject("home.cards");
      if (cardsJson != null) {
        java.util.Iterator<String> keys = cardsJson.keys();
        while (keys.hasNext()) {
          String cardKey = keys.next();
          if (homeCards.size() >= 16)
            throw new IllegalArgumentException("ui.skin.home.cards 最多 16 条");
          JSONObject tokensJson = cardsJson.optJSONObject(cardKey);
          if (tokensJson == null)
            throw new IllegalArgumentException("ui.skin.home.cards." + cardKey + " 必须是对象");
          String prefix = "ui.skin.home.cards." + cardKey;
          String bg = skinColor(tokensJson, "bg", prefix);
          String text = skinColor(tokensJson, "text", prefix);
          String sub = skinColor(tokensJson, "sub", prefix);
          String iconTint = skinColor(tokensJson, "iconTint", prefix);
          String cardImage = tokensJson.optString("image", "").trim();
          // 校验的是 cardImage 自己：漏掉这一步会让 ../ 与绝对路径混进 assetNames()。
          if (!cardImage.isEmpty() && !isImageName(cardImage))
            throw new IllegalArgumentException(prefix + ".image 必须是模块目录里的图片文件名");
          double imageOpacity = tokensJson.optDouble("opacity", 1.0);
          if (Double.isNaN(imageOpacity) || imageOpacity < 0 || imageOpacity > 1)
            throw new IllegalArgumentException(prefix + ".opacity 要在 0-1 之间");
          int cardRadius = tokensJson.optInt("radius", -1);
          if (cardRadius < -1 || cardRadius > 32)
            throw new IllegalArgumentException(prefix + ".radius 要在 0-32 之间");
          // 这里同样要用 cardImage：用顶层 image 会把"只配了皮肤图"的合法条目判成空条目。
          if (bg.isEmpty()
              && cardImage.isEmpty()
              && text.isEmpty()
              && sub.isEmpty()
              && iconTint.isEmpty()
              && cardRadius < 0)
            throw new IllegalArgumentException(
                prefix + " 至少要写一项（bg/image/text/sub/iconTint/radius）");
          homeCards.put(
              cardKey,
              new Skin.CardTokens(
                  bg, cardImage, (float) imageOpacity, cardRadius, text, sub, iconTint));
        }
      }
      if (progress != null || !homeCards.isEmpty()) skin = new Skin(progress, homeCards);
    }

    App app = null;
    JSONObject appJson = provides.get(APP_SURFACE);
    if (appJson != null) {
      JSONArray appView = appJson.optJSONArray("view");
      if (appView == null || appView.length() == 0 || appView.length() > MAX_APP_VIEW_NODES)
        throw new IllegalArgumentException("ui.app.view 节点数量要在 1-" + MAX_APP_VIEW_NODES + " 之间");
      String title = appJson.optString("title", "").trim();
      if (title.length() > 16) throw new IllegalArgumentException("ui.app.title 最多 16 字");
      String appSubtitle = appJson.optString("subtitle", "").trim();
      if (appSubtitle.length() > 60) throw new IllegalArgumentException("ui.app.subtitle 最多 60 字");
      String background = appJson.optString("background", "").trim();
      if (!background.isEmpty() && !background.matches("#[0-9a-fA-F]{6,8}"))
        throw new IllegalArgumentException("ui.app.background 必须是 #RRGGBB 或 #AARRGGBB");
      String exit = appJson.optString("exit", "").trim();
      if (exit.length() > 12) throw new IllegalArgumentException("ui.app.exit 最多 12 字");
      java.util.List<String> targets = new java.util.ArrayList<>();
      JSONArray targetJson = appJson.optJSONArray("targets");
      if (targetJson != null) {
        if (targetJson.length() > APP_TARGETS.size())
          throw new IllegalArgumentException("ui.app.targets 最多 " + APP_TARGETS.size() + " 项");
        for (int i = 0; i < targetJson.length(); i++) {
          String target = String.valueOf(targetJson.opt(i)).trim();
          if (!APP_TARGETS.contains(target))
            throw new IllegalArgumentException("ui.app.targets 只允许 " + APP_TARGETS + "：" + target);
          if (!targets.contains(target)) targets.add(target);
        }
      }
      if (targets.isEmpty()) targets.add("home");
      app = new App(title, appSubtitle, background, exit, appView, targets);
    }

    Lifecycle lifecycle = null;
    JSONObject lifeJson = provides.get(LIFECYCLE_SURFACE);
    if (lifeJson != null) {
      String onShow = scriptField(lifeJson, "onShow");
      String onHide = scriptField(lifeJson, "onHide");
      int tickEvery = 0;
      String tick = "";
      JSONObject tickJson = lifeJson.optJSONObject("tick");
      if (tickJson != null) {
        tickEvery = tickJson.optInt("every", 0);
        if (tickEvery < 10 || tickEvery > 3600)
          throw new IllegalArgumentException("module.lifecycle.tick.every 要在 10-3600 秒之间");
        tick = scriptField(tickJson, "script");
        if (tick.isEmpty()) throw new IllegalArgumentException("module.lifecycle.tick 需要 script");
      }
      if (!onShow.isEmpty() || !onHide.isEmpty() || !tick.isEmpty())
        lifecycle = new Lifecycle(onShow, onHide, tickEvery, tick);
    }

    java.util.Map<String, Program> programs = new java.util.LinkedHashMap<>();
    JSONObject programJson = provides.get(PROGRAM_SURFACE);
    if (programJson != null) {
      JSONArray programList = programJson.optJSONArray("programs");
      if (programList == null || programList.length() == 0)
        throw new IllegalArgumentException("module.program 需要 programs 数组（至少 1 个）");
      if (programList.length() > 4) throw new IllegalArgumentException("受管程序最多 4 个");
      for (int i = 0; i < programList.length(); i++) {
        JSONObject item = programList.optJSONObject(i);
        if (item == null) throw new IllegalArgumentException("programs[" + i + "] 必须是对象");
        String programId = item.optString("id", "").trim();
        if (!programId.matches("[a-z0-9][a-z0-9._-]{0,31}"))
          throw new IllegalArgumentException("程序 id 只能小写字母数字和 ._-（1-32 字）");
        if (programs.containsKey(programId))
          throw new IllegalArgumentException("程序 id 重复：" + programId);
        String file = item.optString("file", "").trim();
        requireProgramName(file, "programs[" + programId + "].file");
        String sha = item.optString("sha256", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (!sha.matches("[0-9a-f]{64}"))
          throw new IllegalArgumentException("programs[" + programId + "].sha256 必须是 64 位十六进制");
        String abi = item.optString("abi", "").trim();
        if (!"arm64-v8a".equals(abi) && !"x86_64".equals(abi))
          throw new IllegalArgumentException(
              "programs[" + programId + "].abi 只允许 arm64-v8a 或 x86_64");
        String entry = item.optString("entry", file).trim();
        requireProgramName(entry, "programs[" + programId + "].entry");
        if (!entry.equals(file))
          throw new IllegalArgumentException("programs[" + programId + "].entry 目前必须与 file 同名");
        java.util.List<String> args = new java.util.ArrayList<>();
        JSONArray argsJson = item.optJSONArray("args");
        if (argsJson != null) {
          if (argsJson.length() > 8) throw new IllegalArgumentException("程序参数最多 8 个");
          for (int a = 0; a < argsJson.length(); a++) {
            String arg = String.valueOf(argsJson.opt(a));
            if (arg.length() > 64 || arg.indexOf('\0') >= 0)
              throw new IllegalArgumentException("程序参数太长或含非法字符");
            args.add(arg);
          }
        }
        int timeout = item.optInt("timeout", 30);
        if (timeout < 1 || timeout > 300)
          throw new IllegalArgumentException("程序 timeout 要在 1-300 秒之间");
        programs.put(programId, new Program(programId, file, sha, abi, entry, args, timeout));
      }
    }

    return new ModuleCard(
        id,
        author,
        version,
        recordedSource,
        digestOf(raw),
        name,
        emoji,
        subtitle,
        accent,
        image,
        signature,
        actions,
        bind,
        bindLabels,
        fields,
        provides,
        view,
        overlay,
        theme,
        nav,
        web,
        skin,
        app,
        pages,
        lifecycle,
        programs);
  }

  private static String text(JSONObject json, String key, int min, int max) {
    String value = json.optString(key, "").trim();
    if (value.length() < min) throw new IllegalArgumentException(key + " 不能为空");
    if (value.length() > max) throw new IllegalArgumentException(key + " 太长");
    return value;
  }

  /** 给用户和容器里的模型看的模板。模型拿到这段就能直接产出可用卡片。 */
  public static final String TEMPLATE =
      """
      {
        "schema": 1,
        "author": "local",
        "version": "0.1.0",
        "id": "demo.pet",
        "name": "小灰",
        "emoji": "🐱",
        "subtitle": "点一下我",
        "accent": "#7C9CFF",
        "image": "avatar.png",
        "bind": { "line2": "现在|clock.time" },
        "fields": [
          { "key": "who", "label": "称呼", "type": "text", "placeholder": "怎么称呼你" }
        ],
        "actions": [
          {
            "label": "打招呼",
            "type": "script",
            "value": "var who = dsha.field('who') || '你'; dsha.slot('line1', '喵~ 你好 ' + who); return '它冲你眨了眨眼';"
          },
          {
            "label": "今日状态",
            "type": "script",
            "value": "var runs = dsha.get('dsh.runs'); dsha.slot('line1', '今日启动 ' + runs + ' 次'); return '启动 ' + runs + ' 次，端口 ' + dsha.get('dsh.port');"
          },
          {
            "label": "一键体检",
            "type": "sequence",
            "value": "toast:开始体检\\nscript:dsha.slot('line1','体检中…')\\nexec:uname -m && df -h / | tail -1\\ntoast:体检完成"
          }
        ]
      }
      """;

  /** 动作与脚本能力的人话说明，随模板一起展示。 */
  public static final String TEMPLATE_HELP =
      """
      type 只能取：toast / copy / url / settings / modules / terminal / exec / sequence / script / page。
      view 节点可用：text / image / row / column / card / space / button / link / badge / segmented / progress / web。
      原生控件换肤：provides 里的 ui.skin.home.progress 可改主页原有状态条。
      脚本沙箱里只有 dsha 对象：get / field / slot / toast / copy / open / exec / log。
      脚本不能联网、不能读文件；exec 要先得到这张卡片的授权。
      """;

  /**
   * 写进容器的技能说明：模型读到它就知道该怎么产出模块，不需要用户解释格式。
   */
  public static final String SKILL =
      """
      # DSHA 主页模块

      > **当前状态（宿主版本说明）**：本页描述的 `dsha.call` / `dsha.services` 能力总线、
      > `schedule.set` 定时任务与 `~/.dsha-launch` 启动器属于模块平台的后续阶段，当前宿主
      > **尚未接线**——调用只会得到「尚未开放」。已经能用的是：声明式卡片、脚本沙箱
      > （`dsha.value/slot/toast/copy/open/log`）、以及逐卡授权后的 `dsha.exec` 与受管程序。

      用户想给 DSHA 主页加一个自己的功能时，你只要在容器里写一个 JSON 文件，宿主会自动收进来。
      这是真能用的东西：卡片会出现在主页，能拖动、改大小、收起，按钮能跑脚本和容器命令。

      ## 落盘位置

      把文件写到 `~/.dsha-modules/<任意名字>.json`（目录不存在就建）。
      立绘图片放同一目录，文件名写进 `image`。
      写完告诉用户"退出到 DSHA 主页就能看到"。宿主按 `id` 去重，同 id 再写一次就是更新。

      ## 身份与更新

      - `author`：作者 ID（最长 48 字），默认 `local`；作者自述不代表已验证身份。
      - `version`：SemVer，默认 `0.1.0`。相同 id 的新版本会替换卡片，但宿主记录摘要并保留旧版副本。
      - 宿主计算 SHA-256；JSON 自带摘要只能用于一致性检查，不代表作者签名。

      ## 格式

      ```json
      """
          + TEMPLATE
          + """
      ```

      ## 字段

      - `schema`：固定 1
      - `author`：作者 ID，默认 `local`；此值为自述，不是身份认证
      - `version`：SemVer，默认 `0.1.0`
      - `id`：小写字母数字和 `._-`，1-48 字符，建议 `作者.模块名`
      - `name`：卡片标题，最多 24 字
      - `emoji`：没有立绘时显示的图标字符
      - `subtitle`：一行说明，最多 64 字
      - `accent`：主题色 `#RRGGBB`
      - `image`：立绘文件名（png/jpg/webp），和 JSON 同目录；最多 4MB
      - `actions`：最多 4 个按钮 `{label, type, value}`，label 最多 12 字
      - `bind`：槽位绑定真实数据（见下）
      - `fields`：输入控件（见下）

      ## 按钮动作

      | type | 作用 | value |
      |---|---|---|
      | `toast` | 弹一句提示 | 文字 |
      | `copy` | 复制到剪贴板 | 文字 |
      | `url` | 打开链接 | http(s) |
      | `settings` / `modules` / `terminal` | 跳到对应页面 | 空 |
      | `exec` | 在容器里跑命令 | shell 命令 |
      | `script` | 跑一段 JS | 脚本源码（≤4000 字） |
      | `sequence` | 一个按钮跑一串 | 每行 `type:参数` |

      ## 动态数据：bind

      让副标题或下面两行显示真实状态，而不是写死的文字：

      ```json
      "bind": { "subtitle": "dsh.status", "line1": "今日启动|dsh.runs", "line2": "内存|system.load" }
      ```

      竖线前是显示标签（可省），竖线后是数据源。可用的数据源：
      `dsh.status`（运行/未运行）、`dsh.port`、`dsh.runs`（今日启动次数）、
      `clock.time`、`date.today`、`modules.count`、`storage.free`、
      `system.load`（内存占用）、`system.memory`。槽位只有 `subtitle` / `line1` / `line2`。

      ## 输入：fields

      ```json
      "fields": [{"key":"who","label":"称呼","type":"text","placeholder":"怎么称呼你"}]
      ```

      `type` 只有 `text` 和 `toggle`；`key` 只能小写字母数字下划线；最多 3 个。
      普通动作里用 `{{who}}` 取值，脚本里用 `dsha.field('who')`。

      ## 脚本：script

      脚本在隔离的 JS 环境里跑（没有网络、没有文件、拿不到 Java），只有一个 `dsha` 对象：

      | 调用 | 作用 |
      |---|---|
      | `dsha.get('dsh.runs')` | 读上面那些真实数据 |
      | `dsha.field('who')` | 读输入框的值 |
      | `dsha.slot('line1', '文字')` | 改自己卡片上那一行 |
      | `dsha.toast('文字')` | 弹提示 |
      | `dsha.copy('文字')` | 复制 |
      | `dsha.open('https://…')` | 打开链接 |
      | `dsha.exec('命令')` | 在容器里跑命令（需授权） |
      | `dsha.log('调试')` | 输出到结果里 |

      `return` 的字符串会显示在结果弹窗里；抛异常会显示错误原文。
      脚本最长跑 10 秒（ModuleScript.TIMEOUT_MS），死循环会被销毁重来。

      ## 编一串动作：sequence

      ```json
      {"label":"一键开工","type":"sequence","value":"exec:cd ~/project && git pull\\ntoast:代码已更新"}
      ```

      每行一个步骤，`type:参数`。步骤里可以有 exec 和 script，前一步失败就停。

      ## 扩展点：provides（不限于宿主认识的）

      宿主认识 `ui.overlay`（悬浮层）、`ui.theme`（主题背景 + 控件 token）、`home.card`（主页卡片）、
      `ui.nav`（底栏外观）、`ui.app`（接管主页/设置/插件内容区）、`module.web`（自带 HTML/CSS/JS）、
      `ui.skin`（改宿主原生控件）、`module.lifecycle`（生命周期/前台 tick）、`module.program`（受管程序）。
      写别的名字不会报错，会保留并在模块中心标「未支持」。

      ```json
      "provides": {
        "ui.overlay": { "image": "pet.png", "size": 96, "say": "script:return dsha.get('dsh.status')" },
        "ui.theme": { "image": "bg.png", "opacity": 0.55, "scrim": "#0E1116" }
      }
      ```

      - `ui.overlay`：立绘会在**整个 App 上**跑（设置、终端都在），点它会说话。
        `say` 可以是固定文字，也可以是 `script:` 开头的脚本；`size` 是 40-160 dp。
      - `ui.theme`：把整个 App 的背景换成这张图，`opacity` 0.15-0.95，`scrim` 是压在图上的底色。

      ### 主题控件 token：ui.theme.tokens

      ```json
      "ui.theme": {
        "image": "bg.png", "opacity": 0.5, "scrim": "#101418",
        "tokens": {
          "accent": "#C7A0FF", "surface": "#1B2230", "text": "#F5F7FB",
          "textSecondary": "#AAB6C8", "buttonBg": "#3A2D55", "buttonFg": "#FFFFFF",
          "buttonRadius": 16, "radius": 20, "navBg": "#141A24", "navIcon": "#C7A0FF"
        }
      }
      ```

      token 只收颜色、圆角（0-32 dp）、字号倍率（0.8-1.3）；不认识的键安全回退。
      白色卡片/按钮里的文字不会被强行提亮，避免变成白底白字。

      ### 底栏外观：ui.nav

      ```json
      "ui.nav": {
        "blur": true,
        "items": {
          "launch": { "label": "首页", "image": "nav-home.png" },
          "settings": { "label": "偏好" }
        }
      }
      ```

      只能改已有五个目的地（launch/module/plugins/settings/terminal）的 label/图标，不能替换宿主路由；
      label 最多 8 字，图标只收 png/jpg/webp 文件名。
      `blur: true` 由模块自己开启底栏实时磨砂；宿主不再内置这个开关，默认保持原版（不磨砂）。

      ### 接管主页：ui.app

      启用的模块可以把自己的 `view` 画到主页内容区，做成完全自定义的主页/商店/面板：

      ```json
      "ui.app": {
        "title": "我的主页", "subtitle": "模块接管",
        "background": "#0E1116", "exit": "退出模块主页",
        "view": [ {"type":"progress","variant":"waveCurve","value":72}, {"type":"link","text":"管理 ›","action":"modules"} ]
      }
      ```

      `view` 节点最多 60 个；`background` 只收 `#RRGGBB` / `#AARRGGBB`。宿主会按底色自动选深/浅标题色。
      `targets` 可选 `home` / `settings` / `plugins`（默认 `home`）；`module` 与 `terminal` 永远不开放。
      用户退出某个页面后按「模块 + 摘要 + 页面」记住选择，换版本会重新出现。

      ### 原生控件换肤：ui.skin

      不是另画一个控件，而是改主页**原有的**启动状态条（图二绿色进度线）：

      ```json
      "ui.skin": {
        "home.progress": {
          "height": 6, "radius": 3,
          "idle": "#35C759", "running": "#7C9CFF",
          "success": "#9CFF6E", "error": "#FF5A5F",
          "band": 0.5, "sweep": 1200
        }
      }
      ```

      `height` 1-16dp，`radius` 0-8dp，`band` 0.05-1，`sweep` 300-10000ms；颜色可空，空则用宿主默认。
      只有启用的模块会生效，模块中心可随时关闭它。

      ### 卡片皮肤：ui.skin.home.cards（改主页原生卡片的样式）

      主页上每一张原生卡片（三格状态、启动操作、快捷入口、日志框，以及用户模块卡）都能改
      底色、背景图、圆角、文字和图标颜色 —— 不用盖网页，直接改宿主原控件：

      ```json
      "ui.skin": {
        "home.cards": {
          "*": { "bg": "#141A24", "text": "#F5F7FB", "sub": "#8A97A8", "iconTint": "#C7A0FF", "radius": 16 },
          "metric-environment": { "image": "card-bg.png", "opacity": 0.6 },
          "metric-port": { "bg": "#1A2430" }
        }
      }
      ```

      - 键是卡片 id：`metric-environment` / `metric-workbench` / `metric-port` / `actions` /
        `shortcuts-title` / `shortcuts` / `logs`，用户模块卡是 `user:<模块id>`；`"*"` 是全部卡的默认。
      - `bg`/`text`/`sub`/`iconTint` 都是 `#RRGGBB` / `#AARRGGBB`；`radius` 0-32 dp；最多 16 条。
      - `image` 是模块目录里的图片文件名（png/jpg/webp），铺满卡片，`opacity` 0-1 控制深浅；
        背景图会随模块一起搬进宿主私有目录，不用额外登记。
      - 文字只改「恰好是主题默认色」的那部分，白底卡片里的自定义配色不会被强行覆盖；
        图标只改主题色 tint。所有键都可选，至少写一项。

      ### 模块网页：module.web（不受节点表限制）

      节点表不够用时，模块可以自带 HTML/CSS/JS，用 Canvas/SVG/CSS 做任意界面：

      ```json
      "provides": {
        "module.web": { "entry": "index.html", "files": ["index.html", "style.css", "app.js"] }
      },
      "view": [ { "type": "web", "src": "index.html", "fill": true } ]
      ```

      文件只收扁平文件名（html/css/js/json/图片/字体），单文件上限 8MB，最多 32 个。
      页面运行在沙箱 WebView：不能联网、不能读宿主文件；只能通过 `dsha` 调宿主能力：

      | 网页调用 | 作用 |
      |---|---|
      | `dsha.value('dsh.status')` | 读真实数据（与 bind 数据源相同） |
      | `dsha.call('data.get',{source:'dsh.runs'})` | 能力总线，权限与脚本一致 |
      | `dsha.toast(text)` / `dsha.copy(text)` / `dsha.open(url)` | 宿主交互 |
      | `dsha.page('detail')` | 打开本模块声明过的二级页面 |
      | `dsha.services()` | 当前能力名列表 |

      页面之间用普通相对链接即可（如 `detail.html`）；`web` 节点可放在卡片、二级页或 `ui.app` 里，
      `src` 必须在 `module.web.files` 中声明。

      ### 二级页面：pages + page 动作

      ```json
      "pages": [
        { "id": "detail", "title": "详情", "view": [ {"type":"text","text":"hi"} ] }
      ],
      "actions": [ {"label":"详情","type":"page","value":"detail"} ]
      ```

      最多 8 个页面，每个页面最多 40 个 view 节点；`page` 动作/`sequence` 步骤只能打开本卡片声明过的页面。
      页面动作（toast/copy/url/settings/terminal/page/exec/script/sequence）与卡片共用同一套宿主权限。

      ### 生命周期：module.lifecycle

      ```json
      "provides": {
        "module.lifecycle": {
          "onShow": "script:dsha.log('show')",
          "onHide": "script:dsha.log('hide')",
          "tick": { "every": 30, "script": "dsha.log('tick')" }
        }
      }
      ```

      只在前台卡片可见时执行；tick 每 10-3600 秒一次，离开页面立即停。后台常驻请用 `schedule.set`。

      ### 模块间通信：events / module.send

      ```js
      dsha.call('events.emit', { event: 'ping', payload: 'hi' })   // 广播给所有启用模块
      dsha.call('module.send', { to: 'other.id', event: 'ping', payload: 'hi' })
      dsha.call('events.poll', {})                                  // 取走并清空自己的收件箱
      ```

      宿主中转、持久化、每卡最多 32 条、单条 payload 最多 512 字；模块不能直接调用另一个模块的脚本。

      ### 受管程序：module.program

      ```json
      "provides": {
        "module.program": {
          "programs": [{
            "id": "hello", "file": "hello", "sha256": "<64 hex>",
            "abi": "x86_64", "entry": "hello", "args": ["demo"], "timeout": 30
          }]
        }
      }
      ```

      程序只随模块包进入（图片通道不放宽），导入与每次运行前都重算 SHA-256、校验 ELF ABI；
      运行走容器固定 guest 路径和固定 argv，参数会转义，模块字符串不拼进 shell。
      程序授权与命令授权分开：模块中心单独弹窗、单独撤销，移除模块时一起清。

      ### 签名：signature + card.sig

      ```json
      "signature": "card.sig"
      ```

      `card.sig` 内容：
      ```json
      {"schema":1,"algorithm":"ed25519","keyId":"<指纹>","publicKey":"<64 hex>","signature":"<128 hex>"}
      ```

      签名覆盖 card.json 与全部素材。只有公钥在信任库里时模块才显示「已验证」；
      首次导入可在模块中心核对指纹后显式信任。作者自述和 SHA-256 摘要都不是签名。

      ## 自绘界面：view

      不写 `view` 就用默认卡片版式。写了就完全按你的节点渲染：

      ```json
      "view": [
        { "type": "row", "gap": 10, "children": [
          { "type": "image", "src": "pet.png", "h": 56, "weight": 1 },
          { "type": "text", "text": "小灰", "size": 16, "bold": true, "weight": 3 }
        ]},
        { "type": "text", "bind": "状态|dsh.status", "size": 12 },
        { "type": "text", "bind": "clock.time", "size": 12, "color": "#8AB4F8" },
        { "type": "button", "label": "刷新", "action": "script:return dsha.get('dsh.runs')" }
      ]
      ```

      节点类型：`text`（text 或 bind，可带 size/bold/color/align/gap）、`image`（src/h/round）、
      `row`（children/gap）、`column`/`stack`（children/padH/padV）、`card`（title/children/bg/radius/pad）、
      `space`（h）、`button`（label/action，action 用 `type:参数`）、`link`（text/action）、
      `badge`（text/bg/fg/pad）、`segmented`（options[{label,selected,action}]/gap）、`progress`（见下）、
      `web`（src/h/fill，自带 HTML/CSS/JS）。最多 40 个节点、嵌套最多 4 层。

      ### 动效进度条：progress

      ```json
      {"type":"progress","variant":"waveCurve","value":72,"gradient":"#35C759","height":4}
      {"type":"progress","variant":"roaming","indeterminate":true,"height":6}
      {"type":"progress","variant":"numeric","value":46,"showValue":true,"height":8}
      ```

      变种：`mixed`（混合）、`shimmer`（流光合拢）、`segmented`（分段刻度）、`roaming`（光点巡游）、
      `numeric`（数值胶囊）、`breath`（呼吸脉冲）、`wave`（波浪）、`waveCurve`（曲线变速）、
      `waveSpring`（弹性追赶）、`line`（细线贴边）。未知变种回退 `line`。
      可选字段：`indeterminate`、`value`（0-1 或 0-100）、`height`（1-24 dp）、`radius`、
      `track`/`fill`/`gradient`/`glow` 颜色、`glow`（bool）、`showValue`、`segments`（2-20）、
      `duration`（200-5000 ms）、`state`（`success`/`error`）。所有值都有上限，非法值安全回退。

      ## 能力总线：dsha.call（后续阶段，当前宿主尚未接线）

      > 本节与下面的 `schedule.set`、`~/.dsha-launch` 属于模块平台后续阶段；
      > 现在调用只会得到「尚未开放」。当前可用能力见上一节与「当前状态」注记。

      比动作更开放的一层：直接调宿主服务，宿主加能力不用改契约。

      ```js
      dsha.call('data.get', { source: 'dsh.runs' })      // 读真实数据
      dsha.call('nav.set', { order: ['launch','module','plugins','settings','terminal'] })
      dsha.call('launch.set', { action: 'tool:my-tool' }) // 启动按钮改去容器里登记的程序
      dsha.call('exec.run', { command: 'uname -m' })      // 需要授权
      dsha.services()                                     // 列出当前所有能力
      ```

      需要授权的能力（`nav.set` / `launch.set` / `exec.run` / `schedule.set` /
      `schedule.clear` / `dsh.start` / `dsh.stop`）第一次调用前要先让用户确认。

      ## 容器程序登记：~/.dsha-launch/（启动按钮只启动容器里的东西）

      启动按钮不负责壳内导航（终端、设置、模块中心都在底栏），它只有两类目标：
      容器工作台（`web`，容器里的 DSH Web），或 `~/.dsha-launch/<id>.json` 登记的程序。
      用户在容器里装了 ARM/x86 工具（比如一个映射端口的服务）时，往 `~/.dsha-launch/<id>.json`
      写一份登记，宿主会把它加进「模块中心 → 布局与外观 → 启动入口」的选择列表；
      用户选中后，主页「启动」按钮就变成启动这个程序：

      ```json
      {"schema":1,"id":"my-tool","name":"我的工具",
       "command":"cd /opt/mytool && ./tool --port 8080","port":8080,"note":"文件同步服务"}
      ```

      - `id` 小写字母数字和 `._-`（≤48 位）；`name` ≤24 字；`command` 是容器内执行的命令（≤500 字）。
      - `port` 可选（1024-65535）：宿主启动后会探测 127.0.0.1:<port> 是否开始监听并告诉用户。
      - 程序命令作为容器会话的主进程运行、由宿主持有：宿主进程活着程序就活着（和 DSH web
        同一生命周期），会话里写 `nohup … &` 没用（proot 退出会连带杀掉后台进程）。
        服务器类程序直接写前台命令即可，例如 `cd /opt/mytool && ./tool --port 8080`。
        重复按「启动」会先停掉旧实例再启动新的；输出固定落 `/tmp/dsha-tool-<id>.log`。
      - 壳内也能新建/编辑/删除：模块中心 → 启动入口 →「新建容器程序」或长按程序行。
        壳内写回的是同一份 `~/.dsha-launch/<id>.json`，和你手写文件完全等价；
        删除登记文件、下次回主页，这个程序就从选择列表消失。
      - 这是用户显式登记的执行入口，不走模块授权弹窗；登记前想清楚命令会做什么。

      ## 定时任务：schedule.set / schedule.list / schedule.clear
      卡片可以登记「每天几点做什么」。登记与到点执行都走这张卡片的运行授权：
      没授权时 `schedule.set` 直接被拒；登记后用户撤销授权，到点只发一条「被跳过」的通知，不会执行。

      ```js
      dsha.call('schedule.set', { hour: 7, minute: 30, kind: 'exec',
                                  value: 'cd ~/project && git pull', label: '晨检' })
      dsha.call('schedule.list', {})                            // 列出本卡片的任务
      dsha.call('schedule.clear', {})                           // 清空本卡片的全部任务
      dsha.call('schedule.clear', { id: 'pet.demo#1a2b3c4d' })  // 只删这一条
      dsha.call('dsh.stop', {})                                 // 请求停止容器（结果以主页为准）
      ```

      - `hour` 0-23、`minute` 0-59；`kind` 只能是 `exec`（跑容器命令，`value` 必填、≤500 字）、
        `dsh.start`（启动容器）或 `dsh.stop`（停止容器），后两种不带 `value`；`label` 可省、最长 24 字。
      - 每张卡片最多 8 条；同一「时间 + 动作」再 `set` 一次是改这条任务，不是新增。
      - 闹钟是非精确的（宿主不申请精确闹钟权限）：系统省电（Doze）可能把触发推迟几分钟，
        别把它当秒级准点用；手机重启后宿主会自动重新登记。
      - 到点结果用系统通知告诉用户：`exec` 附命令输出摘要，容器没就绪会如实报失败；
        `dsh.start` / `dsh.stop` 也可以直接调用，同样只提交启动/停止请求，结果以主页为准。
      - 模块被移除时，它名下的定时任务会被宿主一并撤销；用户在「模块中心」能看到并删除每条任务。

      ## 边界（如实告诉用户，不要假装能做到）

      - 脚本不能联网、不能读写文件、不能加载第三方库；要联网只能 `open` 一个链接让用户自己点。
      - 执行命令必须用户授权，第一次点会弹窗；用户可以随时在模块中心撤销。
      - 受管程序只能随模块包导入并过 SHA-256/ELF ABI 校验，不能从网络下载；程序授权与命令授权分开。
      - 执行授权挡的是「未授权的静默执行」，不是安全沙箱；容器命令该跑什么，写卡前想清楚。
      - 卡片和 `pages` 都是一屏 view；复杂交互拆成二级页面 + 按钮，不能写任意 Activity 跳转。
      - 签名只有公钥受信时才显示「已验证」；作者名和 SHA-256 摘要本身不是身份认证。
      - 做不到就直说，不要编造 type。
      """;

  /**
   * 原始 card.json 的 SHA-256（小写十六进制）。
   *
   * <p>只用于「同一份内容」的一致性识别（更新/回滚对得上号）。作者身份验证走
   * {@link ModuleSignature} + {@link ModuleTrustStore}（ed25519 验签 + 信任库），
   * 摘要本身无法证明作者身份，见 {@code ModuleTrust}。
   */
  static String digestOf(String value) {
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte item : digest) {
        // 显式掩码转无符号，再补两位；不依赖 %02x 对负字节的格式化细节。
        if (item < 0) hex.append(Integer.toHexString(256 + item));
        else {
          if (item < 16) hex.append('0');
          hex.append(Integer.toHexString(item));
        }
      }
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  /** 摘要的短展示形（前 12 位 + …）；用于模块中心可辨识展示，不用于校验。 */
  public String digestShort() {
    if (digest == null || digest.length() <= 12) return digest == null ? "" : digest;
    return digest.substring(0, 12) + "…";
  }

  public String identity() {
    return author + "/" + id + "@" + version;
  }

  /** 生命周期脚本字段；允许 {@code script:} 前缀，长度沿用脚本上限。 */
  private static String scriptField(JSONObject json, String key) {
    String raw = json.optString(key, "").trim();
    if (raw.startsWith("script:")) raw = raw.substring("script:".length()).trim();
    if (raw.isEmpty()) return "";
    if (raw.length() > MAX_SCRIPT)
      throw new IllegalArgumentException(
          "module.lifecycle." + key + " 太长（上限 " + MAX_SCRIPT + " 字符）");
    return raw;
  }

  /** 图片字段统一校验：只能是 png/jpg/jpeg/webp 的文件名，不能带路径。 */
  private static void requireImageName(String name, String field) {
    if (!name.matches("(?i)[A-Za-z0-9._-]{1,60}\\.(png|jpg|jpeg|webp)"))
      throw new IllegalArgumentException(field + " 只能是 png/jpg/webp 文件名，不能带路径");
  }

  /** 签名文件同样只收文件名，不能带路径。 */
  private static void requireSignatureName(String name, String field) {
    if (!name.matches("(?i)[A-Za-z0-9._-]{1,60}\\.sig"))
      throw new IllegalArgumentException(field + " 只能是 .sig 文件名，不能带路径");
  }

  /** 受管程序只收文件名（不含路径），扩展名不限。 */
  private static void requireProgramName(String name, String field) {
    if (!name.matches("[A-Za-z0-9._-]{1,64}"))
      throw new IllegalArgumentException(field + " 只能是 1-64 个安全字符的文件名，不能带路径");
  }

  /** 换肤颜色只收 #RRGGBB / #AARRGGBB；空表示沿用宿主默认。 */
  private static String skinColor(JSONObject json, String key, String prefix) {
    String value = json.optString(key, "").trim();
    if (!value.isEmpty() && !value.matches("#[0-9a-fA-F]{6,8}"))
      throw new IllegalArgumentException(prefix + "." + key + " 必须是 #RRGGBB 或 #AARRGGBB");
    return value;
  }

  /** 模块网页资源只收扁平文件名，扩展名限网页/样式/脚本/图片/字体/JSON。 */
  private static void requireWebName(String name, String field) {
    if (!name.matches(
        "(?i)[A-Za-z0-9._-]{1,64}\\.(html|htm|css|js|mjs|json|png|jpg|jpeg|webp|svg|woff|woff2|ttf)"))
      throw new IllegalArgumentException(field + " 只能是 html/css/js/json/图片/字体文件名，不能带路径");
  }

  /** 这个素材是不是受管程序文件（导入时用更大的体积上限并校验 ELF）。 */
  public boolean isProgramAsset(String name) {
    return programForAsset(name) != null;
  }

  /** 这个素材是不是模块网页资源（HTML/CSS/JS 等，导入时用 8MB 上限）。 */
  public boolean isWebAsset(String name) {
    return web != null && web.files.contains(name);
  }

  /** 按素材文件名找程序声明；不是程序返回 null。 */
  public Program programForAsset(String name) {
    for (Program program : programs.values())
      if (program.file.equals(name) || program.entry.equals(name)) return program;
    return null;
  }

  /** 这个模块引用到的所有图片文件名（卡片立绘 / 悬浮层 / 主题背景），去重后返回。 */
  public java.util.List<String> assetNames() {
    java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
    if (!image.isEmpty()) names.add(image);
    if (!signature.isEmpty()) names.add(signature);
    // 任何扩展点配置里的 image 字段都算素材，不限于宿主认识的两种。
    for (JSONObject config : provides.values()) {
      String art = config.optString("image", "").trim();
      if (art.matches("(?i)[A-Za-z0-9._-]{1,60}\\.(png|jpg|jpeg|webp)")) names.add(art);
    }
    if (nav != null) {
      for (NavItem item : nav.items.values()) if (!item.image.isEmpty()) names.add(item.image);
    }
    // 卡片皮肤的背景图也是素材声明：随模块一起搬进私有目录。
    if (skin != null) {
      for (Skin.CardTokens tokens : skin.homeCards.values())
        if (!tokens.image.isEmpty()) names.add(tokens.image);
    }
    if (web != null) names.addAll(web.files);
    if (view != null) collectViewImages(view, names);
    for (Page page : pages.values()) if (page.view != null) collectViewImages(page.view, names);
    for (Program program : programs.values()) names.add(program.file);
    return new ArrayList<>(names);
  }

  /**
   * 素材名的<b>路径安全</b>判断：不含分隔符、不含 {@code ..}、非绝对、非隐藏、长度受限、不是保留名。
   *
   * <p>扩展名限制属于各类型自己的事（{@link #isImageName}、{@code requireWebName}、
   * {@code requireProgramName}）—— 统一在这里限制会把文档支持的 program（无扩展名 "file":"hello"）
   * 和 web（svg/mjs/woff2）一起拒掉，那是一次真实回归。
   *
   * <p>导入、读取、删除三条路径都必须走这个判断：卡片 JSON 来自容器，一个 {@code ../}
   * 就能让宿主把文件写进别的模块目录（甚至宿主私有目录）。
   */
  static boolean isImageName(String name) {
    return isAssetFileName(name)
        && name.trim()
            .toLowerCase(java.util.Locale.ROOT)
            .matches("(?i)[a-z0-9][a-z0-9._-]{0,60}\\.(png|jpg|jpeg|webp)");
  }

  static boolean isAssetFileName(String name) {
    if (name == null) return false;
    String value = name.trim();
    if (value.isEmpty() || value.length() > 64) return false;
    if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) return false;
    if (value.contains("..")) return false;
    if (value.startsWith(".")) return false;
    // 宿主自己的文件不能被声明成素材：导入会覆盖它们，移除会顺手删掉。
    // card.sig 除外 —— 它是卡片目录里的成员，需要跟着素材一起写。
    return !value.equalsIgnoreCase("card.json")
        && !value.equalsIgnoreCase("module.json")
        && !value.equalsIgnoreCase("HOW-TO.md")
        && !value.equalsIgnoreCase("SKILL.md");
  }

  private static void collectViewImages(JSONArray nodes, java.util.Set<String> into) {
    for (int i = 0; i < nodes.length(); i++) {
      JSONObject node = nodes.optJSONObject(i);
      if (node == null) continue;
      String src = node.optString("src", "").trim();
      if (src.matches("(?i)[A-Za-z0-9._-]{1,60}\\.(png|jpg|jpeg|webp)")) into.add(src);
      if ("web".equals(node.optString("type", "")) && src.matches("[A-Za-z0-9._-]{1,64}"))
        into.add(src);
      // button 可以带 image（图标）；同样只收安全图片文件名。
      String icon = node.optString("image", "").trim();
      if (icon.matches("(?i)[A-Za-z0-9._-]{1,60}\\.(png|jpg|jpeg|webp)")) into.add(icon);
      JSONArray children = node.optJSONArray("children");
      if (children != null) collectViewImages(children, into);
    }
  }

  /** 主页布局里用的键名；带前缀避免和内置区块 id 撞车。 */
  public String layoutId() {
    return "user:" + id;
  }

  public static String layoutIdOf(String cardId) {
    return "user:" + cardId;
  }

  public static boolean isUserModule(String layoutId) {
    return layoutId != null && layoutId.startsWith("user:");
  }
}
