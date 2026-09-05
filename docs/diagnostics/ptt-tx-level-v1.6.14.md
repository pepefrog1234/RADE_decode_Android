# v1.6.14 — PTT switching and TX level controls

## 問題分析

使用者回報最近 PTT 反應遲鈍、峰值功率約 5 W，且另一位使用者有相同現象。目前尚未取得發生問題的版本、電台型號、連線方式、PTT 延遲方向或診斷紀錄，因此不能將所有症狀歸因於單一原因，也不能宣稱實際 RF 功率已恢復。

程式碼中確認的問題與修正：

- RX→TX 會同步等待接收紀錄存檔，最多等待 5 秒。改為擷取完整紀錄後背景儲存，服務關閉時仍允許已提交的儲存完成。
- CAT PTT 原本把任何非空回覆都當成成功，包含 `RPRT -5` 等失敗碼。改用 Hamlib 的單行 Extended Response Protocol，必須同時符合 PTT 指令、ON/OFF 參數與成功碼；逾時後的舊頻率、狀態與相反 PTT 回覆不會被誤認為成功，讀取有時間上限。
- 發射期間略過接收 S-meter 查詢，減少不支援或緩慢查詢佔住 CAT 連線的機會。
- 保留 PTT ON 工作並在 OFF 前等待完成；若音訊啟動或 PTT 失敗，會停止發射並嘗試解除 PTT。若解除失敗，保留待解除狀態、顯示 CAT 錯誤，Stop 可再試，避免錯誤被當成已成功。
- 按住發話時，放開按鍵會取消排在前一次 EOO 後面的再次發射，避免放開後才重新 key。
- LE Audio 接收使用通話音量，但 USB／喇叭的發射波形使用媒體音量。原本整個 LE Audio 期間音量鍵都調整通話音量；現在發射時改調整媒體音量。設定 → TX Output 新增可直接調整的 Android 媒體音量，並說明它與 TX Level、TX Mic Gain 的差異。
- USB AudioTrack 的短寫入會繼續寫完剩餘樣本，EOO 播放等待只計算實際接受的樣本，寫入錯誤會記錄。每秒 `TX health` 記錄原始 PCM 電平、App 增益、系統媒體音量／靜音、路由、underrun 與 ring 使用量。

PTT 明確交給 IO dispatcher，主要目的是追蹤結果及保證 ON/OFF 順序；舊版 `viewModelScope` 使用 Main.immediate，不能僅憑主執行緒音訊初始化就推論舊版 CAT 一定延後送出。原生音訊開啟與 Bluetooth 路由協商仍可能花時間，新增 `TX audio setup elapsedMs` 與 CAT `elapsedMs` 可分別量測。

TX Level 的 20% 預設、使用者已存設定、modem 振幅與網路 TX drive 保持原值。沒有為了追求瓦數強制放大。EOO 與原有 200 ms RF tail 保留。

## 驗證

- `./gradlew testDebugUnitTest assembleDebug --offline`：成功。
- 新增 CAT 回覆解析、過期回覆／相反 PTT 回覆、AudioTrack 短寫入與錯誤回歸測試。
- Android Lint 仍有 4 項既有錯誤：通知權限檢查、USB receiver 註冊旗標與 2 項翻譯缺漏；已比對原始 HEAD，這些問題不是本次變更新增。
- Host AddressSanitizer V1/V2 loopback 均通過同步／解碼檢查。額外用相同的合成 feature 輸入量測未調整的輸出：V1 real PCM RMS 約 0.642、峰值 1.000；V2 RMS 約 0.496、峰值 0.997。此測試不支持「V2 數位振幅大幅下降」的假設；它不是實際語音、USB DAC 或 RF 功率量測。
- 本機沒有連接 Android 裝置／電台，未驗證 Bluetooth、實際 PTT 時間與 RF 功率。

協定依據：[Hamlib rigctld](https://hamlib.sourceforge.net/html/rigctld.1.html#PROTOCOL)；音訊 API：[Android AudioTrack](https://developer.android.com/reference/android/media/AudioTrack)、[AudioManager](https://developer.android.com/reference/android/media/AudioManager)。

## 使用者驗證方式

1. 記錄電台型號、App 原版本與 USB／網路連線方式，以相同電台功率設定比較。
2. 若是 USB 發射，檢查 TX Output 裝置與 Android 媒體音量，再觀察電台 ALC，逐步調整 TX Level／電台輸入增益。TX Mic Gain 用於語音大小。網路發射請使用相應的網路／電台 drive 設定。
3. 測試正常按下／放開、快速短按，以及 EOO 尚未送完時再次按下再放開。
4. 若仍異常，重現一輪 RX→TX（維持數秒）→RX，從設定 → 診斷 → Capture audio log 取得 `TX health`、`setPtt` 與 `TX audio setup` 記錄。

## 可轉交使用者的日文說明

ご報告ありがとうございます。PTT 切替時のログ保存待ちと、CAT エラーを成功と誤判定する問題を修正したテスト版を作成しました。また、LE Audio 使用時に送信中も通話音量を調整してしまう点を修正し、設定画面から Android のメディア音量を確認・調整できるようにしました。

ただし、ピーク約 5 W の原因はまだ確定しておらず、実機で出力が改善したことは未確認です。USB 接続の場合は、設定 → TX Output のメディア音量と送信レベルを、リグの ALC を確認しながら少しずつ調整してください。改善しない場合は、リグの型番、以前のアプリのバージョン、接続方法と、送信直後の診断ログをいただければ、音量・ルーティング・音切れ・CAT 遅延を切り分けられます。
