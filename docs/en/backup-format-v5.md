# Host data archive v5

> Translation verified: 2026-10-21. Source baseline: the current Chinese v5 format document and the F10 `087724c` maintenance baseline. This is a format and implementation reference, not a new build or device acceptance result. Final build identity and executed scope are in the [English README](../../README.en.md) and [release introduction](release-introduction.md).

Build 131 added backward-compatible `dataCompatibility` metadata recording the model schema and observed runtime/read-write implementation digests. Without a reliable format declaration, `formatId` and `formatEpoch` are empty and state is `unknown`. APK version, runtime version, and data format are different generations. A sender's declaration is not local verification: preflight continues to show an unconfirmed format and does not use it for blind downgrades or automatic deletion of originals. Host restore with an established scope still applies. Retained-copy and old-tree entry points are described in the [historical stability acceptance record](../stability-acceptance.md).

Implementations are `backup/BackupArchive.java` and `backup/PortableBackupCrypto.java`. Integration and validation history is in the [historical acceptance record](../backup-upgrade-acceptance.md). v5 does not redefine historical v1–v4; the existing Python engine actually writes format 4.

## Portable files

Portable files are named `DSHA-data-v5-<UUID>.tar.gz`. The tar/gzip contains a `portable.dshbak` payload and a `README.txt`. In build377 one-click backup requires a password. Custom and rescue exports also use a password by default. The protected payload starts with `DSHABAK5`. An explicit plaintext compatibility export contains a `DSHADATA` container and its trailing digest.

Import checks the gzip CRC, tar headers, member types and complete stream. A plaintext rescue export may include one bounded `diagnostics.txt`. Protected rescue exports omit external diagnostics. Raw `.dshbak` files and historical `.tar.gz`, `.tgz` and `.tar` backups are recognized by their contents.

Importing encrypted data requires the password used to create it. Re-export of an existing manually encrypted copy verifies its digest and preserves its ciphertext and original password. Re-export of a local automatic or plaintext copy asks for a new portable password. Automatic sources are authenticated with the device key before re-encryption. The new protected manifest records `PASSWORD_ENCRYPTED` and preserves its actual scope and integrity level.

All multibyte integers use big-endian order. Encrypted v5 containers have a 48-byte header.

| Offset | Length | Meaning |
|---:|---:|---|
| 0 | 8 | ASCII `DSHABAK5` |
| 8 | 4 | Format version, 5 |
| 12 | 4 | PBKDF2 iteration count; 600000 for new files |
| 16 | 4 | Algorithm suite, 1 |
| 20 | 16 | Random salt |
| 36 | 12 | Random GCM nonce |

Suite 1 derives a 32-byte AES key using PBKDF2-HMAC-SHA256 and encrypts using AES-256-GCM. The complete header is AAD. Ciphertext follows the header; the last 16 bytes are the GCM authentication tag. Each internal encrypted copy generates an independent salt and nonce. Readers accept only 600000–1200000 iterations and reject untrusted parameters before derivation.

Passwords are encoded directly as UTF-8 without Unicode normalization. The current interface accepts 12–1024 Java UTF-16 units. Passwords, derived keys, and intermediate buffers are not written into task records or preferences. Clearable memory arrays are erased at task completion, without a promise to eliminate every historical copy in the VM or flash storage.

Decryption may produce plaintext before tag verification. The job writes it to an isolated private file, then parses the archive only after authentication. Authentication failure removes this staging file. Anyone with an explicitly exported plaintext copy can read its conversations, attachments, projects and settings.

Derivation parameters follow the [OWASP PBKDF2-HMAC-SHA256 guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html). Cryptographic code uses the locked Bouncy Castle lightweight API without replacing the system Provider. Library versions, digests, and licenses are in `tools/backup-dependencies.lock.json` and `THIRD_PARTY_NOTICES.md`.

## Plaintext container

The container is an uncompressed streaming sequence of records:

1. Eight ASCII bytes `DSHADATA`, followed by a four-byte version number 5.
2. Each record: four-byte JSON metadata length, UTF-8 JSON, eight-byte payload length, then payload.
3. Four-byte `-1` ends the records, followed by a four-byte manifest length and UTF-8 manifest.
4. A 32-byte SHA-256 covers every preceding container byte, including the end marker, manifest length, and manifest contents. EOF must immediately follow the digest.

Required record fields are `root`, `path`, `kind`, `scope`, `size`, and `sha256`. Optional `mode` is a permission value from 0–0777. `FILE` carries its actual payload and per-file SHA-256; other kinds must have zero payload and an empty digest. Kinds include `DIRECTORY`, `LINK`, `MISSING`, `UNREADABLE`, and `EXCLUDED`, keeping explicit empty directories, absent directories, and read errors distinct. Links carry `target`; failed/excluded records may carry `reason`.

Scopes are `application`, `sessions`, `settings`, `plugins`, and `projects`. An `application` logical root may contain records of different categories; a root declared with a specific scope must contain only that scope. Android exports split data into subroots, and local mapping determines restore targets. Unknown logical roots or scopes do not automatically become a full application restore.

The manifest must contain format, entry count, payload bytes, creation time, app version, runtime provenance, operation type, data-format declaration, sensitive-content policy, plugin-dependency information, logical-root descriptions, and integrity level. Root descriptions must match the roots actually present in the payload in both directions. Duplicate members, conflicting paths, incorrect sizes/digests, truncation, and trailing bytes are rejected.

Integrity levels:

- `QUIESCENT`: application writers have stopped and sources have been checked again.
- `EXTERNAL_CHECKED`: application writers have stopped and sources that external apps may still change are read and checked repeatedly; other apps are not claimed to be frozen.
- `BEST_EFFORT`: read-only rescue without an application-level quiescence guarantee.
- `PARTIAL`: missing, unreadable, or changed items exist, with the specific entries/sources recorded in the manifest.

## Limits and paths

Limits are 16 GiB total payload, 100000 records, and 512 logical roots. Per-record metadata is limited to 16 KiB, the final manifest to 2 MiB, aggregate record metadata to 32 MiB, paths to 2048 characters and 64 levels, and link depth to 40. The container/encrypted stream has an additional allowance of at most 256 MiB structural overhead. Counts use actual bytes read/written.

Absolute write paths, drive letters, backslashes, NUL, empty path segments, `.`, `..`, and newlines are rejected. An empty relative path within a root denotes that logical root itself. Collision keys use NFC plus case folding to conservatively reject names that might collide across filesystems. Unsupported naming combinations return an error rather than silently dropping entries.

The parser writes payload into private `payload/<sequence>` slots; archive paths do not directly become active write locations. Android target mapping rechecks allowed roles/names: native settings use a whitelist; plugins, executable scripts, and shared Cordis patches enter quarantine. PID, environment markers, bridge credentials, and old transaction state remain in the input copy rather than applying to this device.

## Historical readers

`LegacyTarReader` checks tar headers, PAX/GNU long-name limits, member types, and outer gzip CRC/ISIZE, and reads through complete EOF. It rejects special files, sparse extensions, escaping paths, duplicate members, concatenated gzip, and trailing data. Old soft/hard links may refer only to verified payload inside the archive, not to host paths asserted by the archive.

`LegacyBackupImporter` privately converts recognized v1–v4 inputs to v5 before using the same preflight. v3/v4 require inventory/payload agreement in both directions. An old archive without scope enters a confirmation-required import flow only if an explicit historical naming pattern is recognized; unknown scope is rejected. Historical coverage remains mainly synthetic samples and does not establish validation of every old user-data path.

## Independent fixed vector

`tools/generate-backup-vector.mjs` uses Node/OpenSSL to generate `app/src/test/resources/backups/v5-aesgcm-vector.json`. Its password, salt, nonce, plaintext, and key are public synthetic test data, never production values. `PortableBackupCryptoTest.independentOpenSslFixedVectorAndJceInteroperate` checks that Bouncy Castle decryption and JCE derivation/decryption yield identical results.

## When an export is complete

Export verifies the private artifact in full, writes a new SAF or Downloads file and checks the actual output digest by reading it back. If the provider cannot read it back, the app reports that verification is incomplete and retains the private copy for retry. Only a verified destination updates the successful backup record.

`VerifiedBackupCopy` uses the original `verified.json` to check the file type, size, format marker and entire source digest. `NativeBackupJobs.reexportPortable` preserves existing manual ciphertext and generates password-protected payloads for readable plaintext or automatic sources. The original scope and integrity level carry over. See [backup and restore](backup-and-restore.md).
