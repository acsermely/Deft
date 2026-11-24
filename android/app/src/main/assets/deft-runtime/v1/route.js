/** routes, go, current, link */
import{back}from'./app.js';import{effect,signal,untracked}from'./core.js';import{render}from'./dom.js';
/** A skipped or timed-out transition rejects `ready`; that is not an app error, so it is caught here. Pairs [data-deft-morph=id] elements across a navigation: names go on the old tree, are cleared inside the update, then set on the new tree. Reduced motion keeps only the root cross-fade. */
const names=on=>{if(!matchMedia("(prefers-reduced-motion: reduce)").matches)document.querySelectorAll("[data-deft-morph]").forEach(e=>e.style.viewTransitionName=on?e.dataset.deftMorph:"")};
export const morph=fn=>{if(!document.startViewTransition)return fn();names(1);const t=document.startViewTransition(()=>{names(0);const r=fn();names(1);return r});t.ready?.catch(()=>{});return t};
const hash=()=>location.hash.slice(1)||'/';
export const current=signal(hash()),go=p=>{const n=p[0]=='/'?p:'/'+p;location.hash=='#'+n||(location.hash=n);current.value=n},link=p=>'#'+(p[0]=='/'?p:'/'+p);
function hit(p,s){const a=p.split('/').filter(x=>x),b=(s||'/').split('/').filter(x=>x),o={};if(a.length-b.length)return;for(let i=0;i<a.length;i++)if(a[i][0]==':')o[a[i].slice(1)]=decodeURIComponent(b[i]);else if(a[i]!=b[i])return;return o}
export function routes(table,root){const paint=()=>{const raw=current.value||'/',q=raw.indexOf('?'),path=q<0?raw:raw.slice(0,q);let view,arg={};for(const k in table){const m=hit(k,path);if(m){view=table[k];arg=m;break}}const draw=()=>{off();off=render(root,view?()=>view(arg):'')};untracked(()=>morph(draw))};addEventListener('hashchange',()=>current.value=hash());let off=()=>{};const stop=effect(paint),end=back(()=>(current.peek()||'/')!='/'?(history.back(),!0):!1);return()=>{stop();off();end()}}
