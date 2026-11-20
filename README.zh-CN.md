# RADE_decode — Android 版 FreeDV RADE 数字语音收发信机

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-blue)
[![Release](https://img.shields.io/github/v/release/pepefrog1234/RADE_decode_Android)](https://github.com/pepefrog1234/RADE_decode_Android/releases/latest)
![License](https://img.shields.io/badge/license-LGPL--2.1-orange)

[English](README.md) | [繁體中文](README.zh-TW.md) | **简体中文** | [日本語](README.ja.md)

**RADE_decode** 把 Android 手机或平板变成一台完整的 **FreeDV RADE** 短波（HF）数字语音
电台。它能实时接收**并发射** RADE（Radio Autoencoder，无线电自编码器）波形，全部处理都在
设备本地完成，包括神经网络声码器，并可与电脑上用 FreeDV（freedv-gui 2.x）运行 RADE V1
的电台互相通联。

用一根 USB 线把手机接到 SSB 收发信机就能用，也可以完全不接线：通过 Wi-Fi 连接 Icom
电台内置的网络服务器、直连局域网中的 **Hermes-Lite 2**（手机本身就是 SDR 主机），或者
经由 VBAN 音频连接电脑上的 Thetis。电台控制、FreeDV Reporter 上报、实时电台列表与地图、
接收记录都已内置。

> 本页说明的是 **v1.6.37**。各版本的改动请见[发行说明](docs/release-notes/)。

---

## 什么是 RADE？

[RADE](https://github.com/drowe67/radae)（Radio Autoencoder）是 David Rowe 与
[FreeDV](https://freedv.org) 项目开发的机器学习数字语音模式。它不采用传统的
「语音编码器 + FEC + 调制解调器」架构，而是用神经网络把语音直接映射为 OFDM 符号再还原，
语音则由 Opus 项目的 **FARGAN** 神经声码器合成。音质远超传统的 HF 数字语音模式，
在模拟 SSB 已经很吃力的低信噪比下仍能听清。

本 App 采用 FreeDV 项目的 C 语言实现（[rade_c](https://github.com/freedv/rade_c)），
在手机上原生运行整条信号链：

- **调制解调器：** RADE V1 OFDM，30 个载波，以 8 kHz 处理。FreeDV Reporter 上显示的模式是
  `RADEV1`。
- **声码器：** FARGAN，16 kHz，以 CPU 运算。不需要联网，也不需要 GPU。
- **呼号：** 每次发射（over）结束时，EOO（End-Of-Over）帧会带出电台的呼号。
- **RADE V2（实验性）：** 可在设置中切换到较新的波形。V1 与 V2 的电台彼此听不到，
  V2 也不带呼号。日常使用请保持关闭。

## 功能

**接收**
- 实时解码 48 kHz 音频源的 RADE 信号：电台内置的 USB 声卡、USB 音频接口、下方的网络
  连接模式，或手机的内置麦克风
- 频谱图与瀑布图（带载波标记）、信噪比、频率偏移，以及三段式同步指示
  （`搜索中 → 候选 → 已同步`）
- 解码到的呼号显示在屏幕与通知上
- **模拟监听：** 直接听信道上的原始音频，发射前先确认频率没有人在用
- 在后台持续解码，熄屏或切换到其他 App 都不会中断
- 解码后的语音可通过手机扬声器、有线或 USB 音频，或蓝牙耳机、音箱播放，包括
  **LE Audio（LC3）**；可以指定输出设备并进行测试
- 每一段同步的接收都会录制为 WAV 文件，可在 App 内回放与分享

**发射**
- 麦克风 → RADE 波形 → 电台，呼号放在 EOO 帧里
- 点击**发射**切换收发，或改用**按住发话**。电台列表与 Map 标签页也有发射键，
  可以一边讲话一边看谁收到了你
- 通过 CAT 控制 PTT：App 会读回电台自身的收发状态，电台没收到 key-on 时会重发。
  没有 CAT 的接口可以用 RTS 线触发 PTT，否则就用电台的 VOX
- 内置、USB／有线，或（实验性）蓝牙麦克风，增益可调。发射中麦克风若中断，
  App 会松开 PTT 并回到接收
- 发射音量控制，界面上附有 ALC 的调整提示

**电台连接**（电台标签页）
- **串口（本机）：** USB CAT 线加上内置的 **Hamlib 4.7.2** `rigctld`，支持 180 多种
  电台型号，不需要电脑
- **TCP（rigctld）：** 连接网络上兼容 rigctld 的服务器，另有 **Hermes-Lite 2** 配置，
  可连接 Thetis、piHPSDR、SparkSDR、Quisk 的 CAT 服务器
- **Wi-Fi（IC-705）：** Icom 自家的网络协议。CAT 与音频都走 Wi-Fi，也能经由 VPN
  走 LTE（目前用于 IC-705 与 IC-7300MK2）
- **HL2 (局域网)：** 通过 **openHPSDR（协议 1）** 直连 Hermes-Lite 2，手机就是 SDR
  主机：PA 开关、LNA 增益、自动选择 N2ADR 滤波器，以及实验性的 PureSignal
- **Thetis (VBAN)：** 音频经 Wi-Fi 与电脑上的 Voicemeeter 互传，电台仍由 Thetis
  掌控，PureSignal 也照常工作
- 实时显示频率、模式、PTT 与 S 表；输入频率时自动选好边带与数据模式；没有连接电台时，
  可以手动填写频率供 FreeDV Reporter 使用

**FreeDV Reporter**
- 与 [qso.freedv.org](https://qso.freedv.org) 保持实时连接：上报你的接收（呼号与
  信噪比）、发射状态、频率，以及一段自定义的状态消息
- **电台列表**标签页：列出所有在线电台，可按波段（160 m – VHF+）筛选，并按频率、呼号或
  最近活动排序；每一行显示该电台正在发射，或正在接收什么
- **Map** 标签页：OpenStreetMap 世界地图，标示发射、接收、待机的电台，以及大圆信号路径
  （已解出呼号的路径为红到绿，按相同频率推断的路径为蓝色虚线），每条路径附信噪比标签
- Maidenhead 网格坐标可由 GPS 获取，也可以手动输入

**其他**
- 接收记录：每一段接收都有信噪比趋势图、同步事件时间轴、解出的呼号与信号统计
- **性能节约模式**：供低端设备使用，不绘制频谱图与瀑布图、不做 FFT，把 CPU 留给解码
- 电池优化设置向导，附各厂商的操作步骤（三星、小米、华为、OPPO、vivo、华硕、索尼）
- 可在 App 内抓取 CAT 与音频日志，方便反馈问题
- 界面语言：English、繁體中文、简体中文、日本語

## 系统要求

| | 最低要求 |
|---|---|
| Android | 8.0（API 26）以上 |
| CPU | 64 位 ARM（**仅 arm64-v8a**；没有 x86 或 32 位版本） |
| 性能 | FARGAN 神经声码器以 CPU 运算。中端手机就够用；较旧或性能较弱的设备请开启**设置 → 性能 → 性能节约模式**。 |
| 电台 | 任何 HF SSB 收发信机。内置 USB 声卡与 CAT 的电台（IC-7300、IC-705 以及许多新机型）只需一根线。其他电台需要在手机与电台之间加一个 USB 音频接口或数字接口（DigiRig 之类）。带网络服务器的 Icom 电台与 **Hermes-Lite 2** 完全不需要音频接口。 |
| 配件 | USB 音频与 USB 串口需要 USB-OTG 转接头。网络连接模式不需要任何配件。 |

> 只收听的话不需要连接电台：让声音从麦克风进来（声学耦合），或接一块 USB 声卡，
> 就能直接收听。

## 安装

1. 从[最新的发行版](https://github.com/pepefrog1234/RADE_decode_Android/releases/latest)
   下载 **`app-release.apk`**。
2. Android 询问时，允许从该来源安装，然后安装 APK。
3. 第一次按下**启动**时，请授予麦克风权限。Android 12 及以上还会询问「附近设备」权限，
   蓝牙音频需要它；不授予也能使用。
4. 按照电池优化对话框的指引设置，解码才能在后台持续运行。

本 App 没有上架 Google Play；更新均以 GitHub 发行版的形式提供。

**更新。** v1.6.21 起的每个版本都用同一个密钥签名，所以新版 APK 可以直接覆盖安装，
数据会保留。以下两种情况必须先卸载，而卸载会删除 App 的录音、记录与设置：

- 从 v1.6.20 或更早的版本升级（那些是 debug 版，签名密钥不同）；
- 要装回较旧的版本。

卸载前，请先把想保留的录音分享出来存好。详见
[签名与备份指南（繁體中文）](docs/signing/README.zh-TW.md)。

## 连接电台

打开**电台**（Rig）标签页，选择手机连接电台的方式。下表列出各种接法中，音频走哪里、
由什么来触发发射。

| 你的设备 | 音频 | 控制与 PTT | 电台标签页的连接模式 |
|---|---|---|---|
| USB 音频与 CAT 共用一根线的电台 | USB | Hamlib，走同一根线 | [串口（本机）](#serial-local) |
| USB 音频接口，另接一根 CAT 线 | USB | Hamlib，走 CAT 线 | [串口（本机）](#serial-local) |
| 用 RTS 触发电台、没有 CAT 的接口 | USB | RTS 线 | [串口（本机）](#serial-local)，开启 **RTS PTT** |
| 完全没有控制线 | USB 或麦克风 | 电台的 VOX，或手动 | — |
| 提供 rigctld 服务的电脑或 SDR 软件 | Android 音频设备 | TCP | [TCP（rigctld）](#tcp-rigctld) |
| 带网络服务器的 Icom 电台 | 网络 | 网络 | [Wi-Fi（IC-705）](#icom-network) |
| Hermes-Lite 2 | 网络（I/Q） | 网络 | [HL2 (局域网)](#hl2-lan) |
| 电脑上的 Thetis 与 Voicemeeter | VBAN，走 Wi-Fi | Thetis 的 CAT 服务器，或 VOX | [Thetis (VBAN)](#thetis-vban) |

连接电台后，标签页会实时显示频率、模式与 S 表，并有一个频率输入框。输入以 kHz 为单位的
频率后点击旁边的**设置**：App 会调整电台频率，并选好边带与数据模式（10 MHz 以下为
LSB／PKTLSB，以上为 USB／PKTUSB；没有数据模式的电台则用普通的 USB／LSB）。没有连接
电台时，点**设置**会把这个值记为你的手动频率，FreeDV Reporter 上显示的就是它。

App 内置的 CAT 服务程序只接受手机本机（127.0.0.1）的连接，网络上的其他设备连不到它。

<a id="serial-local"></a>
### 串口（本机）— USB CAT 线

App 内含真正的 Hamlib 4.7.2 `rigctld`（arm64），直接在手机上运行，并通过原生的 pty
驱动桥接到 USB 线。

1. 接上 CAT 线。支持 CDC-ACM、CP210x、FTDI、Prolific 与 CH340／CH341 芯片；
   Xiegu X6100 这类复合设备会把每个端口分别列出。
2. 选择**制造商**与**型号**（180 多种 Hamlib 型号，可按名称或 Hamlib ID 搜索），设置
   **波特率**；Icom 电台可另外填写 **CI-V** 地址（十六进制；留空即自动）。
3. **DTR／RTS：** DTR 默认开启，RTS 默认关闭。*如果你的 CAT 线把 RTS 接到 PTT，
   请保持 RTS 关闭*，否则一连接电台就可能进入发射。
4. 点击**建立连接**，并授予 USB 权限。

**RTS PTT。** 接口用 RTS 线触发电台、又没有 CAT 时，请开启
**RTS PTT（发射时用 RTS 触发 PTT）**，并让上方的 RTS 开关保持关闭。按下发射时 App 会
拉起 RTS，等呼号发完、这次发射结束后再松开。

Xiegu 电台（G90、X6100、X6200、X5105、X108G）会自动采用较短的 CI-V 超时设置，
PTT 才不会迟钝。

<a id="tcp-rigctld"></a>
### TCP（rigctld）— 网络上的服务器

让 App 连接任何兼容 `rigctld` 的 TCP 服务器（默认端口 4532）：运行 `rigctld` 的电脑，
或提供相同协议的 SDR 软件。这个模式下，音频仍然走 Android 的音频设备。

**Hermes-Lite 2** 配置会连接负责控制电台的 SDR 软件的 CAT 服务器：**Thetis**
（它的 TCP/IP CAT 服务器）、**piHPSDR**（它的 RigCtl 端口，默认 19090），或
**Spark/Quisk**（SparkSDR 与 Quisk，使用 rigctld 协议）。如果想让 Hermes-Lite 2
完全不依赖电脑，请改用 [HL2 (局域网)](#hl2-lan)。

<a id="icom-network"></a>
### Wi-Fi（IC-705）— Icom 网络控制

通过电台内置的网络服务器实现完全无线操作（Icom 的 RS-BA1 协议，UDP 端口 50001 到
50003）。目前用于 IC-705 与 IC-7300MK2。

1. 在电台的网络设置中开启 **Network Control**，并设置网络用户名与密码。
2. 让手机连接同一个网络（或电台自己的无线接入点），输入电台的 IP 地址、用户名与密码，
   然后点击**建立连接**。
3. CAT 控制与收发音频都走网络，「音频 (Wi-Fi)」指示会显示*已连接*。不需要 USB 线，
   也不需要音频接口。

电台标签页上的选项，下次连接时生效：

- **音频缓冲（网络延迟）：** 150 ms 是电台的默认值；200 或 300 ms 可以缓解 LTE 或
  VPN 的延迟抖动。
- **发射音频 16 kHz（实验性）：** 上行数据量只有三分之一。LTE 上行卡顿时值得一试。
- **接收音频 16 kHz（实验性）：** 要求电台以 16 kHz 发送接收音频。如果没有声音，
  请关闭后重新连接。

**不在家的时候。** 可以经由 VPN 连回电台所在的网络，用 LTE 操作。电台需要设置默认网关，
VPN 则必须给手机分配一个电台子网内的地址，或者用 NAT 把手机转入那个子网。连接中断时，
App 会自行重新连接并回到接收。

<a id="hl2-lan"></a>
### HL2 (局域网) — Hermes-Lite 2，不需要电脑

通过 **openHPSDR（协议 1）** 直连局域网中的 Hermes-Lite 2，手机就是 SDR 主机：

1. 让 HL2 与手机处于同一个局域网（用网线接到路由器或无线接入点）。
2. 在**电台**标签页选 **HL2 (局域网)**。**主机**留空会通过广播自动发现，也可以输入电台的
   IP 地址，然后点击**建立连接**。
3. 频率与 PTT 都由 App 控制。48 kHz 的 I/Q 流在 UDP 端口 1024 上双向传输，
   SSB 的调制与解调由 App 自己完成。

电台标签页上的控制项：

- **发射驱动**（0–255，默认 128）。设为 0 时电台会进入发射，但没有输出。
- **接收 LNA 增益**（−12 到 +48 dB）。
- **PA（5 W 输出）：** 默认开启。关闭时改用小功率输出口（约 10 mW），天线口保持在
  接收，适合搭配变频器或外接功放。
- **PureSignal（实验性）：** 自适应预失真，使用与 Thetis 相同的 WDSP 引擎。默认关闭，
  PA 开启时才有效果。

N2ADR 滤波器板的滤波器会按频率自动选择。

注意事项：
- 这个模式**不使用**「音频设备」的设置，收发音频都走网络。
- 连接时出现「Radio is busy」，表示已有其他主机（Thetis、Quisk、SparkSDR 等）正在
  与 HL2 传输数据流。请关掉它再重新连接。

<a id="thetis-vban"></a>
### Thetis (VBAN) — 音频经 Wi-Fi 传到电脑

适合由电脑上的 Thetis 控制电台的用法。手机的收发音频通过 VBAN 协议，经 Wi-Fi 与电脑上的
[Voicemeeter](https://vb-audio.com/Voicemeeter/) 互传，而 Voicemeeter 的虚拟音频线
就是 Thetis 的 VAC。Thetis 原有的功能照常工作，包括 PureSignal；手机与电脑之间不需要
声卡，也不需要接线。

1. 在 Voicemeeter 中设置一个**传出**的 VBAN 流（48 kHz、PCM 16 位），目标是手机的
   IP 地址；发射方向则选择名为 `RADE-TX` 的**传入**流。
2. 在**电台**标签页选 **Thetis (VBAN)**，在**主机**填入电脑的地址，并填写 **VBAN 端口**
   （默认 6980）与 SDR 软件 CAT 服务器的 **CAT 端口**。CAT 端口留空或填 0 表示
   不使用 CAT，改用 VOX。
3. 选择 SDR 软件（Thetis、piHPSDR 或 Spark/Quisk），点击**建立连接**。

电脑端的设置步骤请见 [v1.5.62 发行说明](docs/release-notes/v1.5.62.md)（日文）。

## 接收

1. **接好音频。** 推荐顺序：
   - *USB 音频：* 用 OTG 转接头把电台或音频接口连接到手机，再到
     **设置 → 音频设备 → 输入设备**选择它。
   - *网络连接模式：* 在电台标签页用 Wi-Fi（IC-705）、HL2 (局域网) 或 Thetis (VBAN)
     连接后，接收音频会经由网络送来。
   - *麦克风：* 把手机靠近电台的扬声器。可以用，但 USB 音频稳定得多。
2. **把电台调到**有 RADE 活动的频率，例如 20 m 的 FreeDV 呼叫频率
   **14.236 MHz USB**。**电台列表**标签页与 [qso.freedv.org](https://qso.freedv.org)
   可以看到当前谁在哪个频率上。
3. 打开**接收器**标签页，按下**启动**。
4. 观察**输入**电平表。调整电台的输出或**设置 → 输入增益 → 数字增益**，让峰值落在
   **−15 到 −5 dBFS** 左右。
5. 出现 RADE 信号时，标题会依次显示 `搜索中 → 候选 → 已同步`，语音开始播放。对方这次
   发射结束后，呼号会出现在「最近解码的呼号」下方，因为 EOO 帧是在那时发出的。

**模拟监听。** 接收中点击**模拟监听**，播放的会换成信道上的原始音频，而不是解码后的
语音，让你在发射前听出频率上有没有模拟通联。同步指示仍会持续更新。再点一次就回到 RADE。

每次取得同步就会开始一条接收记录。它会列在**记录**标签页，解码后的音频也会保存为
WAV 文件，可以回放或分享。

## 发射

> 发射需要持有有效的业余无线电执照。

1. **设置 → 发射呼号：** 输入你的呼号（最多 8 个字符：英文字母、数字与 `/`）。每次
   发射结束时它会放在 EOO 帧里发出，FreeDV Reporter 也需要它。
2. **设置 → 音频设备 → 发射输出设备：** 选择连接到电台的 USB 音频设备。网络连接模式
   可以跳过这一步，发射音频会走网络。
3. 按下**启动**，再点击**发射**并对着麦克风讲话。点击**返回接收**结束这次发射：
   呼号帧发出后才会松开 PTT。
4. **调整电平。** 目标是让电台的 *ALC 几乎不动*：
   - 手机的**音量键**（Android 媒体音量）与**设置 → 发射音量 → USB 发射音量**，
     决定送入电台 USB 音频输入的驱动量；
   - **设置 → 发射麦克风 → 发射麦克风增益**改变的是你声音的大小，不是射频驱动量；
   - 也请检查电台本身的 USB 调制电平（IC-7300：
     `Menu → SET → Connectors → USB MOD Level`）；
   - 网络连接模式的驱动量在电台上设置，HL2 则用**发射驱动**。
5. **PTT。** 在**电台**标签页连接电台后，App 会替你触发 PTT。没有电台控制时，请用
   RTS PTT、电台的 VOX，或手动操作。

**按住发话。** 开启**设置 → PTT → 按住发话（PTT）**后，发射键会像对讲机一样：
按住才发射，松开就回到接收。

**在电台列表与 Map 标签页发射。** 接收启动时，这两个标签页会在导航栏上方多出一栏，里面是
同一个发射键。旁边在接收时显示同步状态、信噪比与你的频率，发射时显示发射时间与麦克风
电平。按住发话时在这两个标签页之间切换，按键会保持按住；离开这两个标签页则会松开 PTT。

## FreeDV Reporter、电台列表与地图

开启**设置 → FREEDV REPORTER → Enable Reporter**（默认开启）并设置呼号后，App 会与
qso.freedv.org 保持实时连接：

- 上报你解码到的内容（呼号与信噪比）；同步期间还会持续发送「接收中」的信号，其他人可以
  实时看到你的电台有反应。
- 上报你的发射状态与频率。频率来自已连接的电台；没有连接电台时，就是你在电台标签页设置的
  频率。
- 网格坐标可由 GPS 获取（点击输入框旁的 **GPS**），也可以手动输入。留空时 App 会上报
  `UN00KN`，你的电台也不会画在地图上。
- 可以设置最多 80 个字符的状态消息，显示在你的电台旁边。
- 没有设置呼号时 App 不会连接，电台列表标签页会说明原因。

**电台列表**标签页列出所有在线电台的网格坐标、频率，以及它正在做什么：`TX`，或正在
接收什么（`RX K1ABC 12 dB`）。列表可按波段筛选，并按频率（默认；同一频率的电台归在同一个
标题下）、呼号或最近活动排序。**Map** 标签页则把这些电台画在世界地图上：

| 标记／线条 | 含义 |
|---|---|
| 🔴 红色标记 | 电台正在发射 |
| 🟢 绿色标记 | 电台正在接收（显示频率与信噪比） |
| ⚪ 灰色标记 | 待机／监听中 |
| 红→绿弧线 | 已确认的路径：接收端解出了发射端的呼号 |
| 蓝色虚线弧线 | 推断的路径：接收端在相同频率（±5 kHz）上取得同步 |
| 信噪比标签 | 路径中点上报的信噪比，以颜色区分 |

## 接收记录

**记录**标签页保存每一段接收。同步中断约 2 秒后，这一段就会结束。点击任意一段可以看到：

- 开始与结束时间、持续时间、同步率、调制解调器帧数，以及使用的音频设备
- **回放与分享解码后的音频**
- 解出的每一个呼号，以及当时的信噪比与在这段接收中的位置
- 信噪比趋势图、同步事件时间轴，以及信号统计（峰值与平均信噪比、频率偏移范围）

录音保存在 App 的私有存储空间，并通过 Android 标准的分享菜单分享出去。

## 设置一览

按**设置**标签页的顺序排列。各连接模式的选项在电台标签页，说明请见[连接电台](#连接电台)。

| 设置 | 默认值 | 作用 |
|---|---|---|
| 音频设备 → 输入设备 | 接有 USB 输入时用它，否则用内置麦克风 | 接收用的采集设备。**刷新**会重新扫描。 |
| 音频设备 → 发射输出设备 | 接有 USB 输出时用它 | 播放 RADE 发射波形的设备。 |
| 输入增益 → 数字增益 | 4.0× | 0.1–30× 的软件增益，用来提升偏弱的 USB 输入；让峰值落在 −15 到 −5 dBFS。只影响接收。 |
| 接收输出 → 音量 | 100% | 解码后语音的音量。 |
| 接收输出 → 接收输出设备 | 自动 | *自动*：接有有线音频时用它，否则用手机扬声器。要从蓝牙播放请直接选择那个蓝牙设备。*系统默认*：交给 Android 决定。 |
| 接收输出 → 测试所选接收输出 | — | 播放 1 kHz 测试音，并显示 Android 实际把声音送到了哪里。 |
| 发射音量 → USB 发射音量 | 20% | RADE 波形送入电台 USB 音频输入的驱动量。 |
| 发射音量 → Android 媒体音量 | — | 走 USB 或扬声器发射时，音量也跟随媒体音量，可用音量键调节。 |
| 发射呼号 → 呼号（EOO） | — | 最多 8 个字符，每次发射都会放在 EOO 帧里发出。FreeDV Reporter 也需要它。 |
| PTT → 按住发话（PTT） | 关闭 | 只有按住发射键时才发射。 |
| 发射麦克风 | 内置麦克风 | 内置麦克风，或 USB／有线麦克风。 |
| 发射麦克风 → 发射麦克风增益 | 4.0× | 0.5–15× 的增益，作用在送入 RADE 编码器之前。发射电平表显示的是应用增益后的值。 |
| 蓝牙麦克风（发射） | 关闭 | 实验性：改由蓝牙耳机采集发射语音。耳机支持时使用 LE Audio（LC3），否则用传统的 SCO。 |
| FREEDV REPORTER → Enable Reporter | 开启 | 与 qso.freedv.org 的实时连接。 |
| FREEDV REPORTER → Grid Square | — | Maidenhead 网格坐标。点 **GPS** 会按当前位置填入；留空时上报为 `UN00KN`。 |
| FREEDV REPORTER → 状态消息 | — | 显示在你的电台旁的自定义文字（最多 80 个字符）。点**更新**立即发送。 |
| 性能 → 性能节约模式 | 关闭 | 隐藏频谱图与瀑布图，**并停止 FFT 运算**，让性能较弱的设备也能流畅解码。 |
| RADE 模式 → RADE V2 波形（实验性） | 关闭 | 把收发都切换到 RADE V2。V1 与 V2 彼此听不到，呼号也只有 V1 才有。 |
| 诊断 | — | **抓取 CAT 日志**与 **Capture audio log** 会收集 App 最近的日志，方便复制到问题反馈里。 |

## 故障排除

- **瀑布图上看得到信号，却一直无法同步。** 先检查边带（10 MHz 以上用 USB，以下用
  LSB），再检查输入电平（−15 到 −5 dBFS）。音频太大或太小是最常见的原因。另外请确认
  RADE V2 是关闭的，除非对方也用 V2。
- **出现橙色警告「设备拒绝了 Unprocessed 模式」。** 手机坚持对所选的输入施加语音处理
  （AEC／AGC），可能导致解码失败（例如三星 Galaxy S24 的内置麦克风）。请改用 USB
  音频输入。
- **低端设备上声音断断续续，或听起来像机器人。** 开启**性能节约模式**。除了声码器之外，
  绘制频谱与 FFT 运算是最占 CPU 的部分。
- **标题显示已同步，却听不到声音。** 运行**设置 → 接收输出 → 测试所选接收输出**，
  可以分辨是声音送错了地方，还是只是还没有解码出语音。然后自己指定输出设备。
- **熄屏后解码就停了。** 电池优化把服务停掉了。请使用电池设置对话框（首次启动时会出现）
  与其中列出的各厂商步骤。
- **一接上串口线，电台就进入发射。** 你的 CAT 线把 RTS 接到了 PTT。请在电台标签页把
  **RTS** 关闭。
- **「未收到 CAT 的 PTT 确认」。** 请查看电台。如果已经切换，可以继续操作，App 会持续
  重试并读回状态；如果卡在发射，请按**停止**。
- **「电台仍报告为接收」。** 电台拒绝发射。请在电台上检查发射禁止、SWR 保护，以及波段
  与模式。
- **「麦克风音频中断，已停止发射」。** App 已松开 PTT 并回到接收。请检查麦克风与它的
  USB 连接。
- **找不到 USB 串口设备。** 确认手机支持 USB-OTG，点击**刷新**，并同意 Android 的
  USB 权限对话框。
- **CAT 在旧版本可以用，现在不行。** v1.6.36 把内置的 Hamlib 从 4.5.5 换成了 4.7.2。
  请抓取 CAT 日志，连同电台型号提交一个 issue。
- **Icom 网络连不上。** Network Control 必须开启，用户名与密码要与电台上的一致，
  手机也必须能访问电台的 IP 地址。经由 VPN 连接时，请看
  [Wi-Fi（IC-705）](#icom-network)一节的路由说明。
- **Icom 走 LTE 时声音中断。** 把音频缓冲设为 200 或 300 ms，并试试
  **发射音频 16 kHz**。
- **HL2 (局域网)：「Radio not found」。** 要用广播发现，手机必须与 Hermes-Lite 2 处于
  同一个局域网与子网；否则请在**主机**填入电台的 IP 地址。「Radio is busy」表示
  已有其他 SDR 主机连接。
- **HL2 (局域网)：继电器有动作，却没有射频输出。** 请检查电台标签页的**发射驱动**滑块
  与 **PA** 开关。
- **Reporter 显示「未连接」。** 到设置填入呼号并开启 **Enable Reporter**。电台列表
  标签页会显示确切的原因。
- **其他难以判断的问题。** **设置 → 诊断**可以直接在手机上抓取 App 最近的 CAT 与音频
  日志（不需要 adb），粘贴到
  [GitHub issue](https://github.com/pepefrog1234/RADE_decode_Android/issues) 即可。

## 权限与网络连接

| 权限 | 用途 |
|---|---|
| 麦克风（`RECORD_AUDIO`） | 从 USB 或麦克风接收音频；发射用的麦克风 |
| 附近设备（`BLUETOOTH_CONNECT`，Android 12 及以上，可不授予） | 蓝牙耳机与 LE Audio 的音频路由 |
| 位置（精确／粗略，可不授予） | 计算你的 Maidenhead 网格坐标供 Reporter 使用 |
| 互联网／网络状态 | FreeDV Reporter、网络连接模式、地图瓦片 |
| 前台服务（麦克风、媒体播放）、唤醒锁 | App 在后台时持续解码 |
| USB 主机（硬件功能，非必需） | USB 音频接口与 CAT 线 |

不需要存储权限。录音保存在 App 的私有存储空间，要取出时通过分享菜单。

App 只会连接 qso.freedv.org（Reporter 已启用且设置了呼号时）、OpenStreetMap 的瓦片
服务器（打开 Map 标签页时），以及你在电台标签页设置的电台或电脑。App 不含任何广告或分析
代码。

## 开发者信息

### 架构

```
RX:  USB / 麦克风 / Icom 网络 / VBAN（48 kHz）   HL2：UDP 上的 I/Q（48 kHz）
      → Oboe 采集或网络数据包                      → 复数抽取 ÷6
      → 多相 FIR 抽取（→ 8 kHz）                  → 解析式 USB 解调（→ 8 kHz）
      → RADE OFDM 解调（+ EOO 呼号解码）
      → FARGAN 神经声码器（→ 16 kHz 语音）
      → Oboe 播放（+ WAV 录音）

TX:  麦克风（48 kHz）
      → 特征提取 → RADE OFDM 调制（8 kHz）
      → 多相插值（→ 48 kHz）→ USB 音频 / Icom 网络 / VBAN
        或 解析滤波 + ×6 插值 → 16 位 I/Q → 经 UDP 发往 HL2
      （PTT 通过 CAT、RTS 或 MOX）
```

| 层级 | 位置 | 说明 |
|---|---|---|
| UI | `ui/` — Jetpack Compose + Material 3 | 接收器、电台、电台列表、Map（osmdroid）、记录、设置；`PttDock` 是电台列表与 Map 标签页的发射键栏 |
| ViewModel | `TransceiverViewModel` | 绑定到服务，以 `StateFlow` 提供 UI 状态 |
| 服务 | `service/AudioService` | 前台服务（麦克风 + 媒体播放类型）：接收记录的生命周期、快照、录音 |
| JNI 桥接 | `AudioBridge`、`RADEWrapper` | Oboe 引擎控制；RADE + FARGAN 处理 |
| 原生代码 | `app/src/main/cpp/` | `audio_engine.cpp`（Oboe 流、抽取、帧分发）、`rade_jni.cpp`、`radae/`（RADE 调制解调器，原样取自 [rade_c](https://github.com/freedv/rade_c)）、`opus/`（含 FARGAN 的 Opus）、`eoo/`（呼号编解码）、`wdsp_ps/`（取自 WDSP 的 PureSignal 引擎） |
| 电台控制 | `network/`、`usb/` | 内置 Hamlib 4.7.2 `rigctld`（arm64）与原生的 USB 串口→pty 桥接；Icom RS-BA1、openHPSDR 协议 1（Hermes-Lite 2，SSB 调制解调在 App 内）与 VBAN 客户端以 Kotlin 实现 |
| 数据 | `data/` | 原生 SQLite（不使用 Room）：接收记录、信号快照、同步事件、呼号事件 |
| Reporter | `network/FreeDVReporter` | 以 OkHttp 实现 WebSocket 上的 Socket.IO v4 |

### 从源代码构建

```bash
git clone https://github.com/pepefrog1234/RADE_decode_Android.git
cd RADE_decode_Android
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # 单元测试
```

- Android Studio，搭配 AGP 9.1（Kotlin 2.2.10）与 JDK 21；Android Studio 自带的 JBR
  即可
- NDK 与 CMake 3.22.1 由 Gradle 构建流程自动驱动，不需要单独构建原生代码
- 第一次构建原生代码时，会通过 CMake FetchContent 下载 Oboe 1.9.0（只需联网一次）。
  Opus 与 RADE 调制解调器的源代码都已放在仓库内
- 产物**仅支持 arm64-v8a**。请在真机或 arm64 模拟器镜像上测试
- `assembleRelease` 需要正式签名密钥，没有就会失败。请见
  [签名指南（繁體中文）](docs/signing/README.zh-TW.md)

### 测试与维护

- `Hamlib*Test` 这几个测试类会让 App 的代码对真正的 `rigctld` 运行。除非
  `RADE_TEST_RIGCTLD` 指向内置 Hamlib 的主机版构建，否则它们会被跳过；
  [tools/hamlib/README.md](tools/hamlib/README.md) 说明内置的可执行文件如何构建与打补丁。
- `./gradlew connectedDebugAndroidTest` 会在已连接的设备上运行仪器化测试。
- 更新 `radae/` 或 `opus/` 之后，请运行 `tools/radecheck` 里的主机端环回检查。
- [CLAUDE.md](CLAUDE.md) 记录了内含的调制解调器与 `rigctld` 的维护注意事项。

## 许可与致谢

本 App 自身的源代码以 **GNU Lesser General Public License v2.1** 许可（见
[LICENSE](LICENSE)）。APK 里另外包含取自 WDSP 的 PureSignal 引擎，它的许可是
GPL-2.0-or-later，因此合并后的二进制文件以 GPL 分发。请见
[WDSP 许可声明](docs/licenses/WDSP-NOTICE.md)。

站在这些项目的肩膀上：

| 项目 | 作者／来源 | 许可 |
|---|---|---|
| [RADE 调制解调器](https://github.com/freedv/rade_c) | David Rowe 与 FreeDV 项目 | BSD 2-Clause |
| Opus／FARGAN 声码器 | Xiph.Org Foundation | BSD 3-Clause |
| EOO 呼号编解码 | Codec2／FreeDV | LGPL 2.1 |
| [Hamlib](https://hamlib.github.io/)（`rigctld`） | The Hamlib Group | LGPL 2.1+ |
| [WDSP](https://github.com/g0orx/wdsp)（PureSignal） | Warren Pratt, NR0V；Linux 移植版由 John Melton, G0ORX/N6LYT 维护 | GPL 2.0+ |
| [Oboe](https://github.com/google/oboe) | Google | Apache 2.0 |
| kiss_fft | Mark Borgerding | BSD 3-Clause |
| OkHttp | Square | Apache 2.0 |
| osmdroid | osmdroid 贡献者 | Apache 2.0 |
| [FreeDV Reporter](https://qso.freedv.org) | FreeDV 项目 | — |
| App 图标设计 | [megabits.xyz](https://megabits.xyz) | — |

本 App 最初是 iOS 版 FreeDV RADE 接收器的 Android 移植版，后来发展为完整的收发信机。

> **免责声明：** 发射需要持有你所在地区有效的业余无线电执照。只接收通常不需要执照，
> 但仍请确认当地法规。
