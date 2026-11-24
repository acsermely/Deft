// RSS item row: a link (or a plain block) with the picture, title, summary and date. Text uses textContent only.
function plain(html) {
  return html.replace(/<[^>]*>/g, ' ').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .replace(/\s+/g, ' ').trim();
}

export function row(item) {
  const el = document.createElement(item.link ? 'a' : 'div');
  el.className = 'deft-row feed-row';
  if (item.link) {
    el.href = item.link;
    el.rel = 'noopener noreferrer';
  }
  if (item.image) {
    const img = document.createElement('img');
    img.className = 'feed-thumb';
    img.alt = '';
    img.loading = 'lazy';
    img.referrerPolicy = 'no-referrer';
    img.src = item.image;
    img.addEventListener('error', () => img.remove());
    el.appendChild(img);
  }
  const body = document.createElement('div');
  body.className = 'feed-body';
  const h = document.createElement('h2');
  h.className = 'deft-headline';
  h.textContent = item.title || '(untitled)';
  body.appendChild(h);
  const summary = plain(item.html).slice(0, 220);
  if (summary) {
    const p = document.createElement('p');
    p.className = 'deft-subhead';
    p.textContent = summary;
    body.appendChild(p);
  }
  const when = item.date ? new Date(item.date) : null;
  if (when && !isNaN(when)) {
    const t = document.createElement('time');
    t.className = 'deft-caption';
    t.dateTime = when.toISOString();
    t.textContent = when.toLocaleString();
    body.appendChild(t);
  }
  el.appendChild(body);
  return el;
}
