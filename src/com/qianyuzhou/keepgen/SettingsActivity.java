package com.qianyuzhou.keepgen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 设置页 —— 控制台磁贴。
 *
 * 把设置从一长串粘连的系统设置行，改成一组像设备控制台上的独立磁贴：
 *   · 每项单独一张圆角卡，卡片之间留呼吸空间，不用 › 驱动操作；
 *   · 彩色图标块当指示灯，动作写成能看懂的胶囊（登录 / 退出 / 查看 / 去开启）；
 *   · 顶部主卡汇总连接与解锁状态，主题是一眼可辨的三张大按钮；
 *   · 退出登录用危险色，关于用凹陷卡面，类别之间有明确质感差异；
 *   · 配色、圆角、字阶全部复用 Ui v2，深浅两套主题都与主界面同族。
 * 行为逻辑保持原样。
 */
public final class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.applyTheme(this);
        setContentView(build());
        a11yShown = KeepAuto.enabled(this);
        applySystemBars();
    }

    private void applySystemBars() {
        try {
            getWindow().setStatusBarColor(Ui.barColor());
            getWindow().getDecorView().setSystemUiVisibility(
                    Ui.isDark() ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Exception ignored) {}
    }

    // ================= 骨架 =================

    private View build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(dp(20), dp(30), dp(20), dp(26));
        sv.addView(root);

        root.addView(Ui.title(this, "设置"));
        TextView sub = Ui.subtitle(this, "账号、写入与生成参数，都在这里");
        sub.setPadding(0, dp(4), 0, 0);
        root.addView(sub);

        buildStatusCard(root);

        sectionLabel(root, "连接", "账号与写入目标");
        if (KeepApi.loggedIn(this)) {
            actionTile(root, "ic_lock", Ui.DANGER, Ui.DANGER_BG, "退出登录",
                    "清除 Keep 账号，本地生成的文件不受影响", "退出", true,
                    v -> confirmLogout());
        }
        buildAutoWrite(root);
        actionTile(root, "ic_calendar", Ui.ACCENT, Ui.ACCENT_SOFT, "补历史",
                "一次补齐前几天的运动记录", "补录", true,
                v -> startActivity(new Intent(SettingsActivity.this, BatchActivity.class)));
        actionTile(root, "ic_route", Ui.ACCENT, Ui.ACCENT_SOFT, "我的 Keep 记录",
                "查看账号里已有的跑步、骑行、走路", "查看", true,
                v -> startActivity(new Intent(SettingsActivity.this, KeepListActivity.class)));

        sectionLabel(root, "生成参数", "决定心率曲线的三个数");
        buildParams(root);

        sectionLabel(root, "外观", "主题模式");
        buildAppearance(root);

        sectionLabel(root, "系统", "权限与辅助");
        final boolean hcOk = Hc.available(this);
        String hcVal = !hcOk ? "本机不支持"
                : (Hc.hasWritePermission(this) ? "已授权" : "未授权");
        actionTile(root, "ic_shield", Ui.OK, Ui.OK_BG, "写入手机健康数据",
                "允许把生成的数据写进手机健康应用", hcVal, true,
                hcOk ? v -> startActivity(new Intent(SettingsActivity.this, HcPermActivity.class)) : null);
        actionTile(root, "ic_settings", Ui.ACCENT, Ui.ACCENT_SOFT, "自动导入 Keep",
                "开无障碍服务，生成后自动跳转导入",
                KeepAuto.enabled(this) ? "已开启" : "去开启", true, v -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception e) {
                        toast("打不开设置");
                    }
                });

        sectionLabel(root, "关于", null);
        buildAbout(root);

        root.addView(Ui.footer(this, "运动数据生成 " + BuildConfig.VERSION_NAME
                + "  ·  生成的运动数据仅用于个人记录\n请遵守所在学校与平台的规定。", null));
        return sv;
    }

    // ================= 顶部状态主卡 =================

    private void buildStatusCard(LinearLayout root) {
        final boolean in = KeepApi.loggedIn(this);
        String name = KeepApi.userName(this);
        String state = in ? (name == null || name.isEmpty() ? "已登录" : name) : "未登录";

        LinearLayout card = Ui.cardBox(this, root);
        card.setPadding(dp(18), dp(18), dp(18), dp(16));
        card.setBackground(pressable(Ui.ACCENT_SOFT, Ui.ACCENT, Ui.RADIUS_CARD));

        LinearLayout row = Ui.buttonRow(this, null, 0);
        // Keep 用自己的 logo（从官方包里取的 ic_launcher_keep），别用主题色去染
        View icon = Ui.brandBadge(this, "keep_logo", 0xFFFFFFFF, dp(48), dp(34));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(48), dp(48));
        ilp.rightMargin = dp(14);
        icon.setLayoutParams(ilp);
        row.addView(icon);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(Ui.weight(this, 1f, 0));
        TextView cap = new TextView(this);
        cap.setText("Keep 连接");
        cap.setTextSize(12);
        cap.setTextColor(Ui.isDark() ? Ui.TEXT_DIM : Ui.ACCENT_INK);
        cap.setPadding(0, 0, 0, dp(2));
        col.addView(cap);
        TextView big = new TextView(this);
        big.setText(state);
        big.setTextSize(21);
        big.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        big.setTextColor(Ui.isDark() ? Ui.TEXT : Ui.ACCENT_INK);
        big.setSingleLine(true);
        col.addView(big);
        row.addView(col);

        row.addView(Ui.badge(this, in ? "已连接" : "登录", Ui.ACCENT));
        card.addView(row);

        LinearLayout badges = Ui.buttonRow(this, card, 14);
        boolean unlocked = License.isUnlocked(this);
        badges.addView(Ui.badge(this, unlocked ? "已解锁" : "未解锁",
                unlocked ? Ui.OK : Ui.WARN));

        card.setClickable(true);
        card.setOnClickListener(v -> openLogin());
    }

    // ================= 分区标题 =================

    private void sectionLabel(LinearLayout root, String title, String sub) {
        LinearLayout box = Ui.plainBox(this, root);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(Ui.TEXT_DIM);
        t.setPadding(dp(4), dp(4), 0, 0);
        box.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = Ui.hint(this, sub);
            s.setPadding(dp(4), dp(2), 0, 0);
            box.addView(s);
        }
    }

    // ================= 磁贴构建 =================

    /** 通用动作磁贴：图标块 + 标题/说明 + 右侧动作胶囊或数值 + 整卡点击 */
    private LinearLayout actionTile(LinearLayout parent, String glyph, int fg, int bg,
                                    String title, String desc, String right,
                                    boolean rightIsPill, View.OnClickListener onClick) {
        LinearLayout card = Ui.cardBox(this, parent);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout row = Ui.buttonRow(this, null, 0);

        View icon = iconBadge(glyph, fg, bg, dp(44), 24);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(44), dp(44));
        ilp.rightMargin = dp(14);
        icon.setLayoutParams(ilp);
        row.addView(icon);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(Ui.weight(this, 1f, 0));
        col.addView(tileTitle(title));
        if (desc != null && !desc.isEmpty()) col.addView(descText(desc));
        row.addView(col);

        if (right != null && !right.isEmpty()) {
            row.addView(rightIsPill ? Ui.badge(this, right, fg) : paramValue(right));
        }

        card.addView(row);
        if (onClick != null) makeClickable(card, onClick);
        return card;
    }

    /** 自动写进 Keep：磁贴 + 系统开关（读写 keep_upload.auto） */
    private void buildAutoWrite(LinearLayout root) {
        LinearLayout card = Ui.cardBox(this, root);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout row = Ui.buttonRow(this, null, 0);

        View icon = iconBadge("ic_download", Ui.ACCENT, Ui.ACCENT_SOFT, dp(44), 24);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(44), dp(44));
        ilp.rightMargin = dp(14);
        icon.setLayoutParams(ilp);
        row.addView(icon);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(Ui.weight(this, 1f, 0));
        col.addView(tileTitle("自动写进 Keep"));
        col.addView(descText("生成后直接写入账号，免手动导入"));
        row.addView(col);

        Switch sw = new Switch(this);
        sw.setChecked(getSharedPreferences("keep_upload", MODE_PRIVATE)
                .getBoolean("auto", true));
        sw.setOnCheckedChangeListener((b, v) -> {
            getSharedPreferences("keep_upload", MODE_PRIVATE).edit()
                    .putBoolean("auto", v).apply();
            toast(v ? "以后生成完会自动写进 Keep" : "已关掉自动写入");
        });
        row.addView(sw);
        card.addView(row);
    }

    /** 三个生成参数：轻字重大数字，呼应主界面 */
    private void buildParams(LinearLayout root) {
        final HrProfile hp = HrProfile.load(this);
        paramTile(root, "ic_heart", Ui.DANGER, Ui.DANGER_BG, "静息心率",
                "睡觉时的心率，多数人 55–70", String.valueOf(hp.restBpm), "次/分",
                v -> pickInt("静息心率", "睡觉时的心率，多数人 55–70", "次/分",
                        40, 120, hp.restBpm, new OnNum() {
                            public void onNum(int val) {
                                HrProfile p = HrProfile.load(SettingsActivity.this);
                                p.restBpm = val; p.save(SettingsActivity.this); rebuild();
                            }
                        }));
        paramTile(root, "ic_activity", Ui.WARN, Ui.WARN_BG, "最高心率",
                "220 减年龄，或实测到的最大值", String.valueOf(hp.maxBpm), "次/分",
                v -> pickInt("最高心率", "220 减年龄，或实测到的最大值", "次/分",
                        140, 220, hp.maxBpm, new OnNum() {
                            public void onNum(int val) {
                                HrProfile p = HrProfile.load(SettingsActivity.this);
                                p.maxBpm = val; p.save(SettingsActivity.this); rebuild();
                            }
                        }));
        paramTile(root, "ic_bolt", Ui.WARN, Ui.WARN_BG, "最快 1 公里",
                "配速上限，比如 4:30 就是 270", Generator.clock(hp.paceMaxSec), "",
                v -> pickPace((int) hp.paceMaxSec, new OnNum() {
                            public void onNum(int val) {
                                HrProfile p = HrProfile.load(SettingsActivity.this);
                                p.paceMaxSec = val; p.save(SettingsActivity.this); rebuild();
                            }
                        }));
    }

    private LinearLayout paramTile(LinearLayout parent, String glyph, int fg, int bg,
                                   String title, String desc, String num, String unit,
                                   View.OnClickListener onClick) {
        LinearLayout card = Ui.cardBox(this, parent);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout row = Ui.buttonRow(this, null, 0);

        View icon = iconBadge(glyph, fg, bg, dp(44), 24);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(44), dp(44));
        ilp.rightMargin = dp(14);
        icon.setLayoutParams(ilp);
        row.addView(icon);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(Ui.weight(this, 1f, 0));
        col.addView(tileTitle(title));
        col.addView(descText(desc));
        row.addView(col);

        LinearLayout numCol = new LinearLayout(this);
        numCol.setOrientation(LinearLayout.HORIZONTAL);
        numCol.setGravity(Gravity.BOTTOM);
        TextView n = new TextView(this);
        n.setText(num);
        n.setTextSize(24);
        n.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        n.setTextColor(Ui.TEXT);
        n.setIncludeFontPadding(false);
        numCol.addView(n);
        if (unit != null && !unit.isEmpty()) numCol.addView(Ui.numberUnit(this, unit));
        row.addView(numCol);

        card.addView(row);
        makeClickable(card, onClick);
        return card;
    }

    /** 三个并排的主题大按钮，选中态用强调色填充 + 描边 + 图标点亮 */
    private void buildAppearance(LinearLayout root) {
        final int cur = Ui.mode(this);
        LinearLayout card = Ui.cardBox(this, root);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(Ui.label(this, "主题模式"));
        LinearLayout row = Ui.buttonRow(this, card, 4);
        themeButton(row, "ic_clock", "跟随系统", Ui.MODE_AUTO, cur);
        themeButton(row, "ic_settings", "浅色", Ui.MODE_LIGHT, cur);
        themeButton(row, "ic_lock", "深色", Ui.MODE_DARK, cur);
    }

    private void themeButton(LinearLayout row, String glyph, String name,
                             final int mode, int cur) {
        final boolean on = cur == mode;
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), dp(15), dp(6), dp(12));
        b.setBackground(Ui.roundStroke(on ? Ui.ACCENT_SOFT : Ui.SUNK,
                on ? Ui.ACCENT : Ui.LINE, Ui.RADIUS_CTRL, on ? 2 : 1, this));
        b.setLayoutParams(Ui.weight(this, 1f, 5));

        View g = Ui.icon(this, glyph, on ? Ui.ACCENT : Ui.TEXT_FAINT, 23);
        b.addView(g);

        TextView n = new TextView(this);
        n.setText(name);
        n.setTextSize(12.5f);
        n.setTextColor(on ? Ui.ACCENT_INK : Ui.TEXT_DIM);
        n.setGravity(Gravity.CENTER);
        n.setPadding(0, dp(6), 0, 0);
        b.addView(n);

        b.setClickable(true);
        b.setOnClickListener(v -> applyThemeMode(mode));
        row.addView(b);
    }

    /** 关于：凹陷质感的信息卡 */
    private void buildAbout(LinearLayout root) {
        LinearLayout card = Ui.cardBox(this, root);
        card.setBackground(Ui.round(Ui.SUNK, Ui.RADIUS_CARD, this));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        boolean unlocked = License.isUnlocked(this);
        LinearLayout r1 = Ui.buttonRow(this, null, 0);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        r1.setPadding(dp(4), dp(8), dp(4), dp(8));
        View icon = iconBadge(unlocked ? "ic_trophy" : "ic_lock",
                unlocked ? Ui.OK : Ui.iosBlue(),
                unlocked ? Ui.OK_BG : Ui.iosTintBlue(), dp(48), 26);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(48), dp(48));
        ilp.rightMargin = dp(14);
        icon.setLayoutParams(ilp);
        r1.addView(icon);

        // 状态文字占满剩余宽度，不再和徽章挤在一起
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = unlockStatusText(unlocked);
        col.addView(title);
        TextView desc = descText(unlocked ? "永久可用，没有到期日" : "1 元永久解锁，支持下作者");
        desc.setPadding(0, dp(4), 0, 0);
        col.addView(desc);
        r1.addView(col);

        // 右侧大箭头，iOS 列表那种
        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(28);
        chevron.setTextColor(Ui.TEXT_FAINT);
        chevron.setPadding(dp(10), 0, dp(2), 0);
        r1.addView(chevron);
        card.addView(r1);
        makeClickable(r1, v -> openUnlockInfo());

        card.addView(Ui.divider(this));

        LinearLayout r2 = Ui.buttonRow(this, null, 0);
        View versionIcon = iconBadge("ic_info", Ui.ACCENT, Ui.ACCENT_SOFT, dp(34), 19);
        LinearLayout.LayoutParams versionIconLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        versionIconLp.rightMargin = dp(10);
        versionIcon.setLayoutParams(versionIconLp);
        r2.addView(versionIcon);

        TextView k = Ui.label(this, "版本");
        k.setPadding(0, 0, 0, 0);
        k.setLayoutParams(Ui.weight(this, 1f, 0));
        r2.addView(k);
        r2.addView(Ui.mono(this, BuildConfig.VERSION_NAME));
        card.addView(r2);
    }

    // ================= 磁贴小部件 =================

    private View iconBadge(String glyph, int fg, int bg, float boxDp, float iconDp) {
        return Ui.iconBadge(this, glyph, fg, bg, boxDp, iconDp);
    }

    /** 关于区解锁状态的醒目状态词：标题与状态合一，字号更大、可读性更好 */
    private TextView unlockStatusText(boolean unlocked) {
        TextView t = new TextView(this);
        t.setText(unlocked ? "已解锁" : "尚未解锁");
        t.setTextSize(18f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(unlocked ? Ui.OK : Ui.iosBlue());
        return t;
    }

    private TextView tileTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15.5f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(Ui.TEXT);
        return t;
    }

    private TextView descText(String s) {
        TextView t = Ui.hint(this, s);
        t.setPadding(0, dp(3), 0, 0);
        return t;
    }

    private TextView paramValue(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(Ui.TEXT);
        t.setPadding(dp(6), 0, 0, 0);
        return t;
    }

    private void makeClickable(View v, View.OnClickListener l) {
        if (l == null) return;
        v.setBackground(pressable(Ui.CARD, Ui.LINE, Ui.RADIUS_CARD));
        v.setClickable(true);
        v.setOnClickListener(l);
    }

    /** 轻点反馈：正常=描边卡面，按下=高亮 */
    private StateListDrawable pressable(int fill, int stroke, float radius) {
        StateListDrawable d = new StateListDrawable();
        GradientDrawable pressed = Ui.roundStroke(
                Ui.isDark() ? Ui.CARD_HI : Ui.ACCENT_SOFT,
                Ui.isDark() ? Ui.ACCENT : Ui.ACCENT, radius, 1, this);
        d.addState(new int[]{android.R.attr.state_pressed}, pressed);
        d.addState(new int[]{}, Ui.roundStroke(fill, stroke, radius, 1, this));
        return d;
    }

    // ================= 行为（保持原样） =================

    private void openLogin() {
        if (KeepApi.loggedIn(this)) {
            toast("已经登录了：" + KeepApi.userName(this));
            return;
        }
        startActivityForResult(new Intent(this, KeepLoginActivity.class), 1001);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 1001) rebuild();
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("退出 Keep")
                .setMessage("退出后生成的运动数据仍然会存到手机，只是不再自动写进账号。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        KeepApi.logout(SettingsActivity.this);
                        toast("已退出");
                        rebuild();
                    }
                })
                .show();
    }

    private void applyThemeMode(int m) {
        Ui.setMode(this, m);
        Ui.applyTheme(this);
        // 颜色是建界面那一刻取的，换主题要重建
        recreate();
    }

    private void openUnlockInfo() {
        if (License.isUnlocked(this)) {
            License lic = License.init(this);
            new AlertDialog.Builder(this)
                    .setTitle("已解锁")
                    .setMessage(lic.detailForDialog())
                    .setPositiveButton("知道了", null)
                    .show();
        } else {
            startActivity(new Intent(this, MainActivity.class));
            toast("在首页点「1 元解锁」");
        }
    }

    interface OnNum { void onNum(int v); }

    /** 单列滚轮选一个整数（心率那些），不再弹系统输入框 */
    private void pickInt(String title, String hint, String unit, int from, int to,
                         int cur, final OnNum cb) {
        final String[] items = Pickers.range(from, to, false);
        int idx = Math.max(0, Math.min(items.length - 1, cur - from));
        Pickers.show(this, title + "　" + hint, new String[]{unit},
                new String[][]{items}, new int[]{idx}, new Pickers.OnDone() {
                    public void onDone(int[] i) {
                        cb.onNum(from + i[0]);
                    }
                });
    }

    /** 配速上限用「分 + 秒」两列滚轮，比让人算 270 秒直观 */
    private void pickPace(int curSec, final OnNum cb) {
        final String[] mins = Pickers.range(3, 20, false);
        final String[] secs = Pickers.range(0, 59, true);
        int m = Math.max(0, Math.min(mins.length - 1, curSec / 60 - 3));
        int sec = Math.max(0, Math.min(59, curSec % 60));
        Pickers.show(this, "最快 1 公里", new String[]{"分", "秒"},
                new String[][]{mins, secs}, new int[]{m, sec}, new Pickers.OnDone() {
                    public void onDone(int[] i) {
                        cb.onNum(Integer.parseInt(mins[i[0]]) * 60 + Integer.parseInt(secs[i[1]]));
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户可能刚从系统的无障碍设置里回来，状态要重算一次
        boolean now = KeepAuto.enabled(this);
        if (now != a11yShown) {
            a11yShown = now;
            rebuild();
        }
    }

    /** 上一次画出来的无障碍状态，用来判断需不需要重建 */
    private boolean a11yShown;

    private void rebuild() {
        setContentView(build());
        a11yShown = KeepAuto.enabled(this);
        applySystemBars();
    }

    // ================= 小工具 =================

    private int dp(int v) { return Ui.dp(this, v); }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
