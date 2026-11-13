package com.graphstudio.calculator;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

public class MainActivity extends Activity {

    private static final String PREFS = "diag";
    private static boolean recorderInstalled = false;

    private FrameLayout root;
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashRecorder();
        applyBars(0x000000, 0x000000);

        String report = collectReport();
        if (report != null) {
            showReport(report);      // the app failed last time: show why instead of failing silently
        } else {
            startApp();
        }
    }

    // ------------------------------------------------------------------ app start

    private void startApp() {
        try {
            destroyWebView();

            root = new FrameLayout(this);
            root.setBackgroundColor(Color.BLACK);

            web = new WebView(this);
            web.setBackgroundColor(Color.BLACK);
            web.setOverScrollMode(View.OVER_SCROLL_NEVER);
            web.setVerticalScrollBarEnabled(false);
            web.setHorizontalScrollBarEnabled(false);

            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);          // the app saves its settings / history in localStorage
            s.setSupportZoom(false);
            s.setBuiltInZoomControls(false);
            s.setDisplayZoomControls(false);
            s.setTextZoom(100);                    // system font size must not change the layout
            try {
                if (Build.VERSION.SDK_INT >= 29) s.setForceDark(WebSettings.FORCE_DARK_OFF);
            } catch (Throwable ignored) { }

            web.setWebViewClient(new WebViewClient() {
                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    String msg = "The WebView renderer process "
                            + (detail.didCrash() ? "crashed." : "was killed by the system (probably low memory).");
                    saveReport(msg);
                    destroyWebView();
                    showReport(msg + "\n\n" + deviceInfo());
                    return true;                   // handled: the app itself stays alive
                }
            });
            web.addJavascriptInterface(new ThemeBridge(), "AndroidTheme");

            root.addView(web, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            setContentView(root);

            web.loadUrl("file:///android_asset/index.html");
        } catch (Throwable t) {
            saveReport("Startup error:\n" + stack(t));
            showReport("Startup error:\n" + stack(t) + "\n" + deviceInfo());
        }
    }

    private void destroyWebView() {
        try {
            if (web != null) {
                if (root != null) root.removeView(web);
                web.removeJavascriptInterface("AndroidTheme");
                web.destroy();
            }
        } catch (Throwable ignored) { }
        web = null;
    }

    // ------------------------------------------------------------------ native bridge used by the page

    /** Called from the page: AndroidTheme.setLightMode(bool) and AndroidTheme.setBars(statusRgb, navRgb). */
    public class ThemeBridge {
        @JavascriptInterface
        public void setLightMode(final boolean light) {
            final int c = light ? 0xF2F2F7 : 0x000000;
            runOnUiThread(new Runnable() {
                @Override public void run() { applyBars(c, c); }
            });
        }

        @JavascriptInterface
        public void setBars(final int statusRgb, final int navRgb) {
            runOnUiThread(new Runnable() {
                @Override public void run() { applyBars(statusRgb, navRgb); }
            });
        }
    }

    private void applyBars(int statusRgb, int navRgb) {
        try {
            Window w = getWindow();
            w.setStatusBarColor(0xFF000000 | (statusRgb & 0xFFFFFF));
            w.setNavigationBarColor(0xFF000000 | (navRgb & 0xFFFFFF));
            if (Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);
            setIconStyle(isLight(statusRgb), isLight(navRgb));
        } catch (Throwable ignored) { }
    }

    private static boolean isLight(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) > 150;
    }

    @SuppressWarnings("deprecation")
    private void setIconStyle(boolean lightStatusBg, boolean lightNavBg) {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(
                        lightStatusBg ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                c.setSystemBarsAppearance(
                        lightNavBg ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            int f = decor.getSystemUiVisibility();
            if (Build.VERSION.SDK_INT >= 23) {
                f = lightStatusBg ? (f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
                                  : (f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
            if (Build.VERSION.SDK_INT >= 26) {
                f = lightNavBg ? (f | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
                               : (f & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
            decor.setSystemUiVisibility(f);
        }
    }

    /** Ask the system for the highest refresh rate of the screen (90 / 120 Hz) so animations stay fluid. */
    @SuppressWarnings("deprecation")
    private void requestHighRefreshRate() {
        try {
            Display d = Build.VERSION.SDK_INT >= 30 ? getDisplay() : getWindowManager().getDefaultDisplay();
            if (d == null) return;
            Display.Mode cur = d.getMode();
            Display.Mode best = cur;
            for (Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() == cur.getPhysicalWidth()
                        && m.getPhysicalHeight() == cur.getPhysicalHeight()
                        && m.getRefreshRate() > best.getRefreshRate()) {
                    best = m;
                }
            }
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.preferredDisplayModeId = best.getModeId();
            lp.preferredRefreshRate = best.getRefreshRate();
            getWindow().setAttributes(lp);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ error reporting (shows why the app closed)

    private void installCrashRecorder() {
        if (recorderInstalled) return;
        recorderInstalled = true;
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try { saveReport("Crash in thread \"" + t.getName() + "\":\n" + stack(e)); } catch (Throwable ignored) { }
                if (old != null) old.uncaughtException(t, e);
                else System.exit(1);
            }
        });
    }

    private void saveReport(String text) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("crash", text).commit();
    }

    private String collectReport() {
        StringBuilder sb = new StringBuilder();
        try {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            String rep = sp.getString("crash", null);
            if (rep != null) {
                sb.append(rep).append("\n\n");
                sp.edit().remove("crash").commit();
            }
            if (Build.VERSION.SDK_INT >= 30) {
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(null, 0, 3);
                long seen = sp.getLong("exit_seen", 0);
                long newest = seen;
                for (ApplicationExitInfo e : list) {
                    if (e.getTimestamp() <= seen) continue;
                    if (e.getTimestamp() > newest) newest = e.getTimestamp();
                    int r = e.getReason();
                    if (r == ApplicationExitInfo.REASON_CRASH_NATIVE || r == ApplicationExitInfo.REASON_CRASH
                            || r == ApplicationExitInfo.REASON_ANR || r == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE) {
                        sb.append("Last exit reason: ").append(r)
                          .append(" (4=crash, 5=native crash, 6=ANR, 7=init failure) status=").append(e.getStatus())
                          .append(" desc=").append(e.getDescription()).append("\n");
                    }
                }
                if (newest > seen) sp.edit().putLong("exit_seen", newest).commit();
            }
        } catch (Throwable ignored) { }
        if (sb.length() == 0) return null;
        return sb.toString() + deviceInfo();
    }

    private String deviceInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
          .append(" | Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")");
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                PackageInfo pi = WebView.getCurrentWebViewPackage();
                if (pi != null) sb.append("\nWebView: ").append(pi.packageName).append(' ').append(pi.versionName);
            }
        } catch (Throwable ignored) { }
        sb.append("\nApp: 3.3");
        return sb.toString();
    }

    private static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void showReport(String report) {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(24), dp(16), dp(24));

        TextView title = new TextView(this);
        title.setText("Graph Studio - error report");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Take a screenshot of this screen and send it to Claude.");
        hint.setTextColor(0xFFBBBBBB);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setPadding(0, dp(8), 0, dp(12));
        col.addView(hint);

        TextView body = new TextView(this);
        body.setText(report);
        body.setTextIsSelectable(true);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextColor(0xFFFFB74D);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        col.addView(body);

        Button go = new Button(this);
        go.setText("Open the app anyway");
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startApp(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        col.addView(go, lp);

        sv.addView(col);
        setContentView(sv);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        requestHighRefreshRate();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyWebView();
        super.onDestroy();
    }
}
