package com.keepgen.hulibuld;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从高德链接里抠出经纬度，再转成 WGS-84。
 *
 * 认这几种：
 *   https://uri.amap.com/marker?position=102.4328,24.8991&name=操场
 *   https://uri.amap.com/regeo?lng=102.4328&lat=24.8991
 *   https://www.amap.com/regeo?lng=...,lat=...
 *   https://ditu.amap.com/...?lng=...&lat=...
 *   纯文本：102.4328,24.8991   或   24.8991,102.4328
 *
 * 高德的 position 参数是「经度,纬度」，跟国际惯例相反，这里专门处理。
 * 返回 null 表示没认出来。
 */
final class AmapLink {

    static final class LatLon {
        double lat, lon;
        boolean swapped;      // 是否做了经纬度对调
        String raw;
    }

    // position=lon,lat
    private static final Pattern P_POSITION =
            Pattern.compile("position=(-?[0-9.]+)[,%]2C?(-?[0-9.]+)", Pattern.CASE_INSENSITIVE);
    // lng=...&lat=...
    private static final Pattern P_LNG =
            Pattern.compile("(?:lng|lon|longitude)=(-?[0-9.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_LAT =
            Pattern.compile("(?:lat|latitude)=(-?[0-9.]+)", Pattern.CASE_INSENSITIVE);
    // 纯数字对
    private static final Pattern P_PAIR =
            Pattern.compile("(-?[0-9]{1,3}\\.[0-9]{3,})\\s*[,，\\s]\\s*(-?[0-9]{1,3}\\.[0-9]{3,})");

    private AmapLink() {}

    static LatLon parse(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;

        Matcher m = P_POSITION.matcher(s);
        if (m.find()) {
            double lon = dbl(m.group(1)), lat = dbl(m.group(2));
            return make(lat, lon, true);
        }

        Matcher mlng = P_LNG.matcher(s);
        Matcher mlat = P_LAT.matcher(s);
        if (mlng.find() && mlat.find()) {
            return make(dbl(mlat.group(1)), dbl(mlng.group(1)), false);
        }

        // 刚解码过的 %2C
        String decoded = s.replace("%2C", ",").replace("%2c", ",");
        m = P_PAIR.matcher(decoded);
        if (m.find()) {
            double a = dbl(m.group(1)), b = dbl(m.group(2));
            return guess(a, b);
        }
        return null;
    }

    /** 判断哪个是纬度 */
    private static LatLon guess(double a, double b) {
        boolean aLat = a >= 3 && a <= 54;      // 中国纬度范围
        boolean bLon = b >= 73 && b <= 136;    // 中国经度范围
        boolean aLon = a >= 73 && a <= 136;
        boolean bLat = b >= 3 && b <= 54;
        if (aLat && bLon) return make(a, b, false);
        if (aLon && bLat) return make(b, a, true);
        if (Math.abs(a) <= 90) return make(a, b, false);
        return null;
    }

    private static LatLon make(double lat, double lon, boolean swapped) {
        if (!(lat >= -90 && lat <= 90)) return null;
        if (!(lon >= -180 && lon <= 180)) return null;
        // 高德给的是 GCJ-02，转成 WGS-84 再写进文件
        double[] w = Geo.gcj2wgs(lat, lon);
        LatLon ll = new LatLon();
        ll.lat = w[0];
        ll.lon = w[1];
        ll.swapped = swapped;
        ll.raw = lat + "," + lon;
        return ll;
    }

    private static double dbl(String s) {
        try { return Double.parseDouble(s.trim()); }
        catch (Exception e) { return Double.NaN; }
    }
}
