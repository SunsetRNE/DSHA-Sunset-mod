package com.deepseekharness.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Cookie extraction and removal of obsolete URL credentials from redirects and forwarding. */
public class LanAuthTest {

  private static final String TOKEN =
      "A7yZ0x9w8v7u6t5s4r3q2p1onm0lk9j8i7h6g5f4e3d2c1b0a9z8y7x6w5v4u";

  @Test
  public void onlyTheSingleNamedCookieIsRead() {
    assertEquals(
        TOKEN,
        LanAuth.sessionCookie(
            "GET / HTTP/1.1\r\nCookie: __Host-dsha_lan_3082=" + TOKEN + "; other=x\r\n\r\n", 3082));
    for (String field :
        new String[] {
          "Referer: /?token=" + TOKEN,
          "Cookie: other=" + TOKEN,
          "Cookie: __Host-dsha_lan_3082=a; __Host-dsha_lan_3082=b",
          "Cookie: __Host-dsha_lan_3082=a\r\nCookie: __Host-dsha_lan_3082=b"
        }) {
      org.junit.Assert.assertNull(
          LanAuth.sessionCookie(
              "GET /?token=" + TOKEN + " HTTP/1.1\r\n" + field + "\r\n\r\n", 3082));
    }
  }

  @Test
  public void cleanRedirectPreservesCallbackAndEncodedValues() {
    assertEquals(
        "/auth/callback?code=a%2Bb&state=x%2Fy",
        LanAuth.cleanRedirectTarget("/auth/callback?token=" + TOKEN + "&code=a%2Bb&state=x%2Fy"));
    assertEquals(
        "/chat?token2=keep", LanAuth.cleanRedirectTarget("/chat?token2=keep&token=" + TOKEN));
    assertEquals("/", LanAuth.cleanRedirectTarget("/?token=" + TOKEN));
  }

  @Test
  public void backendRedirectKeepsLocalAndExternalHttpsCallbacks() {
    assertEquals(
        "/oauth/callback?code=a%2Bb#state",
        LanAuth.browserLocation(
            "http://127.0.0.1:3080/oauth/callback?token=old&code=a%2Bb#state", 3080));
    assertEquals("next?state=x", LanAuth.browserLocation("next?state=x", 3080));
    assertEquals(
        "https://login.example/auth?state=x",
        LanAuth.browserLocation("https://login.example/auth?state=x", 3080));
    assertEquals("/", LanAuth.browserLocation("http://login.example/auth", 3080));
    assertEquals("/", LanAuth.browserLocation("http://localhost:3080//example.test/path", 3080));
  }

  @Test
  public void cleanRedirectCannotBecomeAnExternalAuthorityOrHeader() {
    for (String target :
        new String[] {
          "//external.invalid/path",
          "/\\external.invalid/path",
          "https://external.invalid/",
          "/path\r\nX-Fixture: value",
          "/path#fragment",
          null
        }) assertEquals("/", LanAuth.cleanRedirectTarget(target));
  }

  @Test
  public void stripTokenFromRequestLineKeepsOtherParams() {
    String line = "GET /chat?token=" + TOKEN + "&model=x&a=1 HTTP/1.1";
    String out = LanAuth.stripTokenFromRequestLine(line);
    assertEquals("GET /chat?model=x&a=1 HTTP/1.1", out);
  }

  @Test
  public void stripTokenFromRequestLinePreservesFragmentAndVersion() {
    String line = "GET /p?a=1&token=" + TOKEN + "#frag HTTP/1.1";
    assertEquals("GET /p?a=1#frag HTTP/1.1", LanAuth.stripTokenFromRequestLine(line));
  }

  @Test
  public void stripTokenFromUrl() {
    String out = LanAuth.stripTokenFromUrl("http://192.168.1.5:3081/?token=" + TOKEN + "&x=1");
    assertEquals("http://192.168.1.5:3081/?x=1", out);
  }

  @Test
  public void stripTokenOnlyRemovesToken() {
    String url = "http://h:3081/?token=" + TOKEN;
    assertFalse(LanAuth.stripTokenFromUrl(url).contains("token="));
    // 不误删其它同名相近参数
    String url2 = "http://h:3081/?token2=keep&tokens=keep2";
    assertNotNull(LanAuth.stripTokenFromUrl(url2));
    assertTrue(LanAuth.stripTokenFromUrl(url2).contains("token2=keep"));
    assertTrue(LanAuth.stripTokenFromUrl(url2).contains("tokens=keep2"));
  }

  @Test
  public void constantTimeEqualsIsLengthAware() {
    assertTrue(LanAuth.constantTimeEquals("abc", "abc"));
    assertFalse(LanAuth.constantTimeEquals("abc", "abcd"));
    assertFalse(LanAuth.constantTimeEquals("abc", "abx"));
    assertFalse(LanAuth.constantTimeEquals(null, "abc"));
  }
}
