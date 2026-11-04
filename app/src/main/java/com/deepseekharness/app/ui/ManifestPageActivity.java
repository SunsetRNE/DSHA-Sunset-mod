package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.module.ModuleManifest;
import com.deepseekharness.app.module.ModuleRegistry;
import com.deepseekharness.app.util.UiText;

/**
 * 声明式清单的二级页面渲染器：把 {@code contributes.pages} + {@code sections/rows} 画成可点列表。
 *
 * <p>只支持清单自己声明的页内目标（page:/system:）和宿主已实现的安全动作；
 * 不执行清单里的任何代码，也不允许跳到任意 Activity。
 */
public final class ManifestPageActivity extends AppCompatActivity {

  private static final String EXTRA_MODULE = "module_id";
  private static final String EXTRA_PAGE = "page_id";

  private ModuleManifest manifest;

  public static void open(Context context, String moduleId, String pageId) {
    if (context == null || moduleId == null || pageId == null) return;
    Intent intent =
        new Intent(context, ManifestPageActivity.class)
            .putExtra(EXTRA_MODULE, moduleId)
            .putExtra(EXTRA_PAGE, pageId);
    if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(intent);
  }

  @Override
  protected void onCreate(android.os.Bundle state) {
    super.onCreate(state);
    String moduleId = getIntent().getStringExtra(EXTRA_MODULE);
    String pageId = getIntent().getStringExtra(EXTRA_PAGE);
    manifest = ModuleRegistry.load(this).modules().get(moduleId);
    ModuleManifest.Page page = null;
    if (manifest != null)
      for (ModuleManifest.Page item : manifest.pages()) if (item.id().equals(pageId)) page = item;
    if (manifest == null || page == null) {
      finish();
      return;
    }
    CardPage shell =
        new CardPage(this, page.title(), manifest.name() + " · v" + manifest.version());
    boolean any = false;
    for (ModuleManifest.Section section : manifest.sections()) {
      if (!pageId.equals(section.page())) continue;
      any = true;
      shell.label(section.title());
      LinearLayout card = shell.card();
      for (ModuleManifest.Row row : section.rows()) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.VERTICAL);
        line.setPadding(shell.dp(4), shell.dp(9), shell.dp(4), shell.dp(9));
        TextView title = new TextView(this);
        title.setText(row.title());
        title.setTextSize(14);
        title.setTextColor(getColor(R.color.shell_text));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        line.addView(title, new LinearLayout.LayoutParams(-1, -2));
        if (!row.subtitle().isEmpty()) {
          TextView subtitle = new TextView(this);
          subtitle.setText(row.subtitle());
          subtitle.setTextSize(12);
          subtitle.setTextColor(getColor(R.color.shell_text_secondary));
          subtitle.setPadding(0, shell.dp(3), 0, 0);
          line.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        }
        line.setOnClickListener(v -> run(row.action()));
        UiMotion.pressScale(line);
        card.addView(line, new LinearLayout.LayoutParams(-1, -2));
      }
    }
    if (!any) {
      TextView empty = new TextView(this);
      empty.setText(UiText.choose("这个页面还没有任何 section 行。", "This page has no section rows yet."));
      empty.setTextColor(getColor(R.color.shell_text_secondary));
      empty.setTextSize(12);
      empty.setPadding(shell.dp(4), shell.dp(12), shell.dp(4), shell.dp(12));
      shell.content.addView(empty, new LinearLayout.LayoutParams(-1, -2));
    }
    shell.button(shell.footer, UiText.choose("返回", "Back"), false, this::finish);
    setContentView(shell.root);
  }

  private void run(ModuleManifest.Action action) {
    if (action == null) return;
    if (action.type() == ModuleManifest.ActionType.MANAGE) {
      toast(UiText.choose("管理动作暂未接入宿主", "Manage action is not wired yet"));
      return;
    }
    String target = action.target();
    if (target == null) return;
    if (target.startsWith("page:")) {
      String rest = target.substring("page:".length());
      int slash = rest.indexOf('/');
      if (slash <= 0) return;
      String module = rest.substring(0, slash);
      String page = rest.substring(slash + 1);
      if (!manifest.id().equals(module)) {
        toast(UiText.choose("不能跳到别的模块页面", "Cannot open another module's page"));
        return;
      }
      open(this, module, page);
      return;
    }
    if ("system:terminal".equals(target)) {
      MainActivity main = MainActivity.active();
      if (main != null) main.openTerminal();
      else startActivity(MainActivity.homeIntent(this).putExtra("open_terminal", true));
      return;
    }
    if ("system:diagnostics".equals(target)) {
      startActivity(new Intent(this, DiagnosticActivity.class));
      return;
    }
    toast(UiText.choose("宿主未实现这个系统入口：", "Host has not wired this system target: ") + target);
  }

  private void toast(String text) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
  }
}
