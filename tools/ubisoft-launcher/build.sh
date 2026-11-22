#!/usr/bin/env bash
# Builds droiddeck-upc.exe for Wine and stages it with the guest scripts.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=${1:-"$here/../../app/src/main/assets/linuxfs/usr/local/lib/droiddeck-ubisoft"}
target=x86_64-pc-windows-gnu
# mingw 的 ld 默认把当前时间写进 PE 头的 TimeDateStamp，同源码每次编出来的
# exe 字节都不一样，包 sha 跟着变，已经发出去的 APK 里钉的 sha 就 404 了。
# 关掉时间戳写入，让构建可复现。
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,--no-insert-timestamp"
rustup target add "$target" >/dev/null 2>&1 || true
cargo build --manifest-path "$here/Cargo.toml" --release --locked --target "$target"
mkdir -p "$out"
install -m644 "${CARGO_TARGET_DIR:-$here/target}/$target/release/droiddeck-upc.exe" "$out/droiddeck-upc.exe"
