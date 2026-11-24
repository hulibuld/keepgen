#!/bin/bash
# 生成 BuildStamp.java：把「此刻」作为本版的构建时间烧进代码。
# 60 天期限从这个时间点算，重装、清数据、换设备都改不了。
# 每次出包必须重新生成，否则会沿用上次的时间。
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
NOW_MS=$(node -e 'console.log(Date.now())')
NOW_STR=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
cat > "$HERE/src/com/keepgen/hulibuld/BuildStamp.java" <<EOF
package com.keepgen.hulibuld;

/**
 * 构建期时间戳。由 gen_buildstamp.sh 在每次出包时写入，
 * 手改源码不会改变它 —— 要改只能重新编译并重新签名。
 *
 * 60 天有效期 = BUILD_EPOCH_MS + 60 天，是绝对时间点，
 * 不依赖安装时间、首次启动时间，也不吃「清应用数据」这套。
 */
final class BuildStamp {
    static final long BUILD_EPOCH_MS = ${NOW_MS}L;
    static final String BUILD_UTC = "${NOW_STR}";
    private BuildStamp() {}
}
EOF
echo "BuildStamp -> $NOW_STR ($NOW_MS)"
