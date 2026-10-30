package __PKG__;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/** The full-screen "Incoming call" screen shown over the lock screen. */
public class IncomingCallActivity extends Activity {
    private static IncomingCallActivity current;

    private String tag = "";
    private String callId = "";
    private String orderId = "";
    private String type = "";
    private String actionToken = "";
    private final Handler handler = new Handler(Looper.getMainLooper());

    static void finishIfShowing(String tag) {
        final IncomingCallActivity a = current;
        if (a != null && tag != null && tag.equals(a.tag)) {
            a.runOnUiThread(new Runnable() { @Override public void run() { a.finish(); } });
        }
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        current = this;

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        type = nz(getIntent().getStringExtra("type"));
        callId = nz(getIntent().getStringExtra("callId"));
        orderId = nz(getIntent().getStringExtra("orderId"));
        actionToken = nz(getIntent().getStringExtra("actionToken"));
        boolean isCall = "incoming_call".equals(type);
        tag = isCall ? callId : orderId;

        String label = isCall ? "Incoming call" : ("new_order".equals(type) ? "New order" : "New trip request");
        String name = nz(getIntent().getStringExtra("callerName"));
        if (name.isEmpty()) name = nz(getIntent().getStringExtra("title"));
        String sub = nz(getIntent().getStringExtra("body"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.parseColor("#0B1220"));
        root.setPadding(dp(24), dp(96), dp(24), dp(64));

        root.addView(text(label, 16, Color.parseColor("#9FB0C8"), false));
        TextView nameView = text(name, 34, Color.WHITE, true);
        nameView.setPadding(0, dp(16), 0, dp(8));
        root.addView(nameView);
        root.addView(text(sub, 16, Color.parseColor("#9FB0C8"), false));

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        if (isCall) {
            row.addView(button("Decline", "#E5484D", new View.OnClickListener() {
                @Override public void onClick(View v) { decline(); }
            }));
            row.addView(button("Answer", "#30A46C", new View.OnClickListener() {
                @Override public void onClick(View v) { open("answer"); }
            }));
        } else {
            row.addView(button("Open", "#30A46C", new View.OnClickListener() {
                @Override public void onClick(View v) { open("open"); }
            }));
        }
        root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        handler.postDelayed(new Runnable() {
            @Override public void run() { finish(); }
        }, isCall ? CallNotifier.CALL_RING_MS : CallNotifier.REQUEST_RING_MS);
    }

    private void open(String action) {
        Map<String, String> d = new HashMap<String, String>();
        d.put("callId", callId);
        d.put("orderId", orderId);
        d.put("type", type);
        startActivity(CallNotifier.openAppIntent(this, action, d));
        CallNotifier.cancel(this, tag);
        finish();
    }

    private void decline() {
        final String id = callId;
        final String token = actionToken;
        new Thread(new Runnable() {
            @Override public void run() { CallActionReceiver.postDecline(id, token); }
        }).start();
        CallNotifier.cancel(this, tag);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (current == this) current = null;
        super.onDestroy();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, String color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(18);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(color));
        bg.setCornerRadius(dp(40));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(64), 1f);
        lp.setMargins(dp(12), 0, dp(12), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }
}
