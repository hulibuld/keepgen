package com.qianyuzhou.keepgen;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 授权状态。**只有两种：已解锁 / 未解锁。跟时间没有任何关系。**
 *
 * 2026-11-22 用户明确要求：「代码不要和时间挂钩，用不会被无限破解出来的无规则密钥。」
 *
 * 所以这个文件里：
 *   · 没有试用天数
 *   · 没有截止日期
 *   · 没有首次联网对时
 *   · 没有改日期检测、没有 maxSeenMs、没有 everLocked
 *   · 不看 BuildStamp
 *
 * 未解锁时软件仍然能正常生成文件——只是界面上挂一个「1 元解锁」的提示。
 * 不拦着用，是因为拦了也没意义：会破解的人早就绕过了，
 * 不会破解的人被拦了只会觉得软件坏了。
 *
 * 真正收钱的机制是「愿不愿意付这 1 元」，不是技术拦截。
 */
final class License {

    private static final String PREF = "license_v3";
    private static final String K_UNLOCKED = "unlocked";
    private static final String K_UNLOCK_AT = "unlockAt";
    private static final String K_UNLOCK_BY = "unlockBy";   // code / manual
    private static final String K_UNLOCK_SIG = "unlockSig";
    /** 码的公开标识，用来去「已激活清单」核对。老用户没有这一项，视为未登记 */
    private static final String K_UNLOCK_HASH = "unlockHash";

    /** 是否已解锁 */
    final boolean unlocked;

    /** 解锁时间。未解锁时是 0 */
    final long unlockAt;

    private License(boolean unlocked, long unlockAt) {
        this.unlocked = unlocked;
        this.unlockAt = unlockAt;
    }

    // ---- 读取 ------------------------------------------------------------

    static License load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        boolean u = sp.getBoolean(K_UNLOCKED, false);
        long at = sp.getLong(K_UNLOCK_AT, 0);
        String by = sp.getString(K_UNLOCK_BY, "");

        // 校验值对不上就当没解锁。
        // 这不是加密，只是让「直接改 prefs 文件」这种操作不奏效。
        if (u) {
            String expect = stamp(ctx, at, by);
            String got = sp.getString(K_UNLOCK_SIG, "");
            if (!expect.equals(got)) u = false;
        }
        return new License(u, at);
    }

    /** 兼容旧调用点 */
    static License init(Context ctx) {
        return load(ctx);
    }

    static boolean isUnlocked(Context ctx) {
        return load(ctx).unlocked;
    }

    /**
     * 解锁状态的**内部自洽性**检查。
     *
     * 跟 isUnlocked 的区别：
     *   isUnlocked 问的是「unlocked 这个布尔是不是 true」
     *   isCoherent 问的是「这几个字段互相之间对不对得上」
     *
     * 破解者如果只把 unlocked 改成 true，而没有同时伪造 at / by / sig，
     * isUnlocked 会被 sig 挡住（这一关早就有了），但 isCoherent 会额外检查：
     *   · unlocked=true 时，at 必须是个合理的时间戳（不能是 0 或未来）
     *   · sig 的长度必须对
     *   · by 必须是已知的取值
     *
     * 这几个条件本身都不难伪造，但**要同时伪造对**，
     * 而且不要在混淆后的代码里找错字段，难度就上去了。
     */
    static boolean isCoherent(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        boolean u = sp.getBoolean(K_UNLOCKED, false);

        // 未解锁 **不算** 自洽。
        //
        // 原来这里 return true，等于给「伪造/清空的 prefs」发一张免检通行证：
        // 攻击者只要让 load() 返回 unlocked=true 且把 at/by/sig 留空，
        // Guard 四层 + 自洽检查全过。红队 2026-11-23 实测就是靠这一行拿到的。
        // 现在改成 false：未解锁就是未解锁，后面该怎么提示就怎么提示。
        if (!u) return false;

        long at = sp.getLong(K_UNLOCK_AT, 0);
        String by = sp.getString(K_UNLOCK_BY, "");
        String sig = sp.getString(K_UNLOCK_SIG, "");

        if (at <= 0) return false;
        if (at > System.currentTimeMillis() + 86400000L) return false;
        if (at < 1577836800000L) return false;
        if (sig == null || sig.length() != 16) return false;
        if (!"code".equals(by) && !"manual".equals(by)) return false;

        return stamp(ctx, at, by).equals(sig);
    }

    // ---- 写入 ------------------------------------------------------------

    /** 记下这枚码的公开标识（核对用）；没传就保持原样 */
    static void rememberCode(Context ctx, String hash) {
        if (hash == null || hash.length() != 32) return;
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(K_UNLOCK_HASH, hash).apply();
    }

    /** 这枚码的公开标识；老用户/手工改过的状态返回空串 */
    static String codeHash(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(K_UNLOCK_HASH, "");
    }

    static void unlock(Context ctx, String source) {
        long at = System.currentTimeMillis();
        String by = source == null ? "manual" : source;
        String sig = stamp(ctx, at, by);
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean(K_UNLOCKED, true)
                .putLong(K_UNLOCK_AT, at)
                .putString(K_UNLOCK_BY, by)
                .putString(K_UNLOCK_SIG, sig)
                .apply();
    }

    static void lock(Context ctx) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /**
     * 解锁标记的校验值。
     *
     * 红队 2026-11-22 实测：原来只是 SHA256(at|by|常量)，没有密钥，
     * 读一眼源码就能算出正确值，改 prefs 即可解锁。
     *
     * 现在改成 HMAC，密钥不是明文常量，而是**本机的签名证书摘要**。
     * 这样：
     *   · 想伪造，得先知道这台设备上装的那份 APK 的签名摘要
     *   · 换个签名的重打包版本，算出来的值就不一样
     *
     * 仍然挡不住会反编译的人（他能直接让 load 返回 true），
     * 但把「改个 xml 文件」这条零门槛的路堵上了。
     */
    private static String stamp(Context ctx, long at, String by) {
        try {
            byte[] key = installKey(ctx);
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            byte[] d = mac.doFinal((at + "|" + by + "|keepgen-v4").getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 官方发布签名证书的 SHA-256 白名单（小写十六进制）。
     *
     * 定位要说清楚：这不是安全边界 —— 它跑在被改过的包里，改一行就能让它返回 true。
     * 它挡的是「跟着网上教程改包重签」的那批人：他们得先找到这个方法，
     * 还得知道证书摘要长什么样。
     *
     * ⚠️ 换密钥时**必须**先把新证书加进来再发版：白名单是编译进包里的，
     * 已经发出去的旧版本没法事后追加；而 License.stamp 的 HMAC 密钥
     * 就是签名证书本身，换签名 = 已解锁用户变未解锁、路线库清空。
     */
    private static final String[] RELEASE_CERTS = {
        "3074abd7014fcc88b27b055e296baaf20018b1eb52589adfcdb91f8579e30958"
    };

    /** 当前这份包是不是用官方证书签的 */
    static boolean signedByAuthor(Context ctx) {
        try {
            return certAllowed(installKey(ctx));
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 任意一份证书 DER 是不是官方证书（更新包校验用） */
    static boolean certAllowed(byte[] cert) {
        if (cert == null) return false;
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(cert);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            String hex = sb.toString();
            for (String w : RELEASE_CERTS) if (w.equalsIgnoreCase(hex)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /** 本机安装包的签名证书原始字节（DER）。KeepFmt 用它解规则表，别外传 */
    static byte[] signatureBytes(Context ctx) {
        try {
            return installKey(ctx);
        } catch (Exception e) {
            return "keepgen-fallback".getBytes();
        }
    }

    /** 本机安装包的签名证书摘要，作为 HMAC 密钥。取不到就退回一个固定值 */
    private static byte[] installKey(Context ctx) {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(
                    ctx.getPackageName(),
                    android.os.Build.VERSION.SDK_INT >= 28
                            ? android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                            : android.content.pm.PackageManager.GET_SIGNATURES);
            android.content.pm.Signature[] arr;
            if (android.os.Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
                arr = pi.signingInfo.hasMultipleSigners()
                        ? pi.signingInfo.getApkContentsSigners()
                        : pi.signingInfo.getSigningCertificateHistory();
            } else {
                arr = pi.signatures;
            }
            if (arr != null && arr.length > 0) return arr[0].toByteArray();
        } catch (Exception ignored) {}
        return "keepgen-fallback".getBytes();
    }

    // ---- 界面文案 --------------------------------------------------------

    String summary() {
        if (unlocked) {
            return unlockAt > 0
                    ? "已解锁 · " + new java.text.SimpleDateFormat("yyyy-MM-dd",
                        java.util.Locale.US).format(new java.util.Date(unlockAt))
                    : "已解锁";
        }
        return "未解锁";
    }

    String detailForDialog() {
        if (unlocked) {
            StringBuilder sb = new StringBuilder();
            sb.append("状态：已解锁\n");
            if (unlockAt > 0) {
                sb.append("解锁时间：").append(new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm", java.util.Locale.US)
                        .format(new java.util.Date(unlockAt))).append("\n");
            }
            sb.append("\n谢谢支持。换手机或清了数据，把码发给作者重新登记，不用再买。");
            return sb.toString();
        }
        return "状态：未解锁\n\n软件现在就能正常用，解锁只是支持下作者。";
    }
}
