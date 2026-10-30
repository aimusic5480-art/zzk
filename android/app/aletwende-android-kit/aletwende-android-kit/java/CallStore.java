package __PKG__;

import android.content.Context;
import android.content.SharedPreferences;

/** Remembers what the user tapped (Answer / Open) until the web app is ready to read it. */
final class CallStore {
    private static final String PREFS = "aletwende_calls";
    private static final long MAX_AGE_MS = 120000;

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void putPending(Context c, String action, String callId, String orderId, String type) {
        p(c).edit()
            .putString("pa", action)
            .putString("pc", callId)
            .putString("po", orderId)
            .putString("pt", type)
            .putLong("ts", System.currentTimeMillis())
            .apply();
    }

    /** Returns {action, callId, orderId, type} once, or null. */
    static String[] takePending(Context c) {
        SharedPreferences sp = p(c);
        String a = sp.getString("pa", null);
        long ts = sp.getLong("ts", 0);
        String[] r = null;
        if (a != null && System.currentTimeMillis() - ts <= MAX_AGE_MS) {
            r = new String[]{a, sp.getString("pc", ""), sp.getString("po", ""), sp.getString("pt", "")};
        }
        sp.edit().remove("pa").remove("pc").remove("po").remove("pt").remove("ts").apply();
        return r;
    }
}
