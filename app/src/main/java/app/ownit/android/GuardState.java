package app.ownit.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Remembers which apps are guarded and which visit is currently allowed. */
final class GuardState {
    private static final String PREFS = "ownit_guard";

    static final Map<String, String[]> PACKAGES = new HashMap<>();
    static {
        PACKAGES.put("tiktok", new String[] {"com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go"});
        PACKAGES.put("instagram", new String[] {"com.instagram.android", "com.instagram.lite"});
        PACKAGES.put("youtube", new String[] {"com.google.android.youtube"});
    }

    private GuardState() {}

    static String platformFor(String pkg) {
        if (pkg == null) return null;
        for (Map.Entry<String, String[]> e : PACKAGES.entrySet()) {
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

    /** Set when OwnIt sends you to the Accessibility screen, so the Guard can bring you back once it's on. */
    static void markSetupPending(Context c) { prefs(c).edit().putLong("setup_pending", System.currentTimeMillis()).apply(); }

    static boolean consumeSetupPending(Context c) {
        long t = prefs(c).getLong("setup_pending", 0);
        prefs(c).edit().remove("setup_pending").apply();
        return t > 0 && System.currentTimeMillis() - t < 15 * 60 * 1000L;
    }

    static void end(Context c) { prefs(c).edit().remove("allowed").putLong("until", 0).apply(); }
}
