package __PKG__;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/** Receives the data-only push messages from the Aletwende backend, even when the app is closed. */
public class CallMessagingService extends FirebaseMessagingService {

    @Override
    public void onNewToken(String token) {
        AletwendeCallsPlugin.emitToken(token);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        Map<String, String> d = message.getData();
        if (d == null || d.isEmpty()) return;
        String type = CallNotifier.v(d, "type");

        if ("call_ended".equals(type)) {
            CallNotifier.cancel(this, CallNotifier.v(d, "callId"));
            return;
        }
        if ("request_ended".equals(type) || "order_ended".equals(type)) {
            CallNotifier.cancel(this, CallNotifier.v(d, "orderId"));
            return;
        }
        if ("incoming_call".equals(type) || "new_trip_request".equals(type) || "new_order".equals(type)) {
            CallNotifier.showIncoming(this, d);
            return;
        }
        CallNotifier.showAlert(this, d);
    }
}
