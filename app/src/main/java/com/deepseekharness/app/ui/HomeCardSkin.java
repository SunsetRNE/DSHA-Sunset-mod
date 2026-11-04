package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.module.ModuleCard;
import com.deepseekharness.app.module.ModuleCardStore;

/**
 * 主页原生卡片皮肤：让 {@code ui.skin.home.cards} 模块改主页任意一张卡片的底色、
 * 背景图（模块自带图片 + 透明度）、圆角、文字/次级文字/图标颜色。
 *
 * <p>只改「恰好等于主题默认色」的文字和图标——模块自己指定过颜色的内容不动，
 * 和整页主题层是同一条对比度纪律。皮肤在每次主页重渲染时随新 tile 重新应用，
 * 不需要撤销记录；模块被移除后下一次 render 自然回到原样。
 */
final class HomeCardSkin {

  private HomeCardSkin() {}

  /** skinCard 为 null 或该卡没有 token 时什么都不做。 */
  static void apply(Context context, ModuleCard skinCard, String cardId, View content) {
    if (skinCard == null || skinCard.skin == null || content == null) return;
    ModuleCard.Skin.CardTokens tokens = skinCard.skin.cardTokens(cardId);
    if (tokens == null) return;
    applyBackground(context, skinCard, tokens, content);
    applyText(context, tokens, content);
    applyIconTint(context, tokens, content);
  }

  /**
   * 只重套文字与图标颜色：宿主的状态轮询（每秒）会把工作台值文字改回主题默认色，
   * 状态刷新后要再套一次才能在深色卡上保持可读。背景不动（那部分只在渲染时套）。
   */
  static void applyTextAndTint(Context context, ModuleCard skinCard, String cardId, View content) {
    if (skinCard == null || skinCard.skin == null || content == null) return;
    ModuleCard.Skin.CardTokens tokens = skinCard.skin.cardTokens(cardId);
    if (tokens == null) return;
    applyText(context, tokens, content);
    applyIconTint(context, tokens, content);
  }

  private static void applyBackground(
      Context context, ModuleCard skinCard, ModuleCard.Skin.CardTokens tokens, View content) {
    boolean hasColor = !tokens.bg.isEmpty();
    boolean hasImage = !tokens.image.isEmpty();
    boolean hasRadius = tokens.radiusDp >= 0;
    if (!hasColor && !hasImage && !hasRadius) return;
    // 在原 drawable 上 mutate：保留宿主底样式的 1dp 描边（bg_shell_status 自带），
    // 只改皮肤声明的部分；没有可继承的 GradientDrawable 才新建。
    Drawable existing = content.getBackground();
    GradientDrawable base;
    if (existing instanceof GradientDrawable) base = (GradientDrawable) existing.mutate();
    else {
      base = new GradientDrawable();
      base.setColor(context.getColor(R.color.shell_surface));
      base.setCornerRadius(
          context.getResources().getDimensionPixelSize(R.dimen.shell_launch_radius_status));
    }
    if (hasColor) base.setColor(parse(tokens.bg, context.getColor(R.color.shell_surface)));
    if (hasRadius) base.setCornerRadius(dp(context, tokens.radiusDp));
    Drawable background = base;
    if (hasImage) {
      android.graphics.drawable.Drawable image = imageDrawable(context, skinCard, tokens);
      if (image != null) background = new LayerDrawable(new Drawable[] {base, image});
    }
    content.setBackground(background);
  }

  private static Drawable imageDrawable(
      Context context, ModuleCard skinCard, ModuleCard.Skin.CardTokens tokens) {
    java.io.File file = ModuleCardStore.assetOf(context, skinCard, tokens.image);
    if (file == null) return null;
    Bitmap bitmap = decode(context, file);
    if (bitmap == null) return null;
    BitmapDrawable drawable = new BitmapDrawable(context.getResources(), bitmap);
    drawable.setGravity(android.view.Gravity.FILL);
    drawable.setTileModeX(android.graphics.Shader.TileMode.CLAMP);
    drawable.setTileModeY(android.graphics.Shader.TileMode.CLAMP);
    drawable.setAlpha(Math.round(Math.max(0f, Math.min(1f, tokens.imageOpacity)) * 255));
    return drawable;
  }

  /**
   * 只提亮/压暗「恰好是主题默认色」的文字；模块自己的配色不动。
   *
   * <p>自带不透明底色的子树整棵跳过（MaterialButton、白色卡片等）：那是控件自己的
   * 对比度设计，把它的深字提亮会变成白底白字。和整页主题层是同一条纪律。
   */
  private static void applyText(Context context, ModuleCard.Skin.CardTokens tokens, View content) {
    boolean hasText = !tokens.text.isEmpty();
    boolean hasSub = !tokens.sub.isEmpty();
    if (!hasText && !hasSub) return;
    int shellText = context.getColor(R.color.shell_text);
    int shellSecondary = context.getColor(R.color.shell_text_secondary);
    int shellMuted = context.getColor(R.color.shell_text_muted);
    int textTarget = hasText ? parse(tokens.text, shellText) : 0;
    int subTarget = hasSub ? parse(tokens.sub, shellSecondary) : 0;
    walk(
        content,
        0,
        (view, depth) -> {
          if (!(view instanceof TextView)) return;
          if (depth > 0 && hasOwnOpaqueBackground(view)) return;
          TextView text = (TextView) view;
          int current = text.getCurrentTextColor();
          if (hasText && current == shellText) text.setTextColor(textTarget);
          else if (hasSub && (current == shellSecondary || current == shellMuted))
            text.setTextColor(subTarget);
        });
  }

  /** 是否自带一块不透明底色（透明色和刚清掉底的都算「没有」）。 */
  private static boolean hasOwnOpaqueBackground(View view) {
    Drawable background = view.getBackground();
    if (background == null) return false;
    if (background instanceof ColorDrawable)
      return ((ColorDrawable) background).getColor() != Color.TRANSPARENT;
    return true;
  }

  /** 只改「恰好是主题 accent」的图标 tint。 */
  private static void applyIconTint(
      Context context, ModuleCard.Skin.CardTokens tokens, View content) {
    if (tokens.iconTint.isEmpty()) return;
    int accent = context.getColor(R.color.shell_accent);
    int target = parse(tokens.iconTint, accent);
    walk(
        content,
        0,
        (view, depth) -> {
          if (!(view instanceof ImageView)) return;
          ImageView icon = (ImageView) view;
          if (icon.getImageTintList() != null
              && icon.getImageTintList().getDefaultColor() == accent)
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(target));
        });
  }

  private interface Walker {
    void visit(View view, int depth);
  }

  private static void walk(View view, int depth, Walker walker) {
    walker.visit(view, depth);
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), depth + 1, walker);
    }
  }

  private static int parse(String raw, int fallback) {
    try {
      return Color.parseColor(raw);
    } catch (RuntimeException ignored) {
      return fallback;
    }
  }

  /** 按屏幕宽度采样解码，避免大图直接解码 OOM。 */
  private static Bitmap decode(Context context, java.io.File file) {
    try {
      android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
      int target = Math.max(1, context.getResources().getDisplayMetrics().widthPixels);
      int sample = 1;
      while (bounds.outWidth / (sample * 2) >= target) sample *= 2;
      android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
      options.inSampleSize = sample;
      return android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    } catch (RuntimeException | OutOfMemoryError error) {
      return null;
    }
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
