package com.keepgen.hulibuld;

/**
 * 配速曲线 / 心率 / 步频模型。所有量都从目标配速推出来，保证物理自洽：
 * 没有任何一个采样点能快过 4:00/km，因为瞬时速度上限就来自这个常数。
 *
 * 步频（cadence）是 Keep 反推步数、步幅、触地时间、垂直振幅的唯一依据，
 * 缺了它就报「步频不符，数据无法计算」。这里必须由逐点瞬时速度推出，
 * 不能写常数，否则步频与实际配速对不上。
 */
final class Plan {
    static final int PACE_CAP = 240;                 // 4:00/km 上限（秒）
    static final double HR_FAST_PACE = 240, HR_FAST_MID = 170, HR_FAST_HALF = 10;
    static final double HR_SLOW_PACE = 600, HR_SLOW_MID = 97.5, HR_SLOW_HALF = 22.5;

    // cadence(步/分) = C0 + C1 * v(m/s)
    //   v = 4.167 (4:00/km) -> 186 spm
    //   v = 3.333 (5:00/km) -> 174 spm
    //   v = 1.667 (10:00/km) -> 150 spm
    static final double CAD_C0 = 126.0, CAD_C1 = 14.4;
    static final int CAD_MIN = 118, CAD_MAX = 206;

    private Plan() {}

    // ---- 确定性随机 --------------------------------------------------------
    static final class Rng {
        private int t;
        Rng(int seed) { t = seed; }
        double next() {
            t += 0x6D2B79F5;
            int x = t;
            x = (x ^ (x >>> 15)) * (x | 1);
            x ^= x + (x ^ (x >>> 7)) * (x | 61);
            return ((x ^ (x >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
        }
        double gauss() {
            double u = Math.max(next(), 1e-9), v = next();
            return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
        }
    }

    static double ampFor(double pace) {
        return Math.min(0.20, 0.8 * (pace - PACE_CAP) / pace);
    }

    /**
     * 归一化距离权重，一个采样点一个；返回 {w[], amp}。
     *
     * @param paceCap 该项运动的「最快一公里」秒数，跑步 240、行走 300、骑行 60。
     *
     * 这里曾经固定用跑步的 240 当上限，导致行走 6:00 配速时允许某个点的峰速
     * 达到 4:00 那一档（4.17 m/s），而 Generator 校验时用的是行走自己的
     * 5:00 上限（3.33 m/s），于是必然顶到上限报「这个配速没有余量」。
     * 用户把配速放到 8:30 也没用——错的是上限基准，不是配速。
     */
    static double[][] buildWeights(int n, double dt, double pace, int seed, double paceCap) {
        Rng rand = new Rng(seed);
        double amp = ampFor(pace);
        double P1 = 210 + 90 * rand.next();
        double P2 = 43 + 20 * rand.next();
        double ph1 = rand.next() * Math.PI * 2, ph2 = rand.next() * Math.PI * 2;
        double limit = pace / paceCap;

        double[] w = new double[n];
        for (int guard = 0; guard < 40; guard++) {
            double sum = 0, mx = 0;
            for (int i = 0; i < n; i++) {
                double t = i * dt;
                double v = 1
                        + amp * (0.72 * Math.sin(2 * Math.PI * t / P1 + ph1)
                               + 0.28 * Math.sin(2 * Math.PI * t / P2 + ph2))
                        + 0.15 * amp * rand.gauss();
                w[i] = v; sum += v; if (v > mx) mx = v;
            }
            double mean = sum / n;
            double peak = 0;
            for (int i = 0; i < n; i++) { w[i] /= mean; if (w[i] > peak) peak = w[i]; }
            if (peak <= limit * 0.999) return new double[][]{w, {amp}};
            amp *= 0.85;
        }
        for (int i = 0; i < n; i++) w[i] = 1;
        return new double[][]{w, {0}};
    }

    // ---- 心率 --------------------------------------------------------------
    static int[] hrEnvelope(int n, double dt, double durSec, double pace, int seed) {
        double p = Math.max(240, Math.min(600, pace));
        double k = (p - HR_FAST_PACE) / (HR_SLOW_PACE - HR_FAST_PACE);
        double mid = HR_FAST_MID + (HR_SLOW_MID - HR_FAST_MID) * k;
        double half = HR_FAST_HALF + (HR_SLOW_HALF - HR_FAST_HALF) * k;
        Rng rand = new Rng(seed ^ 0x9E37);
        double ph = rand.next() * Math.PI * 2;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            double t = i * dt;
            double hr = mid + half * (0.6 * Math.sin(2 * Math.PI * t / 260 + ph) + 0.4 * rand.gauss());
            double warm = Math.min(1, t / 180);
            double cool = Math.min(1, Math.max(0, (durSec - t) / 90));
            hr = 70 + (hr - 70) * warm * cool;
            out[i] = (int) Math.max(60, Math.min(200, Math.round(hr)));
        }
        return out;
    }

    // ---- 步频 --------------------------------------------------------------
    static double cadenceFor(double v) {
        double c = CAD_C0 + CAD_C1 * v;
        return Math.max(CAD_MIN, Math.min(CAD_MAX, c));
    }

    static int[] cadenceSeries(int n, double dt, int seed, double[] speedAt) {
        Rng rand = new Rng(seed ^ 0x51ED);
        double ph1 = rand.next() * Math.PI * 2, ph2 = rand.next() * Math.PI * 2;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            double t = i * dt;
            double base = cadenceFor(speedAt[i]);
            double drift = 2.6 * Math.sin(2 * Math.PI * t / 197 + ph1)
                         + 1.1 * Math.sin(2 * Math.PI * t / 41 + ph2)
                         + 0.9 * rand.gauss();
            out[i] = (int) Math.max(CAD_MIN, Math.min(CAD_MAX, Math.round(base + drift)));
        }
        return out;
    }

    /** 由步频反推的跑步动力学，Keep 自己会算，这里只用于界面预览 */
    static double[] runDynamics(double v, double cad) {
        double stepDurMs = 60000.0 / cad;
        double duty = 0.78 - 0.0009 * cad;
        double gct = stepDurMs * Math.max(0.5, Math.min(0.8, duty));
        double vo = 9.4 - 0.9 * (cad - 150) / 36.0;
        double stepLen = v / (cad / 60.0);
        return new double[]{gct, vo, stepLen};
    }

    /**
     * 采样间隔。
     *
     * 2026-11-23 改成「5 秒一点」：Keep 导入不需要 1 秒粒度，点数从约 1800
     * 降到约 364，TCX 体积降到 1/5、云端骨架也不用跟着密。
     *
     * 但不能一律 5 秒：400 m 一圈按 5:00/km 只有 120 秒 → 25 个点，
     * 横向摆动（波长 80~122 m）只剩 3~4 个采样点，轨迹会贴成一条线。
     * 所以取 min(5, 时长/120)，下限 1 秒：
     *   5.5 km / 30 min -> 5 秒（364 点）
     *   400 m  / 2 min  -> 1 秒（121 点）
     */
    static double pickDt(double pace, double lapMetres, double durationSec) {
        double d = Math.max(1.0, durationSec / 120.0);
        if (d > 5.0) d = 5.0;
        return Math.rint(d);
    }
}
