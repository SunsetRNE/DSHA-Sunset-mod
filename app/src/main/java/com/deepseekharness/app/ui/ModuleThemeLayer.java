package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import com.deepseekharness.app.R;
import com.deepseekharness.app.module.ModuleCard;
import com.deepseekharness.app.module.ModuleCardStore;
import java.io.File;

/**
 * 主题层：模块换掉整个 App 的背景图与透明度。
 *
 * <p>只动背景这一层 —— 把 Activity 根布局的底色换成「底色 + 半透明背景图」的图层，
 * 不碰任何控件的位置与配色。这样主题模块不会把按钮、文字的对比度搞崩。
 *
 * <p>多个主题模块同时存在时，按模块 id 稳定取第一个，避免每次进主页换一张。
 */
final class ModuleThemeLayer {

  private static String appliedSignature = "";

  /** 当前主题是不是深色底；新页面挂上来时按这个决定要不要提亮文字。 */
  private static boolean appliedDark;

  /** 被我们改成透明的页面底色，恢复时要还回去，不然移除主题后整页变黑/透底。 */
  private static final java.util.List<java.lang.ref.WeakReference<View>> cleared =
      new java.util.ArrayList<>();

  /** 被改过字色的文字，恢复时要还回去（连同原色）。 */
  private static final java.util.List<Recolored> recolored = new java.util.ArrayList<>();

  /** token 改动过的控件撤销动作；主题切换/移除时先还原，避免叠加。 */
  private static final java.util.List<Runnable> tokenUndo = new java.util.ArrayList<>();

  /** 已经套过 token 的 view，避免 reapply 时把 token 值又当原始值记录。 */
  private static final java.util.IdentityHashMap<View, Boolean> tokenTouched =
      new java.util.IdentityHashMap<>();

  private static final class Recolored {
    final java.lang.ref.WeakReference<android.widget.TextView> view;
    final int original;

    Recolored(android.widget.TextView view, int original) {
      this.view = new java.lang.ref.WeakReference<>(view);
      this.original = original;
    }
  }

  private ModuleThemeLayer() {}

  static void apply(Activity activity) {
    View root = rootOf(activity);
    if (root == null) return;
    ModuleCard chosen = null;
    File art = null;
    for (ModuleCard card : ModuleCardStore.load(activity)) {
      if (card.theme == null) continue;
      File file = ModuleCardStore.assetOf(activity, card, card.theme.image);
      if (file == null) continue;
      chosen = card;
      art = file;
      break;
    }
    if (chosen == null) {
      restore(activity, root);
      clearContentBackgrounds(activity, root);
      return;
    }
    String signature =
        chosen.id
            + "|"
            + art.getAbsolutePath()
            + "|"
            + art.lastModified()
            + "|"
            + chosen.theme.opacity;
    if (signature.equals(appliedSignature)) {
      // 主题没变，但新页面的 view 刚建出来，底色、字色和 token 还得再处理一次。
      clearContentBackgrounds(activity, root);
      recolorText(activity, root, appliedDark);
      applyTokens(activity, root, chosen.theme.tokens);
      return;
    }
    // 换主题前先撤销上一套 token，避免 Material 背景/圆角层层叠加。
    undoTokens();
    Bitmap bitmap = decode(activity, art);
    if (bitmap == null) return;
    int scrim = Color.parseColor(chosen.theme.scrim.isEmpty() ? "#0E1116" : chosen.theme.scrim);
    android.graphics.drawable.BitmapDrawable image =
        new android.graphics.drawable.BitmapDrawable(activity.getResources(), bitmap);
    image.setAlpha(Math.round(chosen.theme.opacity * 255));
    image.setGravity(android.view.Gravity.FILL);
    image.setTileModeX(android.graphics.Shader.TileMode.CLAMP);
    image.setTileModeY(android.graphics.Shader.TileMode.CLAMP);
    LayerDrawable layers = new LayerDrawable(new Drawable[] {new ColorDrawable(scrim), image});
    root.setBackground(layers);
    appliedSignature = signature;
    appliedDark = isDark(scrim);
    // 各页根布局自带 shell_bg 底色，不清掉的话主题只有顶栏能透出来。
    clearContentBackgrounds(activity, root);
    // 深色背景上必须把标题/正文改成浅色，否则直接看不见字。
    recolorText(activity, root, appliedDark);
    // 背景之后套控件 token；没有 token 的旧模块完全不受影响。
    applyTokens(activity, root, chosen.theme.tokens);
  }

  /** 没有主题模块时恢复成出厂底色（包括被我们改成透明的页面）。 */
  static void restore(Activity activity, View root) {
    if (appliedSignature.isEmpty()) return;
    undoTokens();
    int shellBg = activity.getColor(R.color.shell_bg);
    for (Recolored item : recolored) {
      android.widget.TextView view = item.view.get();
      if (view != null) view.setTextColor(item.original);
    }
    recolored.clear();
    for (java.lang.ref.WeakReference<View> reference : cleared) {
      View view = reference.get();
      if (view != null) view.setBackgroundColor(shellBg);
    }
    cleared.clear();
    root.setBackgroundColor(shellBg);
    appliedSignature = "";
  }

  /**
   * 把「恰好是 shell_bg 这个纯色」的背景改成透明。
   *
   * <p>只认纯色，不碰卡片、按钮、输入框那些带描边或圆角的 drawable，避免把对比度搞崩。
   */
  private static void clearContentBackgrounds(Activity activity, View root) {
    if (root == null) return;
    int shellBg = activity.getColor(R.color.shell_bg);
    walk(root, shellBg);
  }

  /** 判断这层底色是不是深的（用感知亮度，不做花哨的对比度计算）。 */
  private static boolean isDark(int color) {
    double luma = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color);
    return luma < 128;
  }

  /**
   * 深色主题下把「默认深色文字」换成浅色，浅色主题下换回来。
   *
   * <p>只改恰好等于主题默认色的文字（shell_text / secondary / muted），
   * 模块自己指定过颜色的文字不动 —— 那是它的设计选择。
   */
  private static void recolorText(Activity activity, View root, boolean dark) {
    int[] sources = {
      activity.getColor(R.color.shell_text),
      activity.getColor(R.color.shell_text_secondary),
      activity.getColor(R.color.shell_text_muted)
    };
    int[] darkTargets = {0xFFF2F4F8, 0xFFA8B3C4, 0xFF7C8798};
    walkText(root, sources, darkTargets, dark);
  }

  private static void walkText(View view, int[] sources, int[] targets, boolean dark) {
    walkText(view, sources, targets, dark, 0);
  }

  private static void walkText(View view, int[] sources, int[] targets, boolean dark, int depth) {
    // 有自己底色的容器（卡片/按钮/输入框）里保持原样：白卡片上的深字不能提亮。
    if (depth > 0 && hasOwnBackground(view)) return;
    if (view instanceof android.widget.TextView) {
      int current = ((android.widget.TextView) view).getCurrentTextColor();
      for (int i = 0; i < sources.length; i++) {
        // 已经改过的跳过（深色下第二次进来时 current 已是目标色）。
        if (current == sources[i] || (dark && current == targets[i])) {
          if (dark && current != targets[i]) {
            recolored.add(new Recolored((android.widget.TextView) view, current));
            ((android.widget.TextView) view).setTextColor(targets[i]);
          }
          break;
        }
      }
    }
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++)
        walkText(group.getChildAt(i), sources, targets, dark, depth + 1);
    }
  }

  /** 是否自带一块不透明底色（透明色和刚清掉底的都算「没有」）。 */
  private static boolean hasOwnBackground(View view) {
    Drawable background = view.getBackground();
    if (background == null) return false;
    if (background instanceof ColorDrawable)
      return ((ColorDrawable) background).getColor() != Color.TRANSPARENT;
    return true;
  }

  private static void walk(View view, int shellBg) {
    Drawable background = view.getBackground();
    if (background instanceof ColorDrawable) {
      if (((ColorDrawable) background).getColor() == shellBg) {
        view.setBackgroundColor(Color.TRANSPARENT);
        cleared.add(new java.lang.ref.WeakReference<>(view));
      }
    }
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), shellBg);
    }
  }

  /** 主题模块被移除后要能立刻退回原样。 */
  static void reset() {
    appliedSignature = "";
  }

  /** 有新页面挂上来时调用：主题已生效就把它新长出来的底色也清掉。 */
  static void reapply(Activity activity) {
    if (appliedSignature.isEmpty()) return;
    View root = rootOf(activity);
    if (root == null) return;
    clearContentBackgrounds(activity, root);
    recolorText(activity, root, appliedDark);
    ModuleCard chosen = null;
    for (ModuleCard card : ModuleCardStore.load(activity)) {
      if (card.theme != null && ModuleCardStore.assetOf(activity, card, card.theme.image) != null) {
        chosen = card;
        break;
      }
    }
    if (chosen != null) applyTokens(activity, root, chosen.theme.tokens);
  }

  private static View rootOf(Activity activity) {
    android.view.ViewGroup content = activity.findViewById(android.R.id.content);
    if (content == null || content.getChildCount() == 0) return null;
    return content.getChildAt(0);
  }

  // ------------------------------------------------------------ 全局控件 token

  /**
   * 把主题 token 套到当前页面的真实控件上。
   *
   * <p>只改宿主认识的控件属性和默认颜色：MaterialCardView 的底色/圆角、MaterialButton 的
   * 背景/文字/圆角、TextView 的默认文字色、BottomNavigationView 的底栏颜色。模块自己指定过
   * 颜色的文字、带独立 drawable 的卡片不会被暴力覆盖。所有改动都记录撤销动作，移除主题时
   * 能回到原样。
   */
  private static void applyTokens(Activity activity, View root, ModuleCard.Tokens tokens) {
    if (tokens == null || tokens.isEmpty()) return;
    View navView = activity.findViewById(R.id.bottom_nav);
    if (navView instanceof com.google.android.material.bottomnavigation.BottomNavigationView) {
      com.google.android.material.bottomnavigation.BottomNavigationView nav =
          (com.google.android.material.bottomnavigation.BottomNavigationView) navView;
      boolean firstTouch = tokenTouched.put(nav, Boolean.TRUE) == null;
      if (!tokens.navBg.isEmpty() && firstTouch) {
        final android.content.res.ColorStateList original = nav.getBackgroundTintList();
        tokenUndo.add(() -> nav.setBackgroundTintList(original));
        nav.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(parseColor(tokens.navBg)));
      }
      if ((!tokens.navIcon.isEmpty() || !tokens.accent.isEmpty()) && firstTouch) {
        final android.content.res.ColorStateList originalIcons = nav.getItemIconTintList();
        final android.content.res.ColorStateList originalText = nav.getItemTextColor();
        tokenUndo.add(
            () -> {
              nav.setItemIconTintList(originalIcons);
              nav.setItemTextColor(originalText);
            });
        int normal = parseColor(tokens.navIcon.isEmpty() ? tokens.textSecondary : tokens.navIcon);
        int checked = parseColor(tokens.accent.isEmpty() ? tokens.navIcon : tokens.accent);
        android.content.res.ColorStateList states =
            new android.content.res.ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                new int[] {checked, normal});
        nav.setItemIconTintList(states);
        nav.setItemTextColor(states);
      }
    }
    walkTokens(root, tokens, activity, false, 0);
  }

  private static void walkTokens(
      View view, ModuleCard.Tokens tokens, Activity activity, boolean opaqueParent, int depth) {
    if (view == null) return;
    boolean themedSurface =
        view instanceof com.google.android.material.card.MaterialCardView
            && !tokens.surface.isEmpty();
    // 根布局的底色是主题背景，不算「自带底色」；子级白色卡片/按钮里的文字必须保持原色。
    boolean opaque = !themedSurface && (opaqueParent || depth > 0 && hasOwnBackground(view));
    if (tokenTouched.put(view, Boolean.TRUE) == null) applyToken(view, tokens, activity, opaque);
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++)
        walkTokens(group.getChildAt(i), tokens, activity, opaque, depth + 1);
    }
  }

  private static void applyToken(
      View view, ModuleCard.Tokens tokens, Activity activity, boolean opaque) {
    try {
      if (view instanceof com.google.android.material.card.MaterialCardView) {
        com.google.android.material.card.MaterialCardView card =
            (com.google.android.material.card.MaterialCardView) view;
        if (!tokens.surface.isEmpty()) {
          int original = card.getCardBackgroundColor().getDefaultColor();
          tokenUndo.add(() -> card.setCardBackgroundColor(original));
          card.setCardBackgroundColor(parseColor(tokens.surface));
        }
        if (tokens.radius >= 0) {
          float original = card.getRadius();
          tokenUndo.add(() -> card.setRadius(original));
          card.setRadius(dp(activity, tokens.radius));
        }
      }
      if (view instanceof com.google.android.material.button.MaterialButton) {
        com.google.android.material.button.MaterialButton button =
            (com.google.android.material.button.MaterialButton) view;
        if (!tokens.buttonBg.isEmpty()) {
          final android.content.res.ColorStateList original = button.getBackgroundTintList();
          tokenUndo.add(() -> button.setBackgroundTintList(original));
          button.setBackgroundTintList(
              android.content.res.ColorStateList.valueOf(parseColor(tokens.buttonBg)));
        }
        if (!tokens.buttonFg.isEmpty()) {
          final int original = button.getCurrentTextColor();
          tokenUndo.add(() -> button.setTextColor(original));
          button.setTextColor(parseColor(tokens.buttonFg));
        }
        if (tokens.buttonRadius >= 0) {
          final int original = button.getCornerRadius();
          tokenUndo.add(() -> button.setCornerRadius(original));
          button.setCornerRadius(dp(activity, tokens.buttonRadius));
        }
      }
      if (view instanceof android.widget.TextView) {
        android.widget.TextView text = (android.widget.TextView) view;
        int current = text.getCurrentTextColor();
        int shellText = activity.getColor(R.color.shell_text);
        int shellSecondary = activity.getColor(R.color.shell_text_secondary);
        int shellMuted = activity.getColor(R.color.shell_text_muted);
        if (!opaque && !tokens.text.isEmpty() && current == shellText) {
          final int original = current;
          tokenUndo.add(() -> text.setTextColor(original));
          text.setTextColor(parseColor(tokens.text));
        } else if (!opaque
            && !tokens.textSecondary.isEmpty()
            && (current == shellSecondary || current == shellMuted)) {
          final int original = current;
          tokenUndo.add(() -> text.setTextColor(original));
          text.setTextColor(parseColor(tokens.textSecondary));
        }
        if (Math.abs(tokens.fontScale - 1f) > 0.001f) {
          final float original = text.getTextSize();
          tokenUndo.add(() -> text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, original));
          text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, original * tokens.fontScale);
        }
      }
    } catch (RuntimeException ignored) {
      // 某个控件不兼容 token（例如自定义 background）时跳过，不影响其它控件。
    }
  }

  private static void undoTokens() {
    for (Runnable undo : tokenUndo) {
      try {
        undo.run();
      } catch (RuntimeException ignored) {
        // 页面已经销毁
      }
    }
    tokenUndo.clear();
    tokenTouched.clear();
  }

  private static int parseColor(String value) {
    try {
      return Color.parseColor(value);
    } catch (RuntimeException ignored) {
      return Color.TRANSPARENT;
    }
  }

  private static int dp(Activity activity, int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }

  /** 按屏幕宽度采样解码：全屏背景图直接解码原图很容易 OOM。 */
  private static Bitmap decode(Context context, File file) {
    try {
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
      int target = Math.max(1, context.getResources().getDisplayMetrics().widthPixels);
      int sample = 1;
      while (bounds.outWidth / (sample * 2) >= target) sample *= 2;
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inSampleSize = sample;
      return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    } catch (RuntimeException | OutOfMemoryError error) {
      return null;
    }
  }
}
