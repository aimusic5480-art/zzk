package __PKG__;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * Keeps the microphone alive while a call is running and the app is in the background
 * (Android blocks the microphone for background apps unless a foreground service is running).
 */
public class CallService extends Service {
    private static final int ID = 7711;

    static void start(Context ctx, String title) {
        Intent i = new Intent(ctx, CallService.class);
        i.putExtra("title", title);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, CallService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        CallNotifier.ensureChannels(this);
        String title = intent != null && intent.getStringExtra("title") != null ? intent.getStringExtra("title") : "Call in progress";
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        android.app.PendingIntent pi = open == null ? null : android.app.PendingIntent.getActivity(
            this, 0, open, android.app.PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0));
        Notification n = new NotificationCompat.Builder(this, CallNotifier.CH_ONGOING)
            .setSmallIcon(getApplicationInfo().icon)
            .setContentTitle(title)
            .setContentText("Tap to return to the call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(pi)
            .build();
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(ID, n);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
