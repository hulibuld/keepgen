package com.qianyuzhou.keepgen;

/**
 * 一个田径场（或任意场地的基准点）。
 *
 * 原来是写死的静态常量，现在做成对象，这样能换场地：
 *   · 只改中心点经纬度 + 长轴方位角，就搬到另一个学校
 *   · 改 straight / laneR，就换成 300 m / 200 m 跑道
 *
 * 坐标一律 WGS-84（TCX / FIT / GPX 都不能带 GCJ-02）。
 */
final class Field {

    final double lat0, lon0;     // 中心点
    final double bearing;        // 长轴方位角，度（从正北顺时针）
    final double straight;       // 直道长度，米
    final double laneR;          // 第一道跑进线半径，米
    final double lapM;           // 一圈长度 = 2*straight + 2*pi*laneR

    static final double M_PER_DEG_LAT = 110771.0;

    Field(double lat0, double lon0, double bearing, double straight, double laneR) {
        this.lat0 = lat0;
        this.lon0 = lon0;
        this.bearing = bearing;
        this.straight = straight;
        this.laneR = laneR;
        this.lapM = 2 * straight + 2 * Math.PI * laneR;
    }

    double mPerDegLon() {
        return 111320.0 * Math.cos(lat0 * Math.PI / 180.0);
    }

    /** 平面偏移（东、北，米）-> 经纬度 */
    double[] toLatLon(double east, double north) {
        return new double[]{
                lat0 + north / M_PER_DEG_LAT,
                lon0 + east / mPerDegLon()
        };
    }

    /** 经纬度 -> 平面偏移（东、北，米） */
    double[] fromLatLon(double lat, double lon) {
        return new double[]{
                (lon - lon0) * mPerDegLon(),
                (lat - lat0) * M_PER_DEG_LAT
        };
    }

    // ---- 预设 -------------------------------------------------------------

    /** 云南经济管理学院 安宁校区足球场 400 m 标准田径场 */
    static Field anning() {
        return new Field(24.8991247, 102.43281125, 148.7, 84.39, 36.80);
    }

    /** 标准 400 m 跑道（直道 84.39，第一道跑进线半径 36.80） */
    static Field std400(double lat, double lon, double bearing) {
        return new Field(lat, lon, bearing, 84.39, 36.80);
    }

    /** 标准 300 m 跑道 */
    static Field std300(double lat, double lon, double bearing) {
        return new Field(lat, lon, bearing, 67.00, 26.40);
    }

    /** 标准 200 m 跑道 */
    static Field std200(double lat, double lon, double bearing) {
        return new Field(lat, lon, bearing, 42.00, 18.50);
    }

    String describe() {
        long lapRounded = Math.round(lapM / 10) * 10;
        return String.format(java.util.Locale.US, "%d m 跑道 · 中心 %.6f, %.6f · 方位 %.1f°",
                lapRounded, lat0, lon0, bearing);
    }
}
