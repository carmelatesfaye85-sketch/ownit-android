package app.ownit.android;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Real time spent in TikTok, Instagram and YouTube, measured by the phone (Usage access),
 * including time spent outside OwnIt (and the apps the person added). Only app names and times: never what's on the screen.
 */
final class Usage {
    private Usage() {}

    private static long startOfDay(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long addDays(long dayStart, int n) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dayStart);
        c.add(Calendar.DAY_OF_MONTH, n);
        return c.getTimeInMillis();
    }

    /** Milliseconds per app for each of the last `days` days (index 0 = oldest, last = today). */
    static Map<String, long[]> perDay(Context c, int days) {
        GuardState.init(c);
        Map<String, long[]> out = new HashMap<>();
        for (String p : GuardState.allPlatforms()) out.put(p, new long[days]);
        if (!Perms.usage(c)) return out;
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return out;
        long now = System.currentTimeMillis();
        long today = startOfDay(now);
        long[] dayStart = new long[days + 1];
        for (int i = 0; i < days; i++) dayStart[i] = addDays(today, i - (days - 1));
        dayStart[days] = addDays(today, 1);
        long from = dayStart[0];

        Map<String, Long> openSince = new HashMap<>();
        try {
            UsageEvents events = usm.queryEvents(from, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                String platform = GuardState.platformFor(e.getPackageName());
                if (platform == null) continue;
                int type = e.getEventType();
                String key = e.getPackageName() + "/" + e.getClassName();
                if (type == UsageEvents.Event.ACTIVITY_RESUMED || type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    if (!openSince.containsKey(key)) openSince.put(key, e.getTimeStamp());
                } else if (type == UsageEvents.Event.ACTIVITY_PAUSED || type == UsageEvents.Event.MOVE_TO_BACKGROUND
                        || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                    Long start = openSince.remove(key);
                    if (start != null) add(out.get(platform), dayStart, start, e.getTimeStamp());
                }
            }
        } catch (Exception ignored) { }
        // Still open right now: count up to now.
        for (Map.Entry<String, Long> open : openSince.entrySet()) {
            String pkg = open.getKey().substring(0, open.getKey().indexOf('/'));
            String platform = GuardState.platformFor(pkg);
            if (platform != null && now - open.getValue() < 6 * 3_600_000L) add(out.get(platform), dayStart, open.getValue(), now);
        }
        return out;
    }

    /** Splits one stretch of time across the days it covers. */
    private static void add(long[] out, long[] dayStart, long a, long b) {
        if (out == null || b <= a || b - a > 6 * 3_600_000L) return;
        for (int d = 0; d < out.length; d++) {
            long s = Math.max(a, dayStart[d]), t = Math.min(b, dayStart[d + 1]);
            if (t > s) out[d] += t - s;
        }
    }

    /** Minutes spent today in all the apps OwnIt knows, together. */
    static int todayMinutes(Context c) {
        long total = 0;
        for (long[] d : perDay(c, 1).values()) total += d[0];
        return (int) (total / 60_000L);
    }

    /** JSON for the website: {ok, days:[{day:"2026-10-11", tiktok:12, instagram:3, youtube:0}, ...]} (minutes). */
    static String json(Context c, int days) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", Perms.usage(c));
            org.json.JSONArray arr = new org.json.JSONArray();
            Map<String, long[]> d = perDay(c, days);
            long today = startOfDay(System.currentTimeMillis());
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            for (int i = 0; i < days; i++) {
                JSONObject day = new JSONObject();
                day.put("day", f.format(addDays(today, i - (days - 1))));
                for (Map.Entry<String, long[]> app : d.entrySet()) day.put(app.getKey(), Math.round(app.getValue()[i] / 60_000.0));
                arr.put(day);
            }
            o.put("days", arr);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"days\":[]}";
        }
    }
}
