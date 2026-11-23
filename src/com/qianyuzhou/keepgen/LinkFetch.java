package com.qianyuzhou.keepgen;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从高德 / Keep 的分享链接里把轨迹抠出来。
 *
 * 不依赖地图 SDK，只做两件事：
 *   1. 按手机浏览器的 UA 把页面抓下来（自动跟短链跳转）
 *   2. 在返回的 HTML / JSON 里找坐标，按几种常见格式依次试
 *
 * 认这几种格式：
 *   · 高德的 "path":"lng,lat;lng,lat;..."
 *   · 任意 JSON 数组 [[lng,lat],[lng,lat]]
 *   · "polyline":"..." Google 编码折线（高德/Keep 都用）
 *   · GPX / TCX 原文（走 RouteIO）
 *   · 纯文本 lat,lon 多行（走 RouteIO）
 *
 * 高德和 Keep 返回的都是 GCJ-02，抓下来后自动迭代转成 WGS-84，
 * 否则导入 Keep 会整体偏 300~600 米。
 */
final class LinkFetch {

    static final class Result {
        List<double[]> points = new ArrayList<double[]>();
        String source = "";
        String title = "";
        String error;
        double lengthM;
        boolean convertedFromGcj;
    }

    private static final int MAX_BYTES = 4 * 1024 * 1024;

    // 高德 path："lng,lat;lng,lat"
    private static final Pattern AMAP_PATH =
            Pattern.compile("\"path\"\\s*:\\s*\"([-0-9.,;\\s]{20,})\"");
    // 编码折线
    private static final Pattern POLYLINE =
            Pattern.compile("\"(?:polyline|encodedPath|points_encoded)\"\\s*:\\s*\"([A-Za-z0-9_@~\\\\-]{20,})\"");
    // JSON 数字对数组
    private static final Pattern PAIR_ARRAY =
            Pattern.compile("\\[\\s*(-?\\d{1,3}\\.\\d{4,})\\s*,\\s*(-?\\d{1,3}\\.\\d{4,})\\s*\\]");

    private LinkFetch() {}

    static Result fetch(String url) {
        Result r = new Result();
        String body;
        try {
            body = get(url);
        } catch (Exception e) {
            r.error = "抓取失败：" + e.getMessage();
            return r;
        }
        if (body == null || body.isEmpty()) {
            r.error = "链接返回空内容";
            return r;
        }

        // 标题
        Matcher tm = Pattern.compile("<title>([^<]{1,80})</title>").matcher(body);
        if (tm.find()) r.title = tm.group(1).trim();

        // 1. GPX / TCX 原文
        if (body.contains("<trkpt") || body.contains("<Trackpoint")) {
            r.points = RouteIO.parse(body);
            if (!r.points.isEmpty()) {
                r.source = body.contains("<trkpt") ? "GPX" : "TCX";
                finish(r);
                return r;
            }
        }

        // 2. 高德 path
        Matcher m = AMAP_PATH.matcher(body);
        if (m.find()) {
            List<double[]> pts = parsePairs(m.group(1), true);
            if (pts.size() >= 2) {
                r.points = pts;
                r.source = "高德 path";
                finish(r);
                return r;
            }
        }

        // 3. 编码折线
        m = POLYLINE.matcher(body);
        if (m.find()) {
            List<double[]> pts = decodePolyline(m.group(1));
            if (pts.size() >= 2) {
                r.points = pts;
                r.source = "编码折线";
                finish(r);
                return r;
            }
        }

        // 4. JSON 数字对数组（取最长的一段）
        m = PAIR_ARRAY.matcher(body);
        List<double[]> best = new ArrayList<double[]>();
        List<double[]> cur = new ArrayList<double[]>();
        while (m.find()) {
            double x = dbl(m.group(1)), y = dbl(m.group(2));
            double[] pair = orderPair(x, y);
            if (pair == null) { if (cur.size() > best.size()) best = cur; cur = new ArrayList<double[]>(); continue; }
            cur.add(pair);
        }
        if (cur.size() > best.size()) best = cur;
        if (best.size() >= 2) {
            r.points = best;
            r.source = "坐标数组";
            finish(r);
            return r;
        }

        // 5. 纯文本坐标
        List<double[]> txt = RouteIO.parse(body);
        if (txt.size() >= 2) {
            r.points = txt;
            r.source = "页面文本坐标";
            finish(r);
            return r;
        }

        r.error = "页面里没找到轨迹。高德/Keep 的部分分享页是 JS 动态渲染的，"
                + "可以试：打开链接 → 查看轨迹 → 长按复制坐标，粘贴到下面的输入框。";
        return r;
    }

    /** 高德 path 是 "lng,lat;lng,lat"，也可能是 "lng,lat lng,lat" */
    private static List<double[]> parsePairs(String s, boolean lngLatOrder) {
        List<double[]> out = new ArrayList<double[]>();
        String[] seg = s.split("[;|\\s]+");
        for (String one : seg) {
            String[] p = one.split(",");
            if (p.length < 2) continue;
            double a = dbl(p[0]), b = dbl(p[1]);
            double[] pair = lngLatOrder ? orderPair(b, a) : orderPair(a, b);
            if (pair != null) out.add(pair);
        }
        return out;
    }

    /** 判断哪个是纬度哪个是经度，返回 {lat, lon}；不像坐标就返回 null */
    private static double[] orderPair(double x, double y) {
        boolean xLat = x >= -90 && x <= 90;
        boolean yLon = y >= -180 && y <= 180;
        // 中国境内：lat 18~54，lon 73~136
        boolean xInCnLat = x >= 18 && x <= 54;
        boolean yInCnLon = y >= 73 && y <= 136;
        boolean xInCnLon = x >= 73 && x <= 136;
        boolean yInCnLat = y >= 18 && y <= 54;

        if (xInCnLat && yInCnLon) return new double[]{x, y};
        if (xInCnLon && yInCnLat) return new double[]{y, x};
        if (xLat && yLon && Math.abs(y) < 90) return new double[]{x, y};
        return null;
    }

    /** Google 编码折线解码，返回 {lat, lon} */
    static List<double[]> decodePolyline(String enc) {
        List<double[]> out = new ArrayList<double[]>();
        int index = 0, len = enc.length();
        long lat = 0, lng = 0;
        while (index < len) {
            int b, shift = 0, result = 0;
            do {
                if (index >= len) return out;
                b = enc.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            long dLat = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lat += dLat;

            shift = 0; result = 0;
            do {
                if (index >= len) return out;
                b = enc.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            long dLng = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lng += dLng;

            out.add(new double[]{lat / 1e5, lng / 1e5});
        }
        return out;
    }

    private static void finish(Result r) {
        // 中国境内的坐标一律按 GCJ-02 处理，转成 WGS-84
        r.points = toWgs(r.points);
        r.lengthM = RouteIO.lengthM(r.points);
        r.convertedFromGcj = true;
    }

    private static List<double[]> toWgs(List<double[]> pts) {
        List<double[]> out = new ArrayList<double[]>(pts.size());
        boolean anyInChina = false;
        for (double[] p : pts) {
            if (!Geo.outOfChina(p[0], p[1])) { anyInChina = true; break; }
        }
        for (double[] p : pts) {
            if (anyInChina) {
                double[] w = Geo.gcj2wgs(p[0], p[1]);
                out.add(new double[]{w[0], w[1]});
            } else {
                out.add(p);
            }
        }
        return out;
    }

    private static double dbl(String s) {
        try { return Double.parseDouble(s.trim()); }
        catch (Exception e) { return Double.NaN; }
    }

    private static String get(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        String proto = url.getProtocol();
        if (!"http".equals(proto) && !"https".equals(proto)) {
            throw new IllegalArgumentException("只支持 http / https 链接");
        }
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 "
              + "(KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "text/html,application/json,*/*");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        int code = c.getResponseCode();
        if (code >= 400) throw new IllegalStateException("HTTP " + code);

        InputStream is = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        try {
            while ((n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
                total += n;
                if (total > MAX_BYTES) break;
            }
        } finally {
            is.close();
            c.disconnect();
        }
        return new String(bo.toByteArray(), "UTF-8");
    }
}
