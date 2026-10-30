import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { onAuthStateChanged } from 'firebase/auth';
import { onValue, ref } from 'firebase/database';
import { arrayRemove, arrayUnion, doc, updateDoc } from 'firebase/firestore';
import { auth, database, db } from '../config/firebase';
import { acceptCall, declineCall, endCall as apiEndCall, getMicPermissionState, joinChannel, MIC_BLOCKED_MESSAGE, primeMicrophone, startCall as apiStartCall, type ActiveCall, type CallCredentials, type MicError } from '../services/callService';
import { useActiveOrder } from '../hooks/useActiveOrder';
import { callServiceStart, callServiceStop, cancelCallNotification, disposeNativeCalls, initNativeCalls, isNative, NativeCalls } from '../native/calls';

type CallState = 'idle' | 'calling' | 'ringing' | 'in-call' | 'ended';
type CallSnapshot = { callId: string; orderId: string; channel: string; direction: 'incoming' | 'outgoing'; peerName?: string; status: 'ringing' | 'active' | 'ended' | 'declined' | 'missed' | 'cancelled' | 'trip_ended'; expiresAt?: number; startedAt?: number; durationSeconds?: number; };
type CallContextValue = { callState: CallState; peerName: string; elapsedSeconds: number; formattedTime: string; isMuted: boolean; micAvailable: boolean; micError: MicError | null; enableMicrophone: () => Promise<boolean>; endedMessage: string | null; startCall: (orderId: string, peerLabel?: string) => Promise<void>; answerCall: () => Promise<void>; declineCall: () => Promise<void>; endCall: () => Promise<void>; toggleMute: () => Promise<void>; };
const CallContext = createContext<CallContextValue | null>(null);
const terminal = new Set(['ended', 'declined', 'missed', 'cancelled', 'trip_ended']);
const formatTime = (seconds: number) => { const s = Math.max(0, seconds); const h = Math.floor(s / 3600); const m = Math.floor((s % 3600) / 60).toString().padStart(2, '0'); const rest = (s % 60).toString().padStart(2, '0'); return h ? `${h}:${m}:${rest}` : `${m}:${rest}`; };
const callErrorMessage = (error: unknown) => {
  const raw = error instanceof Error ? error.message : String(error);
  return /NOT_SUPPORTED|getUserMedia/i.test(raw)
    ? 'Calls need a secure (https) connection and microphone access.'
    : raw;
};

export function CallProvider({ children }: { children: React.ReactNode }) {
  const { order: activeOrder } = useActiveOrder();
  const [callState, setCallState] = useState<CallState>('idle');
  const [peerName, setPeerName] = useState('Driver');
  const peerLabelRef = useRef('Driver');
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [isMuted, setIsMuted] = useState(false);
  const [micAvailable, setMicAvailable] = useState(false);
  const [micError, setMicError] = useState<MicError | null>(null);
  const [micBannerDismissed, setMicBannerDismissed] = useState(false);
  const [micPermissionToastVisible, setMicPermissionToastVisible] = useState(false);
  const [endedMessage, setEndedMessage] = useState<string | null>(null);
  const [snapshot, setSnapshot] = useState<CallSnapshot | null>(null);
  const offsetRef = useRef(0);
  const activeRef = useRef<ActiveCall | null>(null);
  const snapshotRef = useRef<CallSnapshot | null>(null);
  const callStateRef = useRef<CallState>('idle');
  const activeCallIdRef = useRef<string | null>(null);
  const seenRef = useRef(false);
  const endingRef = useRef(false);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const ringTimerRef = useRef<number | null>(null);
  const endedTimerRef = useRef<number | null>(null);
  const micPreflightOrdersRef = useRef(new Set<string>());
  const micDeniedToastOrdersRef = useRef(new Set<string>());

  useEffect(() => { callStateRef.current = callState; }, [callState]);
  useEffect(() => { snapshotRef.current = snapshot; }, [snapshot]);

  const stopAudio = useCallback(() => { audioRef.current?.pause(); if (audioRef.current) audioRef.current.currentTime = 0; if (ringTimerRef.current !== null) { window.clearTimeout(ringTimerRef.current); ringTimerRef.current = null; } }, []);
  const leaveAgora = useCallback(async () => { await activeRef.current?.leave().catch(() => undefined); activeRef.current = null; }, []);

  const finishCall = useCallback(async (message: string) => {
    endingRef.current = true;
    stopAudio();
    callServiceStop();
    await leaveAgora();
    setElapsedSeconds(0);
    setIsMuted(false);
    setMicAvailable(false);
    setMicError(null);
    setMicBannerDismissed(false);
    setSnapshot(null);
    snapshotRef.current = null;
    seenRef.current = false;
    setCallState('ended');
    setEndedMessage(message);
    if (endedTimerRef.current !== null) window.clearTimeout(endedTimerRef.current);
    endedTimerRef.current = window.setTimeout(() => { setCallState('idle'); setEndedMessage(null); setPeerName('Driver'); peerLabelRef.current = 'Driver'; endingRef.current = false; activeCallIdRef.current = null; }, 2500);
  }, [leaveAgora, stopAudio]);

  const messageForTerminal = useCallback((next: CallSnapshot) => {
    if (next.status === 'declined') return 'Call declined';
    if (next.status === 'missed') return 'No answer';
    if (next.status === 'trip_ended') return 'Trip ended';
    if ((next.durationSeconds || 0) > 0 || next.startedAt) return `Call ended · ${formatTime(next.durationSeconds || Math.max(0, Math.floor(((Date.now() + offsetRef.current) - (next.startedAt || Date.now())) / 1000)))}`;
    return next.direction === 'outgoing' ? 'Call cancelled' : 'Missed call';
  }, []);

  useEffect(() => {
    const unlock = () => { try { const audio = audioRef.current || new Audio('/sounds/call.mp3'); audioRef.current = audio; audio.loop = true; void audio.play().then(() => { audio.pause(); audio.currentTime = 0; }); } catch { /* optional ringtone */ } };
    window.addEventListener('pointerdown', unlock, { once: true });
    return () => window.removeEventListener('pointerdown', unlock);
  }, []);

  useEffect(() => {
    let unsubscribeUser = () => undefined;
    const unsubscribeAuth = onAuthStateChanged(auth, user => {
      unsubscribeUser();
      if (!user) return;
      const offsetUnsub = onValue(ref(database, '.info/serverTimeOffset'), snap => { offsetRef.current = Number(snap.val() || 0); });
      const callsUnsub = onValue(ref(database, `user_calls/${user.uid}`), snap => {
        const next = snap.val() as CallSnapshot | null;
        const current = snapshotRef.current;
        const activeId = activeCallIdRef.current;
        if (!next) return;
        if (
          terminal.has(next.status) ||
          (current?.callId === next.callId && current.status === 'ringing' && next.status !== 'ringing')
        ) {
          cancelCallNotification(next.callId);
        }
        if (!current && terminal.has(next.status)) return;
        if (activeId && next.callId !== activeId) return;
        if (callStateRef.current !== 'idle' && activeId && next.callId !== activeId) return;
        if (next.expiresAt && Date.now() + offsetRef.current >= next.expiresAt && !terminal.has(next.status)) return;
        if (next.status === 'active' && (current?.callId !== next.callId || current.status !== 'active')) {
          callServiceStart('Call in progress');
        }
        if (terminal.has(next.status)) {
          if (seenRef.current && (!activeId || next.callId === activeId)) void finishCall(messageForTerminal(next));
          return;
        }
        if (callStateRef.current === 'in-call' && next.direction === 'incoming') return;
        activeCallIdRef.current = next.callId;
        seenRef.current = true;
        setSnapshot(next);
        const resolvedPeerName = next.peerName || (callStateRef.current === 'calling' ? peerLabelRef.current : 'Driver');
        setPeerName(resolvedPeerName);
        if (next.status === 'ringing') {
          setCallState(next.direction === 'incoming' ? 'ringing' : 'calling');
          if (next.direction === 'incoming') { try { const audio = audioRef.current || new Audio('/sounds/call.mp3'); audioRef.current = audio; audio.loop = true; void audio.play().catch(() => undefined); } catch { /* optional ringtone */ } }
          if (next.direction === 'outgoing' && next.expiresAt && ringTimerRef.current === null) {
            const delay = Math.max(0, next.expiresAt - (Date.now() + offsetRef.current));
            ringTimerRef.current = window.setTimeout(async () => { ringTimerRef.current = null; const latest = snapshotRef.current; if (latest?.callId === next.callId && latest.status === 'ringing') { await apiEndCall(next.callId, 'no_answer').catch(() => undefined); await finishCall('No answer'); } }, delay);
          }
        }
        if (next.status === 'active') { stopAudio(); if (ringTimerRef.current !== null) { window.clearTimeout(ringTimerRef.current); ringTimerRef.current = null; } setCallState('in-call'); }
      });
      unsubscribeUser = () => { offsetUnsub(); callsUnsub(); };
    });
    return () => { unsubscribeUser(); unsubscribeAuth(); };
  }, [finishCall, messageForTerminal, stopAudio]);

  const showMicDeniedToast = useCallback((orderId: string) => {
    if (micDeniedToastOrdersRef.current.has(orderId)) return;
    const storageKey = `aletwende-mic-denied-toast:${orderId}`;
    let alreadyShown = false;
    try {
      alreadyShown = sessionStorage.getItem(storageKey) === 'true';
      if (!alreadyShown) sessionStorage.setItem(storageKey, 'true');
    } catch {
      // Keep the in-memory guard when session storage is unavailable.
    }
    micDeniedToastOrdersRef.current.add(orderId);
    if (!alreadyShown) setMicPermissionToastVisible(true);
  }, []);

  useEffect(() => {
    const orderId = activeOrder?.id;
    if (!orderId || !['accepted', 'arrived', 'started', 'picked_up'].includes(activeOrder.status)) return;

    const preflightKey = `aletwende-mic-preflight:${orderId}`;
    let alreadyChecked = micPreflightOrdersRef.current.has(orderId);
    try {
      alreadyChecked = alreadyChecked || sessionStorage.getItem(preflightKey) === 'true';
      if (!alreadyChecked) sessionStorage.setItem(preflightKey, 'true');
    } catch {
      // The in-memory set still prevents duplicate prompts for this mount.
    }
    micPreflightOrdersRef.current.add(orderId);
    if (alreadyChecked) return;

    void (async () => {
      const permissionState = await getMicPermissionState();
      if (permissionState === 'denied') {
        showMicDeniedToast(orderId);
        return;
      }
      if (permissionState !== 'prompt') return;

      const granted = await primeMicrophone();
      if (granted) return;
      if (await getMicPermissionState() === 'denied') {
        showMicDeniedToast(orderId);
      }
    })();
  }, [activeOrder?.id, activeOrder?.status, showMicDeniedToast]);

  useEffect(() => {
    if (!micPermissionToastVisible) return;
    const timer = window.setTimeout(() => setMicPermissionToastVisible(false), 5000);
    return () => window.clearTimeout(timer);
  }, [micPermissionToastVisible]);

  useEffect(() => { if (callState !== 'in-call' || !snapshot?.startedAt) return; const timer = window.setInterval(() => setElapsedSeconds(Math.floor((Date.now() + offsetRef.current - snapshot.startedAt!) / 1000)), 1000); return () => window.clearInterval(timer); }, [callState, snapshot?.startedAt]);

  const connect = useCallback(async (credentials: CallCredentials) => { const activeCall = await joinChannel(credentials, () => setCallState('in-call')); activeRef.current = activeCall; setMicAvailable(activeCall.micAvailable); setMicError(activeCall.micError); setMicBannerDismissed(false); }, []);
  const start = useCallback(async (orderId: string, peerLabel = 'Driver') => {
    if (endingRef.current || callStateRef.current !== 'idle') return;
    peerLabelRef.current = peerLabel;
    endingRef.current = true; setCallState('calling'); callStateRef.current = 'calling'; setEndedMessage(null); setElapsedSeconds(0); setIsMuted(false); setPeerName(peerLabel); seenRef.current = true;
    let credentials: CallCredentials | null = null;
    try { credentials = await apiStartCall(orderId); activeCallIdRef.current = credentials.callId || null; const next = { ...credentials, callId: credentials.callId || '', orderId, direction: 'outgoing' as const, status: 'ringing' as const }; setSnapshot(next); snapshotRef.current = next; setPeerName(peerLabel); await connect(credentials); endingRef.current = false; } catch (error) { if (credentials?.callId) await apiEndCall(credentials.callId).catch(() => undefined); await finishCall(callErrorMessage(error)); }
  }, [connect, finishCall]);
  const answer = useCallback(async () => {
    if (endingRef.current || !snapshotRef.current?.callId) return;
    endingRef.current = true;
    const current = snapshotRef.current;
    activeCallIdRef.current = current.callId;
    seenRef.current = true;
    let credentials: CallCredentials;
    try {
      stopAudio();
      credentials = await acceptCall(current.callId);
    } catch (error) {
      await finishCall(callErrorMessage(error));
      return;
    }
    try {
      await connect(credentials);
      endingRef.current = false;
    } catch (error) {
      await apiEndCall(current.callId).catch(() => undefined);
      await finishCall(callErrorMessage(error));
    }
  }, [connect, finishCall, stopAudio]);

  useEffect(() => {
    if (!isNative()) return;

    let currentUid: string | null = null;
    let disposed = false;
    let authEventQueue: Promise<void> = Promise.resolve();

    const removeSavedToken = async (uid: string) => {
      try {
        const { token } = await NativeCalls!.getToken();
        if (token) {
          await updateDoc(doc(db, 'users', uid), { fcmTokens: arrayRemove(token) });
        }
      } catch (error) {
        console.warn('[calls] Could not remove saved token:', error);
      }
    };

    const unsubscribe = onAuthStateChanged(auth, user => {
      authEventQueue = authEventQueue.then(async () => {
        if (disposed) return;
        if (user) {
          if (currentUid === user.uid) return;
          if (currentUid) {
            await disposeNativeCalls();
            await removeSavedToken(currentUid);
          }
          currentUid = user.uid;
          await initNativeCalls({
            saveToken: async token => {
              await updateDoc(doc(db, 'users', user.uid), {
                fcmToken: token,
                fcmTokens: arrayUnion(token),
              });
            },
            onAnswer: callId => {
              void (async () => {
                const deadline = Date.now() + 10_000;
                while (!disposed && Date.now() < deadline) {
                  const currentCall = snapshotRef.current;
                  if (currentCall?.callId === callId && currentCall.status === 'ringing') {
                    await answer();
                    return;
                  }
                  await new Promise(resolve => window.setTimeout(resolve, 100));
                }
              })().catch(error => console.warn('[calls] Could not answer native call:', error));
            },
          });
        } else {
          const uid = currentUid;
          currentUid = null;
          await disposeNativeCalls();
          if (uid) await removeSavedToken(uid);
          callServiceStop();
        }
      }).catch(error => console.warn('[calls] Native call setup failed:', error));
    });

    return () => {
      disposed = true;
      unsubscribe();
      const uid = currentUid;
      currentUid = null;
      void authEventQueue.then(async () => {
        await disposeNativeCalls();
        if (uid) await removeSavedToken(uid);
        callServiceStop();
      }).catch(error => console.warn('[calls] Native call cleanup failed:', error));
    };
  }, [answer]);

  const decline = useCallback(async () => { if (endingRef.current || !snapshotRef.current?.callId) return; endingRef.current = true; const id = snapshotRef.current.callId; await declineCall(id).catch(() => undefined); await finishCall('Call declined'); }, [finishCall]);
  const end = useCallback(async () => { if (endingRef.current || !snapshotRef.current?.callId) return; endingRef.current = true; const current = snapshotRef.current; const message = current.startedAt || callStateRef.current === 'in-call' ? `Call ended · ${formatTime(elapsedSeconds)}` : 'Call cancelled'; await apiEndCall(current.callId).catch(() => undefined); await finishCall(message); }, [elapsedSeconds, finishCall]);
  const mute = useCallback(async () => { const next = !isMuted; await activeRef.current?.setMuted(next); setIsMuted(next); }, [isMuted]);
  const enableMicrophone = useCallback(async () => {
    const activeCall = activeRef.current;
    if (!activeCall) {
      setMicError('unavailable');
      return false;
    }
    const nextMicError = await activeCall.enableMicrophone();
    const enabled = nextMicError === null;
    setMicAvailable(enabled);
    setMicError(nextMicError);
    return enabled;
  }, []);
  const value = useMemo(() => ({ callState, peerName, elapsedSeconds, formattedTime: formatTime(elapsedSeconds), isMuted, micAvailable, micError, enableMicrophone, endedMessage, startCall: start, answerCall: answer, declineCall: decline, endCall: end, toggleMute: mute }), [answer, callState, decline, elapsedSeconds, enableMicrophone, end, endedMessage, isMuted, micAvailable, micError, mute, peerName, start]);
  return <CallContext.Provider value={value}>
    {children}
    {micPermissionToastVisible && <div role="status" className="fixed left-1/2 top-4 z-[90] w-[calc(100%-2rem)] max-w-md -translate-x-1/2 rounded-xl bg-gray-900 px-4 py-3 text-sm text-white shadow-xl">{MIC_BLOCKED_MESSAGE}</div>}
    {callState === 'in-call' && !micAvailable && !micBannerDismissed && <div className="fixed bottom-28 left-1/2 z-[89] flex max-w-[calc(100%-2rem)] -translate-x-1/2 items-center gap-3 rounded-xl bg-gray-800 px-4 py-2 text-xs text-white shadow-lg">
      {micError === 'denied' ? (
        <>
          <span>{MIC_BLOCKED_MESSAGE}</span>
          <button type="button" onClick={() => setMicBannerDismissed(true)} className="shrink-0 font-semibold text-emerald-300">Dismiss</button>
        </>
      ) : (
        <>
          <span>
            Your microphone is off. The driver can't hear you.
            {micError === 'unsupported' && <span className="mt-1 block">Microphone access requires a supported browser and a secure connection.</span>}
            {micError === 'unavailable' && <span className="mt-1 block">The microphone could not be started. Check the device and try again.</span>}
          </span>
          <button type="button" onClick={() => void enableMicrophone()} className="shrink-0 font-semibold text-emerald-300">Turn on</button>
        </>
      )}
    </div>}
  </CallContext.Provider>;
}
export function useCall() { const value = useContext(CallContext); if (!value) throw new Error('useCall must be used inside CallProvider'); return value; }
