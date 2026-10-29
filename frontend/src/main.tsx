import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import { captureInvitationEntry, watchInvitationFragments } from './invitation-entry';
import './style.css';

const root = ReactDOM.createRoot(document.getElementById('root')!);
let entrySequence = 0;
function renderEntry(invitationEntry: ReturnType<typeof captureInvitationEntry>) {
  root.render(<React.StrictMode><App key={++entrySequence} invitationEntry={invitationEntry} /></React.StrictMode>);
}
renderEntry(captureInvitationEntry(window.location, window.history));
watchInvitationFragments(renderEntry);
