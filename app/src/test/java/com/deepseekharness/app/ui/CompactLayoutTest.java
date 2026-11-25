package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.skills.SkillCatalogueFixture;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

/**
 * 手机竖屏（360dp）与宽屏的布局回归：方案 A 改过的子页在窄屏下不横向溢出，技能页按宽度切换单列 / 并排。
 *
 * <p>mdpi 下 1dp = 1px，直接按 dp 宽度测量。只验证布局，不验证真机渲染。
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = CompactLayoutTest.App.class,
    qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class CompactLayoutTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  public static final class Host extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle saved) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
      FrameLayout content = new FrameLayout(this);
      content.setId(R.id.fragment_container);
      setContentView(content);
    }
  }

  private ActivityController<Host> host;

  @After
  public void tearDown() {
    if (host != null) host.pause().stop().destroy();
  }

  private Host start(Fragment page) {
    host = Robolectric.buildActivity(Host.class).setup().visible();
    host.get()
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, page)
        .commitNow();
    ShadowLooper.shadowMainLooper().idle();
    return host.get();
  }

  private static void layout(View root, int width) {
    for (int pass = 0; pass < 3; pass++) {
      root.measure(
          View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST));
      root.layout(0, 0, width, root.getMeasuredHeight());
      ShadowLooper.shadowMainLooper().idle();
    }
  }

  /** 所有可见子视图的左右边界都在页面宽度内（不计滚动容器内部的纵向延伸）。 */
  private static void assertNoHorizontalOverflow(View root, int width) {
    List<String> overflow = new ArrayList<>();
    collectOverflow(root, 0, width, overflow);
    assertTrue("horizontal overflow: " + overflow, overflow.isEmpty());
  }

  private static void collectOverflow(View view, int left, int width, List<String> out) {
    if (view.getVisibility() != View.VISIBLE) return;
    int start = left + view.getLeft();
    int end = start + view.getWidth();
    if (start < -1 || end > width + 1) {
      String label =
          view instanceof TextView ? String.valueOf(((TextView) view).getText()) : view.toString();
      out.add(label + " [" + start + "," + end + "]");
      return;
    }
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        collectOverflow(group.getChildAt(i), start - view.getScrollX(), width, out);
  }

  private static List<View> tagged(View view, String prefix, List<View> out) {
    if (view.getTag() instanceof String tag && tag.startsWith(prefix)) out.add(view);
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) tagged(group.getChildAt(i), prefix, out);
    return out;
  }

  private SkillsFragment skills(int width, int count) throws Exception {
    return skills(width, count, 0, 0);
  }

  private SkillsFragment skills(int width, int count, int disabled, int trashed) throws Exception {
    SkillsFragment page = new SkillsFragment();
    page.disableAutoLoadForTest();
    start(page);
    String[] names = new String[count];
    for (int i = 0; i < count; i++) names[i] = "fixture-skill-" + (i + 1);
    page.showCatalogueForTest(SkillCatalogueFixture.withStates(1, disabled, trashed, names));
    layout(page.requireView(), width);
    return page;
  }

  private static int checkedSwitches(View root, boolean checked) {
    int count = 0;
    for (View view : tagged(root, "skill.switch:", new ArrayList<>()))
      if (((android.widget.CompoundButton) view).isChecked() == checked) count++;
    return count;
  }

  @Test
  public void skillsPhonePortraitIsSingleColumnWithoutOverflow() throws Exception {
    SkillsFragment page = skills(360, 3);
    View root = page.requireView();
    assertEquals(3, tagged(root, "skill:", new ArrayList<>()).size());
    // 单列：没有右侧详情卡片，行内带箭头，详情走底部弹层。
    assertEquals(0, tagged(root, "skills.detail", new ArrayList<>()).size());
    assertNoHorizontalOverflow(root, 360);
  }

  @Test
  public void skillsPhoneShowsSwitchesDisabledGroupAndRecentlyDeleted() throws Exception {
    SkillsFragment page = skills(360, 3, 1, 2);
    View root = page.requireView();
    assertEquals(3, tagged(root, "skill:", new ArrayList<>()).size());
    assertEquals(2, checkedSwitches(root, true));
    assertEquals(1, checkedSwitches(root, false));
    assertEquals(2, tagged(root, "skills.count", new ArrayList<>()).size());
    assertEquals(1, tagged(root, "skills.trash", new ArrayList<>()).size());
    assertNoHorizontalOverflow(root, 360);
  }

  @Test
  @Config(qualifiers = "zh-rCN-w720dp-h1000dp-mdpi")
  public void skillsTabletPortraitIsSingleColumnWideRows() throws Exception {
    // 平板竖屏（设置子页约 700dp）：单列宽行，详情走居中弹层，不挤成两栏。
    SkillsFragment page = skills(720, 3, 1, 1);
    View root = page.requireView();
    assertEquals(3, tagged(root, "skill:", new ArrayList<>()).size());
    assertEquals(0, tagged(root, "skills.detail", new ArrayList<>()).size());
    assertEquals(3, tagged(root, "skill.switch:", new ArrayList<>()).size());
    assertNoHorizontalOverflow(root, 720);
  }

  @Test
  @Config(qualifiers = "zh-rCN-w900dp-h700dp-mdpi")
  public void skillsWideShowsListAndDetailSideBySide() throws Exception {
    SkillsFragment page = skills(900, 3);
    View root = page.requireView();
    List<View> rows = tagged(root, "skill:", new ArrayList<>());
    assertEquals(3, rows.size());
    List<View> detail = tagged(root, "skills.detail", new ArrayList<>());
    assertEquals(1, detail.size());
    // 并排：详情在列表右侧，首项默认选中；详情带状态卡与删除入口。
    int[] rowAt = new int[2], detailAt = new int[2];
    rows.get(0).getLocationInWindow(rowAt);
    detail.get(0).getLocationInWindow(detailAt);
    assertTrue(detailAt[0] > rowAt[0] + rows.get(0).getWidth() - 1);
    assertTrue(rows.get(0).isSelected());
    assertEquals(1, tagged(detail.get(0), "skills.state", new ArrayList<>()).size());
    assertEquals(1, tagged(detail.get(0), "skills.delete", new ArrayList<>()).size());
    assertNoHorizontalOverflow(root, 900);
  }

  @Test
  public void skillTierThresholds() {
    assertEquals(SkillsFragment.Tier.NARROW, SkillsFragment.tierFor(599));
    assertEquals(SkillsFragment.Tier.MEDIUM, SkillsFragment.tierFor(600));
    assertEquals(SkillsFragment.Tier.MEDIUM, SkillsFragment.tierFor(705));
    assertEquals(SkillsFragment.Tier.WIDE, SkillsFragment.tierFor(780));
  }

  @Test
  public void skillsEmptyStateFitsPhone() throws Exception {
    SkillsFragment page = skills(360, 0);
    assertNoHorizontalOverflow(page.requireView(), 360);
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(qualifiers = "zh-rCN-w900dp-h700dp-mdpi")
  public void wideSkillBindsOnlyThePreviewOfTheExactLargeRemovalSample() throws Exception {
    byte[] sample = SkillCatalogueFixture.removalSample();
    assertEquals(1_048_557, sample.length);
    assertEquals(
        "38536f1cef5a1925c7fc5388d4be5f4db96854cd4b13b9cbaf36c759b8414e92",
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sample)));
    long started = System.nanoTime();
    SkillsFragment page = skillsWithSource(sample, 900);
    TextView source = skillSource(page.requireView());
    assertNotNull(source);
    assertEquals(8, source.getMaxLines());
    System.out.printf(
        "SKILL_PREVIEW\t%d units\t%d ms%n",
        source.getText().length(), (System.nanoTime() - started) / 1_000_000);
    assertTrue("Collapsed TextView received the full document", source.getText().length() <= 768);
    assertTrue(new String(sample, StandardCharsets.UTF_8).startsWith(source.getText().toString()));
    View root = page.requireView();
    tagged(root, "skills.source.expand", new ArrayList<>()).get(0).performClick();
    View next = tagged(root, "skills.source.next", new ArrayList<>()).get(0);
    StringBuilder full = new StringBuilder();
    int pages = 0;
    while (true) {
      assertTrue(++pages <= 400);
      assertTrue(source.getText().length() > 0 && source.getText().length() <= 4096);
      full.append(source.getText());
      if (!next.isEnabled()) break;
      next.performClick();
    }
    assertTrue(new String(sample, StandardCharsets.UTF_8).contentEquals(full));
    System.out.printf("SKILL_FULL\t%d pages\t%d units%n", pages, full.length());
  }

  @Test
  @Config(qualifiers = "zh-rCN-w900dp-h700dp-mdpi")
  public void skillBusyTransitionKeepsTheExistingDetailAndGuardsMutationControls()
      throws Exception {
    SkillsFragment page = skillsWithSource(SkillCatalogueFixture.removalSample(), 900);
    View root = page.requireView();
    View detail = tagged(root, "skills.detail", new ArrayList<>()).get(0);
    View delete = tagged(root, "skills.delete", new ArrayList<>()).get(0);
    View toggle = tagged(root, "skill.switch:", new ArrayList<>()).get(0);
    var model = (SkillsFragment.Model) ReflectionHelpers.getField(page, "model");
    long started = System.nanoTime();
    model.busy.setValue(true);
    System.out.printf(
        "SKILL_BUSY\troot unchanged=%s\t%d ms%n",
        detail == tagged(root, "skills.detail", new ArrayList<>()).get(0),
        (System.nanoTime() - started) / 1_000_000);
    assertSame(detail, tagged(root, "skills.detail", new ArrayList<>()).get(0));
    assertFalse(delete.isEnabled());
    assertFalse(toggle.isEnabled());
    model.busy.setValue(false);
    assertSame(detail, tagged(root, "skills.detail", new ArrayList<>()).get(0));
    assertTrue(delete.isEnabled());
    assertTrue(toggle.isEnabled());
  }

  @Test
  @Config(qualifiers = "zh-rCN-w900dp-h700dp-mdpi")
  public void skillFullSourcePagesKeepAllTextAndSplitNoSurrogatePairs() throws Exception {
    String header = "---\nname: long-line\ndescription: Inert example\n---\n";
    String emoji = "\uD83D\uDE00";
    String doc =
        header
            + "x".repeat(767 - header.length())
            + emoji
            + "x".repeat(3326)
            + emoji
            + "y".repeat(6000);
    SkillsFragment page = skillsWithSource(doc.getBytes(StandardCharsets.UTF_8), 900);
    View root = page.requireView();
    TextView source = skillSource(root);
    assertNotNull(source);
    assertEquals(767, source.getText().length());
    tagged(root, "skills.source.expand", new ArrayList<>()).get(0).performClick();
    View next = tagged(root, "skills.source.next", new ArrayList<>()).get(0);
    View previous = tagged(root, "skills.source.previous", new ArrayList<>()).get(0);
    String first = source.getText().toString();
    assertFalse(previous.isEnabled());
    assertTrue(next.isEnabled());
    next.performClick();
    previous.performClick();
    assertEquals(first, source.getText().toString());
    StringBuilder gathered = new StringBuilder();
    while (true) {
      CharSequence chunk = source.getText();
      assertTrue(chunk.length() > 0 && chunk.length() <= 4096);
      assertFalse(Character.isLowSurrogate(chunk.charAt(0)));
      assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
      gathered.append(chunk);
      if (!next.isEnabled()) break;
      next.performClick();
    }
    assertTrue("The complete original must remain readable", doc.contentEquals(gathered));
    tagged(root, "skills.source.expand", new ArrayList<>()).get(0).performClick();
    assertEquals(767, source.getText().length());
    host.recreate().visible();
    ShadowLooper.shadowMainLooper().idle();
    var recreated =
        (SkillsFragment)
            host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container);
    assertNotNull(recreated);
    View restoredRoot = recreated.requireView();
    assertNotSame(root, restoredRoot);
    layout(restoredRoot, 900);
    assertEquals(767, skillSource(restoredRoot).getText().length());
    assertEquals(1, tagged(restoredRoot, "skill:long-line", new ArrayList<>()).size());
  }

  @Test
  public void smallSkillStillExpandsInOneViewAndCopiesTheCommand() throws Exception {
    String small =
        "---\nname: small-neighbor\ndescription: Inert example\n---\n" + "text\n".repeat(10);
    SkillsFragment page = skillsWithSource(small.getBytes(StandardCharsets.UTF_8), 360);
    tagged(page.requireView(), "skill:small-neighbor", new ArrayList<>()).get(0).performClick();
    var sheet = ShadowDialog.getLatestDialog();
    assertNotNull(sheet);
    View root = sheet.getWindow().getDecorView();
    TextView source = skillSource(root);
    assertNotNull(source);
    action(root, "展开全文", "Show all").performClick();
    assertEquals(small, source.getText().toString());
    assertTrue(source.isTextSelectable());
    action(root, "复制命令", "Copy command").performClick();
    var clipboard = host.get().getSystemService(ClipboardManager.class);
    assertEquals("/small-neighbor", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
    sheet.dismiss();
    assertNotNull(tagged(page.requireView(), "skill:small-neighbor", new ArrayList<>()).get(0));
  }

  private SkillsFragment skillsWithSource(byte[] sample, int width) throws Exception {
    SkillsFragment page = new SkillsFragment();
    page.disableAutoLoadForTest();
    start(page);
    page.showCatalogueForTest(SkillCatalogueFixture.withSource(sample));
    layout(page.requireView(), width);
    return page;
  }

  private static TextView skillSource(View view) {
    if (view instanceof TextView text && text.getText().toString().startsWith("---\nname:"))
      return text;
    if (view instanceof ViewGroup group)
      for (int index = 0; index < group.getChildCount(); index++) {
        TextView found = skillSource(group.getChildAt(index));
        if (found != null) return found;
      }
    return null;
  }

  private static TextView action(View view, String chinese, String english) {
    if (view instanceof TextView text && view.isClickable()) {
      var label = text.getText().toString();
      if (label.equals(chinese) || label.equals(english)) return text;
    }
    if (view instanceof ViewGroup group)
      for (int index = 0; index < group.getChildCount(); index++) {
        TextView found = action(group.getChildAt(index), chinese, english);
        if (found != null) return found;
      }
    return null;
  }

  @Test
  public void overlayPagePhonePortraitHasNoOverflow() {
    Host activity = start(new OverlayFragment());
    View root =
        activity
            .getSupportFragmentManager()
            .findFragmentById(R.id.fragment_container)
            .requireView();
    layout(root, 360);
    assertNoHorizontalOverflow(root, 360);
  }
}
