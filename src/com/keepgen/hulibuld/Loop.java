package com.keepgen.hulibuld;

import java.util.ArrayList;
import java.util.List;

/**
 * 不需要地图的路线生成器。三种形状，全靠参数算出来：
 *
 *   ELLIPSE  椭圆环线：给周长和长宽比，反解半轴
 *   OUTBACK  直线折返：给单程长度和方位角，来回两趟
 *   RECT     圆角矩形环线：给周长、直道长度、转弯半径，像操场但任意尺寸
 *
 * 全部返回 WGS-84 路点列表，交给 Route 去撒点。
 */
final class Loop {

    private Loop() {}

    // 椭圆周长（Ramanujan 二阶近似），代入求半轴用
    private static double ellipsePerimeter(double a, double b) {
        double h = Math.pow(a - b, 2) / Math.pow(a + b, 2);
        return Math.PI * (a + b) * (1 + 3 * h / (10 + Math.sqrt(4 - 3 * h)));
    }

    /** 椭圆环线：给定周长 P 与长宽比 ratio（长轴/短轴），反解半轴 */
    static List<double[]> ellipse(double lat0, double lon0, double bearing,
                                  double perimeterM, double ratio, int n) {
        if (perimeterM <= 0) throw new IllegalArgumentException("周长必须大于 0");
        if (ratio < 1) ratio = 1;
        // 二分求短轴 b：P(b) = ellipsePerimeter(b*ratio, b)
        double lo = 0.1, hi = perimeterM;
        for (int i = 0; i < 80; i++) {
            double mid = (lo + hi) / 2;
            double p = ellipsePerimeter(mid * ratio, mid);
            if (p < perimeterM) lo = mid; else hi = mid;
        }
        double b = (lo + hi) / 2;
        double a = b * ratio;

        Field f = new Field(lat0, lon0, bearing, 0, 0);
        double br = bearing * Math.PI / 180;
        double uE = Math.sin(br), uN = Math.cos(br);
        double wE = -uN, wN = uE;

        List<double[]> out = new ArrayList<double[]>(n + 1);
        for (int i = 0; i <= n; i++) {
            double th = 2 * Math.PI * i / n;
            double e = a * Math.cos(th);
            double nn = b * Math.sin(th);
            double east = e * uE + nn * wE;
            double north = e * uN + nn * wN;
            out.add(f.toLatLon(east, north));
        }
        return out;
    }

    /** 圆角矩形环线：直道 straight，半径 r，一圈 = 2*straight + 2*pi*r */
    static List<double[]> roundedRect(double lat0, double lon0, double bearing,
                                      double straight, double r, int perLap) {
        if (straight <= 0 || r <= 0) throw new IllegalArgumentException("直道和半径必须大于 0");
        double lap = 2 * straight + 2 * Math.PI * r;
        Field f = new Field(lat0, lon0, bearing, 0, 0);
        double br = bearing * Math.PI / 180;
        double uE = Math.sin(br), uN = Math.cos(br);
        double wE = -uN, wN = uE;

        List<double[]> out = new ArrayList<double[]>();
        int steps = Math.max(24, perLap);
        for (int i = 0; i <= steps; i++) {
            double s = lap * i / steps;
            double a, b;
            if (s < straight) { a = -straight / 2 + s; b = r; }
            else if (s < straight + Math.PI * r) {
                double th = (s - straight) / r;
                a = straight / 2 + r * Math.sin(th);
                b = r * Math.cos(th);
            } else if (s < 2 * straight + Math.PI * r) {
                a = straight / 2 - (s - straight - Math.PI * r); b = -r;
            } else {
                double th = (s - 2 * straight - Math.PI * r) / r;
                a = -straight / 2 - r * Math.sin(th);
                b = -r * Math.cos(th);
            }
            out.add(f.toLatLon(a * uE + b * wE, a * uN + b * wN));
        }
        return out;
    }

    /** 直线折返：单程 oneWayM，方位角 bearing */
    static List<double[]> outAndBack(double lat0, double lon0, double bearing, double oneWayM) {
        if (oneWayM <= 0) throw new IllegalArgumentException("单程长度必须大于 0");
        double br = bearing * Math.PI / 180;
        double east = oneWayM * Math.sin(br);
        double north = oneWayM * Math.cos(br);
        Field f = new Field(lat0, lon0, bearing, 0, 0);
        List<double[]> out = new ArrayList<double[]>();
        int n = Math.max(2, (int) Math.ceil(oneWayM / 50));
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            out.add(f.toLatLon(east * t, north * t));
        }
        return out;
    }
}
