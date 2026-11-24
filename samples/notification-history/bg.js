// Background module (PARITY G8): another app posted a notification while this app was closed.
// Saves it to the history; nothing is shown and nothing is sent.
import { call, on } from '/_deft/v1/bridge.js';
import { save } from './store.js';

on('background.trigger', async (e) => {
  try {
    if (e && e.trigger === 'notification') await save(e.payload);
  } catch (err) {
    // Nothing to show in the background; the run ends below either way.
  } finally {
    try { await call('background.done'); } catch (err) { /* page closes after 30 s */ }
  }
});
