# DSHA 0.2.1-rc.5-20261103.1300-dsh0.2.1-rc.5 / build294

## Upstream changes from build279–294

- Experimental: the virtual screen page is rebuilt around four states (off, creating, running and awaiting cleanup) with a side-by-side layout in landscape; rotating reopens the original app, and the floating preview is back.
- Experimental: startup recovery explains failures by cause and suggests next steps; the native loading screen and chat error panel add waiting, diagnostics and recovery controls. Background ADB probes yield when the app is reopened after unlocking. This is a best-effort fix; the original stall could not be reproduced.
- Experimental: background plugin checks run only while the plugin page is visible. Direct Web launch and recovery through notifications or recent apps preserve the launch target, which can also be selected in Web settings.
- If the device bridge port is unpublished or cannot be read, the request fails instead of falling back to port 3090 and sending this installation's credentials. Experimental `/app/install` hands container APKs to the system installer, still requiring user confirmation; background requests open it through a notification.
- Experimental: network health diagnostics report network restrictions, service and DNS issues, and signs of process freezing; they do not repair anything automatically.
- One-tap backup stops writes before verifying data locations and excludes local device-state files from that comparison; replaced user data still aborts the backup.
- Experimental info dialogs adapt to the window shape. Stage progress animations respect system settings, the island page's supported-device entry clears the bottom capsule, and startup log scrolling keeps the same gesture owner until the gesture ends.

## Local startup and home-page changes

- Reduce repeated startup checks and disk reads while retaining runtime integrity checks and failure handling.
- A service-ready event refreshes the home page immediately and continues Web entry without waiting for the next periodic refresh; readiness checks still apply.
- The home page now places Restart on the left and Stop on the right in both wide and narrow layouts; right-to-left languages continue to follow the system layout direction.
- Retain the high-refresh-rate, animated-screen tap and environment file-management fixes. Preserve APK size savings: normal and recovery environments share packaged archive bytes only when their digests match, while their runtime directories remain independent.

## Version and verification scope

- versionCode is 294; both the DSHA revision and bundled DSH are 0.2.1-rc.5. Device acceptance remains paused, and no on-device startup timing was measured for this revision. No fixed speedup is promised. The delivery report records this revision's software checks; historical upstream validation is not evidence of device acceptance for this revision.
