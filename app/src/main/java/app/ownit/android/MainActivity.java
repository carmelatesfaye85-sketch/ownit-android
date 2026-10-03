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
        s.setUserAgentString(s.getUserAgentString() + " OwnItAndroid/0.4");
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
        // Share → OwnIt from TikTok, Instagram, YouTube or anywhere else.
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            intent.setAction(Intent.ACTION_MAIN);
            intent.removeExtra(Intent.EXTRA_TEXT);
            if (text != null && !text.trim().isEmpty()) {
                String all = (subject != null && !text.contains(subject) ? subject + " " : "") + text;
                runJs("window.ownitShare && window.ownitShare(" + JSONObject.quote(all) + ")");
            }
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

    /** TikTok's own link prefix depends on which TikTok app is installed. */
    private static String schemeFor(String pkg) {
        if ("com.ss.android.ugc.trill".equals(pkg)) return "snssdk1180";
        if ("com.zhiliaoapp.musically.go".equals(pkg)) return "snssdk1340";
        return "snssdk1233";
    }

    /** Opens one exact link inside the platform's app. Returns false if the app doesn't accept it. */
    private boolean openLink(String platform, String url) {
        String pkg = installedPackage(platform);
        if (pkg == null) {
            if (!url.startsWith("http")) return false;
            openExternal(url);
            return true;
        }
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url.replace("{scheme}", schemeFor(pkg))));
        view.setPackage(pkg);
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return true;
        } catch (Exception e) {
            return false;
        }
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
                o.put("version", "0.4");
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

        /** Starts a visit on one exact link (a tested search link, or a saved video). Returns "ok" or "none". */
        @JavascriptInterface
        public String startVisitUrl(String platform, String kind, String query, int minutes, String url) {
            if (platform == null || platform.isEmpty()) platform = "tiktok";
            if (url == null || url.isEmpty()) { startVisit(platform, kind, query, minutes); return "ok"; }
            int m = Math.max(1, Math.min(minutes, 120));
            GuardState.allow(MainActivity.this, platform, System.currentTimeMillis() + m * 60_000L, query, kind);
            if (openLink(platform, url)) return "ok";
            GuardState.end(MainActivity.this);
            return "none";
        }

        /** Brings the app back to the front exactly where you left it (the visit keeps its timer). */
        @JavascriptInterface
        public String resumeApp(String platform) {
            String pkg = installedPackage(platform);
            Intent launch = pkg == null ? null : getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) return "none";
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(launch);
                return "ok";
            } catch (Exception e) {
                return "none";
            }
        }

        /** Looks up a shared video's title and picture, then calls window.ownitMeta(key, json). */
        @JavascriptInterface
        public void fetchMeta(String url, String key) {
            new Thread(() -> {
                String json = Meta.lookup(url);
                runJs("window.ownitMeta && window.ownitMeta(" + JSONObject.quote(key) + "," + JSONObject.quote(json) + ")");
            }).start();
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
