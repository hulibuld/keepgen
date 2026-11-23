package com.qianyuzhou.keepgen;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * App 内的 Keep 登录页。
 *
 * ── 为什么是「自建页面 + 原生发请求」而不是「打开 Keep 官方 H5」──
 *
 * 实测（2026-11-23）：Keep 没有可用的手机号 H5 登录页。
 *   · show.gotokeep.com/users/login 只是路由，bundle 里没有 mobile/password/sms 表单
 *   · 登录响应也不下发 Set-Cookie，所以「等网页自己写 cookie 再读」这条路不存在
 *   · api.gotokeep.com 的 CORS 只白名单 show.gotokeep.com，页面里直接 fetch 会被浏览器挡
 *
 * 所以页面只负责画界面，请求由 Java 侧发（WebView + JavascriptInterface）。
 * 这样既满足「app 内打开登录」，又不给用户看到任何 token 输入框。
 */
public final class KeepLoginActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        web = new WebView(this);
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setAllowFileAccess(true);
        st.setAllowContentAccess(false);
        st.setSupportZoom(false);
        st.setUserAgentString(st.getUserAgentString() + " Keep/9.1.130");

        web.addJavascriptInterface(new Bridge(), "KG");
        web.loadUrl("file:///android_asset/keep_login.html");
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }

    /** JS -> Java。只暴露登录与关闭两个方法，没有任何读文件/反射入口 */
    final class Bridge {

        @JavascriptInterface
        public void login(final String mobile, final String password) {
            new Thread(new Runnable() {
                public void run() {
                    String js;
                    try {
                        String tk = KeepApi.login(mobile.trim(), password);
                        if (!KeepApi.check(tk)) {
                            js = "onFail('登录成功但账号校验没过，请到 Keep App 里确认一下账号状态')";
                        } else {
                            String uid = "", name = "";
                            try {
                                org.json.JSONObject o = KeepApi.meInfo(tk);
                                uid = o == null ? "" : o.optString("userId", "");
                            } catch (Exception ignored) {}
                            KeepApi.save(KeepLoginActivity.this, tk, uid, name);
                            js = "onOk()";
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    toast("登录成功");
                                    setResult(RESULT_OK);
                                    finish();
                                }
                            });
                        }
                    } catch (Exception e) {
                        String msg = e.getMessage() == null ? "登录失败" : e.getMessage();
                        // 按 Keep 的错误码说人话：最常见的是「这个账号根本没设密码」
                        if (msg.contains("100001") || msg.contains("不匹配")) {
                            msg = "账号或密码不对。如果这个账号从来没设过密码，"
                                    + "请先在 Keep App 里设一个（我的 → 设置 → 账号与安全）";
                        } else if (msg.contains("100009")) {
                            msg = "Keep 那边限制频繁获取验证码，过 24 小时再试";
                        } else if (msg.contains("100040")) {
                            msg = "手机号格式不对";
                        }
                        js = "onFail(" + quote(msg) + ")";
                    }
                    final String fjs = js;
                    runOnUiThread(new Runnable() {
                        public void run() { web.evaluateJavascript(fjs, null); }
                    });
                }
            }).start();
        }

        @JavascriptInterface
        public void close() {
            runOnUiThread(new Runnable() {
                public void run() { finish(); }
            });
        }

        @JavascriptInterface
        public String existingUser() {
            return KeepApi.loggedIn(KeepLoginActivity.this)
                    ? KeepApi.userName(KeepLoginActivity.this) : "";
        }
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '\\') sb.append('\\');
            if (c == '\n') { sb.append("\\n"); continue; }
            sb.append(c);
        }
        return sb.append('\'').toString();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}