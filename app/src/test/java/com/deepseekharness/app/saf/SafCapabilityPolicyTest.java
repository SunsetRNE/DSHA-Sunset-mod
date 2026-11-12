package com.deepseekharness.app.saf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 边界判定：未授权形状、凭据区、软链接真实路径与 tree URI 反例。 */
public class SafCapabilityPolicyTest {
  private static final String ROOTFS = SafTestSupport.ROOTFS;

  @Test
  public void acceptsOnlyContentTreeUris() {
    assertEquals(
        SafCapabilityPolicy.TreeUri.OK,
        SafCapabilityPolicy.treeUriProblem(SafTestSupport.DOWNLOADS, null));
    assertEquals(
        SafCapabilityPolicy.TreeUri.OK,
        SafCapabilityPolicy.treeUriProblem(SafTestSupport.DOCUMENTS, null));
    assertEquals(
        SafCapabilityPolicy.TreeUri.OWN_PROVIDER,
        SafCapabilityPolicy.treeUriProblem(
            SafTestSupport.OWN_PROVIDER, SafTestSupport.OWN_AUTHORITY));
    assertEquals(
        SafCapabilityPolicy.TreeUri.MISSING,
        SafCapabilityPolicy.treeUriProblem(null, null));
    assertEquals(
        SafCapabilityPolicy.TreeUri.MISSING,
        SafCapabilityPolicy.treeUriProblem("   ", null));
    assertEquals(
        SafCapabilityPolicy.TreeUri.NOT_CONTENT,
        SafCapabilityPolicy.treeUriProblem("https://example.com/tree/x", null));
    assertEquals(
        SafCapabilityPolicy.TreeUri.NOT_CONTENT,
        SafCapabilityPolicy.treeUriProblem("file:///sdcard/x", null));
    assertEquals(
        SafCapabilityPolicy.TreeUri.NOT_TREE,
        SafCapabilityPolicy.treeUriProblem("content://x.documents/document/abc", null));
    assertEquals(
        SafCapabilityPolicy.TreeUri.NOT_TREE,
        SafCapabilityPolicy.treeUriProblem("content://x.documents/tree/", null));
    assertEquals(
        SafCapabilityPolicy.TreeUri.BAD_AUTHORITY,
        SafCapabilityPolicy.treeUriProblem("content://x.documents", null));
    assertEquals(
        SafCapabilityPolicy.TreeUri.BAD_AUTHORITY,
        SafCapabilityPolicy.treeUriProblem("content:///tree/x", null));
    assertEquals(
        SafCapabilityPolicy.TreeUri.BAD_AUTHORITY,
        SafCapabilityPolicy.treeUriProblem("content://bad authority/tree/x", null));
    assertEquals(
        SafCapabilityPolicy.TreeUri.TOO_LONG,
        SafCapabilityPolicy.treeUriProblem(
            "content://x.documents/tree/" + "a".repeat(SafCapabilityLimits.MAX_URI_LENGTH), null));
  }

  @Test
  public void localSourceRejectsRelativePathsAndCredentialZones() {
    assertNull(SafCapabilityPolicy.localSourceProblem("/root/report.md"));
    assertNull(SafCapabilityPolicy.localSourceProblem("/sdcard/Download/report.md"));
    assertEquals(
        SafCapabilityPolicy.LOCAL_NOT_ABSOLUTE,
        SafCapabilityPolicy.localSourceProblem("relative/report.md"));
    assertEquals(
        SafCapabilityPolicy.LOCAL_NOT_ABSOLUTE, SafCapabilityPolicy.localSourceProblem(""));
    assertEquals(
        SafCapabilityPolicy.LOCAL_CREDENTIAL,
        SafCapabilityPolicy.localSourceProblem("/root/.dsh/.bridge_token"));
    assertEquals(
        SafCapabilityPolicy.LOCAL_CREDENTIAL,
        SafCapabilityPolicy.localSourceProblem("/root/.ssh/id_rsa"));
    assertEquals(
        SafCapabilityPolicy.LOCAL_CREDENTIAL,
        SafCapabilityPolicy.localSourceProblem("/.android/data/x"));
    assertEquals(
        SafCapabilityPolicy.LOCAL_CREDENTIAL,
        SafCapabilityPolicy.localSourceProblem("/root/.credentials.yaml"));
    assertEquals(
        SafCapabilityPolicy.LOCAL_CREDENTIAL,
        SafCapabilityPolicy.localSourceProblem("/root/project/.env"));
    assertEquals(
        SafCapabilityPolicy.LOCAL_CREDENTIAL,
        SafCapabilityPolicy.localSourceProblem("/root/.aws/credentials"));
  }

  @Test
  public void canonicalPathCheckBlocksSymlinksThatResolveToCredentialsOrOutsideRootfs() {
    assertNull(SafCapabilityPolicy.localCanonicalProblem(ROOTFS + "/root/report.md", ROOTFS));
    assertEquals(
        SafCapabilityPolicy.LOCAL_CREDENTIAL,
        SafCapabilityPolicy.localCanonicalProblem(ROOTFS + "/root/.dsh/.bridge_token", ROOTFS));
    assertEquals(
        SafCapabilityPolicy.LOCAL_CREDENTIAL,
        SafCapabilityPolicy.localCanonicalProblem(ROOTFS + "/root/.ssh/id_rsa", ROOTFS));
    assertEquals(
        SafCapabilityPolicy.LOCAL_CREDENTIAL,
        SafCapabilityPolicy.localCanonicalProblem(ROOTFS + "/root/.android/x", ROOTFS));
    // 软链接指向 rootfs 外的宿主私有数据：真实路径已不在受控根内。
    assertEquals(
        SafCapabilityPolicy.LOCAL_OUTSIDE_ROOTFS,
        SafCapabilityPolicy.localCanonicalProblem(
            "/data/user/0/com.deepseekharness.app/shared_prefs/x.xml", ROOTFS));
    assertEquals(
        SafCapabilityPolicy.LOCAL_OUTSIDE_ROOTFS,
        SafCapabilityPolicy.localCanonicalProblem(null, ROOTFS));
    assertEquals(
        SafCapabilityPolicy.LOCAL_OUTSIDE_ROOTFS,
        SafCapabilityPolicy.localCanonicalProblem(ROOTFS + "/root/report.md", null));
  }

  @Test
  public void credentialPathNameIsAdvisoryForRemoteTreePaths() {
    assertFalse(SafCapabilityPolicy.credentialPathName("files/report.md"));
    assertFalse(SafCapabilityPolicy.credentialPathName("projects/app/config.json"));
    assertTrue(SafCapabilityPolicy.credentialPathName(".ssh/id_rsa"));
    assertTrue(SafCapabilityPolicy.credentialPathName("id_rsa"));
    assertTrue(SafCapabilityPolicy.credentialPathName("project/.env"));
    assertTrue(SafCapabilityPolicy.credentialPathName(""));
    assertTrue(SafCapabilityPolicy.credentialPathName(null));
  }

  @Test
  public void limitsAreInclusiveAtTheDocumentedBoundary() {
    assertNull(SafCapabilityLimits.readProblem(0, SafCapabilityLimits.MAX_READ_BYTES));
    assertEquals(
        SafCapabilityLimits.OFFSET_INVALID, SafCapabilityLimits.readProblem(-1, 1));
    assertEquals(SafCapabilityLimits.MAX_INVALID, SafCapabilityLimits.readProblem(0, 0));
    assertEquals(
        SafCapabilityLimits.MAX_TOO_LARGE,
        SafCapabilityLimits.readProblem(0, SafCapabilityLimits.MAX_READ_BYTES + 1));

    assertNull(SafCapabilityLimits.writeProblem(SafCapabilityLimits.MAX_WRITE_BYTES));
    assertEquals(SafCapabilityLimits.SIZE_INVALID, SafCapabilityLimits.writeProblem(-1));
    assertEquals(
        SafCapabilityLimits.TOO_LARGE,
        SafCapabilityLimits.writeProblem(SafCapabilityLimits.MAX_WRITE_BYTES + 1));

    assertNull(SafCapabilityLimits.textProblem(SafCapabilityLimits.MAX_TEXT_BYTES));
    assertEquals(
        SafCapabilityLimits.TOO_LARGE,
        SafCapabilityLimits.textProblem(SafCapabilityLimits.MAX_TEXT_BYTES + 1));

    assertNull(SafCapabilityLimits.listProblem(SafCapabilityLimits.MAX_ENTRIES));
    assertEquals(
        SafCapabilityLimits.LIMIT_TOO_LARGE,
        SafCapabilityLimits.listProblem(SafCapabilityLimits.MAX_ENTRIES + 1));
    assertEquals(SafCapabilityLimits.LIMIT_INVALID, SafCapabilityLimits.listProblem(0));

    assertNull(SafCapabilityLimits.pathSegmentProblem(SafCapabilityLimits.MAX_PATH_SEGMENTS));
    assertEquals(
        SafCapabilityLimits.PATH_TOO_DEEP,
        SafCapabilityLimits.pathSegmentProblem(SafCapabilityLimits.MAX_PATH_SEGMENTS + 1));

    assertNull(
        SafCapabilityLimits.uriProblem("a".repeat(SafCapabilityLimits.MAX_URI_LENGTH)));
    assertEquals(
        SafCapabilityLimits.URI_TOO_LONG,
        SafCapabilityLimits.uriProblem("a".repeat(SafCapabilityLimits.MAX_URI_LENGTH + 1)));
    assertEquals(SafCapabilityLimits.URI_MISSING, SafCapabilityLimits.uriProblem(null));
  }
}
