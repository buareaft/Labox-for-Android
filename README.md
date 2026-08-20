# Labox VM

Labox 是一个使用 Kotlin 与 Jetpack Compose 构建的 Android Windows 虚拟机前端。工程已包含响应式虚拟机控制界面、镜像选择、硬件配置，以及 v86/QEMU 双运行时边界。

## 当前可用功能

- 在 v86 与 QEMU 之间切换
- 通过 Android Storage Access Framework 持久选择 ISO、IMG、QCOW2 或 VHD 文件
- 配置 512 MB 至 8 GB 内存和 1 至 8 个 CPU 核心
- 启动、暂停、恢复和停止状态管理
- 横屏/平板双栏布局与手机竖屏布局
- QEMU 模式下可选择 CPU、芯片组、硬盘控制器、显卡、声卡、网卡、固件、USB、指针、启动顺序、RTC 和 TPM 2.0

## QEMU 硬件配置

QEMU 高级硬件面板只在选择 QEMU 时显示。Windows 版本预设会自动选择一组兼容性优先的设备：

| 系统 | 芯片组 | 显卡 | 硬盘 | 网卡 |
| --- | --- | --- | --- | --- |
| Windows 98 / ME | i440FX | Cirrus | IDE | NE2000 PCI |
| Windows 2000 / XP | i440FX | 标准 VGA | IDE | RTL8139 |
| Windows 7 / 8.1 | Q35 | 标准 VGA | SATA | E1000 |
| Windows 10 / 11 | Q35 | 标准 VGA | SATA | E1000 |

界面选项对应 QEMU 官方参数：`-machine`、`-cpu`、`-vga`、`-device`、`-netdev`、`-boot` 和 `-rtc`。VirtIO、QXL、VMware SVGA 和 TPM 2.0 需要相应的来宾驱动或原生后端支持。QEMU 的设备列表会随版本和编译选项变化，JNI 层启动前应使用 `-machine help`、`-cpu help` 和 `-device help` 做一次能力校验。

## 接入 v86

v86 运行时已内置（`app/src/main/assets/v86/`）：

```text
app/src/main/assets/v86/index.html
app/src/main/assets/v86/libv86.js
app/src/main/assets/v86/v86.wasm
app/src/main/assets/v86/seabios.bin
app/src/main/assets/v86/vgabios.bin
```

v86 模式已可真正启动虚拟机：选择 Windows 98/XP 预设和 ISO 镜像后点击启动，
应用会打开 `V86Activity`（WebView），通过应用内本地 HTTP 服务器
（`V86HttpServer`，仅监听 127.0.0.1 回环）提供 v86 运行时，并以流 + Range
方式把用户选择的光盘/磁盘镜像挂载给虚拟机，无需把大镜像复制进应用。
界面提供暂停/继续、软键盘、停止控制。

已验证：选择 FreeDOS 1.3 的 ISO 镜像可在模拟器中完成 BIOS 引导。

实现要点：

- 使用新版 v86 API：全局类是 `V86`（不是旧版的 `V86Starter`），
  配置项为 `wasm_path`、`screen_container`、`bios`、`vga_bios`、`cdrom`/`hda`。
- 必须允许明文流量（`usesCleartextTraffic`），因为本地 HTTP 服务器不是 HTTPS。
- 不使用 `WebViewAssetLoader`：在部分系统/WebView 上 `appassets.androidplatform.net`
  域会阻止内联脚本执行，本地 HTTP 服务是 v86 类应用的标准做法。

已知限制：

- v86 最大可用内存按 512 MB 封顶（v86 为浏览器环境设计，超过容易崩溃）。
- WebView 手写输入依赖软键盘文本框，体验有限；键盘映射为 PS/2 扫描码，与
  Windows 98/XP 兼容。
- Windows 98/XP 安装镜像（ISO）需要用户自行准备。

## 接入 QEMU

QEMU 5.1.0 核心库已内置（来自 Limbo v6.0.1 官方预编译 APK，
`app/src/main/jniLibs/<abi>/`）：

```text
libqemu-system-x86_64.so   QEMU 核心
libglib-2.0.so             依赖
libpixman-1.so             依赖
libSDL2.so                 显示/输入适配（Limbo 定制版）
libcompat-*.so             musl/limbo 兼容层
liblimbo.so                Limbo 主库
```

已覆盖 `arm64-v8a`（真机）与 `x86_64`（模拟器）。Kotlin JNI 边界位于
`QemuRuntime`，原生桥接层 `liblabox_qemu.so` 由 `app/src/main/cpp/qemu_bridge.cpp`
编译（`gradlew assembleDebug -PenableQemuBridge=true`），运行时动态加载
Limbo 导出的 `qemu_init` / `qemu_main_loop` / `qemu_cleanup` /
`qemu_system_shutdown_request` / `qmp_stop` / `qmp_cont` 接口。

已验证：桥接层与 QEMU 核心库在模拟器上成功加载
（`QemuRuntime.isAvailable()` 返回 true）。

待完成：Limbo 的 SDL 显示层不能直接绑定 Compose 的 `Surface`。启动 QEMU
需要先用 SDL 建立窗口（或改为 VNC 输出），再接入 Compose 显示。这是
QEMU 路线"真正开机"的最后一环。

### JNI 桥接层

工程已经提供 `app/src/main/cpp/qemu_bridge.cpp`。它生成 `liblabox_qemu.so`，并动态加载同一 ABI 目录下的 `libqemu-system-x86_64.so`。默认构建不会要求安装 NDK；安装 Android NDK 和 CMake 后使用：

```powershell
.\gradlew.bat assembleDebug -PenableQemuBridge=true
```

QEMU 核心库需要导出以下稳定适配接口：

```cpp
extern "C" int labox_qemu_start(
    int disk_fd,
    int memory_mb,
    int cpu_cores,
    int argc,
    const char* const* argv
);
extern "C" void labox_qemu_pause();
extern "C" void labox_qemu_resume();
extern "C" void labox_qemu_stop();
extern "C" void labox_qemu_set_surface(ANativeWindow* window);
```

核心库必须复制传入的参数字符串，并在 `labox_qemu_start` 返回前复制或 `dup()` 磁盘文件描述符。桥接层只有在核心库及全部适配符号存在时才向 Kotlin 报告 QEMU 可用。

桥接层也兼容 Limbo 6 原生 QEMU 导出的 `qemu_init`、`qemu_main_loop`、
`qemu_cleanup`、`qemu_system_shutdown_request`、`qmp_stop` 和 `qmp_cont`。
完成 Limbo 原生构建后，可批量导入核心及依赖：

```powershell
.\tools\import-limbo-qemu.ps1 -LimboRoot C:\path\to\limbo -Abi @("arm64-v8a", "x86_64")
```

QEMU 原生构建需要 Linux 环境（WSL 或真实 Linux 主机）。如需从源码自行
交叉编译（而不是用内置的预编译核心），执行 `tools/build-qemu.sh arm64-v8a`。
其 SDL 显示层不能直接绑定 Compose 的 `Surface`；Labox 将使用仅监听 Android
本机回环地址的 QEMU VNC 输出接入 Compose，不使用 WebView，也不会把 VNC
端口暴露到局域网。

## 构建

```powershell
.\gradlew.bat assembleDebug
```

带 QEMU 桥接层（生成 liblabox_qemu.so）：

```powershell
.\gradlew.bat assembleDebug -PenableQemuBridge=true
```

调试 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## Release 构建与签名

Release APK 使用 JKS 签名。签名凭据通过 `local.properties` 提供（该文件与
keystore 均不入库，见 `.gitignore`）。首次构建前：

```powershell
keytool -genkeypair -v -keystore keystore\labox-release.jks -alias labox `
  -keyalg RSA -keysize 2048 -validity 10000
```

然后在 `local.properties` 追加（切勿提交）：

```properties
storeFile=keystore/labox-release.jks
storePassword=<密码>
keyAlias=labox
keyPassword=<密码>
```

生成签名 APK：

```powershell
.\gradlew.bat assembleRelease
```

输出到 `app/build/outputs/apk/release/app-release.apk`，可用以下命令验证签名：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk
```
