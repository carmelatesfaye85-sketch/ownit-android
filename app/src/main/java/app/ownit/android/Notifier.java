package app.ownit.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;

/** "Time's up" notification, used when you already left the app before time ran out. */
final class Notifier {
    private static final String CHANNEL = "timeup";

    private Notifier() {}

    static boolean enabled(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.areNotificationsEnabled();
    }

    /** Tells the person the Guard stopped because a permission was switched off. */
    static void guardOff(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return;
        NotificationChannel ch = new NotificationChannel("guard_off", "Guard turned off", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Tells you when OwnIt Guard stops working.");
        nm.createNotificationChannel(ch);
        Intent open = new Intent(c, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 4, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, "guard_off")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("OwnIt Guard is off")
                .setContentText("A permission was switched off. Tap to turn the Guard back on.")
                .setColor(Color.parseColor("#0C6B65"))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        nm.notify(8, n);
    }

    static void timeUp(Context c, String kind, String intent) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Time's up", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Tells you when your planned time is over.");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[] {0, 400, 150, 400});
        nm.createNotificationChannel(ch);

        Intent open = new Intent(c, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("ownit_action", "timeup");
        PendingIntent pi = PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String body = "search".equals(kind) && intent != null && !intent.isEmpty()
                ? "Did you find “" + intent + "”? Tap to check in."
                : "Your scrolling time is over. Tap to check in.";
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Time's up")
                .setContentText(body)
                .setColor(Color.parseColor("#0C6B65"))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build();
        nm.notify(7, n);
    }
}
