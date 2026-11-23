package com.qianyuzhou.keepgen;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * FIT 编码器。Keep 导入首选 FIT（带心率、步频、卡路里、分圈）。
 *
 * 结构：header(14B, 含 CRC) + data(definition/data message) + CRC(2B)
 * 只写必需消息：file_id(0) / event(21) / record(20) / lap(19) / session(18) / activity(34)
 *
 * 2026-11-22：补齐「总步数」链路。
 * Keep 的记录详情页要拿 total_cycles（总步数）才能反推平均步频 → 步幅 / 触地时间 / 垂直振幅，
 * 只给逐点 cadence 不够。Garmin Profile 里总步数的位置：
 *   record.total_cycles  字段 19（uint32，累计步数）
 *   lap.total_cycles     字段 10（uint32）
 *   session.total_cycles 字段 10（uint32）
 * 同时补上逐点步态：vertical_oscillation(39) / stance_time_percent(40) / stance_time(41) /
 * step_length(85)，这样即使解析器只看逐点数据也能算出触地与振幅。
 */
final class Fit {

    private static final int[] CRC_TABLE = {
            0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
            0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    };

    private static int crcUpdate(int crc, int b) {
        int tmp = CRC_TABLE[crc & 0xF];
        crc = (crc >> 4) & 0x0FFF;
        crc = crc ^ tmp ^ CRC_TABLE[b & 0xF];
        tmp = CRC_TABLE[crc & 0xF];
        crc = (crc >> 4) & 0x0FFF;
        crc = crc ^ tmp ^ CRC_TABLE[(b >> 4) & 0xF];
        return crc;
    }

    static int crc16(byte[] bytes, int len) {
        int c = 0;
        for (int i = 0; i < len; i++) c = crcUpdate(c, bytes[i] & 0xFF);
        return c & 0xFFFF;
    }

    private static final int T_ENUM = 0x00, T_UINT8 = 0x02, T_UINT16 = 0x84,
            T_SINT32 = 0x85, T_UINT32 = 0x86, T_UINT32Z = 0x8C;

    private static int sizeOf(int t) {
        if (t == T_UINT16) return 2;
        if (t == T_SINT32 || t == T_UINT32 || t == T_UINT32Z) return 4;
        return 1;
    }

    static final class F {
        final int num, type, scale;
        F(int num, int type, int scale) { this.num = num; this.type = type; this.scale = scale; }
        F(int num, int type) { this(num, type, 0); }
    }

    static final class Msg {
        final int gnum;
        final F[] fields;
        Msg(int gnum, F[] fields) { this.gnum = gnum; this.fields = fields; }
    }

    static final Msg FILE_ID = new Msg(0, new F[]{
            new F(0, T_ENUM), new F(1, T_UINT16), new F(2, T_UINT16),
            new F(3, T_UINT32Z), new F(4, T_UINT32)});
    static final Msg EVENT = new Msg(21, new F[]{
            new F(253, T_UINT32), new F(0, T_ENUM), new F(1, T_ENUM), new F(4, T_UINT8)});
    static final Msg RECORD = new Msg(20, new F[]{
            new F(253, T_UINT32), new F(0, T_SINT32), new F(1, T_SINT32),
            new F(2, T_UINT16), new F(3, T_UINT8), new F(4, T_UINT8),
            new F(5, T_UINT32, 100), new F(6, T_UINT16, 1000),
            new F(19, T_UINT32),                 // total_cycles：累计步数
            new F(39, T_UINT16, 10),             // vertical_oscillation (mm)
            new F(40, T_UINT16, 100),            // stance_time_percent (%)
            new F(41, T_UINT16, 10),             // stance_time (ms)
            new F(85, T_UINT16, 10)});           // step_length (mm)
    static final Msg LAP = new Msg(19, new F[]{
            new F(253, T_UINT32), new F(2, T_UINT32),
            new F(3, T_SINT32), new F(4, T_SINT32), new F(5, T_SINT32), new F(6, T_SINT32),
            new F(7, T_UINT32, 1000), new F(8, T_UINT32, 1000), new F(9, T_UINT32, 100),
            new F(10, T_UINT32),                 // total_cycles：本圈步数
            new F(11, T_UINT16),                 // total_calories
            new F(13, T_UINT16, 1000), new F(14, T_UINT16, 1000),
            new F(15, T_UINT8), new F(16, T_UINT8), new F(17, T_UINT8), new F(18, T_UINT8)});
    static final Msg SESSION = new Msg(18, new F[]{
            new F(253, T_UINT32), new F(2, T_UINT32), new F(5, T_ENUM), new F(6, T_ENUM),
            new F(7, T_UINT32, 1000), new F(8, T_UINT32, 1000), new F(9, T_UINT32, 100),
            new F(10, T_UINT32),                 // total_cycles：总步数
            new F(11, T_UINT16),
            new F(14, T_UINT16, 1000), new F(15, T_UINT16, 1000),
            new F(16, T_UINT8), new F(17, T_UINT8), new F(18, T_UINT8), new F(19, T_UINT8),
            new F(24, T_UINT8, 10),              // total_training_effect
            new F(26, T_UINT16), new F(28, T_ENUM)});   // num_laps, trigger(enum=1B)
    static final Msg ACTIVITY = new Msg(34, new F[]{
            new F(253, T_UINT32), new F(0, T_UINT32, 1000), new F(1, T_UINT16),
            new F(2, T_ENUM), new F(3, T_ENUM), new F(4, T_ENUM),
            new F(5, T_UINT32), new F(6, T_UINT8)});

    static final double NA = Double.NaN;

    private Fit() {}

    private static void putU8(ByteArrayOutputStream bo, int v) { bo.write(v & 0xFF); }
    private static void putU16(ByteArrayOutputStream bo, int v) {
        bo.write(v & 0xFF); bo.write((v >> 8) & 0xFF);
    }
    private static void putU32(ByteArrayOutputStream bo, long v) {
        bo.write((int) (v & 0xFF)); bo.write((int) ((v >> 8) & 0xFF));
        bo.write((int) ((v >> 16) & 0xFF)); bo.write((int) ((v >> 24) & 0xFF));
    }

    private static void def(ByteArrayOutputStream bo, int local, Msg msg) {
        bo.write(0x40 | (local & 0x0F));
        bo.write(0);
        bo.write(0);
        putU16(bo, msg.gnum);
        bo.write(msg.fields.length);
        for (F f : msg.fields) {
            bo.write(f.num);
            bo.write(sizeOf(f.type));
            bo.write(f.type);
        }
    }

    private static void data(ByteArrayOutputStream bo, int local, Msg msg, double[] vals) {
        bo.write(local & 0x0F);
        for (int i = 0; i < msg.fields.length; i++) {
            F f = msg.fields[i];
            double raw = i < vals.length ? vals[i] : NA;
            int sz = sizeOf(f.type);
            if (Double.isNaN(raw)) {
                if (f.type == T_SINT32) putU32(bo, 0x7FFFFFFFL);
                else for (int k = 0; k < sz; k++) bo.write(0xFF);
                continue;
            }
            long v = f.scale > 0 ? Math.round(raw * f.scale) : Math.round(raw);
            if (f.type == T_SINT32 || f.type == T_UINT32 || f.type == T_UINT32Z) {
                putU32(bo, v & 0xFFFFFFFFL);
            } else if (f.type == T_UINT16) {
                putU16(bo, (int) (v & 0xFFFF));
            } else {
                putU8(bo, (int) (v & 0xFF));
            }
        }
    }

    private static final double DEG = 2147483648.0 / 180.0;

    /**
     * @param pts 每个点 {lat, lon, distM, speed, hr, cad, stepsCum, gctMs, voMm, stepLenMm}
     *            hr/cad 用 NaN 表示无；stepsCum 为累计步数，gctMs/voMm/stepLenMm 可为 NaN
     * @param splits 每个分段 {startMs, endMs, distM, startLat, startLon, endLat, endLon, steps}
     * @param totalSteps 全程总步数，NaN 表示不写
     */
    static byte[] build(long startMs, double durationSec, double distanceM,
                        List<double[]> pts, String sport, int calories,
                        double maxSpeed, List<double[]> splits, double totalSteps,
                        double dtSec) {
        int fitSport = 1, fitSub = 0;
        if ("tread".equals(sport)) { fitSport = 1; fitSub = 1; }
        else if ("bike".equals(sport)) { fitSport = 2; fitSub = 0; }
        else if ("walk".equals(sport)) { fitSport = 11; fitSub = 0; }

        long startSec = startMs / 1000L;
        long endSec = (startMs + (long) (durationSec * 1000)) / 1000L;

        double hrSum = 0, cadSum = 0;
        int hrN = 0, cadN = 0, hrMax = 0, cadMax = 0;
        for (double[] p : pts) {
            double hr = p[4], cad = p[5];
            if (!Double.isNaN(hr) && hr > 0) { hrSum += hr; hrN++; if (hr > hrMax) hrMax = (int) hr; }
            if (!Double.isNaN(cad) && cad > 0) { cadSum += cad; cadN++; if (cad > cadMax) cadMax = (int) cad; }
        }
        double avgHr = hrN > 0 ? Math.round(hrSum / hrN) : NA;
        // 平均步频与总步数必须自洽：avg_cadence = total_cycles / 时长(分)
        double avgCad = (!Double.isNaN(totalSteps) && durationSec > 0)
                ? Math.round(totalSteps / (durationSec / 60.0))
                : (cadN > 0 ? Math.round(cadSum / cadN) : NA);

        ByteArrayOutputStream bo = new ByteArrayOutputStream(pts.size() * 40 + 4096);

        // serial_number 必须是每个文件唯一的：Keep 导入时按它判重，
        // 写死一个常量会让第二次导入直接报「重复数据」。
        long serial = ((startMs / 1000L) * 2654435761L + (long) (distanceM * 7)) & 0xFFFFFFFFL;
        if (serial == 0) serial = 1;

        def(bo, 0, FILE_ID);
        data(bo, 0, FILE_ID, new double[]{4, 1, 2600, serial, startSec});

        def(bo, 1, EVENT);
        data(bo, 1, EVENT, new double[]{startSec, 0, 0, 0});

        def(bo, 2, RECORD);
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i);
            // 采样间隔由 Generator 决定（v22 起通常是 5 秒），这里必须跟着 dt 走。
            // 原来写 startSec + i，等于假设 1 秒采样：改成 5 秒后 FIT 时间轴会
            // 被压缩 5 倍，Keep 里瞬时速度变成约 55 km/h。
            double tSec = startSec + i * dtSec;
            data(bo, 2, RECORD, new double[]{
                    tSec,
                    p[0] * DEG,          // position_lat
                    p[1] * DEG,          // position_long
                    0,                   // altitude (m)
                    p[4],                // heart_rate
                    p[5],                // cadence
                    p[2],                // distance
                    p[3],                // speed
                    p.length > 6 ? p[6] : NA,   // total_cycles（累计步数）
                    p.length > 7 ? p[7] : NA,   // vertical_oscillation (mm)
                    p.length > 8 ? p[8] : NA,   // stance_time_percent (%)
                    p.length > 9 ? p[9] : NA,   // stance_time (ms)
                    p.length > 10 ? p[10] : NA  // step_length (mm)
            });
        }

        data(bo, 1, EVENT, new double[]{endSec, 0, 4, 0});

        def(bo, 3, LAP);
        if (splits != null && !splits.isEmpty()) {
            for (double[] s : splits) {
                double dur = (s[1] - s[0]) / 1000.0;
                data(bo, 3, LAP, new double[]{
                        s[1] / 1000.0, s[0] / 1000.0,
                        s[3] * DEG, s[4] * DEG, s[5] * DEG, s[6] * DEG,
                        dur, dur, s[2],
                        s.length > 7 ? s[7] : NA,      // total_cycles（本圈步数）
                        Math.round(calories / (double) splits.size()),   // total_calories
                        s[2] / dur, maxSpeed,
                        avgHr, hrMax, avgCad, cadMax
                });
            }
        } else {
            data(bo, 3, LAP, new double[]{
                    endSec, startSec, NA, NA, NA, NA,
                    durationSec, durationSec, distanceM,
                    totalSteps, calories,
                    distanceM / durationSec, maxSpeed,
                    avgHr, hrMax, avgCad, cadMax
            });
        }

        def(bo, 4, SESSION);
        data(bo, 4, SESSION, new double[]{
                endSec, startSec, fitSport, fitSub,
                durationSec, durationSec, distanceM,
                totalSteps,                  // total_cycles
                calories,
                distanceM / durationSec, maxSpeed,
                avgHr, hrMax, avgCad, cadMax,
                25,                          // total_training_effect（2.5）
                splits != null ? splits.size() : 1,
                0                            // trigger = activity_end
        });

        def(bo, 5, ACTIVITY);
        data(bo, 5, ACTIVITY, new double[]{
                endSec, durationSec, 1, 0, 26, 1, startSec, 0
        });

        byte[] body = bo.toByteArray();
        byte[] header = new byte[14];
        header[0] = 14;
        header[1] = 0x20;
        header[2] = (byte) (2184 & 0xFF);
        header[3] = (byte) ((2184 >> 8) & 0xFF);
        header[4] = (byte) (body.length & 0xFF);
        header[5] = (byte) ((body.length >> 8) & 0xFF);
        header[6] = (byte) ((body.length >> 16) & 0xFF);
        header[7] = (byte) ((body.length >> 24) & 0xFF);
        header[8] = '.';
        header[9] = 'F';
        header[10] = 'I';
        header[11] = 'T';
        int hc = crc16(header, 12);
        header[12] = (byte) (hc & 0xFF);
        header[13] = (byte) ((hc >> 8) & 0xFF);

        byte[] all = new byte[14 + body.length + 2];
        System.arraycopy(header, 0, all, 0, 14);
        System.arraycopy(body, 0, all, 14, body.length);
        int fc = crc16(all, 14 + body.length);
        all[14 + body.length] = (byte) (fc & 0xFF);
        all[14 + body.length + 1] = (byte) ((fc >> 8) & 0xFF);
        return all;
    }
}
