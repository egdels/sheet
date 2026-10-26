#!/usr/bin/env bash
# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

export SOURCE_DATE_EPOCH=1700000000
export TZ=UTC

repo="$(cd "$(dirname "$0")/.." && pwd)"
ort_src="$repo/external/onnxruntime"
work="${WORK_DIR:-${TMPDIR:-/tmp}/sheet-onnxruntime}"
abis="${ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"
jobs="${JOBS:-$(getconf _NPROCESSORS_ONLN)}"

die() { echo "ERROR: $*" >&2; exit 1; }
[ -f "$ort_src/tools/ci_build/build.py" ] || die "external/onnxruntime missing (git submodule update --init --recursive)"
command -v ninja >/dev/null || die "ninja not found"
python3 -c "import flatbuffers" 2>/dev/null || die "python3 lacks flatbuffers (pip install flatbuffers)"
cmake="${ORT_CMAKE:-$(command -v cmake || true)}"
[ -n "$cmake" ] || die "cmake not found"
cmake_version="$("$cmake" --version | awk '/version/{print $3; exit}')"
[ "$(printf '%s\n3.28.0\n' "$cmake_version" | sort -V | head -n1)" = "3.28.0" ] || die "cmake $cmake_version < 3.28"

sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
[ -d "$sdk" ] || die "Android SDK not found (set ANDROID_HOME)"
ndk="${ANDROID_NDK_HOME:-$(find "$sdk/ndk" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n1)}"
[ -d "$ndk" ] || die "NDK not found (set ANDROID_NDK_HOME)"
host_tag="$(uname | tr '[:upper:]' '[:lower:]')-x86_64"
strip="$ndk/toolchains/llvm/prebuilt/$host_tag/bin/llvm-strip"

mkdir -p "$work"
ops="$work/required_operators.config"
python3 - "$ops" "$repo"/ort/*.config <<'PY'
import sys
merged = {}
for path in sys.argv[2:]:
    for line in open(path):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        domain, opset, names = line.split(";")
        merged.setdefault((domain, int(opset)), set()).update(n.strip() for n in names.split(",") if n.strip())
with open(sys.argv[1], "w") as out:
    for (domain, opset), names in sorted(merged.items()):
        out.write(f"{domain};{opset};{','.join(sorted(names))}\n")
PY
echo "operators: $ops"; cat "$ops"

src="$work/src"
rm -rf "$src"
mkdir -p "$src"
cp -a "$ort_src/." "$src/"
cp -f "$repo/external/onnxruntime_pinned/build-android.gradle" "$src/java/build-android.gradle"
cp -f "$repo/external/onnxruntime_pinned/settings-android.gradle" "$src/java/settings-android.gradle"
cp -f "$repo/external/onnxruntime_pinned/gradle-wrapper.properties" "$src/java/gradle/wrapper/gradle-wrapper.properties"

flags="-g0 -fdebug-prefix-map=$src=. -fmacro-prefix-map=$src=. -ffile-prefix-map=$src=."
ldflags="-Wl,--build-id=none -Wl,-z,max-page-size=16384"

for abi in $abis; do
  out="$work/$abi"
  rm -rf "$out"
  mkdir -p "$out"
  echo "=== $abi (log: $out/build.log)"
  (cd "$src" && python3 tools/ci_build/build.py \
    --build_dir "$out" --config Release --parallel "$jobs" \
    --build_shared_lib --build_java \
    --android --android_sdk_path "$sdk" --android_ndk_path "$ndk" --android_api 29 --android_abi "$abi" \
    --minimal_build extended --include_ops_by_config "$ops" \
    --skip_tests --skip_onnx_tests --skip_submodule_sync --compile_no_warning_as_error \
    --use_full_protobuf --cmake_generator Ninja --cmake_path "$cmake" \
    --cmake_extra_defines \
      onnxruntime_DISABLE_ML_OPS=ON onnxruntime_DISABLE_RTTI=ON onnxruntime_DISABLE_EXCEPTIONS=ON \
      onnxruntime_DISABLE_FLOAT8_TYPES=ON onnxruntime_USE_XNNPACK=OFF onnxruntime_USE_KLEIDIAI=OFF \
      onnxruntime_USE_MIMALLOC=OFF onnxruntime_ENABLE_LTO=OFF \
      onnxruntime_BUILD_UNIT_TESTS=OFF onnxruntime_BUILD_SHARED_LIB_TESTS=OFF onnxruntime_BUILD_BENCHMARKS=OFF \
      "CMAKE_C_FLAGS=$flags" "CMAKE_CXX_FLAGS=$flags" \
      "CMAKE_SHARED_LINKER_FLAGS=$ldflags" "CMAKE_EXE_LINKER_FLAGS=$ldflags" \
    >"$out/build.log" 2>&1) || { tail -n 40 "$out/build.log" >&2; die "build failed for $abi"; }

  dest="$repo/app/src/main/jniLibs/$abi"
  mkdir -p "$dest"
  for lib in libonnxruntime.so libonnxruntime4j_jni.so; do
    found="$(find "$out/Release" -name "$lib" -type f -print -quit)"
    [ -n "$found" ] || die "$lib not built for $abi"
    cp -f "$found" "$dest/$lib"
    [ -x "$strip" ] && "$strip" --strip-all --remove-section=.comment --remove-section=.note \
      --remove-section=.note.gnu.build-id --remove-section=.note.gnu.property "$dest/$lib"
  done
  ls -l "$dest"
done

jar="$(find "$src/java/build/libs" -name 'onnxruntime*.jar' ! -name '*sources*' ! -name '*javadoc*' -print -quit 2>/dev/null || true)"
[ -n "$jar" ] || die "onnxruntime jar not built"
mkdir -p "$repo/app/libs"
cp -f "$jar" "$repo/app/libs/onnxruntime.jar"
zip -q -d "$repo/app/libs/onnxruntime.jar" 'ai/onnxruntime/native/*' || true
ls -l "$repo/app/libs/onnxruntime.jar"
