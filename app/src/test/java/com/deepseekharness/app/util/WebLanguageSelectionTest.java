package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class WebLanguageSelectionTest {
  private static final String PAGE_URL = "http://127.0.0.1:41821/";
  private static final PreviewPageSession.Identity PAGE =
      new PreviewPageSession.Identity(7, "original", PAGE_URL);

  @Test
  public void hostChangeRunsOnlyInTheNextUiTurn() {
    var scheduled = new ArrayDeque<Runnable>();
    var changes = new ArrayList<String>();

    new WebLanguageSelection().post("en", scheduled::add, () -> true, () -> PAGE, changes::add);

    assertTrue(changes.isEmpty());
    scheduled.remove().run();
    assertEquals(List.of("en"), changes);
  }

  @Test
  public void latestValidSelectionWinsAndWebOnlyChoicesNeverApplyToAndroid() {
    var request = new WebLanguageSelection();
    var scheduled = new ArrayDeque<Runnable>();
    var changes = new ArrayList<String>();

    for (String language : List.of("en", "fr", "zh", "es", "system")) {
      request.post(language, scheduled::add, () -> true, () -> PAGE, changes::add);
    }

    assertEquals(3, scheduled.size());
    scheduled.forEach(Runnable::run);
    assertEquals(List.of("zh"), changes);
  }

  @Test
  public void aPageThatHasBeenReplacedCannotChangeTheAppLanguage() {
    var request = new WebLanguageSelection();
    var scheduled = new ArrayDeque<Runnable>();
    var changes = new ArrayList<String>();
    var currentPage = new AtomicBoolean(true);

    request.post("en", scheduled::add, currentPage::get, () -> PAGE, changes::add);
    currentPage.set(false);
    request.post("zh", scheduled::add, currentPage::get, () -> PAGE, changes::add);

    assertEquals(1, scheduled.size());
    scheduled.remove().run();
    assertTrue(changes.isEmpty());
  }

  @Test
  public void sameVisibleHostRejectsQueuedLanguageFromAnOldGenerationOrInstance() {
    var request = new WebLanguageSelection();
    var scheduled = new ArrayDeque<Runnable>();
    var changes = new ArrayList<String>();
    var identity = new AtomicReference<>(PAGE);

    request.post("en", scheduled::add, () -> true, identity::get, changes::add);
    identity.set(new PreviewPageSession.Identity(8, PAGE.instanceId(), PAGE_URL));
    scheduled.remove().run();

    request.post("fr", scheduled::add, () -> true, identity::get, changes::add);
    identity.set(new PreviewPageSession.Identity(8, "replacement", PAGE_URL));
    scheduled.remove().run();
    assertTrue(changes.isEmpty());

    request.post("zh", scheduled::add, () -> true, identity::get, changes::add);
    scheduled.remove().run();
    assertEquals(List.of("zh"), changes);
  }
}
