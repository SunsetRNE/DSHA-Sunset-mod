package com.deepseekharness.app.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.deepseekharness.app.R;

import java.io.File;

/**
 * 壁纸裁剪：选图之后按「屏幕比例」裁一次，输出就是能铺满整屏的图。
 *
 * <p>裁剪框比例直接取当前屏幕宽高比（自适应各种屏幕与全面屏），
 * 用户可拖动/双指缩放图片，确认后交给 {@link DshAppearance#commitCroppedBitmap} 落盘。
 */
public class ImageCropActivity extends AppCompatActivity {
  public static final String EXTRA_PATH = "path";
  /** 210：裁剪目标区域。{@code app} = 原生界面壁纸；{@code landscape} = 横屏 DSH 舞台壁纸。 */
  public static final String EXTRA_AREA = "area";

  private CropView crop;
  private String path;
  private String area = "app";

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PATH);
    if (path == null || !new File(path).isFile()) {
      Toast.makeText(this, "没有可裁剪的图片", Toast.LENGTH_SHORT).show();
      finish();
      return;
    }

    area = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_AREA);
    if (area == null || area.isEmpty()) area = "app";

    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);

    // 横屏壁纸最终铺满整块横屏舞台，裁剪框按「横屏屏幕比例」走；竖屏壁纸仍用本页的比例。
    float fixedRatio = 0f;
    if ("landscape".equals(area)) {
      android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
      int longSide = Math.max(dm.widthPixels, dm.heightPixels);
      int shortSide = Math.max(1, Math.min(dm.widthPixels, dm.heightPixels));
      fixedRatio = longSide / (float) shortSide;
    }
    crop = new CropView();
    crop.fixedRatio = fixedRatio;
    root.addView(
        crop,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    Bitmap bitmap = load(path);
    if (bitmap == null) {
      Toast.makeText(this, "图片解码失败", Toast.LENGTH_SHORT).show();
      finish();
      return;
    }
    crop.setBitmap(bitmap);

    // 顶部：取消 / 标题 / 完成（玻璃按钮，跟全局一致）
    LinearLayout top = new LinearLayout(this);
    top.setOrientation(LinearLayout.HORIZONTAL);
    top.setGravity(Gravity.CENTER_VERTICAL);
    top.setPadding(dp(16), dp(10), dp(16), dp(10));
    TextView cancel = barButton("取消", false);
    cancel.setOnClickListener(v -> finish());
    TextView title = new TextView(this);
    title.setText("landscape".equals(area) ? "裁剪横屏壁纸" : "裁剪壁纸");
    title.setTextSize(15);
    title.setTextColor(Color.WHITE);
    title.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
    TextView done = barButton("完成", true);
    done.setOnClickListener(v -> save());
    top.addView(cancel);
    top.addView(title, tlp);
    top.addView(done);
    FrameLayout.LayoutParams tlpWrap =
        new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
    root.addView(top, tlpWrap);

    TextView hint = new TextView(this);
    hint.setText("拖动图片调整位置，双指缩放；框内就是壁纸");
    hint.setTextSize(11);
    hint.setTextColor(0xCCFFFFFF);
    hint.setGravity(Gravity.CENTER);
    hint.setPadding(dp(16), dp(8), dp(16), dp(12));
    FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    root.addView(hint, hlp);

    setContentView(root);

    try {
      androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
      getWindow().setStatusBarColor(Color.TRANSPARENT);
      getWindow().setNavigationBarColor(Color.TRANSPARENT);
      androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
          root,
          (v, insets) -> {
            androidx.core.graphics.Insets bars =
                insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                        | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            top.setPadding(dp(16), dp(10) + bars.top, dp(16), dp(10));
            hint.setPadding(dp(16), dp(8), dp(16), dp(12) + bars.bottom);
            return insets;
          });
    } catch (Throwable ignored) {
    }
  }

  private void save() {
    if (crop == null) return;
    Bitmap result;
    try {
      result = crop.crop();
    } catch (Throwable error) {
      Toast.makeText(this, "裁剪失败：" + error.getClass().getSimpleName(), Toast.LENGTH_LONG)
          .show();
      return;
    }
    if (result == null) {
      Toast.makeText(this, "裁剪结果为空", Toast.LENGTH_SHORT).show();
      return;
    }
    DshAppearance.ImportResult saved =
        DshAppearance.commitCroppedBitmapArea(this, result, area);
    result.recycle();
    if (saved.ok) {
      setResult(RESULT_OK, new Intent());
      finish();
    } else {
      new DshaDialogBuilder(this)
          .setTitle("保存失败")
          .setMessage(saved.message)
          .setPositiveButton("知道了", null)
          .show();
    }
  }

  private TextView barButton(String text, boolean primary) {
    TextView view = new TextView(this);
    view.setText(text);
    view.setTextSize(14);
    view.setGravity(Gravity.CENTER);
    view.setMinHeight(dp(40));
    view.setMinWidth(dp(64));
    view.setPadding(dp(14), 0, dp(14), 0);
    view.setBackgroundResource(primary ? R.drawable.bg_btn_primary : R.drawable.bg_btn_double);
    view.setTextColor(primary ? getColor(R.color.button_primary_text) : getColor(R.color.text));
    return view;
  }

  /** 解码原图：按裁屏幕 2 倍长边采样，避免大图直接 OOM。 */
  private Bitmap load(String file) {
    try {
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(file, bounds);
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
      android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
      int maxLong = Math.max(dm.widthPixels, dm.heightPixels) * 2;
      int sample = 1;
      int longSide = Math.max(bounds.outWidth, bounds.outHeight);
      while (longSide / sample > maxLong) sample *= 2;
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inSampleSize = sample;
      Bitmap bitmap = BitmapFactory.decodeFile(file, options);
      return rotateForExif(bitmap, file);
    } catch (Throwable error) {
      return null;
    }
  }

  private Bitmap rotateForExif(Bitmap bitmap, String file) {
    if (bitmap == null) return null;
    try {
      android.media.ExifInterface exif = new android.media.ExifInterface(file);
      int orientation =
          exif.getAttributeInt(
              android.media.ExifInterface.TAG_ORIENTATION,
              android.media.ExifInterface.ORIENTATION_NORMAL);
      int degrees = 0;
      switch (orientation) {
        case android.media.ExifInterface.ORIENTATION_ROTATE_90:
          degrees = 90;
          break;
        case android.media.ExifInterface.ORIENTATION_ROTATE_180:
          degrees = 180;
          break;
        case android.media.ExifInterface.ORIENTATION_ROTATE_270:
          degrees = 270;
          break;
        default:
          break;
      }
      if (degrees == 0) return bitmap;
      Matrix matrix = new Matrix();
      matrix.postRotate(degrees);
      Bitmap rotated =
          Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
      if (rotated != bitmap) bitmap.recycle();
      return rotated;
    } catch (Throwable ignored) {
      return bitmap;
    }
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  /**
   * 裁剪画布：框比例 = 屏幕宽高比；图片可拖动/缩放，但必须始终盖满框。
   */
  private class CropView extends View {
    private Bitmap bitmap;
    /** >0 = 固定裁剪比例（宽/高）；0 = 用视图宽高比。 */
    float fixedRatio = 0f;
    private final Matrix matrix = new Matrix();
    private final RectF frame = new RectF();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ScaleGestureDetector scaleDetector;
    private float minScale = 1f;
    private float lastX;
    private float lastY;

    CropView() {
      super(ImageCropActivity.this);
      maskPaint.setColor(0xB3000000);
      borderPaint.setColor(0xFFFFFFFF);
      borderPaint.setStyle(Paint.Style.STROKE);
      borderPaint.setStrokeWidth(dp(2));
    }

    void setBitmap(Bitmap value) {
      bitmap = value;
      requestLayout();
      invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
      super.onSizeChanged(w, h, oldw, oldh);
      if (bitmap == null || w <= 0 || h <= 0) return;
      float ratio = fixedRatio > 0f ? fixedRatio : (float) w / (float) h;
      float padW = dp(24);
      float padH = dp(96);
      float frameW = w - padW * 2;
      float frameH = frameW / ratio;
      float maxH = h - padH * 2;
      if (frameH > maxH) {
        frameH = maxH;
        frameW = frameH * ratio;
      }
      float left = (w - frameW) / 2f;
      float top = (h - frameH) / 2f;
      frame.set(left, top, left + frameW, top + frameH);

      minScale = Math.max(frameW / bitmap.getWidth(), frameH / bitmap.getHeight());
      matrix.reset();
      matrix.setScale(minScale, minScale);
      matrix.postTranslate(
          frame.centerX() - bitmap.getWidth() * minScale / 2f,
          frame.centerY() - bitmap.getHeight() * minScale / 2f);
      clamp();
      scaleDetector = new ScaleGestureDetector(getContext(), new ScaleListener());
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      if (bitmap == null || frame.isEmpty()) return;
      canvas.drawBitmap(bitmap, matrix, bitmapPaint);

      // 框外压暗：四条边各画一块，避免用 clip 造成边缘发灰
      canvas.drawRect(0, 0, getWidth(), frame.top, maskPaint);
      canvas.drawRect(0, frame.bottom, getWidth(), getHeight(), maskPaint);
      canvas.drawRect(0, frame.top, frame.left, frame.bottom, maskPaint);
      canvas.drawRect(frame.right, frame.top, getWidth(), frame.bottom, maskPaint);
      canvas.drawRect(frame, borderPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      if (bitmap == null) return false;
      if (scaleDetector != null) scaleDetector.onTouchEvent(event);
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          lastX = event.getX();
          lastY = event.getY();
          return true;
        case MotionEvent.ACTION_MOVE:
          if (event.getPointerCount() == 1) {
            float x = event.getX();
            float y = event.getY();
            matrix.postTranslate(x - lastX, y - lastY);
            lastX = x;
            lastY = y;
            clamp();
            invalidate();
          }
          return true;
        case MotionEvent.ACTION_POINTER_UP:
          // 抬起一根手指后，以剩下那根重新记起点，避免跳一下
          int index = event.getActionIndex() == 0 ? 1 : 0;
          lastX = event.getX(index);
          lastY = event.getY(index);
          return true;
        default:
          return true;
      }
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
      @Override
      public boolean onScale(ScaleGestureDetector detector) {
        float current = currentScale();
        float target = current * detector.getScaleFactor();
        float max = minScale * 4f;
        if (target < minScale) target = minScale;
        if (target > max) target = max;
        float factor = target / current;
        if (Math.abs(factor - 1f) > 0.0001f) {
          matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
          clamp();
          invalidate();
        }
        return true;
      }
    }

    private float currentScale() {
      float[] values = new float[9];
      matrix.getValues(values);
      return values[0];
    }

    /** 保证图始终盖满裁剪框；越界就平移回来。 */
    private void clamp() {
      if (bitmap == null || frame.isEmpty()) return;
      RectF mapped = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
      matrix.mapRect(mapped);
      float dx = 0;
      float dy = 0;
      if (mapped.left > frame.left) dx = frame.left - mapped.left;
      if (mapped.right < frame.right) dx = frame.right - mapped.right;
      if (mapped.top > frame.top) dy = frame.top - mapped.top;
      if (mapped.bottom < frame.bottom) dy = frame.bottom - mapped.bottom;
      if (dx != 0 || dy != 0) matrix.postTranslate(dx, dy);
    }

    Bitmap crop() {
      if (bitmap == null || frame.isEmpty()) return null;
      int outW = Math.max(1, Math.round(frame.width()));
      int outH = Math.max(1, Math.round(frame.height()));
      Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(out);
      canvas.translate(-frame.left, -frame.top);
      canvas.drawBitmap(bitmap, matrix, bitmapPaint);
      return out;
    }
  }
}
