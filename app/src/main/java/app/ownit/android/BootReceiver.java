package app.ownit.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Turns OwnIt Guard back on after the phone restarts or OwnIt is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        GuardService.startIfReady(c);
    }
}
