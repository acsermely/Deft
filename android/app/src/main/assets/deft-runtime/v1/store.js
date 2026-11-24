/** store */
import{effect,signal}from'./core.js';import{toast}from'./app.js';
export function store(key,init,opt){const v=opt?.version||1,k='deft:'+key;let data=init;try{const p=JSON.parse(localStorage.getItem(k)||'');data=p.v===v?p.data:(opt?.migrate(p,v)??init)}catch(e){}const box=signal(data);let t;effect(()=>{const cur=box.value;clearTimeout(t);t=setTimeout(()=>{try{localStorage.setItem(k,JSON.stringify({v,data:cur}))}catch(e){console.warn('store quota',e);toast('Save failed')}},250)});return box}
