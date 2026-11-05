# DSHA 0.2.1-rc.5-20261106.0230-dsh0.2.2-rc.1 / build330

- 虛擬屏拒絕把宿主自身作為啟動目標（T76）：`/app/vscreen/launch` 不再接受把 DSHA 自己啟動到虛擬屏。系統不會為副屏另起實例，而是複用正在運行的同一進程；本進程裡已綁定預設 display 的 WebView 在副屏會以 `libmonochrome` 空指針崩潰（relay 真機證據：display 4、主線程 Looper 進入 WebView、複用已運行的進程 14715）。bridge 在授權詢問前、Manager 在 claim 前、Core 在發出 `am start` 前三層用同一條判定拒絕，返回 `VSCREEN_SELF_LAUNCH_REFUSED`（`state=NOT_SENT`，不發出啟動命令、不再崩潰）；內建外掛把該碼歸入 pre-dispatch，原生應用選擇器不再列出宿主，結果頁給出中英原因。這是安全拒絕，不是支援副屏 self-launch；以後若要支援須用 `createDisplayContext` + 獨立 WebView 實例。runtime-descriptor 重新生成（runtimeId `6bea62f2`），固定輸入不重建。
- 驗證範圍：宿主 contracts 整組、全樹格式檢查（JDK17）與定向單元測試（VirtualScreenPolicy、VirtualScreenManager ownership、更新頁）；本機未做真機驗收。版本碼提升為 330（基於 build329），DSHA 修訂號保持 0.2.1-rc.5，內建 DSH 為 0.2.2-rc.1。
