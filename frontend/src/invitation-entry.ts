export type InvitationEntry = { token: string | null };

// Call before rendering or starting API requests. Fragment secrets stay in memory.
export function captureInvitationEntry(location: Location, history: History): InvitationEntry | undefined {
  if (location.pathname !== '/accept-invitation') return undefined;
  const token = new URLSearchParams(location.hash.slice(1)).get('token');
  history.replaceState(history.state, '', location.pathname);
  return { token: token || null };
}

export function watchInvitationFragments(onEntry: (entry: InvitationEntry) => void) {
  const capture = () => {
    const entry = captureInvitationEntry(window.location, window.history);
    if (entry) onEntry(entry);
  };
  window.addEventListener('hashchange', capture);
  return () => window.removeEventListener('hashchange', capture);
}
