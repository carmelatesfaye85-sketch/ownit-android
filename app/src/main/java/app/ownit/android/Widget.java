package app.ownit.android;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.Calendar;

/** Home screen widget: minutes left today, the streak, and a button that opens OwnIt on the search box. */
public class Widget extends AppWidgetProvider {
    private static final String PREFS = "ownit_widget";
    private static long lastRefresh = 0;

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        draw(c, m, ids);
    }

    /** The website sends {goal, used, streak} each time Home is shown. */
    static void save(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            prefs(c).edit()
                    .putInt("goal", o.optInt("goal", 45))
                    .putInt("used", o.optInt("used", 0))
                    .putString("streak", o.optString("streak", ""))
                    .putLong("at", System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) { }
        refresh(c);
    }

    /** Redraws every OwnIt widget. Safe to call often. */
    static void refresh(Context c) {
        lastRefresh = System.currentTimeMillis();
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, Widget.class));
            if (ids != null && ids.length > 0) draw(c, m, ids);
        } catch (Exception ignored) { }
    }

    /** Called by the Guard every few seconds; redraws at most every 2 minutes. */
    static void refreshIfDue(Context c) {
        if (System.currentTimeMillis() - lastRefresh > 120_000L) refresh(c);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean sameDay(long t) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        a.setTimeInMillis(t);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static void draw(Context c, AppWidgetManager m, int[] ids) {
        SharedPreferences p = prefs(c);
        boolean known = p.getLong("at", 0) > 0;
        int goal = p.getInt("goal", 45);
        int used = sameDay(p.getLong("at", 0)) ? p.getInt("used", 0) : 0;
        int left = Math.max(0, goal - used);
        String streak = p.getString("streak", "");

        String big = !known ? "Your day is yours" : left == 0 ? "That's enough for today" : left + " min left today";
        StringBuilder sub = new StringBuilder();
        if (known) sub.append(used).append(" of ").append(goal).append(" min used");
        if (Perms.usage(c)) {
            int real = Usage.todayMinutes(c);
            if (sub.length() > 0) sub.append(" · ");
            sub.append("phone count ").append(real).append(" min");
        }
        if (!streak.isEmpty()) {
            if (sub.length() > 0) sub.append("\n");
            sub.append(streak);
        }
        if (sub.length() == 0) sub.append("Open OwnIt to see your day.");

        Intent open = new Intent(c, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(c, 10, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent search = new Intent(c, MainActivity.class);
        search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        search.putExtra("ownit_action", "search");
        PendingIntent searchPi = PendingIntent.getActivity(c, 11, search, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_ownit);
            v.setTextViewText(R.id.w_left, big);
            v.setTextViewText(R.id.w_sub, sub.toString());
            v.setOnClickPendingIntent(R.id.widget_root, openPi);
            v.setOnClickPendingIntent(R.id.w_search, searchPi);
            try { m.updateAppWidget(id, v); } catch (Exception ignored) { }
        }
    }
}
