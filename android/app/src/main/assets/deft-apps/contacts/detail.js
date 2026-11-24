// Contact detail drill-down: the list and detail views, the detail top bar, and the Call and Message actions.
import { call } from '/_deft/v1/bridge.js';
import { detailView, paint } from './view.js';

const $ = (id) => document.getElementById(id);
export function showList() {
  $('listView').hidden = false;
  $('detailView').hidden = true;
  $('detailBar').hidden = true;
  $('add').hidden = false;
}
function showDetail() {
  $('listView').hidden = true;
  $('detailView').hidden = false;
  $('detailBar').hidden = false;
  $('add').hidden = true;
}
const act = {
  call: (phone) => call('intents.open', { url: 'tel:' + phone }).catch((e) => showErr(e.message)),
  message: (phone) => call('intents.open', { url: 'sms:' + phone }).catch((e) => showErr(e.message)),
};
function showErr(m) {
  const box = $('detailErr');
  box.querySelector('span').textContent = m || '';
  box.hidden = !m;
}
// Paints the open contact (or nothing) and shows the detail view.
export function openDetail(c) {
  $('detailTitle').textContent = c ? c.name || 'Contact' : 'Contact';
  paint('detail', c ? detailView(c, act) : '');
  showDetail();
}
