package app.ownit.android;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Process;
import android.provider.Settings;

/** The two permissions OwnIt Guard needs. */
final class Perms {
    private Perms() {}

    /** "Display over other apps": lets OwnIt cover TikTok with the Time's up screen. */
    static boolean overlay(Context c) {
        return Settings.canDrawOverlays(c);
    }

    /** "Usage access": lets OwnIt see which app is open (never what's on the screen). */
    static boolean usage(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode = android.os.Build.VERSION.SDK_INT >= 29
                    ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                    : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return c.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** True when the phone won't put OwnIt to sleep to save battery. */
    static boolean battery(Context c) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    static boolean guardReady(Context c) {
        return overlay(c) && usage(c);
    }
}
