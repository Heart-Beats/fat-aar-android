#!/usr/bin/env sh
set -eu

NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [ -z "$NDK" ]; then
    printf '%s\n' 'Set ANDROID_NDK_ROOT or ANDROID_NDK_HOME to an Android NDK directory.' >&2
    exit 1
fi

if command -v cygpath >/dev/null 2>&1; then
    NDK="$(cygpath -u "$NDK")"
fi

PREBUILT_ROOT="$NDK/toolchains/llvm/prebuilt"
if [ ! -d "$PREBUILT_ROOT" ]; then
    printf 'Android NDK LLVM toolchain directory not found: %s\n' "$PREBUILT_ROOT" >&2
    exit 1
fi

TOOLCHAIN=""
for candidate in "$PREBUILT_ROOT"/*; do
    compiler="$candidate/bin/armv7a-linux-androideabi16-clang"
    if [ -x "$compiler" ]; then
        if [ -n "$TOOLCHAIN" ]; then
            printf '%s\n' 'Multiple NDK host toolchains contain armv7a-linux-androideabi16-clang.' >&2
            exit 1
        fi
        TOOLCHAIN="$candidate"
    fi
done

if [ -z "$TOOLCHAIN" ]; then
    printf 'No NDK host toolchain contains armv7a-linux-androideabi16-clang under: %s\n' "$PREBUILT_ROOT" >&2
    exit 1
fi

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
SOURCE="$SCRIPT_DIR/nested_embed_marker.c"
OUTPUT="$SCRIPT_DIR/../jniLibs/armeabi-v7a/libnested_embed_marker.so"

mkdir -p "$(dirname -- "$OUTPUT")"
"$TOOLCHAIN/bin/armv7a-linux-androideabi16-clang" -shared -fPIC -O2 -Wl,--build-id=none -o "$OUTPUT" "$SOURCE"

if [ ! -s "$OUTPUT" ]; then
    printf 'Compiler did not produce a non-empty output: %s\n' "$OUTPUT" >&2
    exit 1
fi

if command -v file >/dev/null 2>&1; then
    file "$OUTPUT"
fi
