// src/native/calls.ts  (copy into each app)
// Bridge between the Android call kit (AletwendeCalls plugin) and the app.
// On the web (no native shell) every function is a safe no-op.
import { Capacitor, registerPlugin } from '@capacitor/core';

type CallAction = { action: 'answer' | 'decline' | 'open'; callId?: string; orderId?: string; type?: string };
type Status = { notifications: boolean; fullScreen: boolean; batteryIgnored: boolean; microphone: boolean };

interface AletwendeCallsPlugin {
  getToken(): Promise<{ token?: string }>;
  getPendingAction(): Promise<Partial<CallAction>>;
  cancel(o: { tag: string }): Promise<void>;
  askRuntimePermissions(): Promise<void>;
  getStatus(): Promise<Status>;
  openSettings(o: { type: 'fullScreen' | 'notifications' | 'battery' | 'app' }): Promise<void>;
  startCallService(o: { title?: string }): Promise<void>;
  stopCallService(): Promise<void>;
  addListener(ev: 'callAction', cb: (a: CallAction) => void): Promise<{ remove: () => void }>;
  addListener(ev: 'token', cb: (t: { token: string }) => void): Promise<{ remove: () => void }>;
}

export const NativeCalls: AletwendeCallsPlugin | null = Capacitor.isNativePlatform()
  ? registerPlugin<AletwendeCallsPlugin>('AletwendeCalls')
  : null;

export const isNative = () => !!NativeCalls;

const handled = new Set<string>();
let handles: { remove: () => void }[] = [];

/**
 * Call once after login.
 * saveToken(token): write to Firestore (fcmToken + arrayUnion fcmTokens) on users/drivers/stores doc.
 * onAnswer(callId): run the app's existing answerCall(callId) (wait until the call is 'ringing' first).
 * onOpen(orderId, type): optional, for tapped trip-request notifications.
 */
export async function initNativeCalls(opts: {
  saveToken: (token: string) => Promise<void> | void;
  onAnswer: (callId: string) => void;
  onOpen?: (orderId?: string, type?: string) => void;
}) {
  if (!NativeCalls) return;
  await disposeNativeCalls();
  try { await NativeCalls.askRuntimePermissions(); } catch {}
  try {
    const { token } = await NativeCalls.getToken();
    if (token) await opts.saveToken(token);
  } catch (e) { console.warn('[calls] token', e); }

  const handle = (a: Partial<CallAction>) => {
    if (!a || !a.action) return;
    const key = `${a.action}:${a.callId ?? a.orderId ?? ''}`;
    if (handled.has(key)) return; // retained event + getPendingAction both fire
    handled.add(key);
    setTimeout(() => handled.delete(key), 15000);
    if (a.action === 'answer' && a.callId) opts.onAnswer(a.callId);
    else if (a.action === 'open') opts.onOpen?.(a.orderId, a.type);
  };

  handles.push(await NativeCalls.addListener('callAction', handle));
  handles.push(await NativeCalls.addListener('token', (t) => t?.token && opts.saveToken(t.token)));
  try { handle(await NativeCalls.getPendingAction()); } catch {}
}

export async function disposeNativeCalls() {
  handles.forEach((h) => { try { h.remove(); } catch {} });
  handles = [];
}

/** Stop the ring / lock-screen screen when the call leaves 'ringing' (answered, declined, ended, missed). */
export const cancelCallNotification = (tag?: string | null) => {
  if (NativeCalls && tag) NativeCalls.cancel({ tag }).catch(() => {});
};
/** Keeps the microphone alive when the app goes to background during an active call. */
export const callServiceStart = (title = 'Call in progress') => NativeCalls?.startCallService({ title }).catch(() => {});
export const callServiceStop = () => NativeCalls?.stopCallService().catch(() => {});
export const getCallPermissionStatus = () => NativeCalls?.getStatus() ?? Promise.resolve(null);
export const openCallSettings = (type: 'fullScreen' | 'notifications' | 'battery' | 'app') =>
  NativeCalls?.openSettings({ type });
