package __PKG__;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.ArrayList;
import java.util.List;

/** The bridge between the web app (JavaScript) and the native call code. */
@CapacitorPlugin(name = "AletwendeCalls")
public class AletwendeCallsPlugin extends Plugin {
    private static AletwendeCallsPlugin instance;

    @Override
    public void load() {
        instance = this;
        CallNotifier.ensureChannels(getContext());
        CallNotifier.appVisible = true;
        if (getActivity() != null) readIntent(getActivity().getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        readIntent(intent);
    }

    @Override
    protected void handleOnResume() {
        CallNotifier.appVisible = true;
    }

    @Override
    protected void handleOnPause() {
        CallNotifier.appVisible = false;
    }

    static void emitToken(String token) {
        if (instance == null) return;
        JSObject o = new JSObject();
        o.put("token", token);
        instance.notifyListeners("token", o, true);
    }

    private void readIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getStringExtra("aw_action");
        if (action == null) return;
        String callId = nz(intent.getStringExtra("callId"));
        String orderId = nz(intent.getStringExtra("orderId"));
        String type = nz(intent.getStringExtra("type"));
        intent.removeExtra("aw_action");
        CallNotifier.cancel(getContext(), callId.isEmpty() ? orderId : callId);
        CallStore.putPending(getContext(), action, callId, orderId, type);
        JSObject o = new JSObject();
        o.put("action", action);
        o.put("callId", callId);
        o.put("orderId", orderId);
        o.put("type", type);
        notifyListeners("callAction", o, true);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    @PluginMethod
    public void getToken(final PluginCall call) {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(new OnCompleteListener<String>() {
            @Override
            public void onComplete(@NonNull Task<String> task) {
                if (!task.isSuccessful() || task.getResult() == null) {
                    call.reject("Could not get the device token");
                    return;
                }
                JSObject o = new JSObject();
                o.put("token", task.getResult());
                call.resolve(o);
            }
        });
    }

    /** Called by the web app once it is ready, in case the user tapped Answer while the app was closed. */
    @PluginMethod
    public void getPendingAction(PluginCall call) {
        String[] p = CallStore.takePending(getContext());
        JSObject o = new JSObject();
        if (p != null) {
            o.put("action", p[0]);
            o.put("callId", p[1]);
            o.put("orderId", p[2]);
            o.put("type", p[3]);
        }
        call.resolve(o);
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        CallNotifier.cancel(getContext(), call.getString("tag", ""));
        call.resolve();
    }

    @PluginMethod
    public void askRuntimePermissions(PluginCall call) {
        List<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 33
            && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (!need.isEmpty() && getActivity() != null) {
            ActivityCompat.requestPermissions(getActivity(), need.toArray(new String[0]), 4711);
        }
        call.resolve();
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        Context c = getContext();
        JSObject o = new JSObject();
        o.put("notifications", NotificationManagerCompat.from(c).areNotificationsEnabled());
        boolean fullScreen = true;
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            fullScreen = nm != null && nm.canUseFullScreenIntent();
        }
        o.put("fullScreen", fullScreen);
        boolean battery = true;
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            battery = pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        }
        o.put("batteryIgnored", battery);
        o.put("microphone", ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
        call.resolve(o);
    }

    /** type: "fullScreen" | "notifications" | "battery" | "app" */
    @PluginMethod
    public void openSettings(PluginCall call) {
        String type = call.getString("type", "app");
        Context c = getContext();
        Intent i;
        Uri pkg = Uri.parse("package:" + c.getPackageName());
        if ("fullScreen".equals(type) && Build.VERSION.SDK_INT >= 34) {
            i = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg);
        } else if ("notifications".equals(type) && Build.VERSION.SDK_INT >= 26) {
            i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
        } else if ("battery".equals(type) && Build.VERSION.SDK_INT >= 23) {
            i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg);
        } else {
            i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(fallback);
        }
        call.resolve();
    }

    /** Keeps the microphone alive while a call runs in the background. */
    @PluginMethod
    public void startCallService(PluginCall call) {
        try {
            CallService.start(getContext(), call.getString("title", "Call in progress"));
        } catch (Exception ignored) {
        }
        call.resolve();
    }

    @PluginMethod
    public void stopCallService(PluginCall call) {
        CallService.stop(getContext());
        call.resolve();
    }
}
