/** on, scope */
import{effect}from'./core.js';
export function on(t,type,sel,fn){const pick=typeof sel=='string'?sel:0,cb=pick?fn:sel,f=e=>{if(!pick)return cb(e);const m=e.target?.closest?.(pick);m&&t.contains(m)&&cb(e,m)};t.addEventListener(type,f);return()=>t.removeEventListener(type,f)}
export function scope(){const s=[],n=[];return{on(t,ty,sel,fn){const x=on(t,ty,sel,fn);s.push(x);return x},interval(fn,ms){const id=setInterval(fn,ms);n.push(id);return()=>clearInterval(id)},effect(fn){const x=effect(fn);s.push(x);return x},dispose(){s.splice(0).map(f=>f());n.splice(0).map(clearInterval)}}}
