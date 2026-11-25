# dsh-fs-read-hint retired from build374

The temporary plugin shipped from build300 and appended an already-read path with the same filename when DSH refused a change. Starting with build374, DSH displays its original error. Editing an existing file still requires reading that exact file first.

During an upgrade from build373, the retired bundle moves into the runtime rollback record only when its files, digests, permissions, and runtime dependency link match the former managed bundle and the previous runtime health receipt. Web registration clears dependencies and aliases tied to that former bundled path. Third-party plugins and customized directories with the same name are preserved.

[Historical build300 release record](../releases/v0.2.1-rc.3-build300-fs-read-hint-20261103.md)
