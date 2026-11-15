package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class StopHandoffTest {
  private final Object owner = new Object();
  private final StopOperationId operation = new StopOperationId(owner, 1, 7, "instance", 8, 3);
  private final StopCompletion.Environment environment =
      new StopCompletion.Environment("root", "boot", "inputs");

  private List<StopCompletion.Fact> facts() {
    var facts = new ArrayList<StopCompletion.Fact>();
    for (var scope : StopCompletion.Scope.values())
      facts.add(new StopCompletion.Fact(scope, StopCompletion.Outcome.EXITED, 10, "exact-target"));
    return facts;
  }

  private StopCompletion complete(List<StopCompletion.Fact> facts) {
    return new StopCompletion(
        operation, environment, facts, 11, StopCompletion.Finish.SUCCEEDED, true, true);
  }

  private StopHandoff.Decision evaluate(StopCompletion completion) {
    return StopHandoff.evaluate(completion, operation, operation, owner, 8, 3, environment, true);
  }

  @Test
  public void completeFactsOnlyPermitThePureProjection() {
    assertEquals(StopHandoff.Decision.ELIGIBLE, evaluate(complete(facts())));
  }

  @Test
  public void everyRequiredScopeMustHaveItsOwnExitEvidence() {
    for (var scope : StopCompletion.Scope.values()) {
      var facts = facts();
      facts.removeIf(fact -> fact.getScope() == scope);
      assertEquals(scope.name(), StopHandoff.Decision.INCOMPLETE, evaluate(complete(facts)));
      facts.add(new StopCompletion.Fact(scope, StopCompletion.Outcome.UNKNOWN, 10, ""));
      assertEquals(scope.name(), StopHandoff.Decision.INCOMPLETE, evaluate(complete(facts)));
    }
  }

  @Test
  public void completionCopiesItsInputsAndDoesNotExposeMutableFacts() {
    var facts = facts();
    var completion = complete(facts);
    facts.clear();
    assertTrue(completion.complete());
    assertThrows(UnsupportedOperationException.class, () -> completion.facts().clear());
  }

  @Test
  public void equalLookingOperationOrDifferentOwnerCannotInheritCompletion() {
    var copy = new StopOperationId(owner, 1, 7, "instance", 8, 3);
    assertEquals(
        StopHandoff.Decision.WRONG_OPERATION,
        StopHandoff.evaluate(complete(facts()), copy, copy, owner, 8, 3, environment, true));
    assertEquals(
        StopHandoff.Decision.WRONG_OWNER,
        StopHandoff.evaluate(
            complete(facts()), operation, operation, new Object(), 8, 3, environment, true));
  }

  @Test
  public void newIntentAndCancellationRevisionInvalidateTheHistoricalFacts() {
    assertEquals(
        StopHandoff.Decision.STALE_INTENT,
        StopHandoff.evaluate(
            complete(facts()), operation, operation, owner, 9, 3, environment, true));
    assertEquals(
        StopHandoff.Decision.STALE_INTENT,
        StopHandoff.evaluate(
            complete(facts()), operation, operation, owner, 8, 4, environment, true));
  }

  @Test
  public void rootBootAndInputReplacementsAreAllRejected() {
    for (var changed :
        List.of(
            new StopCompletion.Environment("other", "boot", "inputs"),
            new StopCompletion.Environment("root", "other", "inputs"),
            new StopCompletion.Environment("root", "boot", "other")))
      assertEquals(
          StopHandoff.Decision.ENVIRONMENT_CHANGED,
          StopHandoff.evaluate(
              complete(facts()), operation, operation, owner, 8, 3, changed, true));
  }

  @Test
  public void finalizationFailureAndObjectReplacementAreNotReusable() {
    for (var finish : List.of(StopCompletion.Finish.FAILED, StopCompletion.Finish.UNKNOWN))
      assertFalse(
          new StopCompletion(operation, environment, facts(), 11, finish, true, true).complete());
    assertFalse(
        new StopCompletion(
                operation, environment, facts(), 11, StopCompletion.Finish.SUCCEEDED, false, true)
            .complete());
  }

  @Test
  public void coldWatchdogDisabledPluginAndUnprotectedPathsDoNotEnterTheSlice() {
    assertEquals(
        StopHandoff.Decision.UNPROTECTED_PATH,
        StopHandoff.evaluate(
            complete(facts()), operation, operation, owner, 8, 3, environment, false));
  }

  @Test
  public void malformedOrOutOfOrderFactsFailBeforePublication() {
    var facts = facts();
    facts.add(facts.get(0));
    assertThrows(IllegalArgumentException.class, () -> complete(facts));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StopCompletion.Fact(
                StopCompletion.Scope.GUEST, StopCompletion.Outcome.EXITED, 1, ""));
    assertThrows(IllegalArgumentException.class, () -> new StopOperationId(owner, 0, 7, "i", 8, 0));
    assertThrows(IllegalArgumentException.class, () -> new StopOperationId(owner, 1, 8, "i", 8, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StopCompletion(
                operation,
                environment,
                List.of(
                    new StopCompletion.Fact(
                        StopCompletion.Scope.GUEST, StopCompletion.Outcome.EXITED, 12, "born")),
                11,
                StopCompletion.Finish.SUCCEEDED,
                true,
                true));
  }
}
