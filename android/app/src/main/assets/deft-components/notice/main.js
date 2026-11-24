// deft-component notice@1.0.0 author=local sha256=bundled
import { html } from '/_deft/v1/dom.js';

export function notice(text) {
  return html`<p class="deft-alert" role="status">${text}</p>`;
}
