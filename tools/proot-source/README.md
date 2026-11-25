# Standard PRoot and talloc source

This directory is the standalone corresponding source for the Standard Android PRoot program, its ARM64 and ARM EABI loaders, and Samba talloc built by DSHA. It includes the original source archives, the complete build script, the Android talloc configuration, the exact link-time shared-memory package and its source. Obtain the pinned Android NDK r26d (26.3.11579264) from [Google's NDK archive](https://developer.android.com/ndk/downloads/older_releases). The local verification used Windows x64 and Python 3.11.

```powershell
python -B build.py --ndk F:/DSHA_RESTART/toolchains/current/android-sdk/ndk/26.3.11579264 --output rebuilt
Get-ChildItem rebuilt -File | Get-FileHash -Algorithm SHA256
```

Use a new output directory. The other inputs are included and the command works offline. DSHA places the four files from that directory under `app/src/main/jniLibs/arm64-v8a/`. Android extracts them with the APK. The app runs `libproot.so` with the absolute paths of the two loaders in `PROOT_LOADER` and `PROOT_LOADER_32`, and copies talloc to `libtalloc.so.2` on its private library path. The executable links dynamically to talloc and Termux libandroid-shmem 0.7. The compiler targets API 26. The app's minimum API is 30. The script sets a 16 KiB load segment alignment for all four files.

PRoot comes from Termux v5.1.107.91, commit `61681c6481197e3c0cec6726075053adb740f235`. The source archive includes upstream notices and COPYING. The DSHA script removes the named-file-descriptor assertion in `src/path/canon.c` and supplies the string declarations for the ashmem translation unit. The original DSHA build documented the named-fd fix. The recipe records both changes and the tools and flags used. [PRoot source](https://github.com/termux/proot/tree/61681c6481197e3c0cec6726075053adb740f235) · [Upstream package recipe and source checksum](https://github.com/termux/termux-packages/blob/8ec1d2071728ab5f79fd509d089892cddb295f36/packages/proot/build.sh) · [Original DSHA build notes](https://github.com/DSH-APP/DSHA/blob/c7bde50aab6adb28a45f8a5491d00e008c3b0db7/BUILD.md)

Samba talloc 2.4.3 is built directly from the original `talloc.c` and `talloc.h` with DSHA's Bionic `replace.h`. The source identifies its library license as LGPL-3.0-or-later and names Andrew Tridgell and Stefan Metzmacher. The source archive includes the upstream LICENSE. [Samba source archive](https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz)

The Termux binary package supplies the link-time libandroid-shmem object and its header. Its BSD-3-Clause copyright text is inside the package and the source archive. [Termux 0.7 source](https://github.com/termux/libandroid-shmem/tree/7f0bd7e25dbdd146265aff7c6a890029e374622d) · [Original ARM64 package](https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb)
