# RADE_decode — Android 版 FreeDV RADE 數位語音收發機

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-blue)
[![Release](https://img.shields.io/github/v/release/pepefrog1234/RADE_decode_Android)](https://github.com/pepefrog1234/RADE_decode_Android/releases/latest)
![License](https://img.shields.io/badge/license-LGPL--2.1-orange)

[English](README.md) | **繁體中文** | [简体中文](README.zh-CN.md) | [日本語](README.ja.md)

**RADE_decode** 把 Android 手機或平板變成一台完整的 **FreeDV RADE** 短波（HF）數位語音
電台。它能即時接收**並發射** RADE（Radio Autoencoder，無線電自編碼器）波形，全部在裝置上
完成，連神經網路聲碼器也是，並可與電腦上以 FreeDV（freedv-gui 2.x）執行 RADE V1 的電台
互通。

用一條 USB 線把手機接上 SSB 收發機就能用，也可以完全不接線：透過 Wi-Fi 連到 Icom 電台
內建的網路伺服器、直接連上區域網路裡的 **Hermes-Lite 2**（手機就是 SDR 主機），或經由
VBAN 音訊連到電腦上的 Thetis。電台控制、FreeDV Reporter 回報、即時電台列表與地圖、
接收記錄都已內建。

> 本頁說明的是 **v1.6.37**。各版本的更動請見[發行說明](docs/release-notes/)。

---

## 什麼是 RADE？

[RADE](https://github.com/drowe67/radae)（Radio Autoencoder）是 David Rowe 與
[FreeDV](https://freedv.org) 專案開發的機器學習數位語音模式。它不採用傳統的
「語音編碼器 + FEC + 數據機」架構，而是用神經網路把語音直接對應成 OFDM 符號再還原，
語音則由 Opus 專案的 **FARGAN** 神經聲碼器合成。音質遠勝傳統的 HF 數位語音模式，
在類比 SSB 已經很吃力的低訊噪比下仍聽得清楚。

本 App 採用 FreeDV 專案的 C 語言實作（[rade_c](https://github.com/freedv/rade_c)），
在手機上原生執行整條訊號鏈：

- **數據機：** RADE V1 OFDM，30 個載波，以 8 kHz 處理。FreeDV Reporter 上顯示的模式是
  `RADEV1`。
- **聲碼器：** FARGAN，16 kHz，以 CPU 運算。不需要網路連線，也不需要 GPU。
- **呼號：** 每次發射（over）結束時，EOO（End-Of-Over）訊框會帶出電台的呼號。
- **RADE V2（實驗性）：** 可在設定中切換到較新的波形。V1 與 V2 的電台彼此聽不到，
  V2 也不帶呼號。一般使用請保持關閉。

## 功能

**接收**
- 即時解碼 48 kHz 音訊來源的 RADE 訊號：電台內建的 USB 音效卡、USB 音訊介面、
  下方的網路連線模式，或手機的內建麥克風
- 頻譜與瀑布圖（含載波標記）、訊噪比、頻率偏移，以及三段式同步指示
  （`搜尋中 → 候選 → 已同步`）
- 解碼到的呼號顯示在畫面與通知上
- **類比監聽：** 直接聽頻道上的原始音訊，發射前先確認頻率沒有人在用
- 在背景持續解碼，螢幕關閉或切到其他 App 都不中斷
- 解碼後的語音可由手機喇叭、有線或 USB 音訊，或藍牙耳機、喇叭播放，包含
  **LE Audio（LC3）**；可以指定輸出裝置並測試
- 每一段同步的接收都會錄成 WAV 檔，可在 App 內播放與分享

**發射**
- 麥克風 → RADE 波形 → 電台，呼號放在 EOO 訊框裡
- 點一下**發射**切換收發，或改用**按住發話**。電台列表與 Map 分頁也有發射鍵，
  可以一邊講話一邊看誰收到你
- 經由 CAT 控制 PTT：App 會讀回電台自己的收發狀態，電台沒收到 key-on 時會重送。
  沒有 CAT 的介面可以用 RTS 線觸發 PTT，否則就用電台的 VOX
- 內建、USB／有線，或（實驗性）藍牙麥克風，增益可調。發射中麥克風若中斷，
  App 會放開 PTT 並回到接收
- 發射音量控制，介面上附有 ALC 的調整提示

**電台連線**（電台分頁）
- **序列埠（本機）：** USB CAT 線加上內建的 **Hamlib 4.7.2** `rigctld`，支援 180 多種
  電台型號，不需要電腦
- **TCP（rigctld）：** 連到網路上相容於 rigctld 的伺服器，另有 **Hermes-Lite 2**
  設定檔，可連到 Thetis、piHPSDR、SparkSDR、Quisk 的 CAT 伺服器
- **Wi-Fi（IC-705）：** Icom 自家的網路通訊協定。CAT 與音訊都走 Wi-Fi，也能經由
  VPN 走 LTE（目前用在 IC-705 與 IC-7300MK2）
- **HL2 (區域網路)：** 以 **openHPSDR（protocol 1）** 直接連到 Hermes-Lite 2，
  手機就是 SDR 主機：PA 開關、LNA 增益、自動選擇 N2ADR 濾波器，以及實驗性的
  PureSignal
- **Thetis (VBAN)：** 音訊經 Wi-Fi 與電腦上的 Voicemeeter 互傳，電台仍由 Thetis
  掌控，PureSignal 也照常運作
- 即時顯示頻率、模式、PTT 與 S 錶；輸入頻率時自動選好邊帶與數據模式；沒有連接電台時，
  可以手動填頻率給 FreeDV Reporter

**FreeDV Reporter**
- 與 [qso.freedv.org](https://qso.freedv.org) 保持即時連線：回報你的接收（呼號與
  訊噪比）、發射狀態、頻率，以及一段自訂的狀態訊息
- **電台列表**分頁：列出所有在線電台，可依波段（160 m – VHF+）篩選，並依頻率、呼號或
  最近活動排序；每一列顯示該電台正在發射，或正在收什麼
- **Map** 分頁：OpenStreetMap 世界地圖，標示發射、接收、待機的電台，以及大圓訊號路徑
  （已解出呼號的路徑為紅到綠，依相同頻率推測的路徑為藍色虛線），每條路徑附訊噪比標籤
- Maidenhead 網格座標可由 GPS 取得，也可以手動輸入

**其他**
- 接收記錄：每一段接收都有訊噪比趨勢圖、同步事件時間軸、解出的呼號與訊號統計
- **效能節約模式**：給效能較低的裝置使用，不畫頻譜與瀑布圖、不做 FFT，把 CPU 留給解碼
- 電池最佳化設定精靈，附各廠牌的操作步驟（Samsung、Xiaomi、Huawei、OPPO、vivo、
  ASUS、Sony）
- 可在 App 內擷取 CAT 與音訊紀錄，方便回報問題
- 介面語言：English、繁體中文、简体中文、日本語

## 系統需求

| | 最低需求 |
|---|---|
| Android | 8.0（API 26）以上 |
| CPU | 64 位元 ARM（**僅 arm64-v8a**；沒有 x86 或 32 位元版本） |
| 效能 | FARGAN 神經聲碼器以 CPU 運算。中階手機就夠用；較舊或效能較低的裝置請開啟**設定 → 效能 → 效能節約模式**。 |
| 電台 | 任何 HF SSB 收發機。內建 USB 音效卡與 CAT 的電台（IC-7300、IC-705 與許多新機種）只要一條線。其他電台需要在手機與電台之間加一個 USB 音訊介面或數位介面（DigiRig 之類）。有網路伺服器的 Icom 電台與 **Hermes-Lite 2** 完全不需要音訊介面。 |
| 配件 | USB 音訊與 USB 序列埠需要 USB-OTG 轉接頭。網路連線模式不需要任何配件。 |

> 只接收的話不需要連接電台：讓聲音從麥克風進來（聲學耦合），或接一張 USB 音效卡，
> 就能直接收聽。

## 安裝

1. 從[最新的發行版本](https://github.com/pepefrog1234/RADE_decode_Android/releases/latest)
   下載 **`app-release.apk`**。
2. Android 詢問時，允許從這個來源安裝，然後安裝 APK。
3. 第一次按下**啟動**時，請允許麥克風權限。Android 12 以上還會詢問「鄰近裝置」權限，
   藍牙音訊需要它；不允許也能使用。
4. 依照電池最佳化對話框的指示設定，解碼才能在背景持續執行。

本 App 沒有在 Google Play 上架；更新都以 GitHub 發行版本的形式提供。

**更新。** v1.6.21 起的每個版本都用同一把金鑰簽章，所以新版 APK 可以直接蓋過舊版安裝，
資料會保留。以下兩種情況必須先解除安裝，而解除安裝會刪除 App 的錄音、記錄與設定：

- 從 v1.6.20 或更早的版本升級（那些是 debug 版，簽章金鑰不同）；
- 要裝回較舊的版本。

解除安裝前，請先把想保留的錄音分享出來存好。詳見
[簽章與備份指南](docs/signing/README.zh-TW.md)。

## 連接電台

開啟**電台**（Rig）分頁，選擇手機連到電台的方式。下表列出各種接法中，音訊走哪裡、
由什麼來觸發發射。

| 你的設備 | 音訊 | 控制與 PTT | 電台分頁的連線模式 |
|---|---|---|---|
| USB 音訊與 CAT 共用一條線的電台 | USB | Hamlib，走同一條線 | [序列埠（本機）](#serial-local) |
| USB 音訊介面，另接一條 CAT 線 | USB | Hamlib，走 CAT 線 | [序列埠（本機）](#serial-local) |
| 以 RTS 觸發電台、沒有 CAT 的介面 | USB | RTS 線 | [序列埠（本機）](#serial-local)，開啟 **RTS PTT** |
| 完全沒有控制線 | USB 或麥克風 | 電台的 VOX，或手動 | — |
| 提供 rigctld 服務的電腦或 SDR 軟體 | Android 音訊裝置 | TCP | [TCP（rigctld）](#tcp-rigctld) |
| 有網路伺服器的 Icom 電台 | 網路 | 網路 | [Wi-Fi（IC-705）](#icom-network) |
| Hermes-Lite 2 | 網路（I/Q） | 網路 | [HL2 (區域網路)](#hl2-lan) |
| 電腦上的 Thetis 與 Voicemeeter | VBAN，走 Wi-Fi | Thetis 的 CAT 伺服器，或 VOX | [Thetis (VBAN)](#thetis-vban) |

連上電台後，分頁會即時顯示頻率、模式與 S 錶，並有一個頻率輸入欄。輸入 kHz 為單位的
頻率後點一下旁邊的**設定**：App 會調整電台頻率，並選好邊帶與數據模式（10 MHz 以下為
LSB／PKTLSB，以上為 USB／PKTUSB；沒有數據模式的電台則用一般的 USB／LSB）。沒有連接
電台時，按**設定**會把這個值記成你的手動頻率，FreeDV Reporter 上顯示的就是它。

App 內建的 CAT 服務程式只接受手機本機（127.0.0.1）的連線，網路上的其他裝置連不到它。

<a id="serial-local"></a>
### 序列埠（本機）— USB CAT 線

App 內含真正的 Hamlib 4.7.2 `rigctld`（arm64），直接在手機上執行，並透過原生的 pty
驅動程式橋接到 USB 線。

1. 接上 CAT 線。支援 CDC-ACM、CP210x、FTDI、Prolific 與 CH340／CH341 晶片；
   Xiegu X6100 這類複合裝置會把每個連接埠分別列出。
2. 選擇**製造商**與**型號**（180 多種 Hamlib 型號，可用名稱或 Hamlib ID 搜尋），設定
   **鮑率**；Icom 電台可另外填 **CI-V** 位址（十六進位；留空即自動）。
3. **DTR／RTS：** DTR 預設開啟，RTS 預設關閉。*如果你的 CAT 線把 RTS 接到 PTT，
   請保持 RTS 關閉*，否則一連線電台就可能進入發射。
4. 點一下**建立連線**，並允許 USB 權限。

**RTS PTT。** 介面以 RTS 線觸發電台、又沒有 CAT 時，請開啟
**RTS PTT（發射時用 RTS 觸發 PTT）**，並讓上方的 RTS 開關保持關閉。按下發射時 App 會
拉起 RTS，等呼號送完、這次發射結束後再放開。

Xiegu 電台（G90、X6100、X6200、X5105、X108G）會自動套用較短的 CI-V 逾時設定，
PTT 才不會遲鈍。

<a id="tcp-rigctld"></a>
### TCP（rigctld）— 網路上的伺服器

讓 App 連到任何相容於 `rigctld` 的 TCP 伺服器（預設連接埠 4532）：執行 `rigctld` 的
電腦，或提供相同通訊協定的 SDR 軟體。這個模式下，音訊仍然走 Android 的音訊裝置。

**Hermes-Lite 2** 設定檔會連到負責控制電台的 SDR 軟體的 CAT 伺服器：**Thetis**
（它的 TCP/IP CAT 伺服器）、**piHPSDR**（它的 RigCtl 連接埠，預設 19090），或
**Spark/Quisk**（SparkSDR 與 Quisk，使用 rigctld 通訊協定）。如果想讓 Hermes-Lite 2
完全不靠電腦，請改用 [HL2 (區域網路)](#hl2-lan)。

<a id="icom-network"></a>
### Wi-Fi（IC-705）— Icom 網路控制

透過電台內建的網路伺服器完全無線操作（Icom 的 RS-BA1 通訊協定，UDP 連接埠 50001 到
50003）。目前用在 IC-705 與 IC-7300MK2。

1. 在電台的網路設定中開啟 **Network Control**，並設定網路使用者名稱與密碼。
2. 讓手機連到同一個網路（或電台自己的無線基地台），輸入電台的 IP 位址、使用者名稱與
   密碼，然後點一下**建立連線**。
3. CAT 控制與收發音訊都走網路，「音訊 (Wi-Fi)」指示會顯示*已連線*。不需要 USB 線，
   也不需要音訊介面。

電台分頁上的選項，下次連線時生效：

- **音訊緩衝（網路延遲）：** 150 ms 是電台的預設值；200 或 300 ms 可以緩和 LTE 或
  VPN 的延遲抖動。
- **發射音訊 16 kHz（實驗性）：** 上傳的資料量只有三分之一。LTE 上傳卡住時值得一試。
- **接收音訊 16 kHz（實驗性）：** 要求電台以 16 kHz 送出接收音訊。如果沒有聲音，
  請關閉後重新連線。

**不在家的時候。** 可以經由 VPN 連回電台所在的網路，用 LTE 操作。電台需要設定預設閘道，
VPN 則必須配給手機一個電台子網路內的位址，或是用 NAT 把手機轉進那個子網路。連線中斷時，
App 會自行重新連線並回到接收。

<a id="hl2-lan"></a>
### HL2 (區域網路) — Hermes-Lite 2，不需要電腦

以 **openHPSDR（protocol 1）** 直接連到區域網路上的 Hermes-Lite 2，手機就是 SDR 主機：

1. 讓 HL2 與手機在同一個區域網路內（用網路線接到路由器或無線基地台）。
2. 在**電台**分頁選 **HL2 (區域網路)**。**主機**留空會以廣播自動探索，也可以輸入電台的
   IP 位址，然後點一下**建立連線**。
3. 頻率與 PTT 都由 App 控制。48 kHz 的 I/Q 串流在 UDP 連接埠 1024 上雙向傳輸，
   SSB 的調變與解調由 App 自己完成。

電台分頁上的控制項：

- **發射驅動**（0–255，預設 128）。設為 0 時電台會進入發射，但沒有輸出。
- **接收 LNA 增益**（−12 到 +48 dB）。
- **PA（5 W 輸出）：** 預設開啟。關閉時改用低功率輸出孔（約 10 mW），天線孔維持在
  接收，適合搭配轉頻器或外接功率放大器。
- **PureSignal（實驗性）：** 自適應預失真，使用和 Thetis 相同的 WDSP 引擎。預設關閉，
  PA 開啟時才有效果。

N2ADR 濾波器板的濾波器會依頻率自動選擇。

注意事項：
- 這個模式**不使用**「音訊裝置」的設定，收發音訊都走網路。
- 連線時出現「Radio is busy」，表示已有其他主機（Thetis、Quisk、SparkSDR 等）正在
  和 HL2 串流。請關掉它再重新連線。

<a id="thetis-vban"></a>
### Thetis (VBAN) — 音訊經 Wi-Fi 傳到電腦

適合由電腦上的 Thetis 控制電台的使用方式。手機的收發音訊以 VBAN 通訊協定，經 Wi-Fi
與電腦上的 [Voicemeeter](https://vb-audio.com/Voicemeeter/) 互傳，而 Voicemeeter 的
虛擬音源線就是 Thetis 的 VAC。Thetis 原本的功能照常運作，包含 PureSignal；手機與電腦
之間不需要音效卡，也不需要接線。

1. 在 Voicemeeter 設定一個**傳出**的 VBAN 串流（48 kHz、PCM 16 位元），目的地是手機的
   IP 位址；發射方向則選擇名為 `RADE-TX` 的**傳入**串流。
2. 在**電台**分頁選 **Thetis (VBAN)**，在**主機**填入電腦的位址，並填寫 **VBAN 連接埠**
   （預設 6980）與 SDR 軟體 CAT 伺服器的 **CAT 連接埠**。CAT 連接埠留空或填 0 表示
   不使用 CAT，改用 VOX。
3. 選擇 SDR 軟體（Thetis、piHPSDR 或 Spark/Quisk），點一下**建立連線**。

電腦端的設定步驟請見 [v1.5.62 發行說明](docs/release-notes/v1.5.62.md)（日文）。

## 接收

1. **接上音訊。** 依建議順序：
   - *USB 音訊：* 用 OTG 轉接頭把電台或音訊介面接到手機，再到
     **設定 → 音訊裝置 → 輸入裝置**選擇它。
   - *網路連線模式：* 在電台分頁以 Wi-Fi（IC-705）、HL2 (區域網路) 或 Thetis (VBAN)
     連線後，接收音訊會經由網路送來。
   - *麥克風：* 把手機靠近電台的喇叭。可以用，但 USB 音訊穩定得多。
2. **調整電台頻率**到有 RADE 活動的地方，例如 20 m 的 FreeDV 呼叫頻率
   **14.236 MHz USB**。**電台列表**分頁與 [qso.freedv.org](https://qso.freedv.org)
   可以看到目前誰在哪個頻率上。
3. 開啟**接收器**分頁，按下**啟動**。
4. 觀察**輸入**電平表。調整電台的輸出或**設定 → 輸入增益 → 數位增益**，讓峰值落在
   **−15 到 −5 dBFS** 左右。
5. 出現 RADE 訊號時，標頭會依序顯示 `搜尋中 → 候選 → 已同步`，語音開始播放。對方這次
   發射結束後，呼號會出現在「最近解碼的呼號」下方，因為 EOO 訊框是在那時送出的。

**類比監聽。** 接收中點一下**類比監聽**，播放的會換成頻道上的原始音訊，而不是解碼後的
語音，讓你在發射前聽出頻率上有沒有類比通聯。同步指示仍會持續更新。再點一下就回到 RADE。

每次取得同步就會開始一段接收記錄。它會列在**記錄**分頁，解碼後的音訊也會存成 WAV 檔，
可以重播或分享。

## 發射

> 發射需要有效的業餘無線電執照。

1. **設定 → 發射呼號：** 輸入你的呼號（最多 8 個字元：英文字母、數字與 `/`）。每次
   發射結束時它會放在 EOO 訊框裡送出，FreeDV Reporter 也需要它。
2. **設定 → 音訊裝置 → 發射輸出裝置：** 選擇接到電台的 USB 音訊裝置。網路連線模式
   可以跳過這一步，發射音訊會走網路。
3. 按下**啟動**，再點一下**發射**並對著麥克風說話。點一下**返回接收**結束這次發射：
   呼號訊框送出後才會放開 PTT。
4. **調整電平。** 目標是讓電台的 *ALC 幾乎不動*：
   - 手機的**音量鍵**（Android 媒體音量）與**設定 → 發射音量 → USB 發射音量**，
     決定送進電台 USB 音訊輸入的推動量；
   - **設定 → 發射麥克風 → 發射麥克風增益**改變的是你聲音的大小，不是射頻推動量；
   - 也請檢查電台本身的 USB 調變電平（IC-7300：
     `Menu → SET → Connectors → USB MOD Level`）；
   - 網路連線模式的推動量在電台上設定，HL2 則用**發射驅動**。
5. **PTT。** 在**電台**分頁連上電台後，App 會替你觸發 PTT。沒有電台控制時，請用
   RTS PTT、電台的 VOX，或手動操作。

**按住發話。** 開啟**設定 → PTT → 按住發話（PTT）**後，發射鍵會像對講機一樣：
按住才發射，放開就回到接收。

**在電台列表與 Map 分頁發射。** 接收啟動時，這兩個分頁會在導覽列上方多出一列，裡面是
同一顆發射鍵。旁邊在接收時顯示同步狀態、訊噪比與你的頻率，發射時顯示發射時間與麥克風
電平。按住發話時在這兩個分頁之間切換，按鍵會維持按住；離開這兩個分頁則會放開 PTT。

## FreeDV Reporter、電台列表與地圖

開啟**設定 → FREEDV REPORTER → Enable Reporter**（預設開啟）並設定呼號後，App 會與
qso.freedv.org 保持即時連線：

- 回報你解碼到的內容（呼號與訊噪比）；同步期間還會持續送出「接收中」的訊號，其他人可以
  即時看到你的電台有反應。
- 回報你的發射狀態與頻率。頻率來自已連接的電台；沒有連接電台時，就是你在電台分頁設定的
  頻率。
- 網格座標可由 GPS 取得（點一下欄位旁的 **GPS**），也可以手動輸入。留空時 App 會回報
  `UN00KN`，你的電台也不會畫在地圖上。
- 可以設定最多 80 個字元的狀態訊息，顯示在你的電台旁邊。
- 沒有設定呼號時 App 不會連線，電台列表分頁會說明原因。

**電台列表**分頁列出所有在線電台的網格座標、頻率，以及它正在做什麼：`TX`，或正在
收什麼（`RX K1ABC 12 dB`）。列表可依波段篩選，並依頻率（預設；同頻率的電台收在同一個
標頭下）、呼號或最近活動排序。**Map** 分頁則把這些電台畫在世界地圖上：

| 標記／線條 | 意義 |
|---|---|
| 🔴 紅色標記 | 電台正在發射 |
| 🟢 綠色標記 | 電台正在接收（顯示頻率與訊噪比） |
| ⚪ 灰色標記 | 待機／監聽中 |
| 紅→綠弧線 | 已確認的路徑：接收端解出了發射端的呼號 |
| 藍色虛線弧線 | 推測的路徑：接收端在相同頻率（±5 kHz）上取得同步 |
| 訊噪比標籤 | 路徑中點上回報的訊噪比，以顏色區分 |

## 接收記錄

**記錄**分頁保存每一段接收。同步中斷約 2 秒後，這一段就會結束。點一下任一段可以看到：

- 開始與結束時間、持續時間、同步率、數據機訊框數，以及使用的音訊裝置
- **播放與分享解碼後的音訊**
- 解出的每一個呼號，以及當時的訊噪比與在這段接收中的位置
- 訊噪比趨勢圖、同步事件時間軸，以及訊號統計（峰值與平均訊噪比、頻率偏移範圍）

錄音存放在 App 的私有儲存空間，並透過 Android 標準的分享選單分享出去。

## 設定總覽

依**設定**分頁的順序排列。各連線模式的選項在電台分頁，說明請見[連接電台](#連接電台)。

| 設定 | 預設值 | 作用 |
|---|---|---|
| 音訊裝置 → 輸入裝置 | 有接 USB 輸入時用它，否則用內建麥克風 | 接收用的收音裝置。**重新整理**會重新掃描。 |
| 音訊裝置 → 發射輸出裝置 | 有接 USB 輸出時用它 | 播放 RADE 發射波形的裝置。 |
| 輸入增益 → 數位增益 | 4.0× | 0.1–30× 的軟體增益，用來補強偏弱的 USB 輸入；讓峰值落在 −15 到 −5 dBFS。只影響接收。 |
| 接收輸出 → 音量 | 100% | 解碼後語音的音量。 |
| 接收輸出 → 接收輸出裝置 | 自動 | *自動*：有接有線音訊時用它，否則用手機喇叭。要從藍牙播放請直接選那個藍牙裝置。*系統預設*：交給 Android 決定。 |
| 接收輸出 → 測試所選接收輸出 | — | 播放 1 kHz 測試音，並顯示 Android 實際把聲音送到哪裡。 |
| 發射音量 → USB 發射音量 | 20% | RADE 波形送進電台 USB 音訊輸入的推動量。 |
| 發射音量 → Android 媒體音量 | — | 走 USB 或喇叭發射時，音量也跟著媒體音量走，可用音量鍵調整。 |
| 發射呼號 → 呼號（EOO） | — | 最多 8 個字元，每次發射都會放在 EOO 訊框裡送出。FreeDV Reporter 也需要它。 |
| PTT → 按住發話（PTT） | 關閉 | 只有按住發射鍵時才發射。 |
| 發射麥克風 | 內建麥克風 | 內建麥克風，或 USB／有線麥克風。 |
| 發射麥克風 → 發射麥克風增益 | 4.0× | 0.5–15× 的增益，作用在送進 RADE 編碼器之前。發射電平表顯示的是套用增益後的值。 |
| 藍牙麥克風（發射） | 關閉 | 實驗性：改由藍牙耳機收發射語音。耳機支援時使用 LE Audio（LC3），否則用傳統的 SCO。 |
| FREEDV REPORTER → Enable Reporter | 開啟 | 與 qso.freedv.org 的即時連線。 |
| FREEDV REPORTER → Grid Square | — | Maidenhead 網格座標。點 **GPS** 會依目前位置填入；留空時回報為 `UN00KN`。 |
| FREEDV REPORTER → 狀態訊息 | — | 顯示在你的電台旁的自訂文字（最多 80 個字元）。點**更新**立即送出。 |
| 效能 → 效能節約模式 | 關閉 | 隱藏頻譜與瀑布圖，**並停止 FFT 運算**，讓效能較低的裝置也能順暢解碼。 |
| RADE 模式 → RADE V2 波形（實驗性） | 關閉 | 把收發都切換到 RADE V2。V1 與 V2 彼此聽不到，呼號也只有 V1 才有。 |
| 診斷 | — | **擷取 CAT 紀錄**與 **Capture audio log** 會收集 App 最近的紀錄，方便複製到問題回報裡。 |

## 疑難排解

- **瀑布圖上看得到訊號，卻一直無法同步。** 先檢查邊帶（10 MHz 以上用 USB，以下用
  LSB），再檢查輸入電平（−15 到 −5 dBFS）。音訊太大或太小是最常見的原因。另外請確認
  RADE V2 是關閉的，除非對方也用 V2。
- **出現橘色警告「裝置拒絕了 Unprocessed 模式」。** 手機堅持對所選的輸入套用語音處理
  （AEC／AGC），可能讓解碼失敗（例如 Samsung Galaxy S24 的內建麥克風）。請改用 USB
  音訊輸入。
- **低階裝置上聲音斷斷續續，或聽起來像機器人。** 開啟**效能節約模式**。除了聲碼器之外，
  畫頻譜與 FFT 運算是最吃 CPU 的部分。
- **標頭顯示已同步，卻聽不到聲音。** 執行**設定 → 接收輸出 → 測試所選接收輸出**，
  可以分辨是聲音送錯地方，還是只是還沒有解碼出語音。接著自己指定輸出裝置。
- **螢幕關閉後解碼就停了。** 電池最佳化把服務停掉了。請使用電池設定對話框（首次啟動時
  會出現）與其中列出的各廠牌步驟。
- **一接上序列埠線，電台就進入發射。** 你的 CAT 線把 RTS 接到了 PTT。請在電台分頁把
  **RTS** 關閉。
- **「未收到 CAT 的 PTT 確認」。** 請看電台。如果已經切換，可以繼續操作，App 會持續
  重試並讀回狀態；如果卡在發射，請按**停止**。
- **「電台仍回報為接收」。** 電台拒絕發射。請在電台上檢查發射禁止、SWR 保護，以及波段
  與模式。
- **「麥克風音訊中斷，已停止發射」。** App 已放開 PTT 並回到接收。請檢查麥克風與它的
  USB 連線。
- **找不到 USB 序列埠裝置。** 確認手機支援 USB-OTG，點一下**重新整理**，並允許 Android
  的 USB 權限對話框。
- **CAT 在舊版可以用，現在不行。** v1.6.36 把內建的 Hamlib 從 4.5.5 換成 4.7.2。
  請擷取 CAT 紀錄，連同電台型號開一個 issue。
- **Icom 網路連不上。** Network Control 必須開啟，使用者名稱與密碼要和電台上的一致，
  手機也必須連得到電台的 IP 位址。經由 VPN 連線時，請看
  [Wi-Fi（IC-705）](#icom-network)一節的路由說明。
- **Icom 走 LTE 時聲音中斷。** 把音訊緩衝設為 200 或 300 ms，並試試
  **發射音訊 16 kHz**。
- **HL2 (區域網路)：「Radio not found」。** 要用廣播探索，手機必須和 Hermes-Lite 2 在
  同一個區域網路與子網路內；否則請在**主機**填入電台的 IP 位址。「Radio is busy」表示
  已有其他 SDR 主機連上。
- **HL2 (區域網路)：繼電器有動作，卻沒有射頻輸出。** 請檢查電台分頁的**發射驅動**滑桿
  與 **PA** 開關。
- **Reporter 顯示「未連線」。** 到設定填入呼號並開啟 **Enable Reporter**。電台列表
  分頁會顯示確切的原因。
- **其他難以判斷的問題。** **設定 → 診斷**可以直接在手機上擷取 App 最近的 CAT 與音訊
  紀錄（不需要 adb），貼到
  [GitHub issue](https://github.com/pepefrog1234/RADE_decode_Android/issues) 即可。

## 權限與網路連線

| 權限 | 用途 |
|---|---|
| 麥克風（`RECORD_AUDIO`） | 從 USB 或麥克風接收音訊；發射用的麥克風 |
| 鄰近裝置（`BLUETOOTH_CONNECT`，Android 12 以上，可不允許） | 藍牙耳機與 LE Audio 的音訊路由 |
| 位置（精確／概略，可不允許） | 計算你的 Maidenhead 網格座標給 Reporter 使用 |
| 網際網路／網路狀態 | FreeDV Reporter、網路連線模式、地圖圖磚 |
| 前景服務（麥克風、媒體播放）、喚醒鎖定 | App 在背景時持續解碼 |
| USB 主機（硬體功能，非必要） | USB 音訊介面與 CAT 線 |

不需要儲存空間權限。錄音存放在 App 的私有儲存空間，要取出時透過分享選單。

App 只會連到 qso.freedv.org（Reporter 已啟用且設定了呼號時）、OpenStreetMap 的圖磚
伺服器（開啟 Map 分頁時），以及你在電台分頁設定的電台或電腦。App 不含任何廣告或分析
程式碼。

## 給開發者

### 架構

```
RX:  USB / 麥克風 / Icom 網路 / VBAN（48 kHz）   HL2：UDP 上的 I/Q（48 kHz）
      → Oboe 擷取或網路封包                        → 複數降取樣 ÷6
      → 多相 FIR 降取樣（→ 8 kHz）                → 解析式 USB 解調（→ 8 kHz）
      → RADE OFDM 解調（+ EOO 呼號解碼）
      → FARGAN 神經聲碼器（→ 16 kHz 語音）
      → Oboe 播放（+ WAV 錄音）

TX:  麥克風（48 kHz）
      → 特徵擷取 → RADE OFDM 調變（8 kHz）
      → 多相升取樣（→ 48 kHz）→ USB 音訊 / Icom 網路 / VBAN
        或 解析濾波 + ×6 升取樣 → 16 位元 I/Q → 經 UDP 送往 HL2
      （PTT 經由 CAT、RTS 或 MOX）
```

| 層級 | 位置 | 說明 |
|---|---|---|
| UI | `ui/` — Jetpack Compose + Material 3 | 接收器、電台、電台列表、Map（osmdroid）、記錄、設定；`PttDock` 是電台列表與 Map 分頁的發射鍵列 |
| ViewModel | `TransceiverViewModel` | 繫結到服務，以 `StateFlow` 提供 UI 狀態 |
| 服務 | `service/AudioService` | 前景服務（麥克風 + 媒體播放類型）：接收記錄的生命週期、快照、錄音 |
| JNI 橋接 | `AudioBridge`、`RADEWrapper` | Oboe 引擎控制；RADE + FARGAN 處理 |
| 原生程式碼 | `app/src/main/cpp/` | `audio_engine.cpp`（Oboe 串流、降取樣、訊框分派）、`rade_jni.cpp`、`radae/`（RADE 數據機，原封不動取自 [rade_c](https://github.com/freedv/rade_c)）、`opus/`（內含 FARGAN 的 Opus）、`eoo/`（呼號編解碼）、`wdsp_ps/`（取自 WDSP 的 PureSignal 引擎） |
| 電台控制 | `network/`、`usb/` | 內建 Hamlib 4.7.2 `rigctld`（arm64）與原生的 USB 序列埠→pty 橋接；Icom RS-BA1、openHPSDR protocol 1（Hermes-Lite 2，SSB 數據機在 App 內）與 VBAN 用戶端以 Kotlin 實作 |
| 資料 | `data/` | 原生 SQLite（不使用 Room）：接收記錄、訊號快照、同步事件、呼號事件 |
| Reporter | `network/FreeDVReporter` | 以 OkHttp 實作 WebSocket 上的 Socket.IO v4 |

### 從原始碼建置

```bash
git clone https://github.com/pepefrog1234/RADE_decode_Android.git
cd RADE_decode_Android
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # 單元測試
```

- Android Studio，搭配 AGP 9.1（Kotlin 2.2.10）與 JDK 21；Android Studio 內附的 JBR
  即可
- NDK 與 CMake 3.22.1 由 Gradle 建置流程自動驅動，不需要另外建置原生程式碼
- 第一次建置原生程式碼時，會透過 CMake FetchContent 下載 Oboe 1.9.0（只需連網一次）。
  Opus 與 RADE 數據機的原始碼都已放在專案內
- 產出**僅支援 arm64-v8a**。請在實機或 arm64 模擬器映像檔上測試
- `assembleRelease` 需要正式簽章金鑰，沒有就會失敗。請見
  [簽章指南](docs/signing/README.zh-TW.md)

### 測試與維護

- `Hamlib*Test` 這幾個測試類別會讓 App 的程式碼對真正的 `rigctld` 執行。除非
  `RADE_TEST_RIGCTLD` 指向內建 Hamlib 的主機版建置，否則它們會被略過；
  [tools/hamlib/README.md](tools/hamlib/README.md) 說明內建的執行檔如何建置與修補。
- `./gradlew connectedDebugAndroidTest` 會在已連接的裝置上執行儀器測試。
- 更新 `radae/` 或 `opus/` 之後，請執行 `tools/radecheck` 裡的主機端迴路檢查。
- [CLAUDE.md](CLAUDE.md) 記錄了內含的數據機與 `rigctld` 的維護注意事項。

## 授權與致謝

本 App 自己的原始碼以 **GNU Lesser General Public License v2.1** 授權（見
[LICENSE](LICENSE)）。APK 裡另外包含取自 WDSP 的 PureSignal 引擎，它的授權是
GPL-2.0-or-later，因此合併後的二進位檔以 GPL 散布。請見
[WDSP 授權聲明](docs/licenses/WDSP-NOTICE.md)。

站在這些專案的肩膀上：

| 專案 | 作者／來源 | 授權 |
|---|---|---|
| [RADE 數據機](https://github.com/freedv/rade_c) | David Rowe 與 FreeDV 專案 | BSD 2-Clause |
| Opus／FARGAN 聲碼器 | Xiph.Org Foundation | BSD 3-Clause |
| EOO 呼號編解碼 | Codec2／FreeDV | LGPL 2.1 |
| [Hamlib](https://hamlib.github.io/)（`rigctld`） | The Hamlib Group | LGPL 2.1+ |
| [WDSP](https://github.com/g0orx/wdsp)（PureSignal） | Warren Pratt, NR0V；Linux 移植版由 John Melton, G0ORX/N6LYT 維護 | GPL 2.0+ |
| [Oboe](https://github.com/google/oboe) | Google | Apache 2.0 |
| kiss_fft | Mark Borgerding | BSD 3-Clause |
| OkHttp | Square | Apache 2.0 |
| osmdroid | osmdroid 貢獻者 | Apache 2.0 |
| [FreeDV Reporter](https://qso.freedv.org) | FreeDV 專案 | — |
| App 圖示設計 | [megabits.xyz](https://megabits.xyz) | — |

本 App 最初是 iOS 版 FreeDV RADE 接收器的 Android 移植版，後來發展成完整的收發機。

> **免責聲明：** 發射需要你所在地區有效的業餘無線電執照。只接收通常不需要執照，
> 但仍請確認當地法規。
