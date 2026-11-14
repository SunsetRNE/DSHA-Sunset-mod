package com.deepseekharness.app.ui;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.saf.SafTransferHistory;
import com.deepseekharness.app.saf.SafTree;
import com.deepseekharness.app.saf.SafWorkbench;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 旋转/切换语言时继续持有当前传输；仅保存结构化结果，文案在界面显示时翻译。 */
public final class SafWorkbenchModel extends AndroidViewModel {
  public static final class Outcome {
    final boolean running;
    final long bytes;
    final String code;

    Outcome(boolean running, long bytes, String code) {
      this.running = running;
      this.bytes = bytes;
      this.code = code;
    }
  }

  final SafWorkbench workbench;
  final MutableLiveData<Outcome> outcome = new MutableLiveData<>();
  final AtomicBoolean working = new AtomicBoolean();
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  public SafWorkbenchModel(@NonNull Application application) {
    super(application);
    workbench =
        new SafWorkbench(
            application, () -> HarnessController.get(application).proot().getRootfsDir());
  }

  void transfer(
      SafWorkbench.Ticket selection, String remotePath, String guestPath, boolean importing) {
    if (!working.compareAndSet(false, true)) {
      workbench.cancel(selection);
      return;
    }
    outcome.setValue(new Outcome(true, 0, ""));
    executor.execute(
        () -> {
          String record = null;
          long bytes = 0;
          String code = "IO_ERROR";
          SafTransferHistory history = null;
          SafWorkbench.Ticket ticket = selection;
          try {
            history = SafTransferHistory.open(getApplication());
            if (!SafWorkbenchUi.enabled(getApplication()))
              throw new IllegalStateException("WORKBENCH_DISABLED");
            if (!importing) ticket = workbench.retarget(selection, remotePath);
            int slash = ticket.path.lastIndexOf('/');
            String name = ticket.path.substring(slash + 1);
            record = history.begin(name, importing, System.currentTimeMillis());
            bytes = workbench.transfer(ticket, guestPath, importing);
            code = "OK";
          } catch (Exception failure) {
            code = failureCode(failure);
          } finally {
            workbench.cancel(selection);
            workbench.cancel(ticket);
            if (record != null && !history.finish(record, bytes, code) && "OK".equals(code))
              code = "OK_HISTORY_UNAVAILABLE";
            working.set(false);
            outcome.postValue(new Outcome(false, bytes, code));
          }
        });
  }

  static String failureCode(Exception error) {
    String code = error instanceof SafTree.Failure failure ? failure.code : error.getMessage();
    return code != null && code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "IO_ERROR";
  }

  @Override
  protected void onCleared() {
    executor.shutdownNow();
  }
}
