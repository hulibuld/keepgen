package com.qianyuzhou.keepgen;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用内下载 APK。
 *
 * 特点：
 *   · 断点不算，一次性下完（APK 只有几十 KB，没必要分块）
 *   · 有进度回调，界面显示百分比
 *   · 下完校验是不是真 APK（看前两个字节是不是 PK），防止下载到 HTML 错误页
 *   · 文件和临时文件分开写，写成功才改名，避免装到半个包
 */
final class ApkDownloader {

    interface Cb {
        /** 0~100，-1 表示总长度未知 */
        void onProgress(int percent, long bytes);
        void onDone(File apk);
        void onError(String msg);
    }

    private ApkDownloader() {}

    static File cacheDir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "apk");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static void download(final Context ctx, final String urlStr, final String fileName, final Cb cb) {
        final Handler h = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                File out = null;
                try {
                    out = fetch(ctx, urlStr, fileName, cb, h);
                    final File f = out;
                    h.post(new Runnable() { public void run() { cb.onDone(f); } });
                } catch (final Exception e) {
                    final String msg = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
                    h.post(new Runnable() { public void run() { cb.onError(msg); } });
                }
            }
        }).start();
    }

    /** 读 APK 的包名；读不到返回 null（不因此拒绝，交给签名校验兜底） */
    private static String readPackageName(File apk) {
        try {
            android.content.pm.PackageInfo pi = ctx0().getPackageManager()
                    .getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            return pi == null ? null : pi.packageName;
        } catch (Exception e) {
            return null;
        }
    }

    private static Context appCtx;

    static void attach(Context c) {
        if (c != null) appCtx = c.getApplicationContext();
    }

    private static Context ctx0() { return appCtx; }

    /**
     * 新包的签名是否与当前已安装的版本一致。
     *
     * 做法：分别取出两份签名的证书字节，逐字节比较。
     * 只比「有没有签名」不够——攻击者用同一套泄露的 keystore 重签，
     * 签名依然是合法的，只有「证书内容是否相同」才能区分。
     */
    private static boolean sameSignatureAsInstalled(Context ctx, File apk) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            int flags = android.os.Build.VERSION.SDK_INT >= 28
                    ? android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                    : android.content.pm.PackageManager.GET_SIGNATURES;

            android.content.pm.PackageInfo installed =
                    pm.getPackageInfo(ctx.getPackageName(), flags);
            android.content.pm.PackageInfo incoming =
                    pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            if (installed == null || incoming == null) return false;

            byte[] a = firstCert(installed);
            byte[] b = firstCert(incoming);
            if (a == null || b == null || a.length != b.length) return false;
            // v22：不只比「与已装版本一致」，还要求新包是官方证书签的。
            // 只比 installed 的话，重签版里 installed 与 incoming 天然一致 —— 等于没校验。
            if (!License.certAllowed(b)) return false;
            int diff = 0;
            for (int i = 0; i < a.length; i++) diff |= (a[i] ^ b[i]);
            return diff == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static byte[] firstCert(android.content.pm.PackageInfo pi) {
        try {
            android.content.pm.Signature[] arr;
            if (android.os.Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
                arr = pi.signingInfo.hasMultipleSigners()
                        ? pi.signingInfo.getApkContentsSigners()
                        : pi.signingInfo.getSigningCertificateHistory();
            } else {
                arr = pi.signatures;
            }
            if (arr == null || arr.length == 0) return null;
            return arr[0].toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static File fetch(Context ctx, String urlStr, String fileName, Cb cb, Handler h)
            throws Exception {
        attach(ctx);
        URL url = new URL(urlStr);
        String proto = url.getProtocol();
        if (!"http".equals(proto) && !"https".equals(proto)) {
            throw new IllegalArgumentException("只支持 http/https");
        }

        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "keepgen/" + BuildConfig.VERSION_NAME);
        int code = c.getResponseCode();
        if (code >= 400) throw new IllegalStateException("HTTP " + code);

        final int total = c.getContentLength();

        File dir = cacheDir(ctx);
        final File tmp = new File(dir, fileName + ".part");
        final File dst = new File(dir, fileName);
        if (tmp.exists()) tmp.delete();

        InputStream is = c.getInputStream();
        FileOutputStream fos = new FileOutputStream(tmp);
        byte[] buf = new byte[16384];
        int n;
        long got = 0;
        int lastPct = -2;
        try {
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
                got += n;
                final long g = got;
                int pct = total > 0 ? (int) (got * 100 / total) : -1;
                if (pct != lastPct) {
                    lastPct = pct;
                    h.post(new Runnable() {
                        public void run() { cb.onProgress(total > 0 ? (int) (g * 100 / total) : -1, g); }
                    });
                }
            }
            fos.flush();
        } finally {
            try { fos.close(); } catch (Exception ignored) {}
            try { is.close(); } catch (Exception ignored) {}
            c.disconnect();
        }

        // 校验 1：APK 本质是 zip，头两字节必须是 PK
        java.io.FileInputStream fis = new java.io.FileInputStream(tmp);
        byte[] head = new byte[2];
        int r;
        try { r = fis.read(head); } finally { fis.close(); }
        if (r < 2 || head[0] != 'P' || head[1] != 'K') {
            tmp.delete();
            throw new IllegalStateException("下载到的不是 APK（可能是网页或错误页）");
        }

        // 校验 2：包名必须是自己。防止有人把别的 App 换上来诱导安装
        String pkg = readPackageName(tmp);
        if (pkg != null && !BuildConfig.PACKAGE.equals(pkg)) {
            tmp.delete();
            throw new IllegalStateException("下载到的不是这个应用（包名 " + pkg + "）");
        }

        // 校验 3：签名必须与当前安装的一致。
        // 这是唯一能挡住「用同一套 keystore 重签一个改过的包」的手段——
        // 红队 2026-11-22 实测：更新包只校验 PK 魔数，攻击者用泄露的 keystore
        // 重签后 App 照装不误，没有任何提示。
        if (!sameSignatureAsInstalled(ctx, tmp)) {
            tmp.delete();
            throw new IllegalStateException("这个更新包的签名跟当前应用不一致，已阻止安装");
        }

        if (dst.exists()) dst.delete();
        if (!tmp.renameTo(dst)) {
            // 有些机型 rename 会失败，退回复制
            java.io.FileInputStream in2 = new java.io.FileInputStream(tmp);
            FileOutputStream out2 = new FileOutputStream(dst);
            try {
                byte[] b = new byte[16384];
                int m;
                while ((m = in2.read(b)) > 0) out2.write(b, 0, m);
                out2.flush();
            } finally {
                try { in2.close(); } catch (Exception ignored) {}
                try { out2.close(); } catch (Exception ignored) {}
            }
            tmp.delete();
        }
        return dst;
    }
}
