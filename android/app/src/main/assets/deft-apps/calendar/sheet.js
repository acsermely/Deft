// Event sheet: a dialog that creates, edits and deletes one event. Saves go through the bridge.
import { call } from '/_deft/v1/bridge.js';
import { confirm } from '/_deft/v1/app.js';

const $ = (id) => document.getElementById(id);
const sheet = $('sheet');
let editId = null;
let deps = null;

function pad(n) { return String(n).padStart(2, '0'); }
function toInput(ms) {
  const d = new Date(ms);
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}

// Opens the sheet for an event, or for a new one starting at defaultStart (ms).
export function openSheet(e, defaultStart) {
  editId = e ? e.eventId : null;
  $('formTitle').textContent = e ? 'Edit event' : 'New event';
  $('fTitle').value = e ? e.title || '' : '';
  const start = e ? e.dtStart : defaultStart + 3600000;
  const end = e ? e.dtEnd : start + 3600000;
  $('fStart').value = toInput(start);
  $('fEnd').value = toInput(end);
  $('fDesc').value = e ? e.description || '' : '';
  $('deleteRow').hidden = !e;
  if (!sheet.open) sheet.showModal();
}

// deps: { calendar() -> first calendar or undefined, err(msg), reload() }
export function initSheet(d) {
  deps = d;
  $('cancel').addEventListener('click', () => sheet.close());
  $('form').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const cal = deps.calendar();
    if (!cal) { deps.err('No calendars on this device'); return; }
    const dtStart = new Date($('fStart').value).getTime();
    const dtEnd = new Date($('fEnd').value).getTime();
    const payload = { title: $('fTitle').value.trim(), dtStart, dtEnd, description: $('fDesc').value.trim() };
    try {
      if (editId) await call('calendar.update', { eventId: editId, ...payload });
      else await call('calendar.create', { calendarId: cal.id, ...payload });
      sheet.close();
      await deps.reload();
    } catch (e) { deps.err(e.message); }
  });
  $('del').addEventListener('click', async () => {
    if (!editId) return;
    if (!(await confirm('Delete this event?', { destructive: true, ok: 'Delete' }))) return;
    try {
      await call('calendar.delete', { eventId: editId });
      sheet.close();
      await deps.reload();
    } catch (e) { deps.err(e.message); }
  });
}
