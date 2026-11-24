package com.keepgen.hulibuld;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 路线库。每人最多 3 条，满了必须先删一条。
 *
 * 两层存储：
 *   1. 本地库（SharedPreferences 里的 JSON）—— 永远可用，不联网
 *   2. 云端（可选）—— 在设置里填了服务端地址才启用，用于六位邀请码跨设备共享
 *
 * 一条路线的结构：
 *   { "id", "code", "name", "points":[[lat,lon],...], "loop":bool,
 *     "lengthM", "createdAt", "source", "remote":bool }
 *
 * code 是六位邀请码，字符集去掉易混的 0/O/1/I/L，剩 31 个字符。
 */
final class RouteStore {

    /** 本地路线库不设上限 */
    static final int MAX_LOCAL = Integer.MAX_VALUE;
    /** 上传到云端（联网可搜）的路线最多 3 条 */
    static final int MAX_UPLOAD = 3;
    private static final String PREF = "route_store";
    private static final String KEY_LIST = "routes";

    // 字母码去掉 0 O 1 I L，避免口头/手抄出错
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    /** 内置默认路线的邀请码。安宁操场就是这条，可删可换。 */
    static final String DEFAULT_CODE = "114514";

    /**
     * 合法邀请码：6 位字母码，或 6 位纯数字。
     * 纯数字单独开一条通道，因为 114514 这种全数字码更顺口。
     */
    static boolean validCode(String c) {
        if (c == null || c.length() != 6) return false;
        boolean allDigit = true;
        boolean allAlpha = true;
        for (int i = 0; i < 6; i++) {
            char ch = c.charAt(i);
            if (ch < '0' || ch > '9') allDigit = false;
            if (ALPHABET.indexOf(ch) < 0) allAlpha = false;
        }
        return allDigit || allAlpha;
    }

    private final Context ctx;

    RouteStore(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    // ---- 邀请码 ----------------------------------------------------------

    /** 生成一个六位码；碰撞由调用方用 exists() 检查 */
    static String newCode(java.util.Random rnd) {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * 规整用户输入：去空格连字符、转大写。
     * 纯数字码原样保留（114514 就是 114514）；混了字母的才做 O→0、I/L→1 的纠正，
     * 但只有当整串都是数字型时才替换，避免把正常的字母码改坏。
     */
    static String normalizeCode(String raw) {
        if (raw == null) return "";
        String s = raw.toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (s.length() != 6) return s;
        boolean digitsOnly = s.matches("[0-9]{6}");
        if (digitsOnly) return s;
        // 含字母：把常见的误抄还原（0 -> O，1 -> I，L -> I 只在第 2 位以后不处理）
        return s.replace('0', 'O');
    }

    // ---- 本地库 ----------------------------------------------------------

    static final class Route {
        String id;
        String code;        // 六位邀请码
        String name;
        List<double[]> points;
        boolean loop;
        double lengthM;
        long createdAt;
        String source;      // track / custom / link / code / builtin
        boolean remote;     // 是否来自云端（别人的路线）
        boolean uploaded;   // 是否已上传到云端，占一条上传配额

        String shortInfo() {
            return String.format(java.util.Locale.US, "%s · %d 点 · 长 %.0f m%s",
                    name == null ? "未命名" : name,
                    points == null ? 0 : points.size(),
                    lengthM,
                    loop ? " · 环线" : " · 折返");
        }
    }

    List<Route> list() {
        String json = prefs().getString(KEY_LIST, "");
        return parse(json);
    }

    /**
     * 首次启动时装一条内置路线（安宁操场，码 114514）。
     * 只装一次：装过就写个标记，用户删了不会再塞回来。
     * 这条不占用户的三条配额——它是「出厂自带」，用户删掉就释放名额。
     */
    void seedOnce() {
        SharedPreferences sp = prefs();
        if (sp.getBoolean("seeded", false)) return;
        sp.edit().putBoolean("seeded", true).apply();

        // 用户已经删过默认路线的话不再补
        if (sp.getBoolean("defaultRemoved", false)) return;

        Route r = new Route();
        r.id = "builtin-" + DEFAULT_CODE;
        r.code = DEFAULT_CODE;
        r.name = "安宁校区 400m 操场";
        r.points = Loop.roundedRect(24.8991247, 102.43281125, 148.7, 84.39, 36.80, 240);
        r.loop = true;
        r.lengthM = RouteIO.lengthM(r.points);
        r.createdAt = 0;    // 排在最下面，不挤占用户自己的
        r.source = "builtin";

        List<Route> all = list();
        for (Route x : all) if (DEFAULT_CODE.equals(x.code)) return;
        all.add(r);
        save(all);
    }

    /** 删内置路线时留个标记，别下次启动又装回来 */
    boolean isBuiltin(String code) {
        return DEFAULT_CODE.equals(code);
    }

    void markDefaultRemoved() {
        prefs().edit().putBoolean("defaultRemoved", true).apply();
    }

    /** 用户自己的路线条数（不含内置） */
    int userCount() {
        int n = 0;
        for (Route r : list()) {
            if (!isBuiltin(r.code)) n++;
        }
        return n;
    }

    /** 已上传到云端的条数（占 3 条配额） */
    int uploadedCount() {
        int n = 0;
        for (Route r : list()) {
            if (r.uploaded) n++;
        }
        return n;
    }

    /** 还能上传几条 */
    int uploadQuotaLeft() {
        return Math.max(0, MAX_UPLOAD - uploadedCount());
    }

    int count() { return list().size(); }

    boolean exists(String code) {
        for (Route r : list()) {
            if (code.equalsIgnoreCase(r.code)) return true;
        }
        return false;
    }

    /** 新增一条；超过 3 条时抛异常，让界面提示先删 */
    Route add(Route r) {
        List<Route> all = list();
        // 本地库不设上限，直接放行
        if (r.id == null) r.id = UUID.randomUUID().toString();
        if (r.code == null || !validCode(r.code)) {
            java.util.Random rnd = new java.util.Random();
            String c;
            int guard = 0;
            do {
                c = newCode(rnd);
                guard++;
                if (guard > 200) break;
            } while (exists(c));
            r.code = c;
        }
        if (r.createdAt == 0) r.createdAt = System.currentTimeMillis();
        all.add(r);
        save(all);
        return r;
    }

    /** 更新（例如从云端拉下来后改成 remote=true） */
    void update(Route r) {
        List<Route> all = list();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(r.id)) {
                all.set(i, r);
                save(all);
                return;
            }
        }
    }

    /** 按码删除 */
    boolean removeByCode(String code) {
        List<Route> all = list();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).code.equalsIgnoreCase(code)) {
                all.remove(i);
                save(all);
                if (isBuiltin(code)) markDefaultRemoved();
                return true;
            }
        }
        return false;
    }

    /** 按 id 删除 */
    boolean removeById(String id) {
        List<Route> all = list();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                String code = all.get(i).code;
                all.remove(i);
                save(all);
                if (isBuiltin(code)) markDefaultRemoved();
                return true;
            }
        }
        return false;
    }

    Route byCode(String code) {
        for (Route r : list()) {
            if (r.code.equalsIgnoreCase(code)) return r;
        }
        return null;
    }

    private void save(List<Route> all) {
        prefs().edit().putString(KEY_LIST, toJson(all)).apply();
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---- 手写 JSON（不引第三方库） ---------------------------------------

    private static String toJson(List<Route> all) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("[");
        for (int i = 0; i < all.size(); i++) {
            Route r = all.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"id\":\"").append(esc(r.id)).append("\"");
            sb.append(",\"code\":\"").append(esc(r.code)).append("\"");
            sb.append(",\"name\":\"").append(esc(r.name == null ? "" : r.name)).append("\"");
            sb.append(",\"loop\":").append(r.loop);
            sb.append(",\"lengthM\":").append(r.lengthM);
            sb.append(",\"createdAt\":").append(r.createdAt);
            sb.append(",\"source\":\"").append(esc(r.source == null ? "" : r.source)).append("\"");
            sb.append(",\"remote\":").append(r.remote);
            sb.append(",\"points\":[");
            if (r.points != null) {
                for (int k = 0; k < r.points.size(); k++) {
                    if (k > 0) sb.append(",");
                    double[] p = r.points.get(k);
                    sb.append("[").append(fixed(p[0], 7)).append(",")
                      .append(fixed(p[1], 7)).append("]");
                }
            }
            sb.append("]}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String fixed(double v, int n) {
        return String.format(java.util.Locale.US, "%." + n + "f", v);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static List<Route> parse(String json) {
        List<Route> out = new ArrayList<Route>();
        if (json == null || json.length() < 3) return out;

        int i = 0, n = json.length();
        while (i < n) {
            int start = json.indexOf('{', i);
            if (start < 0) break;
            int end = matchBrace(json, start);
            if (end < 0) break;
            String obj = json.substring(start, end + 1);
            Route r = parseOne(obj);
            if (r != null) out.add(r);
            i = end + 1;
        }
        Collections.sort(out, new Comparator<Route>() {
            public int compare(Route a, Route b) {
                return Long.compare(b.createdAt, a.createdAt);
            }
        });
        return out;
    }

    private static int matchBrace(String s, int start) {
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
            } else {
                if (c == '"') inStr = true;
                else if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) return i; }
            }
        }
        return -1;
    }

    private static Route parseOne(String obj) {
        Route r = new Route();
        r.id = str(obj, "id");
        r.code = str(obj, "code");
        r.name = str(obj, "name");
        r.loop = bool(obj, "loop");
        r.remote = bool(obj, "remote");
        r.uploaded = bool(obj, "uploaded");
        r.source = str(obj, "source");
        r.lengthM = num(obj, "lengthM");
        r.createdAt = (long) num(obj, "createdAt");
        if (r.id == null || r.code == null) return null;

        int ps = obj.indexOf("\"points\":[");
        if (ps >= 0) {
            int from = ps + "\"points\":[".length();
            int depth = 1, j = from;
            boolean inStr = false;
            while (j < obj.length() && depth > 0) {
                char c = obj.charAt(j);
                if (inStr) { if (c == '\\') j++; else if (c == '"') inStr = false; }
                else if (c == '"') inStr = true;
                else if (c == '[') depth++;
                else if (c == ']') depth--;
                j++;
            }
            String arr = obj.substring(from, Math.max(from, j - 1));
            List<double[]> pts = new ArrayList<double[]>();
            int k = 0;
            while (k < arr.length()) {
                int a = arr.indexOf('[', k);
                if (a < 0) break;
                int b = arr.indexOf(']', a);
                if (b < 0) break;
                String pair = arr.substring(a + 1, b);
                String[] xy = pair.split(",");
                if (xy.length == 2) {
                    try {
                        pts.add(new double[]{Double.parseDouble(xy[0].trim()),
                                             Double.parseDouble(xy[1].trim())});
                    } catch (Exception ignored) {}
                }
                k = b + 1;
            }
            r.points = pts;
        } else {
            r.points = new ArrayList<double[]>();
        }
        return r;
    }

    private static String str(String obj, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(obj);
        if (!m.find()) return null;
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static boolean bool(String obj, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(obj);
        return m.find() && "true".equals(m.group(1));
    }

    private static double num(String obj, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?[0-9.]+)").matcher(obj);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }
}
