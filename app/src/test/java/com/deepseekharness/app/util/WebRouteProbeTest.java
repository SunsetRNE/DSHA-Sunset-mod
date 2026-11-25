package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.Locale;
import org.junit.Test;

/** 路由探针只接收固定状态、挂载点和当前探针代次。 */
public final class WebRouteProbeTest {
  @Test
  public void parseKeepsOnlyKnownFieldsAndSanitizesPath() {
    var result =
        WebRouteProbe.parse(
            WebRouteProbe.PREFIX
                + "generation=41 path=/config?token=secret#fragment mount=%23root state=blank");
    assertNotNull(result);
    assertTrue(result.blank());
    assertTrue(result.readable());
    assertEquals(41, result.generation());
    assertEquals("/config", result.path());
    assertEquals("#root", result.mount());
    assertEquals("path=/config mount=#root state=blank", result.record());
  }

  @Test
  public void contentAndDeferredStatesNeverTriggerBlankFallback() {
    for (String state : new String[] {"rendered", "deferred", "unknown"}) {
      var result =
          WebRouteProbe.parse(WebRouteProbe.PREFIX + "generation=1 path=/a mount= state=" + state);
      assertNotNull(result);
      assertFalse(result.blank());
      assertEquals(state.equals("rendered"), result.readable());
    }
  }

  @Test
  public void mountIsAClosedSet() {
    var optedIn =
        WebRouteProbe.parse(
            WebRouteProbe.PREFIX + "generation=1 path=/a mount=%5Bdata-dsha-app%5D state=rendered");
    assertEquals("[data-dsha-app]", optedIn.mount());
    var unknown =
        WebRouteProbe.parse(
            WebRouteProbe.PREFIX
                + "generation=1 path=/a mount=%23bogus state=unknown extra=secret");
    assertEquals("", unknown.mount());
    assertFalse(unknown.record().contains("secret"));
    assertFalse(unknown.record().contains("bogus"));
  }

  @Test
  public void rejectMissingOrInvalidNavigationAndStates() {
    for (String fields :
        new String[] {
          "path=/a state=blank",
          "generation=-1 path=/a state=blank",
          "generation=abc path=/a state=blank",
          "generation=4 path=/a",
          "generation=4 path=/a state=private",
          "generation=4 state=blank"
        }) {
      assertNull(WebRouteProbe.parse(WebRouteProbe.PREFIX + fields));
    }
  }

  @Test
  public void malformedOrForeignLinesAreRejected() {
    assertNull(WebRouteProbe.parse(null));
    assertNull(WebRouteProbe.parse("[DSHA_PAGE] {}"));
    assertNull(WebRouteProbe.parse(WebRouteProbe.PREFIX));
    assertNull(WebRouteProbe.parse(WebRouteProbe.PREFIX + "no-fields"));
    assertNull(WebRouteProbe.parse(WebRouteProbe.PREFIX + "path=/a\npath=/b"));
    assertNull(WebRouteProbe.parse(WebRouteProbe.PREFIX + "x".repeat(4000)));
  }

  @Test
  public void paintTimingAllowsOnlyBoundedWholeMilliseconds() {
    String row = WebRouteProbe.PREFIX + "generation=4 path=/a state=rendered";
    assertEquals(123, WebRouteProbe.parse(row + " fcpMs=123").fcpMs());
    assertTrue(WebRouteProbe.parse(row + " fcpMs=123").record().endsWith(" fcpMs=123"));
    for (String invalid : new String[] {"-1", "3600001", "0.1", "Infinity", "NaN"}) {
      var parsed = WebRouteProbe.parse(row + " fcpMs=" + invalid);
      assertEquals(-1, parsed.fcpMs());
      assertFalse(parsed.record().contains("fcpMs"));
    }
  }

  @Test
  public void scriptKeepsHooksAndUsesOnlyBoundedDomEvidence() {
    String script = WebRouteProbe.script(4, "\"http://127.0.0.1:3080/\"");
    assertTrue(script.contains("unhandledrejection"));
    assertTrue(script.contains("[DSHA_ROUTE]"));
    assertTrue(script.contains("__dshaErrCount"));
    assertTrue(script.contains("checked < 24"));
    assertTrue(script.contains("substringData(0, 64)"));
    assertTrue(script.contains("var candidate = null"));
    assertTrue(script.contains("if (opaque || (paintMs >= 0 && !routeDocument.moved))"));
    assertTrue(script.contains("getComputedStyle(el, '::before').content"));
    assertTrue(script.contains("visibilitychange"));
    assertTrue(script.contains("location.href !== expectedUrl"));
    assertFalse(script.contains("querySelectorAll"));
    assertFalse(script.contains("innerText"));
    assertFalse(script.contains("textContent"));
    assertFalse(script.contains("location.search"));
    assertFalse(script.contains("console.log(location"));
    assertTrue(WebRouteProbe.cancelScript(4).contains("job.generation===4"));
    assertTrue(WebRouteProbe.confirmScript(4).contains("job.generation===4&&job.confirm"));
    assertTrue(script.contains("getEntriesByName('first-contentful-paint', 'paint')"));
  }

  @Test
  public void scriptUsesAsciiGenerationInEveryDefaultLocale() {
    Locale previous = Locale.getDefault(Locale.Category.FORMAT);
    try {
      Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG"));
      assertTrue(WebRouteProbe.script(123, "\"https://example.test/\"").contains("})(123, \""));
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, previous);
    }
  }
}
