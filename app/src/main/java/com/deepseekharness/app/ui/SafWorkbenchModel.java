package com.deepseekharness.app.ui;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.saf.SafTransferHistory;
import com.deepseekharness.app.saf.SafTransferJob;
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
          SafTransferJob.Result result =
              SafTransferJob.run(
                  new SafTransferJob.Ports<SafWorkbench.Ticket>() {
                    private SafTransferHistory history;

                    @Override
                    public boolean enabled() {
                      return SafWorkbenchUi.enabled(getApplication());
                    }

                    @Override
                    public void openHistory() {
                      history = SafTransferHistory.open(getApplication());
                    }

                    @Override
                    public SafWorkbench.Ticket retarget(SafWorkbench.Ticket ticket, String path)
                        throws Exception {
                      return workbench.retarget(ticket, path);
                    }

                    @Override
                    public String path(SafWorkbench.Ticket ticket) {
                      return ticket.path;
                    }

                    @Override
                    public String beginRecord(String name, boolean importing, long time) {
                      return history.begin(name, importing, time);
                    }

                    @Override
                    public long transfer(
                        SafWorkbench.Ticket ticket, String guestPath, boolean importing)
                        throws Exception {
                      return workbench.transfer(ticket, guestPath, importing);
                    }

                    @Override
                    public boolean finishRecord(String record, long bytes, String code) {
                      return history.finish(record, bytes, code);
                    }

                    @Override
                    public void cancel(SafWorkbench.Ticket ticket) {
                      workbench.cancel(ticket);
                    }

                    @Override
                    public String failureCode(Exception failure) {
                      return SafWorkbenchModel.failureCode(failure);
                    }
                  },
                  selection,
                  remotePath,
                  guestPath,
                  importing,
                  System.currentTimeMillis());
          working.set(false);
          outcome.postValue(new Outcome(false, result.bytes, result.code));
        });
  }

  static String failureCode(Exception error) {
    return error instanceof SafTree.Failure failure
        ? SafTransferJob.failureCode(new java.io.IOException(failure.code))
        : SafTransferJob.failureCode(error);
  }

  @Override
  protected void onCleared() {
    executor.shutdownNow();
  }
}
