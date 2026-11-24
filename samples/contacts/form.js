// The new or edit contact sheet. Save creates or updates; Delete removes the open contact.
import { call } from '/_deft/v1/bridge.js';
import { confirm, toast } from '/_deft/v1/app.js';

const $ = (id) => document.getElementById(id);
let editId = null;
let ctx = null;

// Opens the sheet: blank for a new contact, filled for an edit (c given).
export function openForm(c) {
  editId = c ? c.id : null;
  $('formTitle').textContent = c ? 'Edit contact' : 'New contact';
  $('fName').value = c ? c.name || '' : '';
  $('fPhone').value = c && c.phones ? c.phones[0] || '' : '';
  $('fEmail').value = c && c.emails ? c.emails[0] || '' : '';
  $('fOrg').value = c ? c.org || '' : '';
  $('deleteRow').hidden = !c;
  $('sheet').showModal();
}
// api: { err(message), reload(), showList() }
export function wireForm(api) {
  ctx = api;
  $('cancel').onclick = () => $('sheet').close();
  $('form').onsubmit = async (ev) => {
    ev.preventDefault();
    ctx.err('');
    const payload = {
      name: $('fName').value.trim(),
      phone: $('fPhone').value.trim() || undefined,
      email: $('fEmail').value.trim() || undefined,
      org: $('fOrg').value.trim() || undefined,
    };
    try {
      if (editId) await call('contacts.update', { id: editId, ...payload });
      else await call('contacts.create', payload);
      $('sheet').close();
      await ctx.reload();
    } catch (e) { ctx.err(e.message); toast(e.message, { tone: 'error' }); }
  };
  $('del').onclick = async () => {
    if (!editId) return;
    if (!(await confirm('Delete ' + ($('fName').value.trim() || 'this contact') + '?', { destructive: true, ok: 'Delete' }))) return;
    try {
      await call('contacts.delete', { id: editId });
      $('sheet').close();
      ctx.showList();
      await ctx.reload();
    } catch (e) { ctx.err(e.message); toast(e.message, { tone: 'error' }); }
  };
}
