package com.qianyuzhou.keepgen;

/**
 * 构建期时间戳。由 gen_buildstamp.sh 在每次出包时写入，
 * 手改源码不会改变它 —— 要改只能重新编译并重新签名。
 *
 * 60 天有效期 = BUILD_EPOCH_MS + 60 天，是绝对时间点，
 * 不依赖安装时间、首次启动时间，也不吃「清应用数据」这套。
 */
final class BuildStamp {
    static final long BUILD_EPOCH_MS = 1795454516003L;
    static final String BUILD_UTC = "2026-11-23T17:21:56Z";
    private BuildStamp() {}
}
