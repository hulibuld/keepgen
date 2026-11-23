package com.qianyuzhou.keepgen;

import android.content.Context;

/**
 * 授权校验的**分散副本**。
 *
 * ── 这个类是干什么的 ────────────────────────────────────────
 *
 * 原来的付费判断只有一处（doGenerate 开头的 if）。破解者改那一处就完事。
 * 现在把同一个判断分散到多个地方，任何一处不通过都拦。
 *
 * 目的**不是**让人破不了 —— 经验丰富的人照样能全部改掉。
 * 目的是让「用 AI 挂后台半小时自动破解」这件事失效：
 * AI 得先看懂这些看起来不相关的检查其实是同一件事，
 * 然后逐个改对，改漏一处就白干。
 *
 * ── 为什么分这么多层 ────────────────────────────────────────
 *
 * 每层用不同的写法、不同的返回路径、不同的调用时机：
 *   · 有的返回 boolean，有的抛异常，有的写标志位
 *   · 有的在 UI 线程，有的在后台线程
 *   · 有的检查状态，有的检查「状态是否自洽」
 *
 * 改的人（或 AI）如果只按「把 false 改成 true」的思路改，
 * 会因为层与层之间的交叉校验而失败，且不知道错在哪。
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
     * 如果破解者跳过了 canStart 直接调生成，tokenStage 就是 0。
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
     * 如果破解者直接把 unlocked 改成 true，但没有对应的 sig / at，
     * 这里会发现不一致。
     *
     * 破解者要过这一关，得同时伪造三个字段且互相匹配。
     */
    static boolean isStateCoherent(Context ctx) {
        return License.isCoherent(ctx);
    }

    /**
     * 混淆用的假检查。
     *
     * 这些方法**永远返回 true**，但名字和签名看起来像关键判断。
     * 目的是让反编译结果里出现一堆「像是授权检查」的方法，
     * 增加人工（或 AI）分辨哪个是真的的难度。
     *
     * 为什么不做得更复杂（比如返回随机值）：
     * 那样会真的影响正常使用，得不偿失。
     * 这里只需要「看起来像」，不需要真的起作用。
     */
    static boolean verifySignatureIntegrity() { return true; }
    static boolean checkRuntimeEnvironment() { return true; }
    static boolean validateConfigChecksum() { return true; }
    static boolean assertLicenseChain() { return true; }
    static boolean probeSecureFlag() { return true; }
}
