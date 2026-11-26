#!/usr/bin/env bash
# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

export SOURCE_DATE_EPOCH=1700000000
export TZ=UTC
export LC_ALL=C

repo="$(cd "$(dirname "$0")/.." && pwd)"
ncnn_src="$repo/external/ncnn"
work="${WORK_DIR:-/tmp/sheet-ncnn}"
jobs="${JOBS:-$(getconf _NPROCESSORS_ONLN)}"

die() { echo "ERROR: $*" >&2; exit 1; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum | cut -d' ' -f1; else shasum -a 256 | cut -d' ' -f1; fi; }
[ -f "$ncnn_src/CMakeLists.txt" ] || die "external/ncnn missing (git submodule update --init external/ncnn)"
cmake="${NCNN_CMAKE:-$(command -v cmake || true)}"
[ -n "$cmake" ] || die "cmake not found"
release_cmake="3.31.6"
cmake_version="$("$cmake" --version | awk '/version/{print $3; exit}')"
echo "cmake $cmake_version"
[ "$cmake_version" = "$release_cmake" ] || echo "note: releases are built with cmake $release_cmake; with $cmake_version the libraries work the same but differ byte for byte" >&2

src="$work/src"
rm -rf "$src"
mkdir -p "$src/ncnn" "$src/native"
for entry in CMakeLists.txt cmake src; do cp -R "$ncnn_src/$entry" "$src/ncnn/"; done
cp "$repo"/native/CMakeLists.txt "$repo"/native/ncnn_config.cmake "$repo"/native/layers.config "$repo"/native/ncnn_jni.cpp "$src/native/"

build() {
  local name="$1"; shift
  echo "=== $name (log: $work/$name.log)"
  rm -rf "$work/$name"
  { "$cmake" -S "$src/native" -B "$work/$name" -G "Unix Makefiles" -DCMAKE_BUILD_TYPE=Release -DNCNN_SOURCE_DIR="$src/ncnn" "$@" \
      && "$cmake" --build "$work/$name" --target sheet_ncnn --parallel "$jobs"; } >"$work/$name.log" 2>&1 \
    || { tail -n 40 "$work/$name.log" >&2; die "build failed for $name"; }
}

if [ "${1:-}" = "host" ]; then
  build host -DNCNN_OPENMP=OFF
  mkdir -p "$repo/eval/build/ncnn"
  lib="$(find "$work/host" -maxdepth 1 \( -name 'libsheet_ncnn.dylib' -o -name 'libsheet_ncnn.so' \) -print -quit)"
  [ -n "$lib" ] || die "host library not built"
  cp -f "$lib" "$repo/eval/build/ncnn/"
  ls -l "$repo/eval/build/ncnn"
  exit 0
fi

abis="${ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
[ -d "$sdk" ] || die "Android SDK not found (set ANDROID_HOME)"
ndk="${ANDROID_NDK_HOME:-$(find "$sdk/ndk" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n1)}"
[ -d "$ndk" ] || die "NDK not found (set ANDROID_NDK_HOME)"
echo "NDK $(sed -n 's/^Pkg.Revision *= *//p' "$ndk/source.properties")"
strip="$(find "$ndk/toolchains/llvm/prebuilt" -maxdepth 3 -name llvm-strip | head -n1)"
[ -x "$strip" ] || die "llvm-strip not found in the NDK"

flags="-g0 -ffile-prefix-map=$src=. -ffile-prefix-map=$work=. -ffile-prefix-map=$ndk=ndk"
for abi in $abis; do
  build "$abi" -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static \
    -DCMAKE_C_FLAGS="$flags" -DCMAKE_CXX_FLAGS="$flags"
  dest="$repo/app/src/main/jniLibs/$abi"
  mkdir -p "$dest"
  "$strip" --strip-all --remove-section=.comment --remove-section=.note.gnu.build-id \
    -o "$dest/libsheet_ncnn.so" "$work/$abi/libsheet_ncnn.so"
  echo "$abi: $(wc -c <"$dest/libsheet_ncnn.so" | tr -d ' ') bytes, sha256 $(sha256 <"$dest/libsheet_ncnn.so")"
done

mkdir -p "$repo/app/libs"
grep -v '^[[:space:]]*#' "$repo/native/layers.config" | sed 's/[[:space:]]*$//' | grep -v '^$' | tr '\n' ' ' | sha256 >"$repo/app/libs/ncnn.stamp"
echo "stamp: $(cat "$repo/app/libs/ncnn.stamp")"
