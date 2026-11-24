package com.keepgen.hulibuld;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 授权状态。**只有两种：已解锁 / 未解锁。跟时间没有任何关系。**
 *
 * 所以这个文件里：
 *   · 没有试用天数
 *   · 没有截止日期
 *   · 没有首次联网对时
 *   · 没有改日期检测、没有 maxSeenMs、没有 everLocked
 *   · 不看 BuildStamp
 *
 * 未解锁时软件仍然能正常生成文件——只是界面上挂一个「1 元解锁」的提示。
 * 解锁是对开发工作的支持，不是使用门槛。
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
     * isCoherent 在这一层之外还要求字段之间能对上：
     *   · unlocked=true 时，at 必须是个合理的时间戳（不能是 0 或未来）
     *   · sig 的长度必须对
     *   · by 必须是已知的取值
     *   · sig 必须是 stamp(ctx, at, by) 的结果
     *
     * 任何一条不满足，都按「未解锁」处理。
     */
    static boolean isCoherent(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        boolean u = sp.getBoolean(K_UNLOCKED, false);

        // 未解锁 **不算** 自洽。
        //
        // 早期版本此处返回 true 会放行伪造状态，已改为 false：
        // 未解锁就是未解锁，后面该怎么提示就怎么提示。
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

    // ---- 首次激活的设备登记 ----------------------------------------------

    /**
     * 设备登记端点。**只在兑换解锁码的那一刻请求，没有任何启动路径会调用它。**
     *
     * 一枚码对应一台设备：服务端记住「这枚码第一次是用哪台设备登记的」，
     * 之后拿同一枚码在别的设备上兑换会被 409 拦下。
     * 换机（换手机、重置系统、卸载重装导致设备号变化）需要作者换签。
     */
    static final String BIND_URL = "https://deepchat-writer.pages.dev/bind";

    /** 登记的超时。连接与读取各给 8 秒，超时按「没登记上」处理，不写解锁标记 */
    private static final int BIND_TIMEOUT_MS = 8000;

    /** 登记结果。三种结局，界面文案挂在 message() 上，免得调用方各写一份 */
    static final class Bind {
        static final int OK = 0;         // 登记通过（首台或同一台重复登记）
        static final int MISMATCH = 1;   // 这枚码已经绑在另一台设备上
        static final int NETWORK = 2;    // 连不上/服务端异常，无法完成首次激活

        final int state;
        /** 409 时服务端回的那台设备号，形如 ABCD-****-WXYZ（中间已经打码） */
        final String bound;

        Bind(int state, String bound) {
            this.state = state;
            this.bound = bound == null ? "" : bound;
        }

        boolean ok() { return state == OK; }

        String message() {
            if (state == MISMATCH) {
                return "这枚码已经绑在另一台设备上（" + bound + "）。换机请联系作者换签。";
            }
            if (state == NETWORK) {
                return "首次激活需要联网。连上网再点一次，激活后可以离线生成。";
            }
            return "";
        }
    }

    /**
     * 兑换成功后的落地动作：**先在服务端登记设备，登记通过才写解锁标记。**
     *
     * 顺序是刻意的：
     *   ① 自洽性检查（isCoherent）—— 已经解锁且字段自洽的设备直接算通过，
     *      不再发任何请求，所以已激活的设备每次启动都不联网；
     *   ② 没解锁过就调 /bind 登记本机设备号；
     *   ③ 登记被拒（409）或没网：原样返回，**不写解锁标记**，
     *      界面照 Bind.message() 提示，用户重试即可。
     *
     * 会发网络请求，**必须在后台线程调用**。
     */
    static Bind activateWithDevice(Context ctx, String code) {
        if (isCoherent(ctx)) {
            // 已经激活过：不联网、不重复登记、不动已有的标记
            return new Bind(Bind.OK, "");
        }
        Bind b = bindDevice(ctx, code);
        if (!b.ok()) return b;
        unlock(ctx, "code");
        return b;
    }

    /** POST /bind。任何异常都折算成「没登记上」，不抛给界面 */
    private static Bind bindDevice(Context ctx, String code) {
        HttpURLConnection c = null;
        try {
            String dev = CloudUnlock.deviceId(ctx);
            String body = "{\"code\":\"" + esc(code) + "\",\"deviceId\":\"" + esc(dev) + "\"}";

            c = (HttpURLConnection) new URL(BIND_URL).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(BIND_TIMEOUT_MS);
            c.setReadTimeout(BIND_TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("User-Agent", "keepgen/" + BuildConfig.VERSION_NAME);

            OutputStream os = c.getOutputStream();
            try { os.write(body.getBytes("UTF-8")); os.flush(); } finally { os.close(); }

            int status = c.getResponseCode();
            if (status == 200) {
                // {"ok":true,"first":true} / {"ok":true,"first":false,"count":N} —— 两种都放行
                return new Bind(Bind.OK, "");
            }
            if (status == 409) {
                // {"ok":false,"error":"DEVICE_MISMATCH","bound":"ABCD-****-WXYZ"}
                String raw = readAll(c.getErrorStream());
                String bound = CloudUnlock.pickStr(raw, "bound");
                return new Bind(Bind.MISMATCH, bound == null ? "未知设备" : bound);
            }
            // 5xx / 404 之类：登记没完成，按没网处理，不给解锁
            return new Bind(Bind.NETWORK, "");
        } catch (Exception e) {
            return new Bind(Bind.NETWORK, "");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 解锁标记的校验值。
     *
     * 早期版本是 SHA256(at|by|常量)，常量写在源码里；
     * 现在改成 HMAC，密钥取**本机安装包的签名证书摘要**（见 installKey）。
     *
     * 结果是：换一份签名重打包，算出来的值与原来的签名不一致，
     * 旧的解锁标记随之失效——所以换签名必须同时走一次重新激活。
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
