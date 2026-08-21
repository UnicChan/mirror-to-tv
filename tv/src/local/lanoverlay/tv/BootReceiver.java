package local.lanoverlay.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "MirrorToTvBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent service = new Intent(context, OverlayService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not start overlay receiver", error);
        }
    }
}
