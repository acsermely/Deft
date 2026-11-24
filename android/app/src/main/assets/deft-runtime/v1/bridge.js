/** ready, call, on, has, inLauncher */
function miss(){const e=Error('Open in Deft');e.code='bridge_not_ready';try{const b=document.body;if(b&&!b.querySelector('#deft-host')){const p=document.createElement('p');p.id='deft-host';p.className='deft-empty';p.textContent=e.message;b.append(p)}}catch(x){}return Promise.reject(e)}
const B=()=>self.deftBridge;
export const ready=()=>B()?B().ready:miss(),call=(m,a)=>B()?B().invoke(m,a||{}):miss(),on=(e,fn)=>B()?B().subscribe(e,fn):()=>{};
export const inLauncher=()=>!!B();
export function has(cap){try{for(const m of B().allowed)if(m==cap||String(m).startsWith(cap+'.'))return!0}catch(e){}return!1}
