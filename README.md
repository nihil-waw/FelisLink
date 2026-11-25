# FelisLink

**把 HDMI → USB 采集卡，变成手机的第二块屏幕。**

插上一块免驱 UVC 采集卡，手机横过来就是低延迟副屏：游戏机、相机、另一台电脑的 HDMI 输出，都能直接显示在手机上。

FelisLink 基于 Meta 开源的 [facebookexperimental/usb-video](https://github.com/facebookexperimental/usb-video)（Apache-2.0）二次开发：采集引擎原样保留（libusb + libuvc + libyuv 在原生层直出 Surface），在它之上补齐了手机上真正用得着的界面、设置与中文支持。

> **English** — FelisLink turns any UVC HDMI capture card into a low-latency second display for Android phones. A fork of Meta's usb-video with a settings screen (language / rotation / capture resolution / pixel format / HUD / keep-screen-on / immersive), immersive fullscreen, and zh-CN localization. Requires Android 12+ (API 31), arm64-v8a.

---

## 特性

| 方向 | 说明 |
|---|---|
| 采集 | 任意 UVC 1.1 / 1.5 免驱采集卡（不需要厂商驱动、不需要 root）。支持 YUYV / MJPEG / NV12，1080p60 可用 |
| 渲染 | 原生层直接写 `ANativeWindow`，不经过 Java 层逐帧拷贝；MJPEG 走 `AImageDecoder` 硬解，失败回退软件解码 |
| 音频 | 采集卡若同时暴露 UAC 音频接口，可以连音频一起采集（实验性，界面里有麦克风权限开关）|
| 界面 | 三屏流程（引导 → 状态 → 投屏），加一层半透明设置页 |
| 全屏 | 沉浸式隐藏状态栏/导航栏，边缘滑动可临时唤出 |
| 设置 | 语言、旋转、采集分辨率、色彩格式、HUD、屏幕常亮、沉浸式全屏，全部持久化 |
| 统计 | HUD 显示分辨率/格式/fps/采集与渲染耗时；非 USB 3.0 会提示带宽不足 |
| 语言 | 简体中文 / English，默认跟随系统 |
| 设备 | Android 12+（minSdk 31）、arm64-v8a；保留 Meta Quest 兼容元数据 |

## 当前状态

版本 **0.5.5**（versionCode 10，应用名 `FelisLink`，包名 `com.felislink.app`）。

已实现的东西（也就是这套代码当前的全部能力）：

- **采集**：UVC 直出，YUYV / MJPEG / NV12，1080p60；链路上限（USB 2.0 / 3.0）由描述符与实测带宽共同决定
- **界面**：引导 → 状态 → 投屏三屏 + 设置页；状态页可滚动，窄屏/横屏都不截断
- **策略引擎**：按实际协商的 USB 速率 + 芯片表 + 用户偏好自动挑格式，给出降级链
- **自愈**：卡顿自动降一档（含弹窗与「不再提示」）、断流自动重启、插卡自动开始
- **画面**：填充模式（原始 / 拉伸 / 裁切）、双指缩放平移、双击复位、触控锁定
- **产出**：截图（`Pictures/FelisLink`）、内置录制采集画面（`Movies/FelisLink`，无声音）
- **系统**：Android 12+（minSdk 31）、arm64-v8a、中文 / English、per-app 语言

还没做：签名 release（目前只有 debug 签名）、arm64 以外的 ABI、HDR / 10bit 输入、亮度调节。

状态页有三个下拉（色彩格式 / 分辨率 / 帧率），可以从采集卡真实上报的格式里临时切换——只列出设备支持的组合，不会选到黑屏。

## 环境要求

- **手机**：Android 12 (API 31) 及以上，arm64-v8a；需要支持 USB Host / OTG
  - Android 13+ 有系统级 per-app 语言；Android 12/12L 用 `attachBaseContext` 包一层 Configuration 实现同样效果，语言设置照常可用
- **采集卡**：UVC 免驱即可（HDMI → USB），不需要特定芯片方案；建议 USB 3.0 卡和 USB 3.0 线
- 采集卡只有 USB 2.0 时，1080p60 基本只能靠 MJPEG；HUD 会提示"USB 速率受限"
- OTG 供电要够：采集卡 + HDMI 源都吃电，供电不足会掉流或反复重连

## 安装

1. 到本仓库的 **Releases** 页面下载最新的 `FelisLink-x.y.z-debug.apk`（约 14 MB）
2. 手机上点开安装（允许「未知来源」），或 `adb install -r FelisLink-x.y.z-debug.apk`
3. 插上采集卡 → 系统弹 USB 权限 → 允许（可勾选"默认用于此设备"）
4. 在状态页确认设备和格式，点开始

> 目前只有 debug 签名。将来发布正式签名版之前需要卸载 debug 包一次（签名不同）。

## 使用

### 三屏流程

1. **连接引导页**：没插卡时的提示页
2. **状态页**：设备名 / VID-PID、权限清单（相机、麦克风、USB）、**USB 链路版本（实时检测：USB 3.x / 2.0 / 1.1）**、格式下拉（格式 / 分辨率 / 帧率）、开始串流 + 重置按钮；内容可滚动，窄屏/横屏不会截断
   - 插卡后先弹一次「采集卡配置」：芯片（若识别）/ 链路速率 / **有没有音频** / 已选格式 / 下一档降级预览；可「用这套配置」或「手动选择」
3. **投屏页**：全屏画面。采集流由原生层直接渲染到 `TextureView` 的 Surface

### 手势

| 操作 | 效果 |
|---|---|
| 单击画面 | 切换 HUD：统计信息 → 操作提示 → 隐藏 |
| 长按画面 | 打开设置页 |
| 双指捏合 | 缩放画面（1x–5x）；放大后单指拖动平移 |
| 双击画面 | 缩放复位 |
| 触控锁定开启时 | 所有点按被忽略，长按画面解锁 |
| 左上角「截图」 | 把当前采集画面存到相册 `Pictures/FelisLink`（不含 HUD/缩放）|
| 左上角「录制画面」 | 录**采集画面本身**（不含 HUD/系统 UI/其他 App）到 `Movies/FelisLink`；按钮变「停止录制」 |
| 状态页「重置」 | 用当前设置重新评估采集卡并直接开始串流（改完分辨率/帧率/预设后不用重插卡）|
| 引导页/状态页右上角齿轮 | 打开设置页 |
| 系统返回手势/按键 | 退出（会停止采集）|

设置页是半透明的，叠在投屏页之上，所以调设置时底下的采集流不会中断。

### 设置项

| 设置 | 选项 | 默认 | 生效时机 |
|---|---|---|---|
| 语言 | 跟随系统 / 中文 / English | 跟随系统 | 立即（系统级 per-app locale，界面重建）|
| 屏幕旋转 | 跟随系统 / 横屏 / 竖屏 | 横屏 | 立即 |
| 采集分辨率 | 自动（按屏幕短边匹配）/ 1080p / 720p / 480p | 自动 | 下次连接采集卡时预选 |
| 色彩格式 | 自动 / MJPEG / YUYV / NV12 | 自动 | 下次连接采集卡时预选 |
| HUD | 开 / 关 | 开 | 立即（关闭后画面无浮层，单击也不唤出）|
| 屏幕常亮 | 开 / 关 | 开 | 立即（`FLAG_KEEP_SCREEN_ON`）|
| 沉浸式全屏 | 开 / 关 | 开 | 立即（`WindowInsetsControllerCompat`）|
| 画面填充 | 原始（等比例留黑边，左右/上下边距对称）/ 拉伸（填满可能变形）/ 裁切（填满等比例裁边）| 原始 | 立即 |
| 帧率上限（高级采集）| 不限制 / 120 / 60 / 30 / 25 / 24 / 15 fps | 不限制 | 下次连接采集卡时预选 |
| 压缩质量（高级采集）| 不可调（libuvc 无 UVC Compression Control，画质由固件决定）| — | — |
| 格式预设（高级采集）| 自动 / 1080p60 / 1080p30 / 720p60 / 720p30 / 480p30 / 自定义 | 自动 | 下次连接采集卡时预选（手动改分辨率/帧率/格式会切回自定义）|
| 自动降级（高级采集）| 开 / 关 | 开 | 立即；卡顿触发时自动降一档 |
| 插卡自动开始（高级采集）| 开 / 关 | 关 | 立即（插卡后自动采集，不再弹能力弹窗，只 Toast 格式）|
| 触控锁定（高级采集）| 开 / 关 | 关 | 立即（长按画面解锁）|
| 音频输出（高级采集）| 跟随系统（说明项，不可选）| — | Android 自动路由到当前耳机/蓝牙/扬声器；App 内定向需系统签名权限 |

> 采集分辨率和色彩格式只影响「连接时的预选」：投屏过程中改这两项不会立即重新协商，需要重插采集卡或退出重进；投屏前的状态页下拉可以随时改。

## 从源码构建

### 依赖

- JDK 17+（AGP 8.7.3 / Kotlin 2.0.21）
- Android SDK：platform 35 + build-tools 35（`compileSdk 35`、`targetSdk 35`、`minSdk 31`）
- Gradle 8.9（仓库自带 wrapper）
- 原生库已预编译并提交在 `app/src/main/jniLibs/arm64-v8a/`，**出 APK 不需要 NDK**；只有改 C++ 才需要

### 标准构建

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

./gradlew test        # 单元测试（UVC 描述符解析 / 格式协商）
```

仓库只为 **arm64-v8a** 提供原生库；其它 ABI 需要自己编。

### 重建原生库（改 C++ 后）

```bash
cmake -S app/src/main/cpp -B build/native \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-33 \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build/native -j8

cp build/native/libusbvideo.so         app/src/main/jniLibs/arm64-v8a/
cp build/native/libusb/libusb.so       app/src/main/jniLibs/arm64-v8a/
./gradlew :app:assembleDebug
```

（不同 CMake 版本的输出目录可能略有差异，按实际产物路径拷。）

### 在只有 aarch64 的机器上构建（比如手机里的 proot）

本项目的 APK 实际就是这样编出来的。官方 Android 工具链全是 **x86_64** 二进制，aarch64 宿主机跑不了，所以做了三处替换：

1. **aapt2**：官方 aapt2 是 x86_64 + 动态 glibc → 抽出 amd64 的 `libc6/libgcc/zlib1g` 垫底，用 `qemu-x86_64-static -L` 跑，再在 `~/.gradle/gradle.properties` 里 `android.aapt2FromMavenOverride=<wrapper路径>` 指过去（**放用户级，别提交进仓库**，否则换台机器就编不动）
2. **原生编译器**：不用 NDK 自带的 clang，改用宿主 `clang` / `clang++` 交叉编译：
   `--target=aarch64-linux-android33 --sysroot=<NDK>/toolchains/llvm/prebuilt/linux-x86_64/sysroot --rtlib=compiler-rt`
   C++ 额外加 `-std=c++20 -fexperimental-library -stdlib=libc++`，链接加 `-fuse-ld=lld -static-libstdc++ -Wl,-z,max-page-size=16384`
   （需要自定义 CMake toolchain 文件，因为 NDK 的 `android.toolchain.cmake` 会把编译器强制覆盖成 NDK 里的 x86 clang）
3. **Gradle 侧**：去掉 `externalNativeBuild` / `ndkVersion`，改为直接打包 `jniLibs` 里的预编译 `.so`

另外两点经验：`sdkmanager --licenses` 在国内网络下容易卡在 socket 等待，加 `-Djava.net.preferIPv4Stack=true` 并给有限 stdin；`gradle.properties` 里 `-Xmx4g` 比较稳。整套跑通后增量构建约 1 分钟。

## 项目结构

```
app/src/main/java/com/meta/usbvideo/
  StreamerActivity.kt        主界面：ViewPager2 三屏、沉浸式、旋转、常亮、设置入口
  StreamerViewModel.kt       USB 状态机 ↔ 采集引擎的桥（含格式预选）
  SettingsActivity.kt        设置页（半透明，叠在投屏之上）
  SettingsStore.kt           偏好存储 SharedPreferences + 初始格式挑选
  StreamingViewHolder.kt     投屏页：TextureView、HUD 统计、单击/长按手势
  StatusScreenViewHolder.kt  状态页：权限清单、USB 链路、三个格式下拉、开始/重置
  LocaleSupport.kt           Android 12/12L 的 per-app 语言兜底
  media/                     截图、内置录制（MediaCodec + EGL）
  usb/                       UVC 描述符解析、格式协商、策略引擎、断流/卡顿监控、UsbMonitor
  permission/  eventloop/  ui/  animation/
app/src/main/cpp/
  UsbVideoStreamer.cpp       UVC 取流 → 解码 → 直出 ANativeWindow
  UsbAudioStreamer.cpp       UAC 音频 → AAudio
  UsbVideoNativeLibrary.cpp  JNI 绑定
  libusb/ libuvc/ libyuv/    vendored 第三方
app/src/main/jniLibs/arm64-v8a/   预编译 libusbvideo.so + libusb.so
app/src/main/res/values/          英文默认资源
app/src/main/res/values-zh/       简中资源（整套 UI，不只设置页）
docs/DEVELOPMENT.md               开发过程记录（每轮的改动、踩坑、验证方式）
.github/workflows/android.yml     CI：单测 + 构建 debug APK
```

## 已知限制

- 投屏过程中不能热切换分辨率/色彩格式（需重连）
- 帧率可选项 = 采集卡描述符里真实上报的帧间隔（现在全部列出并按从高到低排序，含 60/120 若卡支持）。卡没上报的帧率不会凭空出现：例如 USB 2.0 下 YUYV 1080p 物理带宽不够 60fps，卡通常只报 30；要 60/120 需要 MJPG 或 USB 3.0 链路
- 旋转瞬间可能闪黑一帧：`TextureView` 重建 Surface 的固有行为
- USB 权限：Android 每次重新插拔仍可能弹窗，取决于设备/系统策略
- 采集卡兼容性取决于它自报的描述符；个别卡的 MJPEG 帧头异常会被丢弃（HUD 的 fps 会掉）
- 压缩质量不可调：vendored `libuvc` 没暴露 UVC Compression Control，设置页如实标「不可调」，不做假滑块
- 降级和断流重连都要重启一次采集流才能换格式：会闪一帧黑。真正的无感换格式要改 C++（`uvc_stream_stop/open`），`VideoStreamingConnection.reconfigure()` 留作将来就地重配
- 卡顿检测基于渲染到 TextureView 的帧率，卡本身的输出抖动会被平滑；不是示波器级的统计
- 触控锁定只锁画面手势，不锁系统返回/音量
- 截图只含采集画面本身（不含 HUD、不含缩放）；录制画面录的是采集画面（不含 HUD/系统 UI/其他 App），**没有声音**——录本 App 的音频要改原生 capture policy
- 录制走「取画面位图 → MediaCodec(EGL) → H.264」，帧率上限 30、长边上限 1920、码率 2–24 Mbps；高分辨率长录时手机发热与丢帧风险比录屏高，属于 Kotlin 侧实现的代价（原生级录制要改 C++）
- 录制跟画面一致：会包含当前的填充模式/缩放；录制中旋转或改填充不会改变已录文件尺寸
- 音频输出不在 App 内选：Android 自己把媒体声音送到当前耳机/蓝牙/扬声器；App 内定向需要系统签名权限
- Android 12 / 12L 未做真机验证：本轮只保证 33-only API 全部有守卫（`LocaleManager`、`RECEIVER_NOT_EXPORTED`、USB 权限 PendingIntent 可变性），首次在 12 上运行请留意
- 只有 arm64-v8a 预编译库；x86 模拟器跑不了
- 没有签名 release 流水线：CI 只出 debug APK（`app/build/outputs/apk/debug/app-debug.apk`），正式签名要自己配 keystore

## 路线图

按「补体验闭环 → 监看刚需 → 专业工具」排序，细节与代价见 `PLAN.md` §13。

| 优先级 | 内容 | 状态 |
|---|---|---|
| M3 先做 | 音频 UAC 检测与「本卡无音频」明确提示 | ✅ 0.3.0（连接弹窗 + 能力模型）|
| M3 | 设备能力评估：链路速率 / 芯片 / 音频 / 格式回退链 | ✅ 0.3.0 |
| M3 | 自定义参数：帧率上限、压缩质量（如实标注不可调）、自动降级开关 | ✅ 0.3.0 |
| M3 | 传感器自动旋屏（「跟随系统」即传感器模式，已补文案与说明）| ✅ 0.4.0 |
| M3 | 自定义格式预设：一键 1080p60 / 1080p30 / 720p60 / 720p30 / 480p30 / 自定义 | ✅ 0.4.0 |
| M3 | 双指缩放 / 平移（`TextureView.setTransform`）| ✅ 0.4.0 |
| M3 | 卡顿自动降级（`StutterMonitor` + 回退链）与降级弹窗 | ✅ 0.4.0 |
| M3 | 插卡自动开始、断流自动重启（最多 2 次）| ✅ 0.4.0 |
| M3 | 触控锁定（防副屏误触，长按解锁）| ✅ 0.4.0 |
| M3 | 一键截图（`TextureView.getBitmap()`，存 `Pictures/FelisLink`）| ✅ 0.5.0 |
| M3 | 画中画 PiP（悬浮在其他 App 上，进 PiP 自动关 HUD）| 规划 |
| P2 | 低延迟 / 流畅两档缓冲策略 | 规划 |
| M3 | 内置录制：录采集画面（MediaCodec+EGL，无 HUD）| ✅ 0.5.2（画面，无声音）|
| M3 | 画面填充：原始 / 拉伸 / 裁切 | ✅ 0.5.2 |
| M3 | 状态页「重置」：按当前设置重新评估并串流 | ✅ 0.5.2 |
| 单独立项 | 专业显示工具：3D LUT、直方图、波形、斑马纹/伪彩色（需将原生直出改为 GL/EGL 管线）| 评估中 |

## 明确不做

| 想法 | 原因 |
|---|---|
| 触控 / 笔反控（把手机触摸回传给信号源）| UVC 只有单向视频流，协议里没有回传通道；要做只能另走 ADB/网络通道，那是另一个产品 |
| 虚拟显示器驱动 | 采集卡本身就是物理视频输出端，系统自带 UVC 驱动，不需要虚拟屏 |
| 多显示器 / 多虚拟屏切换 | 一张卡只有一路 HDMI 输入，没有第二个源可切 |

## 许可与致谢

- 上游：[facebookexperimental/usb-video](https://github.com/facebookexperimental/usb-video)，Apache-2.0。本仓库的改动同样以 Apache-2.0 发布，见 `LICENSE` / `COPYING`
- 引擎内 vendored 第三方库按各自许可保留：
  - **libusb** 1.0.27 — LGPL-2.1（以动态库形式链接，源码随仓库提供）
  - **libuvc** — BSD-3-Clause
  - **libyuv** — BSD-3-Clause
- FelisLink 是非官方 fork，与 Meta / Facebook 无关
- 若要闭源分发，请先自行核对 libusb 的 LGPL 义务

## 版本记录

版本索引（仅元数据），完整更新内容见仓库根目录的 [`版本记录.md`](版本记录.md)。

| 版本 | 发布日期 | 说明 |
|---|---|---|
| 0.5.5 | — | [详情](版本记录.md#055) |
| 0.5.4 | — | [详情](版本记录.md#054) |
| 0.5.3 | — | [详情](版本记录.md#053) |
| 0.5.2 | — | [详情](版本记录.md#052) |
| 0.5.1 | — | [详情](版本记录.md#051) |
| 0.5.0 | — | [详情](版本记录.md#050) |
| 0.4.0 | — | [详情](版本记录.md#040) |
| 0.3.0 | — | [详情](版本记录.md#030) |
| 0.2.0 | — | [详情](版本记录.md#020) |
| 0.1.0 | — | [详情](版本记录.md#010) |