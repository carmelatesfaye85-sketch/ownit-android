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
        if (event == null) return;
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String platform = GuardState.platformFor(pkgCs.toString());
        if ("tiktok".equals(platform)) { keepOnSearch(pkgCs.toString()); return; }
        if (!wanted(this)) return;
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

    // ---------------------------------------------------------------------------------------------
    // TikTok search visits: OwnIt types your search into TikTok for you.
    //
    // OwnIt opens TikTok with the search link, which usually lands right on the results. But TikTok
    // sometimes shows the For You feed instead. So for the first seconds of a search visit OwnIt watches
    // the screen, and only if the feed shows it does what a person would: tap search, type, press Search.
    // If the For You feed comes back later in the visit, it does it again (a few times at most).
    // ---------------------------------------------------------------------------------------------
    static volatile ShortsService instance;
    private static final long WAIT_MS = 25_000;          // give up on one search after this long
    private int step = 0, tries = 0, redos = 0;
    private long stepAt = 0, visitKey = 0, clickedAt = 0;
    private boolean polling = false;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    /** Called by OwnIt right after it opens TikTok for a search visit. */
    static void searchSoon() {
        ShortsService s = instance;
        if (s != null) s.handler.post(s::startPolling);
    }

    private boolean searchVisit() {
        return "tiktok".equals(GuardState.allowedPlatform(this)) && GuardState.until(this) > System.currentTimeMillis()
                && "search".equals(GuardState.kind(this)) && !GuardState.intent(this).trim().isEmpty();
    }

    private void keepOnSearch(String pkg) {
        if (!searchVisit()) return;
        long key = GuardState.until(this);
        if (key != visitKey) { visitKey = key; redos = 0; step = 0; }
        if (GuardState.searchPending(this)) { startPolling(); return; }
        // The search was done. If the For You feed shows up again during the visit, search again.
        if (polling || redos >= 3) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && onTikTokFeed(root)) {
            redos++;
            GuardState.setSearchPending(this, true);
            startPolling();
        }
    }

    private void startPolling() {
        if (polling) return;
        polling = true; step = 0; tries = 0; stepAt = System.currentTimeMillis();
        handler.post(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            boolean again = false;
            try { again = searchStep(); } catch (Exception ignored) { again = true; }
            if (again && System.currentTimeMillis() - stepAt < WAIT_MS) handler.postDelayed(this, 400);
            else { polling = false; step = 0; GuardState.setSearchPending(ShortsService.this, false); }
        }
    };

    /** One look at the screen. Returns true to look again shortly, false when finished. */
    private boolean searchStep() {
        if (!searchVisit() || !GuardState.searchPending(this)) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !"tiktok".equals(GuardState.platformFor(root.getPackageName().toString()))) return true;  // TikTok still opening
        String q = GuardState.intent(this).trim();
        AccessibilityNodeInfo edit = find(root, n -> str(n.getClassName()).endsWith("EditText"));

        // Landed right on the results (the search link worked): nothing to do.
        if (step == 0 && edit != null && q.equalsIgnoreCase(str(edit.getText()).trim())) return false;

        if (edit == null) {
            if (step >= 2 && !onTikTokFeed(root)) return false;     // the results are showing: done
            // Only step in when TikTok shows its For You feed instead of the results.
            if (step == 0 && !onTikTokFeed(root)) return true;
            if (step == 1 && System.currentTimeMillis() - clickedAt < 1500) return true;   // let the search box open
            AccessibilityNodeInfo icon = searchIcon(root);
            if (icon == null) return true;
            clickedAt = System.currentTimeMillis();
            if (step == 0) toast("OwnIt: searching TikTok for “" + q + "”");
            icon.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            step = 1;
            return true;
        }

        // TikTok's search box is open.
        if (step < 2) {
            if (!q.equalsIgnoreCase(str(edit.getText()).trim())) {
                edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                edit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, q);
                if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    copy(q);
                    edit.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                }
                return true;                                        // check the text on the next look
            }
            submit(root, edit);
            step = 2; tries = 1;
            return true;
        }
        // Pressed Search but still on the search box (no results yet): press again, a couple of times at most.
        if (tries >= 3) return false;
        tries++;
        submit(root, edit);
        return true;
    }

    private void submit(AccessibilityNodeInfo root, AccessibilityNodeInfo edit) {
        AccessibilityNodeInfo go = clickable(find(root, n -> "search".equalsIgnoreCase(str(n.getText()).trim())
                && !str(n.getClassName()).endsWith("EditText")));
        boolean done = go != null && go.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!done && android.os.Build.VERSION.SDK_INT >= 30) {
            edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        }
    }

    /** TikTok's search icon: something near the top called "Search". */
    private AccessibilityNodeInfo searchIcon(AccessibilityNodeInfo root) {
        int top = (int) (getResources().getDisplayMetrics().heightPixels * 0.22);
        Rect r = new Rect();
        AccessibilityNodeInfo n = find(root, x -> {
            if (str(x.getClassName()).endsWith("EditText")) return false;
            String d = (str(x.getContentDescription()) + " " + str(x.getViewIdResourceName())).toLowerCase();
            if (!d.contains("search")) return false;
            x.getBoundsInScreen(r);
            return r.top < top;
        });
        return clickable(n);
    }

    private void copy(String q) {
        try {
            android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(android.content.ClipData.newPlainText("OwnIt search", q));
        } catch (Exception ignored) { }
    }

    private void toast(String text) {
        handler.post(() -> {
            try { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); } catch (Exception ignored) { }
        });
    }

    private static String str(CharSequence cs) { return cs == null ? "" : cs.toString(); }

    private interface Match { boolean test(AccessibilityNodeInfo n); }

    /** The first visible node on screen that matches (looks through at most 3000 nodes). */
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, Match m) {
        java.util.ArrayDeque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen++ < 3000) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isVisibleToUser() && m.test(n)) return n;
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return null;
    }

    /** The node itself if it can be tapped, otherwise the nearest parent that can. */
    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        int hops = 0;
        while (n != null && !n.isClickable() && hops++ < 5) n = n.getParent();
        return n;
    }

    /** True when TikTok shows its home feed: the "For You" and "Following" tabs near the top of the screen. */
    private boolean onTikTokFeed(AccessibilityNodeInfo root) {
        if (root == null) return false;
        try {
            int top = (int) (getResources().getDisplayMetrics().heightPixels * 0.25);
            return nearTop(root.findAccessibilityNodeInfosByText("For You"), top)
                    && nearTop(root.findAccessibilityNodeInfosByText("Following"), top);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean nearTop(List<AccessibilityNodeInfo> nodes, int top) {
        if (nodes == null) return false;
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null || !n.isVisibleToUser()) continue;
            n.getBoundsInScreen(r);
            if (r.top < top) return true;
        }
        return false;
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
