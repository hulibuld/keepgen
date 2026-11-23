package com.qianyuzhou.keepgen;

import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 把 Generator.Result 组装成 Keep v5 建档请求体（POST /pd/v5/sportlog）。
 *
 * ── 为什么不能拍脑袋写字段 ──────────────────────────────────
 *
 * 服务端是按顺序逐字段校验的：缺一个报一个（404405 → 404407 → 404403 → 404408 → 404409），
 * 校验顺序固定，报错信息直接告诉你卡在哪。字段全部对了，才轮到「来源」那一关。
 *
 * ── 三条必须踩准的口径 ──────────────────────────────────────
 *
 * ① 轨迹块 = base64(gzip(JSON数组))，编码后必然 H4sI 开头。
 * ② 时间戳单位是「Unix 毫秒 ÷ 100」（10ms 分辨率）。
 *    geoPoints / heartRates 用绝对时间；stepPoints 用相对起点的偏移（从 0 开始）。
 * ③ 坐标必须是 GCJ-02（火星坐标）。传 WGS84 记录本身完全正常、形状距离都对，
 *    但整条轨迹会平移 300~600 米 —— 这是最容易误判成「GPS 漂移」的坑。
 *    所以转换只在这里做一次，别在别处再转。
 *
 * ── 步数那一块为什么必须给 ──────────────────────────────────
 *
 * Keep 判定「缺少步数信息」看的是 stepPoints 是否为空的数组，
 * **不是** totalSteps。官方「数据文件导入」生成的记录 totalSteps 有值、
 * stepPoints 是空数组，所以顶部一直挂红字。我们自己生成，直接给完整序列。
 */
final class KeepBody {

    private KeepBody() {}

    static String build(Generator.Result g) throws Exception {
        final long t0 = g.startMs;
        final int n = g.points;
        final int durSec = (int) Math.round(g.durSec);
        final int distM = (int) Math.round(g.distM);

        StringBuilder b = new StringBuilder(32 * 1024);
        b.append('{');
        str(b, "type", v5Type(g.sportKey));
        str(b, "dataType", v5DataType(g.sportKey));
        raw(b, "startTime", Long.toString(t0));
        raw(b, "endTime", Long.toString(t0 + (long) durSec * 1000));
        raw(b, "duration", Integer.toString(durSec));
        raw(b, "distance", Integer.toString(distM));
        raw(b, "calorie", Integer.toString(g.calories));
        str(b, "timezone", "Asia/Shanghai");

        if (g.latlon != null && g.latlon.length > 0) {
            StringBuilder pts = new StringBuilder(g.latlon.length * 110);
            pts.append('[');
            for (int i = 0; i < n; i++) {
                double[] w = Geo.wgs2gcj(g.latlon[i][0], g.latlon[i][1]);  // WGS84 -> GCJ-02，只转一次
                if (i > 0) pts.append(',');
                pts.append("{\"latitude\":").append(r6(w[0]))
                   .append(",\"longitude\":").append(r6(w[1]))
                   .append(",\"altitude\":0.0")
                   .append(",\"timestamp\":").append((t0 + Math.round(i * g.dt * 1000)) / 100)
                   .append('}');
            }
            pts.append(']');
            raw(b, "pointCount", Integer.toString(n));   // 必须等于数组长度，否则轨迹被静默丢弃
            str(b, "geoPoints", gz(pts));
        }

        if (g.hrs != null && g.hrs.length > 0) {
            StringBuilder hb = new StringBuilder(g.hrs.length * 44);
            hb.append('[');
            for (int i = 0; i < g.hrs.length; i++) {
                if (i > 0) hb.append(',');
                hb.append("{\"timestamp\":").append((t0 + Math.round(i * g.dt * 1000)) / 100)
                  .append(",\"heartRate\":").append(g.hrs[i]).append('}');
            }
            hb.append(']');
            b.append(",\"heartRate\":{\"averageHeartRate\":").append(mean(g.hrs, 0, g.hrs.length))
             .append(",\"maxHeartRate\":").append(max(g.hrs))
             .append(",\"heartRates\":\"").append(gz(hb)).append("\"}");
        }

        if (g.cads != null && g.stepsCum != null && g.stepsCum.length > 0) {
            raw(b, "totalSteps", Integer.toString(g.stepsCum[g.stepsCum.length - 1]));
            raw(b, "averageStepFrequency", Integer.toString((int) Math.round(g.avgSpm)));
            StringBuilder sp = new StringBuilder(g.cads.length * 34);
            sp.append('[');
            for (int i = 0; i < g.cads.length; i++) {
                if (i > 0) sp.append(',');
                sp.append("{\"timestamp\":").append(Math.round(i * g.dt * 100))  // 相对起点
                  .append(",\"steps\":").append(g.stepsCum[i]).append('}');     // 累计步数
            }
            sp.append(']');
            str(b, "stepPoints", gz(sp));
            b.append(",\"crossKmPoints\":").append(crossKm(g));
        }

        // 来源：字段名必须是 vendor.source，取值只认 Keep。
        // 写成 thirdParty / DataImport / KEEP_APP / 数值枚举，一律 404409「未知训练记录来源」。
        b.append(",\"vendor\":{\"source\":\"Keep\"}");
        b.append('}');
        return b.toString();
    }

    /** 按公里分段。splits: {sec, startMs, endMs, 1000, startIdx, endIdx} */
    private static String crossKm(Generator.Result g) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        int w = 0;
        for (int k = 0; k < g.splits.size(); k++) {
            double[] s = g.splits.get(k);
            int a = (int) s[4], e = Math.min((int) s[5], g.points - 1);
            if (w++ > 0) sb.append(',');
            sb.append("{\"kmNO\":").append(k + 1)
              .append(",\"kmPace\":").append(Math.round(s[0]))
              .append(",\"totalDistance\":").append(Math.round((k + 1) * 1000))
              .append(",\"totalDuration\":").append(Math.round((s[2] - g.startMs) / 1000.0));
            if (g.hrs != null) sb.append(",\"averageHeartRate\":").append(mean(g.hrs, a, e));
            if (g.cads != null) sb.append(",\"stepFrequency\":").append(mean(g.cads, a, e));
            if (g.stepsCum != null) sb.append(",\"totalSteps\":").append(g.stepsCum[e]);
            sb.append('}');
        }
        double restM = g.distM - w * 1000.0;
        if (restM > 40) {                       // 不足 40 米的尾巴跳过，Keep 自己会补
            double prevSec = g.splits.isEmpty() ? 0
                    : (g.splits.get(g.splits.size() - 1)[2] - g.startMs) / 1000.0;
            double restSec = g.durSec - prevSec;
            if (w > 0) sb.append(',');
            sb.append("{\"kmNO\":").append(w + 1)
              .append(",\"kmPace\":").append(Math.round(restSec / (restM / 1000.0)))
              .append(",\"totalDistance\":").append(Math.round(g.distM))
              .append(",\"totalDuration\":").append(Math.round(g.durSec));
            if (g.cads != null) {
                int a = g.splits.isEmpty() ? 0 : (int) g.splits.get(g.splits.size() - 1)[5];
                sb.append(",\"stepFrequency\":").append(mean(g.cads, a, g.points - 1));
            }
            if (g.stepsCum != null) sb.append(",\"totalSteps\":").append(g.stepsCum[g.points - 1]);
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    // ---- 小工具 ----

    private static void str(StringBuilder b, String k, String v) {
        if (b.length() > 1) b.append(',');
        b.append('"').append(k).append("\":\"").append(v).append('"');
    }

    private static void raw(StringBuilder b, String k, String v) {
        if (b.length() > 1) b.append(',');
        b.append('"').append(k).append("\":").append(v);
    }

    private static double r6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    /** JSON -> gzip -> Base64（不换行）。必须用 android.util.Base64：java.util.Base64 是 API 26 起。 */
    static String gz(CharSequence json) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(json.length() * 3);
        GZIPOutputStream z = new GZIPOutputStream(bo);
        z.write(json.toString().getBytes("UTF-8"));
        z.close();
        return android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
    }

    private static int mean(int[] a, int from, int to) {
        if (a == null || to < from) return 0;
        long s = 0;
        int c = 0;
        for (int i = Math.max(0, from); i <= Math.min(to, a.length - 1); i++) { s += a[i]; c++; }
        return c == 0 ? 0 : (int) Math.round((double) s / c);
    }

    private static int max(int[] a) {
        int m = 0;
        for (int v : a) if (v > m) m = v;
        return m;
    }

    static String v5Type(String key) {
        if ("bike".equals(key)) return "cycle";
        if ("walk".equals(key)) return "walk";
        return "run";                       // run / tread 都是 run
    }

    static String v5DataType(String key) {
        if ("bike".equals(key)) return "outdoorCycling";
        if ("walk".equals(key)) return "outdoorWalking";
        if ("tread".equals(key)) return "indoorRunning";
        return "outdoorRunning";
    }
}