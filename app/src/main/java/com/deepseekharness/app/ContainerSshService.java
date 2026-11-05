package com.deepseekharness.app;

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
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.RuntimeTasks;
import com.deepseekharness.app.runtime.IsolatedInstallProcess;
import com.deepseekharness.app.runtime.ContainerSshAssets;
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
  private IsolatedInstallProcess process;
  private RuntimeTasks lease;

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
    ConfigStore config = new ConfigStore(context);
    if (!supported(context) || !config.isSshEnabled() || !config.hasSshPassword()) return false;
    if (instance != null) return !instance.cancelled;
    if (!HarnessController.get(context).isEnvironmentReady()) return false;
    if (config.isSshLanEnabled()
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
      new ConfigStore(this).setSshEnabled(false);
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
      ConfigStore config = new ConfigStore(this);
      if (!config.isSshEnabled()) throw new IOException("SSH_DISABLED");
      if (config.isSshLanEnabled()
          && !com.deepseekharness.app.bridge.LocalNetworkAccess.granted(this))
        throw new IOException("SSH_LAN_PERMISSION_REQUIRED");
      HarnessController controller = HarnessController.get(this);
      if (!controller.isEnvironmentReady()) throw new IOException("SSH_RUNTIME_NOT_READY");
      String secret = config.getSshPassword();
      if (!ContainerSshPolicy.validPassword(secret))
        throw new IOException("SSH_PASSWORD_UNAVAILABLE");
      password = secret.toCharArray();
      lease = RuntimeTasks.beginDetached("容器 SSH");
      // 监督器先完成原生出生身份握手，然后才接收密码所在的第二行 stdin。
      process = controller.proot().startSsh(ContainerSshPolicy.script(config.isSshLanEnabled()));
      byte[] bytes = (new String(password) + "\n").getBytes(StandardCharsets.UTF_8);
      try {
        process.getOutputStream().write(bytes);
        process.getOutputStream().flush();
      } finally {
        java.util.Arrays.fill(bytes, (byte) 0);
      }
      CountDownLatch listening = new CountDownLatch(1);
      IsolatedInstallProcess child = process;
      String expectedAddress = config.isSshLanEnabled() ? "0.0.0.0" : "127.0.0.1";
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

  private void read(IsolatedInstallProcess child, String address, CountDownLatch listening) {
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

  private void closeOwned() {
    try {
      if (process != null) process.close();
      process = null;
      if (lease != null) lease.close();
      lease = null;
      if (cancelled || "running".equals(state)) state = "stopped";
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
    } catch (RuntimeException uncertain) {
      state = "SSH_CLEANUP_UNCONFIRMED";
      // 不释放租约，不停止通知，不用端口/进程名猜测并终止其他进程。
      notifyState();
    }
  }

  private Notification notification() {
    Intent stop = new Intent(this, ContainerSshService.class).setAction(STOP);
    PendingIntent action =
        PendingIntent.getService(
            this, NOTICE, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return new NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_terminal)
        .setContentTitle(UiText.choose("容器 SSH", "Container SSH"))
        .setContentText(state + " · dsha · " + PORT)
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
