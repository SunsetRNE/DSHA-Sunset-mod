# DSHA 0.2.1-rc.5-20261116.0740-dsh0.2.2-rc.1 / build369

- Kotlin 整域遷移：capabilities 6 類、runtime 命令與掛載組裝 8 類、plugins 管理 11 類，共 25 類由 Java 遷至 Kotlin；保留既有權限、啟動與外掛交易語意，不宣稱效能提升。A8-P3 多模組切片不在本版。
- 受管動態讀取器改指 ProotBootstrap.kt，同步來源路徑與契約測試，並更新 descriptor，避免沿用舊 Java 路徑或生成器摘要。
- runtimeId 為 1d42f79d5fea6bad5caaa2409822aa5949691c89e6e230b95011ae35db8a5757。baseVersion 10、DSH 0.2.2-rc.1 與資料格式不變；368→369 覆蓋升級後首次啟動預期進入一次整屏執行環境維護頁，保留個人資料。
- Rust 裝配維持預設 prebuilt，執行時後端預設啟用；同 run 來源證明不變。函式庫不可用時依類型化原因選用 JVM；選中 Rust 後失敗不重放，也不用 JVM 掩蓋實作分歧。
- 內置 dsh-web-mobile 3.3.5 與預設啟用、使用者禁用與安全模式意圖、Android 11+ / arm64、Mod 套件名稱與簽章保持；versionCode 369。
- 整合候選 c243b7a0 的 dry-run 43823137009 已通過全部軟體門禁與簽章檢查；正式發佈 CI 重新核驗 build369 的最終提交。本版尚未真機驗收，不以 build368 裝置回執代替。
