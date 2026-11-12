# 历史补丁夹具与 build359 host runtime 证据回复

> 回应对方 2026-11-12 补充：① 三个历史补丁夹具（welcome-notice、plugin-management pre-worker/V1、web-search 旧模块）的精确历史提交/发布资产、固定摘要与适用 DSH 代次；② build359／`92acaa04` 是否有标准 Linux、锁定 0.2.2-rc.1 输入下的完整 host runtime 报告。
> 原则：只给可复核事实；没有的报告不补写；不把 Android 单测当 host runtime。

---

## 0. 结论

1. **两个夹具的真实字节在仓库里，一个只在发布资产里**：
   - welcome-notice V1 配方、plugin-management V1 配方：仓库内 tracked 文件，摘要可直接复算；
   - web-search 旧模块：只在 build191 APK（`ba5fd1e1…`）里，仓库不存该字节；测试脚本已带 `module_from_apk()` 提取函数。
2. **你们说的"历史补丁夹具失配"可精确定位到两处断言**（§2），其中 web-search 处是自相矛盾的常量，在 build359 上必然失败。
3. **build359／`92acaa04` 没有标准 Linux host runtime 报告**。仓库发布记录明确写"本轮未重跑"；最近完整回执是 build357：fast 90/95、runtime 72/128（环境/夹具失败）。Android 单测（4495 例、18 失败）不能替代。
4. **本机无法补跑**：这是 Android/aarch64 proot，且 10 个固定输入全部未恢复（§4）。恢复后可在标准 Linux 复现，命令见 §3.3。

---

## 1. 三个夹具的精确字节、摘要与 DSH 代次

### 1.1 总表

| 夹具 | 真实字节位置 | SHA-256 | 适用 DSH 代次 | 引入提交 |
|---|---|---|---|---|
| welcome-notice V1 配方 | `tools/fixtures/welcome-notice-v1-patch.json`（仓库内） | `de03bfcd64a00718c5576c25505b14e05af5a38b56ecee2d800dfe6e3c66bde0` | **0.2.1-rc.2** | `6dcc172b`（2026-10-22） |
| plugin-management V1 配方 | `tools/fixtures/plugin-bundle-proof-workers-v1-patch.json`（仓库内） | `466c61d0fc0d180cd4828fb981a0851cf1721ced023163cf7e17469e1144b681` | **0.2.1-rc.2** | `b7bee248`（2026-10-22） |
| web-search 旧模块 | build191 APK 内 `assets/dsh-runtime.bin` → `…/node_modules/@deepseek-ai/dsh-web-search-deepseek/lib/index.js` | 模块 `fc13dd4eaba3c9298af41a4d0559a1b8bb61614462fe18ba6e76d8c598c332bd`；APK `ba5fd1e19c5043fe000f02a3ffb56f125939a11b87bad0988d1677c73334cb26`（292,218,461 B） | **0.2.1-rc.2** | 发布资产 tag `v0.2.1-rc.2-20261023.1640` |

> `6dcc172b` / `b7bee248` 为 `git log --diff-filter=A` 得到的引入提交；两者都只改到该夹具一次，可直接 `git show <commit>:<path>` 取字节。

### 1.2 welcome-notice

- 夹具字段：`version 1`、`dshVersion 0.2.1-rc.2`、module `@deepseek-ai/dsh-client-ui-settings-models/lib/client.js`、`sourceSha256 190498e15358bcd3fe6810fe6368023a20b0403e3ea493bc979eca9bdb593b28`、`packageIntegrity sha512-tjxvP66xh+i3h4Qf7h1tv7cEWVVtNAONDEN2ZM+Sv7Z2Z6P+hnr+mlXIjQdC+ovfsYQdg6TMXRle3cRr9x3Vug==`。
- 该 `packageIntegrity` 与 `4e215b74`（build192，内置 DSH `0.2.1-rc.2`）的 `tools/dsh-runtime/package-lock.json` 中的 `@deepseek-ai/dsh-client-ui-settings-models@0.2.1-rc.2` 完全一致 → **代次硬证据**。
- 旧端 canonical 可用 npm tarball 还原并双重校验：
  - `https://registry.npmjs.org/@deepseek-ai/dsh-client-ui-settings-models/-/dsh-client-ui-settings-models-0.2.1-rc.2.tgz`
  - 先校 `sha512-tjxv…`（包完整性），再取 `lib/client.js` 校 `sha256 190498e1…`。
- 新端（用于 old→new 链的 new）：`app/src/main/assets/welcome-notice-patch.json`，`dshVersion 0.2.2-rc.1`，`sourceSha256 e111ecefea8262e0…`，`packageIntegrity sha512-fIcIYmuA…`（与 `68ac972c2` / `92acaa04` 的 lock 一致）。

### 1.3 plugin-management pre-worker / V1

- V1 配方字段：`version 1`、`dshVersion 0.2.1-rc.2`、module `@deepseek-ai/dsh-plugin-manager/lib/index.js`、`sourceSha256 1b8bf9eb266d7b3b7f899175e3f435c89ed8baa8aabc677212759f8c6cd23132`。
- **`PRE_WORKER_SHA` 与 `OLD_MODULE_SHA` 不是存档字节**，而是从 canonical 派生（`tools/test-plugin-management-patch-chain.py:26` 注释、`:185`、`:189`）：
  - `pre-worker = apply(canonical, 现行链 − {WORKER, plugin-pnpm-argv})` → `1eb661c0…`
  - `old-module = apply(canonical, pre_chain + 历史 WORKER 配方)` → `8043321a…`
  - 因此这两个常量**绑定 canonical 的 DSH 代次**。注释写的是按 `0.2.1-rc.5` 重算；当前仓库 canonical 是 `0.2.2-rc.1`，所以它们在 build359 上已过期。要用真实历史字节，需要 rc.5 的 canonical（npm 完整性见 §附录）。
- 旧端真正的历史字节只有那份 V1 配方（`466c61d0…`）；rc.2 的 plugin-manager canonical 用 npm tarball + `sha512-ow+AWsO5…` 还原，取 `lib/index.js` 校 `sha256 1b8bf9eb…`。

### 1.4 web-search 旧模块

- 旧模块 `fc13dd4e…` 只在 build191 APK 里；APK 是**私有 Release 资产**，不是仓库文件：
  - 仓库记录：`docs/releases/v0.2.1-rc.2-build191-sunset-merge-20261023.md:18`（字节数 + SHA-256）；
  - tag：`v0.2.1-rc.2-20261023.1640`（私有 `SunsetRNE/DSHA-Sunset-mod-3090-Q`）。
- 提取方式（测试脚本已有）：`module_from_apk()`（`tools/test-web-search-patch-chain.py:66`），从 `assets/dsh-runtime.bin` 的 tar 里取唯一匹配路径。
- 旧端 canonical 的 npm 完整性：`sha512-1RkLY8uYawWOOrEWPCg7eemvA7T/bPXDJOdHsqhsDwejjZdv496G01hKf/W+T8twdUt8gnx5G19h06R499DoGA==`（`0.2.1-rc.2`）。
- 新端：`app/src/main/assets/deepseek-web-search-headers-patch.json`，`dshVersion 0.2.2-rc.1`，`sourceSha256 6bde60f4…`，`packageIntegrity sha512-wTOUSDd4…`。

---

## 2. 失配的精确位置（可直接修）

**① web-search：自相矛盾的断言（build359 上必然失败）**

`tools/test-web-search-patch-chain.py:103`：

```python
assert sha(canonical) == recipe['sourceSha256'] == OLD_MODULE_SHA, 'LOCKED_SEARCH_MODULE_IDENTITY'
```

- `recipe['sourceSha256']` 当前是 `6bde60f4…`（0.2.2-rc.1，见资产文件）；
- `OLD_MODULE_SHA` 是 `fc13dd4e…`（0.2.1-rc.2，build191）；
- 一个等号链同时要求它们相等 → 无法通过。

**建议修法**：拆成两个身份，别再用一条链：
- `OLD_MODULE_SHA` 只从 `--old-apk` 提取的模块字节校验（旧端）；
- 当前 canonical 用资产里的 `sourceSha256` 校验（新端）；
- `old` 与 `canonical` 的链式应用分别断言。

**② plugin-management：常量绑定旧代次**

`tools/test-plugin-management-patch-chain.py:185/189` 断言 `sha(pre)==1eb661c0…`、`sha(old)==8043321a…`，而这两个值是按 `0.2.1-rc.5` canonical 算的；当前 `92acaa04` 的 canonical 是 `0.2.2-rc.1`。在 0.2.2-rc.1 上要么重算常量，要么显式传入 rc.5 的 canonical 字节。**建议后者**：常量不可跨代次复用，改成"代次 → 期望摘要"表。

**③ welcome-notice：没有同类问题**

`tools/test-welcome-notice-patch-upgrade.py` 用 V1 配方（rc.2）+ 当前 canonical 做升级链，旧端配方是存档字节、新端用当前 canonical，代次是显式分离的，不依赖跨代次常量。

---

## 3. build359／92acaa04 的 host runtime 报告

### 3.1 结论：没有

`docs/releases/build359-changes.zh.md:9` 原文：

> host fast / runtime 全阶段本轮未重跑（最近完整回执为 build357 发布准备：fast 90/95、runtime 72/128，失败均为本机环境 / 夹具缺失，与本次发布准备无关，不预写通过）；正式签名 assemble 不在本机执行（无发布 keystore）。

同页还写明：全量 `:app:testStandardDebugUnitTest` 4495 例、18 失败（DocumentRowsSnapshot 5、DshaDocumentsProvider 4、RuntimeToolsRegistryAndroid 6、BridgeImageDecoder 1、AdbWheelBundle 1、ManagedPatchRegistryIntegration 1，均为 aarch64 Robolectric / 测试运行时夹具环境失败）。

**这两件事都不能当 host runtime 通过**：前者是未执行，后者是 Android 单测。

### 3.2 已有回执的边界

- 最近完整 host fast/runtime：**build357**，fast 90/95、runtime 72/128；build354 同数（`docs/releases/build354-changes.zh.md` 记录了 5/56 失败为 Playwright、pnpm CLI、previous APK 等环境/夹具缺口）。
- 这些是**本机（aarch64 Android/proot）**回执，不是标准 Linux x86_64，也不满足你们"锁定 0.2.2-rc.1 raw/managed 输入"的完整执行条件。

### 3.3 要产出合规报告需要什么

| 项 | 要求 |
|---|---|
| 环境 | 标准 Linux x86_64；JDK 17、Node 24、Python 3.9（`tools/host-tests.manifest.json` 的 `requires`） |
| 固定输入 | 锁 `fixed-inputs-20261105e`；bundle `dsha-a-su-fixed-inputs-20261105e.zip`，SHA-256 `be97d9f0a45f408d3a7de83ae55a00a85a5154343589571c6d159a0fe58f08f5`，716,787,167 B |
| 恢复 | `python3 tools/ci-assets.py --url <release asset> --sha256 be97d9f0…`（或 `--archive` 复用本地 ZIP） |
| raw/managed | 由 `_node-inputs.yml`（`rebuild_runtime: true`）或本地准备生成；`app/build/test-runtimes/current.json` 指向 raw |
| 执行 | `python3 tools/run-host-tests.py --stage runtime --raw <raw> --managed <managed> --jdk <JDK17> --node <node24> --output <dir>`；fast 同法 |
| 回执 | 源码 SHA `92acaa0408cb7b031ee3036c6116c6463ae6b62e` + tree、输入逐文件 sha256、命令原文、逐用例结果、失败分类（环境/夹具/产品） |

### 3.4 为什么本机现在给不了

- 本机是 Android/aarch64 proot，不是标准 Linux；
- 10 个固定输入全部未恢复（§4）；
- 本轮只读文档，未跑任何构建。

---

## 4. 本机缺失的关键内容（对应用户提到的"没恢复"）

`app/src/main/assets/` 与 `tools/recovery-runtime/archives/` 下应存在但当前**全部缺失**：

| 文件 | 字节（锁记录） |
|---|---:|
| `app/src/main/assets/offline-rootfs.bin` | 304,037,588 |
| `app/src/main/assets/dsh-runtime.bin` | 131,056,363 |
| `app/src/main/assets/ubuntu-tools.bin` | 29,919,475 |
| `app/src/main/assets/pnpm-runtime.bin` | 3,837,861 |
| `app/src/main/assets/python-support.bin` | 904,360 |
| `app/src/main/assets/glibc-python.tar.gz` | 6,565,414 |
| `app/src/main/assets/adb-wheels.tar.gz` | 9,951,074 |
| `app/src/main/assets/gh-cli.tar.gz` | 13,661,485 |
| `tools/recovery-runtime/archives/recovery-rootfs.bin` | 85,783,057 |
| `tools/recovery-runtime/archives/recovery-dsh-runtime.bin` | 131,056,363 |

另缺 `app/build/test-runtimes/current.json`（测试 raw/managed 指针）。

**恢复命令**（在标准 Linux 或本机均可，只要网络可达 Release）：

```bash
python3 tools/ci-assets.py \
  --url https://github.com/SunsetRNE/DSHA-Sunset-mod/releases/download/fixed-inputs-20261105e/dsha-a-su-fixed-inputs-20261105e.zip \
  --sha256 be97d9f0a45f408d3a7de83ae55a00a85a5154343589571c6d159a0fe58f08f5
```

> 注意：这是下载与校验，不涉及安装、签名或发布授权；恢复后仍只做只读测试。

---

## 5. 建议的下一步（给双方）

1. **夹具修复**（可立即做，不需要输入）：
   - web-search 拆 old/current 两条断言（§2①）；
   - plugin 常量改"代次 → 期望摘要"表，或显式传 rc.5 canonical（§2②）。
2. **补真实历史字节**：把 rc.2 / rc.5 的 canonical 模块按 §附录的 npm 完整性与 sha256 固化进 `tools/fixtures/`（模式参照 `tools/fixtures/rc2-upgrade-source/provenance.json`：记录 `packageVersion`、`packageIntegrity`、`packageResolved`、文件 sha256）。
3. **host runtime**：在标准 Linux 恢复固定输入后跑 `--stage runtime` 与 `--stage fast`，按 §3.3 出回执；在拿到之前，G1-B 继续按未通过计。
4. **我方**：恢复输入后先跑 welcome/plugin/web-search 三个 patch-chain 测试，确认修夹具后能解释 old→new 链；但 host runtime 仍需标准 Linux 环境，不能拿本机回执充数。

---

## 附录：四代 canonical 的 npm 完整性（可直接拉取校验）

| 代次 | 关键提交 | plugin-manager | web-search-deepseek | client-ui-settings-models |
|---|---|---|---|---|
| 0.2.1-rc.2 | `4e215b74` | `sha512-ow+AWsO5tYsMjef2HpNnKMh6QjLC/r/dsE91Ad94KP26FLFpoA/Wp4Kd+rLNRp4vSzHC7A007BfWxmb5mVJ8Kw==` | `sha512-1RkLY8uYawWOOrEWPCg7eemvA7T/bPXDJOdHsqhsDwejjZdv496G01hKf/W+T8twdUt8gnx5G19h06R499DoGA==` | `sha512-tjxvP66xh+i3h4Qf7h1tv7cEWVVtNAONDEN2ZM+Sv7Z2Z6P+hnr+mlXIjQdC+ovfsYQdg6TMXRle3cRr9x3Vug==` |
| 0.2.1-rc.3 | `6f2d32c3` | `sha512-jmu0cUTq4EL/8XNfZ/2+KXAE8XAyWb8mCwabQJd9ueQdu6FtRKE7Kc/+PpvLM3b665A8GJD6S0snM8uJNXuI8Q==` | `sha512-BAxOlzM0L00y9dFjOptldKj3AAUn/MkD/x1SAC0FKxKt99K9/lgUm3dayPTIz8oYEUlzf3vNJnLh1kQavt0Wlg==` | `sha512-k59mI4AG3PK6PDdOrNyxwwUfjIozNq4EB95PhxABNP/3eeyvqP6Tog1Ksn8/v99IHuNrCw/i3JbCCQD8ZIiZqg==` |
| 0.2.1-rc.5 | `83e4db60` | `sha512-VRfuEhPcZLh+ASO8WLEulShvNKZ0GvZquiO8/AzfFNnublSiEGKdZZU7j7R4+on16IbJtoUthYz1Re6QpoBepw==` | `sha512-CnGSJ7Pku+BiPNyOW8GkG1orC6rSa/b9KpTmuwiCNDIoRX+/Xv+qtX36eHYvnVEKhhGg8bbuEa7b2lOmD8dRXA==` | `sha512-KlgiQvPohimvBNOCvsJ8pjY2Cd4E4BveteaxKUmiwgqt8JQRrGkhTym5J7guihdtgxDEV+PnW8eZ5H8bNJTglQ==` |
| 0.2.2-rc.1 | `68ac972c2` / `92acaa04` | `sha512-6C48x7f9H6OJecb0NrO//ccOE9/xoHPMTiW5yYAYXrsHqpneXMvVdjuOR/hbeKaBOUZQEjwHRM6wu2BX6jngrQ==` | `sha512-wTOUSDd4fqnJUZ0jnN3Vo4RU4OLxcapw4+fNVG8zVQnAJqACICOEaGSAoj/iCAKayDzLRyjoYpo8l4EVGNFcVQ==` | `sha512-fIcIYmuAuDY9KE+hbWXjUISARBLalViNu7PCtbUsXVjw0nC5uAMLnr377kw6W3Pzgc59C5tOe44Jc6bTNiqdEw==` |

> 包完整性（sha512）来自各代次提交的 `tools/dsh-runtime/package-lock.json`；文件级 sha256（如 `1b8bf9eb…`、`190498e1…`、`fc13dd4e…`、`6bde60f4…`、`e111ec…`）来自夹具与资产文件，需在解包后单独校验。

*本文只给证据与复现条件，不宣称任何门禁通过；host runtime 仍需在标准 Linux 上真实执行。*
