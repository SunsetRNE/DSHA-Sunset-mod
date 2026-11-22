# DSHA 0.2.1-rc.5-20261116.0500-dsh0.2.2-rc.1 / build368

- Rust 發布裝配（預設 prebuilt）：release-production 的 rust_native_mode 預設改為 prebuilt，將同一 run 建置並核驗的 arm64 libdsha_rs.so 與 rust-native-receipt.json 隨正式包裝配；off 仍保留為不隨包選項。同 run 產物綁定、提交與 run 回執核對、簽署後逐成員重核照舊，綁定失敗即中止；正式簽署仍由 release 工作流程以 Mod 自有憑證執行。
- Rust 執行階段後端預設啟用：DshaApp.onCreate() 在語言與設定初始化後明確開啟 RustBackends，程式庫隨包時 digest、archive 與 verify_plan 預設走原生。行程內欄位仍預設 false，一般 JVM 單元測試不受原生載入影響；setEnabled(false) 一鍵回 JVM。.so 未隨包、載入失敗或 ABI 不符時依型別化原因留在 JVM；verify_plan 一旦選中 Rust，內部錯誤或雙實作分歧仍 fail-closed 中止，不以 JVM 結果覆寫。
- Android Rust 探針適配：RustNativeInstrumentation 先核驗 App 啟動後的預設值，APK 內含收據時要求後端啟用；隨後暫時關閉開關執行原有 native 差分與失敗清理探針，finally 還原初始狀態並斷言還原成功。新增 BACKENDS_NOT_DEFAULT_ON 與 BACKENDS_RESTORE_FAILED；無收據的 .so 未隨包建置只記錄預設值，不誤報。
- 發布流水線修正：修正 dry-run/publish 單變體測試套件證據下載配置（bf491c77），test_packages=debug 不再因證據目錄合併失敗；補齊 androidTest 的 refreshPowerMode(Context) 呼叫（872d48ac），恢復 prebuilt debug 建置的 androidTest 編譯。
- 覆蓋升級影響：runtimeId 更新為 **52624be1ad386017c68c2fc986c0e834eaeec5364e9539bd0f3aa8dc74e1daba**（517 個啟動輸入、205 個資產輸入；啟動原始碼含 439 Java + 18 Kotlin），原因是 DshaApp 與 Rust 後端／橋進入受管啟動輸入。baseVersion 10、dataWrite dsh-0.2.2-rc.1 不變。**367→368 覆蓋升級後首次啟動會進入一次全螢幕執行環境維護頁**，個人資料保留；版本號提升本身不改變 runtimeId。
- 建置輸入與相容範圍：沿用 fixed-inputs-20261113，資產 dsha-a-su-fixed-inputs-20261113-build364.zip（716,787,167 B；SHA-256 2c8dc3fa494a5faf6f65cafa966b47d234a3331c30396198005cedfccca82cad）。內建 dsh-web-mobile 3.3.5、自動預設停用語意、SAF「跨應用程式檔案存取」、A8-P1 工程基礎、APK 更新（M17）、內建外掛啟用（T2）、S1a/S1b 與既有 UNKNOWN 保守處理維持不變；contract 邊界、資料格式、還原語意與還原計畫 fail-closed 不回退。
- 版本與範圍：版本碼提升為 368，以已發布 build367 為基礎（tag dsha-a-su-build367-20261115-230955 @ f3f8fc03）。DSHA 修訂維持 0.2.1-rc.5，套件名稱 io.github.sunsetrne.dsha、Mod 自有憑證、單一 Standard 與簽署方案維持不變；本候選尚未發布，計畫由使用者批准後的 Android release 工作流程建置，並附帶可並存安裝的 .debug／.dev 測試套件。
- 驗證範圍與限制：build368 之前的 main（bf491c77）prebuilt dry-run 43804700097 已全部通過（含 debug／androidTest 裝配、測試套件簽署與 Dry-run release plan），該歷史結果只證明流水線，不等於 build368 提交的完整門檻。**build368 完整 Gradle 單元測試、Lint、APK／測試套件與正式簽署仍待本版發布工作流程實際執行；裝置驗收待第二支手機以 .debug／.dev 測試套件與 ADB 日誌完成，不在本機安裝官方套件**。維護頁／READY、Rust 原生載入與回退、備份／還原與雙螢幕敏感頁待裝置核對，不宣稱廣泛 ROM／管理器相容性。
- 十語種更新說明請見 [build368 更新說明](build368-changes.zh-Hant.md)。
