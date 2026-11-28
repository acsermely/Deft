// Background module . Runs in a hidden page when a text arrives, even with the app
// closed: posts "New message from <sender>" that opens the app at the thread. It never sends
// anything; sending always needs the app on screen.
function digits(s) { return String(s || '').replace(/\D/g, ''); }

async function threadOf(from) {
  const want = digits(from);
  if (!want) return '';
  try {
    const rows = await deftBridge.invoke('sms.listMessages', { limit: 20 });
    const hit = (Array.isArray(rows) ? rows : []).find((m) => {
      const d = digits(m.address);
      return d && (d === want || d.slice(-10) === want.slice(-10));
    });
    return hit && hit.threadId ? String(hit.threadId) : '';
  } catch (e) {
    return '';
  }
}

async function onSms(payload) {
  const from = String((payload && payload.from) || '').slice(0, 64) || 'Unknown';
  const threadId = await threadOf(from);
  const url = location.origin + '/index.html' + (threadId ? '#t/' + encodeURIComponent(threadId) : '');
  await deftBridge.invoke('notify.post', {
    id: 'sms-' + (digits(from).slice(-20) || 'unknown'),
    title: 'New message from ' + from,
    body: 'Tap to open the conversation.',
    url,
  });
}

deftBridge.subscribe('background.trigger', async (e) => {
  try {
    if (e && e.trigger === 'sms') await onSms(e.payload);
  } catch (err) {
    // Nothing to show in the background; the run ends below either way.
  } finally {
    try { await deftBridge.invoke('background.done'); } catch (err) { /* page closes after 30 s */ }
  }
});
