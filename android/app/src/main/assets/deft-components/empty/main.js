// deft-component empty@1.0.0 author=local sha256=bundled
import { html } from '/_deft/v1/dom.js';

export function empty(title, body) {
  return html`<section class="deft-empty"><h2>${title}</h2><p>${body}</p></section>`;
}
