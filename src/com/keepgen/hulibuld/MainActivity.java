package com.keepgen.hulibuld;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 单页界面：v29 视觉重构 —— ColorOS 圆润卡片 + iOS 时钟大号数字。 */
public class MainActivity extends Activity {

    private static final int PAGE_OUTDOOR = 0;
    private static final int PAGE_TREAD = 1;
    private int page = PAGE_OUTDOOR;
    private int sport = PAGE_OUTDOOR;

    private LinearLayout pageSwitcher;
    private LinearLayout outdoorBody, treadBody;
    private EditText etDist, etPaceMin, etPaceSec, etDurH, etDurM, etDate, etTime;
    private EditText etTreadPaceMin, etTreadPaceSec, etTreadDist;
    private EditText treadDate, treadTime;
    private TextView tvRouteInfo, tvResult, coordInfo;
    private TrackView trackView;
    private Button goButton;
    private TextView keepBar;

    private boolean usePace = true;
    private boolean withHr = true;
    private android.widget.Switch hrSwitch;
    private TextView hrHint;
    private final java.util.List<android.widget.Switch> hrSwitches = new java.util.ArrayList<>();
    private final java.util.List<TextView> hrHints = new java.util.ArrayList<>();
    private final java.util.List<Button> formatBtns = new java.util.ArrayList<>();
    private HrProfile hrProfile;
    private String fmt = "tcx";
    private String routeKind = "track";
    private List<double[]> routePoints = null;
    private boolean routeLoop = true;
    private Field field = Field.anning();
    private int trackLen = 400;
    private License license;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.applyTheme(this);
        license = License.init(this);
        routeKind = "track"; field = Field.anning(); trackLen = 400;
        try { new RouteStore(this).seedOnce(); } catch (Exception ignored) {}
        hrProfile = HrProfile.load(this);
        setContentView(buildUi());
        applySystemBars();
        restoreFormState(b);
        KeepApi.initUa(this);
        syncKeepBar();
        startAfterTerms();
        verifyActivationOnce();
        checkUpdateInBackground();
    }

    /**
     * 启动时的第一道门：使用须知与免责声明。
     *
     * 没同意过就先弹这一页，点什么都不会进主界面；同意之后才走后面的新手引导。
     * 同意过的人这里什么都不发生（读一次 prefs 而已）。
     */
    private void startAfterTerms() {
        if (DisclaimerPage.gate(this, new Runnable() {
            public void run() {
                maybeFirstRunGuide();
            }
        })) {
            // 已经同意过：不用弹，直接接着走新手引导
            maybeFirstRunGuide();
        }
    }

    private void applySystemBars() {
        try {
            android.view.Window w = getWindow();
            w.setStatusBarColor(Ui.barColor());
            w.setNavigationBarColor(Ui.barColor());
            int flags = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (!Ui.isDark()) w.getDecorView().setSystemUiVisibility(flags);
            else w.getDecorView().setSystemUiVisibility(0);
        } catch (Exception ignored) {}
    }

    // ================= 界面 =================

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.iosBg());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.iosBg());
        root.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(root);

        // ---- iOS 风格大标题（Large Title） + 右上角系统蓝按钮 ----
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView h1 = new TextView(this);
        h1.setText("运动数据生成");
        h1.setTextSize(34);
        h1.setTypeface(Typeface.create("sans-serif-bold", Typeface.NORMAL));
        h1.setTextColor(Ui.iosLabel());
        h1.setLetterSpacing(-0.02f);
        h1.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        topBar.addView(h1);
        topBar.addView(Ui.iosTextButton(this, "设置", v ->
                startActivity(new Intent(MainActivity.this, SettingsActivity.class))));
        root.addView(topBar);

        TextView lead = Ui.subtitle(this, "生成 FIT / TCX，导入 Keep");
        lead.setTextColor(Ui.iosSecondary());
        lead.setPadding(0, dp(2), 0, 0);
        root.addView(lead);

        // ---- Keep 登录状态（iOS 通知横幅那种卡片） ----
        keepBar = new TextView(this);
        keepBar.setTextSize(14);
        keepBar.setPadding(dp(14), dp(12), dp(14), dp(12));
        keepBar.setBackground(Ui.round(Ui.iosCard(), Ui.IOS_R_CARD, this));
        LinearLayout.LayoutParams kbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kbLp.topMargin = dp(18);
        keepBar.setLayoutParams(kbLp);
        keepBar.setOnClickListener(v -> openKeepLogin());
        root.addView(keepBar);

        // 解锁状态：iOS 风格横幅卡片
        LinearLayout unlockBanner = new LinearLayout(this);
        unlockBanner.setOrientation(LinearLayout.HORIZONTAL);
        unlockBanner.setGravity(Gravity.CENTER_VERTICAL);
        unlockBanner.setBackground(Ui.round(Ui.iosCard(), Ui.IOS_R_CARD, this));
        LinearLayout.LayoutParams ubLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ubLp.topMargin = dp(12);
        unlockBanner.setLayoutParams(ubLp);
        unlockBanner.setPadding(dp(13), dp(12), dp(13), dp(12));

        if (license.unlocked) {
            unlockBanner.addView(Ui.iconBadge(this, "ic_trophy", Ui.iosGreen(), Ui.iosTintGreen(), 36, 20));
            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView t1 = new TextView(this);
            t1.setText("已解锁 · 永久可用");
            t1.setTextSize(15);
            t1.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t1.setTextColor(Ui.iosLabel());
            t1.setPadding(dp(12), 0, 0, 0);
            txt.addView(t1);
            TextView t2 = new TextView(this);
            t2.setText("感谢支持开发");
            t2.setTextSize(12);
            t2.setTextColor(Ui.iosSecondary());
            t2.setPadding(dp(12), dp(2), 0, 0);
            txt.addView(t2);
            unlockBanner.addView(txt);
        } else {
            unlockBanner.addView(Ui.iconBadge(this, "ic_lock", Ui.iosBlue(), Ui.iosTintBlue(), 36, 20));
            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView t1 = new TextView(this);
            t1.setText("解锁全部功能");
            t1.setTextSize(15);
            t1.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t1.setTextColor(Ui.iosLabel());
            t1.setPadding(dp(12), 0, 0, 0);
            txt.addView(t1);
            TextView t2 = new TextView(this);
            t2.setText("1 元永久解锁 · 支持下作者");
            t2.setTextSize(12);
            t2.setTextColor(Ui.iosSecondary());
            t2.setPadding(dp(12), dp(2), 0, 0);
            txt.addView(t2);
            unlockBanner.addView(txt);
            TextView unlockBtn = Ui.iosTextButton(this, "解锁", v -> openUnlock());
            unlockBtn.setTextSize(15);
            unlockBanner.addView(unlockBtn);
        }
        root.addView(unlockBanner);

        // ---- iOS 分段控件（户外 / 室内） ----
        pageSwitcher = new LinearLayout(this);
        pageSwitcher.setOrientation(LinearLayout.HORIZONTAL);
        pageSwitcher.setBackground(Ui.round(Ui.iosFill(), Ui.IOS_R_CTRL, this));
        pageSwitcher.setPadding(dp(2), dp(2), dp(2), dp(2));
        LinearLayout.LayoutParams pslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        pslp.topMargin = dp(18);
        pageSwitcher.setLayoutParams(pslp);
        root.addView(pageSwitcher);

        final Button[] pageBtns = new Button[2];
        String[] pageNames = {"户外跑步", "室内跑步"};
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(pageNames[i]);
            b.setAllCaps(false);
            b.setTextSize(14);
            b.setMinHeight(0); b.setMinimumHeight(0);
            b.setPadding(0, 0, 0, 0);
            b.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            Ui.iosSegOn(this, b, idx == 0);
            b.setOnClickListener(v -> {
                page = idx; sport = idx;
                for (int j = 0; j < pageBtns.length; j++) Ui.iosSegOn(MainActivity.this, pageBtns[j], j == idx);
                outdoorBody.setVisibility(idx == PAGE_OUTDOOR ? View.VISIBLE : View.GONE);
                treadBody.setVisibility(idx == PAGE_TREAD ? View.VISIBLE : View.GONE);
                syncGoButton();
            });
            pageBtns[i] = b;
            pageSwitcher.addView(b);
        }

        // ======== 户外跑步 ========
        outdoorBody = new LinearLayout(this);
        outdoorBody.setOrientation(LinearLayout.VERTICAL);
        root.addView(outdoorBody);
        buildRouteCard(outdoorBody);
        buildDistCard(outdoorBody);
        buildPaceCard(outdoorBody, false);
        buildTimeCard(outdoorBody);
        buildFormatCard(outdoorBody);

        // ======== 室内跑步 ========
        treadBody = new LinearLayout(this);
        treadBody.setOrientation(LinearLayout.VERTICAL);
        treadBody.setVisibility(View.GONE);
        root.addView(treadBody);
        buildTreadCard(treadBody);
        buildTimeCard(treadBody);
        buildFormatCard(treadBody);

        // ---- iOS 大号主按钮 ----
        goButton = new Button(this);
        goButton.setText("生成运动数据");
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        glp.topMargin = dp(24);
        goButton.setLayoutParams(glp);
        goButton.setBackground(Ui.round(Ui.iosBlue(), Ui.IOS_R_CTRL, this));
        goButton.setTextColor(Color.WHITE);
        goButton.setAllCaps(false);
        goButton.setTextSize(17);
        goButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        goButton.setStateListAnimator(null);
        goButton.setOnClickListener(v -> doGenerate());
        root.addView(goButton);

        // ---- 预览 ----
        trackView = new TrackView(this);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(260));
        tlp.topMargin = dp(18);
        trackView.setLayoutParams(tlp);
        trackView.setVisibility(View.GONE);
        root.addView(trackView);

        tvResult = new TextView(this);
        tvResult.setTextSize(12.5f);
        tvResult.setTypeface(Typeface.MONOSPACE);
        tvResult.setTextColor(Ui.iosLabel());
        tvResult.setLineSpacing(dp(4), 1f);
        tvResult.setPadding(dp(4), dp(14), dp(4), 0);
        tvResult.setVisibility(View.GONE);
        root.addView(tvResult);

        TextView foot = Ui.hint(this, "生成的文件自动保存到手机 Download 文件夹");
        foot.setTextColor(Ui.iosSecondary());
        foot.setPadding(dp(2), dp(20), dp(2), 0);
        root.addView(foot);

        LinearLayout legalRow = Ui.linkRow(this, root);
        legalRow.addView(Ui.link(this, "使用须知", v -> openNotice()));
        legalRow.addView(Ui.dot(this));
        legalRow.addView(Ui.link(this, "免责声明", v -> openDisclaimer()));

        TextView author = Ui.footer(this, "作者：金戈寄渔舟  ·  github.com/hulibuld", v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/hulibuld"))); }
            catch (Exception ex) { toast("打不开浏览器"); }
        });
        root.addView(author);

        syncHrSwitches();
        Ui.iosSegOn(this, pageBtns[PAGE_OUTDOOR], true);
        Ui.iosSegOn(this, pageBtns[PAGE_TREAD], false);
        syncGoButton();
        return scroll;
    }


    /** 使用须知：先说清这个软件做什么、不做什么 */
    private void openNotice() {
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("使用须知", "用之前请看完这几点");

        s.text("一、这个软件做什么");
        s.text("它按你填的距离和配速，生成一份标准格式的运动数据文件（FIT 或 TCX），"
                + "保存到手机 Download。你可以自己在 Keep 里导入。"
                + "轨迹、步频、心率、卡路里都是按参数算出来的，不是真实测量。");
        s.spacer(dp(10));

        s.text("二、你需要自己做的事");
        s.text("· 填对距离和配速。填错了生成的文件也是错的。\n"
                + "· 自己在 Keep 里走导入流程（设置页有说明，或点「自动导入 Keep」）。\n"
                + "· 如果 Keep 提示「重复数据」，说明同一个开跑时间已经生成过，换个时间。");
        s.spacer(dp(10));

        s.text("三、费用");
        s.text("软件可以免费装、免费试、随便调参数。"
                + "只有点「生成」这一步需要 1 元解锁，解锁后永久可用，"
                + "没有次数限制、没有到期日。");
        s.spacer(dp(10));

        s.text("四、不要做的事");
        s.text("不要把生成的数据用于任何需要真实成绩证明的场合——比赛、体测、"
                + "需要官方认证的记录都不行。这类用途不在本软件的设计范围内，"
                + "产生的一切后果由使用者承担。");

        s.spacer(dp(14));
        s.ghost("知道了", new Runnable() { public void run() { s.dismiss(); } });
        s.show();
    }

    /** 免责声明 */
    private void openDisclaimer() {
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("免责声明", "请阅读后使用");

        s.text("1. 本软件按「现状」提供，不对生成结果的准确性、完整性作任何明示或默示担保。");
        s.spacer(dp(8));
        s.text("2. 生成的数据为模拟数据，不代表任何真实的身体活动。使用者应自行判断"
                + "其适用场景，并承担使用后果。");
        s.spacer(dp(8));
        s.text("3. 使用者应遵守所使用平台（包括但不限于 Keep）的服务条款。"
                + "因违反平台规则导致的账号处理、数据清除等后果，与本软件作者无关。");
        s.spacer(dp(8));
        s.text("4. 严禁将本软件用于伪造成绩、骗取奖励、保险理赔、赛事报名等任何"
                + "欺骗性或违法用途。此类行为产生的一切法律责任由使用者自行承担。");
        s.spacer(dp(8));
        s.text("5. 在法律允许的最大范围内，作者不对使用本软件造成的任何直接或间接损失"
                + "承担责任。");
        s.spacer(dp(8));
        s.text("6. 继续使用即表示你已阅读、理解并同意以上条款。如不同意，请停止使用并卸载。");

        s.spacer(dp(14));
        s.text("作者：金戈寄渔舟　github.com/hulibuld");
        s.spacer(dp(6));
        s.ghost("关闭", new Runnable() { public void run() { s.dismiss(); } });
        s.show();
    }

    /** 路线来源卡片：第一行两个模式，第二行三个动作 */
    private void buildRouteCard(LinearLayout parent) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(section);
        section.addView(Ui.iosSection(this, "路线来源"));
        LinearLayout card = Ui.iosGroup(this, section);

        // 第一行：田径场 / 自定义
        LinearLayout row1 = Ui.buttonRow(this, card, 0);
        final Button bTrack = new Button(this);
        bTrack.setText("田径场");
        bTrack.setLayoutParams(Ui.weight(this, 1f, 3));
        bTrack.setAllCaps(false); bTrack.setTextSize(15);
        bTrack.setMinHeight(dp(42)); bTrack.setMinimumHeight(dp(42));
        Ui.iosSegOn(this, bTrack, true);
        row1.addView(bTrack);

        final Button bCustom = new Button(this);
        bCustom.setText("自定义");
        bCustom.setLayoutParams(Ui.weight(this, 1f, 3));
        bCustom.setAllCaps(false); bCustom.setTextSize(15);
        bCustom.setMinHeight(dp(42)); bCustom.setMinimumHeight(dp(42));
        Ui.iosSegOn(this, bCustom, false);
        row1.addView(bCustom);

        // 田径场规格行：只有 400m / 200m
        final LinearLayout trackSpecRow = Ui.buttonRow(this, card, 10);
        final Button b400 = new Button(this);
        b400.setText("400m");
        b400.setLayoutParams(Ui.weight(this, 1f, 3));
        iosChip(b400);
        trackSpecRow.addView(b400);

        final Button b200 = new Button(this);
        b200.setText("200m");
        b200.setLayoutParams(Ui.weight(this, 1f, 3));
        iosChip(b200);
        trackSpecRow.addView(b200);

        b400.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                field = Field.std400(field.lat0, field.lon0, field.bearing);
                trackLen = 400;
                Ui.iosSegOn(MainActivity.this, b400, true);
                Ui.iosSegOn(MainActivity.this, b200, false);
                syncRouteInfo();
            }
        });
        b200.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                field = Field.std200(field.lat0, field.lon0, field.bearing);
                trackLen = 200;
                Ui.iosSegOn(MainActivity.this, b400, false);
                Ui.iosSegOn(MainActivity.this, b200, true);
                syncRouteInfo();
            }
        });
        Ui.iosSegOn(this, b400, true);
        Ui.iosSegOn(this, b200, false);

        final LinearLayout coordRow = Ui.buttonRow(this, card, 8);
        coordRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView coordCurrent = Ui.hint(this, "");
        coordCurrent.setTextSize(12);
        coordCurrent.setLayoutParams(Ui.weight(this, 1.6f, 0));
        coordCurrent.setSingleLine(true);
        coordCurrent.setEllipsize(android.text.TextUtils.TruncateAt.END);
        coordRow.addView(coordCurrent);
        coordInfo = coordCurrent;
        TextView bCoord = new TextView(this);
        bCoord.setText("改坐标 ›");
        bCoord.setTextSize(13);
        bCoord.setTextColor(Ui.iosBlue());
        bCoord.setPadding(dp(10), dp(6), dp(2), dp(6));
        bCoord.setClickable(true);
        bCoord.setOnClickListener(v -> openCoordSheet());
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bCoord.setLayoutParams(cblp);
        coordRow.addView(bCoord);

        // 第二行：从链接 / 路线库 / 邀请码
        // 田径场模式下这一行整块隐藏——操场上用不到外部路线，
        // 摆着只会让人以为「选了田径场还得再去取一条路线」。
        final LinearLayout row2 = Ui.buttonRow(this, card, 10);
        Button bLink = new Button(this);
        bLink.setText("从链接");
        bLink.setLayoutParams(Ui.weight(this, 1f, 3));
        iosChip(bLink);
        bLink.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openLinkSheet(); }
        });
        row2.addView(bLink);

        Button bLib = new Button(this);
        bLib.setText("路线库");
        bLib.setLayoutParams(Ui.weight(this, 1f, 3));
        iosChip(bLib);
        bLib.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openLibrarySheet(); }
        });
        row2.addView(bLib);

        Button bCode = new Button(this);
        bCode.setText("邀请码");
        bCode.setLayoutParams(Ui.weight(this, 1f, 3));
        iosChip(bCode);
        bCode.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openCodeSheet(); }
        });
        row2.addView(bCode);

        // 联动
        bTrack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                routeKind = "track";
                routePoints = null;
                trackSpecRow.setVisibility(View.VISIBLE);
                coordRow.setVisibility(View.VISIBLE);
                row2.setVisibility(View.GONE);
                Ui.iosSegOn(MainActivity.this, bTrack, true);
                Ui.iosSegOn(MainActivity.this, bCustom, false);
                syncRouteInfo();
            }
        });
        bCustom.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                routeKind = "custom";
                trackSpecRow.setVisibility(View.GONE);
                coordRow.setVisibility(View.GONE);
                row2.setVisibility(View.VISIBLE);
                Ui.iosSegOn(MainActivity.this, bTrack, false);
                Ui.iosSegOn(MainActivity.this, bCustom, true);
                syncRouteInfo();
            }
        });

        boolean isTrack = "track".equals(routeKind);
        trackSpecRow.setVisibility(isTrack ? View.VISIBLE : View.GONE);
        coordRow.setVisibility(isTrack ? View.VISIBLE : View.GONE);
        row2.setVisibility(isTrack ? View.GONE : View.VISIBLE);
        Ui.iosSegOn(this, bTrack, isTrack);
        Ui.iosSegOn(this, bCustom, !isTrack);

        tvRouteInfo = coordCurrent;
        tvRouteInfo.setPadding(dp(2), 0, dp(2), 0);
        syncRouteInfo();
    }


    /** 距离卡片：iOS 时钟式大号数字，直接可编辑 */
    private void buildDistCard(LinearLayout parent) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        section.setLayoutParams(slp);
        parent.addView(section);
        section.addView(Ui.iosSection(this, "本次距离"));
        LinearLayout card = Ui.iosGroup(this, section);

        LinearLayout numRow = new LinearLayout(this);
        numRow.setOrientation(LinearLayout.HORIZONTAL);
        numRow.setGravity(Gravity.BOTTOM);
        numRow.setPadding(dp(2), dp(6), 0, dp(8));
        etDist = Ui.iosNumber(this, "5.5");
        etDist.setLayoutParams(new LinearLayout.LayoutParams(
                dp(200), ViewGroup.LayoutParams.WRAP_CONTENT));
        etDist.setSelection(etDist.getText().length());
        numRow.addView(etDist);
        TextView unit = new TextView(this);
        unit.setText("公里");
        unit.setTextSize(20);
        unit.setTextColor(Ui.iosSecondary());
        unit.setPadding(dp(6), 0, 0, dp(14));
        numRow.addView(unit);
        card.addView(numRow);

        card.addView(Ui.iosSeparator(this, 2));

        LinearLayout chips = Ui.buttonRow(this, card, 12);
        String[] quick = {"3", "5", "5.5", "10"};
        for (int i = 0; i < quick.length; i++) {
            final String q = quick[i];
            Button b = new Button(this);
            b.setText(q + " km");
            b.setLayoutParams(Ui.weight(this, 1f, 3));
            b.setAllCaps(false);
            b.setTextSize(13);
            b.setTextColor(Ui.iosBlue());
            b.setBackground(Ui.round(Ui.iosFill(), Ui.IOS_R_CTRL, this));
            b.setMinHeight(dp(40)); b.setMinimumHeight(dp(40));
            b.setStateListAnimator(null);
            final EditText target = etDist;
            b.setOnClickListener(v -> target.setText(q));
            chips.addView(b);
        }
    }

    /** 配速 / 时长卡片 */
    private void buildPaceCard(LinearLayout parent, boolean tread) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(section);
        section.addView(Ui.iosSection(this, "计时方式"));
        LinearLayout card = Ui.iosGroup(this, section);

        LinearLayout modeRow = Ui.buttonRow(this, card, 0);
        final Button bPace = new Button(this);
        bPace.setText("配速");
        bPace.setLayoutParams(Ui.weight(this, 1f, 0));
        iosSegButton(bPace, false);
        modeRow.addView(bPace);
        final Button bTime = new Button(this);
        bTime.setText("总时长");
        bTime.setLayoutParams(Ui.weight(this, 1f, 0));
        iosSegButton(bTime, false);
        modeRow.addView(bTime);

        // 配速和时长都改成「拨轮」：输入框保留但收起来，只作为取值载体。
        // 为什么保留：doGenerate 里一整套校验（最快 4:00、上限 4 小时）都是读这几个
        // EditText 的文本，换成控件就要重写那一整段，风险比收益大。
        final LinearLayout paceRow = Ui.buttonRow(this, card, 12);
        etPaceMin = input("5", InputType.TYPE_CLASS_NUMBER);
        etPaceSec = input("05", InputType.TYPE_CLASS_NUMBER);
        etPaceMin.setLayoutParams(Ui.weight(this, 1f, 4));
        etPaceSec.setLayoutParams(Ui.weight(this, 1f, 4));
        paceRow.addView(etPaceMin);
        paceRow.addView(etPaceSec);
        paceRow.setVisibility(View.GONE);

        final LinearLayout durRow = Ui.buttonRow(this, card, 12);
        etDurH = input("0", InputType.TYPE_CLASS_NUMBER);
        etDurM = input("27", InputType.TYPE_CLASS_NUMBER);
        etDurH.setLayoutParams(Ui.weight(this, 1f, 4));
        etDurM.setLayoutParams(Ui.weight(this, 1f, 4));
        durRow.addView(etDurH);
        durRow.addView(etDurM);
        durRow.setVisibility(View.GONE);

        final Button bWheelPace = wheelRow("配速　5'05\"");
        final Button bWheelDur = wheelRow("时长　0:27");
        bWheelDur.setVisibility(View.GONE);
        card.addView(bWheelPace);
        card.addView(bWheelDur);

        bWheelPace.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String[] mins = Pickers.range(3, 30, false);
                String[] secs = Pickers.range(0, 59, true);
                int mi = Pickers.at(mins, etPaceMin.getText().toString().trim());
                int si = Pickers.at(secs, pad2(etPaceSec.getText().toString().trim()));
                Pickers.show(MainActivity.this, "配速（每公里）",
                        new String[]{"分", "秒"}, new String[][]{mins, secs},
                        new int[]{mi, si}, new Pickers.OnDone() {
                            public void onDone(int[] idx) {
                                String[] mins2 = Pickers.range(3, 30, false);
                                String[] secs2 = Pickers.range(0, 59, true);
                                etPaceMin.setText(mins2[idx[0]]);
                                etPaceSec.setText(secs2[idx[1]]);
                                bWheelPace.setText("配速　" + mins2[idx[0]] + "'" + secs2[idx[1]] + "\"");
                            }
                        });
            }
        });

        bWheelDur.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String[] hrs = Pickers.range(0, 3, false);
                String[] mis = Pickers.range(0, 59, true);
                int hi = Pickers.at(hrs, etDurH.getText().toString().trim());
                int mi2 = Pickers.at(mis, pad2(etDurM.getText().toString().trim()));
                Pickers.show(MainActivity.this, "总时长",
                        new String[]{"时", "分"}, new String[][]{hrs, mis},
                        new int[]{hi, mi2}, new Pickers.OnDone() {
                            public void onDone(int[] idx) {
                                String[] hrs2 = Pickers.range(0, 3, false);
                                String[] mis2 = Pickers.range(0, 59, true);
                                etDurH.setText(hrs2[idx[0]]);
                                etDurM.setText(mis2[idx[1]]);
                                bWheelDur.setText("时长　" + hrs2[idx[0]] + ":" + mis2[idx[1]]);
                            }
                        });
            }
        });

        TextView hint = Ui.hint(this, "每公里用时。跑步最快 4:00，时长上限 4 小时");
        hint.setPadding(dp(2), dp(16), 0, 0);
        card.addView(hint);

        bPace.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                usePace = true;
                paceRow.setVisibility(View.GONE);
                durRow.setVisibility(View.GONE);
                bWheelPace.setVisibility(View.VISIBLE);
                bWheelDur.setVisibility(View.GONE);
                Ui.iosSegOn(MainActivity.this, bPace, true);
                Ui.iosSegOn(MainActivity.this, bTime, false);
            }
        });
        bTime.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                usePace = false;
                paceRow.setVisibility(View.GONE);
                durRow.setVisibility(View.GONE);
                bWheelPace.setVisibility(View.GONE);
                bWheelDur.setVisibility(View.VISIBLE);
                Ui.iosSegOn(MainActivity.this, bPace, false);
                Ui.iosSegOn(MainActivity.this, bTime, true);
            }
        });
        Ui.iosSegOn(this, bPace, true);
        Ui.iosSegOn(this, bTime, false);
    }

    /** 开跑时间 + 心率 */
    private void buildTimeCard(final LinearLayout parent) {
        final boolean isTread = (parent == treadBody);
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(section);
        section.addView(Ui.iosSection(this, "开跑时间（北京时间）"));
        LinearLayout card = Ui.iosGroup(this, section);

        LinearLayout row = Ui.buttonRow(this, card, 10);
        // 每次都新建：同一个 EditText 不能同时挂在两个卡片下
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -1);
        EditText ed = input(new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime()),
                InputType.TYPE_CLASS_DATETIME);
        EditText et = input("06:30", InputType.TYPE_CLASS_DATETIME);
        // 只在用户主动点进来时才弹键盘并接管焦点。
        // 默认给 INPUT_TYPE 的 EditText 会自己抢焦点，页面一打开键盘就顶上来，
        // 把下面的「生成」按钮挡住，点下去反而落到键盘上，日期会被改坏。
        for (EditText e2 : new EditText[]{ed, et}) {
            e2.setFocusable(false);
            e2.setFocusableInTouchMode(false);
            e2.setClickable(true);
            e2.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    v.setFocusableInTouchMode(true);
                    v.requestFocus();
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(v, 0);
                }
            });
        }
        // 日期用拨轮，时和分保留手动输入（作者要求：只有距离和时、分是手输）
        ed.setVisibility(View.GONE);
        final Button bWheelDate = wheelRow("日期　" + ed.getText().toString());
        bWheelDate.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final String[] years = Pickers.range(2020, 2035, false);
                final String[] months = Pickers.range(1, 12, true);
                final String[] days = Pickers.range(1, 31, true);
                String cur = (isTread ? treadDate : etDate).getText().toString();
                String[] parts = cur.split("-");
                int yi = 6, mi = 10, di = 21;
                try { yi = Pickers.at(years, parts[0]); } catch (Exception ignored) {}
                try { mi = Pickers.at(months, parts[1]); } catch (Exception ignored) {}
                try { di = Pickers.at(days, parts[2]); } catch (Exception ignored) {}
                Pickers.show(MainActivity.this, "开跑日期",
                        new String[]{"年", "月", "日"}, new String[][]{years, months, days},
                        new int[]{yi, mi, di}, new Pickers.OnDone() {
                            public void onDone(int[] idx) {
                                String v = years[idx[0]] + "-" + months[idx[1]] + "-" + days[idx[2]];
                                (isTread ? treadDate : etDate).setText(v);
                                bWheelDate.setText("日期　" + v);
                            }
                        });
            }
        });
        ed.setLayoutParams(Ui.weight(this, 1.4f, 4));
        et.setLayoutParams(Ui.weight(this, 1f, 4));
        card.addView(bWheelDate);
        row.addView(ed);
        row.addView(et);
        // 两个页各存一份，生成时按当前页读
        if (isTread) { treadDate = ed; treadTime = et; }
        else { etDate = ed; etTime = et; }

        LinearLayout hrRow = Ui.buttonRow(this, card, 20);
        hrRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView hrLabel = new TextView(this);
        hrLabel.setText("附带心率");
        hrLabel.setTextSize(14);
        hrLabel.setTextColor(Ui.TEXT);
        hrLabel.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hrRow.addView(hrLabel);

        final android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(withHr);
        // 点开关时：如果当前格式不支持心率，自动切到 FIT
        sw.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean want = sw.isChecked();
                if (want && !fmtSupportsHr(fmt)) {
                    fmt = "fit";
                    withHr = true;
                    refreshFormatButtons();
                    syncHrSwitches();
                    toast("TCX 不带心率，已切到 FIT");
                    return;
                }
                withHr = want;
            }
        });
        hrRow.addView(sw);

        hrHint = Ui.hint(this, "");
        hrHint.setPadding(dp(2), dp(8), 0, 0);
        card.addView(hrHint);

        hrSwitches.add(sw);
        hrHints.add(hrHint);
    }

    /** 室内跑步：距离 + 配速（不需要路线） */
    private void buildTreadCard(LinearLayout parent) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(section);
        section.addView(Ui.iosSection(this, "室内跑步"));
        LinearLayout card = Ui.iosGroup(this, section);
        etTreadDist = input("3.0", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        card.addView(etTreadDist);

        LinearLayout chips = Ui.buttonRow(this, card, 10);
        String[] quick = {"2", "3", "5", "10"};
        for (int i = 0; i < quick.length; i++) {
            final String q = quick[i];
            Button b = new Button(this);
            b.setText(q);
            b.setLayoutParams(Ui.weight(this, 1f, 4));
            iosChip(b);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { etTreadDist.setText(q); }
            });
            chips.addView(b);
        }

        // 配速。
        //
        // 早期版本室内页只有距离，没有配速，
        // 但 doGenerate() 无论在哪一页都直接读户外页的 etPaceMin/etPaceSec。
        // 结果是在室内页填 5.5 公里，用的是户外页看不见的配速值，
        // 用户在室内页既看不到也改不了。
        card.addView(Ui.label(this, "配速（每公里）"));
        LinearLayout paceRow = Ui.buttonRow(this, card, 0);
        etTreadPaceMin = input("6", InputType.TYPE_CLASS_NUMBER);
        etTreadPaceMin.setLayoutParams(Ui.weight(this, 1f, 4));
        paceRow.addView(etTreadPaceMin);
        paceRow.addView(Ui.mono(this, ":"));
        etTreadPaceSec = input("00", InputType.TYPE_CLASS_NUMBER);
        etTreadPaceSec.setLayoutParams(Ui.weight(this, 1f, 4));
        paceRow.addView(etTreadPaceSec);

        TextView sp = Ui.hint(this, "跑步机不记录轨迹，所以不需要选路线");
        sp.setPadding(dp(2), dp(10), 0, 0);
        card.addView(sp);
    }

    /** 输出格式 */
    private void buildFormatCard(LinearLayout parent) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(section);
        section.addView(Ui.iosSection(this, "输出格式"));
        LinearLayout card = Ui.iosGroup(this, section);
        LinearLayout row = Ui.buttonRow(this, card, 0);
        final Button[] btns = new Button[2];
        final String[] keys = {"tcx", "fit"};
        final String[] names = {"TCX", "FIT"};
        for (int i = 0; i < 2; i++) {
            final String key = keys[i];
            Button b = new Button(this);
            b.setText(names[i]);
            b.setLayoutParams(Ui.weight(this, 1f, 3));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    fmt = key;
                    for (int j = 0; j < btns.length; j++) {
                        Ui.iosSegOn(MainActivity.this, btns[j], keys[j].equals(fmt));
                    }
                    // 切到不支持心率的格式时，心率自动关掉
                    if (!fmtSupportsHr(fmt) && withHr) {
                        withHr = false;
                        syncHrSwitches();
                        toast(names[fmtIndex(fmt)] + " 不带心率，已关闭");
                    }
                }
            });
            btns[i] = b;
            row.addView(b);
            formatBtns.add(b);
        }
        for (int i = 0; i < btns.length; i++) {
            Ui.iosSegOn(this, btns[i], keys[i].equals(fmt));
        }
        TextView hint = Ui.hint(this, "TCX 兼容性最好；FIT 带完整心率与步频");
        hint.setPadding(dp(2), dp(12), 0, 0);
        card.addView(hint);
    }

    // ================= 状态 =================


    private EditText numInput(String val, String hint) {
        EditText e = input(val, InputType.TYPE_CLASS_TEXT);
        e.setHint(hint);
        return e;
    }

    private static String paceText(int sec) {
        return (sec / 60) + ":" + String.format(Locale.US, "%02d", sec % 60);
    }

    /** 解析 4:00 / 4分00 / 240 三种写法 */
    private static int parseClockSafe(String s) {
        if (s == null) return 0;
        s = s.trim().replace("分", ":").replace("秒", "").replace("'", ":");
        if (s.isEmpty()) return 0;
        if (s.contains(":")) {
            String[] p = s.split(":");
            try {
                int m = Integer.parseInt(p[0].trim());
                int ss = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
                return m * 60 + ss;
            } catch (Exception e) { return 0; }
        }
        try {
            int v = Integer.parseInt(s);
            return v > 60 ? v : v * 60;     // 大于 60 当成秒
        } catch (Exception e) { return 0; }
    }

    /** 改田径场坐标：粘贴高德链接 */
    private void openCoordSheet() {
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("场地坐标", "粘一条高德链接就行，不用自己看经纬度");

        s.text("当前：" + String.format(Locale.US, "%.6f, %.6f", field.lat0, field.lon0));

        final EditText et = new EditText(this);
        et.setHint("https://uri.amap.com/marker?position=102.43,24.89");
        et.setTextSize(13);
        et.setTextColor(Ui.TEXT);
        et.setHintTextColor(Ui.TEXT_FAINT);
        et.setBackground(Ui.roundStroke(Ui.CARD_HI, Ui.INPUT_STROKE, Ui.RADIUS_CTRL, 1, this));
        int p = dp(12);
        et.setPadding(p, dp(10), p, dp(10));
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        s.view(et);

        s.spacer(dp(10));
        s.text("高德里长按操场 → 分享 → 复制链接，粘进来即可。"
                + "也可以直接粘「经度,纬度」两个数。");

        s.ghost("取消", new Runnable() { public void run() { s.dismiss(); } });
        s.primary("用这个坐标", new Runnable() {
            public void run() {
                AmapLink.LatLon ll = AmapLink.parse(et.getText().toString());
                if (ll == null) {
                    toast("没认出坐标，检查一下链接");
                    return;
                }
                // 保留当前跑道规格，只换中心点和方位
                field = new Field(ll.lat, ll.lon, field.bearing,
                        field.straight, field.laneR);
                s.dismiss();
                toast(String.format(Locale.US, "坐标已更新 %.5f, %.5f", ll.lat, ll.lon));
                syncRouteInfo();
            }
        });
        s.show();
    }

    /** 哪些格式支持心率：只有 FIT 有完整的心率汇总字段 */
    private static boolean fmtSupportsHr(String f) {
        return "fit".equals(f);
    }

    private int fmtIndex(String f) {
        if ("fit".equals(f)) return 1;
        return 0;
    }

    /** 格式变化后刷新按钮状态 */
    private void refreshFormatButtons() {
        String[] keys = {"tcx", "fit"};
        for (int i = 0; i < formatBtns.size() && i < keys.length; i++) {
            Ui.iosSegOn(this, formatBtns.get(i), keys[i].equals(fmt));
        }
    }

    /** 心率开关跟随格式：不支持的格式下变灰并说明原因 */
    private void syncHrSwitches() {
        boolean support = fmtSupportsHr(fmt);
        for (android.widget.Switch sw : hrSwitches) {
            sw.setChecked(withHr && support);
            sw.setEnabled(true);      // 保持可点，点了会自动切格式
            sw.setAlpha(support ? 1f : 0.45f);
        }
        for (TextView t : hrHints) {
            if (support) {
                t.setText(withHr
                        ? "心率按设置里的静息/最高心率离线推算"
                        : "当前不带心率，触摸开关可开启");
            } else {
                t.setText("TCX 不带心率。想记录心率就选 FIT，或直接点开关自动切换");
            }
        }
    }

    private void syncRouteInfo() {
        if (tvRouteInfo == null) return;
        String s;
        if ("track".equals(routeKind)) {
            s = String.format(Locale.US, "%d 米跑道 · %.5f, %.5f　点这里改坐标",
                    trackLen, field.lat0, field.lon0);
        } else if (routePoints != null && routePoints.size() >= 2) {
            s = String.format(Locale.US, "%d 个点 · 长 %.0f 米%s",
                    routePoints.size(), RouteIO.lengthM(routePoints),
                    routeLoop ? " · 环线" : "");
        } else {
            s = "还没选路线。点下面的「邀请码」取一条，或「路线库」从自己的收藏里选";
        }
        tvRouteInfo.setText(s);
    }


    /**
     * 未解锁时点「生成」的提示。
     *
     * 不拦着看界面、不拦着调参数，只在真正产出文件这一步拦。
     * 这样用户能先把东西配好，确认这个软件对他有用，再去解锁。
     */
    // ================= 每日配额（不写明规则，到点只说一句人话） =================

    /** 服务端的硬上限是 50；本地这份只用于「提前一句话」，不作判据 */
    private static final int DAILY_SOFT = 50;

    private int todayCount() {
        android.content.SharedPreferences sp =
                getSharedPreferences("gen_quota", MODE_PRIVATE);
        if (!dayStamp().equals(sp.getString("d", ""))) return 0;
        return sp.getInt("n", 0);
    }

    private void countToday() {
        android.content.SharedPreferences sp =
                getSharedPreferences("gen_quota", MODE_PRIVATE);
        int n = dayStamp().equals(sp.getString("d", "")) ? sp.getInt("n", 0) : 0;
        sp.edit().putString("d", dayStamp()).putInt("n", n + 1).apply();
    }

    private static String dayStamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date());
    }

    /**
     * 到量之后的提示。
     *
     * 刻意不出现数字、不出现「限制」「每日」「次数」这些词：
     * 用户看见「每天 50 次」会觉得这软件在卡他。
     * 只说「今天生成得有点多」，明天自然恢复。
     */
    private void showNotOfficial() {
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("这个包不是官方发布的", "");
        s.text("装回官方版本就能正常生成。");
        s.spacer(dp(14));
        s.primary("知道了", new Runnable() { public void run() { s.dismiss(); } });
        s.show();
    }

    /**
     * 同一天去重的拦截层。
     *
     * 为什么按「天」而不是按 startTime：Keep 对同一 startTime 会返回同一个 logId（覆盖），
     * 但用户换个时间点再生成一次，就会真的多出一条 —— 而校园跑的考核是「一天一次」，
     * 同一天出两条反而更可疑（也更容易被老师看出来）。所以按天拦。
     */
    private boolean blockDuplicateDay(final long startMs) {
        if (skipDupCheck) { skipDupCheck = false; return false; }
        final String k = KeepApi.dayKey(startMs);
        if (!KeepApi.uploadedDays(this).contains(k)) return false;

        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("这一天已经有一条了", k.substring(0, 4) + "年" + k.substring(4, 6) + "月" + k.substring(6) + "日");
        s.text("同一天在 Keep 里出现两条，比只有一条更容易被看出问题。"
                + "要不换个日期，要不先去 Keep 把那条删掉。");
        s.spacer(dp(10));
        s.ghost("换个日期", new Runnable() { public void run() { s.dismiss(); } });
        s.primary("仍然再生成一条", new Runnable() {
            public void run() {
                s.dismiss();
                skipDupCheck = true;
                toast("那就再生成一条");
                doGenerate();
            }
        });
        s.show();
        return true;
    }

    private boolean autoUpload() {
        return getSharedPreferences("keep_upload", MODE_PRIVATE)
                .getBoolean("auto", true);   // 默认开：登录了就该一把写完
    }

    private void setAutoUpload(boolean on) {
        getSharedPreferences("keep_upload", MODE_PRIVATE).edit()
                .putBoolean("auto", on).apply();
    }

    /**
     * 显示「已激活」时，去公开清单核对一次。
     *
     * 规矩（作者定）：仓库里读到这枚码 = 默认激活；读不到（404）= 提示「重置 / 联系作者」。
     * 三条防误伤：
     *   · 老用户（有解锁但没有 hash）直接放过 —— 一升级就弹窗会把人吓死；
     *   · 网络失败/超时什么都不做 —— 离线必须能用；
     *   · 24 小时内只查一次 —— 省流量，也免得每次回前台都发请求。
     */
    private void verifyActivationOnce() {
        if (!License.isUnlocked(this)) return;
        final String h = License.codeHash(this);
        if (h == null || h.length() != 32) return;

        long last = getSharedPreferences("keep_act", MODE_PRIVATE).getLong("checked", 0);
        if (System.currentTimeMillis() - last < 24L * 3600 * 1000) return;

        new Thread(new Runnable() {
            public void run() {
                int code = -1;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL(BuildConfig.PUBLIC_ACT_BASE + "/activated/" + h + ".json")
                                    .openConnection();
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("User-Agent", "keepgen/" + BuildConfig.VERSION_NAME);
                    code = c.getResponseCode();
                    try { c.getInputStream().close(); } catch (Exception ignored) {}
                    c.disconnect();
                } catch (Exception e) {
                    code = -1;   // 网络问题：跳过，不判断
                }
                final int fc = code;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (fc == 200) {
                            getSharedPreferences("keep_act", MODE_PRIVATE).edit()
                                    .putLong("checked", System.currentTimeMillis()).apply();
                        } else if (fc == 404) {
                            showActivationGone();
                        }
                        // 其他情况（超时、5xx）什么都不做，下次再试
                    }
                });
            }
        }).start();
    }

    /** 公开清单里没有这枚码：给两个按键，一个是找作者，一个是就地重置 */
    private void showActivationGone() {
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("这个解锁码不在激活名单里", "生成功能照常可用");
        s.text("可能是作者把这枚码撤销了，也可能是这台手机上的激活数据被改过。"
                + "要恢复的话，联系作者重新登记就行，不用再买一次。");
        s.spacer(dp(12));
        s.ghost("重置为未激活", new Runnable() {
            public void run() {
                License.lock(MainActivity.this);
                CloudTrack.clearToken(MainActivity.this);
                s.dismiss();
                toast("已重置，重新输入解锁码即可");
                recreate();
            }
        });
        s.primary("联系作者", new Runnable() {
            public void run() {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            "keepgen", BuildConfig.UNLOCK_URL));
                    toast("购买页链接已复制，去爱发电找作者");
                } catch (Exception e) {
                    toast("复制失败，请手动打开爱发电找作者");
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.UNLOCK_URL)));
                } catch (Exception ignored) {}
                s.dismiss();
            }
        });
        s.show();
    }

    /** 第一次打开时讲清楚三步，之后不再打扰 */
    private void maybeFirstRunGuide() {
        android.content.SharedPreferences sp = getSharedPreferences("intro", MODE_PRIVATE);
        if (sp.getBoolean("seen", false)) return;
        sp.edit().putBoolean("seen", true).apply();

        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("三步就能用", "不用注册，不用填经纬度");
        s.section("1　选场地和距离");
        s.text("默认是安宁校区 400 米跑道。别的地方点「改场地坐标」，粘一条高德链接就行。");
        s.section("2　点「生成」");
        s.text("生成的运动数据会存到手机 Download，也能直接写进 Keep 账号。");
        s.section("3　登录 Keep（可选）");
        s.text("登录之后生成完自动写进账号，不用再手动导入。不登录也能用，只是要自己导文件。");
        s.spacer(dp(12));
        s.ghost("先看看", new Runnable() { public void run() { s.dismiss(); } });
        s.primary("去登录 Keep", new Runnable() {
            public void run() { s.dismiss(); openKeepLogin(); }
        });
        s.show();
    }

    private void openKeepLogin() {
        try {
            startActivity(new Intent(MainActivity.this, KeepLoginActivity.class));
        } catch (Exception e) {
            toast("打不开登录页：" + e.getMessage());
        }
    }

    /**
     * 把刚生成的这条记录写进 Keep 账号。
     *
     * 走 POST /pd/v5/sportlog（唯一可用写通道）。成功后把 logId 落盘，
     * 后续「重传」靠它去重，不会在账号里堆重复记录。
     */
    private void uploadToKeep(final Generator.Result res) { uploadToKeep(res, null); }

    private void uploadToKeep(final Generator.Result res, final String savedPath) {
        if (!KeepApi.loggedIn(this)) { openKeepLogin(); return; }
        final String token = KeepApi.token(this);
        toast("正在写进 Keep…");
        new Thread(new Runnable() {
            public void run() {
                String logId = null;
                String err = null;
                try {
                    logId = KeepApi.uploadV5(token, res);
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                // 读回一次：服务端会自己算 doubtful（风控分），如果被标了要告诉用户
                boolean doubtful = false;
                if (logId != null) {
                    try {
                        KeepApi.Resp r = KeepApi.detail(token, detailTypeOf(res.sportKey), logId);
                        if (r.ok) {
                            org.json.JSONObject d = new org.json.JSONObject(r.body).optJSONObject("data");
                            if (d != null) doubtful = d.optBoolean("doubtful", false);
                        }
                    } catch (Exception ignored) {}
                }
                final String fid = logId, ferr = err;
                final boolean fdoubt = doubtful;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (fid != null) {
                            KeepApi.markUploadedDay(MainActivity.this, res.startMs);
                            res.doubtfulHint = fdoubt;
                            getSharedPreferences("keep_upload", MODE_PRIVATE).edit()
                                    .putString("last_log_id", fid)
                                    .putLong("last_start", res.startMs).apply();
                            showUploadOk(fid, res);
                        } else {
                            showUploadFail(ferr);
                        }
                    }
                });
            }
        }).start();
    }

    /** dataType -> 详情查询用的大类（详情地址是 /pd/v3/{type}log/{id}） */
    private static String detailTypeOf(String sportKey) {
        if ("bike".equals(sportKey)) return "cycling";
        if ("walk".equals(sportKey)) return "walking";
        return "running";
    }

    private void showUploadOk(String logId, Generator.Result res) {
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("已写进 Keep", res.distKmText() + "　" + Generator.clock(res.durSec));
        s.text("现在打开 Keep →「我的 → 运动记录」，最新那条就是它。");
        if (res != null && res.doubtfulHint) {
            s.spacer(dp(6));
            s.text("这条被 Keep 标了「存疑」，可能是配速或轨迹太规律。换一天、换场地再生成会更自然。");
        }
        s.spacer(dp(8));
        TextView idTv = new TextView(this);
        idTv.setText(logId);
        idTv.setTextSize(11);
        idTv.setTypeface(Typeface.MONOSPACE);
        idTv.setTextColor(Ui.TEXT);
        idTv.setTextIsSelectable(true);
        s.view(idTv);
        s.ghost("知道了", new Runnable() { public void run() { s.dismiss(); } });
        s.primary("打开 Keep 看看", new Runnable() {
            public void run() { s.dismiss(); openKeep(); }
        });
        s.show();
    }

    private void showUploadFail(String err) {
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("没写进去", "文件已经存好了，不影响使用");
        s.text(err == null ? "Keep 那边没响应" : err);
        s.spacer(dp(8));
        s.text("可以先在 Keep 里手动导入这份文件；如果是登录失效，重新登录一次再点上传。");
        s.ghost("知道了", new Runnable() { public void run() { s.dismiss(); } });
        s.primary("重新登录 Keep", new Runnable() {
            public void run() { s.dismiss(); openKeepLogin(); }
        });
        s.show();
    }

    private void showTooManyToday() {
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("今天生成得有点多了", "");
        s.text("歇一会儿再来。明天就恢复正常。");
        s.spacer(dp(14));
        s.primary("知道了", new Runnable() { public void run() { s.dismiss(); } });
        s.show();
    }

    private void showNeedUnlock() {
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("生成前需要先解锁", "1 元，永久可用");
        s.text("软件可以随便试、随便调，只有点「生成」这一步需要解锁。");
        s.spacer(dp(8));
        s.text("解锁之后就不再提示这一步，直接生成。");
        s.spacer(dp(14));
        s.ghost("先看看", new Runnable() { public void run() { s.dismiss(); } });
        s.primary("1 元解锁", new Runnable() {
            public void run() {
                s.dismiss();
                openUnlock();
            }
        });
        s.show();
    }

    /** 首页顶部那条 Keep 状态提示：未登录可点，已登录显示昵称 */
    private void syncKeepBar() {
        if (keepBar == null) return;
        if (KeepApi.loggedIn(this)) {
            String nm = KeepApi.userName(this);
            keepBar.setText("Keep 已登录" + (nm == null || nm.isEmpty() ? "" : "：" + nm)
                    + "　生成后自动写入");
            keepBar.setTextColor(Ui.ACCENT);
        } else {
            keepBar.setText("还没登录 Keep　点这里登录（不登录也能导出文件）");
            keepBar.setTextColor(Ui.TEXT);
        }
        keepBar.setVisibility(View.VISIBLE);
    }

    private void syncGoButton() {
        if (goButton == null) return;
        goButton.setEnabled(true);
        // 文案颜色必须跟底色配套：墨底用 ON_ACCENT。
        // 这里原来写死 Color.WHITE，深色模式下底色是白、字也是白，按钮直接看不见。
        goButton.setBackground(Ui.round(Ui.ACCENT, Ui.RADIUS_CTRL, this));
        goButton.setTextColor(Ui.ON_ACCENT);
        goButton.setText("生成");
    }

    // ================= 返回键 =================

    /** 有面板开着就只关面板，不退出 App */
    @Override
    public void onBackPressed() {
        if (activeSheet != null && activeSheet.isOpen()) {
            // 阻塞型弹层不吃返回键：门禁就是门禁，按一下就绕过等于没有。
            if (activeSheet.isBlocking()) return;
            activeSheet.dismiss();
            activeSheet = null;
            return;
        }
        super.onBackPressed();
    }

    private Sheet activeSheet;
    /** 首页顶部那条「Keep 是否已登录」的提示条，点了直接去登录 */

    /** 「这一天已经有一条了」点了「仍然生成」时置 true，只用于跳过一次去重 */
    private boolean skipDupCheck;


    // ================= 弹层 =================

    private void openSettings() {
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("设置", "心率参数填得越准，生成的数据越像你本人");

        // ---- Keep 账号 ----
        s.section("Keep 账号");
        if (KeepApi.loggedIn(this)) {
            String nm = KeepApi.userName(this);
            s.text("已登录" + (nm == null || nm.isEmpty() ? "" : "：" + nm)
                    + "。生成完可以直接写进账号，不用导出文件手动导入。");
            final boolean auto = autoUpload();
            s.ghost((auto ? "✓ " : "") + "生成后自动写进 Keep", new Runnable() {
                public void run() {
                    setAutoUpload(!auto);
                    s.dismiss();
                    toast(auto ? "已关掉自动写入" : "以后生成完会自动写进 Keep");
                    openSettings();
                }
            });
            s.ghost("补历史（一次补齐最近几天）", new Runnable() {
                public void run() {
                    s.dismiss();
                    startActivity(new Intent(MainActivity.this, BatchActivity.class));
                }
            });
            s.primary("看我的 Keep 记录", new Runnable() {
                public void run() {
                    s.dismiss();
                    startActivity(new Intent(MainActivity.this, KeepListActivity.class));
                }
            });
            s.ghost("退出登录", new Runnable() {
                public void run() {
                    KeepApi.logout(MainActivity.this);
                    s.dismiss();
                    toast("已退出 Keep");
                    recreate();
                }
            });
        } else {
            s.text("登录之后，生成的记录可以一键写进 Keep 账号；不登录也能用，只是要自己导出文件导入。");
            s.primary("登录 Keep", new Runnable() {
                public void run() {
                    s.dismiss();
                    openKeepLogin();
                }
            });
        }
        s.spacer(dp(10));

        final HrProfile hp = HrProfile.load(this);
        hrProfile = hp;

        s.section("外观");
        LinearLayout themeRow = Ui.buttonRow(this, null, 0);
        final Button bAuto = new Button(this);
        bAuto.setText("跟随系统");
        bAuto.setLayoutParams(Ui.weight(this, 1f, 3));
        themeRow.addView(bAuto);
        final Button bLight = new Button(this);
        bLight.setText("浅色");
        bLight.setLayoutParams(Ui.weight(this, 1f, 3));
        themeRow.addView(bLight);
        final Button bDark = new Button(this);
        bDark.setText("深色");
        bDark.setLayoutParams(Ui.weight(this, 1f, 3));
        themeRow.addView(bDark);

        for (Button bt : new Button[]{bAuto, bLight, bDark}) {
            iosChip(bt);
        }
        final Button[] themeBtns = {bAuto, bLight, bDark};
        final int[] themeModes = {Ui.MODE_AUTO, Ui.MODE_LIGHT, Ui.MODE_DARK};
        final Runnable syncThemeBtns = new Runnable() {
            public void run() {
                int cur = Ui.mode(MainActivity.this);
                for (int i = 0; i < themeBtns.length; i++) {
                    Ui.iosSegOn(MainActivity.this, themeBtns[i], themeModes[i] == cur);
                }
            }
        };
        for (int i = 0; i < themeBtns.length; i++) {
            final int m = themeModes[i];
            themeBtns[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Ui.setMode(MainActivity.this, m);
                    Ui.applyTheme(MainActivity.this);
                    // 颜色是构建期取的，换主题要重建界面
                    recreate();
                }
            });
        }
        syncThemeBtns.run();
        s.view(themeRow);
        s.spacer(dp(6));

        s.section("心率（离线推算）");
        final EditText etRest = numInput(String.valueOf(hp.restBpm), "静息心率，如 60");
        final EditText etMax = numInput(String.valueOf(hp.maxBpm), "最高心率，如 190");
        final EditText etPaceMax = numInput(paceText(hp.paceMaxSec), "最快 1 公里，如 4:00");

        s.text("静息心率");
        s.view(etRest);
        s.spacer(dp(6));
        s.text("最高心率");
        s.view(etMax);
        s.spacer(dp(6));
        s.text("最快 1 公里配速");
        s.view(etPaceMax);
        s.spacer(dp(6));

        final TextView preview = Ui.hint(this, "");
        preview.setPadding(0, dp(4), 0, 0);
        s.view(preview);

        final Runnable calc = new Runnable() {
            public void run() {
                HrProfile t = new HrProfile();
                t.restBpm = (int) parse(etRest.getText().toString());
                t.maxBpm = (int) parse(etMax.getText().toString());
                int sec = parseClockSafe(etPaceMax.getText().toString());
                if (sec > 0) t.paceMaxSec = sec;
                t.sanitize();
                preview.setText("按这套参数：4:00 配速约 " + t.steadyHr(240)
                        + " bpm　5:00 约 " + t.steadyHr(300)
                        + " bpm　6:30 约 " + t.steadyHr(390) + " bpm");
            }
        };
        calc.run();
        android.text.TextWatcher tw = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence cs, int a, int b, int c) {}
            public void onTextChanged(CharSequence cs, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable e) { calc.run(); }
        };
        etRest.addTextChangedListener(tw);
        etMax.addTextChangedListener(tw);
        etPaceMax.addTextChangedListener(tw);

        s.ghost("关闭", new Runnable() { public void run() { s.dismiss(); } });
        s.primary("保存", new Runnable() {
            public void run() {
                HrProfile hp2 = new HrProfile();
                hp2.restBpm = (int) parse(etRest.getText().toString());
                hp2.maxBpm = (int) parse(etMax.getText().toString());
                int sec = parseClockSafe(etPaceMax.getText().toString());
                if (sec > 0) hp2.paceMaxSec = sec;
                hp2.save(MainActivity.this);
                hrProfile = hp2;

                Toast.makeText(MainActivity.this, "已保存", Toast.LENGTH_SHORT).show();
                s.dismiss();
            }
        });
        s.show();
    }

    /** 邀请码：输入取路线，或把自己的路线发出去 */
    private void openCodeSheet() {
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("邀请码", "输入别人的码取路线；也可以把自己的路线生成码发出去");

        final EditText et = new EditText(this);
        et.setHint("6 位或一长串，粘贴都行");
        et.setTextSize(16);
        et.setTextColor(Ui.TEXT);
        et.setHintTextColor(Ui.TEXT_FAINT);
        et.setBackground(Ui.roundStroke(Ui.CARD_HI, Ui.INPUT_STROKE, Ui.RADIUS_CTRL, 1, this));
        int p = dp(12);
        et.setPadding(p, dp(12), p, dp(12));
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        s.view(et);

        s.spacer(dp(10));
        s.text("短码需要联网才能查；长码里自带路线数据，离线也能用。");

        s.ghost("生成我的码", new Runnable() { public void run() { s.dismiss(); openMakeCode(); } });
        s.primary("取路线", new Runnable() {
            public void run() {
                String raw = et.getText().toString().trim();
                s.dismiss();
                resolveCode(raw);
            }
        });
        s.show();
    }

    /** 把自己的路线打包成码 */
    private void openMakeCode() {
        if (routePoints == null || routePoints.size() < 2) {
            toast("先用「田径场」或「路线库」准备一条路线");
            return;
        }
        final String code = CodeShare.pack(routePoints, routeLoop);
        if (code == null) {
            toast("这条路线点太多，打包不了。可以先存到路线库再分享短码");
            return;
        }

        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("这是我的路线码", "复制发给别人，对方粘贴就能用，不用联网");

        TextView tv = new TextView(this);
        tv.setText(code);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(Ui.TEXT);
        tv.setBackground(Ui.roundStroke(Ui.CARD_HI, Ui.INPUT_STROKE, Ui.RADIUS_CTRL, 1, this));
        int p = dp(14);
        tv.setPadding(p, p, p, p);
        tv.setTextIsSelectable(true);
        s.view(tv);

        s.spacer(dp(10));
        s.text(String.format(Locale.US, "%d 个点，%.0f 米，%d 个字符",
                routePoints.size(), RouteIO.lengthM(routePoints), code.length()));

        s.ghost("关闭", new Runnable() { public void run() { s.dismiss(); } });
        s.primary("复制码", new Runnable() {
            public void run() {
                copyToClipboard(code);
                s.dismiss();
            }
        });
        s.show();
    }

    /** 解析一段码：先看是不是自带数据的长码，再看本地库，最后才联网 */
    private void resolveCode(String raw) {
        if (raw == null || raw.trim().isEmpty()) { toast("先粘贴一个码"); return; }
        String norm = raw.toUpperCase().replaceAll("[^A-Z0-9]", "");

        // 1. 长码自带数据
        CodeShare.Unpacked un = CodeShare.unpack(norm);
        if (un.points != null && un.points.size() >= 2) {
            applyRoute(un.points, un.loop, "已载入路线（" + un.points.size() + " 个点，离线可用）");
            return;
        }

        // 2. 本地库
        String code = norm.length() >= 6 ? norm.substring(0, 6) : norm;
        RouteStore st = new RouteStore(this);
        RouteStore.Route local = st.byCode(code);
        if (local != null) {
            applyRoute(local.points, local.loop, "已从本机载入 " + code);
            return;
        }

        // 3. 联网查公共路线库（地址内置，用户无需任何配置）
        fetchRemote(code);
    }

    private void fetchRemote(final String code) {
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("正在查 " + code, "联网获取中…");
        s.closeOnly();
        s.show();

        new Thread(new Runnable() {
            public void run() {
                String json = null;
                String err = null;
                try { json = Cloud.getRoute(server(), code); }
                catch (Exception e) { err = e.getMessage(); }
                final String fJson = json;
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { s.dismiss(); } catch (Exception ignored) {}
                        if (fErr != null || fJson == null) {
                            // 查不到只有两种原因：没网，或这条还没人分享。
                            // 不再引导用户去填服务器——没有那个设置了。
                            String why = (fErr != null && fErr.contains("404"))
                                    ? "这个短码还没人分享过" : "联网没成功，检查一下网络";
                            toast(why);
                            return;
                        }
                        List<double[]> pts = RouteIO.parse(fJson);
                        if (pts.size() < 2) { toast("返回的内容里没有坐标"); return; }
                        applyRoute(pts, true, "已从网络载入 " + code);
                    }
                });
            }
        }).start();
    }

    /** 路线库 */
    private void openLibrarySheet() {
        final RouteStore st = new RouteStore(this);
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        List<RouteStore.Route> all = st.list();

        s.title("路线库", all.isEmpty() ? "还没有保存的路线"
                : (all.size() + " 条 · 点一条直接用"));

        for (final RouteStore.Route r : all) {
            String sub = String.format(Locale.US, "%d 点 · %.0f 米%s%s",
                    r.points == null ? 0 : r.points.size(), r.lengthM,
                    r.loop ? " · 环线" : "",
                    r.uploaded ? " · 已分享" : "");
            final String label = r.name == null || r.name.isEmpty() ? r.code : r.name;
            s.item(label, sub,
                    new Runnable() {
                        public void run() {
                            s.dismiss();
                            applyRoute(r.points, r.loop, "已载入 " + label);
                        }
                    },
                    new Runnable() {
                        public void run() { confirmDeleteRoute(r, label); }
                    });
        }

        s.spacer(dp(12));
        s.text("本地不限条数。要删除某条，长按它。");

        s.ghost("关闭", new Runnable() { public void run() { s.dismiss(); } });
        s.primary("存当前路线", new Runnable() {
            public void run() { s.dismiss(); saveCurrentRoute(); }
        });
        s.show();
    }

    /** 长按删除确认 */
    private void confirmDeleteRoute(final RouteStore.Route r, final String label) {
        activeSheet = null;
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("删除「" + label + "」？", "删掉之后只能重新取一次，本地库里的记录会消失。");
        s.ghost("保留", new Runnable() { public void run() { s.dismiss(); } });
        s.danger("删除", new Runnable() {
            public void run() {
                new RouteStore(MainActivity.this).removeById(r.id);
                s.dismiss();
                toast("已删除 " + label);
                openLibrarySheet();
            }
        });
        s.show();
    }

    /** 存当前路线到本地库 */
    private void saveCurrentRoute() {
        if (routePoints == null || routePoints.size() < 2) {
            toast("还没有路线。先选「田径场」或从邀请码取一条");
            return;
        }
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("存到路线库", "起个名字，下次在「路线库」里直接点");

        final EditText et = new EditText(this);
        et.setHint("例如 操场 5 圈");
        et.setTextSize(15);
        et.setTextColor(Ui.TEXT);
        et.setHintTextColor(Ui.TEXT_FAINT);
        et.setBackground(Ui.roundStroke(Ui.CARD_HI, Ui.INPUT_STROKE, Ui.RADIUS_CTRL, 1, this));
        int p = dp(12);
        et.setPadding(p, dp(10), p, dp(10));
        s.view(et);

        s.ghost("取消", new Runnable() { public void run() { s.dismiss(); } });
        s.primary("保存", new Runnable() {
            public void run() {
                RouteStore.Route r = new RouteStore.Route();
                r.name = et.getText().toString().trim();
                if (r.name.isEmpty()) r.name = "未命名路线";
                r.points = routePoints;
                r.loop = routeLoop;
                r.lengthM = RouteIO.lengthM(routePoints);
                r.source = routeKind;
                try {
                    RouteStore.Route saved = new RouteStore(MainActivity.this).add(r);
                    s.dismiss();
                    toast("已保存：" + saved.name);
                } catch (Exception e) {
                    toast(e.getMessage());
                }
            }
        });
        s.show();
    }

    /** 从链接读 */
    private void openLinkSheet() {
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("从链接读路线", "粘贴高德或 Keep 的分享链接，自动抓轨迹");

        final EditText et = new EditText(this);
        et.setHint("https://...");
        et.setTextSize(14);
        et.setTextColor(Ui.TEXT);
        et.setHintTextColor(Ui.TEXT_FAINT);
        et.setBackground(Ui.roundStroke(Ui.CARD_HI, Ui.INPUT_STROKE, Ui.RADIUS_CTRL, 1, this));
        int p = dp(12);
        et.setPadding(p, dp(10), p, dp(10));
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        s.view(et);

        s.spacer(dp(10));
        s.text("也可以直接粘贴一串坐标（一行一个 lat,lon）或 TCX 原文。");

        s.ghost("粘贴坐标", new Runnable() { public void run() { s.dismiss(); openPasteSheet(); } });
        s.primary("读取", new Runnable() {
            public void run() {
                String url = et.getText().toString().trim();
                s.dismiss();
                if (url.isEmpty()) { toast("先粘贴一个链接"); return; }
                doFetchLink(url);
            }
        });
        s.show();
    }

    /** 粘贴坐标 */
    private void openPasteSheet() {
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("粘贴坐标", "一行一个，例如 24.8991,102.4328");

        final EditText et = new EditText(this);
        et.setHint("24.8991,102.4328\n24.8995,102.4334\n…");
        et.setTextSize(13);
        et.setTextColor(Ui.TEXT);
        et.setHintTextColor(Ui.TEXT_FAINT);
        et.setBackground(Ui.roundStroke(Ui.CARD_HI, Ui.INPUT_STROKE, Ui.RADIUS_CTRL, 1, this));
        int p = dp(12);
        et.setPadding(p, p, p, p);
        et.setMinLines(5);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        s.view(et);

        s.ghost("取消", new Runnable() { public void run() { s.dismiss(); } });
        s.primary("解析", new Runnable() {
            public void run() {
                List<double[]> pts = RouteIO.parse(et.getText().toString());
                if (pts.size() < 2) {
                    toast("只认出 " + pts.size() + " 个点，至少要 2 个");
                    return;
                }
                s.dismiss();
                applyRoute(pts, true, "已解析 " + pts.size() + " 个点");
            }
        });
        s.show();
    }

    private void doFetchLink(final String url) {
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("正在读取", "抓取链接里的轨迹…");
        s.closeOnly();
        s.show();

        new Thread(new Runnable() {
            public void run() {
                final LinkFetch.Result res = LinkFetch.fetch(url);
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { s.dismiss(); } catch (Exception ignored) {}
                        if (res.error != null) { toast(res.error); return; }
                        applyRoute(res.points, true, "已读取 " + res.points.size() + " 个点");
                    }
                });
            }
        }).start();
    }

    private void applyRoute(List<double[]> pts, boolean loop, String msg) {
        routePoints = pts;
        routeLoop = loop;
        routeKind = "custom";
        syncRouteInfo();
        toast(msg);
    }

    // ================= 生成 =================

    private void doGenerate() {
        try {
            // 付费闸门：没解锁就不产出文件。
            //
            // 早期版本这里完全不看授权状态，license.unlocked 只在徽章那一处用过，
            // 于是「生成」这个唯一的付费点是敞开的——收费等于零门槛。
            //
            // 仍然不用按钮变灰：变灰用户不知道为什么点不动，也没出路。
            // 让他点得动，点了之后说明情况并给解锁入口。
            // 第 1 层：UI 入口检查（同时初始化令牌）
            // 签名 pin：非官方证书签的包，不给生成。
            // 为零误伤，只拦「生成」这一步，其它界面照常用。
            if (!License.signedByAuthor(this)) {
                showNotOfficial();
                return;
            }
            if (!Guard.canStart(this) || !Guard.isStateCoherent(this)) {
                showNeedUnlock();
                return;
            }
            if (!license.unlocked) {
                showNeedUnlock();
                return;
            }
            Generator.Req r = new Generator.Req();
            r.sport = (page == PAGE_TREAD) ? "tread" : "run";

            double distKm;
            double paceSec = 0, durSec = 0;

            if (page == PAGE_TREAD) {
                distKm = parse(etTreadDist.getText().toString());
            } else {
                distKm = parse(etDist.getText().toString());
            }
            if (!(distKm > 0)) throw new IllegalArgumentException("先填一个距离");
            if (distKm > 50) throw new IllegalArgumentException("单次最长 50 公里");

            if (page == PAGE_TREAD) {
                // 室内页用自己的配速输入框。
                // 原来这里直接读户外页的 etPaceMin，用户在室内页看不到那个值。
                double pm = parse(etTreadPaceMin.getText().toString());
                double ps = parse(etTreadPaceSec.getText().toString());
                if (pm <= 0 && ps <= 0) throw new IllegalArgumentException("填一下配速");
                if (ps > 59) throw new IllegalArgumentException("秒数要小于 60");
                paceSec = pm * 60 + ps;
                durSec = Math.round(distKm * paceSec);
            } else if (usePace) {
                double pm = parse(etPaceMin.getText().toString());
                double ps = parse(etPaceSec.getText().toString());
                if (ps > 59) throw new IllegalArgumentException("秒数要小于 60");
                paceSec = pm * 60 + ps;
                durSec = Math.round(distKm * paceSec);
            } else {
                double h = parse(etDurH.getText().toString());
                double m = parse(etDurM.getText().toString());
                durSec = Math.round(h * 3600 + m * 60);
                if (durSec <= 0) throw new IllegalArgumentException("填一下总时长");
                paceSec = durSec / distKm;
            }
            if (durSec > 14400) throw new IllegalArgumentException("时长上限是 4 小时");
            if (paceSec < 240) throw new IllegalArgumentException(
                    "最快只能 4:00 一公里，现在是 " + Generator.clock(paceSec));

            long startMs;
            EditText dateEt = (page == PAGE_TREAD) ? treadDate : etDate;
            EditText timeEt = (page == PAGE_TREAD) ? treadTime : etTime;
            String dateTxt = dateEt.getText().toString().trim();
            String timeTxt = timeEt.getText().toString().trim();
            // 日期必须是整 10 位 yyyy-MM-dd，时间必须是 5 位 HH:mm。
            // 不校验的话，键盘误触多打一位会存成 "2026-11-199"，
            // 解析器宽松模式下会默默算成另一个日期，用户完全看不出错在哪。
            if (!dateTxt.matches("\\d{4}-\\d{2}-\\d{2}")) {
                throw new IllegalArgumentException("日期要写成 2026-11-19 这样");
            }
            if (!timeTxt.matches("\\d{1,2}:\\d{2}")) {
                throw new IllegalArgumentException("时间要写成 06:30 这样");
            }
            try {
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
                f.setLenient(false);
                startMs = f.parse(dateTxt + " " + timeTxt).getTime();
            } catch (Exception e) {
                throw new IllegalArgumentException("日期或时间格式不对");
            }
            if (startMs + (long) (durSec * 1000) > System.currentTimeMillis() + 60000) {
                throw new IllegalArgumentException("开跑时间不能在未来");
            }

            r.distKm = distKm;
            r.paceSec = paceSec;
            r.durSec = durSec;
            r.startMs = startMs;
            r.hr = withHr;
            r.hrProfile = (hrProfile != null) ? hrProfile : HrProfile.load(this);
            r.seed = 1000 + (int) (Math.random() * 90000);
            r.cadence = true;
            r.wantTcx = "tcx".equals(fmt);
            r.wantFit = "fit".equals(fmt);

            // 第 2 层：参数组装完成后检查
            if (!Guard.canBuildParameters(this)) {
                showNeedUnlock();
                return;
            }

            // ---- 向云端要轨迹 ----
            //
            // 跑道几何、摆动曲线这些核心算法在腾讯云函数里。
            // 本地只把返回的点组装成 TCX/FIT。
            //
            // 拿不到就停：不在本地降级生成「没有轨迹的文件」，
            // 那种文件导入 Keep 会缺数据，用户以为软件坏了。
            final boolean isTread = (page == PAGE_TREAD);
            final double distForCloud = distKm;
            final int seedForCloud = r.seed;
            final List<double[]> routeForCloud =
                    (!isTread && !"track".equals(routeKind)) ? routePoints : null;
            final int lapForCloud = trackLen;

            if (!isTread && routeForCloud == null) {
                // 田径场模式，但还没确认有轨迹，交给云端算
            } else if (!isTread && (routeForCloud == null || routeForCloud.size() < 2)) {
                throw new IllegalArgumentException("还没有路线，先用「邀请码」或「路线库」");
            }

            // 本地预检：只用来「别让用户干等 25 秒才被告知」。
            // 真判据在服务端（按码计数），本地这份会被清数据重置，所以不作判据。
            if (todayCount() >= DAILY_SOFT) {
                showTooManyToday();
                return;
            }

            // 同一天去重：跨天、换时间点都拦，只给「仍然再生成」一条出口
            if (blockDuplicateDay(startMs)) return;

            toast("正在计算轨迹…");

            final Field fld = field;
            final CloudTrack.Result[] holder = new CloudTrack.Result[1];
            final Exception[] errHolder = new Exception[1];
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        holder[0] = CloudTrack.fetch(MainActivity.this,
                                isTread ? "tread" : "run", distForCloud,
                                fld.lapM, fld.straight, fld.laneR);
                    } catch (Exception e) {
                        errHolder[0] = e;
                    }
                }
            });
            t.start();
            try { t.join(25000); } catch (Exception ignored) {}

            if (errHolder[0] != null) throw errHolder[0];
            CloudTrack.Result ct = holder[0];

            // 云端骨架是「更好」而不是「必须」：
            // 拿不到（没网 / 没换 token / 服务端挂了）就直接用本地几何生成，
            // 绝不能因为云端不可用就把用户拦在门外 —— 付费已经用 License 管住了。
            if (ct != null && !ct.ok && "daily_limit".equals(ct.error)) {
                showTooManyToday();
                return;
            }

            // 骨架：云端给一圈模板（米制偏移），本地按场地中心展开。
            // 拿到骨架就走骨架路径；没拿到（室内 / 旧服务端）退回本地几何。
            if (ct.tpl != null && !isTread && "track".equals(routeKind)) {
                r.skelEast = ct.tpl.east;
                r.skelNorth = ct.tpl.north;
                r.skelDrift = ct.tpl.drift;
            }

            if (isTread) {
                r.route = null;
                r.field = field;
            } else if ("track".equals(routeKind)) {
                r.route = null;
                r.field = field;
            } else {
                r.route = routePoints;
                r.loop = routeLoop;
                r.field = field;
            }

            Generator.Result res = Generator.generate(r);

            StringBuilder sb = new StringBuilder();
            sb.append(res.sportLabel).append("　").append(res.distKmText())
              .append("　").append(Generator.clock(r.durSec))
              .append("　").append(Generator.clock(r.paceSec)).append("/km\n");
            sb.append("采样点 ").append(res.points)
              .append("　圈数 ").append(res.laps).append("\n\n");

            if (res.avgSpm > 0) {
                sb.append("步频 ").append(fmt0(res.avgSpm)).append(" spm")
                  .append("　步数 ").append(Math.round(res.totalSteps))
                  .append("　步幅 ").append(fmt2(res.strideM)).append(" m\n");
                sb.append("触地 ").append(Math.round(res.gctMs)).append(" ms")
                  .append("　垂直振幅 ").append(fmt1(res.verticalOscCm)).append(" cm\n");
            }
            sb.append("卡路里 ").append(res.calories).append(" kcal\n\n");
            sb.append("每公里：\n");
            int i = 1;
            for (int k = 0; k < res.splits.size(); k++) {
                sb.append("  ").append(i++).append(" km　")
                  .append(Generator.clock(res.splits.get(k)[0])).append("\n");
                if (i > 10) { sb.append("  …\n"); break; }
            }

            // 第 3 层：写文件前检查（验前两层是否都走过）
            if (!Guard.canWriteFile(this)) {
                showNeedUnlock();
                return;
            }

            String saved = saveAll(res);
            Guard.finish();   // 第 4 层：重置令牌，防重放
            countToday();

            // 健康库写入：没有 Health Connect 的机型（国行华为/小米基本如此）静默跳过，
            // 不影响下面任何一行；也不弹任何提示。
            if (Hc.available(this)) Hc.write(this, res, null, null);
            sb.append("\n已保存到 Download：\n").append(saved);
            tvResult.setText(sb.toString());
            tvResult.setVisibility(View.VISIBLE);

            if (res.poly != null && res.poly.size() > 1) {
                trackView.setPoly(res.poly);
                trackView.setVisibility(View.VISIBLE);
            } else {
                trackView.setVisibility(View.GONE);
            }

            if (KeepApi.loggedIn(this) && autoUpload()) {
                uploadToKeep(res, saved);
            } else {
                showDoneSheet(res, saved);
            }
        } catch (Exception e) {
            toast(e.getMessage() == null ? String.valueOf(e) : e.getMessage());
        }
    }

    /**
     * 生成完成的确认面板：告诉他生成了什么、文件叫什么、存在哪，并给一条去导入的路。
     *
     * 「去 Keep 导入」这一条只能做到「把 Keep 打开」，不能直接把文件递进导入页：
     * Keep 的 AndroidManifest 里 action.SEND 声明 0 个、mimeType 声明 0 个，
     * KitDeviceAddActivity 与 KeepInProcessWebViewActivity 都没导出，
     * 外部应用既不能给它发文件，也不能直接拉起导入页。这是 Keep 那边的限制。
     */
    private void showDoneSheet(Generator.Result res, String saved) {
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("已生成 " + res.distKmText(), "数据已经存好了");

        TextView fileTv = new TextView(this);
        fileTv.setText(saved);
        fileTv.setTextSize(13);
        fileTv.setTypeface(Typeface.MONOSPACE);
        fileTv.setTextColor(Ui.ACCENT);
        fileTv.setBackground(Ui.roundStroke(Ui.CARD_HI, Ui.INPUT_STROKE, Ui.RADIUS_CTRL, 1, this));
        int p = dp(14);
        fileTv.setPadding(p, p, p, p);
        fileTv.setTextIsSelectable(true);
        s.view(fileTv);

        s.spacer(dp(10));
        s.text("已保存到手机 Download 文件夹（tcx / fit / gpx 三份）。");
        s.spacer(dp(4));
        s.text("想放到华为运动健康或小米运动健康：用它们的「我的路线 → 导入路线」挑 .gpx。"
                + "注意那边只把它当路线，不会生成一条运动记录 —— 这是它们的设计。");
        s.spacer(dp(6));
        s.text("导入 Keep：我的 → 右上角手表图标 → 添加装备与应用 → 滑到底「第三方应用与服务」"
                + "→ 运动数据文件导入 → 选择文件 → 从左侧菜单进 Downloads 挑这个文件。");

        s.spacer(dp(6));
        if (KeepApi.loggedIn(this)) {
            s.primary("写进 Keep 账号", new Runnable() {
                public void run() { s.dismiss(); uploadToKeep(res); }
            });
            s.text("写完 Keep 里就有这条记录了，不用再手动导入。");
        } else {
            s.primary("登录 Keep，一键写入", new Runnable() {
                public void run() { s.dismiss(); openKeepLogin(); }
            });
        }
        s.ghost("先存着，我自己去导入", new Runnable() { public void run() { s.dismiss(); } });
        s.ghost("自动导入 Keep（走无障碍）", new Runnable() {
            public void run() {
                KeepAuto.attach(MainActivity.this);
                if (KeepAuto.isReady()) {
                    // 服务实例在系统绑定它的那个进程里，界面这边拿不到引用，
                    // 所以不靠返回值，而是把「待办」写进 SharedPreferences，
                    // 服务自己轮询到就会开始走。
                    KeepAuto.arm(MainActivity.this);
                    openKeep();
                    s.dismiss();
                    toast("会自动走到导入页，文件你自己挑");
                    return;
                }
                s.dismiss();
                askA11y();
            }
        });
        s.show();
    }

    /**
     * 没有无障碍权限时的引导。
     *
     * 说清三件事：为什么要这个权限、开到哪一项、开完回来点哪个。
     * 不解释清楚的话，用户看到「无障碍」三个字直接退出去，还以为是病毒。
     */
    private void askA11y() {
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("需要开一个开关", "开了之后，点一下就能自动走到 Keep 的导入页");

        s.text("为什么要开：Keep 的导入页藏在它自己内部，别的应用没法直接把文件递进去，"
                + "也没法直接跳过去。系统唯一允许的做法是「无障碍服务」——它能看到屏幕上的按钮，"
                + "并替你点。");
        s.spacer(dp(8));
        s.text("它只做一件事：从 Keep 首页点到「运动数据文件导入」，然后停下，"
                + "文件由你自己挑。平时不读任何东西，不用时可以随时关掉。");
        s.spacer(dp(8));
        s.text("怎么开：下面的按钮会跳到系统设置，在「已下载的应用」里找到"
                + "「运动数据生成」，把「自动导入 Keep」打开。");

        s.spacer(dp(12));
        s.text("懒得开也行——下面第二个按钮只把 Keep 打开，菜单你自己点，"
                + "就多四五下。");

        s.ghost("只打开 Keep", new Runnable() {
            public void run() {
                openKeep();
                s.dismiss();
            }
        });
        s.primary("去开权限", new Runnable() {
            public void run() {
                s.dismiss();
                try {
                    startActivity(new Intent(
                            android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    toast("找到「运动数据生成」，打开里面的开关");
                } catch (Exception e) {
                    toast("打开设置失败，请手动进 设置 → 无障碍");
                }
            }
        });
        s.show();
    }

    /**
     * 把 Keep 拉到前台；没装就提示。
     *
     * 不能直接用 getLaunchIntentForPackage：Keep 的入口是 SplashActivity，
     * 但它给外部返回的 launch intent 指向的 Activity 在部分版本上不是导出的，
     * 发出去会被系统挡掉（现象是 Keep 起来了又被压回去）。
     * 这里直接点名 SplashActivity，失败的再退回包解析查启动页。
     */
    private void openKeep() {
        final String pkg = "com.gotokeep.keep";
        // getPackageInfo 在 API 33+ 对某个包名可能抛 NameNotFoundException，
        // 用它做「有没有装」的判据会误判成没装，直接静默返回。
        // 改成问 getLaunchIntentForPackage 能不能解析，解不出才算没装。
        boolean installed;
        try {
            installed = getPackageManager()
                    .getLaunchIntentForPackage(pkg) != null
                    || getPackageManager().getPackageInfo(pkg, 0) != null;
        } catch (Exception e) {
            installed = true;   // 探测失败就当装了，下面真跳失败会提示
        }
        if (!installed) {
            toast("这台手机没装 Keep");
            return;
        }

        // 只用显式 component，不带 ACTION_MAIN/CATEGORY_LAUNCHER 组合。
        // 那套组合会把 Intent 变成「解析启动页」的语义，解析出的结果在 Keep 上
        // 指向一个非导出的 Activity，发出去被系统挡掉，现象是 Keep 起来又被压回去。
        Intent i = new Intent();
        i.setClassName(pkg, pkg + ".splash.SplashActivity");

        // 先试点名入口，不行再走包管理器解析
        if (!tryStart(i)) {
            try {
                Intent r = getPackageManager().getLaunchIntentForPackage(pkg);
                if (r != null && tryStart(r)) {
                    toast("已打开 Keep，按提示进导入页");
                    return;
                }
            } catch (Exception ignored) {}
            toast("没能自动打开 Keep，请从桌面点开");
            return;
        }
        toast("已打开 Keep，按提示进导入页");
    }

    private boolean tryStart(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String saveAll(Generator.Result res) throws Exception {
        String base = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
                .format(new Date(res.startMs));
        StringBuilder names = new StringBuilder();
        if (res.tcx != null) names.append(saveOne(base + ".tcx", res.tcx.getBytes("UTF-8"))).append("\n");
        if (res.fit != null) names.append(saveOne(base + ".fit", res.fit)).append("\n");
        // GPX 只有带轨迹的记录才有意义；室内跑步没有坐标，不生成。
        // 它是给华为/小米运动健康「我的路线 → 导入路线」用的（那边只认 GPX 路线，不生成运动记录）。
        if (res.latlon != null && res.latlon.length > 1) {
            names.append(saveOne(base + ".gpx",
                    GpxOut.build(res, res.sportLabel + " " + res.distKmText()).getBytes("UTF-8"))).append("\n");
        }
        return names.toString().trim();
    }

    /**
     * 写一个文件到 Download。**返回磁盘上真实用的文件名。**
     *
     * 文件名用开跑时间生成（如 2026-11-21-06-30-00.tcx），同一个开跑时间
     * 生成第二次就会撞名。
     *
     * 撞名有两种表现，都要处理：
     *   · 某些机型抛 SQLiteConstraintException
     *   · 某些机型**静默改名**，不抛异常，但存下来的不是你要的名字
     *
     * 第二种情况：面板显示 xxx.tcx，磁盘实际是 xxx (1).tcx，
     * 用户按界面上那个名字去文件管理器找，找不到；就算找到，指向的是上一次的文件。
     *
     * 所以不能返回自己拼的名字，必须**从返回的 URI 反查真实名字**。
     */
    private String saveOne(String name, byte[] bytes) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";

            for (int attempt = 1; attempt <= 99; attempt++) {
                String tryName = attempt == 1 ? name : stem + " (" + attempt + ")" + ext;
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, tryName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri;
                try {
                    uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                } catch (Exception dup) {
                    // 撞名，换下一个序号
                    continue;
                }
                if (uri == null) continue;
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) throw new IllegalStateException("无法写入 Download");
                try { os.write(bytes); os.flush(); } finally { os.close(); }

                // 从 URI 反查真实文件名。MediaStore 可能静默改名，不能用 tryName。
                String real = queryDisplayName(uri);
                return (real != null && !real.isEmpty()) ? real : tryName;
            }
            throw new IllegalStateException("同名文件太多，改一下开跑时间再试");
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            try { fos.write(bytes); fos.flush(); } finally { fos.close(); }
            return name;
        }
    }

    /** 从 MediaStore 的 URI 反查磁盘上的真实文件名 */
    private String queryDisplayName(Uri uri) {
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    // ================= 状态保存 =================

    /**
     * 保存用户已经填好的表单。
     *
     * 为什么需要：切换明暗主题、兑换成功之后都会调 recreate() 重建 Activity，
     * 不保存的话所有输入框都回到默认值。
     *
     * 不保存时的实际表现：距离改 7.7 → 点深色 → 回到 5.5。
     * 付费流程正好踩在这条链上：填好表单 → 点生成被拦 → 付款 → 兑码 → 表单清空，
     * 用户得从头再填一遍。
     */
    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        try {
            out.putInt("page", page);
            out.putString("routeKind", routeKind);
            out.putString("fmt", fmt);
            out.putBoolean("usePace", usePace);
            out.putInt("trackLen", trackLen);
            out.putString("eDist", etDist != null ? etDist.getText().toString() : "");
            out.putString("ePaceMin", etPaceMin != null ? etPaceMin.getText().toString() : "");
            out.putString("ePaceSec", etPaceSec != null ? etPaceSec.getText().toString() : "");
            out.putString("eDurH", etDurH != null ? etDurH.getText().toString() : "");
            out.putString("eDurM", etDurM != null ? etDurM.getText().toString() : "");
            out.putString("eDate", etDate != null ? etDate.getText().toString() : "");
            out.putString("eTime", etTime != null ? etTime.getText().toString() : "");
            out.putString("tDist", etTreadDist != null ? etTreadDist.getText().toString() : "");
            out.putString("tPaceMin", etTreadPaceMin != null ? etTreadPaceMin.getText().toString() : "");
            out.putString("tPaceSec", etTreadPaceSec != null ? etTreadPaceSec.getText().toString() : "");
        } catch (Exception ignored) {}
    }

    /** 恢复表单 */
    private void restoreFormState(Bundle in) {
        if (in == null) return;
        try {
            if (in.containsKey("page")) page = in.getInt("page");
            if (in.containsKey("routeKind")) routeKind = in.getString("routeKind", "track");
            if (in.containsKey("fmt")) fmt = in.getString("fmt", "tcx");
            usePace = in.getBoolean("usePace", true);
            if (in.containsKey("trackLen")) trackLen = in.getInt("trackLen", 400);

            setText(etDist, in.getString("eDist", null));
            setText(etPaceMin, in.getString("ePaceMin", null));
            setText(etPaceSec, in.getString("ePaceSec", null));
            setText(etDurH, in.getString("eDurH", null));
            setText(etDurM, in.getString("eDurM", null));
            setText(etDate, in.getString("eDate", null));
            setText(etTime, in.getString("eTime", null));
            setText(etTreadDist, in.getString("tDist", null));
            setText(etTreadPaceMin, in.getString("tPaceMin", null));
            setText(etTreadPaceSec, in.getString("tPaceSec", null));
        } catch (Exception ignored) {}
    }

    private void setText(EditText et, String v) {
        if (et != null && v != null && !v.isEmpty()) et.setText(v);
    }

    // ================= 到期 / 更新 =================



    /** 一行「标题　值」的滚轮入口，长得像输入框，点开才是滚轮 */
    private Button wheelRow(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Ui.iosLabel());
        b.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(Ui.round(Ui.iosFill(), Ui.IOS_R_CTRL, this));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        int h = dp(48);
        b.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h);
        wlp.topMargin = dp(8);
        b.setLayoutParams(wlp);
        return b;
    }

    private static String pad2(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            return String.format(Locale.US, "%02d", v);
        } catch (Exception e) {
            return "00";
        }
    }

    /** 解锁入口：先说明，再给兑换码输入 */
    private void openUnlock() {
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("解锁 · 1 元永久", "一次付费，这台设备永久可用");

        s.text("点下面的「去购买」会打开爱发电的购买页，付完平台会自动发一串解锁码给你。"
                + "拿到码之后回到这里填进去。");
        s.spacer(dp(8));
        s.text("兑换需要联网一次（这枚码会登记到本机，一枚码一台设备，换机联系作者换签），"
                + "兑完就能离线用，不限时间。");

        s.spacer(dp(12));
        final EditText et = new EditText(this);
        et.setHint("粘贴或输入解锁码");
        et.setTextSize(15);
        et.setTextColor(Ui.TEXT);
        et.setHintTextColor(Ui.TEXT_FAINT);
        et.setBackground(Ui.roundStroke(Ui.CARD_HI, Ui.INPUT_STROKE, Ui.RADIUS_CTRL, 1, this));
        int p = dp(14);
        et.setPadding(p, dp(12), p, dp(12));
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        s.view(et);

        s.spacer(dp(10));
        final TextView status = Ui.hint(this, "");
        s.view(status);

        s.spacer(dp(14));
        s.ghost("去购买", new Runnable() {
            public void run() {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse(BuildConfig.UNLOCK_URL)));
                } catch (Exception e) {
                    copyToClipboard(BuildConfig.UNLOCK_URL);
                    toast("打不开浏览器，链接已复制");
                }
            }
        });
        s.primary("解锁", new Runnable() {
            public void run() {
                // 兑换码不做任何位数提示：用户看到的是「验证码无效」这一句，
                // 不告诉他到底是几位、是格式错了、还是已经被用掉。
                // 形态判定在 KeepFmt 里，规则不写在代码中（位数/连字符数/字符集都编码过）
                String code = KeepFmt.normalize(MainActivity.this, et.getText().toString().trim());
                if (code.isEmpty()) {
                    status.setText("验证码无效");
                    return;
                }
                status.setText("正在核销…");
                new Thread(new Runnable() {
                    public void run() {
                        // 本地先验签：签名不对就没必要浪费一个网络请求
                        final boolean sigOk = Redeem.verify(MainActivity.this, code);
                        // 再走云端核销：一码一用靠这一步
                        // 本地验签不过就不发请求：省一次网络，也不给批量试码留反馈
                        final RedeemCloud.Result r = sigOk
                                ? RedeemCloud.redeem(MainActivity.this, code)
                                : new RedeemCloud.Result(RedeemCloud.INVALID, "验证码无效");
                        // 核销通过之后、写解锁标记之前，先在服务端登记本机设备号：
                        // 一枚码只认第一台设备，换机器会被 409 拦下。
                        // 只有「兑换这一刻」联网；已经解锁的设备启动时不走这条路。
                        final License.Bind bind = r.code == RedeemCloud.OK
                                ? License.activateWithDevice(MainActivity.this, code)
                                : null;
                        runOnUiThread(new Runnable() {
                            public void run() {
                                if (bind != null && bind.ok()) {
                                    License.rememberCode(MainActivity.this,
                                            Redeem.codeHash(MainActivity.this, code));
                                    license = License.init(MainActivity.this);
                                    try { s.dismiss(); } catch (Exception ignored) {}
                                    toast("已解锁，永久可用");
                                    recreate();
                                } else if (bind != null) {
                                    // 设备登记没过：绑在别的机器上，或者这台设备连不上网。
                                    // 两种情况都不写解锁标记，点一次「解锁」还能重试。
                                    status.setText(bind.message());
                                } else if (r.code == RedeemCloud.NETWORK
                                        || r.code == RedeemCloud.BUSY) {
                                    // 网络/服务端问题跟码本身无关，单独说一句，别让人以为码坏了
                                    status.setText("现在连不上服务器，换个网络或过一会儿再试");
                                } else {
                                    // 格式对但云端不认（没卖过 / 已被用掉 / 被撤销）——统一一句话，
                                    // 不区分具体原因，避免帮着别人试码。
                                    status.setText("验证码无效");
                                }
                            }
                        });
                    }
                }).start();
            }
        });
        s.show();
    }

    private void checkAndUpdate() {
        final String sv = server();
        final Sheet s = Sheet.make(this);
        activeSheet = s;
        s.title("检查更新", Cloud.configured(sv) ? "正在查询…" : "正在打开下载页…");
        s.closeOnly();
        s.show();

        new Thread(new Runnable() {
            public void run() {
                Updater.Info info = null;
                if (Cloud.configured(sv)) info = Updater.check(sv, BuildConfig.VERSION_CODE);
                final String url = (info != null && info.url != null && !info.url.isEmpty())
                        ? info.url : BuildConfig.FALLBACK_DOWNLOAD_URL;
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { s.dismiss(); } catch (Exception ignored) {}
                        downloadAndInstall(url);
                    }
                });
            }
        }).start();
    }

    private void downloadAndInstall(final String url) {
        if (url == null || url.isEmpty()) { toast("没有下载地址"); return; }

        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle("正在下载新版");
        pd.setMessage("0%");
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();

        String name = "keepgen-" + BuildConfig.VERSION_NAME + "-update.apk";
        ApkDownloader.download(this, url, name, new ApkDownloader.Cb() {
            public void onProgress(int percent, long bytes) {
                if (percent >= 0) pd.setProgress(percent);
                pd.setMessage((bytes / 1024) + " KB");
            }
            public void onDone(final java.io.File apk) {
                try { pd.dismiss(); } catch (Exception ignored) {}
                installApk(apk);
            }
            public void onError(final String msg) {
                try { pd.dismiss(); } catch (Exception ignored) {}
                toast("下载失败：" + msg);
            }
        });
    }

    private void installApk(java.io.File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
                toast("允许安装后再点一次更新");
            } catch (Exception e) {
                toast("请手动允许安装未知来源");
            }
            return;
        }
        try {
            Uri uri = ApkProvider.uriFor(apk.getName());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("拉起安装器失败：" + e.getMessage());
        }
    }

    private void checkUpdateInBackground() {
        final String sv = server();
        if (!Cloud.configured(sv)) return;
        new Thread(new Runnable() {
            public void run() {
                final Updater.Info info = Updater.check(sv, BuildConfig.VERSION_CODE);
                if (!info.hasUpdate(BuildConfig.VERSION_CODE)) return;
                runOnUiThread(new Runnable() {
                    public void run() {
                        final Sheet s = Sheet.make(MainActivity.this);
        activeSheet = s;
                        s.title("有新版本 " + info.latest, "建议更新到最新版");
                        s.ghost("以后", new Runnable() { public void run() { s.dismiss(); } });
                        s.primary("应用内更新", new Runnable() {
                            public void run() {
                                s.dismiss();
                                downloadAndInstall(info.url);
                            }
                        });
                        s.show();
                    }
                });
            }
        }).start();
    }

    // ================= 工具 =================

    /**
     * 路线分享地址。固定用内置的，不再读用户填的值。
     *
     * 之前这里是「读设置里那个输入框」，用户不填就用不了短码。
     * 现在几百人几千人的量级，让用户配服务器不合理，直接指向公共存储。
     */
    private String server() {
        return BuildConfig.ROUTE_FEED_BASE;
    }

    private void copyToClipboard(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("code", s));
            toast("已复制");
        } catch (Exception e) {
            toast("复制失败");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String fmt0(double v) { return String.format(Locale.US, "%.0f", v); }
    private static String fmt1(double v) { return String.format(Locale.US, "%.1f", v); }
    private static String fmt2(double v) { return String.format(Locale.US, "%.2f", v); }

    private static double parse(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    private EditText input(String val, int type) {
        EditText e = new EditText(this);
        e.setText(val);
        e.setInputType(type);
        e.setTextSize(16);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.TEXT_FAINT);
        e.setBackground(Ui.round(Ui.iosFill(), Ui.IOS_R_CTRL, this));
        e.setTextColor(Ui.iosLabel());
        int p = dp(12);
        e.setPadding(p, dp(10), p, dp(10));
        e.setMinHeight(dp(46));
        return e;
    }

    private int dp(int v) { return Ui.dp(this, v); }

    /** iOS 风格的小胶囊按钮 */
    private void iosChip(Button b) {
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Ui.iosBlue());
        b.setBackground(Ui.round(Ui.iosFill(), Ui.IOS_R_CTRL, this));
        b.setMinHeight(dp(40)); b.setMinimumHeight(dp(40));
        b.setStateListAnimator(null);
        b.setElevation(0);
    }

    /** iOS 分段控件里的按钮初始态 */
    private void iosSegButton(Button b, boolean on) {
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMinHeight(dp(38)); b.setMinimumHeight(dp(38));
        b.setStateListAnimator(null);
        Ui.iosSegOn(this, b, on);
    }

    /** 轨迹预览 */
    static final class TrackView extends View {
        private List<double[]> poly;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        TrackView(android.content.Context c) { super(c); }

        void setPoly(List<double[]> list) { poly = list; invalidate(); }

        @Override
        protected void onDraw(Canvas canvas) {
            // 圆角卡片底
            android.graphics.Path bg = new android.graphics.Path();
            float r = Ui.dp(getContext(), Ui.IOS_R_CARD);
            bg.addRoundRect(0, 0, getWidth(), getHeight(), r, r, android.graphics.Path.Direction.CW);
            canvas.save();
            canvas.clipPath(bg);
            canvas.drawColor(Ui.iosCard());
            // 淡网格（像计时器表盘）
            Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
            grid.setColor(Ui.iosSeparator()); grid.setStrokeWidth(1f);
            float step = Ui.dp(getContext(), 28);
            for (float x = step; x < getWidth(); x += step) canvas.drawLine(x, 0, x, getHeight(), grid);
            for (float y = step; y < getHeight(); y += step) canvas.drawLine(0, y, getWidth(), y, grid);
            if (poly == null || poly.size() < 2) { canvas.restore(); return; }
            double lat0 = 0, lon0 = 0;
            for (int i = 0; i < poly.size(); i++) {
                lat0 += poly.get(i)[0]; lon0 += poly.get(i)[1];
            }
            lat0 /= poly.size(); lon0 /= poly.size();

            float minX = 1e9f, maxX = -1e9f, minY = 1e9f, maxY = -1e9f;
            float[] xs = new float[poly.size()], ys = new float[poly.size()];
            for (int i = 0; i < poly.size(); i++) {
                xs[i] = (float) ((poly.get(i)[1] - lon0) * Geo.M_PER_DEG_LON);
                ys[i] = (float) ((poly.get(i)[0] - lat0) * Geo.M_PER_DEG_LAT);
                if (xs[i] < minX) minX = xs[i];
                if (xs[i] > maxX) maxX = xs[i];
                if (ys[i] < minY) minY = ys[i];
                if (ys[i] > maxY) maxY = ys[i];
            }
            float pad = 20;
            float sw = Math.max(1, maxX - minX), sh = Math.max(1, maxY - minY);
            float scale = Math.min((getWidth() - pad * 2) / sw, (getHeight() - pad * 2) / sh);
            float ox = (getWidth() - sw * scale) / 2, oy = (getHeight() - sh * scale) / 2;

            android.graphics.Path path = new android.graphics.Path();
            for (int i = 0; i < xs.length; i++) {
                float px = ox + (xs[i] - minX) * scale;
                float py = getHeight() - (oy + (ys[i] - minY) * scale);
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(getContext(), 5));
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setShader(new LinearGradient(0, 0, getWidth(), getHeight(),
                    Ui.iosBlue(), Ui.iosBlue(), Shader.TileMode.CLAMP));
            canvas.drawPath(path, p);
            p.setShader(null);

            // 起点：白心蓝环；终点：实心蓝点
            float sx = ox + (xs[0] - minX) * scale;
            float sy = getHeight() - (oy + (ys[0] - minY) * scale);
            p.setStyle(Paint.Style.FILL); p.setColor(Ui.CARD);
            canvas.drawCircle(sx, sy, Ui.dp(getContext(), 9), p);
            p.setStyle(Paint.Style.STROKE); p.setColor(Ui.iosBlue());
            p.setStrokeWidth(Ui.dp(getContext(), 3));
            canvas.drawCircle(sx, sy, Ui.dp(getContext(), 9), p);
            int last = xs.length - 1;
            float ex = ox + (xs[last] - minX) * scale;
            float ey = getHeight() - (oy + (ys[last] - minY) * scale);
            p.setStyle(Paint.Style.FILL); p.setColor(Ui.iosBlue());
            canvas.drawCircle(ex, ey, Ui.dp(getContext(), 7), p);
            canvas.restore();
        }
    }
}
