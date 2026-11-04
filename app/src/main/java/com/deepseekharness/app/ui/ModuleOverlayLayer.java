package com.deepseekharness.app.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.module.ModuleCard;
import com.deepseekharness.app.module.ModuleCardStore;
import com.deepseekharness.app.module.ModuleData;
import com.deepseekharness.app.module.ModuleScript;
import com.deepseekharness.app.util.UiText;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 悬浮层：让模块在整个 App 界面上跑起来。
 *
 * <p>卡片只能待在主页的滚动区里；悬浮层挂在 {@code android.R.id.content} 上，盖住所有页面
 * （设置、终端、Web 都一样），所以「宠物满屏幕跑」这件事才成立。
 *
 * <p>它不抢触摸：ImageView 只占自己那一块，其余地方点哪就是哪。
 * 点它一下会说一句话 —— 内容可以是固定文字、真实状态，也可以是一段脚本。
 */
final class ModuleOverlayLayer {

  private static final long MIN_WALK_MS = 850;
  private static final long MAX_WALK_MS = 1500;
  private static final long MIN_REST_MS = 350;
  private static final long MAX_REST_MS = 1200;

  private final Activity activity;
  private final FrameLayout container;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Random random = new Random();
  private final List<Sprite> sprites = new ArrayList<>();
  private final Map<String, Bitmap> cache = new HashMap<>();
  private boolean attached;
  private String signature = "";

  private final class Sprite {
    final ModuleCard card;
    final ImageView view;
    float x;
    float y;
    ValueAnimator walking;

    Sprite(ModuleCard card, ImageView view) {
      this.card = card;
      this.view = view;
    }
  }

  private final Runnable step =
      new Runnable() {
        @Override
        public void run() {
          if (!attached) return;
          for (Sprite sprite : sprites) walk(sprite);
          handler.postDelayed(this, restDelay() + 300);
        }
      };

  ModuleOverlayLayer(Activity activity) {
    this.activity = activity;
    this.container = activity.findViewById(android.R.id.content);
  }

  /** 按当前模块清单重建悬浮层。模块没变就不重建，免得每次回前台动画重置。 */
  void refresh() {
    if (container == null) return;
    String current = signature();
    if (current.equals(signature) && !sprites.isEmpty()) {
      attached = true;
      handler.removeCallbacks(step);
      handler.postDelayed(step, 400);
      return;
    }
    signature = current;
    for (Sprite sprite : sprites) {
      if (sprite.walking != null) sprite.walking.cancel();
      container.removeView(sprite.view);
    }
    sprites.clear();
    for (ModuleCard card : ModuleCardStore.load(activity)) {
      if (card.overlay == null) continue;
      File art = ModuleCardStore.assetOf(activity, card, card.overlay.image);
      if (art == null) continue;
      Bitmap bitmap = decode(activity, art, card.overlay.size);
      if (bitmap == null) continue;
      ImageView view = new ImageView(activity);
      view.setImageBitmap(bitmap);
      view.setScaleType(ImageView.ScaleType.FIT_CENTER);
      view.setContentDescription(card.name);
      int size = dp(activity, card.overlay.size);
      FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
      params.gravity = Gravity.TOP | Gravity.START;
      container.addView(view, params);
      Sprite sprite = new Sprite(card, view);
      sprite.x = randomX(size);
      sprite.y = randomY(size);
      view.setTranslationX(sprite.x);
      view.setTranslationY(sprite.y);
      view.setOnClickListener(v -> speak(sprite));
      sprites.add(sprite);
    }
    attached = true;
    handler.removeCallbacks(step);
    if (sprites.isEmpty()) return;
    if (reduceMotion()) {
      // 系统关了动画 / 开了无障碍：停在右侧中间不动，别让画面一直在跳。
      container.post(
          () -> {
            for (Sprite sprite : sprites) {
              int size = dp(activity, sprite.card.overlay.size);
              sprite.x = Math.max(0, container.getWidth() - size - dp(activity, 16));
              sprite.y = Math.max(0, (container.getHeight() - size) / 2f);
              sprite.view.setTranslationX(sprite.x);
              sprite.view.setTranslationY(sprite.y);
            }
          });
      return;
    }
    handler.postDelayed(step, 600);
  }

  void stop() {
    attached = false;
    handler.removeCallbacks(step);
    for (Sprite sprite : sprites) {
      if (sprite.walking != null) sprite.walking.cancel();
    }
  }

  /** 模块集合 + 立绘 + 尺寸，任一变化才需要重建。 */
  private String signature() {
    StringBuilder builder = new StringBuilder();
    for (ModuleCard card : ModuleCardStore.load(activity)) {
      if (card.overlay == null) continue;
      builder
          .append(card.id)
          .append(':')
          .append(card.overlay.image)
          .append(':')
          .append(card.overlay.size)
          .append(';');
    }
    return builder.toString();
  }

  void destroy() {
    stop();
    signature = "";
    for (Sprite sprite : sprites) container.removeView(sprite.view);
    sprites.clear();
    cache.clear();
  }

  // ------------------------------------------------------------ 走动

  /** 尊重系统的「减少动态效果」：无障碍开启或动画时长为 0 时不做位移。 */
  private boolean reduceMotion() {
    try {
      android.view.accessibility.AccessibilityManager manager =
          (android.view.accessibility.AccessibilityManager)
              activity.getSystemService(Context.ACCESSIBILITY_SERVICE);
      if (manager != null && manager.isEnabled()) return true;
      float scale =
          android.provider.Settings.Global.getFloat(
              activity.getContentResolver(),
              android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
              1f);
      return scale == 0f;
    } catch (RuntimeException error) {
      return false;
    }
  }

  private void walk(Sprite sprite) {
    if (reduceMotion()) return;
    if (sprite.walking != null && sprite.walking.isRunning()) return;
    int size = sprite.view.getWidth();
    if (container.getWidth() <= 0) return;
    float targetX = randomX(size);
    float targetY = randomY(size);
    float fromX = sprite.x;
    float fromY = sprite.y;
    // 走一段路，带一次小跳：y 走抛物线，看起来像在蹦，而不是平移的贴纸。
    long duration = MIN_WALK_MS + random.nextInt((int) (MAX_WALK_MS - MIN_WALK_MS) + 1);
    float hop = dp(activity, 16);
    boolean faceLeft = targetX < fromX;
    ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
    animator.setDuration(duration);
    animator.setInterpolator(new DecelerateInterpolator());
    animator.addUpdateListener(
        animation -> {
          float t = animation.getAnimatedFraction();
          float x = fromX + (targetX - fromX) * t;
          float y = fromY + (targetY - fromY) * t - (float) Math.sin(Math.PI * t) * hop;
          sprite.x = x;
          sprite.y = y;
          sprite.view.setTranslationX(x);
          sprite.view.setTranslationY(y);
          sprite.view.setRotation(faceLeft ? -6f : 6f * (float) Math.sin(Math.PI * t * 2));
        });
    animator.addListener(
        new android.animation.AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(android.animation.Animator animation) {
            sprite.view.setRotation(0f);
          }
        });
    sprite.walking = animator;
    animator.start();
  }

  /** 两次走动之间的停顿，看起来像走两步停一下，而不是匀速机械移动。 */
  private long restDelay() {
    return MIN_REST_MS + random.nextInt((int) (MAX_REST_MS - MIN_REST_MS) + 1);
  }

  private float randomX(int size) {
    int max = Math.max(1, container.getWidth() - size);
    return random.nextInt(max);
  }

  private float randomY(int size) {
    int max = Math.max(1, container.getHeight() - size);
    return random.nextInt(max);
  }

  // ------------------------------------------------------------ 说话

  /** 点它：说一句话。有脚本就跑脚本，没有就报真实状态，再退回复标题。 */
  private void speak(Sprite sprite) {
    ModuleCard card = sprite.card;
    String say = card.overlay.say;
    if (say.startsWith("script:")) {
      String code = say.substring("script:".length()).trim();
      bubble(sprite, UiText.choose("（想了想…）", "(thinking…)"));
      ModuleScript.run(
          activity,
          card,
          code,
          new HashMap<>(),
          (ok, output) ->
              bubble(
                  sprite, output == null || output.isEmpty() ? UiText.choose("……", "…") : output));
      return;
    }
    if (!say.isEmpty()) {
      bubble(sprite, say);
      return;
    }
    String status = ModuleData.value(activity, ModuleData.DSH_STATUS);
    String port = ModuleData.value(activity, ModuleData.DSH_PORT);
    String text = card.subtitle.isEmpty() ? status : card.subtitle + " · " + status;
    if (!port.isEmpty() && !"0".equals(port) && !"—".equals(port)) text = text + " · " + port;
    bubble(sprite, text);
  }

  private void bubble(Sprite sprite, String text) {
    if (!attached || container == null) return;
    TextView bubble = new TextView(activity);
    bubble.setText(text);
    bubble.setTextSize(12);
    bubble.setTextColor(activity.getColor(R.color.shell_text));
    bubble.setBackgroundResource(R.drawable.bg_shell_edit_bar);
    int pad = dp(activity, 10);
    bubble.setPadding(pad, dp(activity, 7), pad, dp(activity, 7));
    bubble.setMaxWidth(dp(activity, 220));
    bubble.setElevation(dp(activity, 8));

    container.addView(
        bubble,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    bubble.post(
        () -> {
          float x =
              clamp(
                  sprite.x + sprite.view.getWidth() / 2f - bubble.getWidth() / 2f,
                  0,
                  container.getWidth() - bubble.getWidth());
          float y = sprite.y - bubble.getHeight() - dp(activity, 6);
          if (y < 0) y = sprite.y + sprite.view.getHeight() + dp(activity, 6);
          bubble.setTranslationX(x);
          bubble.setTranslationY(clamp(y, 0, container.getHeight() - bubble.getHeight()));
          bubble.setAlpha(0f);
          bubble.animate().alpha(1f).setDuration(120).start();
        });
    handler.postDelayed(
        () -> {
          bubble
              .animate()
              .alpha(0f)
              .setDuration(180)
              .withEndAction(
                  () -> {
                    container.removeView(bubble);
                  })
              .start();
        },
        3200);
  }

  private static float clamp(float value, float min, float max) {
    return Math.max(min, Math.min(max, value));
  }

  // ------------------------------------------------------------ 杂项

  private static Bitmap decode(Context context, File file, int dpSize) {
    try {
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
      int target = Math.max(1, dp(context, Math.max(40, dpSize * 2)));
      int sample = 1;
      while (bounds.outWidth / (sample * 2) >= target) sample *= 2;
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inSampleSize = sample;
      return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    } catch (RuntimeException | OutOfMemoryError error) {
      return null;
    }
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
