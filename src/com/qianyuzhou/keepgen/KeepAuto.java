package com.qianyuzhou.keepgen;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 无障碍服务：替用户在 Keep 里一路点到「运动数据文件导入」。
 *
 * 为什么需要它：Keep 的导入页在它自己进程里，既没有对外入口，也不接收外部文件
 * （manifest 里 ACTION_SEND 和 mimeType 都是 0 个）。外部应用只能把 Keep 拉到前台，
 * 剩下的菜单得有人真的去点。无障碍服务是系统允许的「代点」手段。
 *
 * 工作方式：不是录死坐标。每一步都按**当前界面上真实存在的文字**去找控件，
 * 找不到就等界面变化再试，最多等一段时间。这样换机型、换 Keep 版本、界面改版
 * 都不至于立刻失效——找不到就停在那一步，并且告诉用户停在哪。
 *
 * 只做一件事：走完导航，把用户送到系统的文件选择器前面。
 * 选文件那一步不代劳——那是用户自己的选择，也避免误选。
 */
public class KeepAuto extends AccessibilityService {

    /** 服务实例。为 null 表示用户没开无障碍权限。 */
    private static KeepAuto live;

    /** 每一步要点的文字，按顺序。null 表示这一步只是等待界面稳定。 */
    private static final String[] STEPS = {
            "我的",                 // 底部 tab
            "智能硬件与数据来源",     // 我的页右上角设备图标（无障碍文字）
            "应用与服务",            // 装备面板 tab
            "添加装备与应用",         // 面板底部按钮
            "运动数据文件导入",       // 第三方应用与服务 里的入口
            "选择文件"               // 导入页底部按钮 → 会拉起系统文件选择器
    };

    /**
     * 永不点击的危险文案。
     *
     * 红队 2026-11-22 实测（attack-无障碍服务评估-t3.md）：造一个同名包
     * com.gotokeep.keep，用一个「下层整屏可点『立即支付 99 元』、上层盖一个
     * 不可点的『我的』」的界面，KeepAuto 会命中上层文字 → 找不到可点祖先 →
     * 退回「按文字中心发真手势」→ 坐标穿透到下层 → **真的点开了支付页**。
     *
     * 一道文案黑名单就能把这条链断掉：不管界面长什么样，只要要点的位置
     * 附近出现这些词，直接不点、停下、告诉用户。
     * 这是本文件里性价比最高的一行防御。
     */
    private static final String[] DANGER_WORDS = {
            "支付", "付款", "购买", "开通", "续费", "充值", "下单", "确认订单",
            "立即买", "去结算", "收银台", "钱包", "转账", "打赏", "赞助"
    };

    /** 当前走到第几步，-1 表示空闲 */
    private static int step = -1;
    /** 上一次点击是第几步、什么时候，用来防连点 */
    private static int lastClickedStep = -1;
    private static long lastClickAt = 0;
    private static long stepStartedAt = 0;
    /** 当前步骤已经尝试过的滚动次数，切步骤时归零 */
    private static int scrollTry = 0;
    private static final int MAX_SCROLL_TRIES = 6;
    /** 上一次 tick 是不是刚滚动过，用来避免滚动后立刻又滚 */
    private static boolean swiped = false;
    private static final long STEP_TIMEOUT_MS = 9000;
    private static final Handler H = new Handler(Looper.getMainLooper());

    /**
     * 无障碍服务是否已启用。
     *
     * 不能只看静态的 live 引用：服务可能在我们进程重启前就连上了，
     * 或者被系统重新绑定过，那时 live 是 null、但服务其实好好的，
     * 界面会误判成「没开权限」又弹一次引导。
     * 所以要同时问系统：当前启用的无障碍服务列表里有没有我们。
     */
    static boolean isReady() {
        return live != null || isEnabledInSettings();
    }

    private static android.content.Context appCtx;

    /** 界面启动时把 Context 交进来，之后不需要静态引用也能查设置 */
    static void attach(android.content.Context c) {
        if (c != null) appCtx = c.getApplicationContext();
    }

    private static android.content.Context ctx() {
        if (appCtx != null) return appCtx;
        try {
            // 界面还没来得及 attach 时兜底：拿 ActivityThread 里的应用上下文
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                appCtx = (android.content.Context) app;
            }
        } catch (Throwable ignored) {}
        return appCtx;
    }

    private static boolean isEnabledInSettings() {
        android.content.Context c = ctx();
        return c != null && enabled(c);
    }

    /**
     * 这个无障碍服务现在是不是真的开着。
     *
     * 两条路都走，因为系统里「开着」有两种写法：
     *   · 设置项 ENABLED_ACCESSIBILITY_SERVICES 里可能写成 "pkg/pkg.Class"，也可能写成 "pkg/.Class"；
     *     原来只匹配 "pkg.Class" 这一种，用 ".Class" 写法时就永远判成没开 —— 用户明明给了权限，
     *     界面还显示「去开启」，这就是那个 bug。
     *   · 更要紧的是 AccessibilityManager 的实时列表：它反映系统真的绑上了服务，不受写法影响。
     */
    static boolean enabled(android.content.Context c) {
        if (c == null) return false;
        try {
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager)
                            c.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                java.util.List<android.accessibilityservice.AccessibilityServiceInfo> l =
                        am.getEnabledAccessibilityServiceList(
                                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                if (l != null) {
                    for (android.accessibilityservice.AccessibilityServiceInfo i : l) {
                        String id = i.getId();          // 形如 "pkg/pkg.Class"
                        if (id != null && id.startsWith(c.getPackageName())) return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        try {
            String on = android.provider.Settings.Secure.getString(
                    c.getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (on == null) return false;
            String pkg = c.getPackageName();
            return on.contains(pkg + "/" + pkg + ".KeepAuto")
                    || on.contains(pkg + "/.KeepAuto")
                    || on.contains(pkg + ".KeepAuto");
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * 界面状态与服务不在同一处：无障碍服务由系统绑定，它的实例活在系统拉起的那份里，
     * 界面进程里的静态引用一直是 null。所以用 SharedPreferences 传「该干活了」这个信号，
     * 服务自己轮询。
     */
    private static final String PREFS = "keepauto";
    private static final String KEY_ARMED = "armed";

    /** 界面调用：下一个服务轮询周期开始自动导航 */
    static void arm(android.content.Context c) {
        try {
            c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .edit().putLong(KEY_ARMED, System.currentTimeMillis()).apply();
        } catch (Exception ignored) {}
    }

    private boolean consumeArmed() {
        if (appCtx == null) return false;
        try {
            android.content.SharedPreferences sp =
                    appCtx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
            long t = sp.getLong(KEY_ARMED, 0);
            if (t <= 0) return false;
            // 只认 30 秒内的信号，避免很久以前点的把这次也触发
            if (System.currentTimeMillis() - t > 30000) {
                sp.edit().remove(KEY_ARMED).apply();
                return false;
            }
            sp.edit().remove(KEY_ARMED).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void stop() {
        step = -1;
    }

    /** 界面在变但可能没有无障碍事件时，靠这个定时器接着推进 */
    private static final long POLL_MS = 700;

    private final Runnable poller = new Runnable() {
        public void run() {
            try {
                if (live == null) return;
                if (consumeArmed()) {
                    step = 0;
                    stepStartedAt = System.currentTimeMillis();
                }
                if (step >= 0) live.tick();
            } catch (Throwable t) {
                // 任何异常都不能打断轮询链，否则整个自动导航就静默死掉
            } finally {
                H.postDelayed(this, POLL_MS);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        live = this;
        appCtx = getApplicationContext();
        H.removeCallbacks(poller);
        H.postDelayed(poller, POLL_MS);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        live = null;
        step = -1;
        H.removeCallbacks(poller);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        live = null;
        step = -1;
        H.removeCallbacks(poller);
        super.onDestroy();
    }

    @Override
    public void onInterrupt() { step = -1; }

    /**
     * 事件只用来「唤醒」，绝不动计时。
     *
     * 曾经在这里把 stepStartedAt 往后推，想让界面变动时多等一会儿；
     * 结果是每来一个事件就续命一次，The timeout 永远不触发，
     * 步骤卡在第 0 步无限重试（日志里反复出现「找到 我的」）。
     * 计时归 tick() 独占维护。
     */
    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent e) {
        if (step >= 0 && live != null) {
            H.post(new Runnable() {
                public void run() { if (live != null && step >= 0) live.tick(); }
            });
        }
    }

    /** 上一轮 tick 的时间，用来限制推进频率，避免事件风暴里狂点 */
    private static long lastTickAt = 0;
    private static final long MIN_TICK_GAP_MS = 500;
    /** 上一次滚动的时间；两次滚动之间必须留出动画时间 */
    private static long lastScrollAt = 0;
    private static final long SCROLL_COOLDOWN_MS = 1200;

    /** 尝试当前这一步 */
    private void tick() {
        if (step < 0 || step >= STEPS.length) { step = -1; return; }
        long now = System.currentTimeMillis();
        if (now - lastTickAt < MIN_TICK_GAP_MS) return;
        lastTickAt = now;

        // 已经切到系统文件选择器就收工：后面该用户自己挑文件
        if (isDocumentsUi()) {
            step = -1;
            toast("已到选择文件这步，挑刚才生成的那个文件就行");
            return;
        }

        // 只认 Keep 的界面。当前还在本应用或别的应用时不许乱点乱滚——
        // 之前就是没这道闸，第 1 步在自家界面上找不到目标，把本页滚了好几屏。
        if (!isKeepFront()) {
            if (System.currentTimeMillis() - stepStartedAt > STEP_TIMEOUT_MS) {
                step = -1;
                toast("没等到 Keep 界面，请手动点开后再试");
            }
            return;
        }

        String want = STEPS[step];
        AccessibilityNodeInfo target = findByText(want);

        // 找不到就在当前页往下滚一段再找。
        // Keep 的「运动数据文件导入」在列表最底下，不滚根本进不了屏幕，
        // 而节点存在、只是没渲染时 isVisibleToUser 也会是 false。
        // 滚动之间要留冷却：手势动画没走完就接着滚，
        // 界面节点还停在旧位置，会连续滚好几屏把目标滚过头。
        if (target == null && scrollTry < MAX_SCROLL_TRIES
                && now - lastScrollAt > SCROLL_COOLDOWN_MS) {
            scrollTry++;
            swiped = true;
            lastScrollAt = now;
            swipeUp();
            return;
        }

        if (target != null) {
            boolean ok = clickNode(target);
            try { target.recycle(); } catch (Exception ignored) {}
            if (ok) {
                step++;
                stepStartedAt = System.currentTimeMillis();
                scrollTry = 0;
                swiped = false;
                lastScrollAt = 0;   // 换步骤后允许立刻滚
                return;
            }
        }

        // 这一步还没出现，等界面变化；超时就报停在哪
        if (System.currentTimeMillis() - stepStartedAt > STEP_TIMEOUT_MS) {
            final String where = want;
            step = -1;
            toast("自动导入停在「" + where + "」，请手动点一下");
            return;
        }
    }

    /**
     * 当前前台是不是**真正的** Keep。
     *
     * 只看包名不够。红队 2026-11-22 实测：只要装一个包名叫 com.gotokeep.keep 的
     * 应用（不需要任何权限、不需要真 Keep），就能进这个白名单，
     * 然后 KeepAuto 会把点击灌进攻击者的界面。
     *
     * 所以还要比**签名**：真 Keep 的签名证书是固定的，
     * 攻击者造不出同签名的包（除非拿到 Keep 的私钥）。
     */
    private boolean isKeepFront() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            CharSequence pkg = root.getPackageName();
            root.recycle();
            if (pkg == null || !"com.gotokeep.keep".contentEquals(pkg)) return false;
            return hasKeepSignature();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 前台那个 com.gotokeep.keep 是不是真 Keep（签名比对）。
     *
     * 真 Keep 的签名证书哈希在下面写死。第一次装上并验证后填进来；
     * 为空时**不放行**（宁可让自动导入不工作，也不能替用户点错东西）。
     */
    private static final String KEEP_CERT_SHA256 = BuildConfig.KEEP_CERT_SHA256;

    private long certCheckedAt = 0;
    private boolean certResult = false;

    private boolean hasKeepSignature() {
        // 结果缓存 30 秒，避免每次 tick 都查一遍包管理器
        long now = System.currentTimeMillis();
        if (now - certCheckedAt < 30000) return certResult;
        certCheckedAt = now;
        certResult = false;
        if (KEEP_CERT_SHA256 == null || KEEP_CERT_SHA256.isEmpty()) {
            // 没配预期签名：不放行。用户会看到「没等到 Keep 界面」，而不是被误导点击。
            return false;
        }
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(
                    "com.gotokeep.keep",
                    android.os.Build.VERSION.SDK_INT >= 28
                            ? android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                            : android.content.pm.PackageManager.GET_SIGNATURES);
            byte[] cert = firstCertBytes(pi);
            if (cert == null) return false;
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(cert);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02X", b));
            certResult = sb.toString().equalsIgnoreCase(KEEP_CERT_SHA256);
            return certResult;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static byte[] firstCertBytes(android.content.pm.PackageInfo pi) {
        try {
            android.content.pm.Signature[] arr;
            if (android.os.Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
                arr = pi.signingInfo.hasMultipleSigners()
                        ? pi.signingInfo.getApkContentsSigners()
                        : pi.signingInfo.getSigningCertificateHistory();
            } else {
                arr = pi.signatures;
            }
            return (arr == null || arr.length == 0) ? null : arr[0].toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isDocumentsUi() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            CharSequence pkg = root.getPackageName();
            root.recycle();
            return pkg != null && "com.google.android.documentsui".contentEquals(pkg);
        } catch (Exception e) {
            return false;
        }
    }

    /** 在当前窗口里按可见文字找控件；找不到返回 null */
    private AccessibilityNodeInfo findByText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;

        List<AccessibilityNodeInfo> all = new ArrayList<AccessibilityNodeInfo>();
        collect(root, all);

        AccessibilityNodeInfo fallback = null;
        for (AccessibilityNodeInfo n : all) {
            if (!isVisible(n)) continue;
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            String ts = t == null ? "" : t.toString().trim();
            String ds = d == null ? "" : d.toString().trim();
            // 必须全等。用包含匹配会误中「我的课程」「我的活动」这些开头的项，
            // 实测点「我的」时选到了「我的课程」，直接跳进课程页。
            boolean exact = ts.equals(text) || ds.equals(text);
            // 允许「标签 + 少量修饰」的近似，但前缀不同不算
            boolean near = !exact && (ts.startsWith(text + " ") || ts.endsWith(" " + text)
                    || ds.startsWith(text + " ") || ds.endsWith(" " + text));
            if (!exact && !near) continue;
            // 全等的优先；其次可点的优先
            if (exact && n.isClickable()) { root.recycle(); return n; }
            if (exact && fallback == null) fallback = n;
        }
        if (fallback != null) {
            AccessibilityNodeInfo p = clickableAncestor(fallback);
            if (p != null) { root.recycle(); return p; }
            root.recycle();
            return fallback;
        }
        root.recycle();
        return null;
    }

    /**
     * 收集一棵界面树里的节点。
     *
     * 必须有上限。红队（A-2 附带）指出这里原本无深度、无数量限制：
     * Keep 的界面树可以很深，理论上能栈溢出或吃满内存。
     * 一个界面真正需要的节点远不到这个数字，超出的直接丢弃——
     * 丢掉的只可能是极端深的装饰节点，不影响按键。
     */
    private static final int MAX_DEPTH = 60;
    private static final int MAX_NODES = 3000;
    private int collected;

    private void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        collect(n, out, 0);
    }

    private void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > MAX_DEPTH || out.size() >= MAX_NODES) return;
        out.add(n);
        int kids = n.getChildCount();
        for (int i = 0; i < kids; i++) {
            if (out.size() >= MAX_NODES) return;
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) collect(c, out, depth + 1);
        }
    }

    private boolean isVisible(AccessibilityNodeInfo n) {
        if (!n.isVisibleToUser()) return false;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r.width() > 0 && r.height() > 0;
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 5 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    /**
     * 点一个控件。
     *
     * 三种手段依次上，因为 Keep 的控件层级不统一：
     * 1. 节点自己可点 → 直接 performAction
     * 2. 节点不可点，用真实手势点它的中心
     * 3. 它的位置若被父容器吞掉（文字挂在子 View、点击区在父 View），
     *    往上找可点的祖先，点祖先中心
     *
     * 只信 performAction 的返回值不够：Keep 有些按钮返回 true 但其实没响应，
     * 所以手势点击也一并做一次作为兜底。
     */
    private boolean clickNode(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        try { n.getBoundsInScreen(r); } catch (Exception ignored) {}

        // 0. 两道拦截：
        //    a) 区域大得不像按钮（整屏可点 = 典型攻击布局）
        //    b) 目标附近出现支付类文案
        // 任一命中就停下，不点，告诉用户。红队实测过「点开 99 元支付页」。
        if (looksLikeTrap(r) || nearDangerWord(r)) {
            step = -1;
            toast("Keep 界面跟预期不一样，已停下，请手动操作");
            return false;
        }

        // 防连点：同一步 1.5 秒内不重复点。
        // 红队实测「trap 布局」会让一次 armed 连点 7 次。
        long now = System.currentTimeMillis();
        if (step == lastClickedStep && now - lastClickAt < 1500) return false;
        lastClickedStep = step;
        lastClickAt = now;

        try {
            if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        } catch (Exception ignored) {}

        // 真手势点是最后手段：不管可点性，按文字自身所在位置点下去。
        // Keep 的「添加装备与应用」这类按钮，文字在子 View、点击区在父 View，
        // 只对父 View performAction 无效；按文字中心发手势才点得动。
        if (r.width() > 0 && r.height() > 0 && tap(r.centerX(), r.centerY())) {
            return true;
        }

        // 祖先兜底：只往上找 1 层，并且点之前要重新过一遍危险检查。
        //
        // 原来找 4 层，红队 2026-11-22（A-2）指出三个缺口：
        //   1. 黑名单是关键词依赖的，「月卡 ¥9」「升级」「会员中心」都躲得过
        //   2. nearDangerWord 只比目标 rect ±60px，而祖先中心可能差 300px
        //   3. 每层都重新 collect 全树，成本高
        // 现在收到 1 层，并且对「祖先的实际点击位置」重新做一次危险检查——
        // 这样即使要点的祖先中心落在支付按钮上，也会被拦下来。
        AccessibilityNodeInfo p = n.getParent();
        if (p != null) {
            Rect pr = new Rect();
            p.getBoundsInScreen(pr);
            if (pr.width() > 0 && !nearDangerWord(pr) && tap(pr.centerX(), pr.centerY())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这块区域大到不像正常按钮吗。
     *
     * 红队（A-2）指出黑名单是关键词依赖的，「月卡 ¥9」「升级」「会员中心」
     * 这些词都不在名单里，光靠加词永远追不上。
     * 换个角度：**整屏都可点**本身就是攻击特征——正常 App 的按钮不会占满屏幕。
     *
     * 判据：可点区域面积超过屏幕的 22%，或者高度超过屏幕 40%，
     * 就认为不是正常按钮，不点。
     * 依据：本应用要点的 6 个目标里，最大的是「添加装备与应用」（约 15% 宽高占比）。
     */
    private boolean looksLikeTrap(Rect r) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            double screen = (double) dm.widthPixels * dm.heightPixels;
            double area = (double) r.width() * r.height();
            if (area > screen * 0.22) return true;
            if (r.height() > dm.heightPixels * 0.40) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 目标区域附近是否出现危险文案。
     *
     * 做法：遍历当前窗口，找出所有带危险词的可见节点，判断它们的屏幕范围
     * 跟要点的位置有没有重叠（含上下各留 60px 余量，覆盖「按钮在文字下方」的布局）。
     *
     * 为什么不是只看目标节点自己的文字：红队的攻击布局是
     * 上层放「我的」、下层放「立即支付」，目标节点文字是干净的，
     * 只有看周边才能发现下面藏着什么。
     */
    private boolean nearDangerWord(Rect target) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> all = new ArrayList<AccessibilityNodeInfo>();
        try { collect(root, all); } catch (Exception ignored) {}
        boolean danger = false;
        for (AccessibilityNodeInfo n : all) {
            if (!isVisible(n)) continue;
            String t = n.getText() == null ? "" : n.getText().toString();
            String d = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
            String s = t + " " + d;
            boolean hasWord = false;
            for (String w : DANGER_WORDS) {
                if (s.contains(w)) { hasWord = true; break; }
            }
            if (!hasWord) continue;
            Rect nr = new Rect();
            n.getBoundsInScreen(nr);
            if (nr.bottom < target.top - 60 || nr.top > target.bottom + 60) continue;
            danger = true;
            break;
        }
        root.recycle();
        return danger;
    }

    /** 在屏幕中部往上滑一段（手指从下往上，内容往上走） */
    private void swipeUp() {
        if (Build.VERSION.SDK_INT < 24) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        float x = w / 2f;
        Path p = new Path();
        p.moveTo(x, h * 0.72f);
        p.lineTo(x, h * 0.38f);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 260))
                .build();
        dispatchGesture(g, null, null);
    }

    /** 模拟一次点击（需要 canPerformGestures） */
    private boolean tap(int x, int y) {
        if (Build.VERSION.SDK_INT < 24) return false;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 40))
                .build();
        return dispatchGesture(g, null, null);
    }

    private void toast(final String msg) {
        H.post(new Runnable() {
            public void run() {
                android.widget.Toast.makeText(getApplicationContext(), msg,
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
    }
}
