package com.doubao.digitalclock.lite;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Android 5.x 兼容的极薄 WebView 外壳（minSdk 21 / targetSdk 22）。
 * 界面、农历、天气、报时逻辑全部在 assets/index.html 中。
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_IMAGE = 1002;
    private static final String BG_FILE = "custom_bg.jpg";

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;

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

            /** 网页里 <input type="file"> 的选图入口（相册背景图） */
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/*");
                    startActivityForResult(Intent.createChooser(intent, "选择背景图片"), REQ_PICK_IMAGE);
                    return true;
                } catch (Throwable t) {
                    Log.w("DigitalClockLite", "onShowFileChooser", t);
                    filePathCallback = null;
                    return false;
                }
            }
        });
        web.addJavascriptInterface(new HostBridge(), "HostBridge");

        setContentView(web);
        hideSystemUi();
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK_IMAGE) {
            if (filePathCallback == null) return;
            Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
            filePathCallback.onReceiveValue(uri == null ? null : new Uri[]{uri});
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private File bgFile() {
        return new File(getFilesDir(), BG_FILE);
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
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 转屏后让页面重新计算字号与位置（老 WebView 里 resize 事件可能不触发）
        if (web != null) {
            web.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (web == null) return;
                    // 老 WebView 转屏后 innerWidth/innerHeight 可能滞后，用真实屏幕尺寸校正
                    float density = getResources().getDisplayMetrics().density;
                    int wCss = Math.round(getWindowManager().getDefaultDisplay().getWidth() / density);
                    int hCss = Math.round(getWindowManager().getDefaultDisplay().getHeight() / density);
                    String js = "window.__metrics={w:" + wCss + ",h:" + hCss + "};window.__relayout&&window.__relayout();";
                    web.evaluateJavascript(js, null);
                }
            }, 260);
        }
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

        /** 保存相册背景图（入参是 data:image/jpeg;base64,...），成功返回 true */
        @JavascriptInterface
        public boolean saveBg(String dataUrl) {
            if (dataUrl == null) return false;
            try {
                int comma = dataUrl.indexOf(',');
                if (comma < 0) return false;
                byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
                if (bytes == null || bytes.length == 0) return false;
                java.io.FileOutputStream out = new java.io.FileOutputStream(bgFile());
                try {
                    out.write(bytes);
                    out.flush();
                } finally {
                    out.close();
                }
                return true;
            } catch (Throwable t) {
                Log.w("DigitalClockLite", "saveBg", t);
                return false;
            }
        }

        /** 读回背景图，返回 data URL；没有则返回空字符串 */
        @JavascriptInterface
        public String loadBg() {
            File f = bgFile();
            if (!f.exists() || f.length() == 0) return "";
            try {
                FileInputStream in = new FileInputStream(f);
                String b64;
                try {
                    b64 = Base64.encodeToString(readAll(in), Base64.NO_WRAP);
                } finally {
                    in.close();
                }
                return "data:image/jpeg;base64," + b64;
            } catch (Throwable t) {
                Log.w("DigitalClockLite", "loadBg", t);
                return "";
            }
        }

        @JavascriptInterface
        public void clearBg() {
            try {
                File f = bgFile();
                if (f.exists()) f.delete();
            } catch (Throwable t) {
                Log.w("DigitalClockLite", "clearBg", t);
            }
        }

        private byte[] readAll(InputStream in) throws java.io.IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }

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

        /** 直接返回可显示的天气文案（Java 侧完成请求与解析，避免老 WebView 的跨域问题）。
         *  成功返回例如 "晴间多云 4°"；失败返回空字符串。 */
        @JavascriptInterface
        public void getWeatherText(final double lat, final double lon, final String callbackName) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String text = fetchOpenMeteo(lat, lon);
                    if (text == null || text.length() == 0) {
                        text = fetchWttr(lat, lon);
                    }
                    final String result = text == null ? "" : text;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (web == null || callbackName == null) return;
                            String quoted = quote(result);
                            web.evaluateJavascript("window['" + callbackName + "'] && window['" + callbackName + "'](" + quoted + ")", null);
                        }
                    });
                }
            }).start();
        }

        private String fetchOpenMeteo(double lat, double lon) {
            try {
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code&timezone=auto&t=" + (System.currentTimeMillis() / 1000);
                String body = httpBody(url);
                if (body == null) return null;
                org.json.JSONObject o = new org.json.JSONObject(body);
                org.json.JSONObject c = o.optJSONObject("current");
                if (c == null) return null;
                double temp = c.optDouble("temperature_2m");
                int code = c.optInt("weather_code", 3);
                return weatherName(code) + " " + Math.round(temp) + "°";
            } catch (Throwable t) {
                return null;
            }
        }

        private String fetchWttr(double lat, double lon) {
            try {
                String url = "https://wttr.in/" + String.format(java.util.Locale.US, "%.3f,%.3f", lat, lon)
                        + "?format=%C+%t&lang=zh&m";
                String body = httpBody(url);
                if (body == null) return null;
                body = body.replace("\n", " ").trim();
                // 形如 "晴间多云 +4°C"
                body = body.replace("+", "").replace("C", "").replace("Unknown", "").trim();
                return body.length() == 0 ? null : body;
            } catch (Throwable t) {
                return null;
            }
        }

        private String httpBody(String url) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(9000);
                conn.setReadTimeout(9000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DigitalClockLite/1.3");
                conn.setRequestProperty("Accept", "*/*");
                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    return null;
                }
                StringBuilder sb = new StringBuilder();
                BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                r.close();
                conn.disconnect();
                return sb.toString();
            } catch (Throwable t) {
                return null;
            }
        }

        private String quote(String s) {
            try {
                return org.json.JSONObject.quote(s);
            } catch (Throwable t) {
                return "\"\"";
            }
        }

        private String weatherName(int code) {
            if (code == 0) return "晴";
            if (code <= 2) return "多云";
            if (code == 3) return "阴";
            if (code == 45 || code == 48) return "雾";
            if (code >= 51 && code <= 57) return "毛毛雨";
            if (code >= 61 && code <= 67) return "雨";
            if (code >= 71 && code <= 77) return "雪";
            if (code >= 80 && code <= 82) return "阵雨";
            if (code >= 85 && code <= 86) return "阵雪";
            if (code >= 95) return "雷雨";
            return "阴";
        }
    }
}
