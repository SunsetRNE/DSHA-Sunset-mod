# DSHA 0.2.1-rc.5-20261112.1800-dsh0.2.2-rc.1 / build360

- 發佈工程維護版（G0）：build359 之後 `main` 上只有 Rebirth 發行身分工程裝配、模組化線文件與 CI 變體，**沒有應用原始碼 / 資源 / 文案變更**。build360 與 build359 的使用者可見功能完全相同，升級只是版本身分與發佈工程變化。
- Rebirth 隔離實驗線工程裝配：新增 `dsh.android.client.rebirth` 獨立發行身分檔、`-Pdsha.rebirthCandidate=true` 未簽名候選開關、獨立 `signingConfigs.rebirth`（只讀 `DSHA_REBIRTH_*`，不回落正式金鑰）與候選核驗器；憑證尚未建立，只產 `UNSIGNED_NOT_FOR_INSTALLATION` 候選，不簽名、不可安裝、不是發佈驗收。首個候選（CI run 43188205334）12 項身分 / 清單 / 未簽名核驗全部 PASS。
- 模組化線文件：收斂表 v0.2、A1 契約草案（C01/C02 + 結果五欄位 + 投影表）、ADR-0004 決定八與 G0–G5 門禁狀態同步。只改文件，不改執行行為。
- 受管執行環境不變：本次變更不在 `runtime-descriptor` 的 `launcherInputs` 內，`runtimeId` 仍為 **`4d55973d`**（205 個輸入）。**359→360 覆蓋升級不會進入執行環境維護頁**。fixed-inputs 凍結沿用 `fixed-inputs-20261105e`，內建 `dsh-web-mobile` 3.3.5 與預設啟用不變。
- 版本與範圍：版本碼提升為 360（基於已發佈的 build359 / `73890983`）；DSHA 修訂號保持 `0.2.1-rc.5`，內建 DSH 為 `0.2.2-rc.1`。build359 已作為正式包發佈（tag `dsha-a-su-build359-20261111-120000` @ `73890983`）；目標 tag `dsha-a-su-build360-20261112-180000` 由 release 工作流在使用者確認後建立。
- **未驗證 / 未驗收**：build360 只通過單元測試、lint 與倉庫門禁；本版無應用行為變化，但仍未做真機覆蓋安裝驗收。G0 門禁未通過：Rebirth 憑證未建立、無 signed 候選、無真機安裝與同線覆蓋證據。只允許 `.debug` / `.dev` 包，本機不裝正式包、不動正式資料。
