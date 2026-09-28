package app.ownit.android;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * OwnIt Guard.
 * - Opening a guarded app (e.g. TikTok) without a visit started in OwnIt sends you to OwnIt instead.
 * - When a visit's time is up while you're still in the app, a full-screen "Time's up" screen covers it
 *   and takes you back to OwnIt.
 * It only looks at which app is in front. It never reads what's on the screen.
 */
public class GuardService extends AccessibilityService {

    private static final int COUNTDOWN_SECONDS = 8;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeUp = this::onTimeUp;
    private String foreground = "";
    private long lastBlock = 0;
    private View overlay;
    private TextView countdownView;
    private int secondsLeft;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (overlay == null) return;
            secondsLeft--;
            if (secondsLeft <= 0) { goBackToOwnIt(); return; }
            countdownView.setText("Taking you back to OwnIt in " + secondsLeft + "…");
            if (secondsLeft == 4) buzz();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // Just switched on from OwnIt's setup screen: bring the person straight back to OwnIt.
        if (GuardState.consumeSetupPending(this)) handler.postDelayed(() -> openOwnIt("guard-on", null), 400);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();
        // The system bar and keyboards report events while another app is still in front: ignore them.
        if (pkg.equals("com.android.systemui") || pkg.contains("inputmethod") || pkg.contains("keyboard")) return;
        foreground = pkg;
        if (pkg.equals(getPackageName())) return;

        String platform = GuardState.platformFor(pkg);
        if (platform == null || !GuardState.guarded(this).contains(platform)) return;

        if (GuardState.isAllowed(this, platform)) {
            scheduleTimeUp();
            return;
        }
        if (overlay != null) return;
        long now = System.currentTimeMillis();
        if (now - lastBlock < 1500) return;
        lastBlock = now;
        // Opened directly: send them to OwnIt first.
        performGlobalAction(GLOBAL_ACTION_HOME);
        final String p = platform;
        handler.postDelayed(() -> openOwnIt("blocked", p), 300);
    }

    private void scheduleTimeUp() {
        handler.removeCallbacks(timeUp);
        long delay = GuardState.until(this) - System.currentTimeMillis();
        handler.postDelayed(timeUp, Math.max(0, delay));
    }

    private void onTimeUp() {
        long left = GuardState.until(this) - System.currentTimeMillis();
        if (left > 500) { scheduleTimeUp(); return; } // the visit was extended
        String platform = GuardState.platformFor(foreground);
        boolean stillInApp = platform != null && platform.equals(GuardState.allowedPlatform(this));
        GuardState.expire(this);
        if (stillInApp) showOverlay();
        else Notifier.timeUp(this, GuardState.kind(this), GuardState.intent(this));
    }

    private void showOverlay() {
        if (overlay != null) return;
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) { goBackToOwnIt(); return; }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.parseColor("#F5061513"));
        int pad = dp(28);
        box.setPadding(pad, pad, pad, pad);
        box.setClickable(true);

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
        back.setOnClickListener(v -> goBackToOwnIt());
        box.addView(back);
        if (!GuardState.extended(this)) {
            Button more = button("I need 2 more minutes", false);
            more.setOnClickListener(v -> {
                GuardState.extend(this, 2 * 60 * 1000L);
                removeOverlay();
                scheduleTimeUp();
            });
            box.addView(more);
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        try {
            wm.addView(box, lp);
            overlay = box;
        } catch (Exception e) {
            goBackToOwnIt();
            return;
        }
        buzz();
        secondsLeft = COUNTDOWN_SECONDS;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1000);
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

    private void goBackToOwnIt() {
        removeOverlay();
        performGlobalAction(GLOBAL_ACTION_HOME);
        handler.postDelayed(() -> openOwnIt("timeup", null), 300);
    }

    private void openOwnIt(String action, String platform) {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("ownit_action", action);
        if (platform != null) i.putExtra("platform", platform);
        try { startActivity(i); } catch (Exception ignored) { }
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

    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        removeOverlay();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
