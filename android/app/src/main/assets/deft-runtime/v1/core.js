/** signal, computed, effect, batch, untracked */
let A=null,D=0;const Q=new Set;
const flush=()=>{const q=[...Q];Q.clear();q.map(r=>r())};
const ping=s=>{s.forEach(r=>Q.add(r));D||flush()};
export function signal(v){let c=v;const s=new Set,b={peek:()=>c,get value(){A&&A.add(b);return c},set value(n){Object.is(n,c)||(c=n,ping(s))},_sub(r){s.add(r);return()=>s.delete(r)}};return b}
export function effect(fn){let u=()=>{},run=()=>{u();let d,p=A;A=new Set;try{fn();d=A}finally{A=p}const x=[...d].map(k=>k._sub(run));u=()=>x.map(f=>f())};run();return()=>u()}
export function computed(fn){const o=signal();let l=0,a=0;effect(()=>{if(l)return a=1;l=1;try{do{a=0;const n=fn();untracked(()=>o.value=n)}while(a)}finally{l=0}});return o}
export function batch(fn){D++;try{return fn()}finally{--D||flush()}}
export function untracked(fn){const p=A;A=null;try{return fn()}finally{A=p}}
