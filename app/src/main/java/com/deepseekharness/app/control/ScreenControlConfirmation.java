package com.deepseekharness.app.control;

import com.deepseekharness.app.capabilities.CapabilityRequest;
import com.deepseekharness.app.util.ScreenTarget;
import java.util.function.Consumer;

/** Installed by native UI. The coordinator rechecks all fences after an asynchronous answer. */
@FunctionalInterface
public interface ScreenControlConfirmation {
  void confirm(CapabilityRequest request, ScreenTarget target, Consumer<Boolean> completion);
}
