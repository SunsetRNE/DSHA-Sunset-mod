package com.deepseekharness.app.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** C02 诊断：唯一脱敏出口，默认委托 util/SensitiveData，不新增日志出口。 */
public class DiagnosticsSinkTest {
  @Test
  public void redactsAuthorizationDetailThroughSensitiveData() {
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    DiagnosticsSink sink = DiagnosticsSink.forwarding(events::add);

    sink.report("TEST_EVENT", "Authorization: Bearer super-secret-token");

    assertEquals(1, events.size());
    assertEquals("TEST_EVENT", events.get(0).code());
    assertFalse(events.get(0).detail().contains("super-secret-token"));
    assertTrue(events.get(0).detail().contains("***"));
  }

  @Test
  public void redactsApiKeyAssignmentDetail() {
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    DiagnosticsSink sink = DiagnosticsSink.forwarding(events::add);

    sink.report("TEST_EVENT", "api_key=abcd1234");

    assertFalse(events.get(0).detail().contains("abcd1234"));
  }

  @Test
  public void invalidCodeIsNormalizedAndNullDetailIsEmpty() {
    List<DiagnosticsSink.Event> events = new ArrayList<>();
    DiagnosticsSink sink = DiagnosticsSink.forwarding(events::add);

    sink.report("bad code", null);
    sink.report(null, null);

    assertEquals(2, events.size());
    assertEquals(ContractCodes.INVALID_DIAGNOSTIC, events.get(0).code());
    assertEquals("", events.get(0).detail());
    assertEquals(ContractCodes.INVALID_DIAGNOSTIC, events.get(1).code());
  }

  @Test
  public void discardingAndFailingDownstreamNeverBreakHost() {
    DiagnosticsSink.discarding().report("TEST_EVENT", "detail");
    DiagnosticsSink.forwarding(
            event -> {
              throw new IllegalStateException("downstream");
            })
        .report("TEST_EVENT", "detail");
  }
}
