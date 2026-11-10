package com.deepseekharness.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.deepseekharness.app.R;

/**
 * 外观背景：只作用在 DSHA-Mod 的原生界面。
 *
 * <p>页面结构 215 起改用 {@link CardPage} 的统一语言（矢量返回 + 小标题 + 白卡 +
 * 带图标的行项目），和设置/后台任务等二级页一致；壁纸层仍由本页自己铺
 * （{@code app_shell / app_background / app_scrim}），所以 {@code CardPage} 以
 * {@code opaqueRoot=false} 创建，白卡之外能看见用户选的图。
 *
 * <p>功能仍是三件事：<b>选一张图 · 调遮罩 · 清除回默认</b>，竖屏与横屏舞台各一套。
 * 排障信息由 {@link DshAppearance#apply} 自动写到
 * {@code /sdcard/Download/DSHA/appearance-apply.txt}，不占界面。
 */
public class AppearanceActivity extends AppCompatActivity {
  private final ActivityResultLauncher<String> picker =
      registerForActivityResult(new ActivityResultContracts.GetContent(), this::onPicked);
  /** GetContent 在个别 ROM 上打不开相册，保留一条系统文件选择器兜底（界面上不单列按钮）。 */
  private final ActivityResultLauncher<String[]> docPicker =
      registerForActivityResult(
          new ActivityResultContracts.OpenDocument(),
          uri -> {
            if (uri != null) onPicked(uri);
            else toast("没有选图");
          });
  /** 选图后进裁剪页；裁剪页自己写图与配置，这里只负责刷新。 */
  private final ActivityResultLauncher<Intent> cropLauncher =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          result -> {
            if (result.getResultCode() == RESULT_OK) {
              toast("图片已应用");
              recreate();
            }
          });

  private FrameLayout shell;
  private TextView status;
  private TextView scrimLabel;
  private SeekBar scrimBar;
  /** 210：横屏 DSH 舞台壁纸（独立配置段 landscape）。 */
  private TextView landscapeStatus;
  private TextView landscapeScrimLabel;
  private SeekBar landscapeScrimBar;
  /** 本次选图要写进哪个区域：app = 原生界面，landscape = 横屏舞台。 */
  private String pickArea = "app";

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    if (DshAppearance.isTransparent(this, "app"))
      setTheme(R.style.Theme_DeepseekHarness_Translucent);
    super.onCreate(savedInstanceState);

    shell = new FrameLayout(this);
    shell.setId(R.id.app_shell);

    ImageView background = new ImageView(this);
    background.setId(R.id.app_background);
    background.setScaleType(ImageView.ScaleType.CENTER_CROP);
    background.setContentDescription(null);
    background.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    shell.addView(
        background,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    View scrim = new View(this);
    scrim.setId(R.id.app_scrim);
    shell.addView(
        scrim,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    CardPage page =
        new CardPage(
            this,
            "外观背景",
            "竖屏原生界面（启动页/设置/插件/数据/终端）与横屏 DSH 舞台各一张，可分开设置；"
                + "竖屏 DSH 网页不铺壁纸")
            .transparentRoot();
    LinearLayout header = UiNavigation.addHeader(this, page.root, "外观背景", this::finish);
    page.content.removeViewAt(0);
    // 本页是 edge-to-edge（壁纸要铺到状态栏下），所以页头必须自己吃状态栏/挖孔留白，
    // 否则标题会顶进状态栏里 —— 普通主题的二级页由系统自动留白，不需要这一步。
    UiInsets.top(header);

    // ① 卡片外观：白卡默认全不透明会盖住壁纸，这里让用户把卡片调透一点
    page.label("页面卡片");
    LinearLayout lookCard = page.card();
    float currentAlpha = new com.deepseekharness.app.core.ConfigStore(this).getCardAlpha();
    TextView cardAlphaLabel =
        page.text("不透明度：" + Math.round(currentAlpha * 100f) + "%", 12, R.color.text_secondary);
    lookCard.addView(cardAlphaLabel);
    SeekBar cardAlphaBar = new SeekBar(this);
    cardAlphaBar.setMax(80);
    cardAlphaBar.setProgress(
        Math.round((new com.deepseekharness.app.core.ConfigStore(this).getCardAlpha() - 0.2f) * 100f));
    cardAlphaBar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            float alpha = 0.2f + progress / 100f;
            cardAlphaLabel.setText("不透明度：" + Math.round(alpha * 100f) + "%");
            applyCardAlpha(alpha);
          }

          @Override
          public void onStartTrackingTouch(SeekBar bar) {}

          @Override
          public void onStopTrackingTouch(SeekBar bar) {
            new com.deepseekharness.app.core.ConfigStore(AppearanceActivity.this)
                .setCardAlpha(0.2f + bar.getProgress() / 100f);
          }
        });
    lookCard.addView(cardAlphaBar, new LinearLayout.LayoutParams(-1, -2));

    // ② 竖屏原生界面
    page.label("竖屏原生界面");
    LinearLayout appCard = page.card();
    status = page.text("", 13, R.color.text);
    status.setLineSpacing(0, 1.3f);
    appCard.addView(status);
    page.entry(
        appCard,
        "选一张图…",
        "从相册挑，铺满启动页与设置等原生界面",
        R.drawable.ic_ui_camera,
        () -> launchPicker("app"));
    scrimLabel = page.text("遮罩", 12, R.color.text_secondary);
    LinearLayout.LayoutParams scrimLabelLp = new LinearLayout.LayoutParams(-1, -2);
    scrimLabelLp.topMargin = dp(14);
    appCard.addView(scrimLabel, scrimLabelLp);
    scrimBar = new SeekBar(this);
    scrimBar.setMax(90);
    scrimBar.setProgress(Math.round(DshAppearance.currentScrim(this) * 100f));
    scrimBar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            scrimLabel.setText("遮罩：" + progress + "%");
          }

          @Override
          public void onStartTrackingTouch(SeekBar bar) {}

          @Override
          public void onStopTrackingTouch(SeekBar bar) {
            DshAppearance.setScrim(AppearanceActivity.this, bar.getProgress() / 100f);
            DshAppearance.apply(AppearanceActivity.this, "app", null);
          }
        });
    appCard.addView(scrimBar, new LinearLayout.LayoutParams(-1, -2));
    page.button(
        appCard,
        "清除，回到默认",
        false,
        () -> {
          DshAppearance.clearImage(this);
          recreate();
        });

    // ② 横屏 DSH 舞台
    page.label("横屏 DSH 舞台");
    LinearLayout landscapeCard = page.card();
    landscapeStatus = page.text("", 13, R.color.text);
    landscapeStatus.setLineSpacing(0, 1.3f);
    landscapeCard.addView(landscapeStatus);
    page.entry(
        landscapeCard,
        "选一张横屏图…",
        "只铺在 16:9 视口外那圈背景上，不影响视口内的网页",
        R.drawable.ic_ui_camera,
        () -> launchPicker("landscape"));
    landscapeScrimLabel = page.text("横屏遮罩", 12, R.color.text_secondary);
    LinearLayout.LayoutParams landscapeLabelLp = new LinearLayout.LayoutParams(-1, -2);
    landscapeLabelLp.topMargin = dp(14);
    landscapeCard.addView(landscapeScrimLabel, landscapeLabelLp);
    landscapeScrimBar = new SeekBar(this);
    landscapeScrimBar.setMax(90);
    landscapeScrimBar.setProgress(
        Math.round(DshAppearance.currentLandscapeScrim(this) * 100f));
    landscapeScrimBar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            landscapeScrimLabel.setText("横屏遮罩：" + progress + "%");
          }

          @Override
          public void onStartTrackingTouch(SeekBar bar) {}

          @Override
          public void onStopTrackingTouch(SeekBar bar) {
            DshAppearance.setScrimArea(
                AppearanceActivity.this, "landscape", bar.getProgress() / 100f);
          }
        });
    landscapeCard.addView(landscapeScrimBar, new LinearLayout.LayoutParams(-1, -2));
    page.button(
        landscapeCard,
        "清除横屏壁纸",
        false,
        () -> {
          DshAppearance.clearImageArea(this, "landscape");
          recreate();
        });

    shell.addView(
        page.root,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    setContentView(shell);
    DshAppearance.applyEdgeToEdge(this);
    UiInsets.scroll(page.scroll);

    refreshSummary();
    DshAppearance.apply(this, "app", null);
  }

  @Override
  protected void onResume() {
    super.onResume();
    DshAppearance.apply(this, "app", null);
    DshAppearance.applyStatusBarVisibility(this);
    refreshSummary();
  }

  /**
   * 本页实时预览：整棵 shell 扫一遍。
   *
   * <p>壁纸（{@code app_background}）与遮罩（{@code app_scrim}）不是 bg_card，不会被误伤；
   * 其余每张卡片都按 {@link CardAlpha} 的同一套判据刷，拖动时和松手后写库的结果一致。
   */
  private void applyCardAlpha(float alpha) {
    CardAlpha.apply(shell, alpha);
  }



  private void launchPicker(String area) {
    pickArea = area == null ? "app" : area;
    status.setText("正在打开相册…");
    try {
      picker.launch("image/*");
    } catch (Throwable error) {
      try {
        docPicker.launch(new String[] {"image/*"});
      } catch (Throwable again) {
        new DshaDialogBuilder(this)
            .setTitle("相册打不开")
            .setMessage("两个选择器都没能启动：" + again.getClass().getSimpleName())
            .setPositiveButton("知道了", null)
            .show();
      }
    }
  }

  private void refreshSummary() {
    String now = DshAppearance.presetLabel(this);
    if (status != null) {
      if (DshAppearance.read(this).missing) {
        status.setText("图片文件已不在（可能被清理或换机丢失），请重新选一张。");
      } else if ("默认".equals(now)) {
        status.setText("当前：无壁纸。");
      } else {
        status.setText("当前：" + now);
      }
    }
    boolean image = DshAppearance.read(this).isImage();
    if (scrimLabel != null)
      scrimLabel.setText("遮罩：" + Math.round(DshAppearance.currentScrim(this) * 100f) + "%");
    if (scrimBar != null) {
      scrimBar.setEnabled(image);
      scrimBar.setAlpha(image ? 1f : 0.45f);
    }

    DshAppearance.Spec landscape = DshAppearance.readLandscape(this);
    if (landscapeStatus != null) {
      if (landscape.missing) {
        landscapeStatus.setText("横屏图文件已不在，请重新选一张。");
      } else if (landscape.isImage()) {
        landscapeStatus.setText(
            "横屏：已设置（遮罩 "
                + Math.round(DshAppearance.currentLandscapeScrim(this) * 100f)
                + "%）");
      } else {
        landscapeStatus.setText("横屏：使用内置科幻壁纸（可换成自己的图）。");
      }
    }
    if (landscapeScrimLabel != null)
      landscapeScrimLabel.setText(
          "横屏遮罩：" + Math.round(DshAppearance.currentLandscapeScrim(this) * 100f) + "%");
    if (landscapeScrimBar != null) {
      landscapeScrimBar.setEnabled(landscape.isImage());
      landscapeScrimBar.setAlpha(landscape.isImage() ? 1f : 0.45f);
    }
  }

  private void onPicked(Uri uri) {
    if (uri == null) {
      toast("没有选图");
      return;
    }
    // 先复制到缓存再进裁剪页：跨 Activity 传文件路径比传 content:// 稳。
    java.io.File picked = DshAppearance.copyPickToCache(this, uri);
    if (picked != null) {
      try {
        Intent intent = new Intent(this, ImageCropActivity.class);
        intent.putExtra(ImageCropActivity.EXTRA_PATH, picked.getAbsolutePath());
        intent.putExtra(ImageCropActivity.EXTRA_AREA, pickArea);
        cropLauncher.launch(intent);
        return;
      } catch (Throwable ignored) {
        // 裁剪页起不来就退回「直接导入」，不能让选图整个失败。
      }
    }
    DshAppearance.ImportResult result =
        DshAppearance.importImageDetailedArea(this, uri, pickArea);
    if (result.ok) {
      toast("图片已应用");
      recreate();
    } else {
      new DshaDialogBuilder(this)
          .setTitle("选图没成功")
          .setMessage(result.message)
          .setPositiveButton("知道了", null)
          .show();
    }
  }

  private void toast(String text) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
