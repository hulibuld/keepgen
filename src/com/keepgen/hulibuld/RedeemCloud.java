package com.keepgen.hulibuld;

import android.content.Context;

/**
 * 兑换码核销（v22）。
 *
 * ── 为什么这一版只有 30 行 ──────────────────────────────────
 *
 * v21 是客户端自己读公开索引、自己 DELETE 那条 paste.rs 记录，然后向云端换 token。
 * 问题有两个，都是致命的：
 *   ① 索引是公开明文 JSON，码对所有人可读；
 *   ② 云端从不检查「这个码是不是已经被用掉」—— 已核销的码照样能换 token。
 *
 * v22 把这三件事全搬到服务端：查码库、核销、计数。客户端只负责
 * 「把码和设备报上去，把服务端的话翻译成人话」。
 *
 * 本地仍然先验一次 HMAC 签名（Redeem.verify）—— 那是为了让「输错一位」
 * 立刻提示，不用白跑一趟网络；它不是安全边界。
 */
final class RedeemCloud {

    static final int OK = 0;
    static final int USED = 1;
    static final int INVALID = 2;
    static final int NETWORK = 3;
    static final int BUSY = 4;

    static final class Result {
        final int code;
        final String message;
        Result(int c, String m) { code = c; message = m; }
    }

    private RedeemCloud() {}

    /** 会发网络请求，**必须在后台线程调用** */
    static Result redeem(Context ctx, String rawCode) {
        String norm = rawCode.toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (norm.length() != 20) return new Result(INVALID, "码的位数不对");

        CloudUnlock.Rep r = CloudUnlock.exchange(ctx, norm);
        switch (r.state) {
            case CloudUnlock.OK:
                CloudTrack.saveToken(ctx, r.token);
                return new Result(OK, "");
            case CloudUnlock.ALREADY:
                return new Result(USED, "这个码已经被用过了");
            case CloudUnlock.NOT_SOLD:
                return new Result(INVALID, "这个码不在码库里，检查是否输错");
            case CloudUnlock.BUSY:
                return new Result(BUSY, "码库暂时没响应，过一会儿再试");
            default:
                return new Result(NETWORK, "网络不通，换个网络再试");
        }
    }
}