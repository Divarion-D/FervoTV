#!/usr/bin/env bash
# Rebuild the checked-in PLR-012 AAR from the exact upstream Media3 and FFmpeg releases.
set -euo pipefail

readonly MEDIA3_TAG=1.5.1
readonly MEDIA3_SHA=76088cd6af7f263aba238b7a48d64bd4f060cb8b
readonly FFMPEG_TAG=n6.0
readonly FFMPEG_SHA=ea3d24bbe3c58b171e55fe2151fc7ffaca3ab3d2
readonly NDK_VERSION=26.1.10909125
readonly CMAKE_VERSION=3.22.1
readonly ROOT="$(cd "$(dirname "$0")/.." && pwd)"
readonly SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
readonly NDK="${ANDROID_NDK_HOME:-$SDK/ndk/$NDK_VERSION}"
readonly CMAKE="${ANDROID_CMAKE_HOME:-$SDK/cmake/$CMAKE_VERSION}"
readonly OUTPUT="$ROOT/third_party/media3-ffmpeg/media3-decoder-ffmpeg-$MEDIA3_TAG.aar"

[[ -x "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]] || {
    echo "Android NDK r26b ($NDK_VERSION) not found. Set ANDROID_NDK_HOME." >&2
    exit 1
}
grep -q "Pkg.Revision = $NDK_VERSION" "$NDK/source.properties" || { echo "NDK must be r26b ($NDK_VERSION)." >&2; exit 1; }
[[ -x "$CMAKE/bin/cmake" && -x "$CMAKE/bin/ninja" ]] || { echo "Android SDK CMake $CMAKE_VERSION with Ninja not found. Set ANDROID_CMAKE_HOME." >&2; exit 1; }

work="$(mktemp -d "${TMPDIR:-/tmp}/fervotv-ffmpeg.XXXXXX")"
trap 'rm -rf "$work"' EXIT
git clone --quiet --depth 1 --branch "$MEDIA3_TAG" https://github.com/androidx/media.git "$work/media"
[[ "$(git -C "$work/media" rev-parse HEAD)" == "$MEDIA3_SHA" ]] || { echo "Unexpected Media3 commit" >&2; exit 1; }

ffmpeg="$work/media/libraries/decoder_ffmpeg/src/main/jni/ffmpeg"
git clone --quiet --depth 1 --branch "$FFMPEG_TAG" https://github.com/FFmpeg/FFmpeg.git "$ffmpeg"
[[ "$(git -C "$ffmpeg" rev-parse HEAD)" == "$FFMPEG_SHA" ]] || { echo "Unexpected FFmpeg commit" >&2; exit 1; }

module="$work/media/libraries/decoder_ffmpeg"
sed -i 's/^JOBS=.*/JOBS="${FFMPEG_JOBS:-2}"/' "$module/src/main/jni/build_ffmpeg.sh"
sed -i "/namespace 'androidx.media3.decoder.ffmpeg'/a\\    ndkVersion '$NDK_VERSION'\n    ndkPath '$NDK'" "$module/build.gradle"
FFMPEG_JOBS="${FFMPEG_JOBS:-2}" "$module/src/main/jni/build_ffmpeg.sh" \
    "$module/src/main" "$NDK" linux-x86_64 21 dca truehd mlp ac3 eac3

printf 'sdk.dir=%s\ncmake.dir=%s\n' "$SDK" "$CMAKE" > "$work/media/local.properties"
(
    cd "$work/media"
    GRADLE_USER_HOME="${GRADLE_USER_HOME:-$work/gradle}" ./gradlew --no-daemon --max-workers=2 -Dkotlin.compiler.execution.strategy=in-process \
        :lib-decoder-ffmpeg:assembleRelease
)
aar="$(find "$module" -type f -name '*release.aar' -print -quit)"
[[ -n "$aar" ]] || { echo "Media3 FFmpeg AAR was not produced" >&2; exit 1; }
mkdir -p "$(dirname "$OUTPUT")"
cp "$aar" "$OUTPUT"
sha256sum "$OUTPUT"
