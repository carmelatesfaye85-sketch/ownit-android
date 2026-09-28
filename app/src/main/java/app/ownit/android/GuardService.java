package app.ownit.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * OwnIt Guard.
 * - Opening a guarded app (e.g. TikTok) without a visit started in OwnIt covers it and sends you to OwnIt.
 * - When a visit's time is up while you're still in the app, a full-screen "Time's up" screen covers it
 *   and takes you back to OwnIt.
 * It only checks which app is in front (Usage access). It never reads what's on the screen.
 */
public class GuardService extends Service {

    private static final int COUNTDOWN_SECONDS = 8;
    private static final String CHANNEL = "guard";
    private static final int NOTIFICATION_ID = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String foreground = "";
    private long lastEventTime = 0;
    private long lastBlock = 0;
    private View overlay;
    private TextView countdownView;
    private int secondsLeft;
    private boolean running = false;

    /** Starts the Guard if both permissions are granted. Safe to call often. */
    static void startIfReady(Context c) {
        if (!Perms.guardReady(c)) return;
        try {
            Intent i = new Intent(c, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) { }
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long next = 700;
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isInteractive()) next = 3000;
                else check();
            } catch (Exception ignored) { }
            handler.postDelayed(this, next);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (overlay == null) return;
            secondsLeft--;
            if (secondsLeft <= 0) { goBackToOwnIt("timeup", null); return; }
            countdownView.setText("Taking you back to OwnIt in " + secondsLeft + "…");
            if (secondsLeft == 4) buzz();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = guardNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION_ID, n);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Perms.guardReady(this)) { stopSelf(); return START_NOT_STICKY; }
        if (!running) {
            running = true;
            lastEventTime = System.currentTimeMillis() - 10_000;
            handler.post(poll);
        }
        return START_STICKY;
    }

    /** Finds the app in front, then blocks it, lets it through, or ends the visit. */
    private void check() {
        if (!Perms.guardReady(this)) { stopSelf(); return; }
        updateForeground();
        if (overlay != null) return;
        String pkg = foreground;
        if (pkg.isEmpty() || pkg.equals(getPackageName())) {
            timeUpIfDue(false);
            return;
        }
        String platform = GuardState.platformFor(pkg);
        boolean guarded = platform != null && GuardState.guarded(this).contains(platform);
        if (!guarded) { timeUpIfDue(false); return; }

        if (platform.equals(GuardState.allowedPlatform(this)) && GuardState.until(this) > 0) {
            timeUpIfDue(true);            // inside the allowed visit: only act when time is up
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBlock < 1500) return;
        lastBlock = now;
        showBlocked(platform);
    }

    private void timeUpIfDue(boolean inAllowedApp) {
        long until = GuardState.until(this);
        if (until <= 0 || System.currentTimeMillis() < until) return;
        GuardState.expire(this);
        if (inAllowedApp) showTimeUp();
        else Notifier.timeUp(this, GuardState.kind(this), GuardState.intent(this));
    }

    private void updateForeground() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return;
        long now = System.currentTimeMillis();
        long from = Math.max(now - 3_600_000L, Math.min(lastEventTime, now - 2000));
        UsageEvents events = usm.queryEvents(from, now + 1000);
        UsageEvents.Event e = new UsageEvents.Event();
        String latest = null;
        long latestTime = 0;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            int type = e.getEventType();
            if ((type == UsageEvents.Event.ACTIVITY_RESUMED || type == UsageEvents.Event.MOVE_TO_FOREGROUND)
                    && e.getTimeStamp() >= latestTime) {
                latest = e.getPackageName();
                latestTime = e.getTimeStamp();
            }
        }
        if (latest != null) {
            foreground = latest;
            lastEventTime = latestTime;
        }
    }

    // ---------------------------------------------------------------- screens

    private void showBlocked(String platform) {
        String name = "tiktok".equals(platform) ? "TikTok" : "instagram".equals(platform) ? "Instagram" : "YouTube";
        LinearLayout box = screen();
        box.addView(text("∞", 64, "#F0B155", true));
        box.addView(text("Not so fast", 40, "#E8F3F0", true));
        box.addView(text("You opened " + name + " directly. Say what you're looking for first, and OwnIt will take you straight to it.", 18, "#93B3AC", false));
        Button go = button("Open OwnIt", true);
        go.setOnClickListener(v -> goBackToOwnIt("blocked", platform));
        box.addView(go);
        if (!addOverlay(box)) { openOwnIt("blocked", platform); return; }
        // Open OwnIt right away while this screen covers the app.
        handler.postDelayed(() -> goBackToOwnIt("blocked", platform), 900);
    }

    private void showTimeUp() {
        LinearLayout box = screen();
        String kind = GuardState.kind(this);
        String intent = GuardState.intent(this);
        box.addView(text("∞", 72, "#F0B155", true));
        box.addView(text("Time's up", 44, "#E8F3F0", true));
        box.addView(text("search".equals(kind) && !intent.isEmpty()
                ? "Did you find “" + intent + "”?"
                : "Your scrolling time is over.", 20, "#93B3AC", false));
        countdownView = text("Taking you back to OwnIt in " + COUNTDOWN_SECONDS + "…", 16, "#F0B155", false);
        box.addView(countdownView);

        Button back = button("Back to OwnIt", true);
        back.setOnClickListener(v -> goBackToOwnIt("timeup", null));
        box.addView(back);
        if (!GuardState.extended(this)) {
            Button more = button("I need 2 more minutes", false);
            more.setOnClickListener(v -> {
                GuardState.extend(this, 2 * 60 * 1000L);
                removeOverlay();
            });
            box.addView(more);
        }
        if (!addOverlay(box)) { Notifier.timeUp(this, kind, intent); openOwnIt("timeup", null); return; }
        buzz();
        secondsLeft = COUNTDOWN_SECONDS;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1000);
    }

    private LinearLayout screen() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.parseColor("#FA061513"));
        int pad = dp(28);
        box.setPadding(pad, pad, pad, pad);
        box.setClickable(true);
        return box;
    }

    private boolean addOverlay(View v) {
        if (overlay != null) return true;
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null || !Perms.overlay(this)) return false;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        try {
            wm.addView(v, lp);
            overlay = v;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void removeOverlay() {
        handler.removeCallbacks(tick);
        if (overlay == null) return;
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.removeView(overlay);
        } catch (Exception ignored) { }
        overlay = null;
    }

    /** Opens OwnIt while the cover screen is still showing, then removes the cover. */
    private void goBackToOwnIt(String action, String platform) {
        handler.removeCallbacks(tick);
        openOwnIt(action, platform);
        foreground = getPackageName();
        handler.postDelayed(this::removeOverlay, 700);
    }

    private void openOwnIt(String action, String platform) {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("ownit_action", action);
        if (platform != null) i.putExtra("platform", platform);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- helpers

    private Notification guardNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "OwnIt Guard", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Shows that OwnIt Guard is running.");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("OwnIt Guard is on")
                .setContentText("Open social apps through OwnIt.")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void buzz() {
        try {
            long[] pattern = {0, 400, 180, 400, 180, 700};
            Vibrator v;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v != null) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } catch (Exception ignored) { }
        try {
            Ringtone r = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            if (r != null) r.play();
        } catch (Exception ignored) { }
    }

    private TextView text(String s, int sp, String color, boolean serif) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(Color.parseColor(color));
        tv.setGravity(Gravity.CENTER);
        if (serif) tv.setTypeface(Typeface.SERIF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        tv.setLayoutParams(lp);
        return tv;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTextColor(Color.parseColor(primary ? "#1B1204" : "#E8F3F0"));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(30));
        if (primary) bg.setColor(Color.parseColor("#F0B155"));
        else { bg.setColor(Color.TRANSPARENT); bg.setStroke(dp(1), Color.parseColor("#4DE8F3F0")); }
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(280), dp(56));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        removeOverlay();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
