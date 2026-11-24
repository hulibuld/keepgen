package com.keepgen.hulibuld;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 不依赖任何服务端的「邀请码」方案。
 *
 * 思路：轨迹点抽稀到 60 个以内，量化成 5 位小数（约 1 米精度），
 * 用 delta 编码压成字节，再 base32 打包进码里。
 *
 * 码结构（全部大写，去掉易混字符）：
 *   [1 位类型][6 位邀请码][N 位数据]
 *   类型 0 = 只有邀请码，没有数据（要去服务端查）
 *   类型 1 = 码里自带完整轨迹，离线可用
 *
 * 一条 60 点的环线大约 110 个字符。微信/短信都能发。
 * 超过 200 个字符就自动退回「只发码」，交给服务端。
 *
 * 这样「六个字就分享一条路线」这种体验在没有服务端时也能成立：
 * 短码靠服务器查，长码自己带走。
 */
final class CodeShare {

    /**
     * 32 个字符的编码表——必须是 32 个，不是 31 个。
     * 5 位掩码 0x1F 的取值范围是 0~31，字母表少一个就会在第 32 个值上越界崩溃。
     * 末尾的 '0' 就是补第 32 个：字母表里没有 'O'，看到 0 不会被误认成字母。
     * 加在末尾而不是重排，是为了让以前生成的码还能正常解码。
     */
    private static final String B32 = "23456789ABCDEFGHJKMNPQRSTUVWXYZ0";
    static final int MAX_CHARS = 220;      // 超过就不塞数据了
    private static final int MAX_POINTS = 64;

    private CodeShare() {}

    // ---- 打包 ------------------------------------------------------------

    /** 返回码；无法内嵌时返回 null（调用方改走服务端或本地库） */
    static String pack(List<double[]> pts, boolean loop) {
        if (pts == null || pts.size() < 2) return null;

        List<double[]> thin = thin(pts, MAX_POINTS);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try {
            // 头部：点数(1B) + loop(1bit)
            raw.write(thin.size() & 0xFF);
            raw.write(loop ? 1 : 0);

            // 缩放成 1e5 整数，写 delta（varint）
            long prevLat = 0, prevLon = 0;
            for (int i = 0; i < thin.size(); i++) {
                long lat = Math.round(thin.get(i)[0] * 1e5);
                long lon = Math.round(thin.get(i)[1] * 1e5);
                writeVarint(raw, zigzag(lat - prevLat));
                writeVarint(raw, zigzag(lon - prevLon));
                prevLat = lat; prevLon = lon;
            }
        } catch (Exception e) {
            return null;
        }

        byte[] packed;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DeflaterOutputStream dos = new DeflaterOutputStream(bo);
            dos.write(raw.toByteArray());
            dos.close();
            packed = bo.toByteArray();
        } catch (Exception e) {
            packed = raw.toByteArray();
        }

        StringBuilder sb = new StringBuilder();
        sb.append('1');
        sb.append(randomCode());
        sb.append(base32(packed));
        String code = sb.toString();
        if (code.length() > MAX_CHARS) return null;
        return code;
    }

    static final class Unpacked {
        List<double[]> points;
        boolean loop;
        String code;
        String error;
    }

    /** 解包；不是数据码就返回 error */
    static Unpacked unpack(String raw) {
        Unpacked u = new Unpacked();
        if (raw == null) { u.error = "空"; return u; }
        String s = raw.toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (s.length() < 8) { u.error = "码太短"; return u; }

        if (s.charAt(0) != '1') {
            u.error = "不是内嵌数据的码";
            u.code = s.length() >= 7 ? s.substring(1, 7) : s;
            return u;
        }
        u.code = s.substring(1, 7);

        String payload = s.substring(7);
        byte[] packed = unbase32(payload);
        if (packed == null) { u.error = "码体不合法"; return u; }

        byte[] rawBytes;
        try {
            ByteArrayInputStream bi = new ByteArrayInputStream(packed);
            InflaterInputStream iis = new InflaterInputStream(bi);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = iis.read(buf)) > 0) bo.write(buf, 0, n);
            iis.close();
            rawBytes = bo.toByteArray();
        } catch (Exception e) {
            return u;   // 可能是未压缩的，下面直接按原样解
        }

        try {
            int p = 0;
            int count = rawBytes[p++] & 0xFF;
            u.loop = (rawBytes[p++] & 1) == 1;
            List<double[]> pts = new ArrayList<double[]>(count);
            long prevLat = 0, prevLon = 0;
            for (int i = 0; i < count; i++) {
                long[] a = readVarint(rawBytes, p); p = (int) a[1];
                long[] b = readVarint(rawBytes, p); p = (int) b[1];
                long lat = prevLat + unzigzag(a[0]);
                long lon = prevLon + unzigzag(b[0]);
                prevLat = lat; prevLon = lon;
                pts.add(new double[]{lat / 1e5, lon / 1e5});
            }
            if (pts.size() < 2) { u.error = "点数不足"; return u; }
            u.points = pts;
            return u;
        } catch (Exception e) {
            u.error = "解包失败";
            return u;
        }
    }

    // ---- 工具 ------------------------------------------------------------

    private static List<double[]> thin(List<double[]> pts, int max) {
        if (pts.size() <= max) return pts;
        List<double[]> out = new ArrayList<double[]>(max);
        double step = (pts.size() - 1.0) / (max - 1);
        for (int i = 0; i < max; i++) {
            out.add(pts.get((int) Math.round(i * step)));
        }
        return out;
    }

    private static void writeVarint(ByteArrayOutputStream bo, long v) {
        while ((v & ~0x7FL) != 0) {
            bo.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        bo.write((int) (v & 0x7F));
    }

    private static long[] readVarint(byte[] b, int off) {
        long result = 0;
        int shift = 0;
        int p = off;
        while (true) {
            int x = b[p++] & 0xFF;
            result |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) break;
            shift += 7;
            if (shift > 63) throw new IllegalStateException("varint overflow");
        }
        return new long[]{result, p};
    }

    private static long zigzag(long v) { return (v << 1) ^ (v >> 63); }
    private static long unzigzag(long v) { return (v >>> 1) ^ -(v & 1); }

    private static String randomCode() {
        java.util.Random r = new java.util.Random();
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) sb.append(B32.charAt(r.nextInt(B32.length())));
        return sb.toString();
    }

    private static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buf = 0, bits = 0;
        for (byte b : data) {
            buf = (buf << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(B32.charAt((buf >>> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(B32.charAt((buf << (5 - bits)) & 0x1F));
        return sb.toString();
    }

    private static byte[] unbase32(String s) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = B32.indexOf(s.charAt(i));
            if (v < 0) return null;
            buf = (buf << 5) | v;
            bits += 5;
            if (bits >= 8) {
                bo.write((buf >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return bo.toByteArray();
    }
}
