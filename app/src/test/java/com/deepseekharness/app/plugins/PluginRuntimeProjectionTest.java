package com.deepseekharness.app.plugins;

import static org.junit.Assert.*;

import com.deepseekharness.app.core.PluginRepository.Item;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class PluginRuntimeProjectionTest {
  private static final long NOW = 100_000, GENERATION = 7;
  private static final String INSTANCE = "owned-web-instance";

  @Rule public final TemporaryFolder temporary = new TemporaryFolder();

  private Item installed(String name) throws Exception {
    return new Item(
        new JSONObject()
            .put("name", name)
            .put("version", "1.0.0")
            .put("enabled", true)
            .put("available", true)
            .put("loadState", "loaded")
            .put("contentFingerprint", "a".repeat(64))
            .put("contentAlgorithm", "dependency-graph-sha256-v1")
            .put("entries", entries(false)));
  }

  private JSONObject row(String name, String state) throws Exception {
    return new JSONObject()
        .put("name", name)
        .put("version", "1.0.0")
        .put("profile", "web")
        .put("state", state)
        .put("contentFingerprint", "a".repeat(64))
        .put("contentAlgorithm", "dependency-graph-sha256-v1")
        .put(
            "identity",
            new JSONObject()
                .put("identitySource", "OBSERVER_SESSION")
                .put("startupId", "unknown")
                .put("generation", Long.toString(GENERATION))
                .put("instance", INSTANCE)
                .put("observedAt", NOW))
        .put("entries", entries(true));
  }

  private JSONArray entries(boolean observed) throws Exception {
    JSONObject entry =
        new JSONObject()
            .put("entryId", "sample-entry")
            .put("moduleHash", "b".repeat(64))
            .put("parentId", "")
            .put("group", false)
            .put("patchReason", "PROFILE_UNSET");
    if (observed) entry.put("phase", "active").put("diagnostic", "");
    return new JSONArray().put(entry);
  }

  private JSONObject snapshot(JSONObject... rows) throws Exception {
    JSONArray values = new JSONArray();
    for (JSONObject row : rows) values.put(row);
    return new JSONObject()
        .put("generation", Long.toString(GENERATION))
        .put("instance", INSTANCE)
        .put("at", NOW)
        .put("installedPlugins", values)
        .put("plugins", new JSONArray());
  }

  private List<Item> project(List<Item> items, JSONObject snapshot) throws Exception {
    return PluginRuntimeProjection.apply(items, snapshot, GENERATION, INSTANCE, NOW);
  }

  @Test
  public void realDirectorySchemaAndOldActivationLedgerDoNotProveCurrentLoading() throws Exception {
    Item value = project(List.of(installed("sample")), snapshot()).get(0);
    assertTrue(value.enabled);
    assertEquals("loaded", value.loadState);
    assertEquals("unknown", value.runtimeState);
    assertTrue(value.runtimeStale);
  }

  @Test
  public void actualEvidenceUpdatesOfficialManagedAndUserRowsWithoutChangingConfiguration()
      throws Exception {
    for (String name :
        List.of(
            "@deepseek-ai/dsh-experimental-agent-team-profile",
            "@deepseek-ai/dsh-experimental-auto-review",
            "@deepseek-ai/dsh-experimental-badge-skill-bundle",
            "user-bundle")) {
      Item original = installed(name);
      for (String state : List.of("loaded", "failed", "pending", "loading", "unloaded")) {
        Item value = project(List.of(original), snapshot(row(name, state))).get(0);
        assertEquals(state, value.runtimeState);
        assertFalse(value.runtimeStale);
        assertEquals(GENERATION, value.runtimeGeneration);
        assertEquals(original.stableKey, value.stableKey);
        assertEquals(original.enabled, value.enabled);
      }
    }
  }

  @Test
  public void refreshRetainsSameVersionEvidenceAndUnreadableHeartbeatMarksItStale()
      throws Exception {
    Item old = project(List.of(installed("sample")), snapshot(row("sample", "loaded"))).get(0);
    Item refreshed = PluginRuntimeProjection.retainRuntime(installed("sample"), old, GENERATION);
    assertEquals("loaded", refreshed.runtimeState);
    assertFalse(refreshed.runtimeStale);
    Item unavailable = project(List.of(refreshed), null).get(0);
    assertEquals("loaded", unavailable.runtimeState);
    assertTrue(unavailable.runtimeStale);
    Item recovered = project(List.of(unavailable), snapshot(row("sample", "failed"))).get(0);
    assertEquals("failed", recovered.runtimeState);
    assertFalse(recovered.runtimeStale);
  }

  @Test
  public void oldProcessFutureAndExpiredSnapshotsCannotConfirmCurrentState() throws Exception {
    for (JSONObject bad :
        List.of(
            snapshot(row("sample", "loaded")).put("generation", "6"),
            snapshot(row("sample", "loaded")).put("instance", "other-web-instance"),
            snapshot(row("sample", "loaded")).put("at", NOW + 1),
            snapshot(row("sample", "loaded"))
                .put("at", NOW - PluginRuntimeProjection.MAX_AGE_MILLIS - 1),
            snapshot(row("sample", "loaded")).put("instance", ""))) {
      Item value = project(List.of(installed("sample")), bad).get(0);
      assertEquals("unknown", value.runtimeState);
      assertTrue(value.runtimeStale);
    }
  }

  @Test
  public void expiryRetainsLastStateAsHistoryAndFreshBoundaryStillConfirms() throws Exception {
    Item loaded = installed("sample").withRuntime("loaded", GENERATION);
    Item expired =
        project(
                List.of(loaded),
                snapshot().put("at", NOW - PluginRuntimeProjection.MAX_AGE_MILLIS - 1))
            .get(0);
    assertEquals("loaded", expired.runtimeState);
    assertTrue(expired.runtimeStale);
    Item boundary =
        project(
                List.of(loaded),
                snapshot(row("sample", "loaded"))
                    .put("at", NOW - PluginRuntimeProjection.MAX_AGE_MILLIS))
            .get(0);
    assertFalse(boundary.runtimeStale);
  }

  @Test
  public void webGenerationChangeDoesNotRelabelPreviousProcessEvidence() throws Exception {
    Item loaded =
        installed("sample").withRuntime("loaded", GENERATION - 1).withRestartRequired(true);
    Item value = project(List.of(loaded), null).get(0);
    assertEquals("unknown", value.runtimeState);
    assertTrue(value.runtimeStale);
    assertFalse(value.restartRequired);
  }

  @Test
  public void versionChangeCannotBorrowLoadedStateFromPreviousPackage() throws Exception {
    Item changed =
        new Item(
            new JSONObject().put("name", "sample").put("version", "2.0.0").put("enabled", true));
    Item previous = installed("sample").withRuntime("loaded", GENERATION);
    Item retained = PluginRuntimeProjection.retainRuntime(changed, previous, GENERATION);
    assertEquals("unknown", retained.runtimeState);
    Item value = project(List.of(retained), snapshot(row("sample", "loaded"))).get(0);
    assertEquals("unknown", value.runtimeState);
    assertTrue(value.restartRequired);
  }

  @Test
  public void pendingIntentAndCurrentRuntimeRemainSeparateUntilEvidenceAgrees() throws Exception {
    Item disabled = installed("sample").withRuntime("loaded", GENERATION).withIntent(false);
    Item running = project(List.of(disabled), snapshot(row("sample", "loaded"))).get(0);
    assertFalse(running.enabled);
    assertEquals("loaded", running.runtimeState);
    assertTrue(running.restartRequired);
    Item stopped = project(List.of(running), snapshot(row("sample", "unloaded"))).get(0);
    assertFalse(stopped.enabled);
    assertFalse(stopped.restartRequired);
  }

  @Test
  public void missingUnknownOrDuplicateEvidenceDoesNotEraseKnownState() throws Exception {
    Item previous = installed("sample").withRuntime("loaded", GENERATION);
    for (JSONObject partial :
        List.of(
            snapshot(),
            snapshot(row("sample", "unknown")),
            snapshot(row("sample", "loaded"), row("sample", "failed")))) {
      Item value = project(List.of(previous), partial).get(0);
      assertEquals("loaded", value.runtimeState);
      assertTrue(value.runtimeStale);
    }
  }

  @Test
  public void wrongProfileAndUnknownStateCannotGrantLoadSuccess() throws Exception {
    for (JSONObject invalid :
        List.of(
            row("sample", "loaded").put("profile", "other"),
            row("sample", "future-state"),
            row("sample", "loaded").put("version", ""))) {
      Item value = project(List.of(installed("sample")), snapshot(invalid)).get(0);
      assertEquals("unknown", value.runtimeState);
    }
  }

  @Test
  public void queuedSameVersionUpdateCannotBorrowOldLoadedModuleToClearRestart() throws Exception {
    Item queued =
        new Item(
            new JSONObject()
                .put("name", "sample")
                .put("version", "1.0.0")
                .put("enabled", true)
                .put("loadState", "queued")
                .put("restartRequired", true));
    Item value = project(List.of(queued), snapshot(row("sample", "loaded"))).get(0);
    assertEquals("loaded", value.runtimeState);
    assertTrue(value.restartRequired);
  }

  @Test
  public void explicitHistoricalAliasRemainsOneIdentity() throws Exception {
    Item value =
        project(
                List.of(installed("dsh-auto-review")),
                snapshot(row("@deepseek-ai/dsh-experimental-auto-review", "loaded")))
            .get(0);
    assertEquals("loaded", value.runtimeState);
  }

  @Test
  public void legacyShimVersionAloneCannotProveCurrentDeployment() throws Exception {
    Item card =
        new Item(
            new JSONObject()
                .put("name", PluginIdentity.AUTO_REVIEW_CANONICAL)
                .put("version", "0.2.2-rc.1")
                .put("enabled", true)
                .put("available", true)
                .put("loadState", "loaded"));
    Item value =
        project(
                List.of(card),
                snapshot(row(PluginIdentity.AUTO_REVIEW_ALIAS, "loaded").put("version", "0.1.0")))
            .get(0);
    assertEquals("unknown", value.runtimeState);
    assertTrue(value.runtimeStale);
    assertTrue(value.restartRequired);
  }

  @Test
  public void canonicalVersionMismatchWithoutLegacyShimStillRequiresRestart() throws Exception {
    Item card =
        new Item(
            new JSONObject()
                .put("name", PluginIdentity.AUTO_REVIEW_CANONICAL)
                .put("version", "0.2.2-rc.1")
                .put("enabled", true)
                .put("available", true));
    Item value =
        project(
                List.of(card),
                snapshot(
                    row(PluginIdentity.AUTO_REVIEW_CANONICAL, "loaded").put("version", "0.2.1")))
            .get(0);
    assertEquals("unknown", value.runtimeState);
    assertTrue(value.restartRequired);
  }

  @Test
  public void dynamicAndInstalledNamesDoNotCollideAndUnavailableListIsNotEmptyProof()
      throws Exception {
    JSONObject active = snapshot(row("sample", "loaded"));
    active.put(
        "plugins",
        new JSONArray()
            .put(
                new JSONObject()
                    .put("name", "sample")
                    .put("sessionId", "session-a")
                    .put("id", "temporary-a")
                    .put("active", true)));
    List<Item> running = project(List.of(installed("sample")), active);
    assertEquals(2, running.size());
    assertNotEquals(running.get(0).stableKey, running.get(1).stableKey);
    List<Item> stale = project(running, null);
    assertEquals(2, stale.size());
    assertTrue(stale.get(1).runtimeStale);
    assertEquals(1, project(stale, snapshot()).size());
    assertEquals(
        1,
        PluginRuntimeProjection.apply(running, null, GENERATION + 1, "new-instance", NOW).size());
  }

  @Test
  public void rejectionCodesSeparateMissingStaleAndForeignEvidence() throws Exception {
    JSONObject valid = snapshot(row("sample", "loaded"));
    assertEquals("", PluginRuntimeProjection.rejection(valid, GENERATION, INSTANCE, NOW));
    assertEquals("NO_ACTIVITY", PluginRuntimeProjection.rejection(null, GENERATION, INSTANCE, NOW));
    assertEquals("NO_INSTANCE", PluginRuntimeProjection.rejection(valid, GENERATION, "", NOW));
    assertEquals("NO_GENERATION", PluginRuntimeProjection.rejection(valid, 0, INSTANCE, NOW));
    assertEquals(
        "FUTURE_TIME", PluginRuntimeProjection.rejection(valid, GENERATION, INSTANCE, NOW - 1));
    assertEquals(
        "EXPIRED",
        PluginRuntimeProjection.rejection(
            valid, GENERATION, INSTANCE, NOW + PluginRuntimeProjection.MAX_AGE_MILLIS + 1));
    assertEquals(
        "GENERATION", PluginRuntimeProjection.rejection(valid, GENERATION + 1, INSTANCE, NOW));
    assertEquals(
        "INSTANCE", PluginRuntimeProjection.rejection(valid, GENERATION, "other-instance", NOW));
    assertEquals(
        "NO_TIME",
        PluginRuntimeProjection.rejection(
            snapshot(row("sample", "loaded")).put("at", 0), GENERATION, INSTANCE, NOW));
  }

  @Test
  public void oversizedInstalledEvidenceIsRejectedAsAWhole() throws Exception {
    JSONObject evidence = snapshot();
    JSONArray rows = evidence.getJSONArray("installedPlugins");
    rows.put(row("sample", "loaded"));
    for (int i = 0; i < 512; i++) rows.put(row("other-" + i, "loaded"));
    assertEquals("unknown", project(List.of(installed("sample")), evidence).get(0).runtimeState);
  }

  @Test
  public void contentIdentityAndEntryOwnershipCannotBeBorrowedFromSameVersion() throws Exception {
    for (JSONObject invalid :
        List.of(
            row("sample", "loaded").put("contentFingerprint", ""),
            row("sample", "loaded").put("contentAlgorithm", "module-name-sha256"),
            row("sample", "loaded").put("contentFingerprint", "c".repeat(64)),
            row("sample", "loaded").put("entries", new JSONArray()),
            row("sample", "loaded")
                .put("entries", entries(true).put(entries(true).getJSONObject(0))),
            row("sample", "loaded")
                .put(
                    "entries",
                    new JSONArray()
                        .put(entries(true).getJSONObject(0).put("parentId", "other"))))) {
      Item value = project(List.of(installed("sample")), snapshot(invalid)).get(0);
      assertTrue(value.runtimeStale);
      assertFalse(value.runtimeReason.isEmpty());
    }
    Item oldProducer =
        project(
                List.of(installed("sample")),
                snapshot(row("sample", "loaded").put("identity", JSONObject.NULL)))
            .get(0);
    assertEquals("LEGACY_EVIDENCE", oldProducer.runtimeReason);
    assertTrue(oldProducer.runtimeStale);
  }

  @Test
  public void absentOrUnknownFiberCannotAppearAsAnObservedUnloadedEntry() throws Exception {
    for (String diagnostic :
        List.of("ENTRY_ABSENT", "ENTRY_PHASE_UNKNOWN", "ENTRY_NO_FIBER", "LOADER_UNAVAILABLE")) {
      JSONObject value = row("sample", "unknown");
      value
          .getJSONArray("entries")
          .getJSONObject(0)
          .put("phase", JSONObject.NULL)
          .put("diagnostic", diagnostic);
      Item item = project(List.of(installed("sample")), snapshot(value)).get(0);
      assertFalse(item.entries.get(0).runtimeObserved);
      assertEquals("unknown", item.entries.get(0).runtimePhase);
    }
  }

  @Test
  public void observedEntryStateAndIntentAreIndependentAndExpiryInvalidatesBoth() throws Exception {
    JSONObject unloading = row("sample", "unloading");
    unloading.getJSONArray("entries").getJSONObject(0).put("phase", "unloading");
    Item value = project(List.of(installed("sample")), snapshot(unloading)).get(0);
    assertEquals("unloading", value.entries.get(0).runtimePhase);
    assertTrue(value.entries.get(0).runtimeVerified());
    assertNull(value.entries.get(0).patchDisabled);
    assertFalse(value.runtimeStale);
    Item expired = project(List.of(value), snapshot(unloading).put("at", NOW - 30_001)).get(0);
    assertTrue(expired.runtimeStale);
    assertFalse(expired.entries.get(0).runtimeVerified());
  }

  @Test
  public void unavailableManagementDoesNotEraseInstalledInventoryOrProvePatchHealth()
      throws Exception {
    JSONObject activity =
        snapshot(row("sample", "loaded"))
            .put(
                "management",
                new JSONObject()
                    .put("state", "unavailable")
                    .put("reasonCode", "MANAGEMENT_SERVICE_ABSENT")
                    .put("evidence", "SERVICE_PRESENCE"))
            .put(
                "patchChain",
                new JSONObject()
                    .put("state", "present-unverified")
                    .put("reasonCode", "PATCH_PROVENANCE_UNVERIFIED")
                    .put("evidence", "STARTUP_METADATA"))
            .put(
                "coverage", new JSONObject().put("limited", false).put("reasons", new JSONArray()));
    var management = PluginRuntimeProjection.management(activity, GENERATION, INSTANCE, NOW);
    assertEquals("unavailable", management.managementState);
    assertEquals("present-unverified", management.patchState);
    assertEquals(1, project(List.of(installed("sample")), activity).size());
    assertTrue(project(List.of(installed("sample")), activity).get(0).enabled);
    assertEquals(
        "unknown",
        PluginRuntimeProjection.management(activity, GENERATION + 1, INSTANCE, NOW)
            .managementState);
    assertEquals(
        "unknown",
        PluginRuntimeProjection.management(snapshot(), GENERATION, INSTANCE, NOW).managementState);
  }

  @Test
  public void staticPreflightAndManagementProtectionNeverRewriteRuntimeOrNativeDisable()
      throws Exception {
    Item original =
        new Item(
            new JSONObject()
                .put("name", "sample")
                .put("version", "1.0.0")
                .put("enabled", true)
                .put("entries", entries(false))
                .put("installedDsh", "0.2.2-rc.1")
                .put("runtimePlatform", "linux")
                .put("contentAlgorithm", "dependency-graph-sha256-v1")
                .put("contentFingerprint", "a".repeat(64)));
    JSONObject evidence =
        row("sample", "loaded")
            .put("managementReadOnly", true)
            .put("managementReadOnlyReason", "MANAGEMENT_REQUIRED")
            .put(
                "eligibility",
                new JSONObject()
                    .put("state", "incompatible")
                    .put("reasonCode", "PLUGIN_INCOMPATIBLE")
                    .put("evidence", "PREFLIGHT")
                    .put("name", "sample")
                    .put("version", "1.0.0")
                    .put("profile", "web")
                    .put("generation", Long.toString(GENERATION))
                    .put("instance", INSTANCE)
                    .put("observedAt", NOW - 60_000)
                    .put("runtimeVersion", "0.2.2-rc.1")
                    .put("runtimePlatform", "linux"));
    evidence
        .getJSONArray("entries")
        .getJSONObject(0)
        .put("managementReadOnly", true)
        .put("managementReadOnlyReason", "MANAGEMENT_REQUIRED");
    Item projected = project(List.of(original), snapshot(evidence)).get(0);
    assertEquals("loaded", projected.runtimeState);
    assertEquals("incompatible", projected.eligibilityState);
    assertEquals(Boolean.TRUE, projected.managementReadOnly);
    assertEquals(Boolean.TRUE, projected.entries.get(0).managementReadOnly);
    assertFalse(projected.withIntent(false).enabled);
    evidence.getJSONObject("eligibility").put("instance", "older");
    assertEquals("unknown", project(List.of(original), snapshot(evidence)).get(0).eligibilityState);
    Item expired = project(List.of(projected), null).get(0);
    assertNull(expired.managementReadOnly);
    assertNull(expired.entries.get(0).managementReadOnly);
    assertEquals("unknown", expired.eligibilityState);
  }

  @Test
  public void wrongEntryModuleParentOrGroupCannotReceiveManagementProtection() throws Exception {
    for (String field : List.of("moduleHash", "parentId", "group")) {
      JSONObject evidence = row("sample", "loaded");
      JSONObject raw =
          evidence
              .getJSONArray("entries")
              .getJSONObject(0)
              .put("managementReadOnly", true)
              .put("managementReadOnlyReason", "MANAGEMENT_REQUIRED");
      if ("group".equals(field)) raw.put(field, true);
      else raw.put(field, "moduleHash".equals(field) ? "c".repeat(64) : "other");
      Item item = project(List.of(installed("sample")), snapshot(evidence)).get(0);
      assertNull(item.entries.get(0).managementReadOnly);
      assertFalse(item.entries.get(0).runtimeObserved);
    }
    for (String reason : List.of("", "unknown", "PROTECTION_UNVERIFIED")) {
      Item item =
          new Item(
              new JSONObject()
                  .put("name", "sample")
                  .put("managementReadOnly", true)
                  .put("managementReadOnlyReason", reason));
      assertNull(item.managementReadOnly);
      assertNull(
          new com.deepseekharness.app.util.PluginEntryView(
                  "entry", "a".repeat(64), "", false, null, "PROFILE_UNSET")
              .withManagement(true, reason)
              .managementReadOnly);
    }
  }

  @Test
  public void oneBadEntryCannotPoisonTheFollowingMatchedSiblingReason() throws Exception {
    for (boolean wrongFirst : new boolean[] {true, false}) {
      JSONArray expected = new JSONArray();
      JSONArray observed = new JSONArray();
      for (int index = 0; index < 2; index++) {
        String id = (index == 0) == wrongFirst ? "bad-entry" : "good-entry";
        expected.put(
            new JSONObject()
                .put("entryId", id)
                .put("moduleHash", "b".repeat(64))
                .put("parentId", "")
                .put("group", false)
                .put("patchReason", "PROFILE_UNSET"));
        observed.put(
            new JSONObject()
                .put("entryId", id)
                .put("moduleHash", "bad-entry".equals(id) ? "c".repeat(64) : "b".repeat(64))
                .put("parentId", "")
                .put("group", false)
                .put("phase", "active")
                .put("diagnostic", "ENTRY_ACTIVE"));
      }
      Item base =
          new Item(
              new JSONObject()
                  .put("name", "sample")
                  .put("version", "1.0.0")
                  .put("contentFingerprint", "a".repeat(64))
                  .put("contentAlgorithm", "dependency-graph-sha256-v1")
                  .put("entries", expected));
      Item result =
          project(List.of(base), snapshot(row("sample", "loaded").put("entries", observed))).get(0);
      var good =
          result.entries.stream()
              .filter(entry -> "good-entry".equals(entry.entryId))
              .findFirst()
              .orElseThrow();
      var bad =
          result.entries.stream()
              .filter(entry -> "bad-entry".equals(entry.entryId))
              .findFirst()
              .orElseThrow();
      assertTrue(result.runtimeStale);
      assertEquals("ENTRY_MODULE", result.runtimeReason);
      assertEquals("ENTRY_MODULE", bad.runtimeReason);
      assertEquals("", good.runtimeReason);
      assertTrue(good.runtimeVerified());
    }
  }

  @Test
  public void snapshotPathCanonicalizesAliasedRuntimeRootBeforeNofollowRead() throws Exception {
    File real = temporary.newFolder("files", "linux", "ubuntu");
    File alias = new File(temporary.getRoot(), "alias-root");
    Files.createSymbolicLink(alias.toPath(), real.toPath());
    File snapshot = PluginRuntimeProjection.snapshotPath(alias);
    assertEquals(
        new File(real.getCanonicalFile(), "root/.dsha-web-activity.json").getPath(),
        snapshot.getPath());
    assertFalse(snapshot.getPath().contains("alias-root"));
  }

  @Test
  public void snapshotPathRejectsMissingRuntimeRoot() {
    assertThrows(IOException.class, () -> PluginRuntimeProjection.snapshotPath(null));
  }
}
