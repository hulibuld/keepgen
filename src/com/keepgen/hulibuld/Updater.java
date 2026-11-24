package com.keepgen.hulibuld;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 版本检查。服务端 /version 返回 {latest,versionCode,url,notes}，
 * 本地拿 BuildConfig 里的 versionCode 比一下就知道要不要提示更新。
 *
 * 设计原则：
 *   · 检查更新是「尽力而为」，网络不通就当没更新，不弹任何错误
 *   · 永不阻塞启动：调用方自己放线程里
 *   · 拿到的 url 必须是 http/https，防止被塞 file:// 之类
 */
final class Updater {

    static final class Info {
        String latest;
        int versionCode;
        String url;
        String notes;
        String error;

        boolean hasUpdate(int localCode) {
            return error == null && versionCode > localCode && url != null && url.length() > 0;
        }
    }

    private Updater() {}

    /** 服务端地址没配就直接返回带 error 的空结果 */
    static Info check(String server, int localVersionCode) {
        Info info = new Info();
        if (server == null) server = "";
        server = server.trim();
        while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        if (!server.startsWith("http://") && !server.startsWith("https://")) {
            info.error = "没配服务端，跳过更新检查";
            return info;
        }

        String body;
        try {
            body = get(server + "/version");
        } catch (Exception e) {
            info.error = "检查更新失败：" + e.getMessage();
            return info;
        }
        if (body == null) {
            info.error = "空响应";
            return info;
        }

        info.latest = str(body, "latest");
        info.notes = str(body, "notes");
        info.url = str(body, "url");
        info.versionCode = (int) num(body, "versionCode");

        if (info.url != null && !info.url.startsWith("http://") && !info.url.startsWith("https://")) {
            info.url = "";
            info.error = "服务端给的下载地址不是 http(s)，已忽略";
        }
        return info;
    }

    private static String str(String obj, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\s*:\s*\"((?:[^\"\\]|\\.)*)\"").matcher(obj);
        if (!m.find()) return null;
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static double num(String obj, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\s*:\s*(-?[0-9.]+)").matcher(obj);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    /** 最近一次 /version 响应的服务器时间，0 表示没拿到 */
    static volatile long lastServerTimeMs = 0;

    private static String get(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "keepgen");
        int code = c.getResponseCode();
        long dt = c.getHeaderFieldDate("Date", 0);
        if (dt > 0) lastServerTimeMs = dt;
        if (code >= 400) throw new IllegalStateException("HTTP " + code);
        InputStream is = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n, total = 0;
        try {
            while ((n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
                total += n;
                if (total > 65536) break;
            }
        } finally {
            is.close();
            c.disconnect();
        }
        return new String(bo.toByteArray(), "UTF-8");
    }
}
