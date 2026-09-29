# Issue #10：v1.6.28 的持續失效

2026-09-29 檢查；程式基準 `f445bcd`（已同步 `origin/main`）。

## 現場證據

- [RX 停止](https://github.com/pepefrog1234/RADE_decode_Android/issues/10#issuecomment-5800089876)：19:47:47 的 CAT 健康檢查拆除整個 session，19:48:00 恢復；19:49:29 則是 serial/audio 停滯觸發資料 watchdog。後者不能歸咎於 CAT 判定。
- [RX 約 15 秒後恢復](https://github.com/pepefrog1234/RADE_decode_Android/issues/10#issuecomment-5800135482)：19:57:14 的 CAT teardown 與接收中斷相符。這些紀錄中的 `queueDropped=0`、`resync=0`，不支持此次中斷是接收佇列容量不足。
- 19:54:21 有一次 `ForegroundServiceStartNotAllowedException`：自動重連呼叫 `TransceiverViewModel.startReceiving`，在背景重新呼叫 `startForegroundService` 被 Android 拒絕。同一段 crash buffer 出現在多份留言，不是多次獨立崩潰。
- [Set 7103 kHz 失敗](https://github.com/pepefrog1234/RADE_decode_Android/issues/10#issuecomment-5800217562)：20:01:50 的 Set 約 3 ms 即回 `RPRT -9`，沒有相應 CI-V 寫頻送出紀錄；隨後出現 `1010000 Hz`。無法據此認定是無線機真正拒絕寫入。
- [PTT 失效](https://github.com/pepefrog1234/RADE_decode_Android/issues/10#issuecomment-5800298009) 與 [再次 Set 失敗](https://github.com/pepefrog1234/RADE_decode_Android/issues/10#issuecomment-5800359517)：部分正常頻率回覆夾在大量 PTT／Set 錯誤之間；切斷再連接才恢復。v1.6.28 忽略協定錯誤與 NAK 的健康判定會讓此狀態持續。

## 程式與重現

確認 APK 內的 rigctld 為 Hamlib 4.5.5；使用官方 4.5.5 原始碼建置 host rigctld，以真正 PTY 路徑執行測試。該版本在啟動時偵測 echo，後續交易沒有完整驗證回覆位址與命令。掉失或延遲 echo、其他命令的回覆，可污染下一次交易；這是可重現的程式缺口，但不能由截斷的現場日誌證明每次中斷皆由此造成。

原先 v1.6.28 把所有被阻擋的寫頻探測回 ACK，會讓根本未送至無線機的 Set 回報成功。整合測試可重現此問題。

## 修正

1. 橋接層固定提供一次本地 echo，濾除實體 echo，驗證回覆位址、opcode、子命令及頻率 BCD。讀取不能由 ACK 完成；寫入不能由相同 opcode 的舊讀取回覆完成。已完成／已被新命令取代的控制命令不能由舊 UDP 重傳請求重新執行。
2. 僅 Hamlib 的虛擬 VFO A/B 選擇回 ACK。未授權寫頻、記憶或 VFO copy 回 NAK；使用者明確 Set 的短期精確頻率授權仍保留。
3. 對核心 CAT 命令的持續錯誤重新計數，個別讀取成功只清除該命令的失敗紀錄。發射／切換時延後修復；已回 RX 的待確認 OFF 不再永久阻擋修復。
4. CAT 先在同一 PTY 重啟 rigctld，清除舊交易／授權／緩衝，保留 UDP 與音訊。以實際頻率及 PTT 讀回確認控制恢復，失敗才完整重連。
5. 真正網路斷線時保留既有前景服務，使用可撤銷的 ticket 恢復音訊。Stop 或失敗會撤銷 ticket；自動重連不再啟動新的背景前景服務。

## 驗證界線

CI-V 沒有每筆交易的識別碼。相同 opcode、相同 VFO 的舊資料若使用新的 UDP 序號晚到，不能單靠內容保證新鮮度；保留實際讀回與 Set 確認，也不宣稱能消除 LTE/VPN 本身的停流。裝置背景限制、鎖屏恢復及 IC-7300MK2 的長時間實測仍須現場驗證。

建議實測：Start RX 後背景／鎖屏；中斷並恢復 LTE；確認通知維持且 RX 自動回來。恢復期間按通知 Stop 應保持停止。再測 Set、PTT OFF、數分鐘交替收發，檢查 CAT-only 修復是否保留音訊，並收集新版本完整日誌。

## 本機驗證結果

`RADE_TEST_RIGCTLD=/tmp/hamlib-4.5.5/tests/rigctld ./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest --offline` 成功；155 項測試，0 失敗、0 錯誤、0 跳過，包含全部 9 項 Hamlib 4.5.5 真實 PTY 測試。Debug APK 為 `10629 / 1.6.29-cat-recovery`。裝置測試 APK 已編譯；ADB 無連接裝置，未執行實機測試。
