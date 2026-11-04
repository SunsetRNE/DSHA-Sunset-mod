# Landscape dock (desktop viewport)

The launcher entry **Landscape DSH** opens a horizontal 16:9 stage. Inside it, the WebView lays the
page out at a CSS viewport of 1280×720, 1024×576 or 854×480 and the whole stage is scaled to fit the
screen; a scroll bar, three mode chips and a right-side capsule (back / home / refresh / screenshot)
sit around it. When the keyboard opens, the current scale is kept and the WebView height is reduced so
the page reflows.

This is **not** a virtual display: it does not create a `VirtualDisplay`, start a Core, appear in the
assistant-side `/app/vscreen` tools, or inject device-level input; all touches are ordinary in-app
WebView touches. The full Chinese documentation is [docs/landscape-dock.md](../landscape-dock.md).

Runtime configuration is data-only, stored in the app-private `files/landscape/dsh-a-landscape.json`
(default mode, capsule position, chrome visibility, desktop UA, scroll lock). No custom CSS/JS is
injected or executed. Screenshots taken with the capsule button are written to the public
`Pictures/DSHA` album on explicit user action; no other public directory is touched.

Security boundaries: file access, mixed content and third-party cookies are disabled, Safe Browsing is
on, `addJavascriptInterface` is not used (a `WebMessageListener` with origin/main-frame checks replaces
it), microphone requests validate the requesting origin, and non-web schemes (including `dsh://`,
`dsha://` and `intent://`) are rejected from web content. Plugin installation/management links are
only accepted from a user-gesture main-frame navigation to the exact `https://dsha.cc/install...` or
`https://dsha.cc/app/plugins` whitelist.

Minimum WebView: the androidx.webkit feature mapping (`WEB_MESSAGE_LISTENER` 88+, `DOCUMENT_START_SCRIPT`
81+). The code checks `WebViewFeature.isFeatureSupported` at runtime and degrades (scroll bar hidden,
`window.open` falls back to top-level navigation). Device-level verification of the compatibility matrix
is still pending.
