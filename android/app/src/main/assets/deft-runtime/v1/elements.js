/** elements: <deft-number value>, <deft-icon name> (sprite /_deft/v1/icons.svg), <deft-widget app size> (iframe of the app's widget page, from /_deft/v1/widgets.json). Imported by motion.js. Budget 3.5 KB. */
const HE=typeof HTMLElement=="function"?HTMLElement:class{};
const NS='http://www.w3.org/2000/svg';
const RM=()=>matchMedia('(prefers-reduced-motion: reduce)').matches;
const tok=(n,f)=>getComputedStyle(document.documentElement).getPropertyValue(n).trim()||f;
const WIDGET_CAP=6;let WI;const widgets=()=>WI||(WI=fetch('/_deft/v1/widgets.json').then(r=>r.ok?r.json():{}).catch(()=>({})));
const quiet=()=>Object.assign(document.createElement('p'),{className:'deft-widget-quiet',textContent:'Widget unavailable'});
const setup=(n,a)=>{const e=document.createElement('a');e.className='deft-widget-quiet deft-widget-setup';e.href='deft://setup/'+encodeURIComponent(a);e.append(Object.assign(document.createElement('b'),{textContent:'Tap to set up'}),n||a);return e};
class DeftNumber extends HE{
  static get observedAttributes(){return['value']}
  connectedCallback(){if(!this.children.length)this.set(this.getAttribute('value'))}
  attributeChangedCallback(_,o,v){o===null||!this.children.length?this.set(v):this.flip(v)}
  set(v){v=String(v??'');this.replaceChildren(...[...v].map(c=>Object.assign(document.createElement('span'),{className:'deft-d',textContent:c})));this.setAttribute('aria-label',v)}
  flip(v){v=String(v??'');const cs=[...v];if(cs.length!==this.children.length)return this.set(v);const rm=RM();cs.forEach((c,i)=>{const s=this.children[i];if(s.textContent===c)return;s.textContent=c;s.animate(rm?[{opacity:0},{opacity:1}]:[{transform:'translateY(60%)',opacity:0},{transform:'none',opacity:1}],{duration:rm?160:320,easing:rm?'linear':tok('--deft-step-dot','steps(8, jump-end)')})});this.setAttribute('aria-label',v)}
}
class DeftIcon extends HE{
  static get observedAttributes(){return['name']}
  connectedCallback(){this.draw()}
  attributeChangedCallback(){this.draw()}
  draw(){const n=this.getAttribute('name')||'';if(!/^[a-z0-9-]+$/.test(n))return this.replaceChildren();const s=document.createElementNS(NS,'svg'),u=document.createElementNS(NS,'use');s.setAttribute('viewBox','0 0 24 24');s.setAttribute('aria-hidden','true');u.setAttribute('href','/_deft/v1/icons.svg#'+n);s.append(u);this.replaceChildren(s)}
}
class DeftWidget extends HE{
  static get observedAttributes(){return['app','size']}
  connectedCallback(){this.draw()}
  attributeChangedCallback(){this.draw()}
  draw(){const t=this.__t=(this.__t||0)+1,app=this.getAttribute('app')||'',size=this.getAttribute('size')||'1x1';widgets().then(all=>{if(t!==this.__t)return;const w=Object.hasOwn(all,app)?all[app]:null,i=[...document.querySelectorAll('deft-widget')].indexOf(this);if(!w||!(w.sizes||[]).includes(size)||i<0||i>=WIDGET_CAP)return this.replaceChildren(quiet());if(w.state=='setup')return this.replaceChildren(setup(w.name,app));const f=document.createElement('iframe');f.setAttribute('sandbox','allow-scripts allow-same-origin');f.title='Widget';f.src=w.origin+w.page+'?size='+size;f.onload=()=>window.__deftRested&&f.contentWindow.postMessage('deft:rest','*');this.replaceChildren(f)})}
}
if(typeof customElements!='undefined'){addEventListener('deft:widgets',()=>{WI=0;for(const e of document.querySelectorAll('deft-widget'))e.querySelector('iframe')||e.draw()});if(!customElements.get('deft-number'))customElements.define('deft-number',DeftNumber);if(!customElements.get('deft-icon'))customElements.define('deft-icon',DeftIcon);if(!customElements.get('deft-widget'))customElements.define('deft-widget',DeftWidget)}
