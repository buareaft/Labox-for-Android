# Labox

在 Android 手机上运行 Windows 的虚拟机应用，界面全中文。引擎有两个：v86（WebAssembly 模拟）和 QEMU（完整硬件模拟）。

主要目标是 Windows 98 / XP 这类老系统，跑旧软件、旧游戏。系统镜像需要自己准备。

## 功能

- 两种引擎自由切换：v86 轻量，适合老系统；QEMU 完整模拟硬件
- ISO / IMG / QCOW2 / VHD 镜像，通过系统文件选择器导入
- 支持创建虚拟硬盘，可以像 VMware 一样把系统装进虚拟盘
- 内存 512MB - 8GB、CPU 1 - 8 核可调（v86 上限 512MB）
- QEMU 原生 SDL Surface 显示，不经过 VNC 编码/传输/解码；不兼容设备自动回退 VNC
- 暂停/继续、截图、全屏、软键盘
- v86 带快捷键面板（F1-F12、Ctrl+Alt+Del、Alt+Tab、Win 等，安全模式引导按 F8）
- 手机竖屏单栏、平板横屏双栏布局

## 用法

1. 准备一个 Windows 98 / XP 的 ISO 镜像（Windows 2000 / XP 或更早的老系统）
2. 打开应用，选择镜像文件
3. 选 v86 或 QEMU 引擎，点启动

v86 适合 XP 及更早的系统，启动快、吃资源少。QEMU 适合装 Win7 及以上的系统，对老镜像兼容性更好，也能调更多硬件（CPU、芯片组、显卡、网卡等）。

## 已知限制

- v86 内存上限 512MB，跑不动太大的系统，WASM 首次编译在低端设备上会比较慢
- 镜像需要自己准备，应用不提供任何系统下载
- QEMU 的 VirtIO、QXL、TPM 2.0 等选项需要对应的客户机驱动

## 致谢

- [v86](https://github.com/copy/v86) —— WebAssembly x86 模拟器
- [QEMU](https://www.qemu.org/) —— 硬件模拟引擎
- [Limbo](https://github.com/limboemu/limbo) —— Android 上的 QEMU 前端，工程参考了它的预编译库

QEMU 原生显示使用 Limbo Emulator 6.0.1 / QEMU 5.1.0 的 GPL-2.0 原生组件，
对应许可证随 APK 位于 `assets/licenses/limbo-gpl-2.0.txt`。
