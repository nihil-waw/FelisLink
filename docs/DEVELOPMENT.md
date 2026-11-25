# 开发记录

这是 FelisLink fork 的开发过程记录：每轮做了什么、踩了什么坑、怎么验证的。
按时间顺序追加，`## n.` 的编号顺序即迭代顺序，最近的在最后。

> 内容来自开发时的本地笔记，路径与机型信息已做脱敏；对复现构建没帮助的临时环境细节可以忽略。
> 构建说明见 [README](../README.md#从源码构建)，版本变化见 [CHANGELOG](../CHANGELOG.md)。

---

# USB 采集卡 → 手机副屏 APK · 方案规划

> 版本 v0.1 · 2026-11-25 · 待用户拍板后进入实施
> 目标：自编译一个 APK，把 HDMI/DP 信号经 UVC 采集卡投到手机，当便携副屏使用。
> 核心体验：插上即出画面、沉浸式全屏、有设置面板、低延迟。

---

## 1. 场景与产品定义

**典型链路**：PC / 笔记本 / 游戏机 / 树莓派 → HDMI → USB 采集卡 → USB-C OTG → 手机全屏显示。

**产品定位**：一个「哑终端式」的便携副屏，不是录屏软件。
关键体验排序（从高到低）：
1. 插上能出画面（设备权限、格式协商别折腾用户）
2. 全屏沉浸、无系统栏干扰、不误触
3. 画面对得上：分辨率/帧率/比例/旋转可调
4. 设置能记住，下次直接连同一张卡
5. 延迟可感知地低（1080p60 目标端到端 < 100ms）

**非目标**：录制、推流、多路采集、外接显示器输出（留 P2 评估）。

---

## 2. 基线选择

**决定：以 `facebookexperimental/usb-video` v2（main @ 847fe27，2026-05）为基线 fork。**

理由：
- 引擎更新：格式协商（`VideoFormatSelectionState` / `Negotiated`）、配置与启动生命周期分离、统计 JNI 回传
- 已有 Android 15+ 16KB 页修复（CMake 里 `-Wl,-z,max-page-size=16384`）
- v2 已是单 Activity + ViewPager2 三屏（连接引导 / 状态 / 投屏）+ ViewModel + ViewBinding，加设置面板成本低
- 依赖全部 vendored 在 `app/src/main/cpp/{libusb,libuvc,libyuv}`，无需外部 NDK 预编译包，构建可离线可复现

**从 denexapp/usb-video-capture 移植 3 个补丁**（它是 fork，只有 UI/CI 改动）：
- 沉浸式全屏（`Add immersive view for streaming activity`）
- 保持原始宽高比（`Maintain original aspect ratio`）
- 签名 + GitHub Actions 发布（`Add signing to GitHub Actions`、`Push a release on workflow run`）

**当前基线参数**：compileSdk 35 / targetSdk 35 / **minSdk 31（v0.5.1 起，用户要求兼容 Android 12）** / JDK 17 / NDK 26.3.11579264 / CMake 3.22.1。

---

## 3. 功能规划

### M2 · MVP（必须有）
| 功能 | 说明 | 验收标准 |
|---|---|---|
| 插卡引导 | 检测 UVC 设备、申请 USB 权限、开始投屏 | 插上 3 步内出画面 |
| 沉浸式全屏 | 隐藏状态栏/导航栏，边缘上滑临时唤出；横屏锁定 | 投屏时无系统栏，滑动可临时唤出 |
| 画面模式 | 铺满裁切 / 完整留边 / 拉伸 三档 | 三档切换即时生效 |
| 方向 | 自动跟随设备方向 + 手动锁定 0/90/180/270 | 旋转后画面不拉伸 |
| 设置面板 | 单击画面唤出 BottomSheet | 面板不遮挡画面中心，可手势收起 |
| 格式选择 | 从设备描述符列出 分辨率×帧率×格式（YUYV/MJPEG/NV12 等） | 只显示设备实际支持项，切换后重新协商成功 |
| 亮度/音量 | 屏幕亮度滑杆；采集音频开关 + 音量 | 亮度即时生效，重启保留 |
| 常亮 | `FLAG_KEEP_SCREEN_ON` 投屏期间不息屏 | 投屏 10 分钟不熄屏 |
| 记忆 | 上次设备 + 全部设置持久化（DataStore） | 杀进程重开，设置与原样恢复 |
| 统计 | FPS 统计显示（可选开关） | 与内置 10s 统计一致 |

### M3 · 打磨（应该有）
- 触控锁 / 双击锁屏（防误触，副屏模式默认锁）
- 自动连接上次设备（USB_DEVICE_ATTACHED 广播 + VID/PID 过滤）
- 格式回退链：首选失败自动降级（如 1080p60 MJPEG → 1080p30 YUYV）
- 断流重连（拔插、切换信号源分辨率后自动重新协商）
- 错误提示页（权限拒绝、带宽不足、格式不支持）
- 灭屏但保持采集（副屏省电模式，P1）
- 深色主题 / 控制条自动隐藏计时
- **自定义格式预设**：保存几组「分辨率×帧率×色彩格式」（1080p60 MJPEG / 1080p30 YUYV…），连接时套用，只保留设备真实支持的项
- **音频 UAC 兜底**：连接时检测采集卡有无音频接口，有就采集并混音，无就在状态页明确写「当前采集卡无音频（此卡未暴露 UAC）」，不留空白
- **画中画（PiP）**：投屏页支持系统 PiP，悬浮在别的 App 上，适合看攻略/盯监控
- **双指缩放 + 平移**：监看局部细节（`TextureView.setTransform()`，不动引擎）
- **一键截图**：抓当前帧存相册（`PixelCopy` 从纹理取）

### P2 · 以后
- 延迟 overlay（信号源秒表 + 同框高速录像校准）
- 录屏（MediaProjection 或帧级编码，截图先做）
- 抖动缓冲与丢帧策略调优（C++ 侧）+「低延迟 / 流畅」两档可切
- 外接显示器 / 平板横屏 / 双窗口
- **专业显示工具**（面向摄影/视频创作）：3D LUT、直方图、波形、斑马纹/伪彩色 —— 需要把直出路改成 GL 管线，工作量最大，单独立项
- 有线网卡? 不相关，跳过

### 明确不做（写了也不承诺）
| 想法 | 为什么不做 |
|---|---|
| 触控 / 笔透传反控 | UVC 只有 video 单向流，标准里没有回传通道；要做只能用 ADB/网络通道，等于另起一个产品 |
| 虚拟显示器驱动 | 采集卡本身就是物理视频输出端，手机侧只需要 UVC 驱动，系统已有 |
| 多显示器 / 多虚拟屏切换 | 一张卡只有一路 HDMI 输入，没有「第二个源」可切 |

---

## 4. UI 结构

沿用 v2 的 ViewPager2 三屏，新增一层设置：

1. **连接引导页**：插卡示意图、USB 权限说明、已连接设备列表（现有）
2. **状态页**：设备名/VID-PID、可用格式列表、开始按钮、错误提示（现有 + 扩展）
3. **投屏页**：`SurfaceView` 全屏 + 手势层
   - 单击 → 唤出控制条（返回 / 设置 / 锁触控 / FPS）
   - 单击空白处 → 收起；双击 → 锁/解锁
   - 长按 → 退出投屏
4. **设置 BottomSheet（新增）**
   - 画面：比例模式、旋转、亮度
   - 采集：分辨率、帧率、格式（下拉，只列支持项）
   - 声音：开关、音量
   - 行为：常亮、触控锁、自动连接、显示 FPS
   - 底部「恢复默认」

**沉浸式实现**：`WindowInsetsControllerCompat.hide(Type.systemBars())` + `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`；manifest 里投屏 Activity 设 `sensorLandscape`（或 user）+ `configChanges` 防重建。

---

## 5. 技术改动清单（文件级）

| 文件 | 改动 |
|---|---|
| `app/build.gradle.kts` | 加 `datastore-preferences`；改 versionName/applicationId；可选 minSdk 30 |
| `AndroidManifest.xml` | 投屏 Act 方向/configChanges；可选 USB device filter |
| `StreamerActivity.kt` | 沉浸式、常亮、旋转、手势入口、注入 SettingsRepository |
| `StreamingViewHolder`（v2 投屏页） | 比例模式布局（AspectRatioFrameLayout）、双击锁 |
| 新增 `SettingsSheet.kt` | Material BottomSheet + SeekBar/Switch/下拉 |
| 新增 `SettingsRepository.kt` | DataStore 持久化 + 默认值 + Flow |
| 新增 `VideoFormatOptions.kt` | 从 `UsbDescriptorParser` 导出 分辨率×帧率×格式 候选列表 |
| `usb/VideoStreamingConnection.kt` | `configure()` 接受用户选定的 width/height/fps/format，保留原有协商失败回退 |
| `usb/UsbDescriptorParser.kt` | 暴露格式枚举（现有解析逻辑复用） |
| `.github/workflows/android.yml` | 加 keystore base64 secrets、签名、自动 release（移植 denexapp） |
| `app/src/main/cpp/*` | **不改**（除 P2 丢帧策略优化）；CMake 保持现状 |

**引擎调用约定**：设置页只决定「候选格式」，仍由 `VideoStreamingConnection` 做协商；协商失败展示错误并回退到上一组可用格式，避免黑屏。

---

## 6. 构建方案（本机已验证可行）

环境实测：JDK 17 ✅、8 核、14GB 内存、862GB 空闲、可访问 dl.google.com / Maven Central / Gradle / GitHub ✅（无 Android SDK/NDK，需装）。

### 方案 A：这台手机上直接编译（推荐先走）
```bash
# 1) 装 cmdline-tools（约 130MB）
mkdir -p ~/Android/sdk/cmdline-tools && cd ~/Android/sdk/cmdline-tools
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q *.zip && mv cmdline-tools latest

# 2) 装组件（约 1.5-2.5GB：platform 35 + build-tools 35 + NDK 26.3 + cmake）
export ANDROID_HOME=~/Android/sdk
yes | sdkmanager --sdk_root=$ANDROID_HOME "platform-tools" "platforms;android-35" \
  "build-tools;35.0.0" "ndk;26.3.11579264" "cmake;3.22.1"

# 3) 编译
git clone https://github.com/facebookexperimental/usb-video.git
cd usb-video && echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
```
预计首次构建 10–25 分钟（proot 下 AAPT2/dex 偏慢），产物 `app/build/outputs/apk/debug/app-debug.apk`。
风险：proot 内存不足可调 `-Xmx4g`；NDK 首次编译 4 个 ABI 可只留 `arm64-v8a` 加速。

### 方案 B：GitHub Actions（长期迭代更稳）
推到自己的 fork，workflow 用 JDK 17 + setup-android，keystore 放 secrets，产出签名 APK 挂 release（denexapp 的 workflow 可照抄）。速率稳定、不占手机资源，但需要你的 GitHub 账号。

**建议**：先 A 出第一个可安装包验证硬件，再按需切 B。

---

## 7. 测试与验收

- **功能**：上表 MVP 逐项过；拔插、切源、锁屏、旋转、深色模式各跑一遍
- **延迟**：信号源显示毫秒时间戳 → 手机 120/240fps 慢动作同框拍 → 数帧差；目标 1080p60 ≤ 100ms
- **性能**：内置统计看 fps 与 capture/render 耗时占比，1080p60 要求 fps≥58、render<50%
- **兼容**：你手机的 Android 版本 × 采集卡；再抽 1-2 台不同 Android 版本
- **稳定性**：连续投屏 2 小时看发热/掉帧/内存

---

## 8. 里程碑

| 阶段 | 内容 | 估时 |
|---|---|---|
| M0 | ~~装 SDK/NDK，原版编出 debug APK~~ ✅ 2026-11-25 完成（见 §11） | 0.5 天 |
| M1 | 装到手机 + 采集卡跑通，测延迟基线 | 0.5 天 |
| M2 | MVP：设置页 + 沉浸式 + 旋转/分辨率/色彩格式/HUD/常亮/语言 + 持久化 → **已交付 0.2.0（见 §12）**，比例模式与亮度待补 | 2–3 天 |
| M3 | 打磨：自动连接、锁触控、回退链、重连、错误页 | 1–2 天 |
| M4 | 签名 release + 安装说明（可选降 minSdk） | 0.5 天 |

合计约 5–7 个工作日（按验收节奏浮动）。

---

## 9. 风险清单

1. ~~minSdk 33~~ → **v0.5.1 已降到 31**：33-only API 共三处，全部处理（见 §17）。
2. **采集卡兼容**：USB 3.0 带宽/供电、部分卡不暴露音频（UAC）、MJPEG 帧头异常。需要你的具体型号。
3. **协商失败黑屏**：必须有回退链与错误提示，不能静默失败。
4. **SurfaceView 单缓冲**：全屏切换/旋转瞬间闪黑，预期内，需在 M3 处理。
5. **USB 权限每次插拔确认**：Android 限制，可做「记住设备」但系统仍可能弹窗。
6. **proot 构建慢**：首次编 NDK 四个 ABI 可能较久，限制 `abiFilters=arm64-v8a` 可大幅加速（同时减小 APK）。
7. **许可证**：上游 Apache-2.0，但 vendored libuvc 带 GPL-2.0 说明，自用/开源无碍，闭源分发需核对。

---

## 10. 需要拍板的事

1. ✅ 手机：一台 arm64-v8a Android 手机（实测机型隐去），**Android 17 / SDK 37 / arm64-v8a**；v0.5.1 起用户要求兼容 Android 12，minSdk 降到 31。
2. ✅ 采集卡：不指定型号，走**通用 UVC**（插上能枚举就行）；实测时再看它自报格式。
3. ✅ 音频：做成开关；卡自带 UAC 就抓，没有就静音。Windows 虚拟声卡对手机无效（除非卡有 3.5mm 输入回连）。
4. ✅ 构建方式：**本机编译已跑通**（方案见 §11）；GitHub Actions 留作备选。
5. ✅ 应用名 **FelisLink**，包名 `com.felislink.app`，版本 0.1.0 → 0.2.0（设置页）。

---

## 11. 本机构建方案（aarch64 手机上，2026-11-25 已跑通）

官方 Android 工具链只提供 **x86_64** 二进制，手机是 aarch64，直接跑 Gradle 会连环踩坑。已落地的组合：

| 组件 | 方案 |
|---|---|
| 环境 | `ANDROID_HOME=$ANDROID_HOME`；SDK 35 + build-tools 35 + platform-tools + NDK 26.3（NDK 仅作 sysroot 用）|
| Gradle | 8.9 手动装到 `/opt/gradle/gradle-8.9`（wrapper 下载被代理 TLS 掐断，curl 拉 zip）|
| CMake/Ninja | 系统 aarch64 版：`apt install cmake ninja-build`；`local.properties` 加 `cmake.dir=/usr` |
| aapt2 | 官方 aapt2 是 x86_64 + 动态 glibc → 抽 amd64 的 libc6/libgcc/zlib1g 到 `/opt/x86root/root`，用 `qemu-x86_64-static -L` 跑；包装成 `/opt/wrappers/aapt2`，`gradle.properties` 设 `android.aapt2FromMavenOverride` |
| NDK clang | **不用**。改用系统 `clang-18` + `lld-18` 交叉编译（`--target=aarch64-linux-android33 --sysroot=<NDK sysroot> --rtlib=compiler-rt -fexperimental-library`）|
| 原生库 | 用自定义 toolchain `toolchain-android-aarch64.cmake` 手动 CMake 构建 → `libusbvideo.so` + `libusb.so` 放进 `app/src/main/jniLibs/arm64-v8a/`；Gradle 里删掉 `externalNativeBuild`/`ndkVersion`，改为 jniLibs 打包 |
| 打包 | `gradle :app:assembleDebug`（约 2 分钟）→ 14MB APK，`lib/arm64-v8a/{libusbvideo.so,libusb.so}` |

**改 C++ 代码后的重建流程**：
```bash
cd /tmp/cbuild-arm64 && ninja -j8
cp libusbvideo.so libusb/libusb.so <repo>/app/src/main/jniLibs/arm64-v8a/
cd <repo> && gradle :app:assembleDebug
```

**已知限制**：`stripDebugDebugSymbols` 无法 strip（无 x86 llvm-strip），按原样打包，只影响体积；正式 release 时再处理。

---

## 12. M2 设置页：已实现（v0.2.0，2026-11-25）

**入口**：非投屏页（引导页/状态页）右上角齿轮按钮；投屏页**长按画面**打开（避免在画面上留常驻浮层）。设置页用半透明主题叠在投屏页上，底层采集流不中断（Activity 只 onPause，不进 onStop）。

| 设置项 | 取值 | 生效时机 |
|---|---|---|
| 语言 | 跟随系统 / 中文 / English | 立即（`LocaleManager.applicationLocales` + `locales_config`，系统重建界面）|
| 屏幕旋转 | 跟随系统 / 横屏（默认）/ 竖屏 | 立即（`requestedOrientation`；manifest 已去掉写死的 landscape）|
| 采集分辨率 | 自动（按屏幕短边匹配）/ 1080p / 720p / 480p | 下次连接采集卡时预选；状态页三个下拉仍可临时改 |
| 颜色格式 | 自动 / MJPEG / YUYV / NV12 | 同上 |
| HUD | 开（默认）/ 关 | 立即（关掉后画面无任何浮层，单击也不再唤出）|
| 屏幕常亮 | 开（默认）/ 关 | 立即，`FLAG_KEEP_SCREEN_ON`，进前台即生效 |
| 沉浸式全屏 | 开（默认）/ 关 | 立即，`WindowInsetsControllerCompat.hide(systemBars)` + 边缘上滑临时唤出 |
| 关于 | 版本号 + 引擎说明 | — |

**新增文件**
- `java/com/meta/usbvideo/SettingsStore.kt`：`SharedPreferences` 偏好（语言/旋转/分辨率/色彩格式/HUD/常亮/沉浸式），`revision` 计数器让主界面知道后台期间改过设置；`pickInitialVideoFormat()` 按偏好挑首个候选格式（只从采集卡真实上报的格式里挑）。
- `java/com/meta/usbvideo/SettingsActivity.kt`：设置页（标题+分区+行，选择项走暗色 `AlertDialog` 单选列表，开关项是彩色药丸）。
- `res/layout/activity_settings.xml`、`res/layout/item_settings_row.xml`、`drawable/{ic_settings,settings_button_bg,settings_row_bg}.xml`、`res/xml/locales_config.xml`（en + zh-CN）。
- `res/values/settings_strings.xml` + `res/values-zh/settings_strings.xml`；**整套原界面也补了 `res/values-zh/strings.xml`**（65 条），选中文后全 App 中文。

**改动**
- `AndroidManifest.xml`：去掉投屏 Activity 的 `screenOrientation="landscape"`，加 `localeConfig`，注册 `SettingsActivity`（`Theme.UsbVideo.Settings` 半透明）。
- `StreamerActivity.kt`：启动/恢复时应用旋转、常亮、沉浸式；齿轮按钮显隐（投屏页隐藏）；onResume 比 `SettingsStore.revision` → 改了分辨率/格式就 `refreshFormatSelectionFromSettings()`；语言不一致时一次性 `recreate()`（`LocaleRecreateGuard` 防死循环）。
- `StreamingViewHolder.kt`：HUD 总开关（关掉不渲染统计/提示）、长按回调。
- `StreamerViewModel.kt`：初始格式改为 `settings.pickInitialVideoFormat()`。
- `themes.xml`：`FelisLinkDialog`（暗色单选弹窗）、`Theme.UsbVideo.Settings`（半透明）。

**已知限制**
- 投屏**过程中**改分辨率/色彩格式不会立刻重协商（状态页下拉可临时覆盖；彻底改变需重插卡或退出重进）。
- 比例模式（铺满/留边/拉伸）、亮度调节、帧率上限、音频开关尚未做（M3）。

**验证**：`gradle :app:assembleDebug` 通过（36s 增量），`aapt2 dump badging` 确认 `versionCode=2 / versionName=0.2.0`、`locales` 含 `zh-CN`、`lib/arm64-v8a/{libusb.so,libusbvideo.so}` 齐全，`aapt2 dump resources` 确认 `string/settings_title` 有 `(zh) "设置"`。


---

## 13. 功能建议评估（2026-11-25，来自用户列表）

判定口径：**能做 / 要改引擎 / 不值得做**。已按此并入 §3。

| 建议 | 判定 | 技术路径与代价 | 归入 |
|---|---|---|---|
| 传感器自动旋屏 | ✅ 基本已有 | 现有「跟随系统」用的就是 `SCREEN_ORIENTATION_FULL_USER`（含传感器）；只需把选项文案改成「自动（传感器）」并说明。真正的坑是副屏场景里自动旋转会乱翻，默认仍建议锁横屏 | M3（0.5 小时）|
| 自定义分辨率/刷新率预设 | ✅ 做 | 在 `SettingsStore` 存一组 `(w,h,fps,fourcc)` 预设，连接时用现有 `pickInitialVideoFormat()` 逻辑匹配；只保留设备真实上报的组合，避免黑屏 | M3（0.5 天）|
| 音频直通 UAC 兜底 | ✅ 引擎已支持 | `UsbAudioStreamer` + `AudioStreamingConnection` 现有；缺的是「检测卡有没有 UAC」并在状态页明说无音频。UAC 采集属于 Android 允许的 USB 音频路径，不需要 root | M3（0.5 天）|
| 画中画 PiP | ✅ 做，但有边界 | `supportsPictureInPicture` + `onUserLeaveHint` 进 PiP；`TextureView` 在 PiP 里可正常出画。注意：PiP 窗口小、系统会降帧；进 PiP 后 HUD 要自动关 | M3（1 天）|
| 3D LUT / 直方图 / 波形 / 斑马纹 / 伪彩色 | 🟡 可行但要改引擎 | 现在原生层是 `ANativeWindow` 直接锁 buffer 写 RGBA/YUV，加 LUT 或分析必须改成 GL/EGL 管线（一次纹理上传 + shader），直方图/波形还要回读统计。等于重写渲染路径 | P2 单独立项（3–5 天起，风险高）|
| 双指缩放 / 平移 | ✅ 便宜 | `TextureView.setTransform(Matrix)`，只动 view 层，引擎不用碰；要注意与既有画面居中/裁切逻辑的矩阵叠加 | M3（0.5 天）|
| 低延迟 / 画质模式 | ✅ 做 | 调 C++ 侧缓冲与丢帧策略（`RingBuffer` 深度、是否跳帧）+ 一个枚举下发 | P2 / M3 后段（1 天）|
| 一键截图 | ✅ 便宜 | `PixelCopy.request(textureView)` 存 `MediaStore.Pictures`；MJPEG 帧也能直接拿原始 JPEG 落盘（无损） | M3（0.5 天）|
| 录屏 | 🟡 | MediaProjection 录整个屏最省事，但会把系统 UI 也录进去；帧级编码音画同步要调 | P2 |
| 触控/笔透传 | ❌ 不做 | UVC 是单向视频协议，无回传通道；ADB/网络反控是另一个产品 | — |
| 虚拟显示器驱动 | ❌ 不需要 | 采集卡即物理输出端，UVC 驱动系统自带 | — |
| 多显示器切换 | ❌ 做不到 | 一张卡只有一路输入 | — |

**建议实施顺序**：音频 UAC 提示 → 传感器旋屏文案 → 格式预设 → 双指缩放 → 截图 → PiP →（P2）低延迟档位 →（单独立项）专业显示工具。
理由：前三个补的是「体验闭环上的坑」，后两个是「监看刚需」，PiP 改动面大（生命周期 + HUD 联动），专业工具最贵、且要重写渲染管线。

## 14. M3 第一批：策略引擎 + 连接弹窗 + 自定义参数（已实现 v0.3.0，2026-11-25）

本轮只要「设备能力评估 → 界面说清楚 → 参数可调」三件事，范围外的一律留接口不接线。

### 已实现

| 文件 | 内容 |
|---|---|
| `usb/CaptureProfileAssessor.kt`（新）| `LinkSpeed`（读 `getUsbSpeed()` 实际协商速率，Super→USB 3.x，High/Full/Low→USB 2.0，Unknown→未知）；`ChipsetRegistry`（MS2109 `534D:2109`、MS2130 `345F:2130`、AM8352 `1D1F:F115`，**仅供显示 + 未知速率时按 USB 2.0 兜底**）；`CaptureProfile(preferred, fallbackChain, reason, chipsetLabel, linkSpeedLabel, audioSupported)`；排序规则：USB 3.0 优先无损（YUYV→MJPG→NV12）、USB 2.0 强制压缩（MJPG→NV12→YUYV），先网格命中再按面积最近，帧率上限参与筛选 |
| `ui` 无新目录 | 连接弹窗直接放 `StreamerActivity.showCaptureProfileDialog()`：设备名 / 芯片（若识别）/ 链路 / **音频：已检测到 or 该卡未暴露 UAC** / 已选格式 / 一句原因 / 下一档降级预览 / 「压缩质量由固件决定」说明；每次插卡只弹一次（`deviceKey` 去重，转屏不重复弹）|
| `SettingsStore.kt` | 新增 `fpsCap`（0=不限制，60/30/25/24/15）、`autoDowngrade`（仅存值）、`lastGoodFormat`（协商成功时记录）、`resolvedTarget()`；`pickInitialVideoFormat()` 保留作为兜底 |
| `SettingsActivity.kt` | 只新增「高级采集」分区：帧率上限（单选）、压缩质量（不可调 + 如实说明）、自动降级（开关，标注"检测尚未接入"）、生效时机（说明。分辨率/色彩格式仍在「画面与显示」分区，未动结构）|
| `StreamerViewModel.kt` | 连接时调用 `CaptureProfileAssessor.assess()`，结果放进 `captureProfileFlow`；协商成功写 `lastGoodFormat`；断开清空 profile |
| `usb/VideoStreamingConnection.kt` | 预留 `reconfigure(profile: CaptureProfile): Boolean`（含 TODO 注释：将来用 `uvc_stream_stop/open` 原地换格式，不动 Surface）|
| 文案 | `settings_strings.xml` + `values-zh/settings_strings.xml` 各加 16 键（高级采集 11 + 弹窗 13 中的共用部分），中英同步 |

### 本轮的三个技术结论（照规格做不到的地方，如实降级）

1. **压缩质量调节做不到**：vendored `libuvc` 没有暴露 UVC Compression Control（CT_COMPRESSION），原生层也没有对应调用，所以设置页显示「不可调 · 固件决定」，不做假滑块。要真调只能改 C++ 引 `uvc_set_ctrl`，本轮不动 `app/src/main/cpp/*`。
2. **`bcdUSB` 不作为判据**：描述符里的 `bcdUSB` 只是设备自称的速度能力，插到 USB 2.0 口上照样跑 480 Mbps。真实协商速率 `UsbVideoNativeLibrary.getUsbSpeed()` 才决定带宽，`LinkSpeed` 用它；`bcdUSB` 解析仅在原生层已有、不重复实现。
3. **芯片表只做展示**：VID/PID 表无法证明带宽与格式能力，最多用于「未读到速率时按 USB 2.0 保守处理」和弹窗里给个名字。真正能力全部来自 `UsbDescriptorParser` / `supportedVideoFormats()`。

### 明确留到后面（接口已留，未接线）

| 项 | 预留的位置 |
|---|---|
| 卡顿自动降级 | `CaptureProfile.fallbackChain` + `nextFallbackAfter()`；`reconfigure()` 空实现；`autoDowngrade` 已持久化但无人消费 |
| 自动连接 / 断流重连 | 未动 `UsbMonitor` 状态机 |
| 锁触控 / 双指缩放 | 未动 `StreamingViewHolder` 手势 |
| 格式预设（1080p60 等一键档） | 本轮用「分辨率 + 帧率上限 + 色彩格式」组合替代；预设 UI 未做，`CapturePreset` 机制已从代码移除，等真正需要时再加 |

## 15. M3 第二批：自愈闭环 + 手势 + 预设（已实现 v0.4.0，2026-11-25）

§14 里「留到后面」的四项全部落地，另补了旋屏文案。仍然不碰 `app/src/main/cpp/*`。

| 项 | 实现 | 关键决策 |
|---|---|---|
| 卡顿自动降级 | `usb/StutterMonitor.kt`（纯逻辑，5 个单测）：5 秒窗口均值低于目标 80% 且持续 3 秒才触发，触发后冷却 15 秒 | 目标帧率跟随当前格式，降级后自动换目标并重新计时，避免一路降到底 |
| 降级弹窗 | 自动降级关掉时弹「降级 / 保持当前 / 不再提示」；开着一律静默降级 + Toast | 「不再提示」写入 `stutterPromptDisabled`，只在本次及以后都不再问，不改自动降级开关 |
| 降级执行 | `StreamerViewModel.downgradeTo()`：改选择状态 → `onStreamingStopping()` → 原生 stop → `onStreamingStoppedWithFormat()` → 状态机 `Stopped→Restart` 重启 | 就地换格式要 C++（`uvc_stream_stop/open`），本轮不做，所以认一次「闪一帧」的代价；`VideoStreamingConnection.reconfigure()` 注释已改成「留给未来的就地重配」 |
| 降级后视图 | `StreamingViewHolder.syncFormatIfChanged()`：重启复用同一个 holder，不会 rebind，靠每秒看门狗比对当前格式，变了就改 `TextureView` 布局、重置缩放、重建 monitor | 不做这步会出现新分辨率被旧 Surface 拉伸 |
| 断流重连 | 1 秒 ticker 看门狗（**不能只靠帧回调**——断流就没有回调了）：12 秒无首帧或 5 秒无新帧 → 重启采集；同一会话最多 2 次，之后提示重新插拔 | 重启走 `stopStreaming()`，`Stopped` 由 Activity 自动 `restartStreaming()` 接上 |
| 自动连接 | 设置开关 → 插卡后 700ms 自动用评估出的格式开始；弹窗改为不显示，只 Toast 告知格式 | 只有真正开始成功才记住 `deviceKey`，这样「先授权 USB 再自动开始」也能生效 |
| 锁触控 | 开关打开后吞掉所有点/手势，长按画面 2.5 秒级长按解锁并 Toast | 防副屏被误触打断，不锁音量键和返回手势 |
| 双指缩放 / 平移 | 1x–5x 捏合，放大后单指拖动，边界钳制；双击复位；格式变化/重绑自动复位 | 只动 `TextureView.setTransform`，引擎零改动 |
| 格式预设 | 7 档：自动 / 1080p60 / 1080p30 / 720p60 / 720p30 / 480p30 / 自定义 | 显式预设锁定分辨率+帧率；再手动改分辨率/帧率/色彩格式时先把预设值写进手动项再切「自定义」 |
| 旋屏文案 | 「跟随系统」→「自动（传感器）」，补充副屏场景建议锁横屏 | 行为本来就是 `FULL_USER`（含传感器），只修文案 |

验证：`gradle :app:assembleDebug :app:testDebugUnitTest` 通过；`StutterMonitorTest` 5 例 + 上游 `VideoStreamingConnectionTests` 5 例全绿。

### 仍未做（README 路线图里剩下的）

一键截图、画中画 PiP、低延迟/流畅缓冲档、录屏、原生就地换格式（需改 C++）、音频 UAC 直通路由、专业显示工具（要 GL/EGL 管线）。

## 16. 录屏 / 截图 / 音频输出说明（已实现 v0.5.0，2026-11-25）

这一轮全部是「不碰引擎」的实现：截图走 `TextureView.getBitmap()`，录屏走 MediaProjection 镜像窗口，音频路由只做诚实说明。

| 项 | 实现 | 关键决策 |
|---|---|---|
| 截图 | `media/CaptureMediaSaver.kt`：`TextureView.getBitmap()` → `PixelCopy` 都行，这里用前者（TextureView 不支持 PixelCopy）；写 MediaStore `Pictures/FelisLink`，PNG 无损 | 只截采集画面本身：**不含 HUD、不含缩放**（`getBitmap()` 返回未变换的 Surface 内容），截图分辨率 = 协商到的视频格式 |
| 录屏 | `media/ScreenCaptureService.kt`：前台服务（`foregroundServiceType=mediaProjection`）→ MediaProjection → VirtualDisplay → MediaRecorder(H.264/MP4) → MediaStore `Movies/FelisLink`；通知栏带「停止」 | 录的是**屏幕镜像**（窗口 + HUD + 缩放都录进去），引擎零改动；失败/半途停止会删掉坏文件；`stopping` 标志防 MediaProjection 回调重入 |
| 录屏尺寸 | 按 `currentWindowMetrics`，长边 >1920 等比缩，宽高取偶；码率按像素数 clamp 在 4–16 Mbps，帧率 60 | 录制中旋转/改窗口不重开采集，可能留边（写进已知限制）|
| 录屏声音 | 不做 | 要录到本 App 的 AAudio 播放，得在原生层把 capture policy 改成 `ALLOW_CAPTURE_BY_ALL`，属于改引擎二进制（`.so` 重建 + 回归风险），本轮按「不做破坏性更改」跳过 |
| 交互 | 投屏画面左上角「截图 / 录屏」两个胶囊按钮，随 HUD 一起淡出淡入；触控锁定时按钮点击无效 | 放左上避免和右上角设置按钮打架；录屏中按钮变「停止录屏」|
| 音频输出 | 设置 →「高级采集」加说明行「音频输出 · 跟随系统」 | Java 层没有给 media 流定向的 API（`setPreferredDeviceForStrategy` 要 `MODIFY_AUDIO_ROUTING` 签名权限），AAudio 选设备在 C++；而 Android 本身已把媒体音频路由到当前耳机/蓝牙/扬声器，所以不伪装滑块，只写清楚 |

验证：`:app:assembleDebug :app:testDebugUnitTest` 通过；`aapt2 badging` 版本 0.5.0/versionCode 5；录屏/截图需要真机点一次授权才能实测（本机无法自装）。

### 仍未做

一键截图/录屏的**快捷键**（如音量键组合）、录屏包含采集卡音频（需原生 capture policy）、PiP、低延迟/流畅缓冲档、UAC 定向路由（需原生或系统签名）、原生就地换格式、专业显示工具（GL/EGL）。

## 17. 兼容 Android 12（minSdk 31，v0.5.1，2026-11-25）

用户要求「APK 要求版本降到安卓 12+」。全仓扫描 33-only API，只命中三处，逐一处理：

| 位置 | 问题 | 处理 |
|---|---|---|
| `SettingsActivity.applyLanguage` + `LocaleRecreateGuard` | `android.app.LocaleManager` 是 API 33 类；12/12L 直接引用会 NoClassDefFoundError | 13+ 走原 `LocaleManager` 分支；12 走新 `LocaleSupport.wrap()`：两个 Activity 的 `attachBaseContext` 用 `createConfigurationContext` 包一层 `Configuration`，设置页改语言后 `recreate()` 生效。12 上系统「应用语言」菜单里不会出现 FelisLink（没有 localeConfig 支持），但 App 内选择照常工作 |
| `StreamerViewModel.prepareUsbBroadcastReceivers` | `Context.RECEIVER_NOT_EXPORTED`（int 4）在 33 前无意义 | 改 `ContextCompat.registerReceiver(..., ContextCompat.RECEIVER_NOT_EXPORTED)`，低于 33 时 compat 自动退回普通注册；同时去掉 `@RequiresApi(TIRAMISU)` |
| `StreamerViewModel.requestUsbPermission` | USB 权限 PendingIntent 用了 `FLAG_IMMUTABLE`；系统要往 intent 里填 `EXTRA_PERMISSION_GRANTED`/`EXTRA_DEVICE`，immutable 在 12+ 会丢，回调不触发 → 卡永远连不上 | 改 `FLAG_MUTABLE or FLAG_UPDATE_CURRENT`（12 起必须显式声明可变性）。这条其实对 13/14 也是修复，不只是兼容 |

其余依赖都已在 31 以下：`WindowManager.currentWindowMetrics`(30)、`MediaStore IS_PENDING/RELATIVE_PATH`(29)、`MediaProjection` + FGS type(29/34 声明)、`IntentCompat.getParcelableExtra`、`WindowInsetsControllerCompat`、`ActivityResultLauncher`。原生库 `.note.android.ident` 标注 minimal API 21，只依赖 `libaaudio/libjnigraphics/libandroid/liblog/libc`，31 上可加载。

验证：`gradle :app:assembleDebug :app:testDebugUnitTest` 通过；`aapt2 badging` 确认 `minSdkVersion=31`、versionCode 6 / 0.5.1。**Android 12/12L 真机未测**（本机是 Android 17），需要用户在一台 12 设备上验证：USB 权限弹窗回调、设置页语言切换、录屏、截图。

## 18. 内置录制 + 重置 + 画面填充（v0.5.2，2026-11-25）

用户三项要求：录制要内置画面（不是手机录屏）、开始串流旁加重置（按新设置配置串流）、画面填充设置、版本 0.5.2。

### 1. 录制改为内置采集画面

| 项 | 原实现（0.5.0）| 现实现（0.5.2）|
|---|---|---|
| 内容 | MediaProjection 录整块手机屏幕（含 HUD、通知栏、其他 App）| 录采集画面本身（`TextureView.getBitmap()` 每帧 → MediaCodec 输入 Surface）|
| 权限 | 每次弹系统「录制或投射」授权；前台服务 + 通知 | 无额外权限、无系统弹窗、无前台服务 |
| 编码 | MediaRecorder + VirtualDisplay | MediaCodec H.264 + 自建 EGL（`CaptureVideoRecorder`），MediaMuxer 写 MediaStore |
| 尺寸 | 屏幕尺寸/2 对齐，长边 ≤1920 | 视图尺寸等比，长边 ≤1920，帧率 ≤30 |
| 音频 | 无 | 无（UAC 播放走原生 AAudio，Kotlin 侧拿不到；要录音得改 C++ capture policy）|

实现要点：
- `media/CaptureVideoRecorder.kt`：object 门面 + Session（HandlerThread）。EGL 上下文绑 encoder input surface，位图上传纹理后画满屏、`EGLExt.eglPresentationTimeANDROID` 打时间戳、swapBuffers；每帧 drain 一次编码器，停止时 `signalEndOfInputStream` + 3 秒收尾。
- 取帧在 `StreamingViewHolder.onSurfaceTextureUpdated()` 里节流（1000/fps ms），HUD 隐藏也照常取帧；编码器忙时丢帧（`AtomicBoolean`），位图在编码线程回收。
- 录制中点停止 → `RecordingState` 置 false（按钮变回「录制画面」），文件在 `Movies/FelisLink`；离开 App（Activity onStop）自动停录，避免空帧文件。
- 已删除 `media/ScreenCaptureService.kt` 与清单里的 service、`FOREGROUND_SERVICE*` 权限、Activity 里的 MediaProjection launcher。

### 2. 状态页「重置」

`StatusScreenViewHolder` 里「开始串流」右侧新增「重置」按钮 → `StreamerViewModel.reapplySettingsAndStream()`：**重新跑一遍 `CaptureProfileAssessor`**（读当前设置：预设/分辨率/帧率上限/色彩格式），把 `videoFormatSelectionStateFlow` 设为新的 Open(preferred) 后立刻 `negotiateVideoFormat()` 开流，Toast 报出新格式。改完设置不用重插卡。

> 注意：能力弹窗按 `deviceKey` 去重，重置不会重复弹；新格式只在 Toast 里说。

### 3. 画面填充

`SettingsStore.FillMode { ORIGINAL, STRETCH, CROP }`，设置页「画面与显示」→「画面填充」。实现只动 `TextureView.setTransform` 矩阵：
- TextureView 恒为 MATCH_PARENT（默认是拉伸铺满）；
- ORIGINAL：在视图空间按 `min(vw/fw, vh/fh)` 反向修正，等比 + 黑边；
- CROP：按 `max(...)` 修正，等比 + 裁边；
- STRETCH：单位矩阵（直接铺满）。
缩放/平移叠在基础矩阵之上；`clampPan()` 用有效缩放算边界；看门狗每秒比对 `settings.fillMode`，设置页返回后立即重算，不用重连。

### 验证

`gradle :app:assembleDebug :app:testDebugUnitTest` 通过；`aapt2 badging` → versionCode 7 / 0.5.2 / minSdk 31。真机验证点：录制出来的 MP4 是否只有采集画面（无 HUD）、切填充模式是否立即生效、状态页重置能否用新设置直接开流、离开 App 时录制是否正常收尾。

### 0.5.3 修复（2026-11-25）

用户装 0.5.2 后一开就崩，日志：

```
java.lang.NoSuchMethodException: com.meta.usbvideo.StreamerViewModel.<init> [class android.app.Application]
  at androidx.lifecycle.ViewModelProvider$AndroidViewModelFactory.create(...)
  at com.meta.usbvideo.StreamerActivity.getStreamerViewModel(StreamerActivity.kt:82)
```

原因：0.5.2 拆 MediaProjection 时，删除 `recordLauncher` 块的范围过大，把紧跟其后的 `private val streamerViewModel by viewModels { viewModelFactory { initializer { ... } } }` 整段也删了；临时补的 `by viewModels()` 走默认工厂，而 `StreamerViewModel` 的构造是 `(Application, CameraPermissionState, RecordAudioPermissionState)` 三参 → 启动反射失败。

修复：恢复显式工厂（`viewModelFactory { initializer { StreamerViewModel(application, getCameraPermissionState(), getRecordAudioPermissionState()) } }`），版本 0.5.3 / versionCode 8。教训：以后删块必须用「上界+下界」双锚点删除，不能只找第一个 `}`。

## 19. 填充对称 / USB 链路显示 / 帧率选项 / 准备页滚动（v0.5.4，2026-11-25）

| 需求 | 处理 |
|---|---|
| YUY2 可选帧率到 60、MJPG 到 120 | 帧率选项本来就来自描述符 `adwFrameIntervals`，之前没排序也没去重。现在按格式→分辨率分组后 **distinct + 降序**，卡上报的 60/120 会排在最上面；设置「帧率上限」也补了 120。卡没上报的帧率不能强塞（native 协商会失败），USB 2.0 + YUYV 1080p 的 60fps 是物理带宽不够 |
| 检查清单下显示 USB 版本 | 清单卡片下新增一行 `usb_speed_value`，每秒读 `UsbVideoNativeLibrary.getUsbSpeed()` → USB 3.x（SuperSpeed）/ USB 2.0（High Speed）/ USB 1.1 Full/Low / 检测中 |
| 「原始」填充左右黑边不对称 | 根因：0.5.2 把 TextureView 改成铺满容器，再用 Matrix 修正比例，路径绕且受布局缩放影响。改回上游思路：**TextureView 尺寸 = 采集格式尺寸（1:1 像素直出）**，由 `VideoContainerView.onLayout` 统一缩放：ORIGINAL=min(sx,sy) 等比居中（黑边天然对称）、CROP=max(sx,sy)、STRETCH=非等比铺满；双指缩放/平移仍走 TextureView Matrix |
| 准备页小屏/横屏被截断 | `status_screen.xml` 的 `setup_box` 外面包 `ScrollView(fillViewport)`，宽度从固定 520dp 改为 match_parent + 16dp padding；窄屏和横屏都能滚动到底部的「开始串流 / 重置」 |

验证：`gradle :app:assembleDebug :app:testDebugUnitTest` 通过；`aapt2 badging` → versionCode 9 / 0.5.4 / minSdk 31。真机待验证：黑边是否对称、USB 链路显示、YUYV/MJPG 帧率列表是否出现 60/120、横屏滚动。

## 20. 修复「重置」报「没有可用的采集格式」（v0.5.5，2026-11-25）

现象：串流中/串流后点状态页的「重置」，Toast 提示「重置失败：没有可用的采集格式」，卡其实好好的。

根因：`StreamerViewModel.reapplySettingsAndStream()` 第一行就是

```kotlin
val deviceState = usbmon.usbDeviceState.value as? ConnectedUsbDevice ?: return false
```

而一旦开始过串流，状态已经变成 `StreamingUsbDeviceState`（页面上流媒体页是第 2 页，状态页仍在 Pager 第 1 页，`Negotiated` 分支又没把「重置」按钮隐藏），所以按下必然走 `return false` → 误导性的「没有可用的采集格式」。

修复：

- `openInitialSelection` 拆出 `assessSelection(connection, usbDevice, audioSupported)`，不依赖具体的状态类；`Connected` 与 `Streaming` 两种状态都能拿同一路 `videoStreamingConnection` 做评估。
- `reapplySettingsAndStream()` 改为 `when`：
  - `ConnectedUsbDevice` → 评估 + `negotiateVideoFormat()`（原逻辑）；
  - `StreamingUsbDeviceState` → 评估出新格式 → `_videoFormatSelectionStateFlow = Negotiated(target)` → `restartPipelineWith(target)`：`onStreamingStopping()` + `EventLooper.post` 里停/断 audio+video → `onStreamingStoppedWithFormat(target)`，Activity 收集到 `Stopped` 会 `restartStreaming()`，于是 `Restart → startStreaming()` 用新格式重开；
  - 其它（没卡/权限未授予）→ `ReapplyFailure.NO_CARD`。
- `restartPipelineWith()` 与 `downgradeTo()` 共用同一段拆除逻辑，降级路径行为不变。
- 状态页：`Negotiated`（串流中）与 `NegotiationError` 分支显式显示「重置」；`hideVideoFormatSection()` 显式隐藏，避免残留；失败 Toast 按 `lastReapplyFailure` 区分「采集卡还没连上」和「没有可用的采集格式」（新增 `reset_streaming_need_card` 中英文案）。

验证：`gradle :app:assembleDebug :app:testDebugUnitTest` 通过；`aapt2 badging` → versionCode 10 / 0.5.5 / minSdk 31。真机待验证：串流中按「重置」能按新设置重启、未插卡时提示文案正确。
