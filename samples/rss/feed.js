// RSS feed reading: fetches the XML with XHR (no HTML sink) and turns RSS or Atom into plain items.
export function httpsUrl(value) {
  try {
    const u = new URL(String(value || '').trim());
    return u.protocol === 'https:' ? u.href : null;
  } catch (e) {
    return null;
  }
}

// XHR parses XML without an HTML sink (Trusted Types stay on); connect-src still applies.
export function readXml(url) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('GET', url);
    xhr.timeout = 15000;
    xhr.responseType = 'document';
    xhr.overrideMimeType('application/xml');
    xhr.onload = () => {
      if (xhr.status < 200 || xhr.status >= 300) reject(new Error('The site answered ' + xhr.status));
      else if (!xhr.responseXML || xhr.responseXML.getElementsByTagName('parsererror').length) reject(new Error('This is not an RSS or Atom feed'));
      else resolve(xhr.responseXML);
    };
    xhr.onerror = () => reject(new Error('Could not read the feed. Check the address; the site may not let apps read it.'));
    xhr.ontimeout = () => reject(new Error('The site took too long to answer'));
    xhr.send();
  });
}

const kids = (node, name) => [...node.children].filter((c) => c.localName === name);
const text = (node, name) => {
  const hit = kids(node, name)[0];
  return hit ? hit.textContent.trim() : '';
};

function imageOf(node, html) {
  for (const name of ['thumbnail', 'content', 'enclosure']) {
    for (const el of kids(node, name)) {
      const type = el.getAttribute('type') || '';
      const medium = el.getAttribute('medium') || '';
      const ok = name === 'thumbnail' || type.startsWith('image/') || medium === 'image';
      const src = ok && httpsUrl(el.getAttribute('url'));
      if (src) return src;
    }
  }
  for (const group of kids(node, 'group')) {
    const inner = imageOf(group, '');
    if (inner) return inner;
  }
  const m = /<img[^>]+src=["']([^"']+)["']/i.exec(html);
  return m ? httpsUrl(m[1].replace(/&amp;/g, '&')) : null;
}

function parse(doc) {
  const root = doc.documentElement;
  if (root.localName === 'feed') {
    return {
      title: text(root, 'title'),
      items: kids(root, 'entry').map((e) => {
        const links = kids(e, 'link');
        const link = links.find((l) => (l.getAttribute('rel') || 'alternate') === 'alternate') || links[0];
        const html = text(e, 'content') || text(e, 'summary');
        return { title: text(e, 'title'), link: link && httpsUrl(link.getAttribute('href')), html, date: text(e, 'published') || text(e, 'updated'), image: imageOf(e, html) };
      }),
    };
  }
  const channel = kids(root, 'channel')[0] || root;
  const items = kids(channel, 'item').length ? kids(channel, 'item') : kids(root, 'item');
  return {
    title: text(channel, 'title'),
    items: items.map((i) => {
      const html = text(i, 'encoded') || text(i, 'description');
      return { title: text(i, 'title'), link: httpsUrl(text(i, 'link')), html, date: text(i, 'pubDate') || text(i, 'date'), image: imageOf(i, html) };
    }),
  };
}

/** Reads one feed address and returns { title, items }. */
export async function fetchFeed(url) {
  return parse(await readXml(url));
}
