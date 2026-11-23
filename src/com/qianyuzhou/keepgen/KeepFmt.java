package com.qianyuzhou.keepgen;

/**
 * 兑换码的形态判定 —— 规则不写在代码里。
 *
 * ── 为什么要藏 ──────────────────────────────────────────────
 *
 * 「20 位」「至少两个连字符」「必须含字母和数字」这些如果写成明文常量，
 * 反编译一眼就能看到，等于把兑换码的格式直接送人：知道位数和字符集，
 * 剩下的就是穷举空间大小的问题。
 *
 * 这里把全部规则编码成一段字节，密钥是**本机安装包的签名证书**：
 *   · 静态看 BLOB 是一串随机字节，看不到 20 / 2 / A-Z
 *   · 换个签名的重打包版本，密钥就变了，解出来的规则是垃圾（它会判所有码非法）
 *   · 想拿走规则，得同时拿到 BLOB 和证书，还要看懂 XOR 这层
 *
 * 诚实说清楚强度：这挡不住会用调试器的人 —— 他把 rules() 跑一遍就拿到明文规则。
 * 它挡的是「jadx 里搜一下 20 就知道位数」这种零成本逆向，以及把规则抄给别人的搬运工。
 * 和 License 那套一样，定位是「提高门槛」，不是「防死」。
 */
final class KeepFmt {

    private KeepFmt() {}

    /**
     * 编码后的规则：[0]=归一化后长度, [1]=最少连字符数, [2]=必须含数字, [3]=必须含字母,
     * [4..20)=数字字符集(128 位图), [20..36)=字母字符集, [36..52)=允许出现的字符集。
     * 每个字节都与签名证书做 XOR。
     */
    private static final byte[] BLOB = {
            (byte) 0x24, (byte) 0x80, (byte) 0x04, (byte) 0x85,
            (byte) 0x30, (byte) 0x82, (byte) 0x03, (byte) 0x6C,
            (byte) 0xA0, (byte) 0x03, (byte) 0xFD, (byte) 0x02,
            (byte) 0x02, (byte) 0x02, (byte) 0x09, (byte) 0x00,
            (byte) 0xC6, (byte) 0xFD, (byte) 0x96, (byte) 0xA0,
            (byte) 0x43, (byte) 0x48, (byte) 0x03, (byte) 0x6D,
            (byte) 0x30, (byte) 0x0D, (byte) 0x06, (byte) 0x09,
            (byte) 0xD4, (byte) 0x79, (byte) 0xB7, (byte) 0x81,
            (byte) 0x09, (byte) 0xF2, (byte) 0xFE, (byte) 0x06,
            (byte) 0x0C, (byte) 0x05, (byte) 0x00, (byte) 0x30,
            (byte) 0x6F, (byte) 0x31, (byte) 0xF4, (byte) 0x33,
            (byte) 0xF7, (byte) 0xF9, (byte) 0xFC, (byte) 0x52,
            (byte) 0xFA, (byte) 0xF9, (byte) 0xEC, (byte) 0x05,
    };

    private static byte[] rules(android.content.Context ctx) {
        byte[] k = License.signatureBytes(ctx);
        if (k == null || k.length == 0) k = new byte[]{1};
        byte[] out = new byte[BLOB.length];
        for (int i = 0; i < BLOB.length; i++) {
            out[i] = (byte) (BLOB[i] ^ k[i % k.length]);
        }
        return out;
    }

    private static boolean inSet(byte[] set, int idx) {
        int word = idx >> 3;
        if (word < 0 || word >= 16) return false;
        return (set[word] & (1 << (idx & 7))) != 0;
    }

    /** 归一化后的码长（外部拿不到明文常量） */
    static int codeLen(android.content.Context ctx) {
        return rules(ctx)[0] & 0xFF;
    }

    /**
     * 归一化 + 形态判定。不合格返回空串（调用方据此直接判「验证码无效」，不发网络请求）。
     */
    static String normalize(android.content.Context ctx, String raw) {
        if (raw == null) return "";
        byte[] r = rules(ctx);
        int needLen = r[0] & 0xFF;
        int needDash = r[1] & 0xFF;
        boolean needDigit = (r[2] & 1) != 0;
        boolean needLetter = (r[3] & 1) != 0;

        byte[] digitSet = slice(r, 4);
        byte[] letterSet = slice(r, 20);
        byte[] allowSet = slice(r, 36);

        StringBuilder norm = new StringBuilder(raw.length());
        int dash = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '-') {
                dash++;
                continue;
            }
            int c = ch & 0x7F;
            if (!inSet(allowSet, c)) return "";        // 空格、汉字、其它符号一律判为无效
            boolean isDigit = inSet(digitSet, c);
            boolean isLetter = inSet(letterSet, c);
            if (!isDigit && !isLetter) return "";
            norm.append(Character.toUpperCase(ch));
        }
        if (dash < needDash) return "";
        if (norm.length() != needLen) return "";

        boolean hasDigit = false, hasLetter = false;
        for (int i = 0; i < norm.length(); i++) {
            int c = norm.charAt(i);
            if (inSet(digitSet, c)) hasDigit = true;
            else if (inSet(letterSet, c)) hasLetter = true;
        }
        if (needDigit && !hasDigit) return "";
        if (needLetter && !hasLetter) return "";
        return norm.toString();
    }

    private static byte[] slice(byte[] src, int from) {
        byte[] out = new byte[16];
        System.arraycopy(src, from, out, 0, 16);
        return out;
    }
}