// Automation UI helpers: the status alert, the error text for bridge failures, and a run wrapper.
export const $ = (id) => document.getElementById(id);

export function setStatus(text, tone = 'info') {
  const box = $('status');
  box.dataset.tone = tone;
  box.querySelector('deft-icon').setAttribute('name', tone === 'error' ? 'circle-alert' : 'info');
  box.querySelector('span').textContent = text;
}

export function explain(e) {
  const code = e && e.code;
  if (code === 'not_granted') return 'Allow "See and control your screen" for this app on its card in the launcher dashboard.';
  if (code === 'os_permission_missing') return 'Turn on Deft under Accessibility in system settings.';
  return (e && (e.code ? e.code + ': ' : '') + (e.message || e.name)) || String(e);
}

export async function run(fn) {
  try {
    await fn();
  } catch (e) {
    setStatus(explain(e), 'error');
  }
}
