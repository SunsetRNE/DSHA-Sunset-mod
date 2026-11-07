package com.deepseekharness.app.runtime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.RuntimeWorkPort;
import com.deepseekharness.app.util.ContainerSshPolicy;
import com.deepseekharness.app.util.ContainerSshStatus;
import com.deepseekharness.app.util.UiText;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 用户启动的容器 SSH；独立进程组未确认退出时保留维护屏障。 */
public final class ContainerSshService extends Service {
  private static final String START = "dsha.container.ssh.START";
  private static final String STOP = "dsha.container.ssh.STOP";
  private static final String CHANNEL = "dsha_container_ssh";
  private static final int NOTICE = 9071;
  public static final int PORT = ContainerSshPolicy.PORT;
  private static final long GUEST_EXIT_TIMEOUT_MS = 10_000;
  private static final int MAX_TAIL_LINES = 12;
  private static volatile String state = "stopped";
  private static volatile ContainerSshService instance;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread thread = new Thread(r, "dsha-container-ssh-control");
            thread.setDaemon(true);
            return thread;
          });
  private volatile boolean cancelled;
  private Process process;
  private RuntimeWorkPort.Work lease;
  private volatile java.io.File keysDirectory;

  /** 本次实际确认的监听地址；状态与通知按它渲染，不按偏好推导。 */
  private volatile String listeningAddress;

  /** 子进程输出的最后若干行（脱敏后只用于失败诊断，不落用户可见文本）。 */
  private final java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();

  public static boolean supported(Context context) {
    return ContainerSshAssets.supported(context);
  }

  public static boolean isRunning() {
    return "running".equals(state);
  }

  public static boolean isActive() {
    return instance != null;
  }

  public static String status() {
    return state;
  }

  /**
   * 实际监听范围，而不是偏好里的开关。
   *
   * <p>切换「允许局域网」目前不停服务，若状态行按偏好渲染，就会出现 sshd 仍在 0.0.0.0 上监听、
   * 界面却写「仅本机」——对一个以「默认只听回环」为卖点的暴露面，这是安全相关的错误陈述。
   */
  public static boolean activeLan() {
    ContainerSshService owner = instance;
    if (owner != null && owner.listeningAddress != null)
      return "0.0.0.0".equals(owner.listeningAddress);
    return false;
  }

  /**
   * 用户改了监听范围但服务还在按旧地址跑。切换目前不自动重启（重启要等 guest 退出，
   * 期间再次 start 会被拒绝），所以状态里必须明确说「需重启生效」，不能假装已生效。
   */
  public static boolean needsRestart(Context context) {
    ContainerSshService owner = instance;
    if (owner == null || owner.listeningAddress == null) return false;
    boolean want = ContainerSshSettings.lanEnabled(context);
    return want != "0.0.0.0".equals(owner.listeningAddress);
  }

  public static boolean activeLan(Context context) {
    ContainerSshService owner = instance;
    if (owner != null && owner.listeningAddress != null)
      return "0.0.0.0".equals(owner.listeningAddress);
    return ContainerSshSettings.lanEnabled(context);
  }

  public static boolean start(Context context) {
    if (!supported(context)
        || !ContainerSshSettings.enabled(context)
        || !ContainerSshSettings.hasPassword(context)) return false;
    if (instance != null) return !instance.cancelled;
    if (!HarnessController.get(context).isEnvironmentReady()) return false;
    if (ContainerSshSettings.lanEnabled(context)
        && !com.deepseekharness.app.bridge.LocalNetworkAccess.granted(context)) return false;
    try {
      ContextCompat.startForegroundService(
          context.getApplicationContext(),
          new Intent(context, ContainerSshService.class).setAction(START));
      return true;
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  public static void stop(Context context) {
    ContainerSshService owner = instance;
    if (owner != null) owner.requestStop();
  }

  @Override
  public void onCreate() {
    super.onCreate();
    instance = this;
    NotificationManager manager = getSystemService(NotificationManager.class);
    if (manager != null)
      manager.createNotificationChannel(
          new NotificationChannel(
              CHANNEL,
              UiText.choose("容器 SSH", "Container SSH"),
              NotificationManager.IMPORTANCE_LOW));
    state = "starting";
    startForeground(NOTICE, notification());
  }

  @Override
  public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
    if (intent == null || STOP.equals(intent.getAction())) {
      // 系统重启投递的 null intent 只代表上次进程被杀：停掉监督但不改用户设置，
      // 用户重新打开开关即可再启动（开关状态保留，不静默永久关闭能力）。
      requestStop();
    } else if (START.equals(intent.getAction())) {
      worker.execute(this::launch);
    } else requestStop();
    return START_NOT_STICKY;
  }

  private void launch() {
    if (cancelled || process != null) return;
    char[] password = null;
    try {
      if (!ContainerSshSettings.enabled(this)) throw new IOException("SSH_DISABLED");
      if (ContainerSshSettings.lanEnabled(this)
          && !com.deepseekharness.app.bridge.LocalNetworkAccess.granted(this))
        throw new IOException("SSH_LAN_PERMISSION_REQUIRED");
      HarnessController controller = HarnessController.get(this);
      if (!controller.isEnvironmentReady()) throw new IOException("SSH_RUNTIME_NOT_READY");
      // 对账必须在起新进程之前：App 被系统杀掉时 proot+sshd 会被 reparent 到 init 继续跑，
      // 通知随进程消失，端口在没有任何提示的情况下继续开放。
      keysDirectory = ContainerSshAssets.keysDirectory(this);
      reconcilePrevious(keysDirectory);
      String secret = ContainerSshSettings.password(this);
      if (!ContainerSshPolicy.validPassword(secret))
        throw new IOException("SSH_PASSWORD_UNAVAILABLE");
      password = secret.toCharArray();
      lease = RuntimeWorkPort.beginDetached("容器 SSH");
      // pipedInput 让 RuntimeLauncher 保留默认 PIPE 标准输入（不再重定向 /dev/null）；
      // 脚本从 stdin 第一行读密码，密码只进本管道，不拼接进 argv 或日志。
      process =
          controller
              .proot()
              .execRootfsWithSpec(
                  "bash -lc "
                      + com.deepseekharness.app.util.ShellQuote.arg(
                          ContainerSshPolicy.script(ContainerSshSettings.lanEnabled(this))),
                  builder -> {
                    // pid 文件不再无条件删除：它是判断「上次的 sshd 是否还在」的唯一证据。
                    // 清除/终止在 reconcilePrevious 里按身份完成。
                    keysDirectory = ContainerSshAssets.bind(this, builder);
                    builder.pipedInput(true);
                  });
      byte[] bytes = utf8WithNewline(password);
      try {
        process.getOutputStream().write(bytes);
        process.getOutputStream().flush();
      } finally {
        java.util.Arrays.fill(bytes, (byte) 0);
      }
      CountDownLatch listening = new CountDownLatch(1);
      Process child = process;
      String expectedAddress = ContainerSshSettings.lanEnabled(this) ? "0.0.0.0" : "127.0.0.1";
      Thread reader = new Thread(() -> read(child, expectedAddress, listening), "dsha-ssh-output");
      reader.setDaemon(true);
      reader.start();
      if (!listening.await(15, TimeUnit.SECONDS) || !child.isAlive() || cancelled)
        throw new IOException("SSH_LISTENER_UNCONFIRMED");
      listeningAddress = expectedAddress;
      recordGuestIdentity(keysDirectory);
      state = "running";
      notifyState();
      Thread watcher =
          new Thread(
              () -> {
                try {
                  child.waitFor();
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
                if (!cancelled) requestStop();
              },
              "dsha-ssh-exit");
      watcher.setDaemon(true);
      watcher.start();
    } catch (Exception failure) {
      state = failure instanceof IOException ? failure.getMessage() : "SSH_START_FAILED";
      recordFailure(failure);
      closeOwned();
    } finally {
      if (password != null) java.util.Arrays.fill(password, '\0');
    }
  }

  /** char[] 直接编码为「密码+\n」的 UTF-8 字节；不经过 String，调用方用后清零。 */
  private static byte[] utf8WithNewline(char[] password) {
    java.nio.ByteBuffer encoded = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(password));
    byte[] bytes = new byte[encoded.remaining() + 1];
    encoded.get(bytes, 0, encoded.remaining());
    bytes[bytes.length - 1] = '\n';
    encoded.rewind();
    while (encoded.hasRemaining()) encoded.put((byte) 0);
    return bytes;
  }

  private void read(Process child, String address, CountDownLatch listening) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
      for (String line; (line = reader.readLine()) != null; ) {
        if (line.equals("Server listening on " + address + " port " + PORT + ".")) {
          listening.countDown();
          continue;
        }
        // 只留最后若干行用于失败诊断（脚本不会输出密码；客户端地址与用户名不记录）。
        synchronized (tail) {
          if (!line.isBlank()) tail.addLast(line.length() > 200 ? line.substring(0, 200) : line);
          while (tail.size() > MAX_TAIL_LINES) tail.removeFirst();
        }
      }
    } catch (IOException ignored) {
    }
  }

  private void requestStop() {
    cancelled = true;
    state = "stopping";
    if (!worker.isShutdown()) worker.execute(this::closeOwned);
  }

  private static java.nio.file.Path pidFile(java.io.File keysDirectory) {
    return keysDirectory == null ? null : keysDirectory.toPath().resolve("sshd.pid");
  }

  private static java.nio.file.Path identityFile(java.io.File keysDirectory) {
    return keysDirectory == null ? null : keysDirectory.toPath().resolve("sshd.identity");
  }

  /**
   * 启动前对账：上一次的 sshd 可能还在跑。
   *
   * <p>App 被系统杀掉（force stop / OOM / 崩溃）时，Android 只杀 App 的 pid，proot 与 sshd
   * 被 reparent 到 init 继续运行，前台通知随进程消失。局域网模式下这意味着端口在没有任何
   * 提示的情况下持续开放。pid 文件是唯一证据，所以不再像以前那样一进来就 delete()。
   *
   * <p>身份（pid + starttime）一致才终止；不一致或没有身份记录说明无法确认这是我们的 sshd，
   * 拒绝启动而不是误杀别人的进程。
   */
  private void reconcilePrevious(java.io.File keysDirectory) throws IOException {
    java.nio.file.Path pidFile = pidFile(keysDirectory);
    if (pidFile == null || !java.nio.file.Files.isRegularFile(pidFile)) return;
    Integer pid = com.deepseekharness.app.util.ContainerSshExitProof.readPid(pidFile);
    if (pid == null || pid <= 0) return;
    StringBuilder live = new StringBuilder();
    if (com.deepseekharness.app.util.ContainerSshExitProof.observe(pid, live)
        != com.deepseekharness.app.util.ContainerSshExitProof.State.ALIVE) return;
    String recorded = "";
    java.nio.file.Path identity = identityFile(keysDirectory);
    try {
      recorded =
          new String(java.nio.file.Files.readAllBytes(identity), StandardCharsets.UTF_8).trim();
    } catch (IOException | RuntimeException unreadable) {
      // 没有身份记录：不能确认这是上一次的 sshd（pid 可能已被复用）
    }
    if (recorded.isEmpty() || !recorded.equals(live.toString()))
      throw new IOException("SSH_ORPHAN_UNVERIFIED");
    terminate(pid);
    if (!com.deepseekharness.app.util.ContainerSshExitProof.awaitPidExit(pidFile, 4_000, true))
      throw new IOException("SSH_ORPHAN_RUNNING");
  }

  /** 记录本次 sshd 的出生身份，供下次启动对账；写不进去不影响本次运行。 */
  private static void recordGuestIdentity(java.io.File keysDirectory) {
    java.nio.file.Path pidFile = pidFile(keysDirectory);
    java.nio.file.Path identity = identityFile(keysDirectory);
    if (pidFile == null || identity == null) return;
    Integer pid = com.deepseekharness.app.util.ContainerSshExitProof.readPid(pidFile);
    if (pid == null) return;
    StringBuilder live = new StringBuilder();
    if (com.deepseekharness.app.util.ContainerSshExitProof.observe(pid, live)
        != com.deepseekharness.app.util.ContainerSshExitProof.State.ALIVE) return;
    try {
      java.nio.file.Files.writeString(identity, live.toString(), StandardCharsets.UTF_8);
    } catch (IOException ignored) {
      // 只影响下次对账的精度，不影响本次
    }
  }

  /** 按 pid 精确终止（调用方已用 starttime 校验过身份）。 */
  private static void terminate(int pid) {
    try {
      android.system.Os.kill(pid, android.system.OsConstants.SIGTERM);
    } catch (Exception ignored) {
      // 权限或进程已退出：交给随后的等待判定
    }
  }

  /** 启动失败时把原因与最后几行输出写进诊断日志；不写进用户可见文本。 */
  private void recordFailure(Exception failure) {
    StringBuilder detail = new StringBuilder(String.valueOf(failure.getMessage()));
    synchronized (tail) {
      for (String line : tail) detail.append('\n').append(line);
    }
    com.deepseekharness.app.core.DiagnosticLog.record(this, "container-ssh", detail.toString());
  }

  /**
   * 停止时的收尾顺序（抽成静态方法是为了能用 fake Process / fake 租约测）。
   *
   * <p>先确认 guest 退出，再把启动器退出交给租约 watcher：{@code retainUntilExit} 会把
   * token detach 给 watcher，watcher 在启动器退出时就 close；proroot 下启动器死得比 guest
   * 早，先 attach 就等于提前释放维护屏障。
   *
   * @return true 表示已确认 guest 退出；false 表示超时未确认，调用方必须保持租约与通知。
   */
  static boolean teardownGuestFirst(
      Process process,
      com.deepseekharness.app.util.RuntimeWorkPort.Work lease,
      java.nio.file.Path pidFile,
      long timeoutMs,
      boolean requireGuestProof,
      java.util.function.IntConsumer terminator) {
    if (process == null) {
      if (lease != null) lease.close();
      return true;
    }
    try {
      process.getInputStream().close();
    } catch (java.io.IOException | RuntimeException ignored) {
      // 流已经结束
    }
    process.destroyForcibly();
    boolean exited =
        com.deepseekharness.app.util.ContainerSshExitProof.awaitPidExit(
            pidFile, timeoutMs, requireGuestProof);
    if (!exited && requireGuestProof) {
      // proroot 的 argv 里没有 --kill-on-exit：destroyForcibly 只杀启动器，guest 会变孤儿。
      // 已经解析出精确 pid，就按身份再杀一次，而不是等满超时后永远报「未确认」。
      Integer pid = com.deepseekharness.app.util.ContainerSshExitProof.readPid(pidFile);
      if (pid != null && terminator != null) terminator.accept(pid);
      exited =
          com.deepseekharness.app.util.ContainerSshExitProof.awaitPidExit(pidFile, 3_000, true);
    }
    if (!exited) return false;
    if (lease != null) lease.retainUntilExit(process);
    return true;
  }

  private void closeOwned() {
    try {
      if (process != null) {
        boolean requireProof = "running".equals(state) || "stopping".equals(state);
        if (!teardownGuestFirst(
            process,
            lease,
            pidFile(keysDirectory),
            GUEST_EXIT_TIMEOUT_MS,
            requireProof,
            ContainerSshService::terminate)) {
          // 未确认 guest 退出：保持租约与通知，不改写为 stopped。
          state = "SSH_CLEANUP_UNCONFIRMED";
          notifyState();
          return;
        }
        process = null;
        lease = null;
      } else if (lease != null) {
        lease.close();
        lease = null;
      }
      // 先归一化再判失败：否则「启动中按停止」会带着旧失败态走 DETACH，
      // 留下一条 ongoing、写着「已停止」、不可滑掉的通知。
      if (cancelled || "running".equals(state)) state = "stopped";
      boolean failure = ContainerSshStatus.isFailure(state);
      if (failure) {
        // 失败/未确认：把结论与三要素留在通知里，服务退出但通知不撤。
        notifyState();
        stopForeground(STOP_FOREGROUND_DETACH);
      } else {
        stopForeground(STOP_FOREGROUND_REMOVE);
      }
      stopSelf();
    } catch (RuntimeException uncertain) {
      state = "SSH_CLEANUP_UNCONFIRMED";
      // 不释放租约，不停止通知，不用端口/进程名猜测并终止其他进程。
      notifyState();
    }
  }

  /** 通知里的状态用可读文案；未知状态码（诊断用）保持原样不翻译。 */
  private static String displayState(String raw) {
    return ContainerSshStatus.label(raw);
  }

  private Notification notification() {
    Intent stop = new Intent(this, ContainerSshService.class).setAction(STOP);
    PendingIntent action =
        PendingIntent.getService(
            this, NOTICE, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    boolean failure = ContainerSshStatus.isFailure(state);
    NotificationCompat.Builder builder =
        new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle(UiText.choose("容器 SSH", "Container SSH"))
            // 正文只放短标签 + 明细：诊断码不进用户可见文本。
            .setContentText(
                ContainerSshStatus.label(state)
                    + " · "
                    + ContainerSshStatus.detail(state, PORT, activeLan(this)))
            .setOngoing(!failure)
            .setCategory(Notification.CATEGORY_SERVICE);
    // 失败态没有「停止」可点：服务已经退出，留一个能读原因的通知即可。
    if (!failure) builder.addAction(0, UiText.choose("停止", "Stop"), action);
    return builder.build();
  }

  private void notifyState() {
    NotificationManager manager = getSystemService(NotificationManager.class);
    if (manager != null) manager.notify(NOTICE, notification());
  }

  @Override
  public void onDestroy() {
    cancelled = true;
    if (instance == this) instance = null;
    if (!worker.isShutdown()) {
      worker.execute(this::closeOwned);
      worker.shutdown();
    }
    super.onDestroy();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
