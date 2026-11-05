package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.GitHubLogin;
import com.deepseekharness.app.util.GhAuthCommands;
import com.deepseekharness.app.util.GhHostsFile;
import com.deepseekharness.app.util.GhLoginState;
import com.deepseekharness.app.util.GhManagedInstall;
import com.deepseekharness.app.util.UiText;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GitHub 登录管理原生页：显示 hosts.yml 的账号/作用域等非密元数据与受管 gh 状态，提供
 * {@code gh auth login --web} 引导、重新授权、退出与刷新。
 *
 * <p>不在页面、日志、SharedPreferences 或桥接口中保留 token；退出只删除本机记录，不撤销远端授权。
 * 状态读取与退出都在后台线程执行，页面只渲染最近一次快照。
 */
public final class GitHubLoginFragment extends Fragment {
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final AtomicInteger TASKS = new AtomicInteger();

  private CardPage ui;
  private TextView statusValue;
  private TextView accountValue;
  private TextView scopesValue;
  private TextView credentialValue;
  private TextView protocolValue;
  private TextView sourceValue;
  private TextView managedValue;
  private TextView managedVersionValue;
  private TextView managedHint;
  private android.widget.Button loginButton;
  private android.widget.Button logoutButton;
  private android.widget.Button refreshButton;
  private GitHubLogin.Snapshot snapshot;
  private boolean loading;
  private final AtomicInteger loadGeneration = new AtomicInteger();

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    ui =
        new CardPage(
            requireContext(),
            UiText.text("GitHub 登录"),
            UiText.text("管理容器内 GitHub CLI 的登录；本页不读取或显示 token。"));
    ui.label(UiText.text("当前账号"));
    LinearLayout account = ui.card();
    statusValue = kv(account, UiText.text("状态"));
    accountValue = kv(account, UiText.text("账号"));
    scopesValue = kv(account, UiText.text("作用域"));
    credentialValue = kv(account, UiText.text("凭据"));
    protocolValue = kv(account, UiText.text("Git 协议"));
    sourceValue = kv(account, UiText.text("来源"));

    ui.label(UiText.text("受管 GitHub CLI"));
    LinearLayout managed = ui.card();
    managedValue = kv(managed, UiText.text("状态"));
    managedVersionValue = kv(managed, UiText.text("版本"));
    kv(managed, UiText.text("路径")).setText(UiText.raw(GhManagedInstall.BIN));
    managedHint = ui.text("", 12, R.color.shell_text_muted);
    managedHint.setPadding(0, ui.dp(8), 0, ui.dp(2));
    managedHint.setLineSpacing(ui.dp(2), 1);
    managedHint.setVisibility(View.GONE);
    managed.addView(managedHint);

    ui.label(UiText.text("在终端核对"));
    LinearLayout more = ui.card();
    ui.entry(
        more,
        UiText.text("在终端核对作用域"),
        UiText.text("运行 gh auth status，查看 token 的实际作用域；需要网络。"),
        R.drawable.ic_ui_link,
        this::openStatusTerminal);

    TextView policy =
        ui.text(
            UiText.text(
                "gh 把 token 明文保存在容器 hosts.yml；本页不读取、不显示 token，也不会写入日志、备份或导出。网页与插件在容器内仍可能读取该文件。"),
            11,
            R.color.shell_text_muted);
    policy.setLineSpacing(ui.dp(2), 1);
    policy.setPadding(0, ui.dp(4), 0, ui.dp(8));
    ui.content.addView(policy);

    loginButton = ui.button(ui.footer, UiText.text("登录"), true, this::startLogin);
    logoutButton = ui.button(ui.footer, UiText.text("退出登录"), false, this::confirmLogout);
    logoutButton.setVisibility(View.GONE);
    refreshButton = ui.button(ui.footer, UiText.text("刷新"), false, this::reload);
    setLoading(true);
    return ui.root;
  }

  @Override
  public void onResume() {
    super.onResume();
    reload();
  }

  @Override
  public void onDestroyView() {
    loadGeneration.incrementAndGet();
    super.onDestroyView();
  }

  /** 行值可更新；标签沿用 CardPage.kv 的排版。 */
  private TextView kv(LinearLayout card, String label) {
    LinearLayout row = new LinearLayout(requireContext());
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(ui.dp(44));
    row.addView(
        ui.text(label, 12, R.color.shell_text_secondary), new LinearLayout.LayoutParams(0, -2, 1));
    TextView value = ui.text("", 13, R.color.shell_text);
    value.setMaxWidth(ui.dp(196));
    value.setGravity(Gravity.END);
    row.addView(value);
    card.addView(row);
    return value;
  }

  private void reload() {
    if (!isAdded() || getView() == null || ui == null) return;
    final int generation = loadGeneration.incrementAndGet();
    final android.content.Context app = requireContext().getApplicationContext();
    setLoading(true);
    Thread thread =
        new Thread(
            () -> {
              GitHubLogin.Snapshot loaded = GitHubLogin.load(app);
              MAIN.post(
                  () -> {
                    if (!isAdded() || getView() == null || ui == null) return;
                    if (generation != loadGeneration.get()) return;
                    snapshot = loaded;
                    setLoading(false);
                    render();
                  });
            },
            "dsha-github-login-" + TASKS.incrementAndGet());
    thread.setDaemon(true);
    thread.start();
  }

  private void render() {
    GitHubLogin.Snapshot current = snapshot;
    if (current == null) return;
    GhHostsFile.Result hosts = current.hosts;
    String account = hosts.login;
    statusValue.setText(stateText(current));
    accountValue.setText(
        account != null
            ? UiText.raw(account)
            : !hosts.accounts.isEmpty()
                ? UiText.raw(String.join(", ", hosts.accounts))
                : UiText.text("未登录"));
    scopesValue.setText(
        hosts.scopes.isEmpty()
            ? UiText.text("hosts.yml 未记录作用域（gh 不写入）")
            : UiText.raw(String.join(", ", hosts.scopes)));
    credentialValue.setText(
        hosts.credentialStored
            ? UiText.text("hosts.yml 已保存（不显示内容）")
            : UiText.text("hosts.yml 未保存（可能来自环境变量或钥匙串）"));
    protocolValue.setText(
        hosts.gitProtocol.isEmpty() ? UiText.text("未记录") : UiText.raw(hosts.gitProtocol));
    sourceValue.setText(
        UiText.raw(
            GhManagedInstall.HOSTS + (current.hostsPresent ? "" : " · " + UiText.text("未写入"))));
    managedValue.setText(managedText(current.managed));
    managedVersionValue.setText(
        current.managed == GhManagedInstall.State.AVAILABLE
            ? UiText.raw("v" + GhManagedInstall.VERSION)
            : UiText.text("—"));
    managedHint.setText(UiText.text("缺失时首次运行 gh 会按固定版本自举；也可重装环境重新注入。"));
    managedHint.setVisibility(
        current.managed == GhManagedInstall.State.MISSING
                || current.managed == GhManagedInstall.State.SIZE_MISMATCH
            ? View.VISIBLE
            : View.GONE);

    boolean ready = current.environmentReady;
    loginButton.setText(
        current.login == GhLoginState.Login.LOGGED_IN ? UiText.text("重新授权") : UiText.text("登录"));
    loginButton.setEnabled(ready && !loading);
    loginButton.setAlpha(ready && !loading ? 1f : .55f);
    boolean canLogout = ready && GhLoginState.canLogout(current.login) && !loading;
    logoutButton.setVisibility(GhLoginState.canLogout(current.login) ? View.VISIBLE : View.GONE);
    logoutButton.setEnabled(canLogout);
    logoutButton.setAlpha(canLogout ? 1f : .55f);
    refreshButton.setEnabled(!loading);
  }

  private String stateText(GitHubLogin.Snapshot current) {
    if (!current.environmentReady) return UiText.text("运行环境未就绪");
    if (loading) return UiText.text("正在读取…");
    switch (current.problem) {
      case HOSTS_READ_FAILED:
        return UiText.text("无法读取 hosts.yml");
      case HOSTS_TOO_LARGE:
        return UiText.text("hosts.yml 超过大小上限");
      case HOSTS_NOT_REGULAR:
        return UiText.text("hosts.yml 不是普通文件");
      case HOSTS_MALFORMED:
        return UiText.text("hosts.yml 结构无法识别");
      default:
        break;
    }
    switch (current.login) {
      case LOGGED_IN:
        return UiText.text("已登录");
      case CREDENTIAL_MISSING:
        return UiText.text("已登录，但 hosts.yml 未保存 token");
      case ACCOUNT_UNKNOWN:
        return UiText.text("已保存 token，但无法确定账号");
      case OTHER_HOSTS_ONLY:
        return UiText.text("只有其他 GitHub 主机登录");
      case MALFORMED:
        return UiText.text("hosts.yml 结构无法识别");
      default:
        return UiText.text("未登录");
    }
  }

  private String managedText(GhManagedInstall.State state) {
    switch (state) {
      case AVAILABLE:
        return UiText.text("可用");
      case MISSING:
        return UiText.text("缺失");
      case SIZE_MISMATCH:
        return UiText.text("大小与固定输入不符");
      case NOT_REGULAR:
        return UiText.text("不是普通文件");
      case UNREADABLE:
        return UiText.text("无法读取");
      default:
        return UiText.text("运行环境未就绪");
    }
  }

  private void setLoading(boolean value) {
    loading = value;
    if (ui == null) return;
    if (value) {
      if (statusValue != null) statusValue.setText(UiText.text("正在读取…"));
      if (accountValue != null) accountValue.setText("—");
      if (scopesValue != null) scopesValue.setText("—");
      if (credentialValue != null) credentialValue.setText("—");
      if (protocolValue != null) protocolValue.setText("—");
      if (sourceValue != null) sourceValue.setText("—");
      if (managedValue != null) managedValue.setText("—");
      if (managedVersionValue != null) managedVersionValue.setText("—");
      if (loginButton != null) loginButton.setEnabled(false);
      if (logoutButton != null) logoutButton.setEnabled(false);
      if (refreshButton != null) refreshButton.setEnabled(false);
    } else if (refreshButton != null) {
      refreshButton.setEnabled(true);
    }
  }

  /** 已登录时同一个按钮执行重新授权（gh auth refresh），否则走网页登录。 */
  private void startLogin() {
    GitHubLogin.Snapshot current = snapshot;
    loginGuide(current != null && current.login == GhLoginState.Login.LOGGED_IN);
  }

  private void loginGuide(boolean refresh) {
    GitHubLogin.Snapshot current = snapshot;
    if (current == null) return;
    if (!current.environmentReady) {
      toast(UiText.text("运行环境未就绪，请先在「安装」页完成安装"));
      return;
    }
    String command = refresh ? GhAuthCommands.refresh() : GhAuthCommands.login();
    String message =
        UiText.format(
            "将打开终端并运行：\n%s\n\n1. 终端显示一次性验证码；用手机浏览器打开\n%s\n2. 输入验证码并按提示授权 GitHub CLI\n3. 回到本页，状态会自动刷新\n\ngh 默认申请作用域：repo、read:org、gist。",
            command, GhAuthCommands.BROWSER_URL);
    if (refresh) message = message + "\n\n" + UiText.text("重新授权会重新打开网页授权流程。");
    final boolean reauthorize = refresh;
    new DshaDialogBuilder(requireContext())
        .setTitle(UiText.text(reauthorize ? "重新授权 GitHub" : "登录 GitHub"))
        .setMessage(message)
        .setPositiveButton(
            UiText.text("打开终端"), (dialog, which) -> openTerminalForLogin(reauthorize))
        .setNegativeButton(UiText.text("取消"), null)
        .show();
  }

  private void openTerminalForLogin(boolean refresh) {
    String[] argv = refresh ? GhAuthCommands.refreshGuestArgv() : GhAuthCommands.loginGuestArgv();
    String title = UiText.text(refresh ? "GitHub 授权" : "GitHub 登录");
    openTerminal(argv, title);
  }

  private void openStatusTerminal() {
    GitHubLogin.Snapshot current = snapshot;
    if (current == null || !current.environmentReady) {
      toast(UiText.text("运行环境未就绪，请先在「安装」页完成安装"));
      return;
    }
    openTerminal(GhAuthCommands.statusGuestArgv(), UiText.text("GitHub 状态"));
  }

  private void openTerminal(String[] argv, String title) {
    if (!(getActivity() instanceof MainActivity main)) {
      toast(UiText.text("无法打开终端页面"));
      return;
    }
    main.openTerminalWithCommand(argv, title);
  }

  private void confirmLogout() {
    GitHubLogin.Snapshot current = snapshot;
    if (current == null || !GhLoginState.canLogout(current.login)) return;
    if (!current.environmentReady) {
      toast(UiText.text("运行环境未就绪，请先在「安装」页完成安装"));
      return;
    }
    String message =
        current.hosts.login != null
            ? UiText.format(
                "将从本机 hosts.yml 删除账号 %s 的登录。只影响本机，不会撤销 GitHub 上的授权；如需撤销，请到 GitHub 设置中操作。",
                current.hosts.login)
            : UiText.text(
                "将从本机 hosts.yml 删除 GitHub 登录。只影响本机，不会撤销 GitHub 上的授权；如需撤销，请到 GitHub 设置中操作。");
    new DshaDialogBuilder(requireContext())
        .setTitle(UiText.text("退出 GitHub 登录？"))
        .setMessage(message)
        .setPositiveButton(UiText.text("退出"), (dialog, which) -> logout(current.hosts.login))
        .setNegativeButton(UiText.text("取消"), null)
        .show();
  }

  private void logout(String account) {
    if (loading) return;
    setLoading(true);
    final android.content.Context app = requireContext().getApplicationContext();
    Thread thread =
        new Thread(
            () -> {
              GitHubLogin.LogoutResult result = GitHubLogin.logout(app, account);
              MAIN.post(
                  () -> {
                    if (!isAdded() || getView() == null) return;
                    setLoading(false);
                    if (result.ok) {
                      toast(UiText.text("已退出 GitHub 登录（仅本机）"));
                    } else {
                      showLogoutFailure(result);
                    }
                    reload();
                  });
            },
            "dsha-github-logout-" + TASKS.incrementAndGet());
    thread.setDaemon(true);
    thread.start();
  }

  private void showLogoutFailure(GitHubLogin.LogoutResult result) {
    String message =
        result.problem == GitHubLogin.Problem.ENV_NOT_READY
            ? UiText.text("运行环境未就绪，请先在「安装」页完成安装")
            : result.detail == null || result.detail.isEmpty()
                ? UiText.text("退出失败")
                : UiText.format("退出失败：%s", result.detail);
    new DshaDialogBuilder(requireContext())
        .setTitle(UiText.text("退出失败"))
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, null)
        .show();
  }

  private void toast(String message) {
    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
  }
}
