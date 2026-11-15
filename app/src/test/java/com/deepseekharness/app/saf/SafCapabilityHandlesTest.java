package com.deepseekharness.app.saf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 能力句柄：绑定树/操作/路径、代次失效、系统位复查、一次性消费与限额。 */
public class SafCapabilityHandlesTest {
  private static final String URI = SafTestSupport.DOWNLOADS;
  private static final String OTHER = SafTestSupport.DOCUMENTS;
  private static final String ID = SafGrantRecord.idOf(URI);
  private static final String OTHER_ID = SafGrantRecord.idOf(OTHER);
  private static final String PATH = "/files/report.txt";

  private static SafGrantLedger ledger(int flags) {
    return SafTestSupport.ledger(
        SafTestSupport.active(URI, flags, 1),
        SafTestSupport.active(OTHER, SafGrantRecord.READ | SafGrantRecord.WRITE, 2));
  }

  @Test
  public void issueRequiresActiveGrantAndLivePermission() {
    SafCapabilityHandles handles = SafTestSupport.fixedHandles();
    assertEquals(
        SafCapabilityHandles.Result.UNKNOWN,
        handles.issue(null, ID, SafCapabilityHandles.Operation.READ, PATH, 1_000L).result);
    assertEquals(
        SafCapabilityHandles.Result.UNKNOWN,
        handles.issue(SafGrantLedger.empty(), ID, SafCapabilityHandles.Operation.READ, PATH, 1_000L)
            .result);
    assertEquals(
        SafCapabilityHandles.Result.REVOKED,
        handles.issue(
                SafTestSupport.ledger(SafTestSupport.pending(URI, 1)),
                ID,
                SafCapabilityHandles.Operation.READ,
                PATH,
                1_000L)
            .result);
    SafGrantLedger readOnly = SafTestSupport.ledger(SafTestSupport.active(URI, 1, 1));
    assertEquals(
        SafCapabilityHandles.Result.NOT_PERMITTED,
        handles.issue(readOnly, ID, SafCapabilityHandles.Operation.WRITE, PATH, 1_000L).result);
    SafGrantRecord noUserWrite =
        new SafGrantRecord(
            SafGrantRecord.idOf(URI),
            URI,
            "app",
            "label",
            true,
            true,
            false,
            SafGrantRecord.WRITE,
            SafGrantRecord.State.ACTIVE,
            3,
            1_000L,
            0,
            1,
            null);
    assertEquals(
        SafCapabilityHandles.Result.NOT_PERMITTED,
        handles.issue(
                SafTestSupport.ledger(noUserWrite),
                ID,
                SafCapabilityHandles.Operation.DELETE,
                PATH,
                1_000L)
            .result);
    assertEquals(
        SafCapabilityHandles.Result.REVOKED,
        handles.issue(
                SafTestSupport.ledger(SafTestSupport.active(URI, 1, 0)),
                ID,
                SafCapabilityHandles.Operation.READ,
                PATH,
                1_000L)
            .result);
  }

  @Test
  public void handleIsBoundToTreeOperationAndPath() {
    SafCapabilityHandles handles = SafTestSupport.fixedHandles();
    SafGrantLedger ledger = ledger(SafGrantRecord.READ | SafGrantRecord.WRITE);
    String token =
        handles.issue(ledger, ID, SafCapabilityHandles.Operation.READ, PATH, 1_000L).token;

    assertEquals(
        SafCapabilityHandles.Result.OK,
        handles.check(ledger, token, SafCapabilityHandles.Operation.READ, PATH, 1_001L));
    assertEquals(
        SafCapabilityHandles.Result.MISMATCH,
        handles.check(
            ledger, token, SafCapabilityHandles.Operation.READ, "/files/other.txt", 1_001L));
    assertEquals(
        SafCapabilityHandles.Result.MISMATCH,
        handles.check(ledger, token, SafCapabilityHandles.Operation.WRITE, PATH, 1_001L));
    assertEquals(
        SafCapabilityHandles.Result.BAD_PATH,
        handles.check(ledger, token, SafCapabilityHandles.Operation.READ, "../escape", 1_001L));
    assertEquals(
        SafCapabilityHandles.Result.UNKNOWN,
        handles.check(ledger, "missing", SafCapabilityHandles.Operation.READ, PATH, 1_001L));
  }

  @Test
  public void pathNormalizationAcceptsRelativeAndAbsoluteSpellings() {
    SafCapabilityHandles handles = SafTestSupport.fixedHandles();
    SafGrantLedger ledger = ledger(SafGrantRecord.READ);
    String token =
        handles.issue(ledger, ID, SafCapabilityHandles.Operation.READ, "files/report.txt", 1_000L)
            .token;

    assertEquals(
        SafCapabilityHandles.Result.OK,
        handles.check(
            ledger, token, SafCapabilityHandles.Operation.READ, "/files/report.txt", 1_001L));
  }

  @Test
  public void forgetInvalidatesExistingHandle() {
    SafCapabilityHandles handles = SafTestSupport.fixedHandles();
    SafGrantLedger ledger = ledger(SafGrantRecord.READ);
    String token =
        handles.issue(ledger, ID, SafCapabilityHandles.Operation.READ, PATH, 1_000L).token;

    SafGrantLedger forgotten = ledger.forget(URI).ledger;

    assertEquals(
        SafCapabilityHandles.Result.REVOKED,
        handles.consume(forgotten, token, SafCapabilityHandles.Operation.READ, PATH, 1_001L));
    assertEquals(0, handles.size());
  }

  @Test
  public void regrantInvalidatesOldEpochHandle() {
    SafCapabilityHandles handles = SafTestSupport.fixedHandles();
    SafGrantLedger first = SafTestSupport.single(URI, SafGrantRecord.READ);
    String token =
        handles.issue(first, ID, SafCapabilityHandles.Operation.READ, PATH, 1_000L).token;
    SafGrantLedger regranted =
        first
            .beginTake(URI, "app", "label", SafGrantRecord.READ, SafGrantRecord.READ, 2_000L)
            .ledger
            .commitTake(URI, SafGrantRecord.READ, 0, true, 3_000L)
            .ledger;

    assertEquals(
        SafCapabilityHandles.Result.REVOKED,
        handles.check(regranted, token, SafCapabilityHandles.Operation.READ, PATH, 3_001L));
  }

  @Test
  public void userWritePreferenceAndSystemFlagsAreRecheckedAtUseTime() {
    SafCapabilityHandles handles = SafTestSupport.fixedHandles();
    SafGrantLedger ledger = ledger(SafGrantRecord.READ | SafGrantRecord.WRITE);
    String read =
        handles.issue(ledger, ID, SafCapabilityHandles.Operation.READ, PATH, 1_000L).token;
    String write =
        handles.issue(ledger, ID, SafCapabilityHandles.Operation.WRITE, PATH, 1_000L).token;

    SafGrantLedger noWrite = ledger.setUserWritable(URI, false).ledger;
    assertEquals(
        SafCapabilityHandles.Result.NOT_PERMITTED,
        handles.check(noWrite, write, SafCapabilityHandles.Operation.WRITE, PATH, 1_001L));
    assertEquals(
        SafCapabilityHandles.Result.OK,
        handles.check(noWrite, read, SafCapabilityHandles.Operation.READ, PATH, 1_001L));

    SafGrantLedger revoked = noWrite.refreshSystemFlags(URI, 0).ledger;
    assertEquals(
        SafCapabilityHandles.Result.NOT_PERMITTED,
        handles.check(revoked, read, SafCapabilityHandles.Operation.READ, PATH, 1_002L));
  }

  @Test
  public void consumeIsOneShotForDestructiveOperations() {
    SafCapabilityHandles handles = SafTestSupport.fixedHandles();
    SafGrantLedger ledger = ledger(SafGrantRecord.READ | SafGrantRecord.WRITE);
    String token =
        handles.issue(ledger, ID, SafCapabilityHandles.Operation.DELETE, PATH, 1_000L).token;

    assertEquals(
        SafCapabilityHandles.Result.OK,
        handles.consume(ledger, token, SafCapabilityHandles.Operation.DELETE, PATH, 1_001L));
    assertEquals(0, handles.size());
    assertEquals(
        SafCapabilityHandles.Result.UNKNOWN,
        handles.consume(ledger, token, SafCapabilityHandles.Operation.DELETE, PATH, 1_002L));
  }

  @Test
  public void wrongPathDoesNotConsumeTheHandle() {
    SafCapabilityHandles handles = SafTestSupport.fixedHandles();
    SafGrantLedger ledger = ledger(SafGrantRecord.READ | SafGrantRecord.WRITE);
    String token =
        handles.issue(ledger, ID, SafCapabilityHandles.Operation.DELETE, PATH, 1_000L).token;

    assertEquals(
        SafCapabilityHandles.Result.MISMATCH,
        handles.consume(
            ledger, token, SafCapabilityHandles.Operation.DELETE, "/files/other", 1_001L));
    assertEquals(1, handles.size());
    assertEquals(
        SafCapabilityHandles.Result.OK,
        handles.consume(ledger, token, SafCapabilityHandles.Operation.DELETE, PATH, 1_002L));
  }

  @Test
  public void handlesExpireAndArePurged() {
    SafCapabilityHandles handles = SafTestSupport.fixedHandles();
    SafGrantLedger ledger = ledger(SafGrantRecord.READ);
    String token =
        handles.issue(ledger, ID, SafCapabilityHandles.Operation.READ, PATH, 1_000L).token;

    assertEquals(
        SafCapabilityHandles.Result.OK,
        handles.check(
            ledger, token, SafCapabilityHandles.Operation.READ, PATH, 1_000L + 60_000L - 1));
    assertEquals(
        SafCapabilityHandles.Result.EXPIRED,
        handles.check(ledger, token, SafCapabilityHandles.Operation.READ, PATH, 1_000L + 60_000L));
    assertEquals(1, handles.size());
    assertEquals(1, handles.purgeExpired(1_000L + 60_000L));
    assertEquals(0, handles.size());
  }

  @Test
  public void perTreeAndGlobalHandleLimits() {
    SafCapabilityHandles handles = SafTestSupport.fixedHandles();
    SafGrantLedger ledger = ledger(SafGrantRecord.READ);
    for (int index = 0; index < SafCapabilityLimits.MAX_HANDLES_PER_TREE; index++) {
      assertTrue(handles.issue(ledger, ID, SafCapabilityHandles.Operation.READ, PATH, 1_000L).ok());
    }
    assertEquals(
        SafCapabilityHandles.Result.LIMIT,
        handles.issue(ledger, ID, SafCapabilityHandles.Operation.READ, PATH, 1_000L).result);
    handles.revokeTree(ID);
    assertEquals(0, handles.size());

    SafGrantLedger many =
        SafTestSupport.ledger(
            java.util.stream.IntStream.range(0, SafCapabilityLimits.MAX_TREES)
                .mapToObj(
                    index -> SafTestSupport.active(SafTestSupport.treeUri(index), 1, index + 1))
                .toArray(SafGrantRecord[]::new));
    for (SafGrantRecord record : many.records())
      for (int index = 0; index < SafCapabilityLimits.MAX_HANDLES_PER_TREE; index++)
        assertTrue(
            handles
                .issue(many, record.id(), SafCapabilityHandles.Operation.READ, PATH, 1_000L)
                .ok());
    assertEquals(
        SafCapabilityHandles.Result.LIMIT,
        handles.issue(
                many, many.records().get(0).id(), SafCapabilityHandles.Operation.READ, PATH, 1_000L)
            .result);
  }

  @Test
  public void revokeTreeRemovesOnlyMatchingHandles() {
    SafCapabilityHandles handles = SafTestSupport.fixedHandles();
    SafGrantLedger ledger = ledger(SafGrantRecord.READ);
    String mine =
        handles.issue(ledger, ID, SafCapabilityHandles.Operation.READ, PATH, 1_000L).token;
    String other =
        handles.issue(ledger, OTHER_ID, SafCapabilityHandles.Operation.READ, PATH, 1_000L).token;

    assertEquals(1, handles.revokeTree(ID));
    assertEquals(
        SafCapabilityHandles.Result.UNKNOWN,
        handles.check(ledger, mine, SafCapabilityHandles.Operation.READ, PATH, 1_001L));
    assertEquals(
        SafCapabilityHandles.Result.OK,
        handles.check(ledger, other, SafCapabilityHandles.Operation.READ, PATH, 1_001L));
  }

  @Test
  public void closingOnePanelDoesNotRevokeAnotherTicketOnTheSameTree() {
    SafCapabilityHandles handles = SafTestSupport.fixedHandles();
    SafGrantLedger ledger = ledger(SafGrantRecord.READ | SafGrantRecord.WRITE);
    String panel =
        handles.issue(ledger, ID, SafCapabilityHandles.Operation.WRITE, "report.md", 1_000).token;
    String preview =
        handles.issue(ledger, ID, SafCapabilityHandles.Operation.READ, "notes.md", 1_000).token;
    handles.discard(preview);
    handles.discard(preview);
    handles.discard(null);
    assertEquals(1, handles.size());
    assertEquals(
        SafCapabilityHandles.Result.OK,
        handles.check(ledger, panel, SafCapabilityHandles.Operation.WRITE, "report.md", 1_001));
    assertEquals(
        SafCapabilityHandles.Result.UNKNOWN,
        handles.check(ledger, preview, SafCapabilityHandles.Operation.READ, "notes.md", 1_001));
  }

  @Test
  public void defaultTokensAreRandomHexAndUnique() {
    SafCapabilityHandles handles = new SafCapabilityHandles();
    SafGrantLedger ledger = ledger(SafGrantRecord.READ);
    java.util.Set<String> seen = new java.util.LinkedHashSet<>();
    for (int index = 0; index < SafCapabilityLimits.MAX_HANDLES_PER_TREE; index++) {
      String token =
          handles.issue(ledger, ID, SafCapabilityHandles.Operation.READ, PATH, 1_000L).token;
      assertTrue(token.matches("[0-9a-f]{32}"));
      assertTrue("token 必须唯一", seen.add(token));
    }
    assertEquals(seen.size(), handles.size());
  }

  @Test
  public void tokenSourceFailureIsReportedWithoutLeakingState() {
    SafCapabilityHandles handles =
        new SafCapabilityHandles(
            () -> {
              throw new IllegalStateException("token source down");
            });
    SafCapabilityHandles.Issued issued =
        handles.issue(
            ledger(SafGrantRecord.READ), ID, SafCapabilityHandles.Operation.READ, PATH, 1_000L);

    assertFalse(issued.ok());
    assertEquals(SafCapabilityHandles.Result.UNAVAILABLE, issued.result);
    assertEquals("", issued.token);
    assertEquals(0, issued.expiresAt);
    assertEquals(0, handles.size());
  }
}
