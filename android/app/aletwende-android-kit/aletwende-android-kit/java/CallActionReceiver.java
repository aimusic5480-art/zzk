package __PKG__;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Handles the "Decline" button without opening the app. */
public class CallActionReceiver extends BroadcastReceiver {
    static final String ACTION_DECLINE = "__PKG__.ACTION_DECLINE";

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (!ACTION_DECLINE.equals(intent.getAction())) return;
        final String callId = intent.getStringExtra("callId");
        final String token = intent.getStringExtra("actionToken");
        CallNotifier.cancel(context, callId);
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    postDecline(callId, token);
                } finally {
                    pending.finish();
                }
            }
        }).start();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Tells the backend the call was declined (signed one-time token, no login needed). */
    static void postDecline(String callId, String actionToken) {
        if (callId == null || callId.isEmpty() || actionToken == null || actionToken.isEmpty()) return;
        HttpURLConnection c = null;
        try {
            URL u = new URL(CallNotifier.API_BASE + "/api/calls/decline-quick");
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            String body = "{\"callId\":\"" + esc(callId) + "\",\"actionToken\":\"" + esc(actionToken) + "\"}";
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
            c.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
