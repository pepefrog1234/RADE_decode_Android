# RADE_decode — Android 版 FreeDV RADE 數位語音收發機

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-blue)
![License](https://img.shields.io/badge/license-LGPL--2.1-orange)

[English](README.md) | **繁體中文**

**RADE_decode** 把 Android 手機變成一台完整的 **FreeDV RADE** 短波（HF）數位語音收發機。
它能即時接收**並發射** RADE（Radio Autoencoder，無線電自編碼器）波形，全部在裝置端完成
（包含神經網路聲碼器），並可與 PC 上執行 FreeDV（freedv-gui 2.x）RADE V1 模式的電台互通。

只要用 USB 音訊把手機接上 HF SSB 收發機，或搭配 Icom IC-705 走 Wi-Fi 完全無線連接，
就擁有一台口袋裡的 RADE 電台——內建 CAT 電台控制、FreeDV Reporter 通報、
即時電台地圖與自動接收日誌。

---

## 什麼是 RADE？

[RADE](https://github.com/drowe67/radae)（Radio Autoencoder）是 David Rowe 與
[FreeDV](https://freedv.org) 專案開發的機器學習數位語音模式。它不採用傳統的
「語音編碼器 + FEC + 數據機」架構，而是用神經網路把語音直接映射為 OFDM 符號再還原，
語音由 Opus 專案的 **FARGAN** 神經聲碼器合成。成果是遠勝傳統 HF 數位語音模式的音質，
在類比 SSB 已經很吃力的低信噪比下仍能清晰通聯。

本 App 在手機上原生執行完整的 RADE V1 訊號鏈：

- **數據機：** RADE V1 OFDM、30 個載波、以 8 kHz 處理（向 FreeDV Reporter 回報的模式
  代號為 `RADEV1`）
- **聲碼器：** FARGAN @ 16 kHz，純 CPU 運算——不需網路、不需 GPU
- **呼號：** EOO（End-Of-Over，通話段結尾）訊框在每段通話（over）結束時編碼／解碼呼號

## 功能特色

**接收**
- 即時解碼任何 48 kHz 音訊來源的 RADE 訊號（USB 音訊介面、電台內建 USB 音效卡、
  或手機麥克風）
- 即時頻譜與瀑布圖（含載波標記）、SNR、頻率偏移，以及三段式同步指示
  （`SEARCH → CANDIDATE → SYNC`）
- 解碼到的呼號顯示在畫面與通知列上
- 透過前景服務在背景持續解碼——螢幕關閉或切到其他 App 都不中斷
- 每次同步的接收都自動錄成 WAV（解碼後語音），可在 App 內回放與分享

**發射**
- 完整 TX：麥克風 → RADE OFDM 波形 → 電台，呼號自動嵌入 EOO 訊框
- 通聯中一鍵切換 TX/RX；已連接電台時自動透過 CAT 控制 PTT
- TX 推動電平可調，介面內建 ALC 調整指引

**電台控制（CAT）**
- 內建 **Hamlib 4.5.5 `rigctld`**（arm64 原生執行檔）——支援 340+ 種電台型號，不需電腦
- 三種連線模式：
  - **Serial（本機）：** USB CAT 線（OTG）——支援 CDC-ACM、CP210x、FTDI、Prolific、
    CH340/CH341 晶片，可控制 DTR/RTS
  - **TCP（rigctld）：** 連線到任何相容 rigctld 的遠端伺服器，並提供
    **Hermes-Lite 2** 設定檔（Thetis / Quisk / SparkSDR / piHPSDR）
  - **Wi-Fi（IC-705）：** Icom 原生網路協定（RS-BA1）——CAT 控制**與** TX/RX 音訊
    都走 Wi-Fi，完全不需接線
- 即時頻率／模式／PTT／S 表顯示；輸入頻率時自動選擇正確邊帶與資料模式
  （USB/LSB、PKTUSB/PKTLSB）

**FreeDV Reporter 整合**
- 與 [qso.freedv.org](https://qso.freedv.org) 雙向連線：你的接收通報（呼號 + SNR）、
  發射狀態、頻率與自訂狀態訊息即時上傳
- **Stations** 分頁：列出目前所有上線電台，可依波段（160 m – VHF+）篩選
- **Map** 分頁：OpenStreetMap 世界地圖，標示 TX／RX／待機電台、大圓傳播路徑
  （實線 = 透過 EOO 確認呼號、虛線 = 由相同頻率推斷）以及每條路徑的 SNR 標籤
- GPS 自動換算 Maidenhead 網格座標並自動更新

**貼心設計**
- 接收日誌：每次接收都存入本機資料庫，含 SNR 時間曲線圖、同步事件時間軸、
  解碼呼號清單與訊號統計
- **效能節約模式（Power Save Mode）**：給低階裝置使用（停用 FFT 與頻譜繪製，
  把 CPU 留給解碼器）
- 電池最佳化設定小幫手，附各廠牌專屬步驟（Samsung、小米、華為、OPPO、vivo…）
- 介面支援 English、繁體中文、简体中文、日本語

## 系統需求

| | 最低需求 |
|---|---|
| Android | 8.0（API 26）以上 |
| CPU | 64 位元 ARM（**僅支援 arm64-v8a**——不支援 x86 或 32 位元） |
| 效能 | FARGAN 神經聲碼器以 CPU 運算；約 2018 年後的中階手機即可。很弱的裝置請開啟 **設定 → 效能節約模式**。 |
| 電台 | 任何 HF SSB 收發機。想單一 USB 線搞定，選有內建 USB 音效卡 + CAT 的機種（如 IC-7300、IC-705 等）；否則在手機與電台間加一個 USB 音訊介面／數位介面（DigiRig 等）。 |
| 配件 | USB-OTG 轉接頭（USB 音訊／USB serial 用）。IC-705 Wi-Fi 模式什麼都不用。 |

> 純收聽不需要接電台：用麥克風靠近喇叭（聲學耦合）或接 USB 音效卡即可。

## 安裝

1. 到 [**Releases**](https://github.com/pepefrog1234/RADE_decode_Android/releases)
   頁面下載最新 APK。
2. 依提示允許「安裝未知來源應用程式」後安裝。
3. 首次啟動時授予麥克風權限，並依照電池最佳化對話框設定，背景解碼才不會被系統中斷。

App 未上架 Play 商店，更新都以 GitHub Release 發佈。

## 快速上手 — 接收

1. **接好音訊。** 建議順序：
   - *USB 音訊：* 用 OTG 轉接頭把電台（或音訊介面）接上手機，
     到 **設定 → 音訊裝置 → 輸入** 選取該裝置。
   - *IC-705 Wi-Fi：* 見下方 [Wi-Fi（IC-705）](#wi-fiic-705)——音訊直接走網路，免接線。
   - *麥克風：* 手機靠近電台喇叭也行，但 USB 音訊穩定得多。
2. **電台調到** RADE 活動頻率，例如 20 m FreeDV 呼叫頻率 **14.236 MHz USB** 附近。
   **Stations** 分頁與 [qso.freedv.org](https://qso.freedv.org) 可即時看到誰在哪個頻率上。
3. 開啟 **Receiver** 分頁，按下 **START**。
4. 觀察 **INPUT** 電平表：調整電台輸出或 **設定 → 輸入增益**，讓峰值落在
   **−15 ～ −5 dBFS**。
5. 收到 RADE 訊號時，標頭會依序顯示 `SEARCH → SYNC`，接著開始播放語音；
   對方一段通話（over）結束時會送出 EOO 訊框，此時呼號就會顯示出來。

只要取得同步就會自動建立一筆接收紀錄，寫入 **Log** 分頁，
解碼語音也會存成 WAV 供回放或分享。

## 快速上手 — 發射

> 發射需持有有效的業餘無線電執照。

1. **設定 → TX 呼號：** 輸入你的呼號（最多 8 字元）——每段通話都會編入 EOO 訊框。
2. **設定 → 音訊裝置 → 輸出：** 選擇接到電台的 USB 音訊裝置
   （IC-705 Wi-Fi 模式免設，TX 音訊走網路）。
3. 按 **START**，再點 **TX**，對著手機麥克風講話。點 **BACK TO RX** 結束通話
   （此時會同時送出 EOO 呼號訊框）。
4. **調整電平**——目標是電台的 *ALC 幾乎不擺動*：
   - 手機**音量鍵**與 **設定 → RX 輸出 → 音量** 控制整體輸出，
   - **設定 → TX 輸出 → USB TX 電平** 控制送進電台 USB 音訊輸入的推動量，
   - 也要檢查電台本身的 USB MOD 電平（IC-7300：`Menu → SET → Connectors →
     USB MOD Level`）。
5. 若已在 **Rig** 分頁連接電台，PTT 會自動鍵控；否則請用電台的 VOX 或手動按 PTT。

## 電台控制（CAT）

開啟 **Rig** 分頁，從三種連線模式擇一。連線後即可看到即時頻率、模式、S 表，
並可直接輸入頻率——輸入頻率時會自動選擇正確的邊帶與資料模式
（10 MHz 以下 LSB/PKTLSB、以上 USB/PKTUSB；不支援資料模式的機種自動退回一般 USB/LSB）。

### Serial（本機）— USB CAT 線

App 內建貨真價實的 Hamlib 4.5.5 `rigctld`（arm64），直接在手機上執行，
透過原生 pty 驅動橋接 USB 線。

1. 插上 CAT 線（支援 CDC-ACM、CP210x、FTDI、Prolific、CH340/CH341 晶片；
   像 Xiegu X6100 這種複合裝置會把每個埠分開列出）。
2. 選擇**廠牌／型號**（340+ 種 Hamlib 型號，可用名稱或 Hamlib ID 搜尋）、
   設定**鮑率**，Icom 機種可選填 **CI-V 位址**（十六進位，通常 auto 即可）。
3. **DTR/RTS：** DTR 預設開、RTS 預設關——*如果你的 CAT 線把 RTS 接到 PTT，
   請保持 RTS 關閉*，否則一連線電台就會進入發射。
4. 點 **CONNECT** 並允許 USB 權限。

Xiegu 機種（G90、X6100、X5105、X108G）會自動套用較短的 CI-V 逾時設定，
確保 PTT 反應靈敏。

### TCP（rigctld）— 遠端伺服器

連到任何相容 `rigctld` 的 TCP 伺服器（預設埠 4532）：跑 `rigctld` 的電腦、FLRig
或 SDR 軟體。選擇 **Hermes-Lite 2** 設定檔可連 Thetis、Quisk、SparkSDR、piHPSDR
的 rigctl 伺服器——注意音訊仍走 Android 音訊裝置（不支援 OpenHPSDR I/Q 串流）。

### Wi-Fi（IC-705）

利用 IC-705 內建網路伺服器（Icom RS-BA1 協定，UDP 埠 50001/50002/50003）
達成完全無線操作：

1. 電台端：`Set ▸ WLAN ▸ Network Control = ON`，並設定 Network 使用者名稱／密碼。
2. 手機連到同一網路（或電台的 AP 模式），輸入電台 IP、使用者名稱與密碼，
   點 **CONNECT**。
3. CAT 控制**與** 48 kHz TX/RX 音訊都走 Wi-Fi——「Audio (Wi-Fi)」指示燈轉綠。
   不需 USB 線，也不需音訊介面。

## FreeDV Reporter、電台列表與地圖

開啟 **設定 → FreeDV Reporter** 並設定呼號後，App 會與 qso.freedv.org 保持連線：

- 你解碼到的一切都會上傳通報（呼號、SNR、頻率），同步期間還會送出「接收中」
  的即時心跳——其他電台能即時看到你的點亮起來。
- 你的發射狀態與電台頻率（CAT 已連線時）也會回報，還能設定 80 字以內的自訂狀態訊息。
- 沒設呼號則以唯讀（view）模式連線——一樣看得到所有人。

**Stations** 分頁列出所有上線電台（網格、頻率、發射指示），可依波段篩選。
**Map** 分頁把它們畫在世界地圖上：

| 標記／線條 | 意義 |
|---|---|
| 🔴 紅色標記 | 發射中的電台 |
| 🟢 綠色標記 | 接收中的電台（顯示頻率 + SNR） |
| ⚪ 灰色標記 | 待機／守聽 |
| 紅→綠實線弧線 | 已確認路徑——接收端解碼出發射端的 EOO 呼號 |
| 藍色虛線弧線 | 推斷路徑——接收端在相同頻率（±5 kHz）上同步 |
| SNR 標籤 | 路徑中點顯示回報 SNR，依數值著色 |

## 接收日誌

**Log** 分頁保存每一次接收（失去同步超過約 2 秒會自動切分為新紀錄）。
點開一筆紀錄可看到：

- 起迄時間、時長、同步比率、數據機訊框統計、使用的音訊裝置
- **解碼語音 WAV 的回放與分享**
- 解碼到的所有呼號，含解碼當下的 SNR 與訊框位置
- SNR 時間曲線圖（含同步狀態底色）、同步事件時間軸、訊號統計
  （峰值／平均 SNR、頻偏範圍）

錄音存放在 App 私有空間，透過 Android 標準分享面板匯出。

## 設定總覽

| 設定 | 預設 | 作用 |
|---|---|---|
| 音訊裝置 → 輸入／輸出 | 第一個 USB 裝置 | 選擇擷取與播放裝置；Refresh 重新掃描 |
| 輸入增益 → 數位增益 | 4.0×（12 dB） | 0.1–30× 軟體增益，補償過小的 USB 輸入；目標峰值 −15…−5 dBFS |
| RX 輸出 → 音量 | 100 % | 解碼語音音量；同時影響 TX 輸出 |
| TX 輸出 → USB TX 電平 | 20 % | RADE 波形送入電台 USB 音訊輸入的推動量 |
| TX 呼號 → Callsign (EOO) | — | 最多 8 字元，每段通話結束時隨 EOO 訊框送出 |
| FreeDV Reporter → 啟用 Reporter | 開 | 與 qso.freedv.org 保持連線 |
| FreeDV Reporter → 網格座標 | 由 GPS 取得 | 6 字元 Maidenhead 網格；有定位權限時自動填入 |
| FreeDV Reporter → 狀態訊息 | — | 顯示在 qso.freedv.org 你電台旁的自由文字（≤ 80 字元） |
| 效能 → 效能節約模式 | 關 | 隱藏頻譜／瀑布圖**並停止 FFT 運算**，讓低階裝置順暢解碼 |

## 疑難排解

- **瀑布圖看得到訊號卻一直不同步** —— 先檢查邊帶（10 MHz 以上 USB、以下 LSB），
  再檢查輸入電平（−15…−5 dBFS）。電平過大或過小是最常見的原因。
- **橘色警告「device rejected Unprocessed preset」** —— 手機強制對所選輸入套用
  語音處理（回音消除／AGC），可能破壞解碼（例如 Samsung Galaxy S24 的內建麥克風）。
  請改用 USB 音訊輸入。
- **低階裝置聲音斷續、機械音** —— 開啟**效能節約模式**；除了聲碼器之外，
  頻譜繪製與 FFT 是最大的 CPU 負擔。
- **螢幕關閉後解碼停止** —— 電池最佳化把服務砍了。請依照首次啟動顯示的
  電池設定對話框（含各廠牌專屬步驟）處理。
- **Serial 線一連上電台就進入發射** —— 你的 CAT 線把 RTS 接到 PTT 了。
  到 Rig 分頁把 **RTS 關閉**。
- **找不到 USB serial 裝置** —— 確認手機支援 USB-OTG、按 **Refresh** 重掃，
  並允許 Android 的 USB 權限對話框。
- **IC-705 Wi-Fi 連不上** —— Network Control 必須為 ON，帳號密碼要與電台
  Network User 設定一致，手機也要連得到電台 IP（同一網路或電台 AP 模式）。
- **Reporter 顯示「Not connected」** —— 到設定填入呼號並開啟
  **啟用 Reporter**；Stations 分頁會顯示確切原因。

## 權限說明

| 權限 | 用途 |
|---|---|
| 麥克風（`RECORD_AUDIO`） | 擷取 USB／麥克風的接收音訊；發射時的麥克風 |
| 網際網路／網路狀態 | FreeDV Reporter（qso.freedv.org）、IC-705 Wi-Fi、TCP rigctld |
| 定位（精確／粗略，可不給） | 自動計算 Maidenhead 網格供 Reporter 使用 |
| 前景服務（麥克風、媒體播放） | 背景持續解碼 |
| USB host（硬體功能，選用） | USB 音訊介面與 CAT 線 |

不需要儲存空間權限——錄音存於 App 私有空間，經分享面板匯出。

## 架構（開發者）

```
RX：USB／麥克風／IC-705 Wi-Fi（48 kHz）
     → Oboe 擷取
     → 多相 FIR 降取樣（→ 8 kHz）
     → RADE V1 OFDM 解調（+ EOO 呼號解碼）
     → FARGAN 神經聲碼器（→ 16 kHz 語音）
     → Oboe 播放（+ WAV 錄音）

TX：麥克風（48 kHz）
     → 特徵擷取 → RADE OFDM 調變（8 kHz）
     → 多相內插（→ 48 kHz）
     → USB 音訊／IC-705 Wi-Fi → 電台（CAT 控制 PTT）
```

| 層級 | 位置 | 說明 |
|---|---|---|
| UI | `ui/` — Jetpack Compose + Material 3 | Receiver、Rig、Stations、Map（osmdroid）、Log、Settings |
| ViewModel | `TransceiverViewModel` | 綁定服務，對外提供 `StateFlow<ServiceState>` |
| 服務 | `service/AudioService` | 前景服務（麥克風 + 媒體播放類型）；接收紀錄生命週期、訊號快照、錄音 |
| JNI 橋接 | `AudioBridge`、`RADEWrapper` | Oboe 引擎控制／RADE + FARGAN 處理 |
| 原生層 | `app/src/main/cpp/` | `audio_engine.cpp`（Oboe 串流、降取樣、訊框分派）、`rade_jni.cpp`、`radae/`（RADE 數據機，unity build）、`opus/`（FARGAN）、`eoo/`（呼號編解碼） |
| 電台控制 | `network/`、`usb/` | 內建 Hamlib 4.5.5 `rigctld`（arm64）+ 原生 USB-serial→pty 橋接；Kotlin 實作的 Icom RS-BA1 UDP 客戶端 |
| 資料 | `data/` | 原生 SQLite（不用 Room）：接收紀錄、訊號快照、同步事件、呼號事件 |
| Reporter | `network/FreeDVReporter` | 以 OkHttp 實作 Socket.IO v4 over WebSocket |

### 從原始碼建置

```bash
git clone https://github.com/pepefrog1234/RADE_decode_Android.git
cd RADE_decode_Android
./gradlew assembleDebug          # 建置 debug APK
./gradlew testDebugUnitTest      # 執行單元測試
```

- Android Studio（AGP 9.1／Kotlin 2.2.10）、JDK 17+
- NDK 與 CMake 3.22.1 由 Gradle 自動驅動——不需另外建置原生程式
- 首次原生建置會透過 CMake FetchContent 下載 Opus 1.9.0 與 Oboe 1.9.0
  （第一次需要網路）
- 產出**僅含 arm64-v8a**——請用實機或 arm64 模擬器映像測試

## 授權與致謝

本應用程式以 **GNU Lesser General Public License v2.1** 授權
（見 [LICENSE](LICENSE)）。

站在巨人的肩膀上：

| 專案 | 作者／來源 | 授權 |
|---|---|---|
| [RADE modem](https://github.com/drowe67/radae) | David Rowe 與 FreeDV 專案 | BSD 2-Clause |
| Opus / FARGAN 聲碼器 | Xiph.Org Foundation | BSD 3-Clause |
| EOO 呼號編解碼 | Codec2 / FreeDV | LGPL 2.1 |
| [Hamlib](https://hamlib.github.io/)（`rigctld`） | The Hamlib Group | LGPL 2.1+ |
| [Oboe](https://github.com/google/oboe) | Google | Apache 2.0 |
| kiss_fft | Mark Borgerding | BSD 3-Clause |
| OkHttp | Square | Apache 2.0 |
| osmdroid | osmdroid 貢獻者 | Apache 2.0 |
| [FreeDV Reporter](https://qso.freedv.org) | FreeDV 專案 | — |

本 App 始於 iOS FreeDV RADE 接收器的 Android 移植，現已發展為完整的收發機。

> **聲明：** 發射需持有所在地有效的業餘無線電執照。純接收通常不需執照，
> 但請確認當地法規。
