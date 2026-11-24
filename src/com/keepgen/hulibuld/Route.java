package com.keepgen.hulibuld;

import java.util.ArrayList;
import java.util.List;

/**
 * 任意路线模式：给定一串路点（WGS-84），沿路径按目标配速撒点。
 *
 * 与固定跑道模式的差别：
 *   · 路径任意形状，来自用户粘贴的坐标 / 导入的 GPX / TCX
 *   · 起终点可不重合（Open），也可首尾相接（Closed 环线）
 *   · 距离不够就沿路径折返，直到凑够目标里程
 *   · 横向摆动按垂直路径方向施加，同样有 8 m 上限
 */
final class Route {

    static final double GAP_MIN = 80, GAP_MAX = 122;
    static final double STEP_MIN = 3.0, STEP_MAX = 5.0;
    static final double BOUND = 6.2, DETAIL = 0.22, MAX_ABS = 8.0;

    private final List<double[]> base;
    private final double[] cum;
    private final double legLen;
    private final int legsN;
    private final double[] wpU;
    private final double[] wpY;
    private final double ph1, ph2;
    private final double targetM;
    private final double avgLat;
    /** 闭合环线：跑完一圈接着同向跑下一圈，不折返 */
    private final boolean closed;

    Route(List<double[]> path, double targetM, boolean loop, int seed) {
        this.targetM = targetM;

        List<double[]> b = new ArrayList<double[]>(path);
        if (loop && b.size() >= 2) {
            double[] first = b.get(0), last = b.get(b.size() - 1);
            if (Geo.distM(first[0], first[1], last[0], last[1]) > 1.0) {
                b.add(new double[]{first[0], first[1]});
            }
        }
        base = b;
        closed = loop;
        cum = new double[b.size()];
        double latSum = 0;
        for (int i = 0; i < b.size(); i++) latSum += b.get(i)[0];
        avgLat = latSum / b.size();
        for (int i = 1; i < b.size(); i++) {
            cum[i] = cum[i - 1] + Geo.distM(b.get(i - 1)[0], b.get(i - 1)[1],
                                            b.get(i)[0], b.get(i)[1]);
        }
        legLen = cum[cum.length - 1];
        if (legLen <= 0) throw new IllegalArgumentException("路线长度为零，至少给两个不同点");
        legsN = targetM > legLen ? (int) Math.ceil(targetM / legLen) : 1;

        Plan.Rng rand = new Plan.Rng(seed ^ 0x51F3A7);
        int cap = (int) (targetM / GAP_MIN) + 32;
        double[] us = new double[cap];
        double[] ys = new double[cap];
        int cnt = 0;
        double u = 0;
        double y = (rand.next() * 2 - 1) * 2.0;
        int dir = rand.next() < 0.5 ? -1 : 1;
        us[cnt] = 0; ys[cnt] = y; cnt++;
        double limit = targetM + legLen * 2;
        while (u < limit && cnt < cap) {
            double gap = GAP_MIN + rand.next() * (GAP_MAX - GAP_MIN);
            if (rand.next() < 0.5) dir = -dir;
            double len = STEP_MIN + rand.next() * (STEP_MAX - STEP_MIN);
            double ny = y + dir * len;
            if (Math.abs(ny) > BOUND) { dir = -dir; ny = y + dir * len; }
            u += gap;
            y = ny;
            us[cnt] = u; ys[cnt] = y; cnt++;
        }
        wpU = java.util.Arrays.copyOf(us, cnt);
        wpY = java.util.Arrays.copyOf(ys, cnt);

        Plan.Rng r2 = new Plan.Rng(seed ^ 0x2F91);
        ph1 = r2.next() * Math.PI * 2;
        ph2 = r2.next() * Math.PI * 2;
    }

    double length() { return legLen; }
    int legs() { return legsN; }

    private static double smoothstep(double t) { return t * t * (3 - 2 * t); }

    /**
     * 折返点前后的摆幅收敛带（米）。
     *
     * 这里曾经有个严重 bug：折返时法线方向整体翻转，而摆幅 off 原样带过去，
     * 于是位置在一个采样点内平移 2*off（最多 16 m），算出 6.64 m/s 的假速度
     * （相当于 2:31/km），越过 4:00/km 上限，界面报「这个配速没有余量，请放慢一点」。
     * 用户把配速放到 8:30 也没用，因为假速度跟配速无关。
     *
     * 现在让摆幅在折返前后平滑归零：人到掉头点本来就会收拢线路，这是真实行为，
     * 也顺便保证位置连续。
     */
    static final double TURN_TAPER_M = 12.0;

    /** 到最近折返点的距离（米）；不在折返附近返回一个大于收敛带的数 */
    /**
     * 到最近「方向突变处」的距离。
     * 开放路线是两端折返点；闭合环线是首尾接缝。
     * 两处都必须把摆幅收敛到 0，否则位置会瞬时跳变 2*摆幅。
     */
    private double distToTurn(double s) {
        double legIdx = Math.floor(s / legLen);
        double within = s - legIdx * legLen;
        return Math.min(within, legLen - within);
    }

    private double swayAt(double u) {
        double detail = DETAIL * (0.7 * Math.sin(2 * Math.PI * u / 29 + ph1)
                                + 0.3 * Math.sin(2 * Math.PI * u / 11 + ph2));
        int n = wpU.length;
        if (u <= 0) return wpY[0] + detail;
        if (u >= wpU[n - 1]) return wpY[n - 1] + detail;
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (wpU[mid] <= u) lo = mid; else hi = mid;
        }
        double seg = wpU[hi] - wpU[lo];
        double t = smoothstep(seg > 0 ? (u - wpU[lo]) / seg : 0);
        return wpY[lo] + (wpY[hi] - wpY[lo]) * t + detail;
    }

    /** 带折返收敛的摆幅：rawAt 与统计都用它，保证轨迹和指标是同一份数据 */
    private double swayWithTaper(double s) {
        double taper = Math.min(1.0, distToTurn(s) / TURN_TAPER_M);
        return swayAt(s) * smoothstep(taper);
    }

    private double[] pointAt(double s) {
        if (s <= 0) return new double[]{base.get(0)[0], base.get(0)[1]};
        if (s >= legLen) {
            double[] p = base.get(base.size() - 1);
            return new double[]{p[0], p[1]};
        }
        int lo = 0, hi = cum.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (cum[mid] <= s) lo = mid; else hi = mid;
        }
        double seg = cum[hi] - cum[lo];
        double t = seg > 0 ? (s - cum[lo]) / seg : 0;
        return new double[]{
                base.get(lo)[0] + (base.get(hi)[0] - base.get(lo)[0]) * t,
                base.get(lo)[1] + (base.get(hi)[1] - base.get(lo)[1]) * t
        };
    }

    /** 实际弧长 s 处的经纬度（含折返与横向摆动） */
    double[] rawAt(double s) {
        int legIdx = (int) Math.floor(s / legLen);
        double within = s - legIdx * legLen;
        // 闭合环线同向重复；只有开放路线才在两端折返。
        // 环线折返等于倒着绕圈，方向翻转会在掉头处制造假速度尖峰。
        boolean back = !closed && (legIdx % 2 == 1);
        double u = back ? (legLen - within) : within;

        double[] p = pointAt(u);
        double e = 1.0;
        double[] a = pointAt(Math.max(0, u - e));
        double[] b = pointAt(Math.min(legLen, u + e));
        double dLat = b[0] - a[0];
        double dLon = (b[1] - a[1]) * Math.cos(p[0] * Math.PI / 180);
        double nn = Math.hypot(dLat, dLon);
        if (nn <= 0) nn = 1;
        double tLat = dLat / nn, tLon = dLon / nn;

        double nLat = back ? tLon : -tLon;
        double nLon = back ? -tLat : tLat;
        // 摆幅在折返点收敛到 0，否则法线翻转时位置会跳 2*off
        double off = swayWithTaper(s);

        double mLon = 111320.0 * Math.cos(avgLat * Math.PI / 180);
        return new double[]{p[0] + (nLat * off) / Field.M_PER_DEG_LAT,
                            p[1] + (nLon * off) / mLon};
    }

    /** 建弧长表后返回 Path */
    Track.Path path() {
        // 原来 1 米一格。折返是 180° 尖角，粗采样会把尖角削掉，
        // 查表插值时相邻两格之间会插出一个虚假大步（最高到 5.58 m/s）。
        // 改成 0.25 米，并让网格与折返点对齐，尖角才不会被削平。
        final double du = 0.25;
        double span = Math.max(legLen, targetM) + legLen * 2;
        // 网格按 legLen 对齐：每格边界正好落在某趟的起终点上
        int n = (int) Math.ceil(span / du) + 8;
        final double[] arc = new double[n];
        // 坐标一并存下来，查表时直接插值。
        // 原来只存弧长、查到后拿 (lo+t)*du 回头再算一次位置，
        // 在接缝附近会落回另一圈，算出 8.15 m/s 的假速度。
        final double[] la = new double[n];
        final double[] lo2 = new double[n];
        double[] prev = rawAt(0);
        arc[0] = 0;
        la[0] = prev[0]; lo2[0] = prev[1];
        for (int i = 1; i < n; i++) {
            double[] p = rawAt(i * du);
            arc[i] = arc[i - 1] + Geo.distM(prev[0], prev[1], p[0], p[1]);
            la[i] = p[0]; lo2[i] = p[1];
            prev = p;
        }
        return new Track.Path() {
            @Override
            public double[] at(double s) {
                int m = arc.length;
                if (s <= 0) return rawAt(0);
                if (s >= arc[m - 1]) return rawAt((m - 1) * du);
                int lo = 0, hi = m - 1;
                while (hi - lo > 1) {
                    int mid = (lo + hi) >>> 1;
                    if (arc[mid] <= s) lo = mid; else hi = mid;
                }
                double seg = arc[hi] - arc[lo];
                double t = seg > 0 ? (s - arc[lo]) / seg : 0;
                return new double[]{la[lo] + (la[hi] - la[lo]) * t,
                                    lo2[lo] + (lo2[hi] - lo2[lo]) * t};
            }
        };
    }

    double per100m() {
        double sum = 0; int cnt = 0;
        for (double s = 0; s + 100 <= targetM; s += 100) {
            sum += Math.abs(swayWithTaper(s + 100) - swayWithTaper(s));
            cnt++;
        }
        return cnt > 0 ? sum / cnt : 0;
    }

    double maxAbs() {
        double mx = 0;
        for (double s = 0; s <= targetM; s += Math.max(1, targetM / 400)) {
            mx = Math.max(mx, Math.abs(swayWithTaper(s)));
        }
        return mx;
    }
}
