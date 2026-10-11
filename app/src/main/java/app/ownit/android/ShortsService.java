package app.ownit.android;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

/**
 * Hide Reels and Shorts.
 * You can watch the one Reel or Short you opened (for example from a search), but swiping on to the
 * next one takes you back. Scroll on purpose visits started in OwnIt are left alone.
 * It only looks for the Reels / Shorts player by its name in the app. It never saves or sends anything.
 *
 * This depends on how Instagram and YouTube build their screens, so an app update can stop it working
 * until OwnIt is updated too.
 */
public class ShortsService extends AccessibilityService {
    private static final String PREFS = "ownit_shorts";

    /** The full-screen Reels / Shorts players. */
    private static final String[] PLAYER_IDS = {
            "com.google.android.youtube:id/reel_player_page_container",
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_watch_player",
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_viewer_fragment_container",
            "com.instagram.android:id/root_clips_layout",
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long playerSince = 0;
    private long lastBack = 0;

    static boolean wanted(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false);
    }

    static void setWanted(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply();
    }

    /** True when this service is switched on in Android's accessibility settings. */
    static boolean enabled(Context c) {
        try {
            String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null) return false;
            TextUtils.SimpleStringSplitter s = new TextUtils.SimpleStringSplitter(':');
            s.setString(list);
            String me = c.getPackageName() + "/" + ShortsService.class.getName();
            String shortName = c.getPackageName() + "/." + ShortsService.class.getSimpleName();
            while (s.hasNext()) {
                String item = s.next();
                if (item.equalsIgnoreCase(me) || item.equalsIgnoreCase(shortName)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !wanted(this)) return;
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String platform = GuardState.platformFor(pkgCs.toString());
        if (!"youtube".equals(platform) && !"instagram".equals(platform)) return;
        // A "Scroll on purpose" visit: the person chose to scroll, so leave it alone.
        if (platform.equals(GuardState.allowedPlatform(this)) && GuardState.until(this) > System.currentTimeMillis()
                && !"search".equals(GuardState.kind(this))) return;

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            playerSince = hasPlayer() ? System.currentTimeMillis() : 0;
            return;
        }
        if (type != AccessibilityEvent.TYPE_VIEW_SCROLLED) return;
        long now = System.currentTimeMillis();
        if (!isPagerSwipe(event)) return;
        if (!hasPlayer()) { playerSince = 0; return; }
        if (playerSince == 0) { playerSince = now; return; }   // first sight of the player: let it settle
        if (now - playerSince < 1500 || now - lastBack < 2000) return;
        lastBack = now;
        playerSince = 0;
        performGlobalAction(GLOBAL_ACTION_BACK);
        String what = "youtube".equals(platform) ? "Shorts" : "Reels";
        handler.post(() -> {
            try { Toast.makeText(this, "OwnIt: one at a time. " + what + " are hidden.", Toast.LENGTH_SHORT).show(); }
            catch (Exception ignored) { }
        });
    }

    /** A scroll by something almost as tall as the screen: swiping to the next video, not reading comments. */
    private boolean isPagerSwipe(AccessibilityEvent event) {
        AccessibilityNodeInfo src = event.getSource();
        if (src == null) return false;
        try {
            Rect r = new Rect();
            src.getBoundsInScreen(r);
            int screen = getResources().getDisplayMetrics().heightPixels;
            return screen > 0 && r.height() > screen * 0.8;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasPlayer() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            for (String id : PLAYER_IDS) {
                List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
                if (found != null) {
                    for (AccessibilityNodeInfo n : found) if (n != null && n.isVisibleToUser()) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    @Override
    public void onInterrupt() { }

    /** Opens Android's accessibility settings so the person can switch this on. */
    static void openSettings(Context c) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); } catch (Exception ignored) { }
    }
}
