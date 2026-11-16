package com.deepseekharness.app.assistant;

import android.content.Context;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.util.ApplicationServiceSlot;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** 应用级任务呈现所有者；不持有第二份后端连接、运行状态或手机操作权威。 */
public final class AssistantWindowOwner {
  // 只供原无参静态 API 找到应用所有者；选中任务和服务不存放在静态字段中。
  private static volatile WeakReference<AssistantWindowOwner> legacy = new WeakReference<>(null);

  private record Selection(
      AssistantSessionController controller,
      java.util.function.Consumer<com.deepseekharness.app.control.ControlTaskAuthority.Binding>
          cancellation) {}

  private final AtomicReference<Selection> selected = new AtomicReference<>();
  private final ApplicationServiceSlot<AssistantService> services = new ApplicationServiceSlot<>();
  private final ConcurrentHashMap<String, AssistantService.Pending> pending =
      new ConcurrentHashMap<>();
  private AssistantWindow surface;
  private AssistantService surfaceService;

  public static AssistantWindowOwner from(Context context) {
    AssistantWindowOwner owner = DshaApp.from(context).assistantWindowOwner();
    legacy = new WeakReference<>(owner);
    return owner;
  }

  static AssistantWindowOwner legacy() {
    return legacy.get();
  }

  public AssistantSessionController current() {
    Selection value = selected.get();
    return value == null ? null : value.controller();
  }

  boolean isCurrent(AssistantSessionController controller) {
    return current() == controller;
  }

  /** 旧订阅先失效，再发布新选择；release 只用 CAS，不反向取得本锁。 */
  synchronized void select(
      AssistantSessionController controller,
      java.util.function.Consumer<com.deepseekharness.app.control.ControlTaskAuthority.Binding>
          cancellation,
      Runnable activate) {
    AssistantSessionController previous = current();
    if (previous != null && previous != controller) previous.close();
    closeSurface();
    selected.set(new Selection(controller, cancellation));
    activate.run();
  }

  boolean release(AssistantSessionController controller) {
    Selection value = selected.get();
    return value != null && value.controller() == controller && selected.compareAndSet(value, null);
  }

  java.util.function.Consumer<com.deepseekharness.app.control.ControlTaskAuthority.Binding>
      cancellationFor(AssistantSessionController controller) {
    Selection value = selected.get();
    if (value == null || value.controller() != controller)
      throw new IllegalStateException("ASSISTANT_TASK_CHANGED");
    WeakReference<Selection> captured = new WeakReference<>(value);
    return task -> {
      Selection expected = captured.get();
      if (expected == null) return;
      synchronized (expected.controller()) {
        if (selected.get() == expected && expected.controller().ownsTask(task))
          expected.cancellation().accept(task);
      }
    };
  }

  synchronized void attach(AssistantService service) {
    AssistantService previous = services.current();
    if (previous != null && previous != service) previous.retirePresentation();
    services.attach(service);
  }

  AssistantService service() {
    return services.current();
  }

  synchronized boolean release(AssistantService service) {
    if (services.current() != service) return false;
    services.release(service);
    closeSurface(service);
    return true;
  }

  ConcurrentHashMap<String, AssistantService.Pending> pending() {
    return pending;
  }

  /** Service 保留平台句柄的投影；交接与释放资格由同一个应用所有者判断。 */
  synchronized AssistantWindow showSurface(
      AssistantService service, AssistantSessionController controller) {
    if (services.current() != service || current() != controller)
      throw new IllegalStateException("ASSISTANT_TASK_CHANGED");
    closeSurface();
    com.deepseekharness.app.OverlayController.teardown(service);
    AssistantWindow window = new AssistantWindow(service, controller);
    surfaceService = service;
    surface = window;
    window.show();
    return window;
  }

  synchronized void closeSurface(AssistantService service) {
    if (surfaceService == service) closeSurface();
  }

  private synchronized void closeSurface() {
    AssistantWindow previous = surface;
    surface = null;
    surfaceService = null;
    if (previous != null) previous.close();
  }
}
