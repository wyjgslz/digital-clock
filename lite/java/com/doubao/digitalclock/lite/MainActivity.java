package com.doubao.digitalclock.lite;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Android 5.x 兼容的极薄 WebView 外壳（minSdk 21 / targetSdk 22）。
 * 界面、农历、天气、报时逻辑全部在 assets/index.html 中。
 */
public class MainActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadsImagesAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            s.setMediaPlaybackRequiresUserGesture(false);
        }

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });
        web.addJavascriptInterface(new HostBridge(), "HostBridge");

        setContentView(web);
        hideSystemUi();
        web.loadUrl("file:///android_asset/index.html");
    }

    private void hideSystemUi() {
        try {
            View decor = getWindow().getDecorView();
            int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            decor.setSystemUiVisibility(flags);
        } catch (Throwable t) {
            Log.w("DigitalClockLite", "hideSystemUi", t);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** 暴露给 JS 的桥：屏幕方向 + 网络兜底（老 WebView 里 XHR 被 CORS 拦住时使用） */
    public class HostBridge {

        @JavascriptInterface
        public void setOrientation(int mode) {
            final int m = mode;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (m == 1) {
                            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                        } else if (m == 2) {
                            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                        } else {
                            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
                        }
                    } catch (Throwable t) {
                        Log.w("DigitalClockLite", "setOrientation", t);
                    }
                }
            });
        }

        @JavascriptInterface
        public void httpGet(final String url, final String callbackName) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String body = null;
                    try {
                        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                        conn.setConnectTimeout(8000);
                        conn.setReadTimeout(8000);
                        conn.setRequestProperty("User-Agent", "DigitalClockLite/1.0");
                        if (conn.getResponseCode() == 200) {
                            StringBuilder sb = new StringBuilder();
                            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                            String line;
                            while ((line = r.readLine()) != null) sb.append(line);
                            r.close();
                            body = sb.toString();
                        }
                        conn.disconnect();
                    } catch (Throwable t) {
                        body = null;
                    }
                    final String payload = body == null ? "null" : body;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (web == null || callbackName == null || "null".equals(payload)) return;
                            String json = payload.replace("\\", "\\\\").replace("'", "\\'")
                                    .replace("\r", "").replace("\n", "");
                            web.evaluateJavascript("window['" + callbackName + "'] && window['" + callbackName + "']('" + json + "')", null);
                        }
                    });
                }
            }).start();
        }
    }
}
