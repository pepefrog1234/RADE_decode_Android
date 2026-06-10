# RADE_decode — Android 版 FreeDV RADE 数字语音收发信机

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-blue)
![License](https://img.shields.io/badge/license-LGPL--2.1-orange)

[English](README.md) | [繁體中文](README.zh-TW.md) | **简体中文** | [日本語](README.ja.md)

**RADE_decode** 把 Android 手机变成一台完整的 **FreeDV RADE** 短波（HF）数字语音
收发信机。它能实时接收**并发射** RADE（Radio Autoencoder，无线电自编码器）波形，
全部处理（包括神经网络声码器）都在设备本地完成，并可与 PC 上运行
FreeDV（freedv-gui 2.x）RADE V1 模式的电台互相通联。

只需用 USB 音频把手机连接到 HF SSB 电台，或搭配 Icom IC-705 通过 Wi-Fi 实现完全
无线连接，你就拥有了一台口袋里的 RADE 电台——内置 CAT 电台控制、FreeDV Reporter
上报、实时电台地图与自动接收记录。

---

## 什么是 RADE？

[RADE](https://github.com/drowe67/radae)（Radio Autoencoder）是 David Rowe 与
[FreeDV](https://freedv.org) 项目开发的机器学习数字语音模式。它不采用传统的
「语音编码器 + FEC + 调制解调器」架构，而是用神经网络把语音直接映射为 OFDM 符号
再还原，语音由 Opus 项目的 **FARGAN** 神经声码器合成。最终效果是远超传统 HF
数字语音模式的音质，在模拟 SSB 已经很吃力的低信噪比下仍能清晰通联。

本 App 在手机上原生运行完整的 RADE V1 信号链：

- **调制解调器：** RADE V1 OFDM、30 个载波、以 8 kHz 处理（向 FreeDV Reporter
  上报的模式代号为 `RADEV1`）
- **声码器：** FARGAN @ 16 kHz，纯 CPU 运算——不需要联网，也不需要 GPU
- **呼号：** EOO（End-Of-Over，发言段结尾）帧在每段发言（over）结束时编码／解码呼号

## 功能特色

**接收**
- 实时解码任何 48 kHz 音频源的 RADE 信号（USB 声卡／音频接口、电台内置 USB
  声卡、或手机麦克风）
- 实时频谱图与瀑布图（带载波标记）、信噪比（SNR）、频率偏移，以及三段式同步指示
  （搜索中 → 候选 → 已同步）
- 解码到的呼号显示在屏幕与通知栏上
- 通过前台服务在后台持续解码——熄屏或切换到其他 App 都不会中断
- 每次同步的接收都自动录制为 WAV（解码后语音），可在 App 内回放与分享

**发射**
- 完整发射功能：麦克风 → RADE OFDM 波形 → 电台，呼号自动嵌入 EOO 帧
- 通联中一键切换收发；已连接电台时自动通过 CAT 控制 PTT
- 发射驱动电平可调，界面内置 ALC 调整指引

**电台控制（CAT）**
- 内置 **Hamlib 4.5.5 `rigctld`**（arm64 原生可执行文件）——支持 340+ 种电台型号，
  不需要电脑
- 三种连接模式：
  - **串口（本机）：** USB CAT 线（OTG）——支持 CDC-ACM、CP210x、FTDI、Prolific、
    CH340/CH341 芯片，可控制 DTR/RTS
  - **TCP（rigctld）：** 连接到任何兼容 rigctld 的远程服务器，并提供
    **Hermes-Lite 2** 配置（Thetis / Quisk / SparkSDR / piHPSDR）
  - **Wi-Fi（IC-705）：** Icom 原生网络协议（RS-BA1）——CAT 控制**与**收发音频
    都走 Wi-Fi，完全不需要接线
- 实时显示频率／模式／PTT／S 表；输入频率时自动选择正确的边带与数据模式
  （USB/LSB、PKTUSB/PKTLSB）

**FreeDV Reporter 集成**
- 与 [qso.freedv.org](https://qso.freedv.org) 双向连接：你的接收上报（呼号 + SNR）、
  发射状态、频率与自定义状态消息实时上传
- **电台列表**标签页：列出当前所有在线电台，可按波段（160 m – VHF+）筛选
- **地图**标签页：OpenStreetMap 世界地图，标示发射／接收／待机电台、大圆传播路径
  （实线 = 通过 EOO 确认呼号、虚线 = 由相同频率推断）以及每条路径的 SNR 标签
- GPS 自动换算 Maidenhead 网格坐标并自动更新

**贴心设计**
- 接收记录：每次接收都存入本地数据库，含 SNR 时间曲线图、同步事件时间轴、
  解码呼号列表与信号统计
- **性能节约模式**：供低端设备使用（停止 FFT 与频谱绘制，把 CPU 留给解码器）
- 电池优化设置助手，附各厂商专属步骤（三星、小米、华为、OPPO、vivo…）
- 界面支持 English、繁體中文、简体中文、日本語

## 系统要求

| | 最低要求 |
|---|---|
| Android | 8.0（API 26）以上 |
| CPU | 64 位 ARM（**仅支持 arm64-v8a**——不支持 x86 或 32 位） |
| 性能 | FARGAN 神经声码器以 CPU 运算；2018 年之后的中端手机即可。性能较弱的设备请开启 **设置 → 性能节约模式**。 |
| 电台 | 任何 HF SSB 收发信机。想一根 USB 线搞定，选择内置 USB 声卡 + CAT 的机型（如 IC-7300、IC-705 等）；否则需在手机与电台之间加一个 USB 声卡／数字接口（DigiRig 等）。 |
| 配件 | USB-OTG 转接头（USB 音频／USB 串口用）。IC-705 Wi-Fi 模式什么都不需要。 |

> 纯收听不需要连接电台：用麦克风靠近扬声器（声学耦合）或接 USB 声卡即可。

## 安装

1. 到 [**Releases**](https://github.com/pepefrog1234/RADE_decode_Android/releases)
   页面下载最新 APK。
2. 按提示允许「安装未知来源应用」后安装。
3. 首次启动时授予麦克风权限，并按照电池优化对话框的指引设置，后台解码才不会被
   系统中断。

App 未上架应用商店，更新均以 GitHub Release 发布。

## 快速上手 — 接收

1. **接好音频。** 推荐顺序：
   - *USB 音频：* 用 OTG 转接头把电台（或声卡）连接到手机，
     到 **设置 → 音频设备 → 输入设备** 选择该设备。
   - *IC-705 Wi-Fi：* 见下方 [Wi-Fi（IC-705）](#wi-fiic-705)——音频直接走网络，免接线。
   - *麦克风：* 手机靠近电台扬声器也可以，但 USB 音频稳定得多。
2. **电台调到** RADE 活动频率，例如 20 m 波段 FreeDV 呼叫频率 **14.236 MHz USB**
   附近。**电台列表**标签页与 [qso.freedv.org](https://qso.freedv.org) 可实时查看
   谁在哪个频率上。
3. 打开**接收器**标签页，按下**启动（START）**。
4. 观察 **INPUT** 电平表：调整电台输出或 **设置 → 输入增益**，让峰值落在
   **−15 ～ −5 dBFS**。
5. 收到 RADE 信号时，标题会依次显示「搜索中 → 已同步」，随后开始播放语音；
   对方一段发言（over）结束时会发送 EOO 帧，此时呼号就会显示出来。

只要取得同步就会自动创建一条接收记录，写入**记录**标签页，
解码语音也会保存为 WAV 供回放或分享。

## 快速上手 — 发射

> 发射需持有有效的业余无线电执照。

1. **设置 → 发射呼号：** 输入你的呼号（最多 8 个字符）——每段发言都会编入 EOO 帧。
2. **设置 → 音频设备 → 输出设备：** 选择连接到电台的 USB 音频设备
   （IC-705 Wi-Fi 模式无需设置，发射音频走网络）。
3. 按**启动（START）**，再点**发射（TX）**，对着手机麦克风讲话。点
   **返回接收（BACK TO RX）** 结束发言（此时会同时发送 EOO 呼号帧）。
4. **调整电平**——目标是电台的 *ALC 几乎不摆动*：
   - 手机**音量键**与 **设置 → 接收输出 → 音量** 控制整体输出，
   - **设置 → 发射音量 → USB 发射音量** 控制送入电台 USB 音频输入的驱动量，
   - 也要检查电台本身的 USB MOD 电平（IC-7300：`Menu → SET → Connectors →
     USB MOD Level`）。
5. 若已在**电台**标签页连接电台，PTT 会自动键控；否则请使用电台的 VOX 或手动
   按 PTT。

## 电台控制（CAT）

打开**电台**标签页，从三种连接模式中选择一种。连接后即可看到实时频率、模式、
S 表，并可直接输入频率——输入频率时会自动选择正确的边带与数据模式
（10 MHz 以下 LSB/PKTLSB、以上 USB/PKTUSB；不支持数据模式的机型自动回退到
普通 USB/LSB）。

### 串口（本机）— USB CAT 线

App 内置货真价实的 Hamlib 4.5.5 `rigctld`（arm64），直接在手机上运行，
通过原生 pty 驱动桥接 USB 线。

1. 插上 CAT 线（支持 CDC-ACM、CP210x、FTDI、Prolific、CH340/CH341 芯片；
   像协谷 X6100 这种复合设备会把每个端口分开列出）。
2. 选择**制造商／型号**（340+ 种 Hamlib 型号，可按名称或 Hamlib ID 搜索）、
   设置**波特率**，Icom 机型可选填 **CI-V 地址**（十六进制，通常 auto 即可）。
3. **DTR/RTS：** DTR 默认开、RTS 默认关——*如果你的 CAT 线把 RTS 接到了 PTT，
   请保持 RTS 关闭*，否则一连接电台就会进入发射状态。
4. 点**建立连接（CONNECT）**并允许 USB 权限。

协谷机型（G90、X6100、X5105、X108G）会自动应用更短的 CI-V 超时设置，
确保 PTT 响应灵敏。

### TCP（rigctld）— 远程服务器

连接到任何兼容 `rigctld` 的 TCP 服务器（默认端口 4532）：运行 `rigctld` 的电脑、
FLRig 或 SDR 软件。选择 **Hermes-Lite 2** 配置可连接 Thetis、Quisk、SparkSDR、
piHPSDR 的 rigctl 服务器——注意音频仍走 Android 音频设备（不支持 OpenHPSDR
I/Q 流）。

### Wi-Fi（IC-705）

利用 IC-705 内置网络服务器（Icom RS-BA1 协议，UDP 端口 50001/50002/50003）
实现完全无线操作：

1. 电台端：`Set ▸ WLAN ▸ Network Control = ON`，并设置 Network 用户名／密码。
2. 手机连接到同一网络（或电台的 AP 模式），输入电台 IP、用户名与密码，
   点**建立连接（CONNECT）**。
3. CAT 控制**与** 48 kHz 收发音频都走 Wi-Fi——「Audio (Wi-Fi)」指示灯变绿即成功。
   不需要 USB 线，也不需要声卡。

## FreeDV Reporter、电台列表与地图

开启 **设置 → FreeDV Reporter** 并设置呼号后，App 会与 qso.freedv.org 保持连接：

- 你解码到的一切都会上传通报（呼号、SNR、频率），同步期间还会发送「接收中」的
  实时心跳——其他电台能实时看到你的点亮起来。
- 你的发射状态与电台频率（CAT 已连接时）也会上报，还可以设置 80 字符以内的
  自定义状态消息。
- 未设置呼号则以只读（view）模式连接——同样能看到所有人。

**电台列表**标签页列出所有在线电台（网格、频率、发射指示），可按波段筛选。
**地图**标签页把它们画在世界地图上：

| 标记／线条 | 含义 |
|---|---|
| 🔴 红色标记 | 发射中的电台 |
| 🟢 绿色标记 | 接收中的电台（显示频率 + SNR） |
| ⚪ 灰色标记 | 待机／守听 |
| 红→绿实线弧线 | 已确认路径——接收端解码出发射端的 EOO 呼号 |
| 蓝色虚线弧线 | 推断路径——接收端在相同频率（±5 kHz）上同步 |
| SNR 标签 | 路径中点显示上报 SNR，按数值着色 |

## 接收记录

**记录**标签页保存每一次接收（失去同步超过约 2 秒会自动拆分为新记录）。
点开一条记录可看到：

- 起止时间、时长、同步率、调制解调器帧统计、使用的音频设备
- **解码语音 WAV 的回放与分享**
- 解码到的所有呼号，含解码当时的 SNR 与帧位置
- SNR 时间曲线图（带同步状态底色）、同步事件时间轴、信号统计
  （峰值／平均 SNR、频偏范围）

录音保存在 App 私有空间，通过 Android 系统分享面板导出。

## 设置总览

| 设置 | 默认 | 作用 |
|---|---|---|
| 音频设备 → 输入／输出设备 | 第一个 USB 设备 | 选择采集与播放设备；「刷新」重新扫描 |
| 输入增益 → 数字增益 | 4.0×（12 dB） | 0.1–30× 软件增益，补偿过小的 USB 输入；目标峰值 −15…−5 dBFS |
| 接收输出 → 音量 | 100 % | 解码语音音量；同时影响发射输出 |
| 发射音量 → USB 发射音量 | 20 % | RADE 波形送入电台 USB 音频输入的驱动量 |
| 发射呼号 → 呼号（EOO） | — | 最多 8 个字符，每段发言结束时随 EOO 帧发送 |
| FreeDV Reporter → 启用 Reporter | 开 | 与 qso.freedv.org 保持连接 |
| FreeDV Reporter → 网格坐标 | 由 GPS 获取 | 6 字符 Maidenhead 网格；有定位权限时自动填入 |
| FreeDV Reporter → 状态消息 | — | 显示在 qso.freedv.org 你电台旁的自由文本（≤ 80 字符） |
| 性能 → 性能节约模式 | 关 | 隐藏频谱／瀑布图**并停止 FFT 运算**，让低端设备流畅解码 |

## 故障排除

- **瀑布图能看到信号却一直不同步** —— 先检查边带（10 MHz 以上 USB、以下 LSB），
  再检查输入电平（−15…−5 dBFS）。电平过大或过小是最常见的原因。
- **橙色警告「device rejected Unprocessed preset」** —— 手机强制对所选输入应用
  语音处理（回声消除／AGC），可能破坏解码（例如三星 Galaxy S24 的内置麦克风）。
  请改用 USB 音频输入。
- **低端设备声音断续、有机械音** —— 开启**性能节约模式**；除声码器外，
  频谱绘制与 FFT 是最大的 CPU 负担。
- **熄屏后解码停止** —— 电池优化把服务杀掉了。请按照首次启动显示的电池设置
  对话框（含各厂商专属步骤）处理。
- **串口线一连接电台就进入发射** —— 你的 CAT 线把 RTS 接到了 PTT。
  到电台标签页把 **RTS 关闭**。
- **找不到 USB 串口设备** —— 确认手机支持 USB-OTG、点**刷新**重新扫描，
  并允许 Android 的 USB 权限对话框。
- **IC-705 Wi-Fi 连不上** —— Network Control 必须为 ON，用户名密码要与电台
  Network User 设置一致，手机也要能访问电台 IP（同一网络或电台 AP 模式）。
- **Reporter 显示「未连接」** —— 到设置填入呼号并开启 **启用 Reporter**；
  电台列表标签页会显示具体原因。

## 权限说明

| 权限 | 用途 |
|---|---|
| 麦克风（`RECORD_AUDIO`） | 采集 USB／麦克风的接收音频；发射时的麦克风 |
| 互联网／网络状态 | FreeDV Reporter（qso.freedv.org）、IC-705 Wi-Fi、TCP rigctld |
| 定位（精确／粗略，可不授予） | 自动计算 Maidenhead 网格供 Reporter 使用 |
| 前台服务（麦克风、媒体播放） | 后台持续解码 |
| USB host（硬件特性，可选） | USB 声卡与 CAT 线 |

不需要存储权限——录音保存于 App 私有空间，经分享面板导出。

## 架构（开发者）

```
RX：USB／麦克风／IC-705 Wi-Fi（48 kHz）
     → Oboe 采集
     → 多相 FIR 降采样（→ 8 kHz）
     → RADE V1 OFDM 解调（+ EOO 呼号解码）
     → FARGAN 神经声码器（→ 16 kHz 语音）
     → Oboe 播放（+ WAV 录音）

TX：麦克风（48 kHz）
     → 特征提取 → RADE OFDM 调制（8 kHz）
     → 多相插值（→ 48 kHz）
     → USB 音频／IC-705 Wi-Fi → 电台（CAT 控制 PTT）
```

| 层级 | 位置 | 说明 |
|---|---|---|
| UI | `ui/` — Jetpack Compose + Material 3 | 接收器、电台、电台列表、地图（osmdroid）、记录、设置 |
| ViewModel | `TransceiverViewModel` | 绑定服务，对外提供 `StateFlow<ServiceState>` |
| 服务 | `service/AudioService` | 前台服务（麦克风 + 媒体播放类型）；接收记录生命周期、信号快照、录音 |
| JNI 桥接 | `AudioBridge`、`RADEWrapper` | Oboe 引擎控制／RADE + FARGAN 处理 |
| 原生层 | `app/src/main/cpp/` | `audio_engine.cpp`（Oboe 流、降采样、帧分发）、`rade_jni.cpp`、`radae/`（RADE 调制解调器，unity build）、`opus/`（FARGAN）、`eoo/`（呼号编解码） |
| 电台控制 | `network/`、`usb/` | 内置 Hamlib 4.5.5 `rigctld`（arm64）+ 原生 USB 串口→pty 桥接；Kotlin 实现的 Icom RS-BA1 UDP 客户端 |
| 数据 | `data/` | 原生 SQLite（不用 Room）：接收记录、信号快照、同步事件、呼号事件 |
| Reporter | `network/FreeDVReporter` | 基于 OkHttp 的 Socket.IO v4 over WebSocket |

### 从源码构建

```bash
git clone https://github.com/pepefrog1234/RADE_decode_Android.git
cd RADE_decode_Android
./gradlew assembleDebug          # 构建 debug APK
./gradlew testDebugUnitTest      # 运行单元测试
```

- Android Studio（AGP 9.1／Kotlin 2.2.10）、JDK 17+
- NDK 与 CMake 3.22.1 由 Gradle 自动驱动——不需要单独构建原生代码
- 首次原生构建会通过 CMake FetchContent 下载 Opus 1.9.0 与 Oboe 1.9.0
  （首次需要联网）
- 产物**仅含 arm64-v8a**——请用真机或 arm64 模拟器镜像测试

## 许可证与致谢

本应用程序基于 **GNU Lesser General Public License v2.1** 许可证发布
（见 [LICENSE](LICENSE)）。

站在巨人的肩膀上：

| 项目 | 作者／来源 | 许可证 |
|---|---|---|
| [RADE modem](https://github.com/drowe67/radae) | David Rowe 与 FreeDV 项目 | BSD 2-Clause |
| Opus / FARGAN 声码器 | Xiph.Org Foundation | BSD 3-Clause |
| EOO 呼号编解码 | Codec2 / FreeDV | LGPL 2.1 |
| [Hamlib](https://hamlib.github.io/)（`rigctld`） | The Hamlib Group | LGPL 2.1+ |
| [Oboe](https://github.com/google/oboe) | Google | Apache 2.0 |
| kiss_fft | Mark Borgerding | BSD 3-Clause |
| OkHttp | Square | Apache 2.0 |
| osmdroid | osmdroid 贡献者 | Apache 2.0 |
| [FreeDV Reporter](https://qso.freedv.org) | FreeDV 项目 | — |

本 App 始于 iOS 版 FreeDV RADE 接收器的 Android 移植，现已发展为完整的收发信机。

> **声明：** 发射需持有所在地有效的业余无线电执照。纯接收通常无需执照，
> 但请确认当地法规。
