#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
source_dir="$project_dir/app/src/main/cpp"
output_root="$project_dir/app/src/main/jniLibs"
build_root="${TMPDIR:-/data/data/com.termux/files/usr/tmp}/hchat-crash-native"
clang_bin=${CLANG:-clang}
ld_bin=${LD_LLD:-ld.lld}
strip_bin=${LLVM_STRIP:-llvm-strip}
readelf_bin=${LLVM_READELF:-llvm-readelf}
include_dir=${PREFIX:-/data/data/com.termux/files/usr}/include

rm -rf -- "$build_root"
mkdir -p "$build_root"

build_abi() {
  abi=$1
  target=$2
  build_dir="$build_root/$abi"
  output_dir="$output_root/$abi"
  mkdir -p "$build_dir" "$output_dir"

  "$clang_bin" --target="$target" -fPIC -ffreestanding -fno-builtin -fno-stack-protector \
    -Wall -Wextra -Werror -nostdlib -c "$source_dir/libc-stubs.c" -o "$build_dir/libc-stubs.o"
  "$ld_bin" -shared -soname libc.so "$build_dir/libc-stubs.o" -o "$build_dir/libc.so"

  "$clang_bin" --target="$target" -I"$include_dir" -fPIC -ffreestanding -fno-builtin \
    -fno-stack-protector -fvisibility=hidden -Wall -Wextra -Werror -nostdlib -c \
    "$source_dir/hchat_crash.c" -o "$build_dir/hchat_crash.o"
  "$ld_bin" -shared -z now -soname libhchat_crash.so --no-undefined \
    "$build_dir/hchat_crash.o" -L"$build_dir" --no-as-needed -lc \
    -o "$output_dir/libhchat_crash.so"
  "$strip_bin" --strip-unneeded "$output_dir/libhchat_crash.so"
  chmod 0644 "$output_dir/libhchat_crash.so"
  "$readelf_bin" -h "$output_dir/libhchat_crash.so" | sed -n '/Class:/p;/Machine:/p'
  "$readelf_bin" -d "$output_dir/libhchat_crash.so" | grep -q 'Shared library: \[libc.so\]'
  "$readelf_bin" -d "$output_dir/libhchat_crash.so" | grep -q 'BIND_NOW'
  if "$readelf_bin" -d "$output_dir/libhchat_crash.so" | grep -Eq 'RPATH|RUNPATH'; then
    printf '生成库包含本机 RPATH/RUNPATH: %s\n' "$output_dir/libhchat_crash.so" >&2
    exit 1
  fi
  "$readelf_bin" -Ws "$output_dir/libhchat_crash.so" | grep -q \
    'Java_h_Hchat_crash_NativeCrashBridge_nativeInstall'
}

build_abi arm64-v8a aarch64-linux-android27
build_abi armeabi-v7a armv7a-linux-androideabi27

printf 'Native 崩溃捕获库已生成：\n'
ls -lh "$output_root/arm64-v8a/libhchat_crash.so" \
  "$output_root/armeabi-v7a/libhchat_crash.so"
