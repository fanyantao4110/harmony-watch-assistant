package com.wlaz;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 照腕连 3.4 的 HuaweiIdWebViewActivity：登录华为开发者账号后从 Cookie 里取 agcTeamId */
public class HuaweiIdActivity extends Activity {

    public static final String EXTRA_HUAWEI_ID = "extra_huawei_id";
    private static final String TARGET = "https://developer.huawei.com/consumer/cn/service/josp/agc/index.html#/myProject";

    private WebView web;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF2F2F7);
        int p = dp(16);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("获取华为 ID");
        title.setTextSize(22);
        title.setTextColor(0xFF000000);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText("请先完成登录，然后点击底部“已完成登录”。");
        hint.setTextSize(13);
        hint.setTextColor(0xFF8E8E93);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(6);
        root.addView(hint, hp);

        web = new WebView(this);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, 0, 1f);
        wp.topMargin = dp(14);
        web.setLayoutParams(wp);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        WebSettings s = web.getSettings();
        // 用电脑版 UA 打开华为开发者账号页（移动版经常登录不上/取不到 Cookie）
        s.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(85);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setBackgroundColor(0xFFFFFFFF);
        web.setWebViewClient(new WebViewClient());
        root.addView(web);

        TextView done = new TextView(this);
        done.setText("已完成登录");
        done.setGravity(android.view.Gravity.CENTER);
        done.setTextSize(15);
        done.setTypeface(Typeface.DEFAULT_BOLD);
        done.setTextColor(0xFFFFFFFF);
        done.setBackgroundResource(R.drawable.get_btn);
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(-1, dp(52));
        dp2.topMargin = dp(14);
        root.addView(done, dp2);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishWithCookie();
            }
        });

        setContentView(root);
        Toast.makeText(this, "正在打开华为登录页…", Toast.LENGTH_SHORT).show();
        web.loadUrl(TARGET);
    }

    private void finishWithCookie() {
        if (web == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        CookieManager.getInstance().flush();
        String id = HuaweiAuth.extractHuaweiId(CookieManager.getInstance().getCookie(TARGET));
        if (TextUtils.isEmpty(id)) {
            id = HuaweiAuth.extractHuaweiId(CookieManager.getInstance().getCookie("https://developer.huawei.com/"));
        }
        if (TextUtils.isEmpty(id)) {
            Toast.makeText(this, "没有读取到华为 ID，请确认已登录后再试。", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent data = new Intent();
        data.putExtra(EXTRA_HUAWEI_ID, id);
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}