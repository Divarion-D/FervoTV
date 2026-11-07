# Media3 FFmpeg audio decoder

`media3-decoder-ffmpeg-1.5.1.aar` is built from the official AndroidX Media3 `1.5.1` tag and FFmpeg `n6.0` tag by `make ffmpeg-audio`.

Verified AAR SHA-256: `e7c1feda07ae6ee3f00e881daceb5952a8a577e9a5cdddfa0e153aa42e72394a`.

The build enables only the LGPL FFmpeg audio decoders required by PLR-012: `dca` (DTS/DTS-HD), `truehd`, `mlp`, `ac3` and `eac3`. GPL and nonfree components, encoders, demuxers, programs and video decoders are disabled. The AAR contains `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`, targets API 21 and uses 16 KiB ELF page alignment.

Requirements: Linux, JDK 17, Android SDK CMake 3.22.1 (including Ninja) and Android NDK r26b (`26.1.10909125`). Set `ANDROID_NDK_HOME` or `ANDROID_CMAKE_HOME` when they are not installed under `$ANDROID_HOME`.

The Media3 wrapper is Apache-2.0. FFmpeg is LGPL-2.1-or-later; its exact source tag, build inputs and full licence are recorded by the build script and `core/src/main/assets/LICENSES.md`.
