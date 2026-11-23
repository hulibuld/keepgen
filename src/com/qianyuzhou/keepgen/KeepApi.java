package com.qianyuzhou.keepgen;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Keep 私有接口客户端（无第三方依赖）。
 *
 * ── 设计边界 ────────────────────────────────────────────────
 *
 * 只做四件事：登录、验 token、读记录、写记录（v5/sportlog）。
 * 不做「模板自学习」「五种结构连发」那套 —— v5 只要 12 个字段，
 * 多发的每个请求都会在用户账号里真的建一条记录，失败重试等于刷垃圾。
 *
 * ── 端点事实（2026-11-23 在 9.1.130 账号上实测）────────────────
 *
 *   POST /pd/v5/sportlog      建档，唯一可用写通道；v3/v1 写入口已封（恒 100059）
 *   GET  /pd/v3/stats/detail  历史列表 / 验 token
 *   GET  /pd/v3/{type}log/{id} 单条详情
 *   POST /v1.1/users/login    手机号+密码表单登录，token 在 body 里（无 Set-Cookie）
 *
 * 鉴权只有一层：Authorization: Bearer <token>，没有签名、没有 nonce。
 */
final class KeepApi {

    static final String BASE = "https://api.gotokeep.com";
    /**
     * UA。
     *
     * 原来写死 `sdk_gphone64_arm64`（Android 模拟器）—— 全网用户共用同一个模拟器 UA，
     * Keep 侧按机型聚类一眼就能把这批账号捞出来。红队 2026-11-23 把这条列为
     * 「最硬的矛盾点」之一。
     *
     * 现在按设备稳定挑一条真机 UA：同一台设备永远同一条，换设备才换。
     * 必须是「稳定」而不是「随机」——同一个 token 反复换 UA 比写死模拟器更可疑。
     */
    private static final String[] UA_POOL = {
        "Keep/9.1.130 (Android 14; 23127PN0CC; 50254)",
        "Keep/9.1.130 (Android 13; PGZ110; 50254)",
        "Keep/9.1.130 (Android 13; RMX3366; 50254)",
        "Keep/9.1.130 (Android 14; V2219A; 50254)",
        "Keep/9.1.130 (Android 12; ELS-AN00; 50254)",
        "Keep/9.1.130 (Android 13; 22081212C; 50254)",
        "Keep/9.1.130 (Android 14; PJD110; 50254)",
        "Keep/9.1.130 (Android 12; M2102J20SG; 50254)",
    };
    private static String ua;

    /** 首次调用时定一次；后续所有请求都用同一条 */
    static void initUa(Context c) {
        if (ua != null) return;
        int idx = 0;
        try {
            String dev = CloudUnlock.deviceId(c);
            if (dev != null && !dev.isEmpty()) {
                int h = 0;
                for (int i = 0; i < dev.length(); i++) h = h * 31 + dev.charAt(i);
                idx = Math.abs(h) % UA_POOL.length;
            }
        } catch (Exception ignored) {}
        ua = UA_POOL[idx];
    }

    static String ua() { return ua == null ? UA_POOL[0] : ua; }

    private static final String PREF = "keep_account";
    private static final String K_TOKEN = "token";
    private static final String K_UID = "uid";
    private static final String K_NAME = "name";

    private KeepApi() {}

    // ---- 结果 ----

    static final class Resp {
        int code;
        String body = "";
        boolean ok;
        int errorCode;
        String text = "";

        String json() {
            return body == null ? "" : body;
        }

        String pick(String key) {
            try {
                JSONObject o = new JSONObject(body);
                return o.optString(key, "");
            } catch (Exception e) {
                return "";
            }
        }
    }

    // ---- 账号 ----

    static String token(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(K_TOKEN, "");
    }

    static String userName(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(K_NAME, "");
    }

    static String userId(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(K_UID, "");
    }

    static boolean loggedIn(Context c) {
        String t = token(c);
        return t != null && t.length() > 40;
    }

    static void save(Context c, String token, String uid, String name) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(K_TOKEN, token)
                .putString(K_UID, uid == null ? "" : uid)
                .putString(K_NAME, name == null ? "" : name)
                .apply();
    }

    static void logout(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
    }

    // ---- 已写进 Keep 的日期（同一天去重用） ----

    private static final String K_DAYS = "days";

    /** 某天（本地时区）的键，yyyyMMdd */
    static String dayKey(long ms) {
        return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date(ms));
    }

    /** 已经写进过 Keep 的日期集合 */
    static java.util.Set<String> uploadedDays(Context c) {
        String s = c.getSharedPreferences("keep_upload", Context.MODE_PRIVATE)
                .getString(K_DAYS, "");
        java.util.Set<String> out = new java.util.HashSet<String>();
        for (String x : s.split(",")) if (!x.trim().isEmpty()) out.add(x.trim());
        return out;
    }

    static void markUploadedDay(Context c, long ms) {
        java.util.Set<String> d = uploadedDays(c);
        if (!d.add(dayKey(ms))) return;
        StringBuilder sb = new StringBuilder();
        for (String x : d) { if (sb.length() > 0) sb.append(','); sb.append(x); }
        c.getSharedPreferences("keep_upload", Context.MODE_PRIVATE).edit()
                .putString(K_DAYS, sb.toString()).apply();
    }

    // ---- 登录 ----

    /**
     * 手机号 + 密码登录。
     *
     * 这是唯一实测可用的登录口（Keep 没有手机号 H5 登录页，登录响应也不下发 cookie）。
     * 返回 token；失败抛异常，异常信息就是 Keep 的原始提示（可能是「需要验证码」）。
     */
    static String login(String mobile, String password) throws Exception {
        String form = "mobile=" + URLEncoder.encode(mobile, "UTF-8")
                + "&password=" + URLEncoder.encode(password, "UTF-8");
        Resp r = postForm(BASE + "/v1.1/users/login", form);
        JSONObject root = new JSONObject(r.body);
        if (!root.optBoolean("ok")) {
            String t = root.optString("text", "");
            if (t.isEmpty()) {
                Object d = root.opt("data");
                t = d == null ? "登录失败" : String.valueOf(d);
            }
            throw new IllegalStateException(t);
        }
        JSONObject data = root.optJSONObject("data");
        String tk = data == null ? "" : data.optString("token", "");
        if (tk.isEmpty()) throw new IllegalStateException("登录成功但没拿到 token");
        return tk;
    }

    /** 登录后核对一次，顺便拿昵称 */
    static JSONObject meInfo(String token) {
        Resp r = get(BASE + "/pd/v3/stats/detail?dateUnit=all&type=running&lastDate=0", token);
        try { return new JSONObject(r.body); } catch (Exception e) { return null; }
    }

    static boolean check(String token) {
        if (token == null || token.length() < 40) return false;
        Resp r = get(BASE + "/pd/v3/stats/detail?dateUnit=all&type=running&lastDate=0", token);
        return r.code == 200 && r.ok;
    }

    // ---- 读 ----

    static Resp stats(String token, String type) throws Exception {
        return get(BASE + "/pd/v3/stats/detail?dateUnit=all&type=" + type + "&lastDate=0", token);
    }

    static Resp detail(String token, String type, String id) throws Exception {
        return get(BASE + "/pd/v3/" + type + "log/" + id, token);
    }

    // ---- 写 ----

    /**
     * 建档。三级退让只在「参数类错误」时发生：
     *   ① 全字段 → ② 去心率 → ③ 纯基础
     * 401 直接抛（换方案没意义），5xx 直接抛（重发会重复建档）。
     *
     * @return logId
     */
    /**
     * 写入 Keep —— 走我们自己的云函数转发，不直连。
     *
     * 为什么绕一圈：Keep 的建档接口不认我们的凭据，客户端直连的话，
     * 一个只改了本地布尔的补丁包也能往账号里写。改成云端代写之后，
     * 写入必须先有我们签发的 token —— 补丁包签不出来，这一步必然失败，
     * 只能退回「导出文件自己导入」。
     *
     * 代价：自动写入从此依赖云函数在线（导出永远可用）。
     */
    static String uploadV5(Context ctx, Generator.Result g) throws Exception {
        String ours = CloudTrack.token(ctx);
        String keep = token(ctx);
        if (ours.length() < 40) throw new IllegalStateException("这台设备还没激活");
        if (keep.length() < 40) throw new IllegalStateException("请先登录 Keep");

        String full = KeepBody.build(g);
        String noHr = stripKey(full, "heartRate");
        String bare = stripKey(noHr, "stepPoints");
        bare = stripKey(bare, "crossKmPoints");
        bare = stripKey(bare, "totalSteps");
        bare = stripKey(bare, "averageStepFrequency");
        String[] bodies = new String[]{full, noHr, bare};

        Exception last = null;
        for (int i = 0; i < bodies.length; i++) {
            StringBuilder sb = new StringBuilder(bodies[i].length() + 4096);
            sb.append("{\"action\":\"push\",\"token\":\"").append(esc(ours))
              .append("\",\"keep\":\"").append(esc(keep)).append("\",\"v5\":")
              .append(bodies[i]).append('}');
            Resp r = postJson(BuildConfig.CLOUD_URL, sb.toString(), null);

            if (r.code == 0) throw new IllegalStateException("云端没响应，稍后再试");
            if (r.ok) {
                String id = pick(r.body, "logId");
                if (id.isEmpty()) throw new IllegalStateException("云端没返回记录号");
                doubtfulFlag = r.body.contains("\"doubtful\": true")
                        || r.body.contains("\"doubtful\":true");
                return id;
            }
            String err = pick(r.body, "error");
            if ("need_unlock".equals(err)) throw new IllegalStateException("这台设备还没激活");
            if ("daily_limit".equals(err)) throw new IllegalStateException("今天生成得有点多了");
            if ("bad_param".equals(err)) throw new IllegalStateException("数据不完整，换一组参数再试");
            last = new IllegalStateException("Keep 拒了这次写入：" + pick(r.body, "text"));
        }
        throw last == null ? new IllegalStateException("写入失败") : last;
    }

    /** 上一次 push 的结果里 Keep 有没有标「存疑」 */
    static boolean doubtfulFlag;

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String pick(String body, String key) {
        if (body == null) return "";
        String needle = "\"" + key + "\"";
        int k = body.indexOf(needle);
        if (k < 0) return "";
        int q1 = body.indexOf('"', k + needle.length());
        if (q1 < 0) return "";
        int q2 = body.indexOf('"', q1 + 1);
        return q2 < 0 ? "" : body.substring(q1 + 1, q2);
    }

    /** 从扁平 JSON 顶层摘掉一个 key（含它的值），只处理我们自己拼出来的格式 */
    private static String stripKey(String json, String key) {
        String needle = "\"" + key + "\":";
        int k = json.indexOf(needle);
        if (k < 0) return json;
        int i = k + needle.length();
        int depth = 0;
        boolean inStr = false;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (inStr) {
                if (c == '\\') { i += 2; continue; }
                if (c == '"') inStr = false;
                i++;
            } else if (c == '"') { inStr = true; i++; }
            else if (c == '[' || c == '{') { depth++; i++; }
            else if (c == ']' || c == '}') { depth--; i++; }
            else if (c == ',' && depth == 0) { i++; break; }
            else i++;
        }
        String out = json.substring(0, k) + json.substring(i);
        // 修掉摘除后可能留下的 ",," 或 ",}"。
        // 注意：这里必须用字面量替换 String.replace(CharSequence,CharSequence)，
        // 不能用 replaceAll —— ",\\s*}" 作为正则是非法的（* 后面跟 } 会抛
        // PatternSyntaxException），而且每次都抛在用户脸上。
        out = out.replace(",,", ",").replace(",}", "}");
        return out;
    }

    // ---- HTTP ----

    static Resp get(String url, String token) {
        return req("GET", url, null, token);
    }

    static Resp postJson(String url, String json, String token) {
        return req("POST", url, json, token);
    }

    static Resp postForm(String url, String form) {
        Resp r = req("POST", url, form, null);
        return r;
    }

    private static Resp req(String method, String urlStr, String body, String token) {
        Resp out = new Resp();
        HttpURLConnection c = null;
        try {
            URL url = new URL(urlStr);
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", ua());
            c.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                c.setDoOutput(true);
                boolean form = urlStr.endsWith("/login");
                c.setRequestProperty("Content-Type", form
                        ? "application/x-www-form-urlencoded;charset=utf-8"
                        : "application/json;charset=utf-8");
                OutputStream os = c.getOutputStream();
                try { os.write(body.getBytes("UTF-8")); os.flush(); } finally { os.close(); }
            }
            int code = c.getResponseCode();
            out.code = code;
            InputStream is = (code >= 400) ? c.getErrorStream() : c.getInputStream();
            if (is != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                is.close();
                out.body = new String(bo.toByteArray(), "UTF-8");
            }
            try {
                JSONObject o = new JSONObject(out.body);
                out.ok = o.optBoolean("ok");
                out.errorCode = o.optInt("errorCode", 0);
                out.text = o.optString("text", o.optString("message", ""));
            } catch (Exception ignore) {}
            return out;
        } catch (Exception e) {
            out.code = 0;
            out.text = e.getMessage() == null ? e.toString() : e.getMessage();
            return out;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}