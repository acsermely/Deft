/** interval, frame, back, dialog, confirm, prompt, toast, share */
const E=(t,c,x)=>{const n=document.createElement(t);c&&(n.className=c);x!=null&&(n.textContent=x);return n};
export const interval=(fn,ms)=>{const id=setInterval(()=>document.hidden||fn(),ms);return()=>clearInterval(id)};
export const frame=fn=>{let on=1;const l=()=>{if(on){document.hidden||fn();requestAnimationFrame(l)}};requestAnimationFrame(l);return()=>on=0};
export function back(fn){const p=self.__deftOnBack;self.__deftOnBack=()=>fn()===!0?!0:typeof p=='function'?p():!1;return()=>self.__deftOnBack=p}
/** dialog({title, body, actions:[{label, value, primary, tone}]}): modal; resolves the clicked action's value, or false on cancel. Removes itself when closed. */
export function dialog(o={}){return new Promise(ok=>{const d=E('dialog','deft-dialog'),row=E('div','deft-cluster');o.title&&d.append(E('h2',0,o.title));d.append(E('p',0,o.body||''),row);for(const a of o.actions||[{label:'OK',value:!0}]){const b=E('button','deft-btn'+(a.primary?' deft-btn-primary':''),a.label);a.tone&&(b.dataset.tone=a.tone);b.onclick=()=>{d.close();ok(a.value)};row.append(b)}d.oncancel=()=>ok(!1);d.onclose=()=>d.remove();document.body.append(d);d.showModal()})}
/** confirm(msg, {title, ok, destructive}): Promise<boolean>. destructive turns the OK label to the error tone and drops the primary fill. */
export const confirm=(msg,o={})=>dialog({title:o.title,body:msg,actions:[{label:'Cancel',value:!1},{label:o.ok||'OK',value:!0,primary:!o.destructive,tone:o.destructive?'destructive':0}]});
/** prompt(label, {value, ok}): Promise<string|null>; null when cancelled. */
export function prompt(label,o={}){return new Promise(ok=>{const d=E('dialog','deft-dialog'),f=E('form','deft-stack'),i=E('input','deft-input'),r=E('div','deft-cluster'),c=E('button','deft-btn','Cancel'),fin=v=>{ok(v);d.close()};i.value=o.value||'';i.setAttribute('aria-label',label);c.type='button';c.onclick=()=>fin(null);r.append(c,E('button','deft-btn deft-btn-primary',o.ok||'OK'));f.append(E('h2',0,label),i,r);f.onsubmit=e=>{e.preventDefault();fin(i.value)};d.oncancel=()=>ok(null);d.onclose=()=>d.remove();d.append(f);document.body.append(d);d.showModal();i.select()})}
/** toast(text, {tone, ms, action}): one at a time, queued behind the visible one. */
const Q=[];let busy=0;
export function toast(text,o={}){Q.push([text,o]);busy||pump()}
function pump(){const it=Q.shift();if(!it)return busy=0;busy=1;const[text,o]=it,n=E('div','deft-toast',text);n.popover='manual';if(o.tone)n.dataset.tone=o.tone;n.setAttribute('role','status');const a=o.action;if(a){const b=E('button',0,a.label||'Undo');b.onclick=()=>a.run&&a.run();n.append(b)}document.body.append(n);n.showPopover?.();setTimeout(()=>{n.remove();pump()},o.ms||3200)}
export function share(d){if(navigator.share)return navigator.share(d);if(d&&d.url)return navigator.clipboard.writeText(d.url).then(()=>toast('Link copied'))}
