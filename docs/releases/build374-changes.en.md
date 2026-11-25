# DSHA 0.2.2-rc.5-20261119.2300-dsh0.2.2-rc.5 / build374

- The app and bundled DSH are both at 0.2.2-rc.5. Android 11 and later arm64 devices can update `dsh.android.client` with the existing E7E3 signing certificate.
- Improve startup and plugin reads with classic proot. Fix an issue that could return you to the Android home screen when checking all plugin updates. Fix crashes when opening a plugin installation link or turning off plugin safe mode. Fix a false environment-change message that could block a plugin update after a new version was found.
- Align the loading dialog text and remove the pending verification label. The web plugin page lists only the plugins you can manage. The app continues to provide Android shell integrations. Fix an issue where a failed web plugin update could leave the previous version unavailable. The old bundled read-hint plugin has been removed.
- Tap anywhere on the keep-screen-on and power-saving rows to change those settings. Mobile web panels transition more smoothly.
- The result of the latest manual update check stays visible next to the update button when the plugin list refreshes automatically.
- The language picker shows your saved choice immediately, including when the system language matches the one you selected.
- On tablets, the installation pane opens first and fills in the environment status and versions after reading them in the background.
- Fixed a crash when opening limited mode before the runtime is installed. Maintenance can also check an environment that has no health record yet. Fix a normal startup block after Safe start or a quick stop when no older data is waiting to be migrated. The recovery page shows known causes and lets you view details.
- On Android systems that translate ARM64 apps, maintenance and diagnostic exports now use the directory flags expected by the system.
