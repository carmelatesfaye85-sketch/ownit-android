package app.ownit.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Remembers which apps are guarded and which visit is currently allowed. */
final class GuardState {
    private static final String PREFS = "ownit_guard";

    static final Map<String, String[]> PACKAGES = new HashMap<>();
    static {
        PACKAGES.put("tiktok", new String[] {"com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go", "com.ss.android.ugc.trill.go"});
        PACKAGES.put("instagram", new String[] {"com.instagram.android", "com.instagram.lite"});
        PACKAGES.put("youtube", new String[] {"com.google.android.youtube"});
    }

    /** Apps the person added in OwnIt (Substack, their own…): id → {name, packages}. */
    private static final Map<String, String[]> EXTRA = new LinkedHashMap<>();
    private static final Map<String, String> EXTRA_NAMES = new HashMap<>();
    private static boolean extrasLoaded = false;

    private GuardState() {}

    /** Loads the added apps once. Call with any Context before looking apps up. */
    static synchronized void init(Context c) {
        if (extrasLoaded) return;
        extrasLoaded = true;
        parseExtras(prefs(c).getString("extra", "{}"));
    }

    /** Saves the added apps sent by the website: {"substack": {"name": "Substack", "pkgs": ["com.substack.app"]}, …}. */
    static synchronized void setExtras(Context c, String json) {
        prefs(c).edit().putString("extra", json == null ? "{}" : json).apply();
        extrasLoaded = true;
        parseExtras(json);
    }

    private static void parseExtras(String json) {
        EXTRA.clear();
        EXTRA_NAMES.clear();
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                if (PACKAGES.containsKey(id)) continue;
                JSONObject a = o.optJSONObject(id);
                if (a == null) continue;
                JSONArray arr = a.optJSONArray("pkgs");
                if (arr == null || arr.length() == 0) continue;
                String[] pkgs = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) pkgs[i] = arr.optString(i, "");
                EXTRA.put(id, pkgs);
                EXTRA_NAMES.put(id, a.optString("name", "this app"));
            }
        } catch (Exception ignored) { }
    }

    /** Every app OwnIt knows: TikTok, Instagram, YouTube, then the ones the person added. */
    static synchronized List<String> allPlatforms() {
        List<String> out = new ArrayList<>(Arrays.asList("tiktok", "instagram", "youtube"));
        out.addAll(EXTRA.keySet());
        return out;
    }

    static synchronized String[] packagesFor(String platform) {
        if (platform == null) return null;
        String[] p = PACKAGES.get(platform);
        return p != null ? p : EXTRA.get(platform);
    }

    static synchronized boolean isExtra(String platform) {
        return platform != null && EXTRA.containsKey(platform);
    }

    static synchronized String nameFor(String platform) {
        if ("tiktok".equals(platform)) return "TikTok";
        if ("instagram".equals(platform)) return "Instagram";
        if ("youtube".equals(platform)) return "YouTube";
        String n = EXTRA_NAMES.get(platform);
        return n == null ? "this app" : n;
    }

    static synchronized String platformFor(String pkg) {
        if (pkg == null) return null;
        for (Map.Entry<String, String[]> e : PACKAGES.entrySet()) {
            for (String p : e.getValue()) if (p.equals(pkg)) return e.getKey();
        }
        for (Map.Entry<String, String[]> e : EXTRA.entrySet()) {
            for (String p : e.getValue()) if (p.equals(pkg)) return e.getKey();
        }
        return null;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Set<String> guarded(Context c) {
        String csv = prefs(c).getString("guarded", "tiktok");
        return new HashSet<>(Arrays.asList(csv.split(",")));
    }

    static void setGuarded(Context c, String csv) {
        if (csv == null || csv.trim().isEmpty()) csv = "tiktok";
        prefs(c).edit().putString("guarded", csv.trim()).apply();
    }

    static void allow(Context c, String platform, long untilMs, String intent, String kind) {
        prefs(c).edit()
                .putString("allowed", platform)
                .putLong("until", untilMs)
                .putString("intent", intent == null ? "" : intent)
                .putString("kind", kind == null ? "search" : kind)
                .putBoolean("extended", false)
                .apply();
    }

    static boolean isAllowed(Context c, String platform) {
        SharedPreferences p = prefs(c);
        return platform != null && platform.equals(p.getString("allowed", ""))
                && System.currentTimeMillis() < p.getLong("until", 0);
    }

    /** The exact link the visit opened, so OwnIt can bring you back to it. */
    static void setUrl(Context c, String url) { prefs(c).edit().putString("url", url == null ? "" : url).apply(); }
    static String url(Context c) { return prefs(c).getString("url", ""); }

    static String allowedPlatform(Context c) { return prefs(c).getString("allowed", ""); }
    static long until(Context c) { return prefs(c).getLong("until", 0); }
    static String intent(Context c) { return prefs(c).getString("intent", ""); }
    static String kind(Context c) { return prefs(c).getString("kind", "search"); }
    static boolean extended(Context c) { return prefs(c).getBoolean("extended", false); }

    /** Time is up: the visit is no longer allowed, but we keep its details for the "Time's up" screen. */
    static void expire(Context c) { prefs(c).edit().putLong("until", 0).apply(); }

    static void extend(Context c, long ms) {
        prefs(c).edit().putLong("until", System.currentTimeMillis() + ms).putBoolean("extended", true).apply();
    }

    static void end(Context c) { prefs(c).edit().remove("allowed").putLong("until", 0).apply(); }
}
