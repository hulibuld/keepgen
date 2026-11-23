package com.qianyuzhou.keepgen;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 视觉规范 v2：ColorOS 圆润卡片 + iOS 时钟大号数字/滚轮。 */
final class Ui {

    static final int MODE_AUTO = 0, MODE_LIGHT = 1, MODE_DARK = 2;
    private static final String PREF = "ui_prefs";
    private static final String KEY_MODE = "theme_mode";
    private static boolean dark = false;

    static boolean isDark() { return dark; }
    static int mode(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_MODE, MODE_AUTO); }
    static void setMode(Context c, int m) { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, m).apply(); }
    static boolean systemDark(Context c) {
        int n = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return n == Configuration.UI_MODE_NIGHT_YES;
    }
    static void applyTheme(Context c) {
        int m = mode(c);
        setDark(m == MODE_LIGHT ? false : m == MODE_DARK ? true : systemDark(c));
    }

    static void setDark(boolean d) {
        dark = d;
        if (d) {
            BG=0xFF0B0D10; BG_ALT=0xFF171A1F; CARD=0xFF1B1F26; CARD_HI=0xFF232832;
            SUNK=0xFF2A303B; LINE=0xFF303744; INPUT_STROKE=0xFF49525F;
            TEXT=0xFFF2F5F9; TEXT_DIM=0xFFAEB8C6; TEXT_FAINT=0xFF7C8797;
            ACCENT=0xFF5B9BFF; ACCENT_SOFT=0xFF132C4C; ACCENT_INK=0xFF9CC6FF; ON_ACCENT=0xFF041024;
            DANGER=0xFFFF6B6B; DANGER_BG=0xFF3A1A1E; WARN=0xFFFFB648; WARN_BG=0xFF3A2A12;
            OK=0xFF42D392; OK_BG=0xFF123028;
        } else {
            BG=0xFFF3F5F9; BG_ALT=0xFFFFFFFF; CARD=0xFFFFFFFF; CARD_HI=0xFFFFFFFF;
            SUNK=0xFFEBEFF5; LINE=0xFFE1E7EF; INPUT_STROKE=0xFFC7D0DC;
            TEXT=0xFF18202B; TEXT_DIM=0xFF5B6675; TEXT_FAINT=0xFF7A8593;
            ACCENT=0xFF1D5FD6; ACCENT_SOFT=0xFFDCEBFF; ACCENT_INK=0xFF0A3A80; ON_ACCENT=0xFFFFFFFF;
            DANGER=0xFFC62828; DANGER_BG=0xFFFFE9E7; WARN=0xFF9A5B00; WARN_BG=0xFFFFF4DE;
            OK=0xFF1E7B4B; OK_BG=0xFFE4F7EC;
        }
    }

    static int BG,BG_ALT,CARD,CARD_HI,SUNK,LINE,INPUT_STROKE;
    static int TEXT,TEXT_DIM,TEXT_FAINT;
    static int ACCENT,ACCENT_SOFT,ACCENT_INK,ON_ACCENT;
    static int DANGER,WARN,OK,DANGER_BG,WARN_BG,OK_BG;
    static int barColor() { return BG; }
    static { setDark(false); }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static final float RADIUS_CARD=20f, RADIUS_CTRL=13f, RADIUS_PILL=999f;
    static final int H_PRIMARY=62, H_SECOND=54, H_TERTIARY=44, H_TINY=36;

    static GradientDrawable round(int color, float radius, Context c) {
        GradientDrawable g=new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE); g.setColor(color);
        g.setCornerRadius(dp(c,radius)); return g;
    }
    static GradientDrawable roundStroke(int fill,int stroke,float radius,int strokeW,Context c) {
        GradientDrawable g=round(fill,radius,c); g.setStroke(Math.max(1,dp(c,strokeW)),stroke); return g;
    }
    static GradientDrawable card(Context c) { return roundStroke(CARD,LINE,RADIUS_CARD,1,c); }
    static GradientDrawable section(Context c) { return round(CARD,RADIUS_CARD,c); }
    static View hairline(Context c) {
        View v=new View(c); v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,Math.max(1,dp(c,0.8f)));
        lp.topMargin=dp(c,14); v.setLayoutParams(lp); return v;
    }

    /** 分组卡片：圆角白底 + 淡描边 */
    static LinearLayout cardBox(Context c, LinearLayout parent) {
        LinearLayout box=new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(card(c));
        box.setPadding(dp(c,18),dp(c,16),dp(c,18),dp(c,16));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin=dp(c,8); box.setLayoutParams(lp); box.setElevation(0);
        if(parent!=null) parent.addView(box);
        return box;
    }
    static LinearLayout plainBox(Context c, LinearLayout parent) {
        LinearLayout box=new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin=dp(c,14); box.setLayoutParams(lp);
        if(parent!=null) parent.addView(box);
        return box;
    }
    static LinearLayout buttonRow(Context c, LinearLayout parent, float topMarginDp) {
        LinearLayout row=new LinearLayout(c); row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin=dp(c,topMarginDp); row.setLayoutParams(lp);
        if(parent!=null) parent.addView(row);
        return row;
    }
    static LinearLayout.LayoutParams weight(Context c,float w,float marginDp) {
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT,w);
        int m=dp(c,marginDp+3); lp.setMargins(m,0,m,0); return lp;
    }

    private static RippleDrawable ripple(GradientDrawable content) {
        int pressed=dark?Color.argb(46,255,255,255):Color.argb(34,0,0,0);
        return new RippleDrawable(ColorStateList.valueOf(pressed),content,null);
    }

    static void stylePrimary(Context c, Button b) {
        b.setBackground(ripple(round(ACCENT,RADIUS_CTRL,c)));
        b.setTextColor(ON_ACCENT); b.setAllCaps(false); b.setTextSize(17);
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setMinHeight(dp(c,H_PRIMARY)); b.setMinimumHeight(dp(c,H_PRIMARY));
        b.setPadding(dp(c,12),0,dp(c,12),0);
        b.setStateListAnimator(null); b.setElevation(0);
    }
    static void styleSegChoice(Context c, Button b) {
        b.setBackground(ripple(round(SUNK,RADIUS_CTRL,c)));
        b.setTextColor(TEXT_DIM); b.setAllCaps(false); b.setTextSize(15);
        b.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        b.setMinHeight(dp(c,H_SECOND)); b.setMinimumHeight(dp(c,H_SECOND));
        b.setPadding(dp(c,8),0,dp(c,8),0);
        b.setStateListAnimator(null); b.setElevation(0);
    }
    static void setSegOn(Context c, Button b, boolean on) {
        if(on){
            b.setBackground(ripple(round(dark?CARD_HI:CARD,RADIUS_CTRL,c)));
            b.setTextColor(ACCENT);
            b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        }else{
            b.setBackground(ripple(round(SUNK,RADIUS_CTRL,c)));
            b.setTextColor(TEXT_DIM);
            b.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        }
        b.setStateListAnimator(null); b.setElevation(0);
    }
    static void styleChip(Context c, Button b) {
        b.setBackground(ripple(round(SUNK,RADIUS_CTRL,c)));
        b.setTextColor(TEXT_DIM); b.setAllCaps(false); b.setTextSize(13.5f);
        b.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        b.setMinHeight(dp(c,H_TERTIARY)); b.setMinimumHeight(dp(c,H_TERTIARY));
        b.setPadding(dp(c,8),0,dp(c,8),0);
        b.setStateListAnimator(null); b.setElevation(0);
    }
    static void styleAccentChip(Context c, Button b) {
        b.setBackground(ripple(round(ACCENT_SOFT,RADIUS_CTRL,c)));
        b.setTextColor(ACCENT_INK); b.setAllCaps(false); b.setTextSize(13.5f);
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setMinHeight(dp(c,H_TERTIARY)); b.setMinimumHeight(dp(c,H_TERTIARY));
        b.setPadding(dp(c,8),0,dp(c,8),0);
        b.setStateListAnimator(null); b.setElevation(0);
    }
    static void styleQuiet(Context c, Button b) {
        b.setBackground(null); b.setTextColor(ACCENT); b.setAllCaps(false); b.setTextSize(13);
        b.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        b.setMinHeight(dp(c,H_TINY)); b.setMinimumHeight(dp(c,H_TINY));
        b.setPadding(0,0,0,0); b.setStateListAnimator(null); b.setElevation(0);
    }
    static void styleGhost(Context c, Button b) {
        b.setBackground(ripple(roundStroke(CARD_HI,LINE,RADIUS_CTRL,1,c)));
        b.setTextColor(TEXT); b.setAllCaps(false); b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setMinHeight(dp(c,48)); b.setStateListAnimator(null); b.setElevation(0);
    }
    static void styleAccentGhost(Context c, Button b) {
        b.setBackground(ripple(roundStroke(ACCENT_SOFT,ACCENT,RADIUS_CTRL,1,c)));
        b.setTextColor(ACCENT_INK); b.setAllCaps(false); b.setTextSize(13);
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setMinHeight(dp(c,44)); b.setStateListAnimator(null);
    }

    static TextView title(Context c,String s) {
        TextView t=new TextView(c); t.setText(s); t.setTextSize(34);
        t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        t.setTextColor(TEXT); t.setLetterSpacing(-0.025f); return t;
    }
    static TextView subtitle(Context c,String s) {
        TextView t=new TextView(c); t.setText(s); t.setTextSize(14);
        t.setTextColor(TEXT_DIM); t.setLetterSpacing(-0.005f); return t;
    }
    static TextView label(Context c,String s) {
        TextView t=new TextView(c); t.setText(s); t.setTextSize(13); t.setTextColor(TEXT_DIM);
        t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        // 留白放在「上面」：标题要贴着自己那张卡的顶，而不是挂在上一张卡的屁股上。
        // 原来只有 bottom 7dp、top 0，紧跟在上一张卡（bottom padding 16dp）后面，
        // 视觉上就成了「上一个功能的附录」。
        t.setPadding(dp(c,4),0,0,0);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin=dp(c,18);
        lp.bottomMargin=dp(c,8);
        t.setLayoutParams(lp);
        return t;
    }
    static TextView hint(Context c,String s) {
        TextView t=new TextView(c); t.setText(s); t.setTextSize(12); t.setTextColor(TEXT_FAINT);
        t.setLineSpacing(dp(c,3),1f); return t;
    }
    static TextView mono(Context c,String s) {
        TextView t=new TextView(c); t.setText(s); t.setTextSize(13);
        t.setTypeface(Typeface.MONOSPACE); t.setTextColor(TEXT); return t;
    }
    /** 大号数字 TextView：iOS 时钟风格，轻字重 + 负字距 */
    static TextView bigNumber(Context c,String s) {
        TextView t=new TextView(c); t.setText(s); t.setTextSize(72);
        t.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
        t.setTextColor(TEXT); t.setLetterSpacing(-0.045f);
        t.setIncludeFontPadding(false); return t;
    }
    static TextView numberUnit(Context c,String s) {
        TextView t=new TextView(c); t.setText(s); t.setTextSize(17);
        t.setTextColor(TEXT_DIM); t.setPadding(dp(c,8),0,0,dp(c,10)); return t;
    }
    static TextView link(Context c,String s,View.OnClickListener onClick) {
        TextView t=new TextView(c); t.setText(s); t.setTextSize(12.5f);
        t.setTextColor(ACCENT); t.setPadding(0,dp(c,6),0,dp(c,6));
        t.setClickable(true); t.setOnClickListener(onClick);
        t.setPaintFlags(t.getPaintFlags()|android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        return t;
    }
    static LinearLayout linkRow(Context c,LinearLayout parent) {
        LinearLayout row=new LinearLayout(c); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin=dp(c,18); row.setLayoutParams(lp);
        if(parent!=null) parent.addView(row);
        return row;
    }
    static TextView dot(Context c) {
        TextView t=new TextView(c); t.setText("   ·   "); t.setTextSize(12);
        t.setTextColor(TEXT_FAINT); return t;
    }
    static TextView footer(Context c,String s,View.OnClickListener onClick) {
        TextView t=new TextView(c); t.setText(s); t.setTextSize(11.5f);
        t.setTextColor(TEXT_FAINT); t.setGravity(Gravity.CENTER);
        t.setLineSpacing(dp(c,4),1f); t.setPadding(0,dp(c,22),0,dp(c,8));
        if(onClick!=null){t.setOnClickListener(onClick);t.setClickable(true);}
        return t;
    }
    static LinearLayout statRow(Context c,String key,String value) {
        LinearLayout row=new LinearLayout(c); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0,dp(c,4),0,dp(c,4));
        TextView k=new TextView(c); k.setText(key); k.setTextSize(13); k.setTextColor(TEXT_DIM);
        k.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT,1f)); row.addView(k);
        TextView v=new TextView(c); v.setText(value); v.setTextSize(13);
        v.setTypeface(Typeface.MONOSPACE); v.setTextColor(TEXT); row.addView(v);
        return row;
    }
    static View divider(Context c) {
        View v=new View(c); v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,Math.max(1,dp(c,0.7f)));
        lp.topMargin=dp(c,8); lp.bottomMargin=dp(c,8); v.setLayoutParams(lp); return v;
    }
    static TextView badge(Context c,String s,int color,int bgColor) {
        TextView t=new TextView(c); t.setText(s); t.setTextSize(12);
        t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        t.setTextColor(color); t.setBackground(round(bgColor,RADIUS_PILL,c));
        t.setPadding(dp(c,10),dp(c,4),dp(c,10),dp(c,4)); t.setGravity(Gravity.CENTER);
        return t;
    }
    static TextView badge(Context c,String s,int color) {
        int bg=color==OK?OK_BG:color==WARN?WARN_BG:color==DANGER?DANGER_BG:ACCENT_SOFT;
        return badge(c,s,color,bg);
    }

    // ---- iOS 原生风格组件（主页专用；设置页不受影响）----

    static int iosBg()        { return dark ? 0xFF000000 : 0xFFF2F2F7; }
    static int iosCard()      { return dark ? 0xFF1C1C1E : 0xFFFFFFFF; }
    static int iosBlue()      { return dark ? 0xFF0A84FF : 0xFF007AFF; }
    static int iosLabel()     { return dark ? 0xFFFFFFFF : 0xFF000000; }
    static int iosSecondary() { return 0xFF8E8E93; }
    static int iosTertiary()  { return dark ? 0xFF48484A : 0xFFC7C7CC; }
    static int iosSeparator() { return dark ? 0xFF38383A : 0xFFE5E5EA; }
    static int iosFill()      { return dark ? 0xFF1C1C1E : 0xFFE9E9EB; }
    static int iosGreen()     { return dark ? 0xFF30D158 : 0xFF34C759; }

    static final float IOS_R_CARD = 12f, IOS_R_CTRL = 9f;

    static GradientDrawable iosCardBg(Context c) { return round(iosCard(), IOS_R_CARD, c); }

    static LinearLayout iosGroup(Context c, LinearLayout parent) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(iosCardBg(c));
        box.setPadding(dp(c, 14), dp(c, 4), dp(c, 14), dp(c, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 20);
        box.setLayoutParams(lp);
        if (parent != null) parent.addView(box);
        return box;
    }

    static TextView iosSection(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(iosSecondary());
        t.setPadding(dp(c, 14), dp(c, 2), dp(c, 14), dp(c, 8));
        return t;
    }

    static EditText iosNumber(Context c, String val) {
        EditText e = new EditText(c);
        e.setText(val);
        e.setTextSize(76);
        e.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        e.setTextColor(iosLabel());
        e.setBackgroundColor(Color.TRANSPARENT);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setSingleLine(true);
        e.setPadding(0, 0, 0, 0);
        e.setIncludeFontPadding(false);
        return e;
    }

    static void iosSegOn(Context c, Button b, boolean on) {
        if (on) {
            b.setBackground(ripple(roundStroke(iosCard(), 0x14000000, IOS_R_CTRL, 1, c)));
            b.setTextColor(iosLabel());
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        } else {
            b.setBackground(ripple(round(Color.TRANSPARENT, IOS_R_CTRL, c)));
            b.setTextColor(iosSecondary());
            b.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        b.setStateListAnimator(null);
        b.setElevation(0);
    }

    /** 按资源名加载成品图标（Tabler Icons / MIT），运行时解析 id，避免依赖 R 类 */
    static ImageView icon(Context c, String name, int color, float sizeDp) {
        ImageView iv = new ImageView(c);
        int id = c.getResources().getIdentifier(name, "drawable", c.getPackageName());
        if (id != 0) iv.setImageResource(id);
        iv.setColorFilter(color);
        int s = dp(c, sizeDp);
        iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    /** 品牌图标：原样显示，不做 colorFilter（Keep 的 logo 上色就毁了） */
    static ImageView brandIcon(Context c, String name, float sizeDp) {
        ImageView iv = new ImageView(c);
        int id = c.getResources().getIdentifier(name, "drawable", c.getPackageName());
        if (id != 0) iv.setImageResource(id);
        int s = dp(c, sizeDp);
        iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    /** 品牌图标放进圆角小方块里（白底，让绿色 logo 自己说话） */
    static LinearLayout brandBadge(Context c, String name, int bg, float boxDp, float iconDp) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(round(bg, 8f, c));
        int s = dp(c, boxDp);
        box.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        box.addView(brandIcon(c, name, iconDp));
        return box;
    }

    /** 图标放在彩色圆角底上的小方块（iOS 设置图标那种） */
    static LinearLayout iconBadge(Context c, String name, int tint, int bg, float boxDp, float iconDp) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(round(bg, 8f, c));
        int s = dp(c, boxDp);
        box.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        box.addView(icon(c, name, tint, iconDp));
        return box;
    }

    /** iOS 系统色：给图标方块用的浅色调 */
    static int iosTintBlue()   { return dark ? 0x1A3A5C : 0xE8F2FF; }
    static int iosTintGreen()  { return dark ? 0x143A22 : 0xE3F8E8; }
    static int iosTintRed()    { return dark ? 0x3A1A1A : 0xFFE8E8; }
    static int iosTintOrange() { return dark ? 0x3A2A14 : 0xFFF2DC; }
    static int iosTintPurple() { return dark ? 0x2A1A3A : 0xF0E8FF; }

    /** iOS 系统蓝文字按钮 */
    static TextView iosTextButton(Context c, String s, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(17);
        t.setTextColor(iosBlue());
        t.setPadding(dp(c, 8), dp(c, 4), dp(c, 2), dp(c, 4));
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    static View iosSeparator(Context c, float topMarginDp) {
        View v = new View(c);
        v.setBackgroundColor(iosSeparator());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f)));
        lp.topMargin = dp(c, topMarginDp);
        v.setLayoutParams(lp);
        return v;
    }
}
