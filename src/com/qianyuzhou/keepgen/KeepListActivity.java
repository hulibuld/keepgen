package com.qianyuzhou.keepgen;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Keep 记录列表：把云端已经存下来的跑步 / 骑行 / 走路读出来，点开看细节。
 *
 * 跟「运动数据生成」是两件事：那一页负责造数据并写进账号，这一页只读，
 * 不写、不删、不碰账号里的任何东西。所以这里没有任何弹窗确认，
 * 点错最多是看到一个空列表。
 *
 * 读是三类分别请求再合并（running / cycling / walking）：Keep 的列表接口
 * 一次只认一个大类，没有「全部」这个取值，实测传 all 会拿到空数组。
 */
public final class KeepListActivity extends Activity {

    /** 登录页返回时用这个号认领，避免和别的 startActivityForResult 撞车 */
    static final int REQ_LOGIN = 1001;

    /** 顺序就是卡片里出现的先后；同类内部再按开始时间倒序 */
    private static final String[] TYPES = {"running", "cycling", "walking"};

    private LinearLayout content;
    private TextView summary;
    private Button btnRefresh;

    private final List<KeepModel.Item> items = new ArrayList<>();
    private boolean loading;
    /** 详情弹层自己管，返回键先关它再退页 */
    private Sheet sheet;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 颜色是建界面那一刻从 Ui 取的，所以先定明暗再搭 view
        Ui.applyTheme(this);
        setContentView(buildUi());
        applySystemBars();
        if (KeepApi.loggedIn(this)) load();
        else renderLogin();
    }

    private void applySystemBars() {
        try {
            Window w = getWindow();
            w.setStatusBarColor(Ui.barColor());
            w.setNavigationBarColor(Ui.barColor());
            w.getDecorView().setSystemUiVisibility(
                    Ui.isDark() ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Exception ignored) {}
    }

    // ================= 界面骨架 =================

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 26), Ui.dp(this, 20), Ui.dp(this, 30));
        scroll.addView(root);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        // 二级页面不放「返回」：左缘右滑退页

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleBox.setPadding(0, 0, 0, 0);

        TextView title = new TextView(this);
        title.setText("Keep 记录");
        // 比主页标题小一号：这是二级页面，跟主页抢视觉没必要
        title.setTextSize(24);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(Ui.TEXT);
        title.setLetterSpacing(-0.02f);
        titleBox.addView(title);

        summary = Ui.subtitle(this, "");
        summary.setPadding(0, Ui.dp(this, 4), 0, 0);
        titleBox.addView(summary);
        topBar.addView(titleBox);

        root.addView(topBar);

        // 刷新做成和「登录 Keep」一样的大横条：一眼能看见，手指好点
        btnRefresh = new Button(this);
        btnRefresh.setText("刷新记录");
        btnRefresh.setAllCaps(false);
        btnRefresh.setTextSize(16);
        btnRefresh.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btnRefresh.setTextColor(Ui.ACCENT_INK);
        btnRefresh.setBackground(Ui.round(Ui.ACCENT_SOFT, Ui.RADIUS_CARD, this));
        btnRefresh.setStateListAnimator(null);
        btnRefresh.setMinHeight(0);
        btnRefresh.setMinimumHeight(0);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, Ui.H_PRIMARY));
        rlp.topMargin = Ui.dp(this, 14);
        btnRefresh.setLayoutParams(rlp);
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { load(); }
        });
        root.addView(btnRefresh);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, Ui.dp(this, 10), 0, 0);
        root.addView(content);

        setSummary(accountLine());
        return scroll;
    }

    /** 顶部那种小胶囊按钮，跟主页「设置」一致 */
    private Button pill(String text, final Runnable r) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12.5f);
        b.setBackground(Ui.roundStroke(Ui.CARD_HI, Ui.INPUT_STROKE, Ui.RADIUS_PILL, 1, this));
        b.setTextColor(Ui.TEXT);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (r != null) r.run(); }
        });
        return b;
    }

    private void setSummary(String s) {
        if (summary != null) summary.setText(s);
    }

    private String accountLine() {
        String nm = KeepApi.userName(this);
        return (nm == null || nm.isEmpty())
                ? "读的是你登录的 Keep 账号"
                : "读的是 " + nm + " 的账号";
    }

    // ================= 四种状态 =================

    private void renderLogin() {
        content.removeAllViews();
        content.addView(heading("还没连上 Keep"));
        content.addView(body("登录之后，账号里存过的跑步、骑行、走路都会列在这里。"));

        Button login = new Button(this);
        login.setText("登录 Keep");
        Ui.stylePrimary(this, login);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 18);
        login.setLayoutParams(lp);
        login.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // 不用 startActivity 是为了拿到「登录完了」这个回调，好自动重读一遍
                startActivityForResult(new Intent(KeepListActivity.this,
                        KeepLoginActivity.class), REQ_LOGIN);
            }
        });
        content.addView(login);
    }

    private void renderLoading() {
        content.removeAllViews();
        content.addView(heading("正在读…"));
    }

    private void renderEmpty() {
        content.removeAllViews();
        content.addView(heading("没读到记录"));
        content.addView(body("这个账号里还没有跑步、骑行、走路的记录。\n"
                + "先去 Keep 里存一条，再回来点「刷新」。"));
        content.addView(retryRow(false));
    }

    /** 失败分两种：真的没网，和登录过期（后者要重新登录才救得回来） */
    private void renderError(String title, String detail, boolean askLogin) {
        content.removeAllViews();
        content.addView(heading(title));
        if (detail != null && !detail.isEmpty()) content.addView(body(detail));
        content.addView(retryRow(askLogin));
    }

    private LinearLayout retryRow(boolean askLogin) {
        LinearLayout row = Ui.buttonRow(this, null, 18);
        Button again = new Button(this);
        again.setText("重试");
        Ui.styleGhost(this, again);
        again.setLayoutParams(Ui.weight(this, 1f, 0));
        again.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { load(); }
        });
        row.addView(again);

        if (askLogin) {
            Button login = new Button(this);
            login.setText("重新登录");
            Ui.stylePrimary(this, login);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(Ui.dp(this, 8), 0, 0, 0);
            login.setLayoutParams(lp);
            login.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    startActivityForResult(new Intent(KeepListActivity.this,
                            KeepLoginActivity.class), REQ_LOGIN);
                }
            });
            row.addView(login);
        }
        // 只搭好交回去，由调用方决定挂在哪（自己再 addView 一次会 child already has a parent）
        return row;
    }

    private TextView heading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(19);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(Ui.TEXT);
        t.setPadding(0, Ui.dp(this, 34), 0, 0);
        return t;
    }

    private TextView body(String s) {
        TextView t = Ui.subtitle(this, s);
        t.setLineSpacing(Ui.dp(this, 5), 1f);
        t.setPadding(0, Ui.dp(this, 10), 0, 0);
        return t;
    }

    // ================= 读数据 =================

    private void load() {
        if (loading) return;           // 连点两下刷新不该发两轮请求
        if (!KeepApi.loggedIn(this)) {
            setSummary(accountLine());
            renderLogin();
            return;
        }
        loading = true;
        if (btnRefresh != null) {
            btnRefresh.setEnabled(false);
            btnRefresh.setText("读…");
        }
        setSummary("正在读…");
        renderLoading();

        final String token = KeepApi.token(this);
        new Thread(new Runnable() {
            public void run() {
                List<KeepModel.Item> all = new ArrayList<>();
                String err = null;
                boolean expired = false;
                int okTypes = 0;

                for (String t : TYPES) {
                    try {
                        KeepApi.Resp r = KeepApi.stats(token, t);
                        if (r.code == 401 || r.errorCode == 100010) {
                            expired = true;
                            break;
                        }
                        if (!r.ok) {
                            if (err == null) {
                                err = r.text == null || r.text.isEmpty()
                                        ? "Keep 那边回了 " + r.code : r.text;
                            }
                            continue;
                        }
                        okTypes++;
                        all.addAll(KeepModel.stats(r.body, t));
                    } catch (Exception e) {
                        if (err == null) {
                            err = e.getMessage() == null ? e.toString() : e.getMessage();
                        }
                    }
                }

                final List<KeepModel.Item> got = all;
                final String ferr = err;
                final boolean fexpired = expired;
                final int fok = okTypes;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        loading = false;
                        if (btnRefresh != null) {
                            btnRefresh.setEnabled(true);
                            btnRefresh.setText("刷新");
                        }
                        if (fexpired) {
                            setSummary(accountLine());
                            renderError("登录状态过期了",
                                    "Keep 那边已经不认这个登录了，重新登一次就能继续读。", true);
                            return;
                        }
                        if (fok == 0) {
                            setSummary(accountLine());
                            renderError("网络不通", "换一下网络，或者过一会儿再点「重试」。"
                                    + "\n原因：" + (ferr == null ? "没连上" : ferr), false);
                            return;
                        }
                        items.clear();
                        items.addAll(got);
                        sortByNewest(items);
                        if (items.isEmpty()) {
                            setSummary(accountLine());
                            renderEmpty();
                            return;
                        }
                        String line = accountLine() + "　共 " + items.size() + " 条";
                        // 三类只回来一部分时说明一句，别让用户以为记录突然变少了
                        if (fok < TYPES.length) line = line + "　有一类没读到，可以再刷新试试";
                        setSummary(line);
                        renderList();
                    }
                });
            }
        }).start();
    }

    /** 新的在前。startTime 缺失的保持原顺序往后排，不参与比较 */
    private static void sortByNewest(List<KeepModel.Item> list) {
        Collections.sort(list, new Comparator<KeepModel.Item>() {
            public int compare(KeepModel.Item a, KeepModel.Item b) {
                return Long.compare(b.startMs, a.startMs);
            }
        });
    }

    // ================= 列表 =================

    private void renderList() {
        content.removeAllViews();
        for (final KeepModel.Item it : items) {
            content.addView(card(it));
        }
    }

    private View card(final KeepModel.Item it) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        // 描边而非纯色块：深色模式下卡片底色跟页底只差一点点，全靠这条线定界
        card.setBackground(Ui.roundStroke(Ui.CARD, Ui.LINE, Ui.RADIUS_CARD, 1, this));
        int p = Ui.dp(this, 16);
        card.setPadding(p, Ui.dp(this, 14), p, Ui.dp(this, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        card.setLayoutParams(lp);
        card.setClickable(true);

        // 第一行：项目名 + 日期｜右上角来源标签
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout nameBox = new LinearLayout(this);
        nameBox.setOrientation(LinearLayout.VERTICAL);
        nameBox.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(sportName(it.type, it.name));
        name.setTextSize(16);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setTextColor(Ui.TEXT);
        nameBox.addView(name);

        TextView day = new TextView(this);
        day.setText(it.date == null || it.date.isEmpty() ? "日期没读到" : it.date);
        day.setTextSize(12.5f);
        day.setTextColor(Ui.TEXT_DIM);
        day.setPadding(0, Ui.dp(this, 3), 0, 0);
        nameBox.addView(day);
        head.addView(nameBox);

        TextView vendor = vendorTag(it.vendor, it.doubtful);
        if (vendor != null) head.addView(vendor);
        card.addView(head);

        // 距离单独一行放大：列表里最先想看的就是「跑了多远」
        LinearLayout dist = new LinearLayout(this);
        dist.setOrientation(LinearLayout.HORIZONTAL);
        dist.setGravity(Gravity.BOTTOM);
        dist.setPadding(0, Ui.dp(this, 10), 0, 0);

        TextView num = new TextView(this);
        num.setText(kmNumber(it.distM));
        num.setTextSize(26);
        num.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        num.setTextColor(Ui.TEXT);
        num.setLetterSpacing(-0.01f);
        dist.addView(num);

        TextView unit = new TextView(this);
        unit.setText(" 公里");
        unit.setTextSize(12.5f);
        unit.setTextColor(Ui.TEXT_DIM);
        unit.setPadding(0, 0, 0, Ui.dp(this, 5));
        dist.addView(unit);
        card.addView(dist);

        // 时长 / 配速 / 卡路里
        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.setPadding(0, Ui.dp(this, 12), 0, 0);
        addMetric(metrics, "时长", Generator.clock(it.durSec));
        addMetric(metrics, paceLabel(it.type), paceValue(it.type, it.distM, it.durSec));
        addMetric(metrics, "卡路里", it.calorie > 0 ? it.calorie + " 千卡" : "—");
        card.addView(metrics);

        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openDetail(it); }
        });
        return card;
    }

    private void addMetric(LinearLayout row, String label, String value) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(14);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextColor(Ui.TEXT);
        col.addView(v);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(11.5f);
        l.setTextColor(Ui.TEXT_FAINT);
        l.setPadding(0, Ui.dp(this, 3), 0, 0);
        col.addView(l);

        row.addView(col);
    }

    /** 来源标签。读不到来源就不挂标签，宁可空着也不瞎标「第三方」 */
    private TextView vendorTag(String vendor, boolean doubtful) {
        if (doubtful) return Ui.badge(this, "存疑", Ui.WARN);
        if (vendor == null || vendor.isEmpty()) return null;
        if (vendor.toLowerCase(Locale.US).contains("keep")) {
            return Ui.badge(this, "Keep", Ui.TEXT_DIM, Ui.SUNK);
        }
        return Ui.badge(this, "第三方", Ui.WARN);
    }

    // ================= 详情 =================

    private void openDetail(final KeepModel.Item it) {
        // 连点两张卡片不该叠出两层弹层，后一张要等前一张关掉
        if (sheet != null && sheet.isOpen()) return;
        if (it.id == null || it.id.isEmpty()) {
            toast("这条记录缺编号，打不开详情");
            return;
        }
        if (!KeepApi.loggedIn(this)) {
            renderLogin();
            return;
        }
        toast("正在读详情…");
        final String token = KeepApi.token(this);
        final String type = it.type;
        final String id = it.id;

        new Thread(new Runnable() {
            public void run() {
                KeepModel.Detail d = null;
                String err = null;
                boolean expired = false;
                try {
                    KeepApi.Resp r = KeepApi.detail(token, type, id);
                    if (r.code == 401 || r.errorCode == 100010) {
                        expired = true;
                    } else if (!r.ok) {
                        err = r.text == null || r.text.isEmpty()
                                ? "Keep 那边回了 " + r.code : r.text;
                    } else {
                        d = KeepModel.detail(r.body);
                    }
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final KeepModel.Detail fd = d;
                final String ferr = err;
                final boolean fexpired = expired;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        if (fexpired) {
                            toast("登录状态过期了，重新登一次");
                            renderLogin();
                        } else if (fd == null) {
                            showDetailFail(it, ferr == null ? "没读到这条的详情" : ferr);
                        } else {
                            showDetail(it, fd);
                        }
                    }
                });
            }
        }).start();
    }

    private void showDetail(final KeepModel.Item it, final KeepModel.Detail d) {
        final Sheet s = Sheet.make(this);
        sheet = s;

        String sub = it.date == null || it.date.isEmpty() ? "" : it.date;
        if (it.vendor != null && !it.vendor.isEmpty()) {
            sub = sub.isEmpty() ? it.vendor : sub + "　" + it.vendor;
        }
        s.title(sportName(it.type, it.name), sub);

        s.section("总览");
        s.view(Ui.statRow(this, "距离", kmText(d.distM)));
        s.view(Ui.statRow(this, "时长", Generator.clock(d.durSec)));
        s.view(Ui.statRow(this, paceLabel(it.type), paceValue(it.type, d.distM, d.durSec)));
        if (d.calorie > 0) s.view(Ui.statRow(this, "消耗", d.calorie + " 千卡"));

        if (d.avgHr > 0 || d.maxHr > 0) {
            s.section("心率");
            if (d.avgHr > 0) s.view(Ui.statRow(this, "平均", d.avgHr + " 次/分"));
            if (d.maxHr > 0) s.view(Ui.statRow(this, "最高", d.maxHr + " 次/分"));
        }

        if (d.steps > 0 || d.spm > 0) {
            s.section("步数");
            if (d.steps > 0) s.view(Ui.statRow(this, "一共", d.steps + " 步"));
            if (d.spm > 0) {
                s.view(Ui.statRow(this, "步频", Math.round(d.spm) + " 步/分钟"));
            }
        }

        if (!d.kms.isEmpty()) {
            s.section("每公里");
            s.view(kmRow("公里", paceLabel(it.type), "步频", true));
            int shown = Math.min(10, d.kms.size());
            for (int i = 0; i < shown; i++) {
                KeepModel.Km km = d.kms.get(i);
                s.view(kmRow("第 " + km.index + " 公里",
                        kmValue(it.type, km),
                        km.spm > 0 ? String.valueOf(Math.round(km.spm)) : "—",
                        false));
            }
            if (d.kms.size() > shown) {
                s.text("后面还有 " + (d.kms.size() - shown) + " 公里，"
                        + "完整的在 Keep App 里能看到。");
            }
        }

        s.section("轨迹");
        if (d.hasTrack) {
            // 说实话：这串是加密的，解不开。假装能画只会在用户面前崩掉
            s.text("轨迹存在（加密存储，需在 Keep App 内查看）");
        } else {
            s.text("这条没有轨迹。室内跑本来就不带路线。");
        }

        if (d.doubtful) {
            s.text("这条在 Keep 里被标了「存疑」，可能是当时信号不好，数据不太准。");
        }

        s.ghost("关闭", new Runnable() {
            public void run() { s.dismiss(); }
        });
        s.show();
    }

    private void showDetailFail(final KeepModel.Item it, String err) {
        final Sheet s = Sheet.make(this);
        sheet = s;
        s.title(sportName(it.type, it.name), it.date);
        s.text("详情没读到。可能是网络断了一下，也可能是这条记录刚被删了。");
        s.spacer(Ui.dp(this, 8));
        s.text("原因：" + err);
        s.ghost("关闭", new Runnable() {
            public void run() { s.dismiss(); }
        });
        s.show();
    }

    /** 详情里的一行：三列，第一列左对齐，其余靠右 */
    private View kmRow(String a, String b, String c, boolean head) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, head ? 6 : 4), 0, Ui.dp(this, head ? 6 : 4));

        String[] vals = {a, b, c};
        float[] weights = {1.5f, 1.1f, 0.9f};
        for (int i = 0; i < vals.length; i++) {
            TextView t = new TextView(this);
            t.setText(vals[i]);
            t.setTextSize(head ? 11f : 12.5f);
            if (head) {
                t.setTextColor(Ui.TEXT_FAINT);
            } else {
                t.setTextColor(i == 0 ? Ui.TEXT_DIM : Ui.TEXT);
                if (i > 0) t.setTypeface(Typeface.MONOSPACE);
            }
            if (i > 0) t.setGravity(Gravity.END);
            t.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, weights[i]));
            row.addView(t);
        }
        return row;
    }

    // ================= 文案 =================

    /** 骑行看速度更顺，跑步和走路才用配速 */
    private static boolean bySpeed(String type) {
        return "cycling".equals(type);
    }

    private static String paceLabel(String type) {
        return bySpeed(type) ? "时速" : "配速";
    }

    private static String sportName(String type, String name) {
        if (name != null && !name.isEmpty()) return name;
        if ("cycling".equals(type)) return "骑行";
        if ("walking".equals(type)) return "走路";
        return "跑步";
    }

    private static String kmNumber(double m) {
        return String.format(Locale.US, "%.2f", m / 1000.0);
    }

    private static String kmText(double m) {
        return m > 0 ? kmNumber(m) + " 公里" : "—";
    }

    /** 秒/公里 → 5'05" */
    private static String paceText(double secPerKm) {
        if (!(secPerKm > 0)) return "—";
        long s = Math.round(secPerKm);
        return String.format(Locale.US, "%d'%02d\"", s / 60, s % 60);
    }

    private static String speedText(double m, double sec) {
        if (!(m > 0) || !(sec > 0)) return "—";
        return String.format(Locale.US, "%.1f", m / 1000.0 / (sec / 3600.0));
    }

    private static String paceValue(String type, double m, double sec) {
        if (bySpeed(type)) {
            String v = speedText(m, sec);
            return "—".equals(v) ? v : v + " 公里/时";
        }
        return paceText(m > 0 ? sec / (m / 1000.0) : 0);
    }

    private static String kmValue(String type, KeepModel.Km km) {
        if (bySpeed(type)) {
            return speedText(km.distM, km.durSec);
        }
        return paceText(km.paceSec);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---- 侧滑返回 ----
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

    // ================= 生命周期 =================

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_LOGIN) return;
        // 不看 res：用户可能中途点了关闭，只要账号真的登上了就重读
        if (KeepApi.loggedIn(this)) {
            setSummary(accountLine());
            load();
        } else {
            setSummary(accountLine());
            renderLogin();
        }
    }

    @Override
    public void onBackPressed() {
        if (sheet != null && sheet.isOpen()) {
            sheet.dismiss();
            sheet = null;
            return;
        }
        super.onBackPressed();
    }
}