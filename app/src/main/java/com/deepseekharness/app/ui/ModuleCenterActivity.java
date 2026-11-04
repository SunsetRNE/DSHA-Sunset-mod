package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.net.Uri;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.module.ModuleCatalog;
import com.deepseekharness.app.module.ModuleCard;
import com.deepseekharness.app.util.UiText;

/** 用户可查看和启停声明式模块；不执行清单代码，不伪造 provider 安装状态。卡片名下的每日定时任务也能在这里查看与删除。 */
public final class ModuleCenterActivity extends AppCompatActivity {
  private LinearLayout list;
  private ModuleCatalog.Snapshot catalog;

  /** 导入用户模块包（card.json / module.json / .zip）：最小可用的「自己写一个模块」。 */
  private final androidx.activity.result.ActivityResultLauncher<String[]> cardPicker =
      registerForActivityResult(
          new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
          uri -> {
            if (uri == null) return;
            // 读包（最大 64MB）+ 解压 + 摘要 + 整目录落盘都可能在几秒量级，
            // 必须放后台线程；主线程只负责结果提示与刷新，否则导入大模块必 ANR。
            android.widget.Toast.makeText(
                    this,
                    UiText.choose("正在导入模块…", "Importing module…"),
                    android.widget.Toast.LENGTH_SHORT)
                .show();
            new Thread(
                    () -> {
                      com.deepseekharness.app.module.ModuleImport.Result result = null;
                      String error = null;
                      try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
                        if (input == null) throw new java.io.IOException("CARD_INPUT");
                        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                        byte[] chunk = new byte[8192];
                        for (int count; (count = input.read(chunk)) != -1; ) {
                          if (buffer.size() + count > 64 * 1024 * 1024)
                            throw new java.io.IOException("MODULE_PACKAGE_LIMIT");
                          buffer.write(chunk, 0, count);
                        }
                        result =
                            com.deepseekharness.app.module.ModuleImport.importPackage(
                                this, buffer.toByteArray());
                      } catch (Exception failure) {
                        error = failure.getMessage();
                      }
                      final com.deepseekharness.app.module.ModuleImport.Result finalResult = result;
                      final String failureText = error;
                      runOnUiThread(
                          () -> {
                            if (isFinishing() || isDestroyed()) return;
                            if (failureText != null) {
                              android.widget.Toast.makeText(
                                      this,
                                      UiText.choose(
                                          "模块包无效：" + failureText,
                                          "Invalid module package: " + failureText),
                                      android.widget.Toast.LENGTH_LONG)
                                  .show();
                              return;
                            }
                            com.deepseekharness.app.DshaApp.from(this).invalidateModuleSnapshot();
                            render();
                            android.widget.Toast.makeText(
                                    this,
                                    finalResult.card
                                        ? UiText.choose(
                                            "已加入主页："
                                                + finalResult.detail
                                                + "\n"
                                                + finalResult.identitySummary(true),
                                            "Added to home: "
                                                + finalResult.detail
                                                + "\n"
                                                + finalResult.identitySummary(false))
                                        : UiText.choose("已导入模块清单", "Manifest imported"),
                                    android.widget.Toast.LENGTH_LONG)
                                .show();
                          });
                    },
                    "module-import")
                .start();
          });

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    // 模块中心也可能被坏模块带崩：先装看门狗，必要时把上次的嫌疑模块直接停用。
    com.deepseekharness.app.module.ModuleCrashGuard.install(this);
    String recoveredModule = com.deepseekharness.app.module.ModuleCrashGuard.recover(this);
    if (!recoveredModule.isEmpty())
      android.widget.Toast.makeText(
              this,
              UiText.choose(
                  "已自动停用疑似导致崩溃的模块："
                      + com.deepseekharness.app.module.ModuleCrashGuard.labelOf(
                          this, recoveredModule)
                      + "。确认没问题后可再次开启。",
                  "Disabled a module that likely crashed the app: "
                      + com.deepseekharness.app.module.ModuleCrashGuard.labelOf(
                          this, recoveredModule)
                      + ". Enable it again once you're sure it's safe."),
              android.widget.Toast.LENGTH_LONG)
          .show();
    CardPage page =
        new CardPage(
            this,
            UiText.choose("模块中心", "Module center"),
            UiText.choose(
                "查看已安装模块、架构和能力声明。启停只影响声明式模块，不授予权限。",
                "Review installed modules, architecture and capability declarations. Enable/disable changes declarations only; it grants no permissions."));
    list = page.column();
    page.content.addView(list, new LinearLayout.LayoutParams(-1, -2));
    page.button(page.footer, UiText.choose("返回", "Back"), false, this::finish);
    setContentView(page.root);
    render();
  }

  private void render() {
    catalog = ModuleCatalog.load(this);
    list.removeAllViews();
    if (!catalog.issues().isEmpty()) {
      TextView issues = new TextView(this);
      issues.setText(
          UiText.choose("模块问题：\n", "Module issues:\n")
              + catalog.issues().stream()
                  .map(issue -> issue.source + " · " + issue.message)
                  .collect(java.util.stream.Collectors.joining("\n")));
      issues.setTextColor(getColor(R.color.shell_bad));
      issues.setTextSize(12);
      list.addView(issues, new LinearLayout.LayoutParams(-1, -2));
    }
    if (catalog.entries().isEmpty()) {
      TextView empty = new TextView(this);
      empty.setText(
          UiText.choose(
              "暂无已安装模块。可以导入 card.json 或 module.json。",
              "No modules installed. Import a card.json or module.json to get started."));
      empty.setTextColor(getColor(R.color.shell_text_secondary));
      empty.setPadding(dp(16), dp(10), dp(16), dp(10));
      list.addView(empty);
    }
    addCardModules();
    addLayoutCard();
  }

  /**
   * 用户主页卡片：这一块才是「万物皆模块」的第一版落地。
   *
   * <p>卡片是一段声明式 JSON（标题/副标题/emoji/强调色/最多 4 个按钮），
   * 按钮动作只能从宿主白名单里挑。容器里的 dsh 也可以把同样的 JSON 写到
   * {@code ~/.dsha-modules/}，宿主下次回主页时自动收进来。
   */
  private void addCardModules() {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(16), dp(14), dp(16), dp(14));
    card.setBackgroundResource(R.drawable.bg_shell_card);
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
    cp.bottomMargin = dp(12);
    list.addView(card, cp);

    TextView title = new TextView(this);
    title.setText(UiText.choose("模块", "Modules"));
    title.setTextColor(getColor(R.color.shell_text));
    title.setTextSize(15);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    card.addView(title);

    TextView hint = new TextView(this);
    hint.setText(
        UiText.choose(
            "导入 card.json / module.json，或让容器里的 dsh 写到 ~/.dsha-modules/。",
            "Import card.json / module.json, or let dsh in the container write to ~/.dsha-modules/."));
    hint.setTextColor(getColor(R.color.shell_text_secondary));
    hint.setTextSize(12);
    hint.setPadding(0, dp(4), 0, dp(8));
    card.addView(hint);

    // 统一列表：卡片和声明式清单都在这里，一套开关、一套身份、一套移除。
    if (catalog.entries().isEmpty()) {
      TextView empty = new TextView(this);
      empty.setText(UiText.choose("还没有模块", "No modules yet"));
      empty.setTextColor(getColor(R.color.shell_text_muted));
      empty.setTextSize(12);
      empty.setPadding(0, 0, 0, dp(8));
      card.addView(empty);
    }
    for (ModuleCatalog.Entry item : catalog.entries()) {
      boolean enabled = item.enabled;
      // 每个模块一张独立白卡：标题 + 开关 + 一句话描述 + 状态，细节默认收起，和插件详情一致。
      LinearLayout moduleCard = new LinearLayout(this);
      moduleCard.setOrientation(LinearLayout.VERTICAL);
      moduleCard.setPadding(dp(16), dp(14), dp(16), dp(12));
      moduleCard.setBackgroundResource(R.drawable.bg_shell_card);
      LinearLayout.LayoutParams moduleParams = new LinearLayout.LayoutParams(-1, -2);
      moduleParams.bottomMargin = dp(10);
      list.addView(moduleCard, moduleParams);

      TextView name = new TextView(this);
      String emoji = item.card == null || item.card.emoji.isEmpty() ? "" : item.card.emoji + " ";
      String cardTitle = emoji + item.name + (item.isCard() ? "" : " · 清单");
      name.setText(cardTitle);
      name.setTextColor(getColor(R.color.shell_text));
      name.setAlpha(enabled ? 1f : 0.45f);
      name.setTextSize(15);
      name.setTypeface(null, android.graphics.Typeface.BOLD);
      name.setSingleLine(true);
      name.setEllipsize(android.text.TextUtils.TruncateAt.END);
      moduleCard.addView(name, new LinearLayout.LayoutParams(-1, -2));

      com.google.android.material.materialswitch.MaterialSwitch on =
          new com.google.android.material.materialswitch.MaterialSwitch(this);
      on.setContentDescription(UiText.choose("启用 " + item.name, "Enable " + item.name));
      on.setChecked(enabled);
      on.setOnCheckedChangeListener(
          (button, checked) -> {
            ModuleCatalog.setEnabled(this, item, checked);
            com.deepseekharness.app.DshaApp.from(this).invalidateModuleSnapshot();
            moduleDirty();
            render();
            promptRestart(item.name, checked);
          });

      TextView desc = new TextView(this);
      String subtitle =
          item.card != null && !item.card.subtitle.isEmpty()
              ? item.card.subtitle
              : item.capabilities(true);
      desc.setText(subtitle);
      desc.setTextSize(12);
      desc.setTextColor(getColor(R.color.shell_text_secondary));
      desc.setAlpha(enabled ? 1f : 0.45f);
      desc.setMaxLines(2);
      desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
      desc.setPadding(0, dp(6), 0, 0);
      moduleCard.addView(desc);

      // 状态行：左边「已启用 · 版本」，右边开关和「更多」，与插件卡片一致。
      LinearLayout statusRow = new LinearLayout(this);
      statusRow.setOrientation(LinearLayout.HORIZONTAL);
      statusRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

      TextView status = new TextView(this);
      String versionText =
          item.version == null || item.version.isEmpty() ? "" : " · v" + item.version;
      String statusText =
          (enabled ? UiText.choose("已启用", "Enabled") : UiText.choose("已停用", "Disabled"))
              + versionText;
      status.setText(statusText);
      status.setTextSize(12);
      status.setTextColor(getColor(enabled ? R.color.shell_accent : R.color.shell_text_muted));
      status.setSingleLine(true);
      status.setEllipsize(android.text.TextUtils.TruncateAt.END);
      statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
      statusRow.addView(on);

      TextView moreChip = new TextView(this);
      moreChip.setText(UiText.choose("更多", "More"));
      moreChip.setTextSize(12);
      moreChip.setTextColor(getColor(R.color.shell_accent));
      moreChip.setGravity(android.view.Gravity.CENTER);
      moreChip.setBackgroundResource(R.drawable.bg_shell_pill);
      moreChip.setPadding(dp(12), dp(8), dp(12), dp(8));
      moreChip.setContentDescription(UiText.choose("更多操作：" + item.name, "More: " + item.name));
      UiMotion.pressScale(moreChip);
      LinearLayout.LayoutParams moreChipParams = new LinearLayout.LayoutParams(-2, -2);
      moreChipParams.leftMargin = dp(8);
      statusRow.addView(moreChip, moreChipParams);

      LinearLayout.LayoutParams statusRowParams = new LinearLayout.LayoutParams(-1, -2);
      statusRowParams.topMargin = dp(8);
      moduleCard.addView(statusRow, statusRowParams);

      LinearLayout details = new LinearLayout(this);
      details.setOrientation(LinearLayout.VERTICAL);
      details.setVisibility(android.view.View.GONE);
      moduleCard.addView(details, new LinearLayout.LayoutParams(-1, -2));

      // 身份行：作者（自述）/ 版本 / 安装来源 / 摘要短形 / 信任状态，一眼可辨识。
      TextView identity = new TextView(this);
      identity.setText(item.identitySummary(true));
      identity.setTextSize(11);
      identity.setTextColor(getColor(R.color.shell_text_muted));
      identity.setAlpha(enabled ? 1f : 0.45f);
      identity.setPadding(0, dp(8), 0, 0);
      details.addView(identity);

      // 签名状态：未受信时给出指纹和显式信任入口；签名无效时只展示原因，不提供信任。
      if (!com.deepseekharness.app.module.ModuleTrust.VERIFIED.equals(item.trustStatus)
          && !com.deepseekharness.app.module.ModuleTrust.UNVERIFIED.equals(item.trustStatus)
          && !item.trustMessage.isEmpty()) {
        TextView trustNote = new TextView(this);
        trustNote.setText(item.trustMessage);
        trustNote.setTextSize(10);
        trustNote.setTextColor(
            getColor(
                com.deepseekharness.app.module.ModuleTrust.INVALID.equals(item.trustStatus)
                    ? R.color.shell_bad
                    : R.color.shell_warn));
        trustNote.setPadding(0, dp(2), 0, 0);
        details.addView(trustNote);
      }

      LinearLayout actions = new LinearLayout(this);
      actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
      actions.setPadding(0, dp(6), 0, 0);
      if (com.deepseekharness.app.module.ModuleTrust.UNTRUSTED.equals(item.trustStatus)
          && !item.trustKeyId.isEmpty()
          && !item.trustPublicKey.isEmpty()) {
        actions.addView(
            chip(
                UiText.choose("信任此密钥", "Trust this key"),
                getColor(R.color.shell_accent),
                () ->
                    new DshaDialogBuilder(this)
                        .setTitle(UiText.choose("信任这把签名公钥？", "Trust this signing key?"))
                        .setMessage(
                            UiText.choose(
                                "keyId："
                                    + item.trustKeyId
                                    + "\n\n信任后，凡是用这把私钥签名的模块都会显示「已验证」。"
                                    + "只有当你确认指纹来自作者本人时才信任。",
                                "keyId: "
                                    + item.trustKeyId
                                    + "\n\nAfter trusting, modules signed by this private key show as verified. "
                                    + "Only trust a fingerprint you verified with the author."))
                        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
                        .setPositiveButton(
                            UiText.choose("信任", "Trust"),
                            (d, w) -> {
                              com.deepseekharness.app.module.ModuleTrustStore.trust(
                                  this, item.trustKeyId, item.trustPublicKey);
                              render();
                            })
                        .show()),
            rowChipParams());
      }
      if (com.deepseekharness.app.module.ModuleTrust.VERIFIED.equals(item.trustStatus)
          && !com.deepseekharness.app.module.ModuleTrustStore.load(this)
              .isBuiltin(item.trustKeyId)) {
        actions.addView(
            chip(
                UiText.choose("撤销信任", "Revoke trust"),
                getColor(R.color.shell_warn),
                () -> {
                  com.deepseekharness.app.module.ModuleTrustStore.revoke(this, item.trustKeyId);
                  render();
                }),
            rowChipParams());
      }
      if (item.manifest != null && !item.manifest.pages().isEmpty()) {
        com.deepseekharness.app.module.ModuleManifest.Page page = item.manifest.pages().get(0);
        actions.addView(
            chip(
                UiText.choose("打开清单页", "Open manifest page"),
                getColor(R.color.shell_accent),
                () -> ManifestPageActivity.open(this, item.id, page.id())),
            rowChipParams());
      }
      if (item.card != null
          && com.deepseekharness.app.module.ModuleCardStore.hasPrevious(this, item.id)) {
        actions.addView(
            chip(
                UiText.choose("恢复上一版", "Restore previous"),
                getColor(R.color.shell_accent),
                () -> {
                  boolean restored =
                      com.deepseekharness.app.module.ModuleCardStore.restorePrevious(this, item.id);
                  android.widget.Toast.makeText(
                          this,
                          UiText.choose(
                              restored ? "已恢复上一版" : "恢复失败，模块未改动",
                              restored
                                  ? "Previous version restored"
                                  : "Restore failed; module unchanged"),
                          android.widget.Toast.LENGTH_SHORT)
                      .show();
                  render();
                }),
            rowChipParams());
      }
      if (item.card != null
          && enabled
          && com.deepseekharness.app.module.ModuleExec.isAllowed(this, item.id)) {
        actions.addView(
            chip(
                UiText.choose("撤销授权", "Revoke"),
                getColor(R.color.shell_warn),
                () -> {
                  com.deepseekharness.app.module.ModuleExec.setAllowed(this, item.id, false);
                  render();
                }),
            rowChipParams());
      }
      details.addView(actions, new LinearLayout.LayoutParams(-1, -2));

      // 定时任务区：只属于主页卡片；声明式清单目前没有定时执行入口。
      if (item.card != null) addScheduleSection(this, details, item.card, this::render);
      // 受管程序区：与命令授权分开，逐程序授权/撤销。
      if (item.card != null && !item.card.programs.isEmpty())
        addProgramSection(this, details, item.card, this::render);

      TextView remove = new TextView(this);
      remove.setText(UiText.choose("删除模块", "Remove module"));
      remove.setTextSize(13);
      remove.setGravity(android.view.Gravity.CENTER);
      remove.setTextColor(getColor(R.color.shell_bad));
      remove.setPadding(0, dp(12), 0, dp(2));
      UiMotion.pressScale(remove);
      remove.setOnClickListener(
          v ->
              new DshaDialogBuilder(this)
                  .setTitle(UiText.choose("删除这个模块？", "Remove this module?"))
                  .setMessage(
                      UiText.choose(
                          "会同时撤销它的执行授权与定时任务，删除后不可恢复。",
                          "Its command grant and schedules are revoked too. This cannot be undone."))
                  .setNegativeButton(UiText.choose("取消", "Cancel"), null)
                  .setPositiveButton(
                      UiText.choose("删除", "Remove"),
                      (d, w) -> {
                        ModuleCatalog.remove(this, item);
                        render();
                      })
                  .show());
      details.addView(remove);

      TextView more = new TextView(this);
      more.setText(UiText.choose("展开模块详情", "Show details"));
      more.setTextSize(13);
      more.setGravity(android.view.Gravity.CENTER);
      more.setTextColor(getColor(R.color.shell_accent));
      more.setPadding(0, dp(12), 0, dp(2));
      UiMotion.pressScale(more);
      Runnable toggleDetails =
          () -> {
            boolean show = details.getVisibility() != android.view.View.VISIBLE;
            details.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
            more.setText(
                show
                    ? UiText.choose("收起模块详情", "Hide details")
                    : UiText.choose("展开模块详情", "Show details"));
          };
      moreChip.setOnClickListener(v -> toggleDetails.run());
      more.setOnClickListener(v -> toggleDetails.run());
      moduleCard.addView(more);
    }

    com.google.android.material.button.MaterialButton template =
        new com.google.android.material.button.MaterialButton(this);
    template.setText(UiText.choose("给模型看的说明书", "Guide for the model"));
    UiMotion.pressScale(template);
    template.setOnClickListener(
        v ->
            new DshaDialogBuilder(this)
                .setTitle(UiText.choose("主页卡片模板", "Home card template"))
                .setMessage(com.deepseekharness.app.module.ModuleCard.SKILL)
                .setPositiveButton(
                    UiText.choose("复制", "Copy"),
                    (d, w) -> {
                      android.content.ClipboardManager clipboard =
                          (android.content.ClipboardManager)
                              getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                      if (clipboard != null)
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                "DSHA", com.deepseekharness.app.module.ModuleCard.SKILL));
                      android.widget.Toast.makeText(
                              this,
                              UiText.choose("已复制，可直接发给容器里的模型", "Copied, paste it to dsh"),
                              android.widget.Toast.LENGTH_SHORT)
                          .show();
                    })
                .setNegativeButton(UiText.choose("关闭", "Close"), null)
                .show());
    LinearLayout.LayoutParams templateParams = new LinearLayout.LayoutParams(-1, -2);
    templateParams.topMargin = dp(6);
    card.addView(template, templateParams);

    com.google.android.material.button.MaterialButton pick =
        new com.google.android.material.button.MaterialButton(this);
    pick.setText(UiText.choose("导入模块包（.json / .zip）", "Import module (.json / .zip)"));
    UiMotion.pressScale(pick);
    pick.setOnClickListener(
        v -> cardPicker.launch(new String[] {"application/json", "application/zip", "text/*"}));
    LinearLayout.LayoutParams pickParams = new LinearLayout.LayoutParams(-1, -2);
    pickParams.topMargin = dp(6);
    card.addView(pick, pickParams);
  }

  /**
   * 定时任务区：一张卡片名下每日任务的就地管理。
   *
   * <p>只读 {@code ModuleScheduler} 的既有登记（forCard / clear / clearForCard）：
   * 这里不新增、不登记任务 —— 登记只能来自容器脚本（受每卡 ModuleExec 授权），
   * 界面只做展示、删除与状态说明，也不会为了展示而暗中授予执行权限。
   * 任务被删后回调 {@code onChanged}，Activity 借它刷新整份列表。
   *
   * <p>静态构造（不依赖 Activity 实例）：无 Android 页面的测试可以直接用应用上下文
   * 渲染本区，验证任务行内容与删除 / 清空绑定。
   *
   * @return 本分区视图（测试断言用）
   */
  static LinearLayout addScheduleSection(
      android.content.Context context,
      LinearLayout parent,
      com.deepseekharness.app.module.ModuleCard card,
      Runnable onChanged) {
    LinearLayout section = new LinearLayout(context);
    section.setOrientation(LinearLayout.VERTICAL);
    section.setPadding(dpOf(context, 4), 0, dpOf(context, 4), 0);

    java.util.Map<String, com.deepseekharness.app.module.ModuleScheduler.Entry> entries =
        com.deepseekharness.app.module.ModuleScheduler.forCard(context, card.id);

    TextView header = new TextView(context);
    header.setText(SchedulePresenter.sectionHeader(entries.size()));
    header.setTextColor(context.getColor(R.color.shell_text));
    header.setTextSize(12);
    header.setTypeface(null, android.graphics.Typeface.BOLD);
    header.setPadding(0, dpOf(context, 6), 0, 0);
    section.addView(header);

    if (entries.isEmpty()) {
      // 没有任务时只留一行状态，不再铺授权 / 通知 / 电池一整段说明。
      TextView none = new TextView(context);
      none.setText(UiText.choose("无定时任务", "No schedules"));
      none.setTextSize(11);
      none.setTextColor(context.getColor(R.color.shell_text_muted));
      none.setPadding(0, dpOf(context, 2), 0, 0);
      section.addView(none);
      parent.addView(section, new LinearLayout.LayoutParams(-1, -2));
      return section;
    }

    // 授权状态：定时执行沿用每卡 ModuleExec 授权，这里如实显示当前状态，不代替用户授权。
    boolean allowed = com.deepseekharness.app.module.ModuleExec.isAllowed(context, card.id);
    TextView auth = new TextView(context);
    auth.setText(SchedulePresenter.authStatus(allowed));
    auth.setTextSize(11);
    auth.setTextColor(
        context.getColor(allowed ? R.color.shell_text_secondary : R.color.shell_warn));
    auth.setPadding(0, dpOf(context, 2), 0, 0);
    section.addView(auth);

    // 通知权限：没有它，到点结果只会落盘、不会弹系统通知。这里显式提示并请求一次。
    if (android.os.Build.VERSION.SDK_INT >= 33
        && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      TextView notifyOff = new TextView(context);
      notifyOff.setText(
          UiText.choose(
              "通知权限未开启：任务仍会执行并记录结果，但不会弹通知。",
              "Notifications are off: tasks still run and record results, but no notification appears."));
      notifyOff.setTextSize(11);
      notifyOff.setTextColor(context.getColor(R.color.shell_warn));
      notifyOff.setPadding(0, dpOf(context, 2), 0, 0);
      section.addView(notifyOff);
      if (context instanceof android.app.Activity) {
        android.app.Activity activity = (android.app.Activity) context;
        TextView grant =
            chipOf(
                context,
                UiText.choose("开启通知", "Enable notifications"),
                context.getColor(R.color.shell_accent),
                () ->
                    activity.requestPermissions(
                        new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 104));
        LinearLayout.LayoutParams grantParams = new LinearLayout.LayoutParams(-2, -2);
        grantParams.topMargin = dpOf(context, 2);
        section.addView(grant, grantParams);
      }
    }

    // 电池优化：只显示状态并提供系统设置入口，不自动申请白名单。
    android.os.PowerManager power =
        (android.os.PowerManager) context.getSystemService(android.content.Context.POWER_SERVICE);
    boolean ignoring =
        power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    TextView powerLine = new TextView(context);
    powerLine.setText(SchedulePresenter.powerStatus(ignoring));
    powerLine.setTextSize(11);
    powerLine.setTextColor(
        context.getColor(ignoring ? R.color.shell_text_secondary : R.color.shell_warn));
    powerLine.setPadding(0, dpOf(context, 2), 0, 0);
    section.addView(powerLine);
    if (!ignoring) {
      TextView openPower =
          chipOf(
              context,
              UiText.choose("去关闭电池优化", "Open battery settings"),
              context.getColor(R.color.shell_accent),
              () -> {
                android.content.Intent intent =
                    new android.content.Intent(
                            android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                  context.startActivity(intent);
                } catch (RuntimeException ignoredError) {
                  android.widget.Toast.makeText(
                          context,
                          UiText.choose("无法打开电池优化设置", "Cannot open battery settings"),
                          android.widget.Toast.LENGTH_SHORT)
                      .show();
                }
              });
      LinearLayout.LayoutParams powerParams = new LinearLayout.LayoutParams(-2, -2);
      powerParams.topMargin = dpOf(context, 2);
      section.addView(openPower, powerParams);
    }

    for (com.deepseekharness.app.module.ModuleScheduler.Entry entry : entries.values()) {
      LinearLayout entryRow = new LinearLayout(context);
      entryRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
      entryRow.setPadding(0, dpOf(context, 2), 0, dpOf(context, 2));
      LinearLayout details = new LinearLayout(context);
      details.setOrientation(LinearLayout.VERTICAL);
      TextView line = new TextView(context);
      line.setText(
          SchedulePresenter.entryLine(
              entry.label, entry.hour, entry.minute, entry.kind, entry.value));
      line.setTextSize(11);
      line.setTextColor(context.getColor(R.color.shell_text_secondary));
      details.addView(line, new LinearLayout.LayoutParams(-1, -2));
      com.deepseekharness.app.module.ModuleScheduler.Result last =
          com.deepseekharness.app.module.ModuleScheduler.lastResult(context, entry.id);
      if (last != null) {
        TextView result = new TextView(context);
        result.setText(SchedulePresenter.lastResultLine(last.at, last.ok, last.text));
        result.setTextSize(10);
        result.setTextColor(
            context.getColor(last.ok ? R.color.shell_text_muted : R.color.shell_warn));
        details.addView(result, new LinearLayout.LayoutParams(-1, -2));
      }
      entryRow.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
      TextView delete =
          chipOf(
              context,
              UiText.choose("删除", "Delete"),
              context.getColor(R.color.shell_bad),
              () -> {
                com.deepseekharness.app.module.ModuleScheduler.clear(context, card.id, entry.id);
                onChanged.run();
              });
      delete.setContentDescription(
          UiText.choose(
              "删除定时任务：" + SchedulePresenter.displayLabel(entry.label),
              "Delete schedule: " + SchedulePresenter.displayLabel(entry.label)));
      LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(-2, -2);
      deleteParams.leftMargin = dpOf(context, 6);
      entryRow.addView(delete, deleteParams);
      section.addView(entryRow);
    }
    TextView clearAll =
        chipOf(
            context,
            UiText.choose("清空此模块任务", "Clear this module's tasks"),
            context.getColor(R.color.shell_bad),
            () -> {
              com.deepseekharness.app.module.ModuleScheduler.clearForCard(context, card.id);
              onChanged.run();
            });
    clearAll.setContentDescription(
        UiText.choose("清空此模块的全部定时任务", "Clear all schedules of this module"));
    LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(-2, -2);
    clearParams.topMargin = dpOf(context, 2);
    section.addView(clearAll, clearParams);

    TextView note = new TextView(context);
    note.setText(SchedulePresenter.delayNote());
    note.setTextSize(10);
    note.setTextColor(context.getColor(R.color.shell_text_muted));
    note.setPadding(0, dpOf(context, 4), 0, 0);
    section.addView(note);

    parent.addView(section, new LinearLayout.LayoutParams(-1, -2));
    return section;
  }

  /**
   * 受管程序区：展示程序身份、逐程序授权状态，并提供运行/撤销。
   *
   * <p>与每卡命令授权完全分开；运行前重新校验 SHA-256 与 ELF ABI。没有授权时先弹
   * 确认框，用户同意后才写入本程序自己的授权位。
   */
  static LinearLayout addProgramSection(
      android.content.Context context,
      LinearLayout parent,
      com.deepseekharness.app.module.ModuleCard card,
      Runnable onChanged) {
    LinearLayout section = new LinearLayout(context);
    section.setOrientation(LinearLayout.VERTICAL);
    section.setPadding(dpOf(context, 4), 0, dpOf(context, 4), dpOf(context, 4));

    TextView header = new TextView(context);
    header.setText(
        UiText.choose(
            "受管程序 · " + card.programs.size() + " 个", "Managed programs · " + card.programs.size()));
    header.setTextColor(context.getColor(R.color.shell_text));
    header.setTextSize(12);
    header.setTypeface(null, android.graphics.Typeface.BOLD);
    header.setPadding(0, dpOf(context, 8), 0, 0);
    section.addView(header);

    TextView note = new TextView(context);
    note.setText(
        UiText.choose(
            "程序只随模块包导入；运行前重算 SHA-256 与 ELF ABI。程序授权与命令授权分开，可单独撤销。",
            "Programs come only from module packages; SHA-256 and ELF ABI are rechecked before every run. "
                + "Program permission is separate from shell permission and can be revoked."));
    note.setTextSize(10);
    note.setTextColor(context.getColor(R.color.shell_text_muted));
    note.setPadding(0, dpOf(context, 2), 0, dpOf(context, 4));
    section.addView(note);

    for (com.deepseekharness.app.module.ModuleCard.Program program : card.programs.values()) {
      java.io.File file =
          com.deepseekharness.app.module.ModuleCardStore.assetOf(context, card, program.file);
      String validation =
          com.deepseekharness.app.module.ModuleProgram.validate(context, program, file);
      LinearLayout row = new LinearLayout(context);
      row.setGravity(android.view.Gravity.CENTER_VERTICAL);
      row.setPadding(0, dpOf(context, 3), 0, dpOf(context, 3));
      LinearLayout details = new LinearLayout(context);
      details.setOrientation(LinearLayout.VERTICAL);
      TextView line = new TextView(context);
      line.setText(
          program.id
              + " · "
              + program.abi
              + " · "
              + program.file
              + " · "
              + program.sha256.substring(0, 12)
              + "…");
      line.setTextSize(11);
      line.setTextColor(context.getColor(R.color.shell_text_secondary));
      details.addView(line, new LinearLayout.LayoutParams(-1, -2));
      TextView auth = new TextView(context);
      boolean allowed =
          com.deepseekharness.app.module.ModuleProgramAuth.isAllowed(context, card.id, program.id);
      auth.setText(
          validation != null
              ? UiText.choose("不可运行：", "Cannot run: ") + validation
              : allowed
                  ? UiText.choose("已授权运行这个程序", "Program run allowed")
                  : UiText.choose("未授权运行这个程序", "Program run blocked"));
      auth.setTextSize(10);
      auth.setTextColor(
          context.getColor(
              validation != null || !allowed ? R.color.shell_warn : R.color.shell_text_muted));
      details.addView(auth, new LinearLayout.LayoutParams(-1, -2));
      row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));

      TextView run =
          chipOf(
              context,
              allowed ? UiText.choose("运行", "Run") : UiText.choose("授权并运行", "Allow and run"),
              context.getColor(R.color.shell_accent),
              () -> {
                if (validation != null) {
                  android.widget.Toast.makeText(
                          context, validation, android.widget.Toast.LENGTH_SHORT)
                      .show();
                  return;
                }
                if (!com.deepseekharness.app.module.ModuleProgramAuth.isAllowed(
                    context, card.id, program.id)) {
                  requestProgramPermission(
                      context,
                      card,
                      program,
                      () -> {
                        com.deepseekharness.app.module.ModuleProgramAuth.setAllowed(
                            context, card.id, program.id, true);
                        runProgram(context, card, program);
                        onChanged.run();
                      });
                } else {
                  runProgram(context, card, program);
                }
              });
      LinearLayout.LayoutParams runParams = new LinearLayout.LayoutParams(-2, -2);
      runParams.leftMargin = dpOf(context, 6);
      row.addView(run, runParams);
      if (allowed) {
        TextView revoke =
            chipOf(
                context,
                UiText.choose("撤销程序授权", "Revoke program permission"),
                context.getColor(R.color.shell_warn),
                () -> {
                  com.deepseekharness.app.module.ModuleProgramAuth.revoke(
                      context, card.id, program.id);
                  onChanged.run();
                });
        LinearLayout.LayoutParams revokeParams = new LinearLayout.LayoutParams(-2, -2);
        revokeParams.leftMargin = dpOf(context, 4);
        row.addView(revoke, revokeParams);
      }
      section.addView(row);
    }
    parent.addView(section, new LinearLayout.LayoutParams(-1, -2));
    return section;
  }

  private static void requestProgramPermission(
      android.content.Context context,
      com.deepseekharness.app.module.ModuleCard card,
      com.deepseekharness.app.module.ModuleCard.Program program,
      Runnable onAllowed) {
    if (!(context instanceof android.app.Activity)) {
      android.widget.Toast.makeText(
              context,
              UiText.choose("请在模块中心授予程序授权", "Grant program permission in module center"),
              android.widget.Toast.LENGTH_SHORT)
          .show();
      return;
    }
    new DshaDialogBuilder((android.app.Activity) context)
        .setTitle(
            UiText.choose(
                "允许「" + card.name + "」运行原生程序？",
                "Allow \"" + card.name + "\" to run a native program?"))
        .setMessage(
            UiText.choose(
                "程序："
                    + program.id
                    + "\n文件："
                    + program.file
                    + "\n架构："
                    + program.abi
                    + "\nSHA-256："
                    + program.sha256
                    + "\n\n它会在你的容器里执行，拥有该容器内的权限。只对这张卡片的这个程序生效，"
                    + "随时可在模块中心撤销。",
                "Program: "
                    + program.id
                    + "\nFile: "
                    + program.file
                    + "\nABI: "
                    + program.abi
                    + "\nSHA-256: "
                    + program.sha256
                    + "\n\nIt runs inside your container with that container's privileges. "
                    + "Applies only to this program of this card, revocable in module center."))
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .setPositiveButton(UiText.choose("允许并运行", "Allow and run"), (d, w) -> onAllowed.run())
        .show();
  }

  private static void runProgram(
      android.content.Context context,
      com.deepseekharness.app.module.ModuleCard card,
      com.deepseekharness.app.module.ModuleCard.Program program) {
    android.widget.Toast.makeText(
            context,
            UiText.choose("正在运行 " + program.id + "…", "Running " + program.id + "…"),
            android.widget.Toast.LENGTH_SHORT)
        .show();
    final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    new Thread(
            () -> {
              com.deepseekharness.app.module.ModuleProgramRunner.Result result =
                  com.deepseekharness.app.module.ModuleProgramRunner.run(context, card, program);
              main.post(
                  () -> {
                    if (!(context instanceof android.app.Activity)) {
                      android.widget.Toast.makeText(
                              context, result.output, android.widget.Toast.LENGTH_LONG)
                          .show();
                      return;
                    }
                    new DshaDialogBuilder((android.app.Activity) context)
                        .setTitle(
                            result.ok
                                ? UiText.choose("程序运行完成", "Program finished")
                                : UiText.choose("程序运行失败", "Program failed"))
                        .setMessage(result.output)
                        .setPositiveButton(
                            UiText.choose("复制", "Copy"),
                            (d, w) -> {
                              android.content.ClipboardManager clipboard =
                                  (android.content.ClipboardManager)
                                      context.getSystemService(
                                          android.content.Context.CLIPBOARD_SERVICE);
                              if (clipboard != null)
                                clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText("DSHA", result.output));
                            })
                        .setNegativeButton(UiText.choose("关闭", "Close"), null)
                        .show();
                  });
            },
            "module-program-run")
        .start();
  }

  /**
   * 定时任务区的纯展示逻辑：把已读出的任务条目变成人话。
   *
   * <p>不 import Android、不碰 ModuleScheduler 的存储，方便在无 Android 运行时的
   * JVM 单测里验证界面到底会写什么（时间补零、动作命名、命令摘要、授权与 Doze 说明）。
   */
  public static final class SchedulePresenter {
    private SchedulePresenter() {}

    /** 每日时间，两位补零："7:5" → "07:05"。 */
    public static String timeLabel(int hour, int minute) {
      return String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute);
    }

    /** 任务显示名：登记时没写 label 就如实叫「未命名任务」，不编名字。 */
    public static String displayLabel(String label) {
      return label == null || label.isEmpty() ? UiText.choose("未命名任务", "Unnamed task") : label;
    }

    /** 动作类型的人话；未知类型原样返回，不冒充已认识。 */
    public static String kindLabel(String kind) {
      if ("exec".equals(kind)) return UiText.choose("执行命令", "shell command");
      if ("dsh.start".equals(kind)) return UiText.choose("启动容器", "start container");
      if ("dsh.stop".equals(kind)) return UiText.choose("停止容器", "stop container");
      return kind == null ? "" : kind;
    }

    /** exec 命令摘要：压成单行、折叠空白；超长截断加省略号。 */
    public static String commandSummary(String value, int max) {
      String oneLine = value == null ? "" : value.trim().replaceAll("\\s+", " ");
      if (oneLine.isEmpty() || max <= 0) return "";
      return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    /** 一条任务的展示行：标签 · 每天 HH:MM · 动作（exec 附命令摘要）。 */
    public static String entryLine(String label, int hour, int minute, String kind, String value) {
      StringBuilder line = new StringBuilder(displayLabel(label));
      line.append(" · ")
          .append(UiText.choose("每天", "daily"))
          .append(' ')
          .append(timeLabel(hour, minute));
      line.append(" · ").append(kindLabel(kind));
      if ("exec".equals(kind)) {
        String summary = commandSummary(value, 60);
        if (!summary.isEmpty()) line.append(" · ").append(summary);
      }
      return line.toString();
    }

    /** 分区标题：写明条数，0 条也要能看出这一区是干什么的。 */
    public static String sectionHeader(int count) {
      return UiText.choose("定时任务 · " + count + " 条", "Schedules · " + count);
    }

    /** 授权状态行：定时执行沿用每卡运行授权，未授权时到点只通知跳过。 */
    public static String authStatus(boolean allowed) {
      return allowed
          ? UiText.choose(
              "已授权运行：到点执行并把结果发进通知",
              "Run allowed: tasks run at trigger time and post the result as a notification")
          : UiText.choose(
              "未授权运行：到点只发「被跳过」通知，不会执行",
              "Run blocked: at trigger time the task is only reported as skipped, nothing runs");
    }

    /** 固定说明：非精确闹钟会被 Doze 推迟；之后撤销授权同样只会跳过。 */
    public static String delayNote() {
      return UiText.choose(
          "闹钟是非精确的：系统省电（Doze）可能把触发推迟几分钟。若之后撤销运行授权，任务到点会被跳过。",
          "Alarms are inexact: Doze may postpone triggers by minutes. "
              + "If run permission is revoked later, tasks are skipped at trigger time.");
    }

    /** 电池优化状态：只描述事实，不代替用户改系统设置。 */
    public static String powerStatus(boolean ignoringBatteryOptimizations) {
      return ignoringBatteryOptimizations
          ? UiText.choose(
              "电池优化已关闭：系统不会因省电提前冻结本应用。",
              "Battery optimization is off: the system will not freeze this app early to save power.")
          : UiText.choose(
              "电池优化未关闭：Doze 可能推迟触发，建议在系统设置里允许本应用后台运行。",
              "Battery optimization is on: Doze may postpone triggers. Allow this app to run in the background.");
    }

    /** 最近一次真实触发结果；没有结果时调用方不显示这一行。 */
    public static String lastResultLine(long at, boolean ok, String text) {
      String state = ok ? UiText.choose("成功", "success") : UiText.choose("失败/跳过", "failed/skipped");
      String body = text == null ? "" : text.trim().replaceAll("\\s+", " ");
      if (body.length() > 80) body = body.substring(0, 80) + "…";
      String time;
      try {
        time =
            java.time.Instant.ofEpochMilli(at)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
      } catch (RuntimeException ignored) {
        time = "";
      }
      return UiText.choose("最近 ", "last ")
          + time
          + " · "
          + state
          + (body.isEmpty() ? "" : " · " + body);
    }

    /** 空列表状态：说明这一区是什么、任务从哪来。 */
    public static String emptyNote() {
      return UiText.choose(
          "这张卡片还没有定时任务。容器里的脚本可用 schedule.set 登记，每天定时跑命令或启停容器。",
          "No schedules for this card yet. Scripts in the container can register one "
              + "with schedule.set to run a command or start/stop the container daily.");
    }
  }

  /**
   * 布局编辑入口。
   *
   * <p>主页每个区块都是可拖动模块，编辑必须在主页里就地做（拖动/改大小/收起都能立刻看到结果），
   * 所以这里不再维护第二套编辑器，只负责把用户送回主页并直接进入编辑态。
   */
  private void addLayoutCard() {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(16), dp(14), dp(16), dp(14));
    card.setBackgroundResource(R.drawable.bg_shell_card);
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
    cp.bottomMargin = dp(12);
    list.addView(card, cp);

    TextView title = new TextView(this);
    title.setText(UiText.choose("布局与外观", "Layout and appearance"));
    title.setTextColor(getColor(R.color.shell_text));
    title.setTextSize(15);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    card.addView(title);

    TextView hint = new TextView(this);
    hint.setText(
        UiText.choose(
            "主页上的每块内容都是模块：长按就能拖动换位置、改大小、收起。",
            "Every block on the home page is a module: long-press to drag, resize or hide."));
    hint.setTextColor(getColor(R.color.shell_text_secondary));
    hint.setTextSize(12);
    hint.setPadding(0, dp(6), 0, dp(10));
    card.addView(hint);

    com.google.android.material.button.MaterialButton edit =
        new com.google.android.material.button.MaterialButton(this);
    edit.setText(UiText.choose("回主页编辑布局", "Edit layout on home"));
    UiMotion.pressScale(edit);
    edit.setOnClickListener(
        v -> {
          com.deepseekharness.app.ui.LaunchFragment.pendingEditOnResume = true;
          finish();
        });
    card.addView(edit);

    // 启动入口可编辑：点这一行弹选择，和模块化思路一致，不再写死成本机 Web。
    TextView launchLabel = new TextView(this);
    launchLabel.setText(UiText.choose("启动入口", "Launch target"));
    launchLabel.setTextColor(getColor(R.color.shell_text));
    launchLabel.setTextSize(13);
    launchLabel.setPadding(0, dp(14), 0, 0);
    card.addView(launchLabel);

    TextView launchBody = new TextView(this);
    launchBody.setText(
        UiText.choose(
            launchSummary(com.deepseekharness.app.util.HomeLayoutStore.launchAction(this))
                + " · 点按修改",
            launchSummary(com.deepseekharness.app.util.HomeLayoutStore.launchAction(this))
                + " · tap to change"));
    launchBody.setTextColor(getColor(R.color.shell_accent));
    launchBody.setTextSize(12);
    launchBody.setPadding(0, dp(4), 0, dp(2));
    launchBody.setContentDescription(UiText.choose("修改启动入口", "Change launch target"));
    UiMotion.pressScale(launchBody);
    launchBody.setOnClickListener(v -> showLaunchTargetPicker());
    card.addView(launchBody);

    com.google.android.material.button.MaterialButton reset =
        new com.google.android.material.button.MaterialButton(this);
    reset.setText(UiText.choose("恢复默认布局", "Reset layout"));
    UiMotion.pressScale(reset);
    reset.setOnClickListener(
        v -> {
          com.deepseekharness.app.util.HomeLayoutStore.reset(this);
          com.deepseekharness.app.util.HomeLayoutStore.resetNav(this);
          com.deepseekharness.app.util.HomeLayoutStore.resetNavItemScale(this);
          if (com.deepseekharness.app.ui.MainActivity.active() != null)
            com.deepseekharness.app.ui.MainActivity.active().applyNavItemScales();
          android.widget.Toast.makeText(
                  this,
                  UiText.choose("已恢复默认布局，回主页查看", "Layout reset, check the home page"),
                  android.widget.Toast.LENGTH_SHORT)
              .show();
        });
    LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(-1, -2);
    resetParams.topMargin = dp(10);
    card.addView(reset, resetParams);

    // 模块开关并非全部能力都能热切换，这里给一个明确的「重启 App」入口。
    com.google.android.material.button.MaterialButton restart =
        new com.google.android.material.button.MaterialButton(this);
    restart.setText(UiText.choose("重启 App 生效", "Restart app"));
    UiMotion.pressScale(restart);
    restart.setOnClickListener(v -> restartApp());
    LinearLayout.LayoutParams restartParams = new LinearLayout.LayoutParams(-1, -2);
    restartParams.topMargin = dp(8);
    card.addView(restart, restartParams);
  }

  /** 启动入口在列表里显示的人话：只有容器工作台和容器里登记的程序。 */
  private String launchSummary(String action) {
    if (com.deepseekharness.app.util.HomeLayoutStore.isToolAction(action)) {
      String id = com.deepseekharness.app.util.HomeLayoutStore.toolIdOf(action);
      com.deepseekharness.app.module.ContainerTools.refresh(this);
      com.deepseekharness.app.module.ContainerTools.Tool tool =
          com.deepseekharness.app.module.ContainerTools.byId(this, id);
      return tool != null
          ? UiText.choose("容器程序 · ", "Container program · ")
              + tool.name
              + (tool.port > 0 ? " · :" + tool.port : "")
          : UiText.format("容器程序 · %s", id);
    }
    int port = com.deepseekharness.app.core.HarnessController.get(this).getWebPort();
    return port > 0
        ? UiText.format(
            UiText.choose("容器工作台 · 127.0.0.1:%s", "Container workspace · 127.0.0.1:%s"),
            String.valueOf(port))
        : UiText.choose("容器工作台（DSH Web）", "Container workspace (DSH web)");
  }

  /**
   * 启动入口选择器：只列容器内的目标 —— 容器工作台，以及容器里登记的程序。
   *
   * <p>程序来自容器内 {@code ~/.dsha-launch/<id>.json}（与模块同一条容器→壳通道），
   * 也能在这里直接新建/编辑/删除；写回的是同一份 JSON，容器里的 dsh 和壳内用户
   * 看到的是同一张列表。选中后按主页「启动」键就在容器里执行该程序的命令。
   */
  private void showLaunchTargetPicker() {
    java.util.concurrent.atomic.AtomicReference<androidx.appcompat.app.AlertDialog> dialogRef =
        new java.util.concurrent.atomic.AtomicReference<>();
    String current = com.deepseekharness.app.util.HomeLayoutStore.launchAction(this);
    com.deepseekharness.app.module.ContainerTools.refresh(this);
    java.util.List<com.deepseekharness.app.module.ContainerTools.Tool> tools =
        com.deepseekharness.app.module.ContainerTools.load(this);

    android.widget.ScrollView scroll = new android.widget.ScrollView(this);
    scroll.setVerticalScrollBarEnabled(false);
    LinearLayout list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    list.setPadding(dp(4), dp(4), dp(4), dp(4));
    scroll.addView(list);

    list.addView(pickerSectionLabel(UiText.choose("容器里的 DSH", "DSH in the container")));
    list.addView(
        pickerRow(
            UiText.choose("容器工作台", "Container workspace"),
            UiText.choose(
                "启动容器里的 DSH Web，就绪后进工作台",
                "Start the DSH web inside the container, then open the workspace"),
            com.deepseekharness.app.util.HomeLayoutStore.ACTION_WEB.equals(current),
            () -> {
              applyLaunchTarget(com.deepseekharness.app.util.HomeLayoutStore.ACTION_WEB);
              androidx.appcompat.app.AlertDialog open = dialogRef.get();
              if (open != null) open.dismiss();
            }));

    list.addView(pickerSectionLabel(UiText.choose("容器里的程序", "Programs in the container")));
    if (tools.isEmpty()) {
      TextView empty = new TextView(this);
      empty.setText(
          UiText.choose(
              "还没有登记容器程序。点下面的「新建容器程序」，或在容器里写 " + "~/.dsha-launch/<id>.json。",
              "No container programs registered yet. Tap \"New container program\" below, or "
                  + "write ~/.dsha-launch/<id>.json in the container."));
      empty.setTextColor(getColor(R.color.shell_text_muted));
      empty.setTextSize(12);
      empty.setPadding(dp(8), dp(2), dp(8), dp(6));
      list.addView(empty);
    } else {
      for (com.deepseekharness.app.module.ContainerTools.Tool tool : tools) {
        String action = com.deepseekharness.app.util.HomeLayoutStore.ACTION_TOOL_PREFIX + tool.id;
        boolean running = com.deepseekharness.app.module.ContainerTools.isRunning(tool.id);
        String state = running ? UiText.choose("运行中 · ", "Running · ") : "";
        String sub =
            state
                + (tool.note.isEmpty() ? "" : tool.note + (tool.port > 0 ? " · " : ""))
                + (tool.port > 0 ? UiText.choose("端口 ", "port ") + tool.port : "");
        android.view.View row =
            pickerRow(
                tool.name,
                sub,
                current.equals(action),
                () -> {
                  applyLaunchTarget(action);
                  androidx.appcompat.app.AlertDialog open = dialogRef.get();
                  if (open != null) open.dismiss();
                });
        // 长按 = 编辑这条登记（壳内直接改，不用进容器手写文件）。
        row.setOnLongClickListener(
            v -> {
              androidx.appcompat.app.AlertDialog open = dialogRef.get();
              if (open != null) open.dismiss();
              showContainerProgramEditor(
                  tool,
                  () -> {
                    render();
                    showLaunchTargetPicker();
                  });
              return true;
            });
        list.addView(row);
      }
    }

    TextView create = new TextView(this);
    create.setText(UiText.choose("＋ 新建容器程序", "＋ New container program"));
    create.setTextColor(getColor(R.color.shell_accent));
    create.setTextSize(13);
    create.setPadding(dp(10), dp(10), dp(10), dp(8));
    UiMotion.pressScale(create);
    create.setOnClickListener(
        v -> {
          androidx.appcompat.app.AlertDialog open = dialogRef.get();
          if (open != null) open.dismiss();
          showContainerProgramEditor(
              null,
              () -> {
                render();
                showLaunchTargetPicker();
              });
        });
    list.addView(create);

    TextView footer = new TextView(this);
    footer.setText(
        UiText.choose(
            "程序命令在乌班图容器里执行，输出落容器 /tmp/dsha-tool-<id>.log。" + "长按程序可编辑或删除；运行中的程序在主页按启动键会先停旧实例再重启。",
            "Program commands run in the Ubuntu container (output: /tmp/dsha-tool-<id>.log). "
                + "Long-press a program to edit or delete it; pressing start again restarts it."));
    footer.setTextColor(getColor(R.color.shell_text_muted));
    footer.setTextSize(11);
    footer.setPadding(dp(8), dp(6), dp(8), dp(2));
    list.addView(footer);

    androidx.appcompat.app.AlertDialog dialog =
        new DshaDialogBuilder(this)
            .setTitle(UiText.choose("启动入口", "Launch target"))
            .setView(scroll)
            .setNegativeButton(UiText.choose("取消", "Cancel"), null)
            .create();
    dialogRef.set(dialog);
    dialog.show();
  }

  /**
   * 容器程序编辑器：新建（{@code existing == null}）或编辑一条 {@code ~/.dsha-launch} 登记。
   *
   * <p>字段与容器里的 JSON 一一对应（id / 名称 / 命令 / 端口 / 说明）；保存走
   * {@link com.deepseekharness.app.module.ContainerTools#save}，校验和容器写文件完全同一套。
   * 编辑时另给「删除」入口，删除会连同运行实例一起停掉。
   */
  private void showContainerProgramEditor(
      com.deepseekharness.app.module.ContainerTools.Tool existing, Runnable onSaved) {
    showContainerProgramEditor(existing, onSaved, null, null, null, null, null);
  }

  /**
   * 打开容器程序表单；{@code typed*} 非空时优先用它们回填——保存失败重开时
   * 保留用户刚输入的内容（新建态 existing 为 null，之前的实现会把输入全丢掉）。
   */
  private void showContainerProgramEditor(
      com.deepseekharness.app.module.ContainerTools.Tool existing,
      Runnable onSaved,
      String typedId,
      String typedName,
      String typedCommand,
      String typedPort,
      String typedNote) {
    boolean editing = existing != null;
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(20), dp(8), dp(20), dp(4));

    android.widget.EditText idField = programField(form, "id（小写字母/数字/._-）", "id (lowercase, ._-)");
    android.widget.EditText nameField = programField(form, "显示名称", "Display name");
    android.widget.EditText commandField =
        programField(form, "容器内执行的命令", "Command to run in the container");
    android.widget.EditText portField = programField(form, "端口（可空，1024-65535）", "Port (optional)");
    android.widget.EditText noteField = programField(form, "说明（可空）", "Note (optional)");
    idField.setText(typedId != null ? typedId : editing ? existing.id : "");
    nameField.setText(typedName != null ? typedName : editing ? existing.name : "");
    commandField.setText(typedCommand != null ? typedCommand : editing ? existing.command : "");
    portField.setText(
        typedPort != null
            ? typedPort
            : editing && existing.port > 0 ? String.valueOf(existing.port) : "");
    noteField.setText(typedNote != null ? typedNote : editing ? existing.note : "");
    // 编辑时 id 是文件身份，改它等于换一条登记；仍然允许，但明确提示。
    if (editing) {
      TextView idHint = new TextView(this);
      idHint.setText(
          UiText.choose(
              "改 id 会删除旧登记、按新 id 另存一条。",
              "Changing the id deletes the old registration and saves a new one."));
      idHint.setTextColor(getColor(R.color.shell_text_muted));
      idHint.setTextSize(11);
      form.addView(idHint);
    }
    android.widget.ScrollView scroll = new android.widget.ScrollView(this);
    scroll.addView(form);

    DshaDialogBuilder builder = new DshaDialogBuilder(this);
    builder.setTitle(
        editing
            ? UiText.choose("编辑容器程序", "Edit container program")
            : UiText.choose("新建容器程序", "New container program"));
    builder.setView(scroll);
    builder.setNegativeButton(UiText.choose("取消", "Cancel"), null);
    builder.setPositiveButton(
        UiText.choose("保存", "Save"),
        (dialog, which) -> {
          int port = 0;
          String rawPort = portField.getText().toString().trim();
          if (!rawPort.isEmpty()) {
            try {
              port = Integer.parseInt(rawPort);
            } catch (NumberFormatException error) {
              port = -1;
            }
          }
          try {
            com.deepseekharness.app.module.ContainerTools.save(
                this,
                editing ? existing.id : "",
                idField.getText().toString(),
                nameField.getText().toString(),
                commandField.getText().toString(),
                port,
                noteField.getText().toString());
          } catch (IllegalArgumentException error) {
            android.widget.Toast.makeText(
                    this,
                    UiText.choose("没有保存：" + error.getMessage(), "Not saved: " + error.getMessage()),
                    android.widget.Toast.LENGTH_LONG)
                .show();
            // 保存失败就重新打开表单，用用户刚输入的值回填——真的不丢输入。
            showContainerProgramEditor(
                existing,
                onSaved,
                idField.getText().toString(),
                nameField.getText().toString(),
                commandField.getText().toString(),
                rawPort,
                noteField.getText().toString());
            return;
          }
          android.widget.Toast.makeText(
                  this,
                  UiText.choose("已保存到容器 ~/.dsha-launch/", "Saved to ~/.dsha-launch/"),
                  android.widget.Toast.LENGTH_SHORT)
              .show();
          onSaved.run();
        });
    if (editing) {
      builder.setNeutralButton(
          UiText.choose("删除", "Delete"),
          (dialog, which) ->
              new DshaDialogBuilder(this)
                  .setTitle(UiText.choose("删除这条容器程序？", "Delete this container program?"))
                  .setMessage(
                      UiText.choose(
                          "会删除容器里的 ~/.dsha-launch/" + existing.id + ".json；如果它正在运行也会被停止。",
                          "This removes ~/.dsha-launch/"
                              + existing.id
                              + ".json from the container and stops it if running."))
                  .setNegativeButton(UiText.choose("取消", "Cancel"), null)
                  .setPositiveButton(
                      UiText.choose("删除", "Delete"),
                      (d, w) -> {
                        com.deepseekharness.app.module.ContainerTools.delete(this, existing.id);
                        if (com.deepseekharness.app.util.HomeLayoutStore.isToolAction(
                            com.deepseekharness.app.util.HomeLayoutStore.launchAction(this)))
                          if (com.deepseekharness.app.util.HomeLayoutStore.toolIdOf(
                                  com.deepseekharness.app.util.HomeLayoutStore.launchAction(this))
                              .equals(existing.id))
                            applyLaunchTarget(
                                com.deepseekharness.app.util.HomeLayoutStore.ACTION_WEB);
                        render();
                        onSaved.run();
                      })
                  .show());
    }
    builder.show();
  }

  /** 编辑器里的一行：小标签 + 输入框，加进表单。 */
  private android.widget.EditText programField(LinearLayout form, String labelZh, String labelEn) {
    TextView label = new TextView(this);
    label.setText(UiText.choose(labelZh, labelEn));
    label.setTextColor(getColor(R.color.shell_text_secondary));
    label.setTextSize(12);
    label.setPadding(0, dp(8), 0, 0);
    form.addView(label);
    android.widget.EditText input = new android.widget.EditText(this);
    input.setTextSize(13);
    input.setSingleLine(true);
    form.addView(input);
    return input;
  }

  private void applyLaunchTarget(String action) {
    try {
      com.deepseekharness.app.util.HomeLayoutStore.setLaunchAction(this, action);
      moduleDirty();
      render();
    } catch (IllegalArgumentException ignored) {
      android.widget.Toast.makeText(
              this,
              UiText.choose("这个启动入口已经不存在了", "This launch target no longer exists"),
              android.widget.Toast.LENGTH_SHORT)
          .show();
    }
  }

  private TextView pickerSectionLabel(String text) {
    TextView label = new TextView(this);
    label.setText(text);
    label.setTextColor(getColor(R.color.shell_text_muted));
    label.setTextSize(11);
    label.setPadding(dp(8), dp(10), dp(8), dp(4));
    return label;
  }

  /** 选择器一行：标题 + 副标题；选中态描边加 accent 色与对勾。 */
  private android.view.View pickerRow(
      String title, String subtitle, boolean selected, Runnable onPick) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    row.setPadding(dp(10), dp(9), dp(10), dp(9));
    android.graphics.drawable.GradientDrawable background =
        new android.graphics.drawable.GradientDrawable();
    background.setCornerRadius(dp(12));
    if (selected) {
      background.setColor((getColor(R.color.shell_accent) & 0x00FFFFFF) | 0x14000000);
      background.setStroke(dp(1), getColor(R.color.shell_accent));
    } else {
      background.setColor(0x00000000);
    }
    row.setBackground(background);
    LinearLayout textHost = new LinearLayout(this);
    textHost.setOrientation(LinearLayout.VERTICAL);
    textHost.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
    TextView titleView = new TextView(this);
    titleView.setText(title);
    titleView.setTextSize(14);
    titleView.setTextColor(getColor(selected ? R.color.shell_accent : R.color.shell_text));
    textHost.addView(titleView);
    if (subtitle != null && !subtitle.isEmpty()) {
      TextView subView = new TextView(this);
      subView.setText(subtitle);
      subView.setTextSize(12);
      subView.setTextColor(getColor(R.color.shell_text_secondary));
      subView.setPadding(0, dp(2), 0, 0);
      textHost.addView(subView);
    }
    row.addView(textHost);
    if (selected) {
      TextView check = new TextView(this);
      check.setText("✓");
      check.setTextSize(14);
      check.setTextColor(getColor(R.color.shell_accent));
      row.addView(check);
    }
    UiMotion.pressScale(row);
    row.setOnClickListener(
        v -> {
          onPick.run();
        });
    return row;
  }

  /** 记录模块状态已变，回主页时刷新卡片；完整生效仍建议重启。 */
  private void moduleDirty() {
    getSharedPreferences("dsha_home_layout", 0).edit().putBoolean("modules_dirty", true).apply();
  }

  private void promptRestart(String name, boolean enabled) {
    new DshaDialogBuilder(this)
        .setTitle(
            enabled
                ? UiText.choose("已启用 · " + name, "Enabled · " + name)
                : UiText.choose("已停用 · " + name, "Disabled · " + name))
        .setMessage(
            UiText.choose(
                "主页卡片会立即更新；运行环境、底栏以及已经打开的模块页面需要重启 App 才能完全切换。",
                "Home cards update immediately. The runtime, bottom bar and already-open module pages "
                    + "switch completely after restarting the app."))
        .setNegativeButton(UiText.choose("稍后", "Later"), null)
        .setPositiveButton(UiText.choose("立即重启", "Restart now"), (d, w) -> restartApp())
        .show();
  }

  /** 真正重开一个干净的任务：先清空本任务的所有页面再回主页，避免重启后又落回模块中心。 */
  private void restartApp() {
    android.content.Intent intent =
        new android.content.Intent(this, com.deepseekharness.app.ui.MainActivity.class)
            .addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("fresh_start", true);
    // CLEAR_TASK 会先结束本任务里的页面（含模块中心），这里再 finishAffinity 会把新主页一起关掉。
    startActivity(intent);
  }

  /** 行内小按钮：实心底 + 描边，和页面其它按钮一个观感，不再是透明玻璃。 */
  private TextView chip(String text, int color, Runnable action) {
    return chipOf(this, text, color, action);
  }

  /** 静态版行内小按钮：定时任务区在没有 Activity 实例时（测试）也能构造同一观感。 */
  static TextView chipOf(android.content.Context context, String text, int color, Runnable action) {
    TextView chip = new TextView(context);
    chip.setText(text);
    chip.setTextSize(12);
    chip.setTextColor(color);
    chip.setGravity(android.view.Gravity.CENTER);
    chip.setBackgroundResource(R.drawable.bg_chip_solid);
    chip.setPadding(dpOf(context, 10), dpOf(context, 5), dpOf(context, 10), dpOf(context, 5));
    UiMotion.pressScale(chip);
    chip.setOnClickListener(v -> action.run());
    return chip;
  }

  private LinearLayout.LayoutParams rowChipParams() {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
    params.leftMargin = dp(6);
    return params;
  }

  /** 宿主认识的扩展点；其余一律标「未支持」，让用户知道是宿主版本旧了。 */
  private static final java.util.Set<String> KNOWN_SURFACES =
      new java.util.HashSet<>(
          java.util.Arrays.asList(
              com.deepseekharness.app.module.ModuleCard.CARD_SURFACE,
              com.deepseekharness.app.module.ModuleCard.OVERLAY_SURFACE,
              com.deepseekharness.app.module.ModuleCard.THEME_SURFACE,
              com.deepseekharness.app.module.ModuleCard.NAV_SURFACE));

  /** 一张卡片到底会做什么，写清楚，用户才知道自己在装什么。 */
  private String capabilities(com.deepseekharness.app.module.ModuleCard card) {
    java.util.List<String> caps = new java.util.ArrayList<>();
    if (!card.bind.isEmpty()) caps.add(UiText.choose("读状态", "live data"));
    if (!card.fields.isEmpty()) caps.add(UiText.choose("输入", "input"));
    boolean script = false;
    boolean run = false;
    for (com.deepseekharness.app.module.ModuleCard.Action action : card.actions) {
      if ("script".equals(action.type)) script = true;
      if ("exec".equals(action.type)) run = true;
      if ("sequence".equals(action.type)) {
        if (action.value.contains("script:")) script = true;
        if (action.value.contains("exec:")) run = true;
      }
    }
    if (card.overlay != null) caps.add(UiText.choose("悬浮层", "overlay"));
    if (card.theme != null) caps.add(UiText.choose("主题", "theme"));
    if (card.view != null) caps.add(UiText.choose("自绘界面", "custom view"));
    for (String surface : card.provides.keySet()) {
      if (!KNOWN_SURFACES.contains(surface))
        caps.add(UiText.choose("未支持:" + surface, "unsupported:" + surface));
    }
    if (script) caps.add(UiText.choose("脚本", "script"));
    if (run) caps.add(UiText.choose("执行命令", "shell"));
    if (!card.image.isEmpty()) caps.add(UiText.choose("立绘", "art"));
    if (caps.isEmpty()) caps.add(UiText.choose("展示", "display"));
    StringBuilder builder = new StringBuilder();
    for (String cap : caps) {
      if (builder.length() > 0) builder.append(" · ");
      builder.append(cap);
    }
    boolean allowed = com.deepseekharness.app.module.ModuleExec.isAllowed(this, card.id);
    builder
        .append(" · ")
        .append(
            allowed
                ? UiText.choose("已授权运行", "run allowed")
                : UiText.choose("未授权运行", "run blocked"));
    return builder.toString();
  }

  private int dp(int value) {
    return dpOf(this, value);
  }

  private static int dpOf(android.content.Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
