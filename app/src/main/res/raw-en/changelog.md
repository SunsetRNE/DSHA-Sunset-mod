# DSHA 0.2.1-rc.5-20261118.1400-dsh0.2.2-rc.5 / build370

- Development candidate: update bundled DSH to 0.2.2-rc.5 and review compatibility patches against the new interfaces, recording why each fix is retained, adapted, or retired.
- Migrate 13 Java classes in the Rust bridge and backup policy domains to Kotlin while preserving JNI entry points, object identity, exception behavior, and data contracts.
- Keep the independent recovery runtime on its own 0.2.2-rc.1 lock. The Ubuntu base version, application identities, and permission boundaries remain unchanged.
- This candidate is unpublished and has no device acceptance result. Build, test, and data compatibility claims are limited to this batch’s recorded evidence.
