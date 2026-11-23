package com.qianyuzhou.keepgen;

/** 构建期常量。改版本号时同步改 AndroidManifest.xml 的 versionCode。 */
final class BuildConfig {
    static final int VERSION_CODE = 156;
    static final String VERSION_NAME = "32.6";
    /** 包名。更新包校验时用来确认下到的确实是本应用 */
    static final String PACKAGE = "com.qianyuzhou.keepgen";

    /**
     * 真 Keep 的签名证书 SHA-256（大写十六进制）。
     *
     * 自动导入前核对这个值，防止「同名冒充包」骗走点击
     * ——红队 2026-11-22 实测：冒充包能诱导 KeepAuto 点开 99 元支付页。
     *
     * 当前值取自 Keep 9.1.130：
     *   adb shell dumpsys package com.gotokeep.keep | grep -A1 Signatures
     *   → 87:2F:83:8C:...:82
     *
     * ⚠️ 注意：Keep 如果换签名（正常应用不会，但万一），这个值要跟着换，
     * 否则自动导入会停用（表现为「没等到 Keep 界面」）。停用是安全的默认行为。
     */
    static final String KEEP_CERT_SHA256 =
            "872F838CC1CF7D939A06DF095BD985B24B69913E3F96198A53751D2DD8539782";

    /**
     * 兜底下载页。设了服务端就用 /version 里的地址，没设就开这个。
     * 出包前改成你自己的发布页，用户到期后至少有地方去。
     */
    static final String FALLBACK_DOWNLOAD_URL = "https://www.52run.cn/qy.html";

    /**
     * 路线分享用的公共地址。**内置，用户不需要填任何东西。**
     *
     * 为什么不再让用户配服务端：这个软件预计几百到几千人用，路线总共一两千条，
     * 让人为了「拿一条别人的路线」去开服务器、填地址，是把开发者的活推给用户。
     * 地址固定在这里，界面上不再出现。
     *
     * 当前指向仓库里的 public route feed（见 keep-apk/server/README.md），
     * 换成别的公共存储时只改这一行。
     */
    static final String ROUTE_FEED_BASE = "https://raw.githubusercontent.com/hulibuld/keepgen/main";

    /**
     * 已激活码的公开只读清单（「公开仓库」）。
     *
     * 核销成功的码会从云端私有索引转到这个前缀下，一个码一个对象：
     *   activated/<hash>.json   → 200 {"t":...} / 404 未激活或已被作者吊销
     * 对象级 public-read，不需要任何凭据，客户端直接 GET。
     * 换成 GitHub 仓库时只改这一行（那边形态是 raw.githubusercontent.com/<owner>/<repo>/...）。
     */
    static final String PUBLIC_ACT_BASE =
            "https://keepgen-1538693785.cos.ap-guangzhou.myqcloud.com";

    /** 索引的 commit。由 pin-index-commit.py 写入，用来绕开 CDN 缓存 */
    static final String INDEX_COMMIT = "a4bbdd6ec3fc227eeed6d75192d183f5ccc950c8";

    /** 上传走同一个仓库的提交接口；token 不写在包里的，只留接口位置 */
    static final String ROUTE_FEED_UPLOAD = "";

    /**
     * 云端轨迹生成接口。
     *
     * 核心算法（跑道几何、横向摆动、配速曲线）放在腾讯云函数里，
     * 本地只负责把云端返回的轨迹点组装成 TCX/FIT 文件。
     *
     * 这样做的原因：破解者改客户端只能改到「我发出了请求」，
     * 改不了「服务器愿意回我数据」。
     *
     * 实测：201ms 往返，返回 900 个点约 22KB。
     * 按 2 万次/月算 = 0.44GB，在腾讯云免费额度 2GB 内。
     */
    static final String CLOUD_URL =
            "https://1538693785-1df5wxnyu3.ap-guangzhou.tencentscf.com";

    /**
     * 1 元解锁的购买页（爱发电商品）。
     * App 里点「1 元解锁」→「去购买」直接开这个链接，用户不用自己搜。
     */
    static final String UNLOCK_URL =
            "https://afdian.com/item/9ebf4c4ae5eb11f191f1525400c8052f";

    private BuildConfig() {}
}
