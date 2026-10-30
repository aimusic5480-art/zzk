import { useEffect, useState } from 'react';
import { primeMicrophone, getMicPermissionState, MIC_BLOCKED_MESSAGE } from '../services/callService';
import { useActiveOrder } from '../hooks/useActiveOrder';
import { useCall } from '../contexts/CallContext';

export function MicPermissionPrompt() {
  const { order } = useActiveOrder();
  const { callState } = useCall();
  const [permissionState, setPermissionState] = useState<'granted' | 'prompt' | 'denied' | 'unsupported' | null>(null);
  const [dismissed, setDismissed] = useState(false);
  const [blockedDismissed, setBlockedDismissed] = useState(false);
  const activeOrderId = order?.id;

  useEffect(() => {
    try {
      if (sessionStorage.getItem('aletwende-mic-prompt-dismissed') === 'true') setDismissed(true);
    } catch {
      // Keep the prompt usable when session storage is unavailable.
    }
  }, []);

  useEffect(() => {
    if (!activeOrderId) {
      setPermissionState(null);
      return;
    }
    let mounted = true;
    const updatePermissionState = () => void getMicPermissionState().then(state => {
      if (mounted) {
        setPermissionState(state);
      }
    });
    updatePermissionState();
    window.addEventListener('focus', updatePermissionState);
    return () => {
      mounted = false;
      window.removeEventListener('focus', updatePermissionState);
    };
  }, [activeOrderId]);

  const dismissForSession = () => {
    try {
      sessionStorage.setItem('aletwende-mic-prompt-dismissed', 'true');
    } catch {
      // Dismiss for this mount if session storage is unavailable.
    }
    setDismissed(true);
  };

  const allowMicrophone = async () => {
    const granted = await primeMicrophone();
    const state = await getMicPermissionState();
    setPermissionState(state);
    if (state === 'denied') {
      setBlockedDismissed(false);
    } else if (granted || state !== 'prompt') {
      setDismissed(true);
    }
  };

  if (!order || callState !== 'idle') return null;
  if (permissionState === 'denied') {
    if (blockedDismissed) return null;
    return (
      <div className="fixed bottom-24 left-1/2 z-[55] w-[calc(100%-2rem)] max-w-sm -translate-x-1/2 rounded-2xl bg-white p-4 shadow-xl ring-1 ring-black/10 dark:bg-gray-900 dark:ring-white/10" role="status">
        <p className="text-sm font-medium text-gray-800 dark:text-gray-100">{MIC_BLOCKED_MESSAGE}</p>
        <div className="mt-3 flex justify-end">
          <button type="button" onClick={() => setBlockedDismissed(true)} className="rounded-lg px-3 py-2 text-sm font-medium text-gray-600 dark:text-gray-300">Dismiss</button>
        </div>
      </div>
    );
  }
  if (permissionState !== 'prompt' || dismissed) return null;

  return (
    <div className="fixed bottom-24 left-1/2 z-[55] w-[calc(100%-2rem)] max-w-sm -translate-x-1/2 rounded-2xl bg-white p-4 shadow-xl ring-1 ring-black/10 dark:bg-gray-900 dark:ring-white/10" role="status">
      <p className="text-sm font-medium text-gray-800 dark:text-gray-100">Allow your microphone so your driver can call you.</p>
      <div className="mt-3 flex justify-end gap-3">
        <button type="button" onClick={dismissForSession} className="rounded-lg px-3 py-2 text-sm font-medium text-gray-600 dark:text-gray-300">Not now</button>
        <button type="button" onClick={() => { void allowMicrophone(); }} className="rounded-lg bg-[#5B2EFF] px-4 py-2 text-sm font-semibold text-white">Allow</button>
      </div>
    </div>
  );
}
