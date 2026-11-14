package com.deepseekharness.app.ui;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.widget.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.PtySession;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.TerminalSessionOwner;
import com.deepseekharness.app.runtime.TerminalProcessCloser;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.ProcessIdentity;
import com.deepseekharness.app.util.TerminalTabs;
import com.deepseekharness.app.util.TerminalSession;
import com.deepseekharness.app.util.Constants;
import java.lang.reflect.Field;
import java.util.*;

/** debug 源集验证独立终端和语言重建；不能代替非调试APK的设备验收。 */
public final class MultiTerminalLanguageAudit extends Instrumentation {
  private FragmentSessionTestActivity page;
  private Bundle args;
  private int checks;
  private final List<TerminalTabs.Tab<PtySession>> ownedPty = new ArrayList<>();
  private final List<TerminalTabs.Tab<Object>> ownedSimple = new ArrayList<>();

  private record CloseEvidence(TerminalTabs.Tab<?> tab, ProcessIdentity birth) {}

  private final Map<String, CloseEvidence> closing = new HashMap<>();
  private final List<String> evidence = new ArrayList<>();

  @Override
  public void onCreate(Bundle value) {
    super.onCreate(value);
    args = value;
    start();
  }

  @Override
  public void callActivityOnCreate(Activity activity, Bundle value) {
    super.callActivityOnCreate(activity, value);
    activity
        .getWindow()
        .addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
  }

  private void check(boolean ok, String why) {
    checks++;
    if (!ok) throw new AssertionError(why);
  }

  private void ui(Runnable action) {
    Throwable[] error = {null};
    runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable t) {
            error[0] = t;
          }
        });
    if (error[0] != null) throw new AssertionError(error[0]);
  }

  private static Object field(Object target, String name) throws Exception {
    Field f =
        (target instanceof Class ? (Class<?>) target : target.getClass()).getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target instanceof Class ? null : target);
  }

  @SuppressWarnings("unchecked")
  private static <T> TerminalTabs.ReadOnly<T> tabs(Class<?> type) throws Exception {
    return (TerminalTabs.ReadOnly<T>)
        (type == PtyTerminalFragment.class
            ? com.deepseekharness.app.core.TerminalSessionOwner.shared().ptyTabs()
            : com.deepseekharness.app.core.TerminalSessionOwner.shared().simpleTabs());
  }

  private void mount(androidx.fragment.app.Fragment fragment) {
    Bundle options = new Bundle();
    options.putBoolean("terminal_skip_initial_create", true);
    fragment.setArguments(options);
    ui(
        () ->
            page.getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commitNow());
    waitForIdleSync();
  }

  private TerminalPane pane() {
    return (TerminalPane)
        page.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
  }

  private void click(int id) throws Exception {
    if (id != R.id.terminal_new) {
      ui(() -> check(page.findViewById(id).performClick(), "控件未接受点击：" + id));
      waitForIdleSync();
      return;
    }
    TerminalPane current = pane();
    TerminalTabs.ReadOnly<?> tabs =
        tabs(current.isPty() ? PtyTerminalFragment.class : TerminalFragment.class);
    Set<Long> before = new HashSet<>();
    for (var tab : tabs.snapshot()) before.add(tab.id);
    ui(current::newSession);
    long end = System.currentTimeMillis() + 20000;
    while ((tabs.current() == null || before.contains(tabs.current().id))
        && System.currentTimeMillis() < end) Thread.sleep(50);
    check(tabs.current() != null && !before.contains(tabs.current().id), "前台保护准入后未发布新终端");
    waitForIdleSync();
  }

  private String closeKey(TerminalTabs.ReadOnly<?> tabs, long id) {
    return (tabs == (Object) TerminalSessionOwner.shared().ptyTabs() ? "PTY/" : "SIMPLE/") + id;
  }

  private <T> void tabAction(TerminalTabs.ReadOnly<T> tabs, long id, boolean close)
      throws Exception {
    int index = -1;
    var snapshot = tabs.snapshot();
    for (int i = 0; i < snapshot.size(); i++) if (snapshot.get(i).id == id) index = i;
    check(index >= 0, "标签不存在");
    // 夹具只对自己新建的会话执行已授权关闭；生产确认归 TerminalActivity。
    if (close) {
      var target = tabs.find(id);
      boolean pty = target.value instanceof PtySession;
      check(
          pty
              ? ownedPty.stream().anyMatch(tab -> tab.id == id && target.value == tab.value)
              : ownedSimple.stream().anyMatch(tab -> tab.id == id && target.value == tab.value),
          "拒绝关闭非本夹具创建的会话");
      ProcessIdentity birth = pty ? ptyBirth((PtySession) target.value) : simpleBirth(target.value);
      closing.put(closeKey(tabs, id), new CloseEvidence(target, birth));
    }
    ui(
        () -> {
          if (close) pane().closeSession(id);
          else check(pane().selectSession(id), "标签切换未执行");
        });
    waitForIdleSync();
  }

  private <T> void waitRemoved(TerminalTabs.ReadOnly<T> tabs, long id) throws Exception {
    CloseEvidence expected = closing.remove(closeKey(tabs, id));
    check(expected != null && expected.birth() != null, "缺少关闭前的本次出生证据：" + id);
    long end = System.currentTimeMillis() + 20000;
    while (tabs.find(id) != null && System.currentTimeMillis() < end) Thread.sleep(100);
    check(tabs.find(id) == null, "关闭未确认或标签未移除：" + id);
    check(
        expected.tab().cleanupState() == TerminalTabs.CleanupState.VERIFIED, "标签消失但没有核验关闭状态：" + id);
    TerminalProcessCloser.requireSessionEmpty(expected.birth().session);
    if (expected.tab().value instanceof PtySession)
      check(!((PtySession) expected.tab().value).isCleanupPending(), "PTY 标签已移除但清理仍 pending");
  }

  private String transcript(PtySession session) {
    String[] text = {""};
    ui(() -> text[0] = session.session().getEmulator().getScreen().getTranscriptText());
    return text[0];
  }

  private void waitPty(PtySession session, String marker) throws Exception {
    long end = System.currentTimeMillis() + 15000;
    while (!transcript(session).contains(marker) && System.currentTimeMillis() < end)
      Thread.sleep(100);
    check(transcript(session).contains(marker), "真实 PTY 命令未产生预期回执：" + marker);
  }

  private void language(String id) throws Exception {
    ConfigStore config = new ConfigStore(getTargetContext());
    if (id.equals(config.getUiLanguagePreference())) return;
    var owner = TerminalSessionOwner.shared();
    String epoch = owner.ownerEpoch();
    Set<Long> ptyIds = ids(owner.ptyTabs()), simpleIds = ids(owner.simpleTabs());
    long ptySelected = selected(owner.ptyTabs()), simpleSelected = selected(owner.simpleTabs());
    ActivityMonitor monitor = addMonitor(FragmentSessionTestActivity.class.getName(), null, false);
    ui(() -> LanguageController.select(page, id));
    FragmentSessionTestActivity next =
        (FragmentSessionTestActivity) waitForMonitorWithTimeout(monitor, 20000);
    removeMonitor(monitor);
    check(next != null, "切换语言未重建活动界面");
    page = next;
    waitForIdleSync();
    Thread.sleep(300);
    check(
        id.equals(page.getResources().getConfiguration().getLocales().get(0).getLanguage()),
        "资源语言未更新");
    verifyTables(epoch, ptyIds, simpleIds, ptySelected, simpleSelected);
  }

  private static Set<Long> ids(TerminalTabs.ReadOnly<?> table) {
    Set<Long> result = new HashSet<>();
    for (var tab : table.snapshot()) result.add(tab.id);
    return result;
  }

  private static long selected(TerminalTabs.ReadOnly<?> table) {
    var tab = table.current();
    return tab == null ? 0 : tab.id;
  }

  private void verifyTables(
      String epoch, Set<Long> ptyIds, Set<Long> simpleIds, long ptySelected, long simpleSelected) {
    var owner = TerminalSessionOwner.shared();
    check(epoch.equals(owner.ownerEpoch()), "页面重建更换了终端 owner");
    check(
        ptyIds.equals(ids(owner.ptyTabs())) && simpleIds.equals(ids(owner.simpleTabs())),
        "页面重建创建或移除了终端，验收未完成");
    check(
        ptySelected == selected(owner.ptyTabs()) && simpleSelected == selected(owner.simpleTabs()),
        "页面重建改变了当前标签");
  }

  private ProcessIdentity ptyBirth(PtySession session) throws Exception {
    check(session.isRunning(), "PTY 已退出，不能作为保活证据");
    ProcessIdentity saved = (ProcessIdentity) field(session, "identity");
    check(saved != null, "PTY 出生身份缺失");
    ProcessIdentity current = TerminalProcessCloser.captureSimpleLeader(saved.pid, saved.started);
    check(saved.sameProcess(current), "PTY 内核出生身份已改变");
    return current;
  }

  private ProcessIdentity simpleBirth(Object value) throws Exception {
    TerminalSession session = simpleSession(value);
    TerminalSession.State state = session.state();
    check(
        state == TerminalSession.State.READY || state == TerminalSession.State.BUSY,
        "简易终端未处于可核验运行态");
    Object run = field(session, "active");
    check(run != null, "简易终端运行身份缺失");
    long group = ((Number) field(run, "group")).longValue();
    check(group > 1 && group <= Integer.MAX_VALUE, "简易终端会话号无效");
    int pid = (int) group;
    ProcessIdentity read =
        ProcessIdentity.inSession(
            Compat.readAll(new java.io.File("/proc/" + pid + "/stat")), pid, pid);
    check(read != null && read.ownsSession(), "简易终端出生身份不可读，验收未完成");
    ProcessIdentity current = TerminalProcessCloser.captureSimpleLeader(pid, read.started);
    check(read.sameProcess(current), "简易终端内核身份在核验期间改变");
    return current;
  }

  private Map<Long, ProcessIdentity> ownBirths(boolean pty) throws Exception {
    Map<Long, ProcessIdentity> result = new HashMap<>();
    if (pty) {
      for (var tab : ownedPty) {
        var current = TerminalSessionOwner.shared().ptyTabs().find(tab.id);
        if (current != null) {
          check(current.value == tab.value, "PTY 标签复用了其他会话对象");
          result.put(tab.id, ptyBirth(tab.value));
        }
      }
    } else {
      for (var tab : ownedSimple) {
        var current = TerminalSessionOwner.shared().simpleTabs().find(tab.id);
        if (current != null) {
          check(current.value == tab.value, "简易标签复用了其他会话对象");
          result.put(tab.id, simpleBirth(tab.value));
        }
      }
    }
    return result;
  }

  private void verifyBirths(boolean pty, Map<Long, ProcessIdentity> expected) throws Exception {
    Map<Long, ProcessIdentity> current = ownBirths(pty);
    check(expected.keySet().equals(current.keySet()), "重建后本夹具会话集合改变");
    for (var item : expected.entrySet()) {
      ProcessIdentity value = current.get(item.getKey());
      check(
          item.getValue().sameProcess(value)
              && item.getValue().group == value.group
              && item.getValue().session == value.session,
          "重建后 PID/出生/会话已改变：" + item.getKey());
    }
  }

  /** 只观察本夹具创建的进程；离页与旋转不借结束进程模拟“清理成功”。 */
  private void reenterAndRotate(boolean pty) throws Exception {
    var owner = TerminalSessionOwner.shared();
    String epoch = owner.ownerEpoch();
    Set<Long> ptyIds = ids(owner.ptyTabs()), simpleIds = ids(owner.simpleTabs());
    long ptySelected = selected(owner.ptyTabs()), simpleSelected = selected(owner.simpleTabs());
    Map<Long, ProcessIdentity> expected = ownBirths(pty);
    for (boolean rotate : new boolean[] {false, true}) {
      ActivityMonitor monitor =
          addMonitor(FragmentSessionTestActivity.class.getName(), null, false);
      int target =
          page.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT
              ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
              : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
      FragmentSessionTestActivity old = page;
      FragmentSessionTestActivity next;
      try {
        if (rotate) ui(() -> old.setRequestedOrientation(target));
        else {
          ui(old::finish);
          waitForIdleSync();
          ui(
              () ->
                  getTargetContext()
                      .startActivity(
                          new Intent(getTargetContext(), FragmentSessionTestActivity.class)
                              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        }
        next = (FragmentSessionTestActivity) waitForMonitorWithTimeout(monitor, 20000);
      } finally {
        removeMonitor(monitor);
      }
      check(next != null && next != old, rotate ? "系统未完成方向重建，旋转证据未形成" : "未重新打开测试宿主");
      page = next;
      waitForIdleSync();
      if (rotate) {
        int actual = page.getResources().getConfiguration().orientation;
        check(
            actual
                == (target == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    ? Configuration.ORIENTATION_LANDSCAPE
                    : Configuration.ORIENTATION_PORTRAIT),
            "系统未执行方向切换，不能记为旋转通过");
        check(pane() != null && pane().isPty() == pty, "旋转未恢复原终端面板");
      } else mount(pty ? new PtyTerminalFragment() : new TerminalFragment());
      verifyTables(epoch, ptyIds, simpleIds, ptySelected, simpleSelected);
      verifyBirths(pty, expected);
      evidence.add(
          (pty ? "PTY" : "简易")
              + (rotate ? "：真实方向切换" : "：离页重进")
              + "后同 epoch/ID/PID/出生/会话与选中项保持，未创建或结束终端");
    }
  }

  private void pty() throws Exception {
    TerminalTabs.ReadOnly<PtySession> tabs = tabs(PtyTerminalFragment.class);
    Set<Long> before = new HashSet<>();
    for (var t : tabs.snapshot()) before.add(t.id);
    mount(new PtyTerminalFragment());
    if (tabs.current() == null || before.contains(tabs.current().id)) click(R.id.terminal_new);
    ownedPty.add(tabs.current());
    click(R.id.terminal_new);
    ownedPty.add(tabs.current());
    click(R.id.terminal_new);
    ownedPty.add(tabs.current());
    check(
        new HashSet<>(
                    Arrays.asList(
                        ownedPty.get(0).value.session().getPid(),
                        ownedPty.get(1).value.session().getPid(),
                        ownedPty.get(2).value.session().getPid()))
                .size()
            == 3,
        "PTY 没有三个独立进程");
    String[] dirs = {"/tmp", "/var/tmp", "/root"};
    for (int i = 0; i < 3; i++) {
      final int n = i;
      ui(
          () ->
              ownedPty
                  .get(n)
                  .value
                  .write(
                      "unset HISTFILE; export DSHA_MULTI=P"
                          + n
                          + "; cd "
                          + dirs[n]
                          + "; printf 'MULTI_READY=%s:%s\\n' \"$DSHA_MULTI\" \"$PWD\"\n"));
      waitPty(ownedPty.get(i).value, "MULTI_READY=P" + i + ":" + dirs[i]);
    }
    tabAction(tabs, ownedPty.get(1).id, false);
    String requested = new ConfigStore(getTargetContext()).isProroot() ? "proroot" : "proot";
    String expected = args == null ? requested : args.getString("runtime", requested);
    check("proot".equals(expected) || "proroot".equals(expected), "runtime 只接受 proot/proroot");
    for (var tab : ownedPty) {
      String launcher =
          new java.io.File((String) field(tab.value.session(), "mShellPath")).getName();
      String actual =
          launcher.equals("libproot.so")
              ? "proot"
              : launcher.equals("libproroot.so") ? "proroot" : "unknown";
      check(expected.equals(actual), "PTY 本次启动方式与验收选择不符：" + expected + " / " + actual);
      evidence.add("PTY " + tab.id + " requested=" + requested + " actualLauncher=" + actual);
    }
    Map<Long, ProcessIdentity> births = ownBirths(true);
    reenterAndRotate(true);
    int[] pids = ownedPty.stream().mapToInt(t -> t.value.session().getPid()).toArray();
    language("en");
    verifyBirths(true, births);
    check(
        ((TextView) page.findViewById(R.id.terminal_new)).getText().toString().contains("New"),
        "英文新建按钮未显示");
    for (int i = 0; i < 3; i++)
      check(
          ownedPty.get(i).value.isRunning() && ownedPty.get(i).value.session().getPid() == pids[i],
          "语言切换重启了 PTY");
    check(tabs.current().id == ownedPty.get(1).id, "语言切换丢失当前 PTY 标签");
    tabAction(tabs, ownedPty.get(0).id, true);
    waitRemoved(tabs, ownedPty.get(0).id);
    births.remove(ownedPty.get(0).id);
    check(
        ownedPty.get(1).value.isRunning() && ownedPty.get(2).value.isRunning(), "关闭一个 PTY 影响了其他会话");
    ui(
        () ->
            ownedPty
                .get(1)
                .value
                .write("printf 'AFTER_CLOSE=%s:%s\\n' \"$DSHA_MULTI\" \"$PWD\"\n"));
    waitPty(ownedPty.get(1).value, "AFTER_CLOSE=P1:/var/tmp");
    language("zh");
    verifyBirths(true, births);
    check(
        ((TextView) page.findViewById(R.id.terminal_new)).getText().toString().contains("新建"),
        "返回中文失败");
    for (int i = 1; i < 3; i++) {
      tabAction(tabs, ownedPty.get(i).id, true);
      waitRemoved(tabs, ownedPty.get(i).id);
    }
    if (before.isEmpty()) {
      check(tabs.current() == null, "关闭最后一个 PTY 后仍选中会话");
      check(
          page.findViewById(R.id.pty_empty).getVisibility() == android.view.View.VISIBLE,
          "未展示空终端状态");
    }
    if (before.isEmpty()) {
      click(R.id.terminal_new);
      var fresh = tabs.current();
      ownedPty.add(fresh);
      check(fresh.number == 1 && fresh.id > ownedPty.get(2).id, "重新打开 PTY 应显示 1 且身份不复用");
      check(
          ((TextView)
                  ((LinearLayout)
                          ((LinearLayout) page.findViewById(R.id.terminal_tabs)).getChildAt(0))
                      .getChildAt(0))
              .getText()
              .toString()
              .equals("终端 1"),
          "PTY 显示编号没有归一");
      tabAction(tabs, fresh.id, true);
      waitRemoved(tabs, fresh.id);
    }
    evidence.add("PTY：三个真实进程、独立 cwd/export/回滚缓冲、关闭一个不影响另两个、语言双向切换保留 PID 与选中标签");
  }

  private String simpleOutput(Object value) {
    return ((TerminalSessionOwner.SimpleTab) value).output();
  }

  private TerminalSession simpleSession(Object value) throws Exception {
    return (TerminalSession) field(value, "session");
  }

  private void waitSimpleReady(Object value) throws Exception {
    long end = System.currentTimeMillis() + 20000;
    while (simpleSession(value).state() != TerminalSession.State.READY
        && System.currentTimeMillis() < end) Thread.sleep(100);
    check(
        simpleSession(value).state() == TerminalSession.State.READY,
        "简易终端尚未就绪：" + simpleState(value));
  }

  private String simpleState(Object value) throws Exception {
    TerminalSession session = simpleSession(value);
    Object active = field(session, "active");
    return session.state() + " group=" + (active == null ? "none" : field(active, "group"));
  }

  private void waitSimple(Object value, String marker) throws Exception {
    long end = System.currentTimeMillis() + 15000;
    while (!simpleOutput(value).contains(marker) && System.currentTimeMillis() < end)
      Thread.sleep(100);
    String output = com.deepseekharness.app.util.SensitiveData.redact(simpleOutput(value));
    check(
        output.contains(marker),
        "简易终端命令未执行："
            + marker
            + "; "
            + simpleState(value)
            + "\n"
            + output.substring(Math.max(0, output.length() - 4000)));
  }

  private void simple() throws Exception {
    TerminalTabs.ReadOnly<Object> tabs = tabs(TerminalFragment.class);
    Set<Long> before = new HashSet<>();
    for (var t : tabs.snapshot()) before.add(t.id);
    mount(new TerminalFragment());
    if (tabs.current() == null || before.contains(tabs.current().id)) click(R.id.terminal_new);
    ownedSimple.add(tabs.current());
    waitSimpleReady(tabs.current().value);
    waitForIdleSync();
    click(R.id.terminal_new);
    ownedSimple.add(tabs.current());
    check(
        ownedSimple.get(0).id != ownedSimple.get(1).id
            && ownedSimple.get(0).value != ownedSimple.get(1).value,
        "新建未产生独立的简易终端标签");
    waitSimpleReady(ownedSimple.get(1).value);
    for (int i = 0; i < 2; i++) {
      simpleSession(ownedSimple.get(i).value)
          .submit(
              "export DSHA_MULTI=S"
                  + i
                  + "; cd /tmp; printf 'SIMPLE_READY=%s:%s\\n' \"$DSHA_MULTI\" \"$PWD\"");
      waitSimple(ownedSimple.get(i).value, "SIMPLE_READY=S" + i + ":/tmp");
    }
    evidence.add(
        "Simple before language: "
            + simpleState(ownedSimple.get(0).value)
            + " / "
            + simpleState(ownedSimple.get(1).value));
    String draft = "echo 用户自己的中文命令";
    ui(
        () -> {
          EditText input = page.findViewById(R.id.term_input);
          input.setText(draft);
          input.setSelection(6);
        });
    tabAction(tabs, ownedSimple.get(0).id, false);
    Map<Long, ProcessIdentity> births = ownBirths(false);
    reenterAndRotate(false);
    language("en");
    verifyBirths(false, births);
    tabAction(tabs, ownedSimple.get(1).id, false);
    evidence.add(
        "Simple after language: "
            + simpleState(ownedSimple.get(0).value)
            + " / "
            + simpleState(ownedSimple.get(1).value));
    ui(
        () -> {
          EditText input = page.findViewById(R.id.term_input);
          check(draft.equals(input.getText().toString()), "语言切换或标签切换改变了命令草稿");
          check(input.getSelectionStart() == 6, "命令光标未保留");
        });
    tabAction(tabs, ownedSimple.get(0).id, true);
    waitRemoved(tabs, ownedSimple.get(0).id);
    births.remove(ownedSimple.get(0).id);
    evidence.add("Simple after closing first: " + simpleState(ownedSimple.get(1).value));
    simpleSession(ownedSimple.get(1).value)
        .submit("printf 'SIMPLE_SURVIVES=%s\\n' \"$DSHA_MULTI\"");
    waitSimple(ownedSimple.get(1).value, "SIMPLE_SURVIVES=S1");
    language("zh");
    verifyBirths(false, births);
    tabAction(tabs, ownedSimple.get(1).id, true);
    waitRemoved(tabs, ownedSimple.get(1).id);
    if (before.isEmpty())
      check(
          tabs.current() == null && !page.findViewById(R.id.term_input).isEnabled(), "简易终端关闭后仍可输入");
    if (before.isEmpty()) {
      click(R.id.terminal_new);
      var fresh = tabs.current();
      ownedSimple.add(fresh);
      waitSimpleReady(fresh.value);
      check(fresh.number == 1 && fresh.id > ownedSimple.get(1).id, "重新打开简易终端应显示 1 且身份不复用");
      tabAction(tabs, fresh.id, true);
      waitRemoved(tabs, fresh.id);
    }
    evidence.add("简易终端：两个独立 shell、输出分离、中文命令草稿与光标跨语言保留、单独关闭和线程释放");
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    Context app = getTargetContext();
    var prefs = app.getSharedPreferences(Constants.PREFS, 0);
    boolean had = prefs.contains("ui_language");
    String original = new ConfigStore(app).getUiLanguagePreference();
    try {
      try (var fd =
              getUiAutomation()
                  .executeShellCommand(
                      "am start -W -n "
                          + app.getPackageName()
                          + "/com.deepseekharness.app.ui.MainActivity");
          var input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
        while (input.read() != -1) {}
      }
      long ready = System.currentTimeMillis() + 120000;
      while ((!HarnessController.get(app).proot().isEnvironmentReady()
              || com.deepseekharness.app.BackupManager.isEnvironmentTaskBusy())
          && System.currentTimeMillis() < ready) Thread.sleep(200);
      check(HarnessController.get(app).proot().isEnvironmentReady(), "环境尚未就绪");
      check(!com.deepseekharness.app.BackupManager.isEnvironmentTaskBusy(), "维护尚未安全收尾");
      ActivityMonitor monitor =
          addMonitor(FragmentSessionTestActivity.class.getName(), null, false);
      ui(
          () ->
              app.startActivity(
                  new Intent(app, FragmentSessionTestActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
      page = (FragmentSessionTestActivity) waitForMonitorWithTimeout(monitor, 15000);
      removeMonitor(monitor);
      check(page != null, "测试宿主未打开");
      pty();
      simple();
      result.putString("result", "PASS");
      result.putInt("checks", checks);
      result.putString("evidence", String.join("\n", evidence));
    } catch (Throwable error) {
      result.putString("failure", android.util.Log.getStackTraceString(error));
    } finally {
      result.putString("evidence", String.join("\n", evidence));
      try {
        var owner = com.deepseekharness.app.core.TerminalSessionOwner.shared();
        for (var t : ownedPty) if (t != null) owner.closePty(t.id, 5000);
        for (var t : ownedSimple) if (t != null) owner.closeSimple(t.id, 5000);
      } catch (Exception error) {
        result.putString("cleanup", error.toString());
        result.putString("result", "FAIL: 会话退出清理未确认");
      }
      if (page != null) ui(page::finish);
      ui(
          () -> {
            if (had) prefs.edit().putString("ui_language", original).commit();
            else prefs.edit().remove("ui_language").commit();
            LanguageController.apply(app);
          });
      finish(
          result.containsKey("failure") || result.containsKey("cleanup")
              ? android.app.Activity.RESULT_CANCELED
              : android.app.Activity.RESULT_OK,
          result);
    }
  }
}
