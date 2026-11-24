package com.keepgen.hulibuld;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 使用须知与免责声明。
 *
 * ── 为什么要有这一页 ─────────────────────────────────────────
 *
 * 这个软件生成的是「算法算出来的运动数据」，不是真实运动记录。
 * 它能被用去干什么、不能用来干什么、写进「健康」之后会带来什么后果，
 * 必须在第一次打开时就讲清楚，不能藏在仓库的 Markdown 里。
 *
 * ── 三个入口 ─────────────────────────────────────────────────
 *
 *   ① 首次启动：{@link #gate} 拦一层，点「我已阅读并同意」才进主界面，
 *      点「退出」直接 finish()；
 *   ② 设置页常驻入口：{@link #show} 只读回看，随时能翻；
 *   ③ 条款文本改了：把 {@link #KEY_AGREED} 换成新键（terms_v2、terms_v3…），
 *      已同意过的人会重新看到这一页。
 *
 * 文案以仓库里的 DISCLAIMER.md 为准，**逐句照抄，不改写、不删句**。
 * 其中三句是红线，单独做成醒目的一块：
 *   · 这些数据是算法算出来的，不是你跑出来的
 *   · 不得用于成绩申报、赛事、考核、保险与医疗
 *   · 写入健康的数据可能干扰医疗判断
 */
final class DisclaimerPage {

    private static final String PREF = "terms";

    /**
     * 同意标记的键名。**同时也是条款文本的版本号**：
     * 条款改了就换成 terms_v2，旧标记自然失效，已同意的人会重新弹一次。
     */
    static final String KEY_AGREED = "terms_v1";

    private DisclaimerPage() {}

    /** 这一版条款是否已经同意过 */
    static boolean isAgreed(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(KEY_AGREED, false);
    }

    static void markAgreed(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AGREED, true).apply();
    }

    /** 只读回看（设置页入口）：只有一个「关闭」 */
    static void show(Activity act) {
        final Sheet s = Sheet.make(act);
        s.title("使用须知与免责声明",
                "你可以随时回到这里重看。条款有更新时会再弹一次。");
        fill(act, s);
        s.spacer(Ui.dp(act, 16));
        s.closeOnly();
        s.show();
    }

    /**
     * 首次启动的拦截层。已同意过就直接返回 true，界面上什么都不发生。
     *
     * @param onAgree 点完「我已阅读并同意」之后要做的事（比如接着弹新手引导）
     * @return true 表示不需要拦截（已经同意过）
     */
    static boolean gate(final Activity act, final Runnable onAgree) {
        if (isAgreed(act)) return true;

        final Sheet s = Sheet.make(act);
        // 阻塞型：点空白关不掉，只能点下面两个按钮之一
        // （按返回键等于「退出」：Activity 直接关掉，不会绕过这一页进主界面）
        s.blockDismiss();
        s.title("使用须知与免责声明",
                "第一次用之前请把下面这四节读完。点「我已阅读并同意」才会进主界面。");

        fill(act, s);

        s.spacer(Ui.dp(act, 14));
        s.text("点「我已阅读并同意」，表示你已经读过并接受上面全部内容。"
                + "不同意其中任何一条，点「退出」关掉软件，不要使用。");

        s.danger("退出", new Runnable() {
            public void run() {
                act.finish();
            }
        });
        s.primary("我已阅读并同意", new Runnable() {
            public void run() {
                markAgreed(act);
                s.dismiss();
                if (onAgree == null) return;
                // 等这一层收起来的动画走完再弹下一层，否则两块面板会叠在一起
                act.getWindow().getDecorView().postDelayed(new Runnable() {
                    public void run() {
                        if (!act.isFinishing()) onAgree.run();
                    }
                }, 260);
            }
        });
        s.show();
        return false;
    }

    // ================= 正文 =================

    /** 正文按 DISCLAIMER.md 的顺序排：先红线，再四节原文 */
    private static void fill(Activity act, Sheet s) {
        hardLines(act, s);

        s.section("一　这个软件到底做什么");
        s.text("1. 本软件生成运动数据：按你输入的日期、时长、距离、配速、心率与路线，"
                + "计算出一份完整的训练记录，并写入你手机的「健康」应用"
                + "（Android 为 Health Connect，iOS 为 Apple「健康」），或导出为 FIT / TCX / GPX 文件。");
        s.text("2. 这些数据是算法算出来的，不是你跑出来的。它们不对应任何真实的运动过程、"
                + "真实的心率变化与真实的地理位置。");
        s.text("3. 本软件的设计目的，是个人训练记录的补全与技术研究：例如设备没电、"
                + "跑步机不记录、换手机丢数据时，把当天的训练量补进自己的记录里，让月度统计连续。");
        s.text("4. 本软件不是测量工具、不是医疗设备、不是运动能力的证明工具。");

        s.section("二　明确禁止的用途");
        s.text("下列用途一律禁止。因下列用途产生的一切后果，由使用者自行承担，"
                + "作者不承担任何责任：");
        s.text("1. 学校体育测试、体质健康测试、军训考核、毕业体测等任何以真实性为前提的成绩申报。");
        s.text("2. 各类线上或线下赛事、挑战赛、企业团建、单位考核与绩效评比。");
        s.text("3. 保险理赔、健康告知、体检报告、就医诊断、康复评估等与医疗或保险有关的场景。");
        s.text("4. 社交平台或运动平台的排行、勋章、等级、连续打卡等需要真实性的展示。");
        s.text("5. 伪造他人的运动记录，或冒充他人身份提交数据。");
        s.text("6. 任何需要证明「我确实完成了这项运动」的场合。");
        s.text("用于上述场合可能构成违规、违纪，甚至构成欺诈；由此产生的纪律处分、"
                + "民事赔偿或刑事责任，与本项目无关。");

        s.section("三　与第三方平台的关系");
        s.text("1. 本项目是个人开发的第三方工具，与 Keep（北京卡路里科技有限公司）、"
                + "华为运动健康、小米运动健康、Strava、Garmin 及其关联公司"
                + "没有任何隶属、授权、代理、合作或背书关系。");
        s.text("2. 上述平台的名称、商标、图标归各自权利人所有。本项目提及它们仅用于说明"
                + "数据流向与兼容性，不主张任何权利。");
        s.text("3. Keep 等平台的用户协议通常要求数据真实。写入伪造数据可能违反其用户协议，"
                + "可能导致记录被删除、账号被限制或封禁、权益被收回。这些后果由你自行承担。");
        s.text("4. 若平台的技术手段识别出数据异常（例如步频、配速、轨迹规律性不符合真实运动特征），"
                + "本项目不提供任何规避手段的保证，也不对识别结果负责。");

        s.section("四　健康数据的特别提示");
        s.text("1. 写入「健康」的数据会被系统与其他应用读取，包括可能被用于运动建议、"
                + "心率区间统计与健康趋势分析。");
        s.text("2. 伪造的心率数据与训练数据可能干扰医疗判断。若你就医、体检或使用健康类应用，"
                + "请先确认这些数据是否已被同步；必要时在「健康」中删除对应记录，"
                + "并主动告知医生这些数据不是真实测量结果。");
        s.text("3. 写入的路线与位置是算法生成的坐标，不代表你曾经到过该地点。"
                + "若你的设备开启了位置记录或家庭共享，相关数据可能与真实位置不一致，"
                + "由此产生的误会由你自行处理。");
        s.text("4. 本项目不对写入数据的医学意义、安全性或适用性作任何承诺。");
    }

    /** 三条红线：用危险色卡面单独拎出来，别让人从整段灰色正文里读漏 */
    private static void hardLines(Activity act, Sheet s) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.roundStroke(Ui.DANGER_BG, Ui.DANGER, Ui.RADIUS_CTRL, 1, act));
        int p = Ui.dp(act, 14);
        box.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(act, 4);
        box.setLayoutParams(lp);

        TextView head = new TextView(act);
        head.setText("先记住这三句");
        head.setTextSize(12);
        head.setLetterSpacing(0.08f);
        head.setTextColor(Ui.DANGER);
        box.addView(head);

        line(act, box, "这些数据是算法算出来的，不是你跑出来的");
        line(act, box, "不得用于成绩申报、赛事、考核、保险与医疗");
        line(act, box, "写入健康的数据可能干扰医疗判断");
        s.view(box);
    }

    private static void line(Activity act, LinearLayout box, String text) {
        TextView tv = new TextView(act);
        tv.setText("·　" + text);
        tv.setTextSize(14.5f);
        tv.setTextColor(Ui.TEXT);
        tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        tv.setLineSpacing(Ui.dp(act, 4), 1f);
        tv.setPadding(0, Ui.dp(act, 8), 0, 0);
        box.addView(tv);
    }
}