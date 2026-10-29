package com.example.ershima;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 主界面：用一个全屏 WebView 加载内置的 index.html（二十八脉网页），
 * 并通过 addJavascriptInterface 把"原生振动"能力暴露给网页（window.NativeVibe）。
 * 网页检测到 NativeVibe 后就会走系统马达，绕过小米对网页振动的屏蔽。
 */
public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        webView.getSettings().setJavaScriptEnabled(true);     // 启用 JS（网页逻辑需要）
        webView.getSettings().setDomStorageEnabled(true);     // 启用 localStorage（学习数据/收藏/笔记需要）
        webView.getSettings().setAllowFileAccess(true);       // 允许读取内置资产
        webView.setWebViewClient(new WebViewClient());        // 页面内跳转不跳出 App

        // 暴露原生振动桥：网页里调用 window.NativeVibe.vibrate(json) / .stop()
        webView.addJavascriptInterface(new VibeBridge(this), "NativeVibe");

        // 加载内置网页（assets 目录下的 index.html）
        webView.loadUrl("file:///android_asset/index.html");
    }

    // 返回键：在网页内返回上一页，而不是直接退出 App
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
