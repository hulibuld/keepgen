package com.keepgen.hulibuld;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 路线共享。**地址是内置的，用户不需要配置任何东西。**
 *
 * 存法：一条路线一个静态 JSON，放在公开仓库里，走 raw.githubusercontent 读。
 *   GET {BASE}/routes/{CODE}.json
 *       -> {code,name,loop,lengthM,points:[[lat,lon],...]}
 *
 * 为什么不再用自建服务端：
 *   · 预计几百到几千人用，路线总共一两千条，一个静态目录足够
 *   · 让用户为了拿一条路线去开服务器、填地址，是把开发者的活推给用户
 *   · 公开仓库有 CDN，读取稳定且不需要任何凭据
 *
 * 读取失败（没网、这条还没人分享）都返回明确的异常，界面据此给用户看得懂的话，
 * 不再出现「先去设置里填服务器」这种引导。
 */
final class Cloud {

    static final class Hit {
        String code, name;
        double lengthM;
    }

    private static String base(String s) {
        if (s == null) return "";
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    static boolean configured(String server) {
        String b = base(server);
        return b.startsWith("http://") || b.startsWith("https://");
    }

    /** 按短码取路线原始 JSON；失败抛异常 */
    static String getRoute(String server, String code) throws Exception {
        if (!configured(server)) throw new IllegalStateException("路线地址未配置");
        return http(base(server) + "/routes/" + code + ".json", "GET", null);
    }

    /** 上传一条路线，body 是完整 JSON */
    static void putRoute(String server, String json) throws Exception {
        if (!configured(server)) throw new IllegalStateException("没配服务端地址");
        String r = http(base(server) + "/route", "POST", json);
        if (r == null || r.indexOf("\"ok\"") < 0) {
            throw new IllegalStateException("上传被拒绝：" + (r == null ? "空响应" : r));
        }
    }

    /** 搜路线 */
    static java.util.List<Hit> search(String server, String q) throws Exception {
        java.util.List<Hit> out = new java.util.ArrayList<Hit>();
        if (!configured(server)) throw new IllegalStateException("没配服务端地址");
        String body = http(base(server) + "/search?limit=20&q=" + urlenc(q), "GET", null);
        if (body == null) return out;
        // 极简解析：[{...},{...}]
        int i = 0;
        while (i < body.length()) {
            int a = body.indexOf('{', i);
            if (a < 0) break;
            int b = body.indexOf('}', a);
            if (b < 0) break;
            String obj = body.substring(a, b + 1);
            Hit h = new Hit();
            h.code = field(obj, "code");
            h.name = field(obj, "name");
            try { h.lengthM = Double.parseDouble(field(obj, "lengthM")); } catch (Exception ignored) {}
            if (h.code != null) out.add(h);
            i = b + 1;
        }
        return out;
    }

    private static String field(String obj, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"?([^\",}]*)").matcher(obj);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String urlenc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    /** 最近一次 HTTP 响应的服务器时间（Date 头），0 表示没拿到 */
    static volatile long lastServerTimeMs = 0;

    /**
     * 取一次网络时间，用于「首次使用必须先联网对时」。
     *
     * 只发一个 HEAD/GET 拿到响应头里的 Date，不下载正文，
     * 所以很小、很快，移动数据下也不心疼。
     * 拿不到就返回 0，调用方据此拦住用户。
     */
    /** 上一次对时失败的原因，给界面挑提示用 */
    static volatile String lastTimeError = null;

    static long fetchServerTime(String anyUrl) {
        lastTimeError = null;
        try {
            URL url = new URL(anyUrl);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            // 用 GET 不用 HEAD：不少 CDN（含 raw.githubusercontent）对 HEAD 直接 4xx/5xx，
            // 只读响应头就断开，不把正文读进内存。
            c.setRequestMethod("GET");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 keepgen");
            // 不要加 Range 头：raw.githubusercontent 对 Range 请求会走不同分支，
            // 带 Range 容易拿到 416/403，反而拿不到 Date。正常 GET 读完头就关流。
            int code = c.getResponseCode();
            long t = c.getHeaderFieldDate("Date", 0);
            try { c.getInputStream().close(); } catch (Exception ignored) {}
            c.disconnect();
            if (t > 0) lastServerTimeMs = t;
            return t;
        } catch (javax.net.ssl.SSLException e) {
            // 手机日期被调到很早时，证书校验会全线失败（证书「还没生效」）。
            // 原来一律提示「检查网络」，用户把 Wi-Fi 换来换去也解决不了。
            // 这种情况用户只会以为「网络坏了」，所以单独识别出来。
            lastTimeError = "CLOCK";
            return 0;
        } catch (Exception e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("SSL") || m.contains("Cert") || m.contains("certificate")) {
                lastTimeError = "CLOCK";
            } else {
                lastTimeError = "NET";
            }
            return 0;
        }
    }

    private static String http(String urlStr, String method, String body) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "keepgen/1.9");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream os = c.getOutputStream();
            try { os.write(body.getBytes("UTF-8")); os.flush(); } finally { os.close(); }
        }
        int code = c.getResponseCode();
        // Date 头。现在不用于任何授权判断，只给「检查更新」看版本发布时间
        long dt = c.getHeaderFieldDate("Date", 0);
        if (dt > 0) lastServerTimeMs = dt;
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is == null) throw new IllegalStateException("HTTP " + code);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        } finally { is.close(); c.disconnect(); }
        String text = new String(bo.toByteArray(), "UTF-8");
        if (code >= 400) throw new IllegalStateException("HTTP " + code + "：" + text);
        return text;
    }
}
