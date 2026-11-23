package com.qianyuzhou.keepgen;

/**
 * 云端骨架路径（v22）。
 *
 * ── 是什么 ──────────────────────────────────────────────────
 *
 * 云端只给「一圈的形状」：模板点（米制偏移，曲率自适应，弯道弧长 ≤12 米一点），
 * 外加每圈的横向漂移。客户端把它展开成任意距离的轨迹。
 *
 * ── 为什么要重采样 + 连续法向 ────────────────────────────────
 *
 * 第一版直接在模板顶点间插值、按「当前段法向」叠偏移，结果在圈接缝、
 * 直道与弯道交界处出现 2~6 米的法向跳变，步长瞬间超过配速上限，
 * `Generator` 直接抛「这个配速没有余量」。
 * 现在：① 模板重采样成 1 米的稠密环；② 法向由相邻稠密点中心差分得到；
 * ③ 摆动用 lapM 的整数谐波（保证 pos=0 与 pos=lapM 处连续）；
 * ④ 每圈漂移按圈内进度线性过渡，不在接缝处跳。
 *
 * ── 不是防线 ────────────────────────────────────────────────
 *
 * 破解者能改包，也能用包内 Track/Loop 自己造几何。这里只负责省钱、
 * 让轨迹每次不同、让摆动看起来像人跑的。真正的防线在服务端（核销、日限）。
 */
final class SkeletonPath implements Track.Path {

    private final Field field;
    private final double lapM;
    private final double step;          // 稠密环的采样步长（米）
    private final double[] px, py;      // 稠密环上的点（米，相对场地中心）
    private final double[] nx, ny;      // 稠密环上的单位法向
    private final byte[] drift;         // 每圈横向漂移（0.1 米）
    private final double ph0, ph1;

    SkeletonPath(double[] east, double[] north, byte[] drift, Field field, int seed) {
        this.field = field;
        this.drift = (drift != null && drift.length > 0) ? drift : new byte[]{0};

        int m = east.length;
        double[] seg = new double[m];
        double total = 0;
        for (int i = 0; i < m; i++) {
            int j = (i + 1) % m;
            seg[i] = Math.hypot(east[j] - east[i], north[j] - north[i]);
            total += seg[i];
        }
        if (total < 1) {                     // 退化：至少给一个点
            this.lapM = 1;
            this.step = 1;
            this.px = new double[]{east[0]};
            this.py = new double[]{north[0]};
            this.nx = new double[]{1};
            this.ny = new double[]{0};
            this.ph0 = 0; this.ph1 = 0;
            return;
        }
        this.lapM = total;
        this.step = 1.0;
        int dn = Math.max(8, (int) Math.round(total / step));
        px = new double[dn];
        py = new double[dn];

        // 把模板按弧长重采样成 dn 个等距点
        int si = 0;
        double acc = 0;
        for (int k = 0; k < dn; k++) {
            double target = total * k / dn;
            while (si < m - 1 && acc + seg[si] < target) {
                acc += seg[si];
                si++;
            }
            double t = seg[si] > 1e-9 ? (target - acc) / seg[si] : 0;
            if (t < 0) t = 0;
            if (t > 1) t = 1;
            int j = (si + 1) % m;
            px[k] = east[si] + (east[j] - east[si]) * t;
            py[k] = north[si] + (north[j] - north[si]) * t;
        }

        // 中心差分求切向，再取法向。稠密环保证法向连续。
        nx = new double[dn];
        ny = new double[dn];
        for (int k = 0; k < dn; k++) {
            int a = (k - 1 + dn) % dn, b = (k + 1) % dn;
            double tx = px[b] - px[a], ty = py[b] - py[a];
            double nn = Math.hypot(tx, ty);
            if (nn < 1e-9) { nx[k] = 0; ny[k] = 1; continue; }
            nx[k] = -ty / nn;
            ny[k] = tx / nn;
        }

        Plan.Rng rng = new Plan.Rng(seed ^ 0x2C1B);
        ph0 = rng.next() * Math.PI * 2;
        ph1 = rng.next() * Math.PI * 2;
    }

    @Override
    public double[] at(double s) {
        int dn = px.length;
        double lap = Math.floor(s / lapM);
        int li = (int) lap;
        double pos = s - lap * lapM;
        if (pos < 0) pos = 0;
        if (pos >= lapM) pos = lapM - 1e-6;

        double f = pos / lapM * dn;
        int i0 = (int) Math.floor(f);
        int i1 = (i0 + 1) % dn;
        double t = f - i0;

        double cx = px[i0] + (px[i1] - px[i0]) * t;
        double cy = py[i0] + (py[i1] - py[i0]) * t;
        double ux = nx[i0] + (nx[i1] - nx[i0]) * t;
        double uy = ny[i0] + (ny[i1] - ny[i0]) * t;
        double un = Math.hypot(ux, uy);
        if (un > 1e-9) { ux /= un; uy /= un; }

        double off = driftAt(li, pos) + swayAt(pos);
        return field.toLatLon(cx + ux * off, cy + uy * off);
    }

    /** 每圈漂移按圈内进度线性过渡 —— 接缝处不跳，跑起来也不会瞬移 */
    private double driftAt(int lap, double pos) {
        int a = Math.floorMod(lap, drift.length);
        int b = Math.floorMod(lap + 1, drift.length);
        double da = drift[a] * 0.1;
        double db = drift[b] * 0.1;
        return da + (db - da) * (pos / lapM);
    }

    /** 摆动：用 lapM 的整数谐波，pos=0 与 pos=lapM 处严格相等 */
    private double swayAt(double pos) {
        double w = 2 * Math.PI * pos / lapM;
        return 1.0 * Math.sin(4 * w + ph0) + 0.35 * Math.sin(9 * w + ph1);
    }

    double per100m(double distM) {
        double sum = 0;
        int cnt = 0;
        for (double s = 0; s + 100 <= distM; s += 100) {
            sum += Math.abs(swayAt((s + 100) % lapM) - swayAt(s % lapM));
            cnt++;
        }
        return cnt > 0 ? sum / cnt : 0;
    }

    double maxAbs(double distM) {
        double max = 0;
        double d = Math.max(1, distM / 400);
        for (double s = 0; s <= distM; s += d) {
            max = Math.max(max, Math.abs(swayAt(s % lapM)));
        }
        return max;
    }

    double lapM() { return lapM; }
}