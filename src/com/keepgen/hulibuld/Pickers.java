package com.keepgen.hulibuld;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 滚轮选择弹窗：iOS 风格的底部面板，顶部「取消 / 标题 / 完成」工具栏。
 *
 * 为什么做成弹窗而不是直接铺在页面上：
 *   主页上已经有距离、路线、格式等一堆控件，再摆三个滚轮会把「生成」按钮顶到屏幕外。
 *   点一下从底部弹出、选完就收，和 iOS 设置里选日期/时间的体验一致。
 */
final class Pickers {

    interface OnDone { void onDone(int[] index); }

    private Pickers() {}

    static void show(Activity act, String title, String[] labels,
                     String[][] items, int[] initial, final OnDone done) {
        Context c = act;

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(sheetBackground(c));
        // 底部固定留白，覆盖手势区域；dialog 宽度撑满，视觉上贴住屏幕底边。
        root.setPadding(0, 0, 0, Ui.dp(c, 24));

        // iOS sheet 顶部的小横条
        View grabber = new View(c);
        grabber.setBackground(Ui.round(Ui.isDark() ? Ui.LINE : 0xFFD6DDE7, 3f, c));
        LinearLayout.LayoutParams grabLp = new LinearLayout.LayoutParams(
                Ui.dp(c, 38), Math.max(2, Ui.dp(c, 4)));
        grabLp.gravity = Gravity.CENTER_HORIZONTAL;
        grabLp.topMargin = Ui.dp(c, 8);
        grabber.setLayoutParams(grabLp);
        root.addView(grabber);

        final Dialog d = new Dialog(act, android.R.style.Theme_Translucent_NoTitleBar);

        // ---- 顶部工具栏 ----
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 10));
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 52)));

        TextView cancel = toolButton(c, "取消", false);
        cancel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { d.dismiss(); }
        });
        bar.addView(cancel);

        TextView name = new TextView(c);
        name.setText(title != null ? title : "");
        name.setTextSize(15);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setTextColor(Ui.TEXT);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        bar.addView(name);

        final Wheel[] wheels = new Wheel[items.length];
        TextView doneBtn = toolButton(c, "完成", true);
        doneBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        doneBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int[] out = new int[wheels.length];
                for (int i = 0; i < wheels.length; i++) out[i] = wheels[i].selected();
                d.dismiss();
                if (done != null) done.onDone(out);
            }
        });
        bar.addView(doneBtn);
        root.addView(bar);

        View sep = new View(c);
        sep.setBackgroundColor(Ui.LINE);
        sep.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(c, 0.7f))));
        root.addView(sep);

        // ---- 滚轮区 ----
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 2));

        for (int i = 0; i < items.length; i++) {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Wheel w = new Wheel(c, 40, 5);
            int start = Math.max(0, Math.min(items[i].length - 1,
                    initial != null && i < initial.length ? initial[i] : 0));
            w.setItems(items[i], start);
            col.addView(w, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            wheels[i] = w;

            if (labels != null && i < labels.length && labels[i] != null) {
                TextView l = new TextView(c);
                l.setText(labels[i]);
                l.setTextSize(11.5f);
                l.setTextColor(Ui.TEXT_FAINT);
                l.setGravity(Gravity.CENTER);
                col.addView(l);
            }
            row.addView(col);
        }
        root.addView(row);

        d.setContentView(root);
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);   // 点遮罩：取消，不回调 done

        Window win = d.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setGravity(Gravity.BOTTOM);
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            win.setDimAmount(0.55f);
        }
        d.show();

        // 没有 R.anim 资源也能从底部滑入：view 自己平移 + 淡入。
        root.setAlpha(0f);
        root.setTranslationY(Ui.dp(c, 240));
        root.post(new Runnable() {
            public void run() {
                root.animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(220L)
                        .setInterpolator(new DecelerateInterpolator());
            }
        });
    }

    private static TextView toolButton(Context c, String text, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(16);
        t.setTextColor(Ui.ACCENT);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setClickable(true);
        t.setPadding(Ui.dp(c, 2), 0, Ui.dp(c, 2), 0);
        t.setTypeface(Typeface.create("sans-serif" + (bold ? "-medium" : ""),
                bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    /** 只有上方两个角圆角，底部贴住屏幕边缘。 */
    private static GradientDrawable sheetBackground(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(Ui.isDark() ? Ui.BG_ALT : Color.WHITE);
        float r = Ui.dp(c, Ui.RADIUS_CARD);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return g;
    }

    /** 生成 0..n 的字符串数组，补零由 caller 决定 */
    static String[] range(int from, int to, boolean pad2) {
        String[] out = new String[to - from + 1];
        for (int i = from; i <= to; i++) {
            out[i - from] = pad2 ? String.format(java.util.Locale.US, "%02d", i) : String.valueOf(i);
        }
        return out;
    }

    /** 在数组里找一个值的位置，找不到就给 0 */
    static int at(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }
}
