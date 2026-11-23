package com.qianyuzhou.keepgen;

import android.content.Context;

/**
 * 解锁码校验。
 *
 * ── 为什么长这样 ─────────────────────────────────────────────
 *
 * 码 = BASE32( 随机 6 字节 | HMAC-SHA256 前 6 字节 )
 * 共 12 字节 → 20 个字符，按 4 位一组写成 XXXX-XXXX-XXXX-XXXX-XXXX。
 *
 * 特点：
 *   · **跟时间没有任何关系**。不看日期、不看安装时间、不看运行时长。
 *     码生成了就永久有效，不存在"过期"这回事。
 *   · 随机空间 2^48，签名 2^48。要蒙对一整个码需要 2^96 次尝试，
 *     相当于不可能；光靠签名蒙也需要 2^48 次，按每秒一亿次算要 32 天。
 *   · 没有规律。码里没有批次号、没有序号、没有可推导的字段，
 *     拿到一批码也推不出下一批。
 *
 * ── 密钥可以远程更新 ─────────────────────────────────────────
 *
 * SECRET 只是内置的**默认**密钥。启动时会去 GitHub 拉一份 secrets.json：
 *
 *   { "secrets": ["<hex>", "<hex>", ...] }
 *
 * 校验时会把这几个密钥**都试一遍**，任意一个对上就算通过。
 * 这样换密钥的过渡期里，老码还能用、新码也能用，不会一刀切作废。
 *
 * 用法：发现密钥泄露 → 生成新密钥 → 加进 secrets.json 的数组开头
 * → 推上去 → 所有 App 下次启动自动生效。**不用重新发版**。
 *
 * 需要说清楚的：密钥在 APK 里，反编译能看到内置那一个。
 * 所以内置密钥只当兜底，真正在用的应该是远程那份——
 * 一旦怀疑泄露，换掉远程的就行，不用动安装包。
 */
final class Redeem {

    /**
     * 内置密钥。**第一批 1000 个码就是用这个生成的**，所以它必须在包里。
     *
     * 反编译能看到它，这是离线验签的固有代价，接受。
     * 应对办法是远程密钥：一旦怀疑泄露，把新密钥写进 GitHub 上的
     * secrets.json，所有 App 下次启动就换成新的，内置这个随之失效
     * （见 allSecrets 的规则），不用重新发版。
     */
    private static final byte[] SECRET_DEFAULT = {
            (byte) 0x41, (byte) 0xC2, (byte) 0x98, (byte) 0x41,
            (byte) 0x20, (byte) 0xAA, (byte) 0xDA, (byte) 0xAD,
            (byte) 0xBA, (byte) 0xFE, (byte) 0x1F, (byte) 0x57,
            (byte) 0x82, (byte) 0x6F, (byte) 0x67, (byte) 0x4D,
            (byte) 0x95, (byte) 0xEF, (byte) 0xD2, (byte) 0x0D,
            (byte) 0xB8, (byte) 0xA3, (byte) 0xE3, (byte) 0xAD,
            (byte) 0x15, (byte) 0x5B, (byte) 0xF6, (byte) 0x07,
            (byte) 0x03, (byte) 0xB8, (byte) 0x4C, (byte) 0xC4
    };

    /** 码体长度（不含连字符）：6 字节随机 + 6 字节签名 = 12 字节 → 20 字符 */
    static final int RAW_LEN = 12;
    static final int CODE_LEN = 20;

    /**
     * 32 个字符，**必须正好 32 个**。
     * base32 每次取 5 位（0~31）当下标，少一个就会越界崩溃。
     * 已在这个项目里被坑过两次（CodeShare 一次、Redeem 一次），写的时候数一遍。
     *
     * 字符选择原则：**去掉所有会看错的**。
     *
     * 去掉的：数字 0 和 1（像 O、I），字母 S 和 Z（像 5、2）。
     * 保留的：O、I、L —— 因为 0 和 1 已经不在表里了，
     * 所以这三个字母不会跟任何数字混，加回来正好凑满 32。
     *
     *   数字 8 个：2 3 4 5 6 7 8 9
     *   字母 24 个：A B C D E F G H I J K L M N O P Q R T U V W X Y
     *   合计 32，无重复。
     *
     * 实测教训：最初那版里 S 和 5 挨着出现，我照着屏幕抄都抄错过一次。
     * 从爱发电复制粘贴没事，手动敲就很容易错，所以字符集要挑。
     */
    private static final String B32 = "23456789ABCDEFGHIJKLMNOPQRTUVWXY";

    private Redeem() {}

    /**
     * 校验一个码。
     *
     * @return true 表示能对上任意一个已知密钥
     */
    static boolean verify(Context ctx, String code) {
        if (code == null) return false;
        String s = code.toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (s.length() != CODE_LEN) return false;

        byte[] raw = base32Decode(s);
        if (raw == null || raw.length < RAW_LEN) return false;

        byte[] data = new byte[RAW_LEN - 6];
        System.arraycopy(raw, 0, data, 0, data.length);
        byte[] sig = new byte[6];
        System.arraycopy(raw, data.length, sig, 0, 6);

        // 内置密钥 + 远程下发的密钥，全都试一遍
        java.util.List<byte[]> keys = allSecrets(ctx);
        for (byte[] k : keys) {
            if (matches(k, data, sig)) return true;
        }
        return false;
    }

    private static boolean matches(byte[] key, byte[] data, byte[] sig) {
        byte[] expect = hmac(key, data, 6);
        int diff = 0;
        for (int i = 0; i < 6; i++) diff |= (expect[i] ^ sig[i]);
        return diff == 0;
    }

    /**
     * 当前可用密钥。**只有内置那一把。**
     *
     * ── 远程换密钥这条机制为什么删掉了 ──────────────────────────
     *
     * 红队 2026-11-22 指出两个致命问题，让这套设计从根上不成立：
     *
     * 1. 「远程换密钥」要生效，远程那份配置必须能被客户端读到。
     *    而唯一不花钱、国内又能连的载体是公开仓库（GitHub raw）。
     *    公开 = 密钥公开 = 任何人都能造码。
     *    本来是为了防泄露，结果把密钥主动公开了。
     *
     * 2. 原逻辑是「一旦拉到远程密钥，内置的立刻失效」。
     *    但如果 secrets.json 里放的就是内置那一把（当时确实是这样），
     *    等于零变化；而一旦真的换成新密钥，作者自己用旧密钥生成的码会全部作废。
     *
     * 结论：在「不维护服务器 + 离线验签」下，远程轮换没有安全可言。
     * 删掉它，只留内置密钥。真要换密钥就发新版本，同时重新生成一批码。
     *
     * 代价说清楚：反编译能看到密钥，就能造出本地验签通过的码。
     * 但这类码在云端核销那一步会被拦住——索引里没有它，DELETE 会 404。
     * 所以单靠泄露的密钥，造不出能真正解锁的码。
     */
    private static java.util.List<byte[]> allSecrets(Context ctx) {
        java.util.List<byte[]> out = new java.util.ArrayList<byte[]>();
        out.add(SECRET_DEFAULT);
        return out;
    }

    /**
     * 码的公开标识：hex(HMAC-SHA256(CODE_SECRET, "claim:"+码)) 的前 32 位。
     *
     * 服务端用同一个算法（cloud/main_v22.py 的 index_key），所以两边能对上。
     * 客户端只拿它去公开清单查「这枚码还在不在激活名单里」，不参与任何验签。
     * 密钥可能有多个候选（allSecrets），逐个试，返回第一个能算出来的。
     */
    static String codeHash(Context ctx, String code) {
        // 这里收到的可能已经是归一化过的码（没有连字符），所以不能再走 KeepFmt.normalize
        // —— normalize 会要求至少两个连字符，那样永远算不出 hash（踩过这个坑）。
        String norm = code == null ? "" : code.toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (norm.length() != KeepFmt.codeLen(ctx)) return "";
        byte[] data = ("claim:" + norm).getBytes();
        for (byte[] k : allSecrets(ctx)) {
            byte[] d = hmac(k, data, 32);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            String hex = sb.toString();
            if (!"00000000000000000000000000000000".equals(hex)) return hex.substring(0, 32);
        }
        return "";
    }

    /** 生成一个码。只给你自己用，界面上没有这个入口。 */
    static String make(Context ctx, long seed) {
        java.util.Random r = new java.util.Random(seed);
        byte[] data = new byte[RAW_LEN - 6];
        r.nextBytes(data);
        byte[] sig = hmac(SECRET_DEFAULT, data, 6);
        byte[] raw = new byte[RAW_LEN];
        System.arraycopy(data, 0, raw, 0, data.length);
        System.arraycopy(sig, 0, raw, data.length, 6);
        return format(base32Encode(raw));
    }

    /** 用指定的十六进制密钥生成码（换密钥后生成新批次用这个） */
    static String makeWith(String secretHex, long seed) {
        byte[] key = hexToBytes(secretHex);
        if (key == null) return null;
        java.util.Random r = new java.util.Random(seed);
        byte[] data = new byte[RAW_LEN - 6];
        r.nextBytes(data);
        byte[] sig = hmac(key, data, 6);
        byte[] raw = new byte[RAW_LEN];
        System.arraycopy(data, 0, raw, 0, data.length);
        System.arraycopy(sig, 0, raw, data.length, 6);
        return format(base32Encode(raw));
    }

    private static String format(String b) {
        if (b.length() < CODE_LEN) return b;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CODE_LEN; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(b.charAt(i));
        }
        return sb.toString();
    }

    private static byte[] hmac(byte[] key, byte[] data, int take) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            byte[] full = mac.doFinal(data);
            byte[] out = new byte[take];
            System.arraycopy(full, 0, out, 0, take);
            return out;
        } catch (Exception e) {
            return new byte[take];
        }
    }

    static String base32Encode(byte[] data) {
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

    static byte[] base32Decode(String s) {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
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

    static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static byte[] hexToBytes(String s) {
        if (s == null) return null;
        s = s.trim().toLowerCase();
        if (s.length() % 2 != 0 || s.isEmpty()) return null;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
