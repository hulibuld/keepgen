package com.keepgen.hulibuld;

import android.content.Context;

/**
 * 授权校验的**分散副本**。
 *
 * ── 这个类是干什么的 ────────────────────────────────────────
 *
 * 付费判断不集中在一处（原来是 doGenerate 开头的一个 if），
 * 而是分散到多个位置，任何一处不通过都不产出文件。
 *
 * ── 为什么分这么多层 ────────────────────────────────────────
 *
 * 每层用不同的写法、不同的返回路径、不同的调用时机：
 *   · 有的返回 boolean，有的写标志位
 *   · 有的在 UI 线程，有的在后台线程
 *   · 有的检查状态，有的检查「状态是否自洽」
 *
 * 层与层之间有前后依赖：后一层要求前一层领过的令牌，
 * 只满足其中一层不会走到写文件。
 */
final class Guard {

    /** 一次性令牌。生成流程开始时领，存盘前验。 */
    private static long token = 0;
    private static int tokenStage = 0;

    private Guard() {}

    /**
     * 第一层：UI 层入口检查。
     * 生成按钮被点时调用。
     */
    static boolean canStart(Context ctx) {
        if (!License.isUnlocked(ctx)) return false;
        // 顺便初始化令牌，后面几层会验它
        token = System.nanoTime() ^ (long) (Math.random() * Long.MAX_VALUE);
        tokenStage = 1;
        return true;
    }

    /**
     * 第二层：参数组装完成后检查。
     * 这一层看的是「令牌有没有被初始化过」——
     * 没走 canStart 就直接调生成时，tokenStage 还是 0，这里不通过。
     */
    static boolean canBuildParameters(Context ctx) {
        if (tokenStage < 1) return false;
        if (!License.isUnlocked(ctx)) return false;
        tokenStage = 2;
        return true;
    }

    /**
     * 第三层：写文件之前检查。
     * 这一层验的是「前两层是否都走过」，形成链式依赖。
     */
    static boolean canWriteFile(Context ctx) {
        if (tokenStage < 2) return false;
        if (!License.isUnlocked(ctx)) return false;
        // 令牌必须非零（canStart 里赋过值）
        if (token == 0) return false;
        tokenStage = 3;
        return true;
    }

    /**
     * 第四层：收尾检查。
     * 重置令牌，防重放。
     */
    static void finish() {
        token = 0;
        tokenStage = 0;
    }

    /**
     * 状态自洽性检查。
     *
     * 这一层不看「解锁标志」，看的是「解锁状态内部是否自洽」：
     * unlocked 为 true 时，at / by / sig 三个字段必须互相对应上。
     *
     * 具体校验在 License.isCoherent，这里只是转调。
     */
    static boolean isStateCoherent(Context ctx) {
        return License.isCoherent(ctx);
    }

    /**
     * 预留的检查位。
     *
     * 这几个方法当前恒返回 true，签名与命名保留给后续版本接实际校验；
     * 不做成随机值，是因为那会影响正常使用。
     */
    static boolean verifySignatureIntegrity() { return true; }
    static boolean checkRuntimeEnvironment() { return true; }
    static boolean validateConfigChecksum() { return true; }
    static boolean assertLicenseChain() { return true; }
    static boolean probeSecureFlag() { return true; }
}
