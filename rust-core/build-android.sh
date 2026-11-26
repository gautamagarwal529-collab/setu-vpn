#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 android-ndk-directory output-directory" >&2
    exit 2
fi
toolchain="$(realpath "$1")/toolchains/llvm/prebuilt"
output="$(realpath -m "$2")"
manifest="$(dirname "$(realpath "$0")")/Cargo.toml"
case "$(uname -s)" in
    Linux) host=linux-x86_64 ;;
    Darwin) host=darwin-x86_64 ;;
    *) echo "Use the Android NDK on Linux or macOS." >&2; exit 2 ;;
esac
bin="$toolchain/$host/bin"
if [[ ! -x "$bin/aarch64-linux-android26-clang" ]]; then
    echo "Android NDK linker unavailable at $bin" >&2
    exit 2
fi
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$(dirname "$manifest")/target}"
for mapping in "arm64-v8a aarch64-linux-android aarch64-linux-android" \
    "armeabi-v7a armv7-linux-androideabi armv7a-linux-androideabi" \
    "x86 i686-linux-android i686-linux-android" \
    "x86_64 x86_64-linux-android x86_64-linux-android"; do
    read -r abi target clang_target <<< "$mapping"
    linker_env="CARGO_TARGET_${target^^}_LINKER"
    linker_env="${linker_env//-/_}"
    env "$linker_env=$bin/${clang_target}26-clang" \
        cargo build --locked --manifest-path "$manifest" --target "$target" --release
    install -D -m 0644 "$CARGO_TARGET_DIR/$target/release/libsetu_core.so" \
        "$output/$abi/libsetu_core.so"
done
