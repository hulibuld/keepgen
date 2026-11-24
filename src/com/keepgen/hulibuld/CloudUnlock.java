package com.keepgen.hulibuld;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 用兑换码向云端换 token。
 *
 * ── v22 变化 ────────────────────────────────────────────────
 *
 * 核销动作**搬到服务端**了（服务端对索引里的 claim 记录做状态变更）。
 * 客户端不再自己 DELETE —— 原来的做法是「自己给自己发许可证」，
 * 服务端从来不知道这个码被用掉，于是同一个码可以无限换 token。
 *
 * 现在客户端只把码和设备报上去，由服务端给出明确状态：
 *   ok / invalid / not_sold / already_claimed / index_busy / bad_code
 */
final class CloudUnlock {

    static final int OK = 0;
    static final int INVALID = 1;       // 签名不对
    static final int NOT_SOLD = 2;      // 没发售过（伪造码）
    static final int ALREADY = 3;       // 已被用掉（且不是这台设备）
    static final int NETWORK = 4;
    static final int BUSY = 5;          // 码库拉不到 / 服务端状态不可用

    static final class Rep {
        final int state;
        final String token;
        Rep(int s, String t) { state = s; token = t; }
    }

    private CloudUnlock() {}

    static Rep exchange(Context ctx, String code) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"action\":\"unlock\",\"code\":\"").append(esc(code)).append("\"");
        String dev = deviceId(ctx);
        if (dev != null && !dev.isEmpty()) {
            sb.append(",\"device\":\"").append(esc(dev)).append("\"");
        }
        sb.append('}');

        String resp = post(BuildConfig.CLOUD_URL, sb.toString());
        if (resp == null) return new Rep(NETWORK, null);
        if (resp.contains("already_claimed")) return new Rep(ALREADY, null);
        if (resp.contains("not_sold")) return new Rep(NOT_SOLD, null);
        if (resp.contains("index_busy")) return new Rep(BUSY, null);
        if (resp.contains("\"invalid\"") || resp.contains("bad_code")) return new Rep(INVALID, null);
        if (!resp.contains("\"ok\": true") && !resp.contains("\"ok\":true")) {
            return new Rep(NETWORK, null);
        }
        String tk = pickStr(resp, "token");
        return tk == null ? new Rep(NETWORK, null) : new Rep(OK, tk);
    }

    /**
     * 设备标识。只用于「这个 token 是签给谁的」，不收集任何个人信息。
     *
     * ⚠️ 这是客户端自报的字符串，服务端不把它当唯一凭证，
     * 只作为副键（主键是码摘要）。
     */
    static String deviceId(Context ctx) {
        try {
            String id = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (id == null || id.isEmpty()) return "";
            return id.length() > 16 ? id.substring(0, 16) : id;
        } catch (Exception e) {
            return "";
        }
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

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

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
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}