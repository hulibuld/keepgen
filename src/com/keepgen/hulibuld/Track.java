package com.keepgen.hulibuld;

/**
 * 400 m 标准跑道，建模成「胶囊」：两条直道 + 两个半圆。
 * 现在场地参数来自 Field 对象，所以能换任意场地的任意跑道规格。
 *
 * 真人不贴第一道跑进线走：换道、超车、切内角，每 100 m 横向移动 3~5 m，
 * 但始终不越出基准线 8 m。这里用「随机路点 + smoothstep 插值」建模横向位置。
 *
 * 关键：位置按「实际走过的距离」参数化，不是按中心线弧长。否则横向摆动会让
 * 瞬时速度虚高，一顶到配速上限就得把摆幅压掉。现在横向偏移只改变轨迹形状，
 * 不改变速度，摆幅和配速完全解耦。
 */
final class Track {

    // 横向摆动参数
    static final double GAP_MIN = 80, GAP_MAX = 122;
    static final double STEP_MIN = 3.0, STEP_MAX = 5.0;
    static final double BOUND = 6.2, DETAIL = 0.22, MAX_ABS = 8.0;
    /** 直道/弯道过渡带（米）：弯道进出口各留这么长做平滑 */
    static final double CURVE_BLEND = 18.0;
    /** 弯道摆幅系数：只取向内那一半，再乘这个数，避免弯道上步子被拉长 */
    static final double BEND_K = 0.35;

    private final Field field;
    private final double L, R, LAP, CAP;
    private final double U_E, U_N, W_E, W_N;
    private final boolean ccw;

    private final double[] wpU;
    private final double[] wpY;
    private final double ph1, ph2, detK;

    private static double smoothstep(double t) { return t * t * (3 - 2 * t); }

    Track(Field field, int seed, double span, double k) {
        this.field = field;
        this.L = field.straight;
        this.R = field.laneR;
        this.LAP = field.lapM;
        this.CAP = Math.PI * R;

        double br = field.bearing * Math.PI / 180;
        U_E = Math.sin(br); U_N = Math.cos(br);
        W_E = -U_N; W_N = U_E;

        this.ccw = computeCcw();
        this.detK = k;

        Plan.Rng rand = new Plan.Rng(seed ^ 0x51F3A7);
        int cap = (int) (span / GAP_MIN) + 8;
        double[] us = new double[cap];
        double[] ys = new double[cap];
        int cnt = 0;

        double u = 0;
        double y = (rand.next() * 2 - 1) * 2.0;
        int dir = rand.next() < 0.5 ? -1 : 1;
        us[cnt] = 0; ys[cnt] = y; cnt++;

        double stepMin = STEP_MIN * k, stepMax = STEP_MAX * k;
        double limit = span + 2 * LAP;
        while (u < limit && cnt < cap) {
            double gap = GAP_MIN + rand.next() * (GAP_MAX - GAP_MIN);
            if (rand.next() < 0.5) dir = -dir;
            double len = stepMin + rand.next() * (stepMax - stepMin);
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

    double lap() { return LAP; }

    private boolean computeCcw() {
        double area = 0;
        for (int i = 0; i < 720; i++) {
            double[] p0 = walkRaw(i * LAP / 720.0);
            double[] p1 = walkRaw((i + 1) * LAP / 720.0);
            area += p0[0] * p1[1] - p1[0] * p0[1];
        }
        return area > 0;
    }

    private double[] walkRaw(double s) {
        s = ((s % LAP) + LAP) % LAP;
        if (s < L) return new double[]{-L / 2 + s, R};
        s -= L;
        if (s < CAP) { double th = s / R; return new double[]{L / 2 + R * Math.sin(th), R * Math.cos(th)}; }
        s -= CAP;
        if (s < L) return new double[]{L / 2 - s, -R};
        s -= L;
        double th2 = s / R;
        return new double[]{-L / 2 - R * Math.sin(th2), -R * Math.cos(th2)};
    }

    /** 统一成逆时针（田径场方向） */
    private double[] walk(double s) {
        return walkRaw(ccw ? s : LAP - s);
    }

    private double detailAt(double u) {
        return DETAIL * detK * (0.7 * Math.sin(2 * Math.PI * u / 29 + ph1)
                              + 0.3 * Math.sin(2 * Math.PI * u / 11 + ph2));
    }

    /**
     * 弯道比例：弯道处返回 1，直道返回 0，中间平滑过渡（单位：弧度参数 s）。
     *
     * 人不会全程都往两边随意摆动：直道随便左右走，弯道只会往内侧切
     * （离心方向要多跑路，没人那么干）。所以摆幅在弯道要压向内侧，
     * 不能还是正负乱跳，否则弯道上的点会自己往弯外甩，跑出来的
     * 单步距离比实际配速对应的步长还大，一超过瞬间速度上限就报
     * 「这个配速没有余量」。
     */
    private double bendFactor(double u) {
        double s = ((u % LAP) + LAP) % LAP;
        double edge = Math.max(1e-6, Math.min(CURVE_BLEND, CAP / 2));
        if (s < L) return 0;
        double a = s - L;
        if (a < CAP) return smoothstep(Math.min(1, Math.min(a, CAP - a) / edge));
        return 0;
    }

    /** 累计距离 u 处的横向偏移（米），已按直道/弯道分别处理 */
    double swayAt(double u) {
        double bend = bendFactor(u);
        double raw = swayRaw(u);
        // 弯道只保留向内侧的那一半（负值），并整体缩到 BEND_K 倍
        if (bend <= 0) return raw;
        double inward = Math.min(0, raw) * BEND_K;
        return raw * (1 - bend) + inward * bend;
    }

    private double swayRaw(double u) {
        int n = wpU.length;
        if (u <= 0) return wpY[0] + detailAt(0);
        if (u >= wpU[n - 1]) return wpY[n - 1] + detailAt(u);
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (wpU[mid] <= u) lo = mid; else hi = mid;
        }
        double seg = wpU[hi] - wpU[lo];
        double t = smoothstep(seg > 0 ? (u - wpU[lo]) / seg : 0);
        return wpY[lo] + (wpY[hi] - wpY[lo]) * t + detailAt(u);
    }

    private double[] posRaw(double u) {
        double[] p0 = walk(u - 0.5), p1 = walk(u + 0.5);
        double ta = p1[0] - p0[0], tb = p1[1] - p0[1];
        double nn = Math.hypot(ta, tb);
        if (nn <= 0) nn = 1;
        ta /= nn; tb /= nn;
        double na = -tb, nb = ta;
        double[] cur = walk(u);
        double off = swayAt(u);
        double a = cur[0] + na * off;
        double b = cur[1] + nb * off;
        return new double[]{a * U_E + b * W_E, a * U_N + b * W_N};
    }

    /** 轨迹路径接口：s = 实际走过的距离（米） -> {lat, lon} */
    interface Path {
        double[] at(double s);
    }

    /** 预先建好弧长表 */
    final class TrackPath implements Path {
        // 弧长表步长。原来 0.5 米，慢配速(6:00 以上)每步只走 2.8 米，
        // 网格误差叠上摆动能让单步忽长忽短，出现 3.55 米（上限 2.78 米）的假步长。
        // 收到 0.1 米后，插值误差降到厘米级。
        private final double du = 0.1;
        private final double[] arc;

        TrackPath(double span) {
            int n = (int) Math.ceil(span / du) + 4;
            arc = new double[n];
            double[] p = posRaw(0);
            double pe = p[0], pn = p[1];
            arc[0] = 0;
            for (int i = 1; i < n; i++) {
                double[] q = posRaw(i * du);
                arc[i] = arc[i - 1] + Math.hypot(q[0] - pe, q[1] - pn);
                pe = q[0]; pn = q[1];
            }
        }

        @Override
        public double[] at(double s) {
            int n = arc.length;
            if (s <= 0) { double[] p = posRaw(0); return field.toLatLon(p[0], p[1]); }
            if (s >= arc[n - 1]) { double[] p = posRaw((n - 1) * du); return field.toLatLon(p[0], p[1]); }
            int lo = 0, hi = n - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (arc[mid] <= s) lo = mid; else hi = mid;
            }
            double span = arc[hi] - arc[lo];
            double t = span > 1e-9 ? (s - arc[lo]) / span : 0;
            double[] p = posRaw((lo + t) * du);
            return field.toLatLon(p[0], p[1]);
        }
    }
}
