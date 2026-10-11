package app.ownit.android;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Wakes OwnIt Guard up when a visit's time is up, even if the phone put it to sleep
 * (or it didn't restart after the phone was switched off and on again).
 */
public class TimeUp extends BroadcastReceiver {
    static void schedule(Context c, long at) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(c, TimeUp.class);
            PendingIntent pi = PendingIntent.getBroadcast(c, 21, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at + 1000, pi);
        } catch (Exception ignored) { }
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        GuardService.startIfReady(c);
    }
}
