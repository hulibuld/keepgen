package com.keepgen.hulibuld;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 从底部升起的面板，替代系统 AlertDialog。
 *
 * 为什么要自己写：原生弹窗是系统的浅色方框，跟深色界面完全两个风格，
 * 而且输入框、列表、按钮都得套系统样式。这个面板：
 *   · 圆角顶、深色底、跟卡片同一套色
 *   · 内容自由拼（列表 / 输入框 / 按钮组）
 *   · 上下滑动关闭，点遮罩关闭
 *   · 只用一个 View 叠在 Activity 最上层，不新建 Window，所以不卡
 */
final class Sheet {

    private final Activity act;
    private final FrameLayout overlay;
    private final LinearLayout body;
    private final ScrollView scroller;
    private final LinearLayout footer;
    private boolean dismissed;
    /** true 表示不可被点空白/返回键随手关掉（门禁类弹层用） */
    private boolean blocking;

    private Sheet(Activity act) {
        this.act = act;

        overlay = new FrameLayout(act);
        // 遮罩跟着明暗走：浅色模式用淡黑影，深色模式用重黑影
        overlay.setBackgroundColor(Ui.isDark() ? 0xB3000000 : 0x40000000);
        overlay.setClickable(true);
        overlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // 有阻塞性质的弹层（联网门禁、试用结束）不能被点空白关掉。
                // 早期版本点遮罩外空白就 dismiss：
                // 到期态点空白 + 点生成 → 照样落盘一个 690KB 的文件，门禁形同虚设。
                if (blocking) return;
                dismiss();
            }
        });

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(Ui.BG_ALT, 24f, act));
        panel.setElevation(Ui.dp(act, 12));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.BOTTOM;
        panel.setLayoutParams(plp);
        // 吃掉点击，避免穿透到遮罩
        panel.setClickable(true);

        // 顶部把手
        View handle = new View(act);
        handle.setBackground(Ui.round(Ui.LINE, 999f, act));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                Ui.dp(act, 38), Ui.dp(act, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = Ui.dp(act, 10);
        hlp.bottomMargin = Ui.dp(act, 4);
        handle.setLayoutParams(hlp);
        panel.addView(handle);

        scroller = new ScrollView(act);
        scroller.setFillViewport(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scroller.setLayoutParams(slp);

        body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(act, 20);
        body.setPadding(pad, Ui.dp(act, 8), pad, Ui.dp(act, 12));
        scroller.addView(body);
        panel.addView(scroller);

        footer = new LinearLayout(act);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        int fpad = Ui.dp(act, 20);
        footer.setPadding(fpad, Ui.dp(act, 4), fpad, Ui.dp(act, 20));
        panel.addView(footer);

        overlay.addView(panel);
        this.view = overlay;
    }

    private final View view;

    static Sheet make(Activity a) { return new Sheet(a); }

    /** 标题 + 一行说明 */
    Sheet title(String t, String subtitle) {
        TextView tv = new TextView(act);
        tv.setText(t);
        tv.setTextSize(20);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setTextColor(Ui.TEXT);
        body.addView(tv);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView st = new TextView(act);
            st.setText(subtitle);
            st.setTextSize(12.5f);
            st.setTextColor(Ui.TEXT_DIM);
            st.setPadding(0, Ui.dp(act, 6), 0, 0);
            st.setLineSpacing(Ui.dp(act, 3), 1f);
            body.addView(st);
        }
        spacer(Ui.dp(act, 14));
        return this;
    }

    /** 段落小标题 */
    Sheet section(String t) {
        TextView tv = new TextView(act);
        tv.setText(t);
        tv.setTextSize(11.5f);
        tv.setLetterSpacing(0.08f);
        tv.setTextColor(Ui.TEXT_FAINT);
        tv.setPadding(0, Ui.dp(act, 14), 0, Ui.dp(act, 6));
        body.addView(tv);
        return this;
    }

    Sheet text(String t) {
        TextView tv = new TextView(act);
        tv.setText(t);
        tv.setTextSize(13.5f);
        tv.setTextColor(Ui.TEXT_DIM);
        tv.setLineSpacing(Ui.dp(act, 4), 1f);
        body.addView(tv);
        return this;
    }

    Sheet view(View v) { body.addView(v); return this; }

    Sheet spacer(int px) {
        View v = new View(act);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, px));
        body.addView(v);
        return this;
    }

    /** 可点的列表项：标题 + 副标题 + 右侧箭头 */
    Sheet item(final String title, final String subtitle, final Runnable onClick) {
        return item(title, subtitle, onClick, null);
    }

    /** 带长按删除的列表项 */
    Sheet item(final String title, final String subtitle, final Runnable onClick,
               final Runnable onLongPress) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.roundStroke(Ui.CARD, Ui.LINE, Ui.RADIUS_CTRL, 1, act));
        int p = Ui.dp(act, 14);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(act, 8);
        row.setLayoutParams(lp);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t1 = new TextView(act);
        t1.setText(title);
        t1.setTextSize(15);
        t1.setTextColor(Ui.TEXT);
        col.addView(t1);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView t2 = new TextView(act);
            t2.setText(subtitle);
            t2.setTextSize(12);
            t2.setTextColor(Ui.TEXT_DIM);
            t2.setPadding(0, Ui.dp(act, 4), 0, 0);
            col.addView(t2);
        }
        row.addView(col);

        TextView arrow = new TextView(act);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(Ui.TEXT_FAINT);
        arrow.setPadding(Ui.dp(act, 8), 0, 0, 0);
        row.addView(arrow);

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (onClick != null) onClick.run();
            }
        });
        if (onLongPress != null) {
            row.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    onLongPress.run();
                    return true;
                }
            });
        }
        body.addView(row);
        return this;
    }

    /** 主按钮 */
    Sheet primary(String t, final Runnable r) {
        Button b = new Button(act);
        b.setText(t);
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Ui.stylePrimary(act, b);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (r != null) r.run(); }
        });
        footer.addView(b);
        return this;
    }

    /** 次按钮 */
    Sheet ghost(String t, final Runnable r) {
        Button b = new Button(act);
        b.setText(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(act, 8), 0, 0, 0);
        b.setLayoutParams(lp);
        Ui.styleGhost(act, b);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (r != null) r.run(); }
        });
        footer.addView(b);
        return this;
    }

    /** 危险动作按钮（红字） */
    Sheet danger(String t, final Runnable r) {
        Button b = new Button(act);
        b.setText(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(act, 8), 0, 0, 0);
        b.setLayoutParams(lp);
        b.setBackground(Ui.roundStroke(
                android.graphics.Color.argb(24, 255, 69, 58),
                android.graphics.Color.argb(102, 255, 69, 58), Ui.RADIUS_CTRL, 1, act));
        b.setTextColor(Ui.DANGER);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (r != null) r.run(); }
        });
        footer.addView(b);
        return this;
    }

    /** 只有在没有 footer 按钮时才需要一个关闭 */
    Sheet closeOnly() {
        ghost("关闭", new Runnable() { public void run() { dismiss(); } });
        return this;
    }

    Sheet show() {
        ViewGroup root = (ViewGroup) act.getWindow().getDecorView();
        if (overlay.getParent() != null) return this;
        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 入场动画：面板从下往上滑，遮罩淡入
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(160).start();
        View panel = overlay.getChildAt(0);
        panel.setTranslationY(Ui.dp(act, 60));
        panel.animate().translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();
        return this;
    }

    /** 标记为阻塞型：点空白、按返回键都关不掉，只能点弹层里的按钮 */
    Sheet blockDismiss() {
        blocking = true;
        return this;
    }

    boolean isBlocking() { return blocking; }

    void dismiss() {
        if (dismissed) return;
        dismissed = true;
        View panel = overlay.getChildAt(0);
        panel.animate().translationY(Ui.dp(act, 80)).setDuration(160).start();
        overlay.animate().alpha(0f).setDuration(160).withEndAction(new Runnable() {
            public void run() {
                ViewGroup root = (ViewGroup) act.getWindow().getDecorView();
                root.removeView(overlay);
            }
        }).start();
    }

    boolean isOpen() { return !dismissed && overlay.getParent() != null; }

    /** 当前是否有面板开着（给 Activity 拦返回键用） */
    static boolean anyOpen = false;

    static void syncFlag() { anyOpen = true; }
}
