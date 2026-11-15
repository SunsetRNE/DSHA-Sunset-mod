package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.RootModuleInventory.Entry;
import com.deepseekharness.app.util.RootModuleInventory.MarkerFacts;
import com.deepseekharness.app.util.RootModuleInventory.NodeKind;
import com.deepseekharness.app.util.RootModuleInventory.Rejected;
import com.deepseekharness.app.util.RootModuleInventory.SourceFacts;
import com.deepseekharness.app.util.RootModulePolicy.Code;
import com.deepseekharness.app.util.RootModulePolicy.Manager;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public final class RootModuleInventoryTest {
  @Test
  public void plainFieldsAndMarkerFactsAreTheOnlyReturnedData() throws Exception {
    Entry entry =
        RootModuleInventory.parse(
            "mod_1",
            utf8(
                "# plain metadata\nid=mod_1\nname=模块 😀\nversion=1.2 beta\nversionCode=123\n"
                    + "description=not retained ${anything}\nauthor=not retained\nscript=ignored\n"),
            true,
            false,
            true,
            1234);
    assertEquals("mod_1", entry.id());
    assertEquals("模块 😀", entry.name());
    assertEquals("1.2 beta", entry.version());
    assertEquals("123", entry.versionCode());
    assertTrue(entry.disabled());
    assertFalse(entry.removing());
    assertTrue(entry.updatePending());
    assertEquals(1234, entry.modifiedMillis());
    assertFalse(entry.toString().contains("retained"));
    assertFalse(entry.toString().contains("script"));
  }

  @Test
  public void lfCrLfAndNoFinalNewlineHaveSamePlainMeaning() throws Exception {
    for (String lineEnding : List.of("\n", "\r\n")) {
      for (boolean tail : new boolean[] {false, true}) {
        Entry entry = parse("mod", "id=mod" + lineEnding + "name=A" + (tail ? lineEnding : ""));
        assertEquals("A", entry.name());
        assertEquals("", entry.version());
        assertEquals("", entry.versionCode());
      }
    }
  }

  @Test
  public void directoryIdIsASafeSingleNameAndNeverAPath() {
    for (String id :
        new String[] {
          null,
          "",
          ".",
          "..",
          "../mod",
          "mod/..",
          "/data/adb/modules/mod",
          "mod\\part",
          "a\u0000b",
          "-option",
          "name space",
          "mod:part",
          "模块",
          "a".repeat(129)
        }) {
      assertCode(Code.MODULE_ID_INVALID, id, "id=mod\n");
    }
  }

  @Test
  public void propIdMustBePresentAndMatchDirectoryExactly() {
    assertCode(Code.MODULE_PROP_INVALID, "mod", "name=A\n");
    for (String id : List.of("other", "../mod", "mod/sub", "MOD", ""))
      assertCode(Code.MODULE_ID_MISMATCH, "mod", "id=" + id + "\n");
  }

  @Test
  public void allDuplicateKeysIncludingUnknownOrSpacePaddedKeysAreRejected() {
    for (String fields :
        List.of(
            "id=mod\nid=mod\n",
            "id=mod\n name =one\nname=two\n",
            "id=mod\ndescription=one\ndescription=two\n",
            "id=mod\nversionCode=1\nversionCode=1\n"))
      assertCode(Code.MODULE_PROP_DUPLICATE, "mod", fields);
  }

  @Test
  public void invalidUtf8NeverGetsReplacementCharactersOrLossyDecoded() {
    for (byte[] invalid :
        new byte[][] {
          {(byte) 0xc0, (byte) 0xaf}, {(byte) 0xe2, (byte) 0x82},
          {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, {(byte) 0x80}
        }) {
      var error =
          assertThrows(
              Rejected.class,
              () -> RootModuleInventory.parse("mod", invalid, false, false, false, -1));
      assertEquals(Code.MODULE_PROP_UTF8, error.code());
    }
  }

  @Test
  public void controlsAndBidiCannotHideInSelectedUnknownOrCommentLines() {
    for (String control :
        List.of(
            "\u0000", "\t", "\u001b", "\u007f", "\u0085", "\u202e", "\u200b", "\u2028", "\u2029",
            "\ufeff")) {
      assertCode(Code.MODULE_PROP_INVALID, "mod", "id=mod\nname=A" + control + "B\n");
      assertCode(Code.MODULE_PROP_INVALID, "mod", "id=mod\ndescription=A" + control + "B\n");
      assertCode(Code.MODULE_PROP_INVALID, "mod", "# A" + control + "B\nid=mod\n");
    }
    assertCode(Code.MODULE_PROP_INVALID, "mod", "id=mod\rname=A\n");
  }

  @Test
  public void propertiesEscapesContinuationInterpolationAndCommandSubstitutionAreNotInterpreted() {
    for (String name : List.of("line\\nnew", "unicode\\u0000", "$(id)", "${value}", "`id`"))
      assertCode(Code.MODULE_PROP_INVALID, "mod", "id=mod\nname=" + name + "\n");
    assertCode(Code.MODULE_PROP_INVALID, "mod", "id=mod\nname=first\\\nsecond\n");
    assertCode(Code.MODULE_PROP_INVALID, "mod", "id=mod\nunknown=value\\\ncontinued\n");
    assertCode(Code.MODULE_PROP_INVALID, "mod", "id=mod\nname:colon separator\n");
    assertCode(Code.MODULE_PROP_INVALID, "mod", "id=mod\n$(touch)=x\n");
  }

  @Test
  public void selectedFieldLimitCountsCodePointsRatherThanUtf16Units() throws Exception {
    String within = "😀".repeat(RootModulePolicy.MAX_FIELD_CODEPOINTS);
    assertEquals(within, parse("mod", "id=mod\nname=" + within + "\n").name());
    assertCode(Code.MODULE_FIELD_LIMIT, "mod", "id=mod\nname=" + within + "😀\n");
  }

  @Test
  public void versionCodeIsOptionalPlainUnsignedLongAndCannotOverflow() throws Exception {
    assertEquals("", parse("mod", "id=mod\n").versionCode());
    assertEquals("0", parse("mod", "id=mod\nversionCode=0\n").versionCode());
    assertEquals(
        "9223372036854775807",
        parse("mod", "id=mod\nversionCode=9223372036854775807\n").versionCode());
    for (String invalid :
        List.of("-1", "+1", "1.0", "1e2", "1; id", "9223372036854775808", "11111111111111111111"))
      assertCode(Code.MODULE_PROP_INVALID, "mod", "id=mod\nversionCode=" + invalid + "\n");
  }

  @Test
  public void propByteLimitIncludesIgnoredUnknownKeys() throws Exception {
    byte[] exact = padded("mod", RootModulePolicy.MAX_PROP_BYTES);
    assertEquals(RootModulePolicy.MAX_PROP_BYTES, exact.length);
    assertEquals("mod", RootModuleInventory.parse("mod", exact, false, false, false, -1).id());
    byte[] tooLong = padded("mod", RootModulePolicy.MAX_PROP_BYTES + 1);
    var rejected =
        assertThrows(
            Rejected.class,
            () -> RootModuleInventory.parse("mod", tooLong, false, false, false, -1));
    assertEquals(Code.MODULE_PROP_LIMIT, rejected.code());
  }

  @Test
  public void sourceSymlinkHardlinkSpecialUnreadableOrUnverifiedFactsAreRefused() {
    for (NodeKind kind : List.of(NodeKind.MISSING, NodeKind.SYMLINK, NodeKind.SPECIAL)) {
      var directory = new SourceFacts(kind, NodeKind.REGULAR, true, true, 1, 1);
      assertEquals(
          Code.MODULE_SOURCE_UNSAFE,
          RootModuleInventory.inspect("mod", utf8("id=mod\n"), directory, MarkerFacts.none())
              .code());
      var property = new SourceFacts(NodeKind.DIRECTORY, kind, true, true, 1, 1);
      assertEquals(
          Code.MODULE_SOURCE_UNSAFE,
          RootModuleInventory.inspect("mod", utf8("id=mod\n"), property, MarkerFacts.none())
              .code());
    }
    assertEquals(
        Code.MODULE_SOURCE_UNSAFE,
        RootModuleInventory.sourceRejection(
            new SourceFacts(NodeKind.DIRECTORY, NodeKind.REGULAR, false, true, 1, 1),
            MarkerFacts.none()));
    for (long links : new long[] {0, 2, Long.MAX_VALUE})
      assertEquals(
          Code.MODULE_SOURCE_UNSAFE,
          RootModuleInventory.sourceRejection(
              new SourceFacts(NodeKind.DIRECTORY, NodeKind.REGULAR, true, true, links, 1),
              MarkerFacts.none()));
    assertEquals(
        Code.MODULE_ROOT_UNREADABLE,
        RootModuleInventory.sourceRejection(
            new SourceFacts(NodeKind.UNREADABLE, NodeKind.REGULAR, true, true, 1, 1),
            MarkerFacts.none()));
    assertEquals(
        Code.MODULE_SOURCE_CHANGED,
        RootModuleInventory.sourceRejection(
            new SourceFacts(NodeKind.DIRECTORY, NodeKind.REGULAR, true, false, 1, 1),
            MarkerFacts.none()));
  }

  @Test
  public void markerFactsOnlyAcceptMissingOrVerifiedRegularMarkers() {
    var accepted =
        RootModuleInventory.inspect(
            "mod",
            utf8("id=mod\n"),
            source(),
            new MarkerFacts(NodeKind.REGULAR, NodeKind.MISSING, NodeKind.REGULAR));
    assertTrue(accepted.accepted());
    assertTrue(accepted.module().disabled());
    assertFalse(accepted.module().removing());
    assertTrue(accepted.module().updatePending());
    for (NodeKind kind : List.of(NodeKind.DIRECTORY, NodeKind.SYMLINK, NodeKind.SPECIAL))
      assertEquals(
          Code.MODULE_SOURCE_UNSAFE,
          RootModuleInventory.sourceRejection(
              source(), new MarkerFacts(kind, NodeKind.MISSING, NodeKind.MISSING)));
    assertEquals(
        Code.MODULE_ROOT_UNREADABLE,
        RootModuleInventory.sourceRejection(
            source(), new MarkerFacts(NodeKind.UNREADABLE, NodeKind.MISSING, NodeKind.MISSING)));
  }

  @Test
  public void onlyExplicitCompleteEnumerationCanProduceACompleteEmptyList() {
    var empty = new RootModuleInventory.Builder(Manager.APATCH).finish(true, null);
    assertEquals(Code.LIST_COMPLETE, empty.code());
    assertTrue(empty.complete());
    assertTrue(empty.modules().isEmpty());
    var unreadable =
        new RootModuleInventory.Builder(Manager.APATCH).finish(false, Code.MODULE_ROOT_UNREADABLE);
    assertFalse(unreadable.complete());
    assertEquals(Code.LIST_PARTIAL, unreadable.code());
    assertEquals(Code.MODULE_ROOT_UNREADABLE, unreadable.reason());
    assertTrue(unreadable.modules().isEmpty());
    assertFalse(RootModuleInventory.unavailable(Code.MODULE_ROOT_UNREADABLE).complete());
    assertFalse(RootModuleInventory.unavailable(Code.UNKNOWN).complete());
    assertFalse(RootModuleInventory.unavailable(Code.ROOT_NOT_GRANTED).complete());
    assertFalse(RootModuleInventory.unavailable(Code.UNSUPPORTED_MANAGER_VERSION).complete());
  }

  @Test
  public void firstEntryFailureStaysPartialEvenWhenReaderLaterSaysComplete() {
    var builder = new RootModuleInventory.Builder(Manager.KERNELSU);
    assertFalse(builder.add("mod", utf8("id=other\n"), source(), MarkerFacts.none()));
    assertFalse(builder.add("mod2", utf8("id=mod2\n"), source(), MarkerFacts.none()));
    var result = builder.finish(true, null);
    assertEquals(Code.LIST_PARTIAL, result.code());
    assertEquals(Code.MODULE_ID_MISMATCH, result.reason());
    assertFalse(result.complete());
    assertEquals(1, result.attemptedEntries());
    assertTrue(result.modules().isEmpty());
  }

  @Test
  public void laterReadFailureKeepsOnlyVerifiedPrefixAndExplicitIncompleteCode() {
    var builder = new RootModuleInventory.Builder(Manager.APATCH);
    assertTrue(builder.add("good", utf8("id=good\n"), source(), MarkerFacts.none()));
    assertFalse(
        builder.add(
            "bad",
            utf8("id=bad\n"),
            new SourceFacts(NodeKind.DIRECTORY, NodeKind.SYMLINK, true, true, 1, 0),
            MarkerFacts.none()));
    var result = builder.finish(false, Code.UNKNOWN);
    assertEquals(Code.LIST_PARTIAL, result.code());
    assertEquals(Code.MODULE_SOURCE_UNSAFE, result.reason());
    assertEquals(List.of("good"), result.modules().stream().map(Entry::id).toList());
    assertFalse(result.complete());
    assertThrows(UnsupportedOperationException.class, () -> result.modules().clear());
  }

  @Test
  public void exactEntryLimitIsAcceptedAndNextEntryCannotBeReadAsComplete() {
    var builder = new RootModuleInventory.Builder(Manager.APATCH);
    for (int i = 0; i < RootModulePolicy.MAX_ENTRIES; i++) {
      String id = "m" + i;
      assertTrue(builder.add(id, utf8("id=" + id + "\n"), source(), MarkerFacts.none()));
    }
    assertFalse(builder.add("extra", utf8("id=extra\n"), source(), MarkerFacts.none()));
    var result = builder.finish(true, null);
    assertEquals(256, result.modules().size());
    assertEquals(257, result.attemptedEntries());
    assertEquals(Code.MODULE_ENTRY_LIMIT, result.reason());
    assertFalse(result.complete());
  }

  @Test
  public void totalBudgetCountsIgnoredBytesAndStopsBeforeAnyExtraOutput() {
    var exact = new RootModuleInventory.Builder(Manager.MAGISK);
    for (int i = 0; i < 16; i++) {
      String id = "m" + i;
      assertTrue(
          exact.add(id, padded(id, RootModulePolicy.MAX_PROP_BYTES), source(), MarkerFacts.none()));
    }
    var full = exact.finish(true, null);
    assertTrue(full.complete());
    assertEquals(RootModulePolicy.MAX_TOTAL_BYTES, full.propertyBytes());
    var over = new RootModuleInventory.Builder(Manager.MAGISK);
    for (int i = 0; i < 16; i++) {
      String id = "m" + i;
      assertTrue(
          over.add(id, padded(id, RootModulePolicy.MAX_PROP_BYTES), source(), MarkerFacts.none()));
    }
    assertFalse(over.add("extra", utf8("id=extra\n"), source(), MarkerFacts.none()));
    var partial = over.finish(true, null);
    assertEquals(Code.MODULE_TOTAL_LIMIT, partial.reason());
    assertEquals(16, partial.modules().size());
    assertFalse(partial.complete());
  }

  @Test
  public void parsedEntryPathStillAccountsRawBytesAndRejectsDuplicateIds() throws Exception {
    var builder = new RootModuleInventory.Builder(Manager.KERNELSU);
    Entry entry = parse("mod", "id=mod\n");
    assertTrue(builder.add(entry, 7));
    assertFalse(builder.add(entry, 7));
    var result = builder.finish(true, null);
    assertEquals(Code.MODULE_DUPLICATE_ID, result.reason());
    assertEquals(14, result.propertyBytes());
    assertEquals(1, result.modules().size());
    assertFalse(result.complete());
  }

  @Test
  public void typedResultsCannotTurnUnknownUnsupportedOrNoRootIntoEmptySuccess() {
    for (Code code :
        List.of(
            Code.ROOT_DISABLED,
            Code.SU_UNAVAILABLE,
            Code.ROOT_NOT_GRANTED,
            Code.ROOT_IDENTITY_UNVERIFIED,
            Code.UNSUPPORTED_MANAGER_VERSION,
            Code.MANAGER_AMBIGUOUS,
            Code.MODULE_ROOT_UNREADABLE,
            Code.UNKNOWN)) {
      var result = RootModuleInventory.unavailable(code);
      assertEquals(code, result.code());
      assertFalse(result.complete());
      assertNull(result.manager());
      assertTrue(result.modules().isEmpty());
    }
    assertThrows(
        IllegalArgumentException.class, () -> RootModuleInventory.unavailable(Code.LIST_COMPLETE));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RootModuleInventory.Builder(Manager.APATCH).finish(false, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RootModuleInventory.Builder(Manager.APATCH).finish(true, Code.UNKNOWN));
  }

  @Test
  public void finishedBuilderCannotBeReusedToAppendOrUpgradeAnOldPartial() throws Exception {
    var builder = new RootModuleInventory.Builder(Manager.KERNELSU);
    builder.finish(false, Code.UNKNOWN);
    assertThrows(IllegalStateException.class, () -> builder.add(parse("mod", "id=mod\n"), 7));
    assertThrows(IllegalStateException.class, () -> builder.finish(true, null));
  }

  private static SourceFacts source() {
    return new SourceFacts(NodeKind.DIRECTORY, NodeKind.REGULAR, true, true, 1, 1);
  }

  private static Entry parse(String id, String property) throws Rejected {
    return RootModuleInventory.parse(id, utf8(property), false, false, false, -1);
  }

  private static void assertCode(Code code, String id, String property) {
    var rejected = assertThrows(Rejected.class, () -> parse(id, property));
    assertEquals(code, rejected.code());
    assertEquals(code.name(), rejected.getMessage());
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] padded(String id, int bytes) {
    String prefix = "id=" + id + "\nignored=";
    return utf8(prefix + "x".repeat(bytes - prefix.length()));
  }
}
