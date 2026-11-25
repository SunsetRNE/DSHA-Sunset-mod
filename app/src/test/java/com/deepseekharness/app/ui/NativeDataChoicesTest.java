package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.DocumentStreams;
import com.deepseekharness.app.backup.NativeBackupJobs;
import com.deepseekharness.app.util.UiText;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowDialog;

@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = NativeDataChoicesTest.App.class,
    shadows = {NativeDataChoicesTest.Jobs.class, NativeDataChoicesTest.Streams.class})
@LooperMode(LooperMode.Mode.PAUSED)
public final class NativeDataChoicesTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  @Implements(value = NativeBackupJobs.class, isInAndroidSdk = false)
  public static final class Jobs {
    static Set<String> restored;
    static String destination;
    static boolean key;
    static char[] submittedPassword;
    static int secureCalls, plainCalls;
    static Uri exportUri;

    @Implementation
    protected NativeBackupJobs.ExportRefusal exportProtected(
        com.deepseekharness.app.backup.NativeDataLocations.Selection selection,
        char[] password,
        Uri uri,
        String name,
        boolean rescue) {
      secureCalls++;
      exportUri = uri;
      submittedPassword = password.clone();
      return NativeBackupJobs.ExportRefusal.NONE;
    }

    @Implementation
    protected NativeBackupJobs.ExportRefusal exportWithReason(
        com.deepseekharness.app.backup.NativeDataLocations.Selection selection,
        Uri uri,
        String name,
        boolean rescue,
        byte[] diagnostics) {
      plainCalls++;
      return NativeBackupJobs.ExportRefusal.NONE;
    }

    @Implementation
    protected boolean prepareRestore(
        Uri uri, char[] supplied, Set<String> selected, boolean includeKey, String projects) {
      restored = selected;
      key = includeKey;
      destination = projects;
      return false;
    }
  }

  @Implements(value = DocumentStreams.class, isInAndroidSdk = false)
  public static final class Streams {
    static CountDownLatch queryStarted, releaseName;
    static int queries;
    static boolean main;

    @Implementation
    protected static InputStream input(ContentResolver resolver, Uri uri, BackupControl control) {
      return new ByteArrayInputStream("DSHABAK5".getBytes(StandardCharsets.US_ASCII));
    }

    @Implementation
    protected static Cursor optionalQuery(
        ContentResolver resolver, Uri uri, String[] columns, BackupControl control)
        throws IOException {
      queries++;
      main = Looper.myLooper() == Looper.getMainLooper();
      queryStarted.countDown();
      try {
        if (!releaseName.await(10, TimeUnit.SECONDS)) throw new IOException("query timed out");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException(e);
      }
      MatrixCursor result = new MatrixCursor(columns);
      result.addRow(new Object[] {"fixture.tar.gz"});
      return result;
    }
  }

  private static final String SOURCE = "content://test.documents/archive";
  private String language;

  @Before
  public void setUp() {
    language = UiText.language();
    UiText.setLanguage("zh");
    Streams.queryStarted = new CountDownLatch(1);
    Streams.releaseName = new CountDownLatch(1);
    Streams.queries = 0;
    Jobs.restored = null;
    Jobs.secureCalls = 0;
    Jobs.plainCalls = 0;
    Jobs.submittedPassword = null;
  }

  @After
  public void tearDown() {
    Streams.releaseName.countDown();
    UiText.setLanguage(language);
  }

  private static ShadowActivity launchedBy(NativeDataActivity page) {
    return Shadow.extract(page);
  }

  private static Intent intent() {
    return new Intent(RuntimeEnvironment.getApplication(), NativeDataActivity.class)
        .putExtra("data_mode", "backup");
  }

  private static View find(View view, Predicate<View> match) {
    if (match.test(view)) return view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View child = find(group.getChildAt(i), match);
        if (child != null) return child;
      }
    return null;
  }

  private static View textView(NativeDataActivity page, String text) {
    View view =
        find(
            page.findViewById(android.R.id.content),
            v -> v instanceof TextView label && text.contentEquals(label.getText()));
    assertNotNull(text, view);
    return view;
  }

  private static View card(NativeDataActivity page, String description) {
    View view =
        find(
            page.findViewById(android.R.id.content),
            v ->
                description.contentEquals(
                    v.getContentDescription() == null ? "" : v.getContentDescription()));
    assertNotNull(description, view);
    return view;
  }

  private static void click(View child) {
    while (!child.isClickable()) child = (View) child.getParent();
    assertTrue(child.isShown());
    child.performClick();
  }

  private static AlertDialog openExportDialog(NativeDataActivity page) {
    click(textView(page, "导出应用数据"));
    // Dialog.show dispatches OnShowListener on the main queue before a user can tap its button.
    ShadowLooper.idleMainLooper();
    return (AlertDialog) ShadowDialog.getLatestDialog();
  }

  private static com.deepseekharness.app.backup.NativeDataLocations.Selection confirmExport(
      NativeDataActivity page) {
    AlertDialog dialog = openExportDialog(page);
    var fields =
        (PortablePasswordFields)
            find(dialog.getWindow().getDecorView(), v -> v instanceof PortablePasswordFields);
    assertNotNull(fields);
    var pending = new ViewModelProvider(page).get(NativeDataActivity.Pending.class);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    assertTrue(dialog.isShowing());
    assertNotNull(fields.first.getError());
    assertFalse(pending.exportPrepared);
    fields.first.setText("a synthetic test password");
    fields.confirm.setText("another synthetic password");
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    assertTrue(dialog.isShowing());
    assertNotNull(fields.confirm.getError());
    assertFalse(pending.exportPrepared);
    fields.confirm.setText(fields.first.getText());
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    ShadowLooper.idleMainLooper();
    assertFalse(pending.plainExport);
    org.junit.Assert.assertArrayEquals("a synthetic test password".toCharArray(), pending.password);
    return pending.selection;
  }

  @Test
  public void displayedScopesChooseIndependentJobArguments() {
    Bundle saved = new Bundle();
    saved.putInt("restoreStep", 1);
    saved.putString("restoreUri", SOURCE);
    saved.putParcelableArrayList(
        "projectTrees",
        new java.util.ArrayList<>(
            java.util.List.of(Uri.parse("content://test.documents/tree/fixture"))));
    try (var host =
        Robolectric.buildActivity(NativeDataActivity.class, intent())
            .create(saved)
            .start()
            .resume()
            .visible()) {
      NativeDataActivity page = host.get();
      click(card(page, "设置"));
      click(card(page, "插件，用户插件；系统插件按当前 APK 重建"));
      click(textView(page, "开始预检"));
      assertTrue(SelectableCard.isSelected(card(page, "设置")));
      assertEquals(Set.of("settings"), Jobs.restored);
      click(card(page, "设置，profile 设置与工作目录配置"));
      click(textView(page, "包含原生 API Key（默认不包含）"));
      click(card(page, "所选项目文件"));
      click(textView(page, "容器主目录 /root"));
      click(textView(page, "开始预检"));
      assertEquals(Set.of("projects"), Jobs.restored);
      assertEquals("GUEST_HOME", Jobs.destination);
      assertFalse(Jobs.key);
      assertTrue(SelectableCard.isSelected(card(page, "设置，profile 设置与工作目录配置")));
      var selected = confirmExport(page);
      assertEquals("settings", selected.scope);
      assertTrue(selected.includeApiKey);
      assertFalse(selected.includeProviderCredentials);
      assertTrue(selected.documentProjects.isEmpty());
    }
  }

  @Test
  public void rotationDuringFilenameLookupBindsNewPageAndFreshOwnerRestoresOnlyChoices()
      throws Exception {
    Intent route = intent().putExtra("restore_uri", SOURCE);
    var host = Robolectric.buildActivity(NativeDataActivity.class, route).setup();
    ShadowLooper.idleMainLooper();
    assertTrue(Streams.queryStarted.await(4, TimeUnit.SECONDS));
    try {
      host.recreate().visible();
      Streams.releaseName.countDown();
      NativeDataActivity page = host.get();
      var pending = new ViewModelProvider(page).get(NativeDataActivity.Pending.class);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
      while (pending.restoreStep == 0 && System.nanoTime() < deadline) {
        ShadowLooper.idleMainLooper();
        Thread.sleep(10);
      }
      assertNotNull(textView(page, "fixture.tar.gz"));
      assertEquals(1, Streams.queries);
      assertFalse(Streams.main);
      click(textView(page, "恢复原生 API Key"));
      click(textView(page, "容器主目录 /root"));
      click(textView(page, "包含提供商凭据文件和 .env 文件"));
      EditText password =
          (EditText) find(page.findViewById(android.R.id.content), v -> v instanceof EditText);
      assertNotNull(password);
      password.setText("synthetic-input");
      Bundle saved = new Bundle();
      host.pause().saveInstanceState(saved).stop().destroy();
      try (var next =
          Robolectric.buildActivity(NativeDataActivity.class, route)
              .create(saved)
              .start()
              .restoreInstanceState(saved)
              .resume()
              .visible()) {
        page = next.get();
        assertTrue(((CheckBox) textView(page, "恢复原生 API Key")).isChecked());
        assertTrue(((RadioButton) textView(page, "容器主目录 /root")).isChecked());
        assertTrue(((CheckBox) textView(page, "包含提供商凭据文件和 .env 文件")).isChecked());
        assertFalse(((CheckBox) textView(page, "包含原生 API Key（默认不包含）")).isChecked());
        assertNotNull(textView(page, "fixture.tar.gz"));
        EditText fresh =
            (EditText) find(page.findViewById(android.R.id.content), v -> v instanceof EditText);
        assertEquals("", fresh.getText().toString());
        assertEquals(1, Streams.queries);
        assertNull(Jobs.restored);
        var selected = confirmExport(page);
        assertTrue(selected.includeProviderCredentials);
        assertFalse(selected.includeApiKey);
      }
    } finally {
      Streams.releaseName.countDown();
    }
  }

  @Test
  public void safResultAfterRotationUsesProtectedOperationAndWipesPendingPassword() {
    try (var host = Robolectric.buildActivity(NativeDataActivity.class, intent()).setup()) {
      var page = host.get();
      confirmExport(page);
      var launched = launchedBy(page).getNextStartedActivityForResult();
      assertEquals(Intent.ACTION_CREATE_DOCUMENT, launched.intent.getAction());
      var retained = new ViewModelProvider(page).get(NativeDataActivity.Pending.class);
      char[] owned = retained.password;
      host.recreate().visible();
      var fresh = host.get();
      Uri target = Uri.parse("content://synthetic.documents/portable");
      assertTrue(
          fresh
              .getActivityResultRegistry()
              .dispatchResult(
                  launched.requestCode,
                  android.app.Activity.RESULT_OK,
                  new Intent().setData(target)));
      ShadowLooper.idleMainLooper();
      assertEquals(1, Jobs.secureCalls);
      assertEquals(0, Jobs.plainCalls);
      assertEquals(target, Jobs.exportUri);
      assertArrayEquals("a synthetic test password".toCharArray(), Jobs.submittedPassword);
      assertNull(retained.password);
      assertArrayEquals(new char[owned.length], owned);
    }
  }

  @Test
  public void cancellingPickerWipesPasswordAndCompatibilityRequiresExplicitCheckbox() {
    try (var host = Robolectric.buildActivity(NativeDataActivity.class, intent()).setup()) {
      var page = host.get();
      confirmExport(page);
      var pending = new ViewModelProvider(page).get(NativeDataActivity.Pending.class);
      char[] owned = pending.password;
      var picker = launchedBy(page).getNextStartedActivityForResult();
      page.getActivityResultRegistry()
          .dispatchResult(picker.requestCode, android.app.Activity.RESULT_CANCELED, null);
      ShadowLooper.idleMainLooper();
      assertEquals(0, Jobs.secureCalls);
      assertEquals(0, Jobs.plainCalls);
      assertArrayEquals(new char[owned.length], owned);
      AlertDialog dialog = openExportDialog(page);
      var plain = (CheckBox) find(dialog.getWindow().getDecorView(), v -> v instanceof CheckBox);
      assertFalse(plain.isChecked());
      plain.performClick();
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      picker = launchedBy(page).getNextStartedActivityForResult();
      page.getActivityResultRegistry()
          .dispatchResult(
              picker.requestCode,
              android.app.Activity.RESULT_OK,
              new Intent().setData(Uri.parse("content://synthetic.documents/plain")));
      ShadowLooper.idleMainLooper();
      assertEquals(1, Jobs.plainCalls);
      assertEquals(0, Jobs.secureCalls);
    }
  }

  @Test
  public void losingTheProcessDuringSafNeverFallsBackToPlaintext() {
    Bundle saved = new Bundle();
    int requestCode;
    char[] owned;
    var original = Robolectric.buildActivity(NativeDataActivity.class, intent()).setup();
    var page = original.get();
    confirmExport(page);
    requestCode = launchedBy(page).getNextStartedActivityForResult().requestCode;
    owned = new ViewModelProvider(page).get(NativeDataActivity.Pending.class).password;
    original.pause().saveInstanceState(saved).stop().destroy();
    assertArrayEquals(new char[owned.length], owned);
    try (var recreated =
        Robolectric.buildActivity(NativeDataActivity.class, intent())
            .create(saved)
            .start()
            .restoreInstanceState(saved)
            .resume()
            .visible()) {
      var fresh = recreated.get();
      assertTrue(
          fresh
              .getActivityResultRegistry()
              .dispatchResult(
                  requestCode,
                  android.app.Activity.RESULT_OK,
                  new Intent().setData(Uri.parse("content://synthetic.documents/restarted"))));
      ShadowLooper.idleMainLooper();
      assertEquals(0, Jobs.secureCalls);
      assertEquals(0, Jobs.plainCalls);
      assertNotNull(textView(fresh, "应用已重启，请重新发起导出"));
    }
  }

  /** Bounds observed on the 360dp API 30 device, plus a shorter viewport while typing. */
  private static void checkExportActions(
      AlertDialog dialog,
      int widthDp,
      int heightDp,
      String scenario,
      java.util.List<String> clipped) {
    ViewGroup panel = measureExportPanel(dialog, widthDp, heightDp);
    int width = panel.getWidth();
    for (int which : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
      var button = dialog.getButton(which);
      android.graphics.Rect bounds = new android.graphics.Rect();
      button.getDrawingRect(bounds);
      panel.offsetDescendantRectToMyCoords(button, bounds);
      if (!button.isEnabled()
          || bounds.isEmpty()
          || bounds.left < 0
          || bounds.right > width
          || bounds.top < 0
          || bounds.bottom > panel.getHeight())
        clipped.add(
            scenario
                + " "
                + widthDp
                + "x"
                + heightDp
                + "dp "
                + button.getText()
                + " "
                + bounds
                + ", panel height "
                + panel.getHeight());
    }
  }

  private static ViewGroup measureExportPanel(AlertDialog dialog, int widthDp, int heightDp) {
    View decor = dialog.getWindow().getDecorView();
    ViewGroup panel =
        (ViewGroup) find(decor, v -> v instanceof androidx.appcompat.widget.AlertDialogLayout);
    assertNotNull(panel);
    float density = panel.getResources().getDisplayMetrics().density;
    int width = Math.round(widthDp * density), height = Math.round(heightDp * density);
    int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
    int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST);
    // Android schedules a fresh traversal when the button bar changes its minimum height
    // during layout. These direct test measurements must not reuse the first pass's cached size.
    for (int pass = 0; pass < 2; pass++) {
      panel.forceLayout();
      panel.measure(widthSpec, heightSpec);
      panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());
    }
    return panel;
  }

  @Test
  @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
  @Config(qualifiers = "en-rUS-w360dp-h640dp-port-xhdpi")
  public void compactStandardAndRescueFormsKeepBothActionsWithinTheViewport() {
    UiText.setLanguage("en");
    java.util.List<String> clipped = new java.util.ArrayList<>();
    try {
      for (float scale : new float[] {1f, 1.3f}) {
        for (boolean rescue : new boolean[] {false, true}) {
          RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h640dp-port-xhdpi");
          RuntimeEnvironment.setFontScale(scale);
          String scenario = (rescue ? "rescue" : "normal") + " font " + scale;
          var route = intent();
          if (rescue) route.putExtra("data_mode", NativeDataActivity.MODE_RESCUE);
          try (var host =
              Robolectric.buildActivity(NativeDataActivity.class, route).setup().visible()) {
            if (!rescue) click(textView(host.get(), "Export application data"));
            ShadowLooper.idleMainLooper();
            var dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            checkExportActions(dialog, 312, 384, scenario, clipped);
            checkExportActions(dialog, 312, 248, scenario, clipped);
            var fields =
                (PortablePasswordFields)
                    find(
                        dialog.getWindow().getDecorView(),
                        v -> v instanceof PortablePasswordFields);
            fields.first.setText("synthetic saved nowhere");
            var landscape =
                new android.content.res.Configuration(host.get().getResources().getConfiguration());
            landscape.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            landscape.screenWidthDp = 640;
            landscape.screenHeightDp = 360;
            host.configurationChange(landscape).visible();
            ShadowLooper.idleMainLooper();
            var rebuilt = (AlertDialog) ShadowDialog.getLatestDialog();
            fields =
                (PortablePasswordFields)
                    find(
                        rebuilt.getWindow().getDecorView(),
                        v -> v instanceof PortablePasswordFields);
            assertEquals(0, fields.first.length());
            checkExportActions(rebuilt, 560, 228, scenario, clipped);
            rebuilt.cancel();
          }
        }
      }
      assertTrue(clipped.toString(), clipped.isEmpty());
    } finally {
      RuntimeEnvironment.setFontScale(1f);
    }
  }

  @Implements(value = DshaDialogBuilder.class, isInAndroidSdk = false)
  public static class DialogSizing {
    @org.robolectric.annotation.RealObject DshaDialogBuilder builder;
    static DshaDialogBuilder shown;

    @Implementation
    protected AlertDialog create() {
      shown = builder;
      return Shadow.directlyOn(builder, DshaDialogBuilder.class, "create");
    }
  }

  /** Exercise the same one-time sizing with an oversized decor before an IME appears. */
  private static android.view.WindowManager.LayoutParams sizeTallDialog(AlertDialog dialog) {
    var window = dialog.getWindow();
    assertNotNull(window);
    View decor = window.getDecorView();
    int width = decor.getResources().getDisplayMetrics().widthPixels;
    int height = decor.getResources().getDisplayMetrics().heightPixels + 400;
    decor.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    ShadowLooper.idleMainLooper();
    decor.layout(0, 0, width, height);
    // Robolectric's floating decor can lack a window token. Invoke the actual sizing callback
    // on the real builder that just created this dialog, with its measured decor and window.
    org.robolectric.util.ReflectionHelpers.callInstanceMethod(
        DialogSizing.shown,
        "sizeOnce",
        org.robolectric.util.ReflectionHelpers.ClassParameter.from(
            android.view.Window.class, window),
        org.robolectric.util.ReflectionHelpers.ClassParameter.from(View.class, decor));
    return window.getAttributes();
  }

  @Test
  @Config(qualifiers = "en-rUS-w800dp-h600dp-land-xhdpi", shadows = DialogSizing.class)
  public void tallBackupDialogRetainsWrapHeightAndStandardTabletWidthAfterSizing() {
    try (var host =
        Robolectric.buildActivity(NativeDataActivity.class, intent()).setup().visible()) {
      var page = host.get();
      click(textView(page, "导出应用数据"));
      var backup = (AlertDialog) ShadowDialog.getLatestDialog();
      var sized = sizeTallDialog(backup);
      var res = page.getResources();
      float density = res.getDisplayMetrics().density;
      int width =
          Math.round(
              density
                  * com.deepseekharness.app.util.SheetGeometry.dialogWindowWidth(
                      res.getConfiguration().screenWidthDp, true));
      assertTrue(width < res.getDisplayMetrics().widthPixels);
      assertEquals(width, sized.width);
      assertEquals(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, sized.height);
      var fields =
          (PortablePasswordFields)
              find(backup.getWindow().getDecorView(), v -> v instanceof PortablePasswordFields);
      int flags =
          android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
              | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
              | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
      for (EditText field : new EditText[] {fields.first, fields.confirm}) {
        assertEquals(flags, flags & field.getImeOptions());
        assertEquals(
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            android.text.InputType.TYPE_MASK_VARIATION & field.getInputType());
      }
      backup.cancel();
      var unchanged =
          new DshaDialogBuilder(page)
              .setTitle("Control")
              .setView(new android.widget.LinearLayout(page))
              .show();
      var control = sizeTallDialog(unchanged);
      assertEquals(width, control.width);
      assertEquals(
          Math.round((res.getConfiguration().screenHeightDp - 24) * density), control.height);
      unchanged.dismiss();
    } finally {
      DialogSizing.shown = null;
    }
  }

  @Test
  @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
  @Config(qualifiers = "en-rUS-w640dp-h360dp-land-xhdpi")
  public void compactFormsKeepAFullPasswordRowAndBothLabelledActionsAboveIme() {
    UiText.setLanguage("en");
    try {
      for (boolean landscape : new boolean[] {true, false}) {
        RuntimeEnvironment.setQualifiers(
            landscape ? "en-rUS-w640dp-h360dp-land-xhdpi" : "en-rUS-w360dp-h640dp-port-xhdpi");
        for (float scale : new float[] {1f, 1.3f}) {
          RuntimeEnvironment.setFontScale(scale);
          for (boolean rescue : new boolean[] {false, true}) {
            var route = intent();
            if (rescue) route.putExtra("data_mode", NativeDataActivity.MODE_RESCUE);
            try (var host =
                Robolectric.buildActivity(NativeDataActivity.class, route).setup().visible()) {
              if (!rescue) click(textView(host.get(), "Export application data"));
              ShadowLooper.idleMainLooper();
              var dialog = (AlertDialog) ShadowDialog.getLatestDialog();
              var decor = dialog.getWindow().getDecorView();
              android.graphics.Rect padding = new android.graphics.Rect();
              decor.getBackground().getPadding(padding);
              assertEquals("top background inset", 0, padding.top);
              assertEquals("bottom background inset", 0, padding.bottom);
              String title = rescue ? "Rescue export" : "Data export";
              View heading =
                  find(
                      decor,
                      v ->
                          v instanceof TextView t
                              && title.contentEquals(t.getText())
                              && t.isAccessibilityHeading());
              assertNotNull("accessible form heading", heading);
              var fields =
                  (PortablePasswordFields) find(decor, v -> v instanceof PortablePasswordFields);
              var scroll = (android.widget.ScrollView) fields.getParent().getParent();
              assertSame(fields.getParent(), heading.getParent());
              var missed = new java.util.ArrayList<String>();
              // The OS can first measure actions at a narrow width and stack them. After widening,
              // only their natural row height may remain reserved; the rest belongs to the form.
              measureExportPanel(dialog, 280, 384);
              checkExportActions(
                  dialog,
                  landscape ? 512 : 312,
                  landscape ? 136 : 319,
                  title + " " + scale,
                  missed);
              assertTrue(missed.toString(), missed.isEmpty());
              for (EditText field : new EditText[] {fields.first, fields.confirm}) {
                assertTrue(
                    "form="
                        + title
                        + ", landscape="
                        + landscape
                        + ", scale="
                        + scale
                        + ", viewport="
                        + scroll.getHeight()
                        + ", field="
                        + field.getHeight(),
                    scroll.getHeight() >= field.getHeight());
              }
              dialog.dismiss();
            }
          }
        }
      }
    } finally {
      RuntimeEnvironment.setFontScale(1f);
    }
  }
}
