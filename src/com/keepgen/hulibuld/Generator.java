package com.keepgen.hulibuld;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** 生成核心：纯计算，无 IO。输出 TCX / FIT 两种格式。 */
final class Generator {
    static final double MAX_TIME = 4 * 3600;
    static final double MAX_DIST_M = 50000;
    static final double PACE_CAP = 240;
    static final double V_CAP = 1000.0 / PACE_CAP;

    /** 每种运动的配速上下限 */
    static final class Sport {
        final String key, label, tcxSport;
        final double paceCap, paceFloor, calPerKm;
        final boolean cadence;
        Sport(String k, String l, String t, double cap, double floor, double cal, boolean cad) {
            key = k; label = l; tcxSport = t;
            paceCap = cap; paceFloor = floor; calPerKm = cal; cadence = cad;
        }
    }
    static final Sport RUN = new Sport("run", "户外跑步", "Running", 240, 1800, 1.036, true);
    static final Sport TREAD = new Sport("tread", "室内跑步", "Running", 240, 1800, 1.036, true);
    static final Sport WALK = new Sport("walk", "户外行走", "Walking", 300, 3600, 0.85, true);
    static final Sport BIKE = new Sport("bike", "户外骑行", "Biking", 60, 1200, 0.5, false);

    static Sport sportOf(String key) {
        if ("tread".equals(key)) return TREAD;
        if ("walk".equals(key)) return WALK;
        if ("bike".equals(key)) return BIKE;
        return RUN;
    }

    static final class Req {
        double distKm;
        double paceSec;
        double durSec;
        long startMs;
        boolean hr;
        boolean cadence = true;
        int seed = 7;
        double weightKg = 62;
        String sport = "run";
        List<double[]> route;      // 可选：{lat, lon}，给了就走任意路线模式
        boolean loop;
        Field field = Field.anning();   // 跑道模式用哪个场地
        HrProfile hrProfile;            // 心率参数，null 时用默认
        boolean wantTcx = true, wantFit = true;
        // 云端骨架（v22）。三者都非空才按骨架跑；为空时退回本操场几何。
        double[] skelEast, skelNorth;
        byte[] skelDrift;
    }

    static final class Result {
        String tcx;
        byte[] fit;
        double distM, durSec, paceSec, dt;
        int points;
        long startMs;
        String sportKey, sportLabel;
        double avgSpm, minSpm, maxSpm, totalSteps;
        double gctMs, verticalOscCm, strideM;
        int calories;
        List<double[]> poly;
        List<double[]> splits;     // {sec, startMs, endMs, distM}
        double swayPer100m, swayMaxAbs;
        // 直写 Keep（v5/sportlog）用的逐点序列。微信/DOM 那些字段不存在时才为 null。
        double[][] latlon;   // 逐点 {lat, lon}，WGS84；null = 无轨迹（室内跑步）
        int[] hrs;           // 逐点心率 bpm，null = 无心率
        int[] cads;          // 逐点步频（双脚 spm），null = 无步频
        int[] stepsCum;      // 逐点累计步数，末位即 totalSteps
        boolean doubtfulHint; // 上传后被 Keep 标「存疑」时置 true，界面据此提醒
        int routeLegs;
        double routeLength;
        int laps;
        String fieldDesc;

        String distKmText() {
            return String.format(Locale.US, "%.2f km", distM / 1000.0);
        }
    }

    static String clock(double sec) {
        long s = Math.round(sec);
        long h = s / 3600, m = (s % 3600) / 60, ss = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, ss)
                     : String.format(Locale.US, "%d:%02d", m, ss);
    }

    static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    static Result generate(Req r) {
        Sport sp = sportOf(r.sport);
        double distM = r.distKm * 1000;
        double paceSec = r.paceSec;
        double durSec = r.durSec;
        if (!(distM > 0)) throw new IllegalArgumentException("距离必须大于 0");
        if (distM > MAX_DIST_M) throw new IllegalArgumentException("距离超过 50 公里");
        if (!(paceSec > 0)) paceSec = durSec / r.distKm;
        if (!(durSec > 0)) durSec = Math.round(r.distKm * paceSec);
        if (durSec > MAX_TIME) throw new IllegalArgumentException("时长超过 4 小时");
        if (paceSec < sp.paceCap) {
            throw new IllegalArgumentException(sp.label + "最快只能 " + clock(sp.paceCap)
                    + " 一公里，现在是 " + clock(paceSec));
        }
        if (paceSec > sp.paceFloor) {
            throw new IllegalArgumentException(sp.label + "最慢只能 " + clock(sp.paceFloor) + " 一公里");
        }

        // 速度上限按运动类型算：骑行 1:00/km = 16.7 m/s，跑步 4:00/km = 4.17 m/s
        double vCap = 1000.0 / sp.paceCap;

        double dt = Plan.pickDt(paceSec, r.field.lapM, durSec);
        int n = (int) Math.floor(durSec / dt) + 1;
        double[][] built = Plan.buildWeights(n, dt, paceSec, r.seed, sp.paceCap);
        double[] w = built[0];

        double wSum = 0;
        for (int i = 1; i < n; i++) wSum += w[i];

        boolean withCad = r.cadence && sp.cadence;
        HrProfile hrp = r.hrProfile != null ? r.hrProfile : new HrProfile();
        hrp.sanitize();

        boolean isRoute = r.route != null && r.route.size() >= 2;

        // 室内跑步不生成轨迹。
        //
        // 室内页生成 5.5km 的文件里曾出现 2669 个 <Position>，坐标全落在内置操场。可是室内页自己写着「跑步机不记录轨迹」，
        // 生成的数据带 GPS 反而露馅。
        //
        // 原来 isRoute 为 false 时会走 new Track(r.field, ...)（内置操场），
        // 所以「没有路线」不等于「没有坐标」。现在按运动类型再分一次。
        boolean isTread = TREAD.key.equals(r.sport);

        Route traveller = isRoute ? new Route(r.route, distM, r.loop, r.seed ^ 0x5A5A) : null;

        double swayK = 1;
        List<double[]> latlon = null;
        List<Double> dists = null;
        List<Double> speeds = null;
        double maxStep = 0;
        Track track = null;
        int[] hrs = r.hr ? new int[n] : null;

        SkeletonPath skel = null;
        if (!isTread && !isRoute && r.skelEast != null && r.skelEast.length >= 3) {
            skel = new SkeletonPath(r.skelEast, r.skelNorth, r.skelDrift, r.field, r.seed);
        }

        for (int attempt = 0; attempt < 10; attempt++) {
            Track.Path path = null;
            if (isTread) {
                // 室内跑步：没有任何地理坐标
                path = null;
            } else if (isRoute) {
                path = traveller.path();
            } else if (skel != null) {
                // 骨架模式：几何由云端模板展开，本地只叠加摆动
                path = skel;
            } else {
                track = new Track(r.field, r.seed ^ 0x5A5A, distM, swayK);
                path = track.new TrackPath(distM + 2 * r.field.lapM);
            }
            latlon = isTread ? null : new ArrayList<double[]>(n);
            dists = new ArrayList<Double>(n);
            speeds = new ArrayList<Double>(n);
            maxStep = 0;
            double s = 0;
            double[] prev = null;
            for (int i = 0; i < n; i++) {
                if (i > 0) s += (distM * w[i]) / wSum;

                double v;
                if (isTread) {
                    // 室内跑步没有坐标。速度恒定（跑步机就是匀速），不靠位移反推。
                    // latlon 保持 null（不是空数组），下游按「没有轨迹」处理。
                    v = 1000 / paceSec;
                    speeds.add(v);
                    // dists 必须跟 speeds 同步增长。漏掉这行会让下游
                    // dists.get(i) 越界崩溃。
                    dists.add(s);
                    if (hrs != null) hrs[i] = hrp.hrAt(v, i * dt, durSec);
                    continue;
                }

                double[] p = path.at(s);
                if (prev == null) {
                    v = 1000 / paceSec;
                } else {
                    double step = Math.hypot((p[0] - prev[0]) * Field.M_PER_DEG_LAT,
                                             (p[1] - prev[1]) * r.field.mPerDegLon());
                    if (step > maxStep) maxStep = step;
                    v = step / dt;
                }
                speeds.add(v);
                // 心率：按逐点瞬时速度用 Karvonen 推断（离线，无传感器）
                if (hrs != null) hrs[i] = hrp.hrAt(v, i * dt, durSec);
                latlon.add(p);
                dists.add(s);
                prev = p;
            }
            if (maxStep / dt <= vCap * 1.002) break;
            // 骨架模式没有可退火的摆动参数，退火只会白跑 10 轮
            if (skel != null) break;
            swayK *= 0.9;
            if (isRoute) break;      // 任意路线模式下不作摆动退火
        }
        if (maxStep / dt > vCap * 1.01) {
            throw new IllegalArgumentException("这个配速没有余量，请放慢一点");
        }

        // 步频
        double[] speedArr = new double[n];
        for (int i = 0; i < n; i++) speedArr[i] = speeds.get(i);
        int[] cads = withCad ? Plan.cadenceSeries(n, dt, r.seed, speedArr) : null;

        // 步频统计与总步数（总步数 = Σ 每点步频 × 采样间隔 ÷ 60，比 平均步频×时长 更贴）
        double avgSpm = 0, minSpm = 0, maxSpm = 0, totalSteps = 0;
        double[] stepsCum = null, gctArr = null, stancePctArr = null,
                voArr = null, stepLenArr = null;
        if (cads != null) {
            double sum = 0, mn = 1e9, mx = -1e9;
            for (int k = 0; k < n; k++) {
                int c = cads[k];
                sum += c;
                if (c < mn) mn = c;
                if (c > mx) mx = c;
                totalSteps += c * dt / 60.0;
            }
            avgSpm = sum / n;
            minSpm = mn;
            maxSpm = mx;
            if (!(totalSteps > 0)) totalSteps = avgSpm * durSec / 60.0;

            // 逐点累计步数 + 步态（触地时间 / 触地占比 / 垂直振幅 / 步幅）
            stepsCum = new double[n];
            gctArr = new double[n];
            stancePctArr = new double[n];
            voArr = new double[n];
            stepLenArr = new double[n];
            double acc = 0;
            for (int k = 0; k < n; k++) {
                acc += cads[k] * dt / 60.0;
                stepsCum[k] = Math.round(acc);
                double[] dyn = Plan.runDynamics(speeds.get(k), cads[k]);
                gctArr[k] = dyn[0];
                stancePctArr[k] = 100.0 * dyn[0] / (60000.0 / cads[k]);
                voArr[k] = dyn[1];
                stepLenArr[k] = dyn[2] * 1000;   // m -> mm
            }
        }

        int calories = (int) Math.round(r.weightKg * (distM / 1000) * sp.calPerKm);

        Result out = new Result();
        out.distM = distM;
        out.durSec = durSec;
        out.paceSec = paceSec;
        out.dt = dt;
        out.points = n;
        out.startMs = r.startMs;
        out.calories = calories;
        out.sportKey = sp.key;
        out.sportLabel = sp.label;
        out.laps = (int) Math.round(distM / r.field.lapM);
        out.fieldDesc = r.field.describe();
        if (cads != null) {
            out.avgSpm = avgSpm;
            out.minSpm = minSpm;
            out.maxSpm = maxSpm;
            out.totalSteps = totalSteps;
            double[] dynAvg = Plan.runDynamics(1000 / paceSec, avgSpm);
            out.gctMs = dynAvg[0];
            out.verticalOscCm = dynAvg[1];
            out.strideM = distM / totalSteps;
        }
        if (isRoute) {
            out.routeLegs = traveller.legs();
            out.routeLength = traveller.length();
        }

        // 逐点输入，供三种格式共用
        double[] msArr = new double[n];
        for (int i = 0; i < n; i++) msArr[i] = r.startMs + Math.round(i * dt * 1000);

        // ---- 分段 ----
        out.splits = new ArrayList<double[]>();
        double nextKm = 1000, prevT = 0;
        int prevIdx = 0;
        // 在 km 边界做线性插值。不插值的话 dt=5 会让每公里时间必然是 5 的倍数
        // （5:00 配速只会出现 295/300/305），用户看分段表一眼就能看出是机器生成的。
        for (int i = 1; i < n; i++) {
            double d0 = dists.get(i - 1), d1 = dists.get(i);
            while (nextKm <= distM && d1 >= nextKm) {
                double f = (d1 - d0) > 1e-9 ? (nextKm - d0) / (d1 - d0) : 0;
                if (f < 0) f = 0;
                if (f > 1) f = 1;
                double tB = (i - 1 + f) * dt;
                out.splits.add(new double[]{tB - prevT, prevT + r.startMs,
                        tB + r.startMs, 1000, prevIdx, i});
                prevT = tB;
                prevIdx = i;
                nextKm += 1000;
            }
        }
        double restM = distM - out.splits.size() * 1000;

        // ---- TCX ----
        if (r.wantTcx) {
            out.tcx = buildTcx(r.startMs, durSec, distM, latlon, dists, speeds,
                    msArr, hrs, cads, stepsCum, sp.tcxSport, calories, maxStep / dt,
                    withCad, avgSpm, maxSpm, totalSteps);
        }

        // ---- FIT ----
        if (r.wantFit) {
            List<double[]> pts = new ArrayList<double[]>(n);
            for (int i = 0; i < n; i++) {
                double[] ll = (latlon != null) ? latlon.get(i) : null;
                pts.add(new double[]{
                        ll != null ? ll[0] : Double.NaN,
                        ll != null ? ll[1] : Double.NaN,
                        dists.get(i), speeds.get(i),
                        hrs != null ? hrs[i] : Double.NaN,
                        cads != null ? cads[i] : Double.NaN,
                        stepsCum != null ? stepsCum[i] : Double.NaN,      // total_cycles
                        voArr != null ? voArr[i] * 10 : Double.NaN,       // vertical_oscillation (mm)
                        stancePctArr != null ? stancePctArr[i] : Double.NaN,
                        gctArr != null ? gctArr[i] : Double.NaN,
                        stepLenArr != null ? stepLenArr[i] : Double.NaN
                });
            }
            List<double[]> fitSplits = new ArrayList<double[]>();
            for (double[] s : out.splits) {
                int a = (int) s[4], b = (int) s[5];
                // 本圈步数 = 累计步数在该圈首尾点的差值
                double lapSteps = stepsCum == null ? Double.NaN
                        : Math.max(0, stepsCum[b] - stepsCum[a]);
                double[] llA = (latlon != null) ? latlon.get(a) : null;
                double[] llB = (latlon != null) ? latlon.get(b) : null;
                fitSplits.add(new double[]{
                        s[1], s[2], s[3],
                        llA != null ? llA[0] : Double.NaN, llA != null ? llA[1] : Double.NaN,
                        llB != null ? llB[0] : Double.NaN, llB != null ? llB[1] : Double.NaN,
                        lapSteps
                });
            }
            // 最后一个不满一公里的尾段并入最后一圈，保证分圈步数合计 = 总步数
            if (!fitSplits.isEmpty() && stepsCum != null) {
                double sumLap = 0;
                for (double[] s : fitSplits) if (!Double.isNaN(s[7])) sumLap += s[7];
                double[] lastLap = fitSplits.get(fitSplits.size() - 1);
                lastLap[7] = Math.max(0, Math.round(lastLap[7] + (out.totalSteps - sumLap)));
            }
            out.fit = Fit.build(r.startMs, durSec, distM, pts, r.sport, calories,
                    maxStep / dt, fitSplits, out.totalSteps, dt);
        }


        // 预览折线。室内跑步没有轨迹，poly 保持空（界面不画环线预览）
        out.poly = new ArrayList<double[]>();
        if (latlon != null) {
            int strideN = Math.max(1, (int) Math.ceil(n / 260.0));
            for (int i = 0; i < n; i += strideN) out.poly.add(latlon.get(i));
        }

        // ---- 直写 Keep 需要的逐点序列 ----
        // 必须放在「室内跑步提前 return」之前，否则室内分支拿不到。
        out.latlon = (latlon == null) ? null : latlon.toArray(new double[0][]);
        out.hrs = hrs;
        out.cads = cads;
        if (stepsCum != null) {
            out.stepsCum = new int[stepsCum.length];
            for (int i = 0; i < stepsCum.length; i++) out.stepsCum[i] = (int) stepsCum[i];
        }

        // 横向摆动
        double sumSway = 0; int cntSway = 0; double maxAbs = 0;
        if (isTread) {
            // 室内跑步没有横向摆动这回事
            out.swayPer100m = 0;
            out.swayMaxAbs = 0;
            return out;
        }
        if (isRoute) {
            out.swayPer100m = traveller.per100m();
            out.swayMaxAbs = traveller.maxAbs();
        } else if (skel != null) {
            out.swayPer100m = skel.per100m(distM);
            out.swayMaxAbs = skel.maxAbs(distM);
        } else {
            for (double s = 0; s + 100 <= distM; s += 100) {
                sumSway += Math.abs(track.swayAt(s + 100) - track.swayAt(s));
                cntSway++;
            }
            out.swayPer100m = cntSway > 0 ? sumSway / cntSway : 0;
            for (double s = 0; s <= distM; s += Math.max(1, distM / 400)) {
                maxAbs = Math.max(maxAbs, Math.abs(track.swayAt(s)));
            }
            out.swayMaxAbs = maxAbs;
        }
        return out;
    }

    private static String buildTcx(long startMs, double durSec, double distM,
                                   List<double[]> latlon, List<Double> dists,
                                   List<Double> speeds, double[] msArr,
                                   int[] hrs, int[] cads, double[] stepsCum,
                                   String tcxSport, int calories, double maxSpeed,
                                   boolean withCad, double avgSpm, double maxSpm,
                                   double totalSteps) {
        // latlon 为 null 表示「没有轨迹」（室内跑步）
        int pointCount = (latlon != null) ? latlon.size() : dists.size();
        StringBuilder sb = new StringBuilder(pointCount * 340);
        String id = iso(startMs);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
          .append("<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\">")
          .append("<Activities><Activity Sport=\"").append(tcxSport).append("\">")
          .append("<Id>").append(id).append("</Id>")
          .append("<Lap StartTime=\"").append(id).append("\">")
          .append("<TotalTimeSeconds>").append(fixed(durSec, 1)).append("</TotalTimeSeconds>")
          .append("<DistanceMeters>").append(fixed(distM, 1)).append("</DistanceMeters>")
          .append("<MaximumSpeed>").append(fixed(maxSpeed, 2)).append("</MaximumSpeed>")
          .append("<Calories>").append(calories).append("</Calories>")
          .append("<Intensity>Active</Intensity>");
        // Lap 级步频：Garmin 规范里这里放的是「半步频」（步/分 ÷ 2）
        if (withCad && cads != null && avgSpm > 0) {
            sb.append("<Cadence>").append(Math.round(avgSpm / 2.0)).append("</Cadence>");
        }
        sb.append("<TriggerMethod>Manual</TriggerMethod>")
          .append("<Track>");

        for (int i = 0; i < pointCount; i++) {
            double[] p = (latlon != null) ? latlon.get(i) : null;
            sb.append("<Trackpoint><Time>").append(iso(Math.round(msArr[i]))).append("</Time>");
            // 没有坐标就不写 Position 段。室内跑步按 TCX 规范可以只有时间+距离。
            if (p != null) {
                sb.append("<Position><LatitudeDegrees>").append(fixed(p[0], 8))
                  .append("</LatitudeDegrees><LongitudeDegrees>").append(fixed(p[1], 8))
                  .append("</LongitudeDegrees></Position>");
            }
            sb.append("<DistanceMeters>").append(fixed(dists.get(i), 1)).append("</DistanceMeters>");
            if (hrs != null) {
                sb.append("<HeartRateBpm><Value>").append(hrs[i]).append("</Value></HeartRateBpm>");
            }
            if (withCad && cads != null) {
                sb.append("<Cadence>").append(cads[i]).append("</Cadence>");
                sb.append("<Extensions><TPX xmlns=\"http://www.garmin.com/xmlschemas/ActivityExtension/v2\">")
                  .append("<RunCadence>").append(Math.round(cads[i] / 2.0)).append("</RunCadence>")
                  .append("</TPX></Extensions>");
            }
            sb.append("</Trackpoint>");
        }
        sb.append("</Track>");
        // Lap 级扩展：平均/最高步频 + 总步数（Keep 拿不到总步数就报「缺少步数信息」）
        if (withCad && cads != null) {
            sb.append("<Extensions><LX xmlns=\"http://www.garmin.com/xmlschemas/ActivityExtension/v2\">");
            if (avgSpm > 0) {
                sb.append("<AvgRunCadence>").append(Math.round(avgSpm / 2.0)).append("</AvgRunCadence>");
            }
            if (maxSpm > 0) {
                sb.append("<MaxRunCadence>").append(Math.round(maxSpm / 2.0)).append("</MaxRunCadence>");
            }
            if (totalSteps > 0) {
                sb.append("<Steps>").append(Math.min(65535, Math.round(totalSteps))).append("</Steps>");
            }
            sb.append("</LX></Extensions>");
        }
        sb.append("</Lap></Activity></Activities></TrainingCenterDatabase>");
        return sb.toString();
    }

    static String fixed(double x, int n) {
        return String.format(Locale.US, "%." + n + "f", x);
    }
}
