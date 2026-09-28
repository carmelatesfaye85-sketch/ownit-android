package app.ownit.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
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
        s.setUserAgentString(s.getUserAgentString() + " OwnItAndroid/0.1");
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
        ComponentName me = new ComponentName(this, GuardService.class);
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        TextUtils.SimpleStringSplitter split = new TextUtils.SimpleStringSplitter(':');
        split.setString(enabled);
        for (String item : split) {
            ComponentName c = ComponentName.unflattenFromString(item);
            if (me.equals(c)) return true;
        }
        return false;
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
                o.put("version", "0.1");
                o.put("allowed", GuardState.allowedPlatform(MainActivity.this));
                o.put("until", GuardState.until(MainActivity.this));
                o.put("tiktokInstalled", installedPackage("tiktok") != null);
                o.put("instagramInstalled", installedPackage("instagram") != null);
                o.put("youtubeInstalled", installedPackage("youtube") != null);
                o.put("restrictedSettings", Build.VERSION.SDK_INT >= 33);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void openGuardSettings() {
            runOnUiThread(() -> {
                Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
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
