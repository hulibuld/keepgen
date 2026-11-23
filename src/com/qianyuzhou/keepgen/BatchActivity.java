package com.qianyuzhou.keepgen;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * 补历史：一次给最近 N 天各写一条记录。
 *
 * 为什么需要它：校园跑的考核是「这个月 15 次」，不是「今天跑一次」。
 * 主页一次只能生成一条，补 15 天要点 15 次、还要改 15 次日期 —— 小白根本走不完。
 *
 * 几条硬规矩（都是被现实打出来的）：
 *   ① 每天的 startTime 必须不同：Keep 对同一 startTime 会返回同一个 logId（去重），
 *      不了解这一点的人会以为「点了没生效」，其实是写到了同一条上。
 *   ② 每条之间要停一下：连着发十几个建档请求，是最容易被风控命中的形态。
 *   ③ 时间带随机秒：整分整秒起跑是「机器生成」的强特征，真实记录几乎不会这样。
 *   ④ 只写 Keep，不落本地文件：15 份 TCX 加起来 2MB，用户也不知道拿去干嘛。
 *      想要文件就回主页单条生成。
 */
public final class BatchActivity extends Activity {

    private EditText etDays, etDist, etPaceMin, etPaceSec, etHour, etMin;
    private TextView tvProgress, tvLog;
    private Button btnRun;
    private volatile boolean running;

    private final StringBuilder log = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.applyTheme(this);
        setContentView(buildUi());
        try {
            getWindow().setStatusBarColor(Ui.barColor());
        } catch (Exception ignored) {}
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 20);
        root.setPadding(p, Ui.dp(this, 26), p, Ui.dp(this, 30));
        scroll.addView(root);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        // 二级页面不放「返回」按钮：从屏幕左缘往右滑就是返回（下面 dispatchTouchEvent 里实现）
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.setPadding(0, 0, 0, 0);
        TextView title = new TextView(this);
        title.setText("补历史");
        title.setTextSize(24);
        title.setTextColor(Ui.TEXT);
        box.addView(title);
        TextView sub = Ui.subtitle(this, "一次补齐最近几天的打卡");
        sub.setPadding(0, Ui.dp(this, 4), 0, 0);
        box.addView(sub);
        topBar.addView(box);
        root.addView(topBar);

        root.addView(label("补几天（最多 30）"));
        etDays = numInput("15", "比如 15");

        root.addView(label("每天距离（公里）"));
        etDist = numInput("5.5", "比如 5.5");

        root.addView(label("配速（分'秒\"）"));
        LinearLayout paceRow = new LinearLayout(this);
        paceRow.setOrientation(LinearLayout.HORIZONTAL);
        etPaceMin = numInput("5", "分");
        etPaceMin.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));
        etPaceSec = numInput("30", "秒");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f);
        sp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        etPaceSec.setLayoutParams(sp);
        paceRow.addView(etPaceMin);
        paceRow.addView(etPaceSec);
        root.addView(paceRow);

        root.addView(label("每天几点开跑"));
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        etHour = numInput("6", "时");
        etHour.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));
        etMin = numInput("30", "分");
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f);
        mp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        etMin.setLayoutParams(mp);
        timeRow.addView(etHour);
        timeRow.addView(etMin);
        root.addView(timeRow);

        TextView note = Ui.subtitle(this, "从昨天往前数，每天一条。周末也补（学校一般不看你哪天跑）。"
                + "每条之间会停 2 秒左右，避免被 Keep 判定为异常。");
        note.setPadding(0, Ui.dp(this, 14), 0, 0);
        root.addView(note);

        btnRun = new Button(this);
        btnRun.setText("开始补");
        Ui.stylePrimary(this, btnRun);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, Ui.H_PRIMARY));
        blp.topMargin = Ui.dp(this, 18);
        btnRun.setLayoutParams(blp);
        btnRun.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { start(); }
        });
        root.addView(btnRun);

        tvProgress = new TextView(this);
        tvProgress.setTextSize(13);
        tvProgress.setTextColor(Ui.TEXT);
        tvProgress.setPadding(0, Ui.dp(this, 16), 0, 0);
        root.addView(tvProgress);

        tvLog = new TextView(this);
        tvLog.setTextSize(12);
        tvLog.setTextColor(Ui.TEXT_DIM);
        tvLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvLog.setPadding(0, Ui.dp(this, 10), 0, 0);
        tvLog.setTextIsSelectable(true);
        root.addView(tvLog);

        return scroll;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(Ui.TEXT_DIM);
        t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        return t;
    }

    private EditText numInput(String def, String hint) {
        EditText e = new EditText(this);
        e.setText(def);
        e.setHint(hint);
        e.setTextSize(16);
        e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        e.setBackground(Ui.roundStroke(Ui.CARD_HI, Ui.INPUT_STROKE, Ui.RADIUS_CTRL, 1, this));
        e.setTextColor(Ui.TEXT);
        return e;
    }

    private Button pill(String text, final Runnable r) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12.5f);
        b.setBackground(Ui.roundStroke(Ui.CARD_HI, Ui.INPUT_STROKE, Ui.RADIUS_PILL, 1, this));
        b.setTextColor(Ui.TEXT);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (r != null) r.run(); }
        });
        return b;
    }

    // ================= 执行 =================

    private void start() {
        if (running) return;
        if (!KeepApi.loggedIn(this)) {
            toast("先去设置里登录 Keep");
            return;
        }
        int days = clamp(parseInt(etDays, 15), 1, 30);
        double dist = clampD(parseDouble(etDist, 5.5), 0.4, 50);
        int paceMin = clamp(parseInt(etPaceMin, 5), 3, 30);
        int paceSec = clamp(parseInt(etPaceSec, 30), 0, 59);
        int hour = clamp(parseInt(etHour, 6), 0, 23);
        int min = clamp(parseInt(etMin, 30), 0, 59);
        double pace = paceMin * 60.0 + paceSec;
        double durSec = Math.round(dist * pace);

        // 前置校验：参数不对就别开跑。
        // 不校验的后果我实测过：配速填成 3:30，15 天全部失败，一串红字刷满屏，
        // 用户根本不知道该改哪个数。
        if (pace < 240) {
            toast("跑步最快只能 4:00 一公里，现在是 " + Generator.clock(pace));
            return;
        }
        if (pace > 1800) {
            toast("配速最慢只能 30:00 一公里");
            return;
        }
        if (durSec > 4 * 3600) {
            toast("单条时长超过 4 小时，把距离或配速调小一点");
            return;
        }

        final String token = KeepApi.token(this);
        final String sport = "run";
        final Field field = Field.anning();

        running = true;
        btnRun.setEnabled(false);
        btnRun.setText("进行中…");
        log.setLength(0);
        tvLog.setText("");
        tvProgress.setText("准备开始…");

        final int fDays = days, fHour = hour, fMin = min;
        final double fDist = dist, fPace = pace, fDur = durSec;

        new Thread(new Runnable() {
            public void run() {
                int ok = 0, fail = 0;
                final int[] lastFail = new int[1];
                java.util.Set<String> done = KeepApi.uploadedDays(BatchActivity.this);
                for (int i = 1; i <= fDays; i++) {
                    if (!running) break;                 // 用户退出页面
                    final int idx = i;
                    // 日期：从昨天往前数，避免出现「未来的记录」
                    Calendar c = Calendar.getInstance();
                    c.add(Calendar.DAY_OF_YEAR, -i);
                    c.set(Calendar.HOUR_OF_DAY, fHour);
                    c.set(Calendar.MINUTE, fMin);
                    // 随机秒：整分整秒起跑是机器特征，真实记录几乎不会这样
                    int sec = (int) ((Math.abs(c.getTimeInMillis() / 60000) * 37) % 60);
                    c.set(Calendar.SECOND, sec);
                    c.set(Calendar.MILLISECOND, 0);

                    // 同一天去重：这一天已经写过了就跳过，不要在同一分数上再压一条
                    final String dk = KeepApi.dayKey(c.getTimeInMillis());
                    if (done.contains(dk)) {
                        post(new Runnable() {
                            public void run() {
                                log.append("第 ").append(idx).append(" 天（").append(dk)
                                   .append("）— 已有记录，跳过\n");
                                tvLog.setText(log.toString());
                                tvProgress.setText("已跳过 " + idx + " / " + fDays);
                            }
                        });
                        continue;
                    }

                    Generator.Req r = new Generator.Req();
                    r.sport = sport;
                    r.distKm = fDist;
                    r.paceSec = fPace;
                    r.durSec = fDur;
                    r.startMs = c.getTimeInMillis();
                    r.seed = (int) (c.getTimeInMillis() % 90000) + 1000;
                    r.hr = true;
                    r.cadence = true;
                    r.wantTcx = false;
                    r.wantFit = false;
                    r.field = field;

                    String err = null;
                    try {
                        Generator.Result res = Generator.generate(r);
                        KeepApi.uploadV5(BatchActivity.this, res);
                        KeepApi.markUploadedDay(BatchActivity.this, res.startMs);
                        done.add(dk);
                        ok++;
                    } catch (Exception e) {
                        fail++;
                        err = e.getMessage() == null ? e.toString() : e.getMessage();
                        lastFail[0] = idx;
                        // 连续 3 次失败基本不是「这一条的问题」，是账号/接口/网络，
                        // 继续刷只会白跑并可能招风控，直接停。
                        if (fail >= 3 && ok == 0) {
                            final String stopLine = "连续 3 次失败，先停下。原因：" + err;
                            post(new Runnable() {
                                public void run() {
                                    log.append(stopLine).append('\n');
                                    tvLog.setText(log.toString());
                                }
                            });
                            break;
                        }
                    }
                    final int okNow = ok, failNow = fail;
                    final String line = "第 " + idx + " 天（"
                            + new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(c.getTime())
                            + "）" + (err == null ? "✓ 已写进 Keep" : "✗ " + err);
                    post(new Runnable() {
                        public void run() {
                            log.append(line).append('\n');
                            tvLog.setText(log.toString());
                            tvProgress.setText("已完成 " + idx + " / " + fDays
                                    + "　成功 " + okNow + "　失败 " + failNow);
                        }
                    });
                    try { Thread.sleep(1800 + (idx % 3) * 500); } catch (Exception ignored) {}
                }

                final int fok = ok, ffail = fail;
                post(new Runnable() {
                    public void run() {
                        running = false;
                        btnRun.setEnabled(true);
                        btnRun.setText(fok > 0 ? "再补一批" : "开始补");
                        tvProgress.setText("完成：成功 " + fok + " 条，失败 " + ffail + " 条");
                        if (ffail > 0) {
                            toast("有 " + ffail + " 条没写进去，看下面的原因");
                        } else {
                            toast("全部写进 Keep 了");
                        }
                    }
                });
            }
        }).start();
    }

    private void post(Runnable r) {
        runOnUiThread(r);
    }

    private static int parseInt(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return def; }
    }

    private static double parseDouble(EditText e, double def) {
        try { return Double.parseDouble(e.getText().toString().trim()); } catch (Exception x) { return def; }
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private static double clampD(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    // ---- 侧滑返回：左缘起手、横向位移超过 110dp 就退页 ----
    private float downX, downY;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                break;
            case android.view.MotionEvent.ACTION_UP:
                if (downX < Ui.dp(this, 32)
                        && e.getX() - downX > Ui.dp(this, 110)
                        && Math.abs(e.getY() - downY) < Ui.dp(this, 90)) {
                    finish();
                    return true;
                }
                break;
        }
        return super.dispatchTouchEvent(e);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        running = false;   // 让循环在下一轮退出
        super.onDestroy();
    }
}