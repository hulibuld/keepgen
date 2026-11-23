package com.qianyuzhou.keepgen;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/**
 * 与云端交换「轨迹骨架」。
 *
 * ── v22 协议 ────────────────────────────────────────────────
 *
 * 请求：{"action":"track","token":"..","sport":"run","distKm":5.5,
 *        "lapM":400,"straight":84.39,"laneR":36.8,"device":".."}
 * 返回：{"ok":true,"token":"<新 token>","lapM":400,"n":22,
 *        "tpl":"<base64：首点 int32 绝对(厘米) + 其后 int16 差分(厘米)>",
 *        "ph":"<base64：每圈横向漂移(有符号字节，单位 0.1 米)>"}
 *
 * 模板是**米制偏移**，跟场地无关；客户端用自己的场地中心点换算经纬度，
 * 所以换场地 / 换 200 m 跑道都不受影响。
 *
 * 响应体 < 400 字节（骨架 22 点 ≈ 124 B），服务端可能 gzip。
 *
 * ── 日限 ────────────────────────────────────────────────────
 *
 * 服务端按「码摘要」计数，超限返回 {"ok":false,"error":"daily_limit"}。
 * 本地不做判据，只负责把这句话翻译成一句人话弹出来。
 */
final class CloudTrack {

    private static final String PREF = "cloud_token";
    private static final String K_TOKEN = "token";

    /** 骨架模板 */
    static final class Tpl {
        double[] east, north;
        byte[] drift;
        double lapM;
    }

    static final class Result {
        final boolean ok;
        final int seed;
        final boolean indoor;
        final String error;
        final Tpl tpl;

        Result(boolean ok, int seed, boolean indoor, String error, Tpl tpl) {
            this.ok = ok; this.seed = seed; this.indoor = indoor;
            this.error = error; this.tpl = tpl;
        }
        Result(boolean ok, int seed, boolean indoor, String error) {
            this(ok, seed, indoor, error, null);
        }
    }

    private CloudTrack() {}

    static void saveToken(Context ctx, String token) {
        if (token == null || token.isEmpty()) return;
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
           .edit().putString(K_TOKEN, token).apply();
    }

    static String token(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                  .getString(K_TOKEN, "");
    }

    static void clearToken(Context ctx) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /**
     * 要一次骨架。**必须在后台线程调用。**
     *
     * 本地缓存命中时只发一个极小的 renew 请求（服务端照样计数、换新 token），
     * 不再下发模板 —— 这样「请求数 = 生成数」和「省流量」能同时成立。
     */
    static Result fetch(Context ctx, String sport, double distKm,
                        double lapM, double straight, double laneR) {
        String tk = token(ctx);
        if (tk.isEmpty()) return new Result(false, 0, false, "need_unlock");

        Tpl cached = Cache.get(ctx, lapM, distKm);
        if (cached != null) {
            String resp0 = post(BuildConfig.CLOUD_URL, renewBody(tk, distKm));
            if (resp0 == null) return new Result(false, 0, false, "network");
            if (resp0.contains("daily_limit")) return new Result(false, 0, false, "daily_limit");
            if (resp0.contains("need_unlock")) return new Result(false, 0, false, "need_unlock");
            if (!hasOk(resp0)) return new Result(false, 0, false, "server");
            saveToken(ctx, pickStr(resp0, "token"));
            return new Result(true, 0, "tread".equals(sport), null, cached);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"action\":\"track\"");
        sb.append(",\"token\":\"").append(esc(tk)).append("\"");
        sb.append(",\"sport\":\"").append(esc(sport)).append("\"");
        sb.append(",\"distKm\":").append(fmt(distKm));
        sb.append(",\"lapM\":").append(fmt(lapM));
        sb.append(",\"straight\":").append(fmt(straight));
        sb.append(",\"laneR\":").append(fmt(laneR));
        String dev = CloudUnlock.deviceId(ctx);
        if (dev != null && !dev.isEmpty()) sb.append(",\"device\":\"").append(esc(dev)).append("\"");
        sb.append('}');

        String resp = post(BuildConfig.CLOUD_URL, sb.toString());
        if (resp == null) return new Result(false, 0, false, "network");
        if (resp.contains("daily_limit")) return new Result(false, 0, false, "daily_limit");
        if (resp.contains("need_unlock")) return new Result(false, 0, false, "need_unlock");
        if (!hasOk(resp)) return new Result(false, 0, false, "server");

        saveToken(ctx, pickStr(resp, "token"));

        boolean indoor = "tread".equals(sport);
        if (indoor) return new Result(true, 0, true, null);

        Tpl tpl = decode(resp);
        if (tpl == null) return new Result(false, 0, false, "server");
        Cache.put(ctx, lapM, distKm, tpl);
        return new Result(true, 0, false, null, tpl);
    }

    // ---- 模板解码 --------------------------------------------------------

    /** base64 的 int32 首点 + int16 差分（单位厘米） -> 米 */
    static Tpl decode(String body) {
        String s = pickStr(body, "tpl");
        if (s == null || s.length() < 8) return null;
        byte[] raw = b64(s);
        if (raw == null || raw.length < 9) return null;
        Tpl t = new Tpl();
        t.lapM = pickNum(body, "lapM", 400);
        t.drift = b64(pickStr(body, "ph"));

        int m = 1 + (raw.length - 8) / 4;
        double[] e = new double[m], n = new double[m];
        e[0] = readI32(raw, 0) / 100.0;
        n[0] = readI32(raw, 4) / 100.0;
        int o = 8;
        for (int i = 1; i < m; i++) {
            e[i] = e[i - 1] + readI16(raw, o) / 100.0;
            n[i] = n[i - 1] + readI16(raw, o + 2) / 100.0;
            o += 4;
        }
        t.east = e;
        t.north = n;
        return t;
    }

    private static int readI32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
             | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static int readI16(byte[] b, int o) {
        return (short) (((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF));
    }

    static byte[] b64(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            int pad = (4 - s.length() % 4) % 4;
            StringBuilder sb = new StringBuilder(s);
            for (int i = 0; i < pad; i++) sb.append('=');
            return android.util.Base64.decode(sb.toString(),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- 缓存 ------------------------------------------------------------

    /** 骨架缓存：只存模板与日期，**不存 seed、不存 Result** */
    static final class Cache {
        private static final String P = "cloud_tpl";
        private static String key(double lapM, double distKm) {
            return Math.round(lapM) + "|" + String.format(java.util.Locale.US, "%.1f", distKm);
        }
        private static String day() {
            return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                    .format(new java.util.Date());
        }

        static Tpl get(Context ctx, double lapM, double distKm) {
            SharedPreferences sp = ctx.getSharedPreferences(P, Context.MODE_PRIVATE);
            String k = key(lapM, distKm);
            if (!day().equals(sp.getString(k + "|d", ""))) return null;
            String enc = sp.getString(k, null);
            if (enc == null) return null;
            // 存的是拼好的假 JSON 片段，复用 decode
            return CloudTrack.decode(enc);
        }

        static void put(Context ctx, double lapM, double distKm, Tpl t) {
            if (t == null || t.east == null) return;
            String k = key(lapM, distKm);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"lapM\":").append(t.lapM).append(",\"tpl\":\"")
              .append(encTpl(t)).append('"');
            if (t.drift != null) {
                sb.append(",\"ph\":\"").append(android.util.Base64.encodeToString(
                        t.drift, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING)).append('"');
            }
            sb.append('}');
            ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
               .putString(k, sb.toString())
               .putString(k + "|d", day())
               .apply();
        }

        private static String encTpl(Tpl t) {
            int m = t.east.length;
            byte[] raw = new byte[8 + (m - 1) * 4];
            writeI32(raw, 0, (int) Math.round(t.east[0] * 100));
            writeI32(raw, 4, (int) Math.round(t.north[0] * 100));
            int o = 8;
            for (int i = 1; i < m; i++) {
                int de = (int) Math.round((t.east[i] - t.east[i - 1]) * 100);
                int dn = (int) Math.round((t.north[i] - t.north[i - 1]) * 100);
                writeI16(raw, o, de);
                writeI16(raw, o + 2, dn);
                o += 4;
            }
            return android.util.Base64.encodeToString(raw,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        }

        private static void writeI32(byte[] b, int o, int v) {
            b[o] = (byte) (v >>> 24); b[o + 1] = (byte) (v >>> 16);
            b[o + 2] = (byte) (v >>> 8); b[o + 3] = (byte) v;
        }
        private static void writeI16(byte[] b, int o, int v) {
            b[o] = (byte) (v >> 8); b[o + 1] = (byte) v;
        }
    }

    // ---- 小工具 ----------------------------------------------------------

    private static String renewBody(String tk, double distKm) {
        return "{\"action\":\"renew\",\"token\":\"" + esc(tk) + "\",\"distKm\":" + fmt(distKm) + "}";
    }

    private static boolean hasOk(String resp) {
        return resp.contains("\"ok\": true") || resp.contains("\"ok\":true");
    }

    static String pickStr(String body, String key) {
        String needle = "\"" + key + "\"";
        int k = body.indexOf(needle);
        if (k < 0) return null;
        int q1 = body.indexOf('"', k + needle.length());
        if (q1 < 0) return null;
        int q2 = body.indexOf('"', q1 + 1);
        return q2 < 0 ? null : body.substring(q1 + 1, q2);
    }

    static double pickNum(String body, String key, double def) {
        String needle = "\"" + key + "\"";
        int k = body.indexOf(needle);
        if (k < 0) return def;
        int c = body.indexOf(':', k);
        if (c < 0) return def;
        int i = c + 1;
        while (i < body.length() && " \t".indexOf(body.charAt(i)) >= 0) i++;
        int j = i;
        while (j < body.length() && "0123456789.-".indexOf(body.charAt(j)) >= 0) j++;
        try { return Double.parseDouble(body.substring(i, j)); } catch (Exception e) { return def; }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * POST。**故意不设 Accept-Encoding**：留给 OkHttp 的透明解压。
     * 万一网关剥掉 Content-Encoding / 或者哪天有人手动加了 identity，
     * 再靠 1f 8b magic 自己解一次 —— 两条路都要留着。
     */
    private static String post(String urlStr, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("User-Agent", "keepgen/" + BuildConfig.VERSION_NAME);

            OutputStream os = c.getOutputStream();
            try { os.write(body.getBytes("UTF-8")); os.flush(); } finally { os.close(); }

            if (c.getResponseCode() != 200) return null;

            InputStream is = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n0;
            while ((n0 = is.read(buf)) > 0) bo.write(buf, 0, n0);
            is.close();
            byte[] raw = bo.toByteArray();

            // 两种情况都要兜：
            //   ① 网关把 gzip 二进制原样给我们 —— body 以 1f 8b 开头
            //   ② 网关没解码 isBase64Encoded，直接把 base64 文本给我们 —— 以 "H4sI" 开头
            if (raw.length > 4 && raw[0] == 'H' && raw[1] == '4' && raw[2] == 's' && raw[3] == 'I') {
                byte[] dec = b64(new String(raw, "UTF-8"));
                if (dec != null) raw = dec;
            }
            if (raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                InputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(raw));
                while ((n0 = gz.read(buf)) > 0) out.write(buf, 0, n0);
                gz.close();
                raw = out.toByteArray();
            }
            return new String(raw, "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}