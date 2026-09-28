package app.ownit.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** Shows the OwnIt website and lets it talk to the Guard through the "OwnItAndroid" bridge. */
public class MainActivity extends Activity {
    private static final String HOME = "https://ownit-app.netlify.app/?app=android";
    private static final String SITE_HOST = "ownit-app.netlify.app";

    private WebView web;
    private boolean pageReady = false;
    private final List<String> pendingJs = new ArrayList<>();
    private boolean askedNotifications = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#061513"));
        w.setNavigationBarColor(Color.parseColor("#061513"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#061513"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " OwnItAndroid/0.3");
        web.addJavascriptInterface(new Bridge(), "OwnItAndroid");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return handleUrl(req.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageReady = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                for (String js : pendingJs) view.evaluateJavascript(js, null);
                pendingJs.clear();
            }
        });
        setContentView(web);

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(HOME);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        waitingFor = null;
        GuardService.startIfReady(this);
        runJs("window.ownitResume && window.ownitResume()");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        runJs("window.ownitResume && window.ownitResume()");
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** Links inside OwnIt and sign-in pages stay here; anything else opens in the browser. */
    private boolean handleUrl(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        String host = uri.getHost() == null ? "" : uri.getHost();
        if (scheme.equals("ownit")) {
            handleIntent(new Intent(Intent.ACTION_VIEW, uri));
            return true;
        }
        if (host.equals(SITE_HOST) || host.endsWith(".supabase.co")) return false;
        openExternal(uri.toString());
        return true;
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data != null && "ownit".equals(data.getScheme()) && "auth".equals(data.getHost())) {
            String frag = data.getEncodedFragment();
            if (frag == null) frag = data.getEncodedQuery();
            if (frag != null) {
                runJs("window.ownitAuthCallback && window.ownitAuthCallback(" + JSONObject.quote(frag) + ")");
            }
            intent.setData(null);
            return;
        }
        String action = intent.getStringExtra("ownit_action");
        if (action == null) return;
        intent.removeExtra("ownit_action");
        if (action.equals("blocked")) {
            String platform = intent.getStringExtra("platform");
            runJs("window.ownitBlocked && window.ownitBlocked(" + JSONObject.quote(platform == null ? "" : platform) + ")");
        } else if (action.equals("timeup")) {
            runJs("window.ownitTimeUp && window.ownitTimeUp()");
        } else if (action.equals("guard-on")) {
            runJs("window.ownitResume && window.ownitResume()");
        }
    }

    private void runJs(String js) {
        if (web == null) return;
        web.post(() -> {
            if (pageReady) web.evaluateJavascript(js, null);
            else pendingJs.add(js);
        });
    }

    private boolean isGuardOn() {
        return Perms.guardReady(this);
    }

    /** While a settings page is open, watch for the permission and come straight back once it's on. */
    private String waitingFor = null;
    private final android.os.Handler watcher = new android.os.Handler(android.os.Looper.getMainLooper());

    private void watchFor(String what) {
        waitingFor = what;
        final long started = System.currentTimeMillis();
        watcher.removeCallbacksAndMessages(null);
        watcher.postDelayed(new Runnable() {
            @Override public void run() {
                if (waitingFor == null || System.currentTimeMillis() - started > 5 * 60 * 1000L) return;
                boolean ok = what.equals("overlay") ? Perms.overlay(MainActivity.this) : Perms.usage(MainActivity.this);
                if (ok) {
                    waitingFor = null;
                    GuardService.startIfReady(MainActivity.this);
                    Intent back = new Intent(MainActivity.this, MainActivity.class);
                    back.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(back); } catch (Exception ignored) { }
                    return;
                }
                watcher.postDelayed(this, 500);
            }
        }, 800);
    }

    private void openSettings(String action, boolean withPackage) {
        Intent i = new Intent(action);
        if (withPackage) i.setData(Uri.parse("package:" + getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            if (withPackage) openSettings(action, false);
        }
    }

    private String installedPackage(String platform) {
        String[] pkgs = GuardState.PACKAGES.get(platform);
        if (pkgs == null) return null;
        PackageManager pm = getPackageManager();
        for (String p : pkgs) {
            try {
                pm.getPackageInfo(p, 0);
                return p;
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return null;
    }

    private static String enc(String q) {
        try { return URLEncoder.encode(q == null ? "" : q, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    private static String targetUrl(String platform, String kind, String q) {
        boolean search = "search".equals(kind) && q != null && !q.trim().isEmpty();
        switch (platform) {
            case "youtube":
                return search ? "https://www.youtube.com/results?search_query=" + enc(q) : "https://www.youtube.com/shorts";
            case "instagram":
                return search ? "https://www.instagram.com/explore/search/keyword/?q=" + enc(q) : "https://www.instagram.com/reels/";
            default:
                return search ? "https://www.tiktok.com/search?q=" + enc(q) : "https://www.tiktok.com/foryou";
        }
    }

    /** Opens the app straight on the search when possible; otherwise opens the app and copies the search. */
    private void openTarget(String platform, String kind, String q) {
        String url = targetUrl(platform, kind, q);
        String pkg = installedPackage(platform);
        if (pkg != null) {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            view.setPackage(pkg);
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(view);
                return;
            } catch (ActivityNotFoundException ignored) { }
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                if (q != null && !q.trim().isEmpty()) {
                    ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cb.setPrimaryClip(ClipData.newPlainText("OwnIt search", q));
                    Toast.makeText(this, "Search copied. Paste it in the search bar.", Toast.LENGTH_LONG).show();
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                return;
            }
        }
        openExternal(url);
    }

    private void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app can open this link.", Toast.LENGTH_SHORT).show();
        }
    }

    /** Everything the website can ask the phone to do. */
    private class Bridge {
        @JavascriptInterface
        public boolean isApp() { return true; }

        @JavascriptInterface
        public String status() {
            try {
                JSONObject o = new JSONObject();
                o.put("guardOn", isGuardOn());
                o.put("notifyOn", Notifier.enabled(MainActivity.this));
                o.put("overlayOn", Perms.overlay(MainActivity.this));
                o.put("usageOn", Perms.usage(MainActivity.this));
                o.put("version", "0.3");
                o.put("allowed", GuardState.allowedPlatform(MainActivity.this));
                o.put("until", GuardState.until(MainActivity.this));
                o.put("tiktokInstalled", installedPackage("tiktok") != null);
                o.put("instagramInstalled", installedPackage("instagram") != null);
                o.put("youtubeInstalled", installedPackage("youtube") != null);
                o.put("restrictedSettings", false);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void openGuardSettings() {
            runOnUiThread(() -> {
                if (!Perms.overlay(MainActivity.this)) { openOverlaySettings(); return; }
                if (!Perms.usage(MainActivity.this)) { openUsageSettings(); return; }
                GuardService.startIfReady(MainActivity.this);
                runJs("window.ownitResume && window.ownitResume()");
            });
        }

        @JavascriptInterface
        public void openOverlaySettings() {
            runOnUiThread(() -> {
                openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true);
                watchFor("overlay");
            });
        }

        @JavascriptInterface
        public void openUsageSettings() {
            runOnUiThread(() -> {
                openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS, Build.VERSION.SDK_INT >= 29);
                watchFor("usage");
            });
        }

        @JavascriptInterface
        public void openAppInfo() {
            runOnUiThread(() -> {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            });
        }

        @JavascriptInterface
        public void requestNotifications() {
            runOnUiThread(() -> {
                if (Notifier.enabled(MainActivity.this)) { runJs("window.ownitResume && window.ownitResume()"); return; }
                if (Build.VERSION.SDK_INT >= 33 && !askedNotifications) {
                    askedNotifications = true;
                    requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 7);
                } else {
                    Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }
            });
        }

        @JavascriptInterface
        public void setGuardedApps(String csv) {
            GuardState.setGuarded(MainActivity.this, csv);
        }

        @JavascriptInterface
        public void startVisit(String platform, String kind, String query, int minutes) {
            if (platform == null || platform.isEmpty()) platform = "tiktok";
            int m = Math.max(1, Math.min(minutes, 120));
            long until = System.currentTimeMillis() + m * 60_000L;
            GuardState.allow(MainActivity.this, platform, until, query, kind);
            final String p = platform;
            runOnUiThread(() -> openTarget(p, kind, query));
        }

        @JavascriptInterface
        public void endVisit() {
            GuardState.end(MainActivity.this);
        }

        @JavascriptInterface
        public void openExternal(String url) {
            runOnUiThread(() -> MainActivity.this.openExternal(url));
        }
    }
}
