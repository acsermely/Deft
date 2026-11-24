// deft-component cluster@1.0.0 author=local sha256=bundled
import { html } from '/_deft/v1/dom.js';

export function cluster(title, action) {
  return html`<header class="deft-cluster"><h1>${title}</h1>${action}</header>`;
}
