# DSHA 0.2.1-rc.5-20261103.1500-dsh0.2.1-rc.5 / build295

## Tablet, long conversations and streaming responses

- SSE streaming compatibility: handle event blocks without data correctly. Preserve SDK compatibility for complete JSON at EOF; malformed JSON still fails explicitly.
- Long conversations avoid repeated conversion and component construction. Incremental session status and workspace fork ordering retain existing order without deep recursion.
- Tablet layout avoids repeated full-page scans. Native high refresh rate and theme sampling reuse unchanged inputs while retaining focus, display, settings and page-generation invalidation.
- Startup avoids repeated APK input reads and imports optional Python modules on demand. Permission, data-directory and error boundaries remain intact.
- The formal and independent recovery environments share identical archive bytes to retain APK size savings; running directories and lifecycles remain independent.
- Validation scope: host regressions, browser results and device acceptance are recorded separately. The delivery report states actual results; no fixed speedup or coverage of every historical data shape is promised.
