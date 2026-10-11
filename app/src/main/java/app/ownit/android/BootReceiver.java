package app.ownit.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Turns OwnIt Guard back on after the phone restarts or OwnIt is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        GuardState.init(c);
        // A visit that ran out while the phone was off: end it, so its timer doesn't carry on.
        long until = GuardState.until(c);
        if (until > 0 && System.currentTimeMillis() >= until) GuardState.expire(c);
        else if (until > 0) TimeUp.schedule(c, until);
        GuardService.startIfReady(c);
        Widget.refresh(c);
    }
}
