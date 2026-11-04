# DSHA 0.2.1-rc.5-20261104.1412-dsh0.2.1-rc.5 / build308

- When you confirm an operation on an external device, the selected target is fixed. Steps that have yet to be sent are rejected if the request becomes invalid, the device binding changes, or external access is turned off.
- Read-only debugging commands for external devices now validate their arguments. wm can query display size and density. logcat accepts supported read options and filters. Arguments that change display settings, clear logs, resize buffers, or write files are rejected.
