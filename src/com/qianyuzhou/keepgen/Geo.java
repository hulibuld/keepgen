package com.qianyuzhou.keepgen;

/** 坐标系常量。云南经济管理学院安宁校区足球场 400 m 标准田径场，第一道跑进线。 */
final class Geo {
    // 中心点（WGS-84，TCX/FIT 一律用 WGS-84，不用 GCJ-02）
    static final double LAT0 = 24.8991247;
    static final double LNG0 = 102.43281125;
    static final double BEARING = 148.7;   // 长轴方位角，度
    static final double STRAIGHT = 84.39;  // 直道长度
    static final double LANE_R = 36.80;    // 第一道半径

    static final double M_PER_DEG_LAT = 110771.0;
    // 保留给旧的常量调用方；新代码请用 Field.mPerDegLon()，因为场地可以换
    static final double M_PER_DEG_LON = 111320.0 * Math.cos(LAT0 * Math.PI / 180.0);

    private Geo() {}

    static double[] toLatLon(double east, double north) {
        return new double[]{
                LAT0 + north / M_PER_DEG_LAT,
                LNG0 + east / M_PER_DEG_LON
        };
    }

    /** 两点球面距离（米），haversine */
    static double distM(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000.0;
        double dLat = (lat2 - lat1) * Math.PI / 180.0;
        double dLon = (lon2 - lon1) * Math.PI / 180.0;
        double sa = Math.sin(dLat / 2), so = Math.sin(dLon / 2);
        double h = sa * sa + Math.cos(lat1 * Math.PI / 180.0) * Math.cos(lat2 * Math.PI / 180.0) * so * so;
        return R * 2 * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    // ---- GCJ-02 -> WGS-84（腾讯/高德坐标转标准坐标） ----
    private static final double GCJ_A = 6378245.0;
    private static final double GCJ_EE = 0.00669342162296594323;

    static boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double tfLat(double x, double y) {
        double r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        r += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        r += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        r += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return r;
    }

    private static double tfLon(double x, double y) {
        double r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        r += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        r += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        r += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return r;
    }

    static double[] wgs2gcj(double lat, double lon) {
        if (outOfChina(lat, lon)) return new double[]{lat, lon};
        double dLat = tfLat(lon - 105.0, lat - 35.0);
        double dLon = tfLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - GCJ_EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic) * Math.PI);
        dLon = (dLon * 180.0) / (GCJ_A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lon + dLon};
    }

    static double[] gcj2wgs(double lat, double lon) {
        if (outOfChina(lat, lon)) return new double[]{lat, lon};
        double wLat = lat, wLon = lon;
        for (int i = 0; i < 8; i++) {
            double[] g = wgs2gcj(wLat, wLon);
            wLat -= g[0] - lat;
            wLon -= g[1] - lon;
        }
        return new double[]{wLat, wLon};
    }
}
