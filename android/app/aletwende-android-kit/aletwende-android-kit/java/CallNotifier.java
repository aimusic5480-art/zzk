package __PKG__;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.util.Map;

/** Builds the loud, full-screen "incoming call" notification (and simple alerts). */
final class CallNotifier {
    static final String CH_CALLS = "incoming_calls_v2";
    static final String CH_REQUESTS = "incoming_requests_v2";
    static final String CH_ORDERS = "incoming_orders_v2";
    static final String CH_ALERTS = "new_orders";
    static final String CH_ONGOING = "call_ongoing_v1";
    static final String NOTIF_TAG = "aletwende";
    static final String API_BASE = "https://aletwend-render-backend.onrender.com";
    static final long CALL_RING_MS = 45000;
    static final long REQUEST_RING_MS = 30000;

    /** true while the web app is on screen: it rings by itself, so we stay quiet. */
    static volatile boolean appVisible = false;

    private static int piFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    static String v(Map<String, String> d, String key) {
        String s = d == null ? null : d.get(key);
        return s == null ? "" : s;
    }

    /** Uses res/raw/<rawName>.mp3 (the app's own tone) if it was copied in, else the phone ringtone. */
    private static void makeRingChannel(Context ctx, NotificationManager nm, AudioAttributes attrs, String id, String name, String rawName) {
        NotificationChannel ch = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
        int res = ctx.getResources().getIdentifier(rawName, "raw", ctx.getPackageName());
        Uri sound = res != 0
            ? Uri.parse("android.resource://" + ctx.getPackageName() + "/" + res)
            : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        ch.setSound(sound, attrs);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 800, 600, 800, 600});
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        AudioAttributes ringAttrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
        makeRingChannel(ctx, nm, ringAttrs, CH_CALLS, "Incoming calls", "call_tone");
        makeRingChannel(ctx, nm, ringAttrs, CH_REQUESTS, "Trip requests", "request_tone");
        makeRingChannel(ctx, nm, ringAttrs, CH_ORDERS, "New orders", "order_tone");

        AudioAttributes alertAttrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
        NotificationChannel alerts = new NotificationChannel(CH_ALERTS, "Orders and updates", NotificationManager.IMPORTANCE_HIGH);
        alerts.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), alertAttrs);
        alerts.enableVibration(true);
        nm.createNotificationChannel(alerts);

        NotificationChannel ongoing = new NotificationChannel(CH_ONGOING, "Call in progress", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ongoing);
    }

    /** Intent that opens the web app and tells it what the user chose. */
    static Intent openAppIntent(Context ctx, String action, Map<String, String> d) {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (i == null) i = new Intent();
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra("aw_action", action);
        i.putExtra("callId", v(d, "callId"));
        i.putExtra("orderId", v(d, "orderId"));
        i.putExtra("type", v(d, "type"));
        return i;
    }

    static void showIncoming(Context ctx, Map<String, String> d) {
        if (appVisible) return;
        String type = v(d, "type");
        boolean isCall = "incoming_call".equals(type);
        String tag = isCall ? v(d, "callId") : v(d, "orderId");
        if (tag.isEmpty()) return;
        ensureChannels(ctx);

        int id = tag.hashCode();
        long ringMs = isCall ? CALL_RING_MS : REQUEST_RING_MS;
        String title = v(d, "title").isEmpty() ? (isCall ? "Incoming call" : ("new_order".equals(type) ? "New order" : "New trip request")) : v(d, "title");
        String body = v(d, "body").isEmpty() ? "Tap to open" : v(d, "body");

        Intent full = new Intent(ctx, IncomingCallActivity.class);
        full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        full.putExtra("type", type);
        full.putExtra("callId", v(d, "callId"));
        full.putExtra("orderId", v(d, "orderId"));
        full.putExtra("callerName", v(d, "callerName"));
        full.putExtra("title", title);
        full.putExtra("body", body);
        full.putExtra("actionToken", v(d, "actionToken"));
        PendingIntent fullPi = PendingIntent.getActivity(ctx, id, full, piFlags());

        PendingIntent openPi = PendingIntent.getActivity(ctx, id + 1, openAppIntent(ctx, isCall ? "answer" : "open", d), piFlags());

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, isCall ? CH_CALLS : ("new_order".equals(type) ? CH_ORDERS : CH_REQUESTS))
            .setSmallIcon(ctx.getApplicationInfo().icon)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(openPi)
            .setTimeoutAfter(ringMs);

        if (isCall) {
            Intent decline = new Intent(ctx, CallActionReceiver.class);
            decline.setAction(CallActionReceiver.ACTION_DECLINE);
            decline.putExtra("callId", v(d, "callId"));
            decline.putExtra("actionToken", v(d, "actionToken"));
            PendingIntent declinePi = PendingIntent.getBroadcast(ctx, id + 2, decline, piFlags());
            b.addAction(0, "Decline", declinePi);
            b.addAction(0, "Answer", openPi);
        } else {
            b.addAction(0, "Open", openPi);
        }

        if (Build.VERSION.SDK_INT < 26) {
            Uri ring = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            b.setSound(ring, android.media.AudioManager.STREAM_RING);
            b.setVibrate(new long[]{0, 800, 600, 800, 600});
        }

        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT; // keep ringing until answered, cancelled or timed out

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_TAG, id, n);
    }

    static void showAlert(Context ctx, Map<String, String> d) {
        if (appVisible) return;
        ensureChannels(ctx);
        String title = v(d, "title").isEmpty() ? "Aletwende" : v(d, "title");
        String body = v(d, "body");
        String key = !v(d, "orderId").isEmpty() ? v(d, "orderId") : String.valueOf(System.currentTimeMillis());
        PendingIntent openPi = PendingIntent.getActivity(ctx, key.hashCode(), openAppIntent(ctx, "open", d), piFlags());
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(ctx.getApplicationInfo().icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPi);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_TAG, key.hashCode(), b.build());
    }

    /** Stops the ringing for one call or request and closes the full-screen screen. */
    static void cancel(Context ctx, String tag) {
        if (tag == null || tag.isEmpty()) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_TAG, tag.hashCode());
        IncomingCallActivity.finishIfShowing(tag);
    }
}
