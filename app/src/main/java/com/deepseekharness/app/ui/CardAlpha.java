package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.core.content.ContextCompat;

import com.deepseekharness.app.R;

/**
 * 卡片不透明度：把「外观背景 → 不透明度」应用到**每一个**内容卡片上（全 App，不只是二级页）。
 *
 * <h3>为什么不是只认 {@code bg_card}</h3>
 * 214 把卡片皮肤合并成同外观后，工程里同时存在三个兄弟资源：
 * <ul>
 *   <li>{@code bg_card} —— 设置页 / 二级页 / 插件页表头 / 列表项；
 *   <li>{@code bg_card_double} —— **启动页主卡**（DSH 未运行 + 启动/重启/停止/横屏 DSH）；
 *   <li>{@code bg_card_plain} —— 启动页的「实际 Web 端口 / READY」小卡。
 * </ul>
 * 三者是三个不同的资源 id，ConstantState 也不同；237 只认 {@code bg_card}，
 * 所以真机上「设置页成功、启动页和插件页不动」。238 改成按资源集合识别。
 *
 * <h3>为什么还要 watch</h3>
 * 插件页的表头（{@code plugin_list_header}）在 {@code onViewCreated} 里 inflate、
 * 列表项（{@code item_plugin}）在 {@code onBindViewHolder} 里才 inflate —— 前者可能晚于
 * 「Activity resume 时扫一遍」，后者则随时可能在滚动中产生。所以除了主动扫一遍，
 * 还给每个 ViewGroup 挂「子 View 加入即补刷」的监听（{@link #watch(View)}）。
 *
 * <h3>识别 / 记账 / 恢复</h3>
 * <ol>
 *   <li>识别：{@code View#getBackground()} 的 {@code ConstantState} 与当前主题下
 *       任一卡片资源的 ConstantState 是同一个对象。同一进程、同一 Resources、同一套配置下，
 *       资源缓存返回的正是同一个 ConstantState —— 不改布局、不依赖控件 id；
 *   <li>记账：认出来之后打 {@code tag_card_alpha}，以后背景被 {@code mutate()} 过
 *       （ConstantState 分叉）也照样认得；再用 {@code tag_card_alpha_value} 记上次的值，
 *       值没变就不重复 setAlpha；
 *   <li>恢复：不透明度拖回 100% 时会真正 {@code setAlpha(255)}（236 的旧实现在
 *       {@code alpha >= 1} 时直接 return，靠「新建卡片天然不透明」蒙混过去，拖回去就会卡在半透）。
 * </ol>
 *
 * <h3>不算卡片、故意不处理的</h3>
 * {@code bg_btn*}（按钮）、{@code bg_input}（搜索/输入框）、{@code bg_action_plain}（透明行动区）、
 * {@code bg_icon_soft}（图标底）、{@code bg_terminal} / {@code bg_ui2_console}（终端深色正文）、
 * {@code bg_device_card}（验证码输入框 / 错误提示块）、{@code bg_preview}（终端容器）。
 * 把这些也调透明会让文字与控件失去承载面，不是用户要的「卡片」。
 *
 * <p>238 追加：顶栏 {@code bg_appbar_glass} 与底栏 {@code bg_nav_double} 也算「白面」，
 * 必须跟同一档不透明度，否则主界面上下两条永远不透明（用户截图点名）。
 *
 * <p><b>240：顶栏/底栏改走「按 id 接管」，不再依赖上面那套 ConstantState 判据。</b>
 * 真机反馈「顶栏跟、底栏不跟」—— 底栏是 {@code BottomNavigationView}，Material 的
 * {@code NavigationBarView} 构造函数会把 layer-list 背景换掉（详见 {@link #CHROME_ID}），
 * 资源启发式必然认不出。所以这两条 bar 由 {@link #paintChrome} 显式装背景、逐层下发 alpha。
 *
 * <p>调用点：{@link ModernAndroidUi}（每个 Activity 建好/回到前台）、
 * {@code MainActivity} 的 Fragment 回调（切 tab）、{@link AppearanceActivity}（拖动实时预览）、
 * {@link CardPage#card()}、{@code CardSheet}、{@code DshaSelectView}、
 * {@code VirtualScreenForeground}（悬浮面板不在 Activity 树里）。
 */
public final class CardAlpha {
  private CardAlpha() {}

  /**
   * 内容卡片的资源集合。以后加新皮肤时**只改这里**。
   *
   * <p>刻意不含 {@code bg_device_card}（输入框/错误块）与 {@code bg_preview}（终端容器）：
   * 调透明会破坏可读性。顶栏/底栏是「大面白板」，所以放进来了。
   */
  private static final int[] CARD_RES = {
    R.drawable.bg_card,
    R.drawable.bg_card_double,
    R.drawable.bg_card_plain,
    // 238：顶栏与底栏也是「盖在壁纸上的白面」，必须跟同一个不透明度走，
    // 否则主界面上下两条永远不透明，看起来像漏了（用户截图点名）。
    R.drawable.bg_appbar_glass,
    R.drawable.bg_nav_double,
    // 245：rc.3 的 v5 外壳（shell_* token）也要跟同一个不透明度走，
    // 否则新 UI 的卡片是实心白，壁纸只能在页边露一条，看着像没生效。
    R.drawable.bg_shell_card,
    // H1 的 FILLED 层级用的是 shell_raised 底（蓝灰），之前漏在集合外：既不透、颜色也和
    // 其它卡片不是一套。补进来让三级皮肤走同一个不透明度与同一套卡面色。
    R.drawable.bg_shell_card_filled,
    // 设置分组与插件卡片原来各写一种实心底（shell_raised 蓝灰 / shell_surface 死白），
    // 既不是玻璃也不跟随不透明度 —— 用户说的「设置列表一直没变」就是它们。
    R.drawable.bg_settings_group,
    R.drawable.bg_plugin_card_selectable,
    R.drawable.bg_shell_card_filled,
    R.drawable.bg_launch_log_box,
    R.drawable.bg_data_stat,
    R.drawable.bg_plugin_details,
    R.drawable.bg_plugin_empty,
    R.drawable.bg_plugin_note,
    R.drawable.bg_plugin_strip,
    R.drawable.bg_update_inset,
    R.drawable.bg_web_crash_card,
    R.drawable.bg_web_crash_tech,
    R.drawable.bg_island_notice,
    R.drawable.bg_island_strip,
    R.drawable.bg_shell_launch_card,
    R.drawable.bg_shell_status
  };

  /**
   * App chrome（顶栏 / 底栏）：**按 id 显式接管**，不走资源启发式。
   *
   * <p><b>240 的取证（真机症状「顶栏跟、底栏不跟」）</b>：根因不是「Material 把 layer-list
   * 换成自己的形状 drawable」——那条路径只在 {@code bg == null} 或背景自带 ColorStateList 时走，
   * {@code bg_nav_double} 两头都不占。真正的原因是同一段构造代码的下一句：
   * {@code com.google.android.material.navigation.NavigationBarView} 构造函数末尾**无条件**执行
   * <pre>DrawableCompat.setTintList(getBackground().mutate(), backgroundTint);</pre>
   * 而 {@code LayerDrawable.mutate()} 会 {@code mLayerState = new LayerState(mLayerState)}
   * —— ConstantState 从此与 Resources 缓存里那份**不是同一个对象**。本类的
   * {@link #isCard} 判据正是「ConstantState 同一性」，于是底栏永远认不出来、一次
   * {@code setAlpha} 都没发过；顶栏是普通 {@code LinearLayout}，没人碰它的背景，所以正常。
   * （Material 3 的 {@code backgroundTint} 是 {@code @null}，所以底栏外观不变、只是认不出 ——
   * 正好对上用户「白底还在，但不变淡」的描述。）
   *
   * <p><b>对策</b>：这两条 chrome 由本类「按 id 装背景 + 用 tag 记 Drawable 实例」，
   * 身份判断只比实例（不比 ConstantState），组件怎么 mutate 都逃不掉；
   * 装完再逐层下发 alpha（详见 {@link #setDeepAlpha}）。
   * 顺带把 Material 的 tint 拿掉：{@code bg_nav_double} 那 1px 顶线本来就该露出来。
   */
  private static final int[] CHROME_ID = {R.id.app_bar, R.id.bottom_nav};

  private static final int[] CHROME_RES = {
    // 245：底栏改成 rc.3 的悬浮胶囊（bg_shell_nav 已换成玻璃底），不再套旧的白底 layer-list。
    R.drawable.bg_appbar_glass, R.drawable.bg_shell_nav
  };

  /** chrome 自检落盘：{@code Download/DSHA/ui-probe/card-alpha.txt}，每次 apply 覆盖。 */
  private static final String PROBE_DIR = "/sdcard/Download/DSHA/ui-probe";
  private static final String PROBE_NAME = "card-alpha.txt";
  private static final ExecutorService PROBE_IO = Executors.newSingleThreadExecutor();

  /**
   * 卡片不透明度的存取。
   *
   * <p><b>故意不放在 {@code core/ConfigStore}</b>：那个文件属于受管启动器输入
   * （{@code runtime-descriptor.json} 的 {@code launcherInputs} 按字节校验），改一个字节
   * 就会换 {@code runtimeId}，让已经装好的环境被判成「不是当前候选」，触发 1.4 GB 全量
   * 重装 —— UI-only 的改动不该动运行时。这里用独立的偏好文件，与外观配置分开。
   */
  private static final String PREFS = "dsh-appearance";
  /**
   * 键名带 v2：旧键存过 0.27/0.5 这类历史默认值，沿用会让新默认永远看不到。
   * 本设置还没随任何版本发布，不存在用户数据迁移问题。
   */
  private static final String KEY_ALPHA = "card_alpha_v2";
  /**
   * 默认 68%：内置浅色壁纸下半段是淡蓝紫，50% 的白卡压不住它，整片看着发灰发青
   * （build354 真机实测）；68% 仍能透出壁纸渐层，但卡片读起来是白的。
   */
  private static final float DEFAULT_ALPHA = 0.85f;

  /** 1 = 完全不透明；默认 0.27，超出 0.2~1.0 一律回落默认。 */
  public static float cardAlpha(Context context) {
    if (context == null) return DEFAULT_ALPHA;
    float value =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_ALPHA, DEFAULT_ALPHA);
    return value < 0.2f || value > 1f ? DEFAULT_ALPHA : value;
  }

  public static void setCardAlpha(Context context, float value) {
    if (context == null) return;
    float clamped = value < 0.2f ? 0.2f : Math.min(value, 1f);
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_ALPHA, clamped).apply();
  }

  /** 按当前设置刷整棵树，并给每个 ViewGroup 挂「新子 View 补刷」监听。 */
  public static void apply(View root) {
    if (root == null) return;
    int value = Math.round(clamp(cardAlpha(root.getContext())) * 255f);
    walk(root, value, cardStates(root.getContext()));
    watch(root);
    probeChrome(root, value);
  }

  /** 按指定不透明度刷整棵树（外观页拖动实时预览用），同样会补挂监听。 */
  public static void apply(View root, float alpha) {
    if (root == null) return;
    int value = Math.round(clamp(alpha) * 255f);
    walk(root, value, cardStates(root.getContext()));
    watch(root);
  }

  /**
   * 给这棵树的每个 ViewGroup 挂「子 View 加入时补刷」的监听。
   *
   * <p>这是插件页/任何 RecyclerView 能跟上的关键：列表项是在 {@code onBindViewHolder}
   * 里才 inflate 并 addView 的，主动扫一遍时它可能还不存在。
   *
   * <p>用 {@code tag_card_watch} 去重，重复调用只是空遍历一次。
   * 工程里没有任何地方用过 {@code setOnHierarchyChangeListener}（238 已全库 grep 确认），
   * 所以不会覆盖别人的监听。
   */
  public static void watch(View root) {
    if (!(root instanceof ViewGroup)) return;
    ViewGroup group = (ViewGroup) root;
    if (Boolean.TRUE.equals(group.getTag(R.id.tag_card_watch))) return;
    group.setTag(R.id.tag_card_watch, Boolean.TRUE);
    group.setOnHierarchyChangeListener(
        new ViewGroup.OnHierarchyChangeListener() {
          @Override
          public void onChildViewAdded(View parent, View child) {
            // 新加进来的子树可能本身就是卡片（列表项），也可能里面还有卡片。
            apply(child);
          }

          @Override
          public void onChildViewRemoved(View parent, View child) {}
        });
    for (int i = 0; i < group.getChildCount(); i++) watch(group.getChildAt(i));
  }

  private static void walk(View view, int value, Drawable.ConstantState[] cards) {
    if (view == null) return;
    int chrome = chromeIndex(view.getId());
    // chrome 先按 id 接管：它可能已经被组件换过背景，资源启发式在这两条上不可靠。
    if (chrome >= 0) paintChrome(view, value, CHROME_RES[chrome]);
    boolean known = Boolean.TRUE.equals(view.getTag(R.id.tag_card_alpha));
    if (known || isCard(view, cards)) {
      if (!known) view.setTag(R.id.tag_card_alpha, Boolean.TRUE);
      paint(view, value);
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), value, cards);
    }
  }

  private static int chromeIndex(int id) {
    for (int i = 0; i < CHROME_ID.length; i++) if (CHROME_ID[i] == id) return i;
    return -1;
  }

  /**
   * 顶栏 / 底栏的刷法：先确认背景是「我们装的那一份」，再逐层下发 alpha。
   *
   * <p>「我们装的那一份」用 {@code tag_bar_surface} 直接记 Drawable 实例 —— 只比对象身份，
   * 不比 ConstantState，所以组件 mutate 过、或资源缓存给了新实例，都能立刻发现并重装。
   *
   * <p>100% 也照常 {@code setAlpha(255)}、不做特例：AOSP 里 {@code GradientDrawable} 与
   * {@code LayerDrawable} 的 alpha 都是**乘性**的（{@code modulateAlpha}：
   * {@code colorAlpha * mAlpha / 255}），255 因此是精确复原 —— 顶栏玻璃 solid 自带的
   * {@code #DB} 不会被抹掉，底栏白底与 1px 顶线也回到全不透明。
   */
  private static void paintChrome(View view, int value, int res) {
    Drawable background = view.getBackground();
    boolean owned = background != null && background == view.getTag(R.id.tag_bar_surface);
    if (!owned) {
      if (!installChrome(view, res)) return;
      background = view.getBackground();
      if (background == null) return;
      // 新装的一份：上一次的记账作废；即使当前就是 100% 也要刷一遍 ——
      // 资源缓存里的子层可能被上一次 setAlpha 改过，必须显式复位。
      view.setTag(R.id.tag_card_alpha_value, null);
    }
    Integer last = (Integer) view.getTag(R.id.tag_card_alpha_value);
    if (last != null && last == value) return;
    setDeepAlpha(background, value);
    view.setTag(R.id.tag_card_alpha_value, value);
  }

  /** 按 id 把 chrome 的背景换成资源原样，并记账（tag_bar_surface 记实例，tag_card_alpha_value 清空）。 */
  private static boolean installChrome(View view, int res) {
    try {
      view.setBackgroundResource(res);
    } catch (Throwable ignored) {
      return false;
    }
    Drawable background = view.getBackground();
    if (background == null) return false;
    view.setTag(R.id.tag_bar_surface, background);
    view.setTag(R.id.tag_card_alpha_value, null);
    return true;
  }

  /**
   * 逐层下发 alpha。
   *
   * <p>{@code LayerDrawable#setAlpha} 在各版本上是否向子层传播没有稳定保证（底栏白底与 1px
   * 顶线就是两层），所以这里自己遍历：每层先 {@code mutate()} 拿到私有副本再设，
   * 免得把 Resources 缓存的共享 ConstantState 一起改掉。
   */
  private static void setDeepAlpha(Drawable drawable, int value) {
    if (drawable == null) return;
    try {
      drawable.mutate().setAlpha(value);
    } catch (Throwable ignored) {
      return;
    }
    if (drawable instanceof LayerDrawable) {
      LayerDrawable layers = (LayerDrawable) drawable;
      try {
        for (int i = 0; i < layers.getNumberOfLayers(); i++) {
          setDeepAlpha(layers.getDrawable(i), value);
        }
      } catch (Throwable ignored) {
      }
    }
  }

  /**
   * 把顶栏 / 底栏此刻的真实状态写到 {@code Download/DSHA/ui-probe/card-alpha.txt}。
   *
   * <p>为什么留一段「诊断」在生产代码里：这两条 bar 的透明度是**只能看真机**的问题
   *（构建期无法证伪，239 就吃过一次「静态全对、真机没变」），所以每次回到页面都留一份
   * 现场：背景类名、是否仍是我们装的那份、当前 alpha、记账值。文件每次覆盖，量很小。
   */
  private static void probeChrome(View root, int value) {
    final View bar;
    final View nav;
    try {
      bar = root.findViewById(R.id.app_bar);
      nav = root.findViewById(R.id.bottom_nav);
    } catch (Throwable ignored) {
      return;
    }
    if (bar == null && nav == null) return;
    StringBuilder text = new StringBuilder();
    text.append("cardAlpha=").append(Math.round(value / 255f * 100f)).append("%\n");
    line(text, "app_bar", bar);
    line(text, "bottom_nav", nav);
    text.append("at=").append(System.currentTimeMillis()).append('\n');
    final String body = text.toString();
    PROBE_IO.execute(
        () -> {
          try {
            File dir = new File(PROBE_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            try (FileOutputStream out = new FileOutputStream(new File(dir, PROBE_NAME), false)) {
              out.write(body.getBytes(StandardCharsets.UTF_8));
            }
          } catch (Throwable ignored) {
          }
        });
  }

  private static void line(StringBuilder text, String label, View view) {
    text.append(label).append(':').append(' ');
    if (view == null) {
      text.append("不存在\n");
      return;
    }
    Drawable background = view.getBackground();
    boolean owned = background != null && background == view.getTag(R.id.tag_bar_surface);
    text.append("bg=")
        .append(background == null ? "null" : background.getClass().getSimpleName())
        .append(" owned=")
        .append(owned)
        .append(" alpha=")
        .append(background == null ? -1 : background.getAlpha())
        .append(" tagged=")
        .append(view.getTag(R.id.tag_card_alpha_value))
        .append('\n');
  }


  private static void paint(View view, int value) {
    Drawable background = view.getBackground();
    if (background == null) return;
    Integer last = (Integer) view.getTag(R.id.tag_card_alpha_value);
    if (last != null && last == value) return;
    // 100% 且之前没被我们动过：背景本来就是不透明的，不白白 mutate 一份。
    // （被我们调低过的卡片 last 一定非 null，所以「拖回去」这条路径一定会真正 setAlpha(255)。）
    if (value != 255 || last != null) background.mutate().setAlpha(value);
    view.setTag(R.id.tag_card_alpha_value, value);
  }

  private static boolean isCard(View view, Drawable.ConstantState[] cards) {
    Drawable background = view.getBackground();
    if (background == null) return false;
    Drawable.ConstantState state = background.getConstantState();
    if (state == null) return false;
    for (Drawable.ConstantState card : cards) {
      if (card != null && card == state) return true;
    }
    return false;
  }

  /**
   * 当前主题下每个卡片资源的 ConstantState。
   *
   * <p>不缓存：深色主题（{@code values-night/colors.xml} 里 card 是 #111214）与浅色主题
   * 是两套不同的 ConstantState，Activity 重建后 Context/Resources 已经切好，现取现用最稳。
   * 一次 apply 只解析三个资源，开销可以忽略。
   */
  private static Drawable.ConstantState[] cardStates(Context context) {
    Drawable.ConstantState[] states = new Drawable.ConstantState[CARD_RES.length];
    if (context == null) return states;
    for (int i = 0; i < CARD_RES.length; i++) {
      try {
        Drawable drawable = ContextCompat.getDrawable(context, CARD_RES[i]);
        states[i] = drawable == null ? null : drawable.getConstantState();
      } catch (Throwable ignored) {
        states[i] = null;
      }
    }
    return states;
  }

  private static float clamp(float alpha) {
    if (alpha < 0.2f) return 0.2f;
    return Math.min(alpha, 1f);
  }
}
