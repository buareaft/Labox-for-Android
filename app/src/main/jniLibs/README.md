# QEMU Android native libraries

Each ABI directory must contain `libqemu-system-x86_64.so` and all of its
Limbo-built shared-library dependencies. Do not place desktop Linux libraries
here; Android can only load libraries built with the Android NDK.

Import a completed Limbo native build with:

```powershell
.\tools\import-limbo-qemu.ps1 -LimboRoot C:\path\to\limbo -Abi @("arm64-v8a", "x86_64")
```

The generated `.so` files are intentionally not committed to source control.
