#!/usr/bin/env bash
# Labox QEMU 核心构建脚本
# 在 Linux 或 WSL 中运行：android 的 QEMU 构建无法在纯 Windows 环境下完成。
#
# 依赖：
#   - Ubuntu/Debian 或类似 Linux 发行版（WSL: wsl --install -d Ubuntu）
#   - Android NDK（本机已装 27.3.13750724，路径见下方 NDK_ROOT）
#   - git, make, gcc, python3, ninja-build, pkg-config, libglib2.0-dev,
#     libpixman-1-dev, zlib1g-dev（Limbo 会自带大部分，构建时按报错补装）
#
# 原理：直接编译官方 QEMU 到 Android 需要大量移植补丁，业界成熟做法是
# 基于 Limbo 的 QEMU 分支（limbo-android-x86）构建，它已包含 Android 所需
# 的显示/输入/内存适配。本脚本负责拉取、配置、交叉编译并产出 .so。
#
# 用法：
#   ./tools/build-qemu.sh arm64-v8a
#   ./tools/build-qemu.sh x86_64
#   ./tools/build-qemu.sh all

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${PROJECT_ROOT}/app/src/main/jniLibs"
WORK_DIR="${PROJECT_ROOT}/tools/.qemu-build"
LIMBO_URL="https://github.com/limboemu/limbo.git"
LIMBO_BRANCH="android"

# 本机 NDK 路径，按需修改
NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/27.3.13750724}"

case "${1:-all}" in
  arm64-v8a) TARGET_ABI="arm64-v8a"; ANDROID_ARCH="arm64"; QEMU_ARCH="aarch64";;
  x86_64)    TARGET_ABI="x86_64";    ANDROID_ARCH="x86_64"; QEMU_ARCH="x86_64";;
  all)       TARGET_ABI="all";;
  *) echo "未知 ABI: $1（可选 arm64-v8a / x86_64 / all）" >&2; exit 1;;
esac

require() { command -v "$1" >/dev/null 2>&1 || { echo "缺少依赖: $1" >&2; exit 1; }; }
require git; require make; require ninja; require pkg-config

echo "=== 1/4 准备 QEMU/Limbo 源码 ==="
mkdir -p "${WORK_DIR}"
if [ ! -d "${WORK_DIR}/limbo" ]; then
  git clone --depth 1 -b "${LIMBO_BRANCH}" "${LIMBO_URL}" "${WORK_DIR}/limbo"
fi
cd "${WORK_DIR}/limbo"

echo "=== 2/4 配置 NDK 工具链 ==="
TOOLCHAIN="${NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "${TOOLCHAIN}" ] || { echo "NDK 工具链不存在: ${TOOLCHAIN}" >&2; exit 1; }
export PATH="${TOOLCHAIN}/bin:${PATH}"

build_one() {
  local abi="$1" arch="$2" qemu_arch="$3"
  echo "=== 3/4 编译 ${abi} (${qemu_arch}) ==="
  local build_dir="${WORK_DIR}/build-${abi}"
  mkdir -p "${build_dir}"
  cd "${build_dir}"

  # Limbo 的 configure 脚本位于其 QEMU 源码树内，不同分支参数略有差异；
  # 核心是 --cross-prefix 指向 NDK 编译器前缀。如分支路径不同请按实际调整。
  local qemu_src="${WORK_DIR}/limbo/limbo-android-x86"
  [ -f "${qemu_src}/configure" ] || { echo "找不到 QEMU configure: ${qemu_src}" >&2; exit 1; }

  "${qemu_src}/configure" \
    --cross-prefix="${arch}-linux-android-" \
    --target-list="x86_64-softmmu" \
    --enable-system \
    --disable-werror \
    --disable-sdl \
    --disable-gtk \
    --disable-curses \
    --disable-vnc-jpeg \
    --disable-vnc-png \
    --disable-vnc-sasl \
    --disable-vnc \
    --disable-kvm \
    --disable-xen \
    --disable-hax \
    --disable-hvf \
    --disable-tcg-interpreter \
    --disable-debug-tcg \
    --disable-debug \
    --disable-tests \
    --disable-docs \
    --enable-vnc-tight=no \
    --python=python3

  make -j"$(nproc)" qemu-system-x86_64

  echo "=== 4/4 安装 ${abi} ==="
  local dest="${OUT_DIR}/${abi}"
  mkdir -p "${dest}"
  cp qemu-system-x86_64 "${dest}/libqemu-system-x86_64.so"
  echo "完成: ${dest}/libqemu-system-x86_64.so"
  cd "${WORK_DIR}"
}

if [ "${TARGET_ABI}" = "all" ]; then
  build_one arm64-v8a aarch64 aarch64
  build_one x86_64 x86_64 x86_64
else
  build_one "${TARGET_ABI}" "${ANDROID_ARCH}" "${QEMU_ARCH}"
fi

echo ""
echo "QEMU 核心构建完成。"
echo "下一步：将 Limbo 依赖（libglib-2.0.so、libpixman-1.so 等）放入 jniLibs，"
echo "或运行 tools/import-limbo-qemu.ps1 导入整个 Limbo 构建产物。"
echo "之后用以下命令打包 APK："
echo "  .\\gradlew.bat assembleDebug -PenableQemuBridge=true"
