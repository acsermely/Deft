// Notification History rows: one list item per notification, with its tools (Open, Clear, reply).
// All text is written with textContent only.
import { call } from '/_deft/v1/bridge.js';

function when(ms) {
  return new Date(ms).toLocaleString();
}

function button(label, fn, ctx) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'deft-btn';
  b.textContent = label;
  b.addEventListener('click', () => ctx.run(fn));
  return b;
}

// ctx: { run(fn) runs an action and reports errors, refresh() reloads the list, report(text) sets the status }
export function toolsFor(n, ctx) {
  const box = document.createElement('div');
  box.className = 'tools';
  box.append(
    button('Open', () => call('notifications.open', { key: n.key }), ctx),
    button('Clear', async () => {
      await call('notifications.dismiss', { key: n.key });
      await ctx.refresh();
    }, ctx),
  );
  const replyAction = (n.actions || []).find((a) => a.reply);
  if (replyAction) {
    const input = document.createElement('input');
    input.type = 'text';
    input.className = 'deft-input';
    input.maxLength = 1000;
    input.placeholder = replyAction.title || 'Reply';
    input.setAttribute('aria-label', input.placeholder);
    box.append(input, button('Send', async () => {
      const text = input.value.trim();
      if (!text) return;
      await call('notifications.reply', { key: n.key, actionIndex: replyAction.index, text });
      input.value = '';
      ctx.report('Reply sent');
    }, ctx));
  }
  return box;
}

export function row(n, tools) {
  const li = document.createElement('li');
  li.className = 'deft-cell';
  const body = document.createElement('div');
  body.className = 'deft-cell-body';
  const head = document.createElement('div');
  head.className = 'head deft-footnote';
  const app = document.createElement('span');
  app.textContent = n.appLabel || n.package || '';
  const time = document.createElement('span');
  time.textContent = when(n.time);
  head.append(app, time);
  const title = document.createElement('div');
  title.className = 'deft-headline';
  title.textContent = n.redacted ? '(content hidden)' : (n.title || '');
  const text = document.createElement('div');
  text.className = 'deft-body text';
  text.textContent = n.redacted ? '' : (n.text || '');
  body.append(head, title, text);
  if (tools) body.append(tools);
  li.append(body);
  return li;
}
