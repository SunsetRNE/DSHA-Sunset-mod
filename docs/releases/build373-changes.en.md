# DSHA 0.2.2-rc.5-20261119.1852-dsh0.2.2-rc.5 / build373

- The app and bundled DSH are both at 0.2.2-rc.5. Android 11 and later arm64 devices can update `dsh.android.client` with the existing E7E3 signing certificate.
- Storage scans and cleanup show a progress bar, the current step and the number of items processed. Counts update while the scan runs.
- Open the runtime rollback copies in Storage and files to see how much space each copy uses and why it is kept.
- The Plugins page can show the existing list while checks are running. Use the detection button to run a fresh check and see its verification status.
- Manual cleanup can compress an older DSH copy after checking its transaction record and contents. The details page can expand it in its original location. Its eligibility for runtime rollback stays the same.
