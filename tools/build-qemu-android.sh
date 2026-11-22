#!/usr/bin/env bash
# Cross-builds the Apple-SoC QEMU fork for Android arm64:
#   qemu-system-aarch64 -> app/src/main/jniLibs/arm64-v8a/libqemu.so
#   qemu-img            -> app/src/main/jniLibs/arm64-v8a/libqemuimg.so
# Prereq: DEPS_PREFIX holds *static* cross-built glib, pixman, pcre2, libffi, zlib (+ .pc files)
# so the executables are self-contained. A native aarch64 build (Termux / arm64 Linux box)
# is the lower-friction alternative: build there, copy the two binaries into jniLibs.
# Apply qemu-patches/ first (see qemu-patches/README.md).
set -euo pipefail
: "${ANDROID_NDK_HOME:?NDK r29+}"; : "${QEMU_SRC:?qemu fork checkout}"; : "${DEPS_PREFIX:?static deps prefix}"
API="${API:-33}"; HOST_TAG="${HOST_TAG:-linux-x86_64}"
TC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"; T=aarch64-linux-android
OUT="${OUT:-$PWD/out/qemu}"; JNILIBS="${JNILIBS:-$PWD/app/src/main/jniLibs/arm64-v8a}"
mkdir -p "$OUT/bin" "$OUT/build" "$JNILIBS"

# QEMU's --cross-prefix expects binutils-style names; NDK ships llvm-* only.
for t in ar ranlib strip nm objcopy objdump; do ln -sf "$TC/bin/llvm-$t" "$OUT/bin/$T-$t"; done
ln -sf "$TC/bin/ld.lld" "$OUT/bin/$T-ld"

export PKG_CONFIG_LIBDIR="$DEPS_PREFIX/lib/pkgconfig:$DEPS_PREFIX/share/pkgconfig"
export PKG_CONFIG_PATH=""
CC="$TC/bin/${T}${API}-clang"; CXX="$TC/bin/${T}${API}-clang++"

cd "$OUT/build"
"$QEMU_SRC/configure" \
  --cross-prefix="$OUT/bin/$T-" --cc="$CC" --cxx="$CXX" --host-cc=cc \
  --target-list=aarch64-softmmu \
  --extra-cflags="-O2 -fno-emulated-tls -fPIE -I$DEPS_PREFIX/include" \
  --extra-ldflags="-pie -L$DEPS_PREFIX/lib -Wl,-z,max-page-size=16384" \
  --enable-tcg --disable-kvm --disable-docs --disable-guest-agent --disable-werror \
  --disable-gtk --disable-sdl --disable-opengl --disable-spice --disable-user \
  --disable-linux-user --disable-bsd-user
# Flag names drift between QEMU versions/forks: check ./configure --help on yours.
# The fork's USB-over-TCP device (usb-tcp-remote) is required by the helper VM: keep it enabled.
make -j"$(nproc)" qemu-system-aarch64 qemu-img

"$OUT/bin/$T-strip" -o "$JNILIBS/libqemu.so"    qemu-system-aarch64
"$OUT/bin/$T-strip" -o "$JNILIBS/libqemuimg.so" qemu-img
echo "installed -> $JNILIBS"
