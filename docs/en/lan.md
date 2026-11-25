# LAN access, authentication, and failure diagnosis

The current behavior described here is for build377. References to F11 and F12 describe earlier proxy and backend-recovery evidence.

F12 addition: if process identity becomes UNKNOWN within the same generation, the final publication gate rejects a late LAN cookie. Existing authentication, listener and connections for that generation are retired, so keep-alive cannot reopen them using an old cookie. A late observation of an older generation cannot retire a newer connection. This sends no signal to the Web process, does not release the unknown-exit barrier and does not clean user data.

## Enabling and revoking access

**Configuration → Allow LAN access** is off by default. Set a LAN password when enabling it. The proxy binds after the current Web run is authenticated and the network is confirmed. Android API 37 and later also require system local-network permission.

Use **Home → + → Access addresses** to confirm the network and copy `https://private-IPv4:actual-port/`. Sign in as `dsha` with the configured LAN password. For `dsh.android.client`, the proxy prefers port 3082 and can let the OS allocate a port. It binds the confirmed address and forwards to the current Web loopback endpoint. See [ports and networking](ports-and-networking.md).

Disabling LAN revokes browser sessions and closes the listener and its connections. The local Web entry remains independent. After switching networks or restarting the app process, confirm the network, copy the actual address, and sign in again.

## Reuse verified authentication after strict recovery

F12 host reconstruction checks the private record, APK/descriptor/root, and process identity, performs actual HTTP authentication, then verifies the process in the kernel a second time. Success adopts only the same still-active Web process. LAN publication reuses the cookie verified by that strict recovery. It does not exchange the token again, call the ordinary new-start `afterAuthenticated` path, or replay startup, migration, or business work.

Publication still requires the current generation, original authentication URL, and actual port. `WebLifecycleController.publishRecoveredLan` and `LanAuthBridge.publishVerified` recheck the exact snapshot; a late or mismatched result cannot bind the proxy. LAN disabled means no listener starts. A LAN bind failure records a LAN diagnostic without revoking an already successful local Web adoption. The cookie comes from that actual authentication, not a new grant inferred from old storage. Failed strict adoption preserves UNKNOWN and its original stop barrier; LAN reachability cannot prove recovery is safe.

See [WebSessionRecovery](../../app/src/main/java/com/deepseekharness/app/core/WebSessionRecovery.java), [WebLifecycleController](../../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java), and [LanAuthBridge](../../app/src/main/java/com/deepseekharness/app/core/LanAuthBridge.java). The [F12 record](releases/v0.2.1-rc.2-build190-fix12-20261021.md) describes the batch's known scope. The delivery owner freezes final software/APK counts and publication state; this page does not predeclare them.

## HTTPS and first connection

Open the current HTTPS URL from the phone. On first use the browser may present a self-signed certificate warning. Before entering the LAN password, view the certificate details and compare all 32 hexadecimal pairs of the SHA-256 fingerprint with the value in Access addresses on the phone. Trust the connection only after an exact match. If the browser cannot display the full fingerprint, use a client that can. Replace bookmarks for the old HTTP URL.

AndroidKeyStore holds the signing key. The certificate covers the current IPv4 address and lasts 90 days. Compare the fingerprint again after an address change, renewal or reinstall. DSH on the phone continues to use its local loopback address. If Android cannot identify the hotspot’s downstream interface, connect both devices to the same Wi-Fi and confirm that network. [TLS listener](../../app/src/main/java/com/deepseekharness/app/LanTlsIdentity.java) · [Key storage](../../app/src/main/java/com/deepseekharness/app/AndroidLanIdentity.java)

Each browser login issues a separate random 32-byte session cookie with `Secure; HttpOnly; SameSite=Strict; Max-Age=43200`. The server uses monotonic time to enforce the 12-hour lifetime and retains at most 64 sessions. Each listener uses a cookie named `__Host-dsha_lan_<actual-port>`, allowing two ports on the same IP to keep separate browser sessions. Ordinary requests also need the browser’s cached Basic credential for the HTTPS origin. The proxy checks a per-run in-memory HMAC and runs the password KDF only during login. Before opening a WebSocket, the bundled client requests a one-use ticket over authenticated same-origin HTTPS. The ticket expires after 60 seconds and is bound to the listener, session and requested path. The proxy removes its transport subprotocol before forwarding. Clients that send Basic on WSS can also connect using their session cookie and Basic credentials. Former URL tokens do not grant a session. See [LanAuth](../../app/src/main/java/com/deepseekharness/app/LanAuth.java).

The proxy uses the backend DSH token/cookie only against the verified local target. It does not directly expose the 3090 `X-Token` or backend credentials as browser-forwardable credentials. Request/response handling, WebSocket upgrades, chunking, and connection quotas remain governed by the bounded HTTP-processing chain.

F11 preserved an unknown backend port of 0 as not ready rather than silently falling back to 3080. If authentication within the same generation supplies a new actual port, the corresponding proxy target is replaced. An ordinary business 401/403 is forwarded for that request; an arbitrary business denial does not invalidate the entire BrowserAuth run. Idle header-reading connections and short requests have separate limits, preventing slow headers from filling the business-dispatch pool.

The listener checks the Android network instance, interface, address, and prefix at request admission and roughly every second. A change retires the listener, sessions, and connections. Android must report an identifiable non-VPN Wi-Fi, Ethernet, or USB network with a direct private IPv4 route. The client must be in the approved subnet. See [LocalNetworkAccess](../../app/src/main/java/com/deepseekharness/app/bridge/LocalNetworkAccess.java).

## Diagnose failures in this order

| Symptom | Meaning and response |
|---|---|
| No shareable LAN link | Check the toggle, network confirmation, system permission, and Web readiness. |
| 401 with `X-Dsha-Lan-Reason` | Open the app's current address and enter the browser login credentials. |
| 503 | Check current backend readiness and the actual bound address. |
| A browser remains on an old page | An old cookie or service link does not identify the new run. Reopen the current link. |
| Connection fails despite being on the same network | Check the phone IP, Wi-Fi isolation, VPN, firewall, and system permission. A network fault is not necessarily an authentication failure. |
| Local Web works but LAN fails | These entry points are independent. Preserve the working local state and investigate the proxy and network. |

The service redacts LAN logs before writing its dedicated log and logcat. Do not publish complete token-bearing links in logs or issue screenshots. Verify external-listener closure, connection shutdown, and backend-generation binding separately. A changed toggle color alone does not prove revocation took effect.

## Source and verification

Configuration entry points are [ConfigFragment](../../app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java) and [LocalNetworkAccess](../../app/src/main/java/com/deepseekharness/app/bridge/LocalNetworkAccess.java). Authentication state is managed by [LanAuthBridge](../../app/src/main/java/com/deepseekharness/app/core/LanAuthBridge.java) and [LanTokenState](../../app/src/main/java/com/deepseekharness/app/util/LanTokenState.java). Failure responses are defined by [LanFailureResponse](../../app/src/main/java/com/deepseekharness/app/util/LanFailureResponse.java). Related tests are [LanAuthTest](../../app/src/test/java/com/deepseekharness/app/LanAuthTest.java), [LanAuthBridgeTest](../../app/src/test/java/com/deepseekharness/app/core/LanAuthBridgeTest.java), and [LanTokenStateTest](../../app/src/test/java/com/deepseekharness/app/util/LanTokenStateTest.java). Source-level tests do not replace real Wi-Fi, system-permission, network-isolation, or long-stream device validation.

Historical F11 evidence for [test-lan-proxy.py](../../tools/test-lan-proxy.py) reports 49 passing cases on JDK 17, combining the actual production proxy, host loopback sockets, and logic regressions. The pre-fix comparison with five failures among 44 cases is retained. Android Context, permissions, and logging were host stubs. The checks used no device, ADB, real Wi-Fi switching, or running APK. Unknown OEM, hotspot, and VPN compatibility remains unverified. The delivery receipt must bind the exact final execution scope; this paragraph is historical host software evidence, not phone LAN acceptance or a final F12 release result.
