package com.deepseekharness.app.util;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Applies the latest supported selection after returning from the WebView callback. */
public final class WebLanguageSelection {
  private long sequence;

  public void post(
      String language,
      Consumer<Runnable> post,
      BooleanSupplier currentPage,
      Supplier<PreviewPageSession.Identity> currentIdentity,
      Consumer<String> apply) {
    if (!UiLanguagePreference.isSupportedLanguage(language) || !currentPage.getAsBoolean()) return;
    PreviewPageSession.Identity selectedPage = currentIdentity.get();
    if (selectedPage == null) return;
    long requested = ++sequence;
    post.accept(
        () -> {
          if (requested == sequence
              && currentPage.getAsBoolean()
              && selectedPage.equals(currentIdentity.get())) apply.accept(language);
        });
  }
}
