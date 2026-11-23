package com.qianyuzhou.keepgen;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Health Connect 权限引导页。极简：三行说明 + 一个按钮，不连网、不读数据、不做别的。
 *
 * <h3>它同时承担两个角色（manifest 里要各注册一次，见交付说明）</h3>
 * <ol>
 *   <li><b>权限说明页</b>：HC 的授权界面（Health Home → 应用 → 本应用 → 权限）会把
 *       {@code android.health.connect.action.SHOW_PERMISSIONS_RATIONALE} 指向这里。
 *       没有这个 intent-filter，系统在 HC 里根本列不出本应用，用户想给权限都找不到入口。</li>
 *   <li><b>权限用途页</b>：系统的「权限使用情况」页面通过
 *       {@code android.intent.action.VIEW_PERMISSION_USAGE}（category=HEALTH_PERMISSIONS）
 *       打开这里，用来回答「这个 App 为什么要健康权限」。所以它必须 exported，
 *       并且因为会暴露给任意应用拉起，manifest 上要用 {@code START_VIEW_PERMISSION_USAGE} 权限兜住。</li>
 * </ol>
 *
 * <h3>为什么页面本身要能「什么都不做就退出」</h3>
 * 国行无 GMS 机型没有 HC，用户可能永远到不了这个页面；能到这里的都是有 HC 的机器，
 * 但用户依然有权拒绝授权。拒绝之后 App 照常生成运动数据（只是不往健康库里写），
 * 所以这一页不能有「必须授权才能继续」的强制逻辑，也没有任何回传结果——
 * 授权状态由 {@link Hc#hasWritePermission} 下次写入时自然体现。
 */
public class HcPermActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.applyTheme(this);
        buildUi();
    }

    @Override
    protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        // 系统可能复用同一个实例再带一次 VIEW_PERMISSION_USAGE 进来，页面内容一样，不用重建。
        setIntent(it);
    }

    private void buildUi() {
        int pad = Ui.dp(this, 20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("写入健康数据");
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.TEXT);
        root.addView(title);

        // 三行说明，顺序就是用户想知道的三件事：为什么要、去哪儿给、不给会怎样。
        addLine(root, "为什么要这个权限",
                "生成跑步记录后，可以把这一条直接写进系统的健康数据库，"
              + "让其他健康类应用（比如手表 / 体重 / 运动汇总）也能读到这次跑步，"
              + "不用再手动导一次文件。");
        addLine(root, "去哪开",
                "点下面的按钮进 Health Connect（中文机型里可能叫「健康数据平台」）。"
              + "进去后找「应用」→「运动数据生成」，把「运动」「距离」「卡路里」「速度」「步数」"
              + "和「运动轨迹」全部打开。");
        addLine(root, "不开也能用",
                "授权是可选的。不授权时 App 只把数据存成本地文件（Download 文件夹里的 tcx / fit），"
              + "原有流程完全不受影响，也不会再弹任何提示。");

        Button go = new Button(this);
        go.setText("去开启权限");
        go.setAllCaps(false);
        go.setTextSize(15);
        go.setTextColor(Ui.ON_ACCENT);
        go.setBackground(Ui.round(Ui.ACCENT, Ui.RADIUS_CTRL, this));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, Ui.H_PRIMARY));
        glp.topMargin = Ui.dp(this, 22);
        go.setLayoutParams(glp);
        go.setOnClickListener(v -> openSettings());
        root.addView(go);

        // 返回：给一个明确的出口，避免用户以为退不出去（HC 里点进来时没有系统返回栈）。
        Button back = new Button(this);
        back.setText("先不开启，直接返回");
        back.setAllCaps(false);
        back.setTextSize(14);
        back.setTextColor(Ui.ACCENT);
        back.setBackgroundColor(0x00000000);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, Ui.H_SECOND));
        blp.topMargin = Ui.dp(this, 6);
        back.setLayoutParams(blp);
        back.setOnClickListener(v -> finish());
        root.addView(back);

        TextView foot = new TextView(this);
        foot.setText("状态：" + (Hc.available(this)
                ? (Hc.hasWritePermission(this) ? "已授权，可以写入" : "未授权")
                : "这台设备没有 Health Connect，本页可忽略"));
        foot.setTextSize(12);
        foot.setTextColor(Ui.TEXT_FAINT);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = Ui.dp(this, 14);
        foot.setLayoutParams(flp);
        root.addView(foot);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.addView(root);
        setContentView(sv);
    }

    private void addLine(LinearLayout root, String head, String body) {
        TextView h = new TextView(this);
        h.setText(head);
        h.setTextSize(14.5f);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(Ui.TEXT);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Ui.dp(this, 18);
        h.setLayoutParams(hlp);
        root.addView(h);

        TextView b = new TextView(this);
        b.setText(body);
        b.setTextSize(13.5f);
        b.setTextColor(Ui.TEXT_DIM);
        b.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 5);
        b.setLayoutParams(blp);
        root.addView(b);
    }

    /**
     * 跳 HC 的首页设置。
     *
     * <p>为什么不用 {@code HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS}：
     * 那个 action 要求调用方持有 HEALTH_PERMISSIONS 类别并被 HC 自己解析，
     * 从普通 App 直接拉起在部分机型上直接被拒；
     * 而 {@code action.HEALTH_HOME_SETTINGS} 是 HC 官方给出的「打开 HC 设置」入口，兼容性最好。
     *
     * <p>三级降级（全程静默，不弹错误框）：
     * HC 设置页 → 系统设置首页 → 什么都不做。用户看到的结果最多是「点了没反应」，
     * 但从这里出去弹个「找不到页面」只会让人更困惑——这是权限引导页，不是功能入口。
     */
    private void openSettings() {
        if (tryStart(new Intent(Hc.ACTION_HEALTH_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return;
        // 部分 ROM 只认包名，直接把 HC 的设置页当包详情页打开
        if (tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:com.google.android.apps.healthdata")))) return;
        if (tryStart(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return;
        // 三级全失败：落一行日志就好，页面留着让用户自己返回
        Hc.log(this, "perm-page:no-settings-target");
    }

    private boolean tryStart(Intent it) {
        try {
            startActivity(it);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (Throwable t) {
            // SecurityException 之类也一并降级，别让引导页崩掉
            Hc.log(this, "perm-page:start-fail " + t);
            return false;
        }
    }

}
