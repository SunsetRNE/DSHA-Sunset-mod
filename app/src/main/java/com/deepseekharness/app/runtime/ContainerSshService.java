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
                    keysDirectory = ContainerSshAssets.bind(this, builder);
                    // 上一次运行留下的 sshd.pid 可能是陈旧文件（pid 已被复用）：启动前删掉，
                    // 否则停止时会拿别人/旧进程的 pid 等超时或误判成未确认。
                    new java.io.File(keysDirectory, "sshd.pid").delete();
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
        if (line.equals("Server listening on " + address + " port " + PORT + "."))
          listening.countDown();
        // 只消费输出，不保存用户名、客户端地址、会话命令或密码。
      }
    } catch (IOException ignored) {
    }
  }

  private void requestStop() {
    cancelled = true;
    state = "stopping";
    if (!worker.isShutdown()) worker.execute(this::closeOwned);
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
      long timeoutMs) {
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
    if (!com.deepseekharness.app.util.ContainerSshExitProof.awaitPidExit(pidFile, timeoutMs))
      return false;
    if (lease != null) lease.retainUntilExit(process);
    return true;
  }

  private void closeOwned() {
    try {
      if (process != null) {
        java.nio.file.Path pidFile =
            keysDirectory == null ? null : keysDirectory.toPath().resolve("sshd.pid");
        if (!teardownGuestFirst(process, lease, pidFile, 10_000)) {
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
      if (cancelled || "running".equals(state)) state = "stopped";
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
    } catch (RuntimeException uncertain) {
      state = "SSH_CLEANUP_UNCONFIRMED";
      // 不释放租约，不停止通知，不用端口/进程名猜测并终止其他进程。
      notifyState();
    }
  }

  /** 通知里的状态用可读文案；未知状态码（诊断用）保持原样不翻译。 */
  private static String displayState(String raw) {
    if (raw == null) return "";
    return switch (raw) {
      case "running" -> UiText.text("运行中");
      case "starting" -> UiText.text("启动中…");
      case "stopping" -> UiText.text("停止中…");
      default -> raw;
    };
  }

  private Notification notification() {
    Intent stop = new Intent(this, ContainerSshService.class).setAction(STOP);
    PendingIntent action =
        PendingIntent.getService(
            this, NOTICE, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return new NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_terminal)
        .setContentTitle(UiText.choose("容器 SSH", "Container SSH"))
        .setContentText(displayState(state) + " · dsha · " + PORT)
        .setOngoing(true)
        .addAction(0, UiText.choose("停止", "Stop"), action)
        .setCategory(Notification.CATEGORY_SERVICE)
        .build();
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
