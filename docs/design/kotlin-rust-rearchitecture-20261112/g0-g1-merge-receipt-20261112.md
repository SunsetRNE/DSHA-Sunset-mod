# G0–G1 合并回执与门禁对齐 v0.1

> 回应：《Rebirth：G0／G1 命名空间确认与首包门禁清单》v0.1（2026-11-12）
> 用途：把对方五层门禁（G1-A…E）并入我方 G0–G5，给出逐层回执、我方 G0 工程项与两个待决问题。
> 状态：**合并回执为初稿**；不表示任何门禁已通过。当前无候选 APK、固定测试证书未创建、未安装。

---

## 0. 一页结论

1. **命名空间接受**：`dsh.android.client.rebirth` 有意选定、位于上游命名空间、不改 Mod 命名空间；我方新建独立 Rebirth 发行身份，**不复用 Mod 证书与核验链**。
2. **五层门禁接受**，并入 G0–G5；A1 改为 **"缺一不开工"**，我方文档里"不阻塞 A1 编码"的冲突表述已删除（路线图 v0.6）。
3. **两个待决问题会阻塞首包**（不是排期问题）：
   - 首发候选由**哪个仓库/分支**产出？
   - **测试证书由谁创建与保管**？（同一 `applicationId` 只能有一个签名身份，两仓各自签名会互相拒绝覆盖安装）
4. **我方 G0 工程项已列出**（本仓需新增 `rebirthCandidate` 开关、身份文件、签名配置、APK 核验、CI 变体），可先行实现，但产出 unsigned 候选不得称"可安装"。
5. **当前事实不对齐**：对方 G1-B 的 runtime 95/128（33 失败）、第六轮 CI 终态 failure；我方本轮只读文档、未跑构建。因此 G1-B 未通过，C/D/E 不能启动。

---

## 1. 命名空间确认回执

| 项 | 对方已确认 | 我方回执 |
|---|---|---|
| applicationId | `dsh.android.client.rebirth` | 接受；写入 ADR-0004 决定八 |
| 应用显示名 | `DSHA 新生` | 接受 |
| 项目／代号 | 新生计划／嫩芽 Sprout | 接受 |
| 正式 DSHA | `dsh.android.client`，身份与数据不动 | 接受 |
| 参考 Mod | `io.github.sunsetrne.dsha`，身份与数据不动 | 接受；Rebirth 不复用其证书/`release-identity.mod.json`/`verifyPublishCertificate` |
| 安装关系 | 可共存，不覆盖正式包或 Mod 包 | 接受 |
| 候选签名 | 独立、固定测试证书；不用正式或 Mod 私钥 | 接受；**唯一签名身份见 §7** |
| 证书状态 | 未创建／未固定指纹；创建后共享公钥与 SHA-256 | 接受；不填虚构值 |
| 首包 versionCode | 359 起，同线递增 | 接受；359 仅候选首包编号 |
| 首包版本标记 | 来源版本保留 + `-Rebirth-Sprout.1` | 接受 |
| 跨线数据 | 默认不继承、不自动迁移；导入另核格式/范围/密钥/权限；系统授权不继承 | 接受；写入 G4 验收 |

---

## 2. 五层门禁 → 我方 G0–G5 映射

| 对方层 | 覆盖内容 | 我方 gate | 证据归属 |
|---|---|---|---|
| G1-A 源码与输入 | 基线锁、输入/工具链、身份约束、候选开关、静态门禁、安全约束 | G5（代码基线）+ G0（身份开关） | 各仓各自出 |
| G1-B 完整软件 | fast、全量 unit、release Lint、host runtime、software、更新隔离、修复闭环 | G5 | 各仓各自出；**不可跨仓拼接** |
| G1-C unsigned 候选 | 唯一产物、包名/显示名/versionCode、release 非 debug、authorities、真未签名、资产检查、提交绑定、SHA | G0 + G2 | 各仓各自出 |
| G1-D 固定测试签名 | 独立证书、私钥保管、独立核验域、apksigner 真实验证、unsigned/signed 双 SHA | G0 | **唯一签名持有方**出 |
| G1-E 真机与覆盖 | 共存安装、初始数据/授权、启动与容器基线、更新隔离、同线覆盖、失败恢复、核心冒烟 | G1 + G4 | 首次候选由产包方执行，双方复核 |

**我方新增的 G2/G3/G4 不在对方五层里**，需要对方确认并入：
- G2 A1 任务基线（范围/文件/验证/回退/owner）；
- G3 契约草案（C01/C02 最小接口 + 结果五字段 wire + 投影表）；
- G4 数据与授权边界（跨线不继承、系统授权不随导入、导入需单独验证）。

---

## 3. 合并回执（填对方 §九）

| 门禁 | owner | 接受／调整／暂缓 | 需要的批准 | 对应提交与证据 | 当前结果（2026-11-12） |
|---|---|---|---|---|---|
| A 源码与输入 | 双方各自仓 | **接受** | 无（只读仓库） | 我方：待建 Rebirth 分支；对方：`feat/rebirth-identity` @ `0a86c652…` | 对方代码已落地；我方未开始 |
| B 自动软件 | 双方各自仓 | **接受，但要求同提交同输入** | 无 | 对方：fast 97/97、unit 4496 通过、**runtime 95/128（33 失败）**、第六轮 CI failure；我方：**build359 无 host runtime 回执**（最近 build357 fast 90/95、runtime 72/128，均为本机环境/夹具失败），见《[历史夹具与 host runtime 证据](historical-fixtures-and-host-runtime-20261112.md)》 | **未通过**（runtime 未绿） |
| C unsigned 候选 | 产包方 | **接受**；本仓已补 `-Pdsha.rebirthCandidate` 等价入口 | 无（不签名） | 我方：身份文件/构建开关/核验器/CI 变体（2026-11-12 工程装配）；产物未生成 | 🟡 工程装配中（证书未创建，仅 unsigned） |
| D 固定测试签名 | **待定唯一持有方** | **接受**；见 §7 | 证书创建与私钥保管授权 | 无 | 未开始（证书未创建） |
| E 真机与覆盖 | 产包方执行，双方复核 | **接受**；首包无前代时 E05 标"无前代，待下一包演练" | 安装/操作授权 | 无 | 未开始（未安装） |

**我方同意对方的方法学**：实现、自动测试、包检查、真机分别报告，不互相代替；unsigned 不得称可安装；签名后不得称已安装/已验收。

---

## 4. 我方 G0 工程项（本仓需要落地）

> 本仓是 `DSHA-Sunset-mod` 工作树；对方的 `-Pdsha.identity=upstream -Pdsha.rebirthCandidate=true` 命令是**他们仓的入口**，我方需要提供**等价可复核入口**，不照抄。

| # | 工程项 | 验收 |
|---|---|---|
| G0-1 | 新增 `ci/release-identity.rebirth.json`：applicationId、显示名、versionCode 359、`testSuffix` 语义、证书指纹占位（未创建前留空） | `printReleaseIdentity` 输出候选身份；空指纹不得通过正式签名门禁 |
| G0-2 | 新增构建开关 `-Pdsha.rebirthCandidate=true`（互斥：不得与 `dsha.identity=mod/upstream` 正式签名、不得与 debug/test 变体混用） | 配置矩阵测试：错误组合直接失败；dry-run 不冒充编译 |
| G0-3 | 独立签名配置 `signingConfigs.rebirth`（固定测试证书；创建后填 SHA-256；不读正式私钥） | apksigner 真实验证；unsigned/signed 双 SHA 记录 |
| G0-4 | 候选版核验器：`verifyPublishCertificate` 的候选分支（拒绝正式证书；允许 unsigned 候选并打 `UNSIGNED_NOT_FOR_INSTALLATION`） | 负控：用 Mod 证书签名候选 → 拒绝；用正式身份构建候选 → 拒绝 |
| G0-5 | APK 检查：包名、全部本地化显示名、versionCode、非 debug、authorities、min30/target37/arm64、第三方许可 | 对照 G1-C01…C08 逐项回执 |
| G0-6 | CI 变体：不接正式 Secrets；unsigned 候选 + 固定的软件门禁顺序 | CI 产物带 NOT_FOR_INSTALLATION 标记 |
| G0-7 | 文档：ADR-0004 决定八、路线图 G0、收敛表覆盖更新影响保持不变 | 链接检查通过 |

> 上述 7 项可先行实现（不产出可安装包）；完成后只关闭 G0 的"工程装配"，不关闭 A–E 证据。

**2026-11-12 落地状态（v0.2 增量）**：

- G0-1：`ci/release-identity.rebirth.json` 已建，含包名/显示名/versionCode 359/versionName/证书占位（64 个 0）与独立后缀、tag/APK 前缀；`tools/release-identity.py --check` 覆盖三身份。
- G0-2：`-Pdsha.rebirthCandidate=true` 已实现；与 `dsha.identity=mod|upstream`、`.test/.debug/.dev` 互斥；`printReleaseIdentity` 输出 `candidate/unsigned/versionCode/certificateStatus`。
- G0-3：`signingConfigs.rebirth` 只读 `DSHA_REBIRTH_KEYSTORE` / `DSHA_REBIRTH_KEYSTORE_PASSWORD` / `DSHA_REBIRTH_KEY_ALIAS` / `DSHA_REBIRTH_KEY_PASSWORD`，不回落 `DSHA_KEYSTORE`；证书未固定前签名候选在配置阶段被 `REBIRTH_CERTIFICATE_UNSET` 拒绝。
- G0-4/5/6：候选核验器与 CI 变体已落盘（`tools/verify-rebirth-candidate.py` 35 项单测、`tools/test-verify-rebirth-candidate.py`、`.github/workflows/rebirth-candidate.yml` 仅 workflow_dispatch、不接签名凭据）；身份矩阵 7 组负控与单测由 CI 实跑。本机为 aarch64，构建工具链的 aapt2 是 x86_64 ELF，**真 APK 的 aapt2/apksigner E2E 未在本机验证，也未产出 unsigned APK**。
- G0-7：ADR-0004 决定八、路线图 §0.1.1、BUILD.md 已同步；链接检查 0 error。
- **仍未通过**：证书未创建、无 unsigned/signed APK、无真机安装与覆盖证据；G0 门禁不因工程装配而关闭。

### 4.1 G0 工程装配 CI 回执（2026-11-12）

[run 43188205334](https://github.com/SunsetRNE/DSHA-Sunset-mod/actions/runs/43188205334)，commit `73890983`，全部 job 成功：

- **身份矩阵**：`candidate-valid` 通过并输出 `releaseIdentity=rebirth` / `applicationId=dsh.android.client.rebirth` / `versionCode=359` / `versionName=0.2.1-rc.5-20261111.1200-dsh0.2.2-rc.1` / `certificateStatus=unset`；7 组负控（identity=mod/upstream、缺候选开关、`sunsetTest`/`debugPackage`/`devPackage`、缺 `unsignedPackage`）全部以预期 `REBIRTH_*` 标记失败。
- **unsigned 候选**：`dsha-rebirth-NOT_FOR_INSTALLATION-73890983.apk`，316,563,780 B，SHA-256 `7f9702cd0a4d642a7101fefe7b3a92c9115685bf900f2a4a3a276849b91f93b3`；12 项核验全部 PASS（包名/versionCode/versionName/非 debuggable/min30/target37/仅 arm64/显示名/无测试后缀/authorities/确实未签名/证书 unset）。
- **产物**：artifact `rebirth-candidate-NOT_FOR_INSTALLATION-73890983…`（约 298 MB），只标 `UNSIGNED_NOT_FOR_INSTALLATION`，不可安装、不是发布验收。
- **未验证**：证书未创建、无 signed 候选、无真机安装与同线覆盖证据；G0 门禁不因工程装配或 CI 而关闭。

---

## 5. 可先行 vs 不可开工（对齐对方最后一段）

| 可先行（不需要 G0–G5 完成） | 不可开工 |
|---|---|
| ADR 与文档维护 | A1 业务编码（C01/C02 接口实现） |
| 契约草案与五字段投影表 | 页面/导航实现 |
| 收敛表六列 | 数据路径改动（M12/M20） |
| Gecko 评审（对方执笔） | 任何进入 Rebirth 首包的代码合入 |
| G0-1…G0-7 工程装配（unsigned 候选可产出，但不得称可安装） | — |

我方已删除"文档答完=可开工"与"不阻塞 A1 编码"的表述。

---

## 6. 计数口径提醒（避免拿两边数字对比）

- 对方"全量 unit 4496 项"是**用例项**；我方"706 个 `*Test.java`"是**测试类**。两者不可直接比较，也不可互相换算。
- 对方 runtime 95/128（33 失败）已明确"未通过"；不能因为 fast/unit 绿就推导 runtime 绿。
- 我方本轮只读文档，未跑任何构建；G5 的"全量单测绿"尚无本仓回执。

---

## 7. 两个待决问题（阻塞首包，不是排期）

### 7.1 首发候选由哪个仓库/分支产出？

**为什么是硬问题**：同一 `applicationId` 只能有一个签名身份。若 DSHA-god 与 DSHA-Sunset-mod 各自用不同测试证书构建 `dsh.android.client.rebirth`，两个包**无法互相覆盖安装**（签名不一致），"同线覆盖"（E05）就永远验证不了。

建议（三选一）：

| 选项 | 含义 | 代价 |
|---|---|---|
| **A. 单产包方**（推荐） | 指定一个仓/分支作为唯一产包线，另一仓只做代码贡献/评审 | 另一仓不能独立出包；协调成本低 |
| B. 同证书双线 | 两仓共用同一张测试证书与私钥（私钥保管在外） | 私钥分发面扩大；需审计 |
| C. 只产 unsigned | 另一仓只产 unsigned 供检查，不签名 | 该仓无法做 D/E 门禁 |

### 7.2 测试证书由谁创建与保管？

- 证书未创建；G1-D 要求"固定、可审计、私钥不入 Git/日志/共享"。
- 需要明确：创建人、保管介质、备份、密码来源、轮换策略（丢失会破坏同线升级连续性）。
- 我方立场：**若本仓是产包方，我方创建并保管；若不是，我方接受只产 unsigned 或共用证书。**

---

## 8. 下一步

1. 对方答复 §7 两个问题 → 确定产包方与证书持有方；
2. 双方按 §3 回执补 owner/批准/证据；
3. 我方执行 G0-1…G0-7（可先行），产出 unsigned 候选并回 C01–C08；
4. 产包方跑 A/B，绿后进 C/D；
5. E 层由产包方真机执行，双方复核；
6. 全部通过后，才进入 A1 业务编码。

---

## 附录：可复制的回执（给对方的填写格式）

```text
[我方回执 2026-11-12]
A 源码与输入：接受 / 调整（说明）
B 自动软件：接受；当前 runtime 未绿，不通过
C unsigned：接受；等价入口待我方实现
D 签名：接受；证书持有方 = 待定
E 真机：接受；首包无前代，E05 待下一包演练

待决：
1 产包仓/分支 =
2 证书创建与保管方 =
3 是否接受本仓只产 unsigned（若不为产包方） =
```

*本回执只对齐门禁与分工，不构成任何安装、签名或发布授权。*
