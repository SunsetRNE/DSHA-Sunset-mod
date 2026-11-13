# DSHA 0.2.1-rc.5-20261113.1340-dsh0.2.2-rc.1 / build364

- 資料安全與啟動觀測版（一處使用者可見的資料正確性修復）：build364 在 build363 之後合入 RC1 遷移 P1a/P1b 與非實作者評審 P0/P1/P2 修復、K0 Kotlin 受管輸入與架構邊界閘門、S0 啟動唯讀觀測基線，以及外接 adb/fastboot 路徑與首次 proroot 試執行修復。**使用者可見的是 RC1 遷移的保守失敗關閉語意**；K0 / S0 為建置與診斷基礎設施，其餘為裝置路徑修復與文件。
- RC1 遷移（資料安全，使用者可見）：結果協定升級為 `resultVersion=3`，區分「本次內容副本讀回」「歷史完成」「寫入結果未知」。只有本次流程完整讀回副本才允許遷移寫入；歷史完成只授予無遷移寫入的普通啟動，且必須已有 committed 完成回執與完整 settings 帳本；快取命中綁定 generation + sourceSha256 + 期望分節集合，不再由舊布林合成成功。
- RC1 遷移（續）：history-only 核對不建立或更新設定分節、不覆寫使用者後來修改的值；來源分節缺失或 pending/writing/unknown 只回傳 pending/unknown，不寫入檔案。已派發但終態未知的寫入不再自動重放；保留資料清單按真實 current 形狀投影歷史完成 / 待處理 / 未知，未知記錄仍可唯讀查看，不授予自動還原或刪除。
- 啟動唯讀觀測基線（工程）：S0 為停止殘留、掃描、guest helper、準備命中與 Node→鑑權鏈路增加唯讀計數觀測，只進入啟動診斷與既有日誌，不改變啟動順序、等待時間與權限閘門。
- Kotlin 受管輸入（工程）：K0 讓受管源樹中的 `.kt` 進入 `runtimeId` 與架構邊界閘門，並補充 Kotlin 編譯 / 格式與重名負控；不改變執行行為。
- 裝置路徑與維護試執行修復：外接裝置庫搜尋補充 `usr/lib/aarch64-linux-gnu/android`，adb / fastboot 在外接環境可被找到；首次 proroot 受管執行時試執行不再因 profile 列舉 ENOENT 崩潰，維護頁不再多一次失敗試執行。
- 文件與驗收（僅文件）：build363 真機覆蓋驗收回執、執行時 trial 首次失敗分析、RC1 遷移設計與對外回覆、啟動鏈路評估納入文件索引。
- 覆蓋升級影響：受管啟動輸入與受管執行時變化，`runtimeId` 更新為 **`bd0016c33e807a8e60cec8b3267e78fef0d003f618c083227cb651bd274eaabf`**（489 個啟動輸入、205 個資產輸入）；**363→364 覆蓋升級後首次啟動會進入一次執行環境維護頁**並執行受管執行時試執行，屬預期，個人資料保留。內建 `dsh-web-mobile` 3.3.5 與預設啟用不變，資料格式不升級。
- 固定建置輸入：鎖保持 `fixed-inputs-20261105e`。K0 修改了 `tools/runtime_input_contract.py`（dsh 執行時 recipe input），舊 provenance 在 `ci-package` 的 `rebuild_runtime:false` 下會按設計 fail-closed；本版已用本機鎖重建執行時，封存位元組仍為 `c1bd6833…`，僅 `dsh-runtime.inputs.json` 的 recipe 摘要更新。release-production 使用 `rebuild_runtime:true` 會重建並重算描述符；`ci-package` 需在發布新 bundle 後以顯式 URL + SHA-256 觸發（本分支候選 bundle `sha256 2c8dc3fa…`，尚未上傳，故不更新鎖）。
- 版本與範圍：版本碼提升為 364（基於已發布的 build363，tag `dsha-a-su-build363-20261113-170000` @ `d0bc1b0b`）；DSHA 修訂號保持 `0.2.1-rc.5`，內建 DSH 為 `0.2.2-rc.1`；套件名 `io.github.sunsetrne.dsha`、Mod 自有憑證與簽章方案不變。裝置 shell 白名單與執行期護欄不放寬。目標 tag `dsha-a-su-build364-20261113-134000` 由 release 工作流在使用者確認後建立。
- **未驗證 / 未驗收**：build364 發布準備只通過本機靜態閘門與定向測試；全量單元測試、lint、未簽章建置、測試包與正式簽章由 release 工作流執行，結果以 CI / Release 回執為準；**未做真機覆蓋安裝驗收**。驗收項是 363→364 覆蓋安裝成功、首次啟動進入一次執行環境維護頁並完成、資料完好、到達 READY，以及 RC1 遷移在普通啟動下的提示與保留資料清單符合預期。
- 驗證範圍：發布身分與前置檢查（versionCode 364 / versionName 與 `ci/release-identity.json` 一致；`check-release-prerequisites.py` PASS）、三身分交叉核對、`generate-changelog.py --check`、`UpdateVersionPageTest` 斷言更新、`prepare-runtime-descriptor.py --check`（`bd0016c3…`，489 個啟動輸入）與 descriptor 源核驗、全樹 Java 與 Kotlin 格式、來源衛生、UI i18n、文件連結、`test-ci-workflows.py`、`test-release-signing.py`、`test-core-boundaries.py`，以及 RC1 Python 與真實 SettingsForms CJS 回歸；正式簽章 assemble 不在本機執行。
- 十語種更新說明見 [build364 更新說明](build364-changes.zh-Hant.md)。
