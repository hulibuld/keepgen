package com.keepgen.hulibuld;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把用户粘贴的文本解析成路点列表。不联网，不需要地图。
 *
 * 认三种输入：
 *   1. GPX 里的 <trkpt lat="..." lon="...">
 *   2. TCX 里的 <Trackpoint><Position><LatitudeDegrees>...<LongitudeDegrees>...
 *   3. 纯文本坐标：一行一个 "lat,lon" 或 "lat lon"（逗号/空格/制表符都行）
 *
 * 解析完按累计距离抽稀：两点间隔小于 minGapM 的丢掉，控制点数。
 */
final class RouteIO {

    private static final Pattern TRKPT =
            Pattern.compile("lat\\s*=\\s*[\"']([-0-9.]+)[\"']\\s+lon\\s*=\\s*[\"']([-0-9.]+)[\"']");
    private static final Pattern TCPT =
            Pattern.compile("LatitudeDegrees>\\s*([-0-9.]+)\\s*<\\s*/LatitudeDegrees>\\s*"
                          + "<LongitudeDegrees>\\s*([-0-9.]+)\\s*<");

    private RouteIO() {}

    /** 解析文本为 {lat, lon} 列表；失败返回空列表 */
    static List<double[]> parse(String text) {
        List<double[]> raw = new ArrayList<double[]>();
        if (text == null || text.trim().isEmpty()) return raw;

        // GPX
        Matcher m = TRKPT.matcher(text);
        while (m.find()) raw.add(new double[]{dbl(m.group(1)), dbl(m.group(2))});
        if (!raw.isEmpty()) return clean(raw);

        // TCX
        m = TCPT.matcher(text);
        while (m.find()) raw.add(new double[]{dbl(m.group(1)), dbl(m.group(2))});
        if (!raw.isEmpty()) return clean(raw);

        // 纯文本坐标
        String[] lines = text.split("[\\r\\n]+");
        for (String line : lines) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            String[] parts = s.split("[,\\s]+");
            if (parts.length < 2) continue;
            double lat = dbl(parts[0]), lon = dbl(parts[1]);
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
            raw.add(new double[]{lat, lon});
        }
        return clean(raw);
    }

    private static double dbl(String s) {
        try { return Double.parseDouble(s.trim()); }
        catch (Exception e) { return Double.NaN; }
    }

    /** 去掉非法点、重复点，再按间隔抽稀 */
    static List<double[]> clean(List<double[]> raw) {
        List<double[]> ok = new ArrayList<double[]>();
        for (double[] p : raw) {
            if (Double.isNaN(p[0]) || Double.isNaN(p[1])) continue;
            if (Math.abs(p[0]) > 90 || Math.abs(p[1]) > 180) continue;
            if (p[0] == 0 && p[1] == 0) continue;
            ok.add(p);
        }
        if (ok.size() < 2) return ok;

        // 按累计距离抽稀，最小间隔 8 m，最多 2000 点
        double minGap = 8.0;
        List<double[]> out = new ArrayList<double[]>();
        out.add(ok.get(0));
        for (int i = 1; i < ok.size(); i++) {
            double[] last = out.get(out.size() - 1);
            double[] cur = ok.get(i);
            if (Geo.distM(last[0], last[1], cur[0], cur[1]) >= minGap) out.add(cur);
        }
        double[] lastRaw = ok.get(ok.size() - 1);
        double[] lastOut = out.get(out.size() - 1);
        if (Geo.distM(lastOut[0], lastOut[1], lastRaw[0], lastRaw[1]) > 0.5) out.add(lastRaw);

        if (out.size() > 2000) {
            List<double[]> thin = new ArrayList<double[]>();
            int step = (int) Math.ceil(out.size() / 2000.0);
            for (int i = 0; i < out.size(); i += step) thin.add(out.get(i));
            if (thin.size() >= 2) return thin;
        }
        return out;
    }

    /** 解析结果的长度（米） */
    static double lengthM(List<double[]> pts) {
        double d = 0;
        for (int i = 1; i < pts.size(); i++) {
            d += Geo.distM(pts.get(i - 1)[0], pts.get(i - 1)[1],
                           pts.get(i)[0], pts.get(i)[1]);
        }
        return d;
    }
}
