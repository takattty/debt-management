import { StrictMode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import App from './App';
import { captureInvitationEntry, watchInvitationFragments } from './invitation-entry';

export function startBrowserApplication(container: HTMLElement) {
  let root: Root | null = null;
  let stopFragments: (() => void) | undefined;
  let entrySequence = 0;

  function renderEntry(invitationEntry: ReturnType<typeof captureInvitationEntry>) {
    root?.render(<StrictMode><App key={++entrySequence} invitationEntry={invitationEntry} /></StrictMode>);
  }

  function bootstrap() {
    if (root) return;
    root = createRoot(container);
    renderEntry(captureInvitationEntry(window.location, window.history));
    stopFragments = watchInvitationFragments(renderEntry);
  }

  function discard() {
    stopFragments?.(); stopFragments = undefined;
    const leaving = root; root = null;
    // Unmount synchronously: React state updates alone can be frozen by BFCache.
    try { leaving?.unmount(); }
    finally { container.replaceChildren(); }
  }

  function restore(event: PageTransitionEvent) {
    if (event.persisted) bootstrap();
  }

  window.addEventListener('pagehide', discard);
  window.addEventListener('pageshow', restore);
  bootstrap();
  return () => {
    window.removeEventListener('pagehide', discard);
    window.removeEventListener('pageshow', restore);
    discard();
  };
}
