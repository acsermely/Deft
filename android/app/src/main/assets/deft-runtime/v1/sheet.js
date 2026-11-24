/** sheet: dialog.deft-sheet[data-detents="medium large"] drag from .deft-grab, velocity-aware snap, dismiss on fast fling. Budget 2 KB. Started by motion.js. */
const FR={medium:.5,large:.92};
const tok=(n,f)=>getComputedStyle(document.documentElement).getPropertyValue(n).trim()||f;
/** Growth velocity in px/ms from [[time,clientY]] samples; positive when the finger moves up. */
export const velocity=s=>{if(s.length<2)return 0;const a=s[Math.max(0,s.length-3)],b=s[s.length-1],dt=b[0]-a[0];return dt>0?(a[1]-b[1])/dt:0};
/** Detent snap over ascending heights hs. Returns an index, or -1 to dismiss on a fast downward fling. */
export const snapDetent=(hs,h,v,fling=.5)=>{if(v>=fling){const i=hs.findIndex(x=>x>h+1);return i<0?hs.length-1:i}if(v<=-fling){let j=-1;hs.forEach((x,i)=>{if(x<h-1)j=i});return j}let k=0;hs.forEach((x,i)=>{if(Math.abs(x-h)<Math.abs(hs[k]-h))k=i});return k};
/** Pixel height of a detent name for the current viewport. */
export const detentPx=(name,vh)=>Math.round(vh*(FR[name]||.5));
/** Starts the sheets. haptic is called with 'selection' when a detent changes. */
export function initSheets(D,haptic){
  const names=d=>d.dataset.detents.split(' ').sort((a,b)=>detentPx(a,innerHeight)-detentPx(b,innerHeight)),setH=(d,h)=>d.style.setProperty('--deft-sheet-h',h+'px');
  const grab=d=>{if(!d.querySelector(':scope>.deft-grab'))d.prepend(Object.assign(D.createElement('div'),{className:'deft-grab','aria-hidden':'true'}))};
  let drag=null,raf=0;
  const open=d=>{grab(d);d.dataset.detent=names(d)[0];setH(d,detentPx(d.dataset.detent,innerHeight))};
  D.querySelectorAll('dialog.deft-sheet[data-detents]').forEach(grab);
  new MutationObserver(ms=>ms.forEach(m=>{if(m.type=='attributes'){const d=m.target;if(d.open&&d.matches('dialog.deft-sheet[data-detents]'))open(d)}else m.addedNodes.forEach(n=>{if(n.nodeType!=1)return;if(n.matches?.('dialog.deft-sheet[data-detents]'))grab(n);n.querySelectorAll?.('dialog.deft-sheet[data-detents]').forEach(grab)})})).observe(D,{subtree:true,childList:true,attributes:true,attributeFilter:['open']});
  D.addEventListener('pointerdown',e=>{const g=e.target.closest?.('.deft-grab');if(!g||!g.parentElement.open)return;const d=g.parentElement,r=d.getBoundingClientRect();if(e.clientY-r.top>40)return;drag={d,id:e.pointerId,y:e.clientY,h0:r.height,h:r.height,s:[[e.timeStamp,e.clientY]]};g.setPointerCapture(e.pointerId)},{passive:true});
  D.addEventListener('pointermove',e=>{if(!drag||e.pointerId!=drag.id)return;drag.h=Math.min(innerHeight,Math.max(0,drag.h0+drag.y-e.clientY));drag.s.push([e.timeStamp,e.clientY]);if(!raf)raf=requestAnimationFrame(()=>{raf=0;drag&&setH(drag.d,drag.h)})});
  const end=e=>{if(!drag||e.pointerId!=drag.id)return;if(raf){cancelAnimationFrame(raf);raf=0}const{d,h,s}=drag,nm=names(d),hs=nm.map(n=>detentPx(n,innerHeight));drag=null;const i=snapDetent(hs,h,velocity(s));if(i<0)d.close();else{const from=d.getBoundingClientRect().height;d.dataset.detent=nm[i];setH(d,hs[i]);haptic('selection');if(!matchMedia('(prefers-reduced-motion: reduce)').matches)d.animate([{blockSize:from+'px'},{blockSize:hs[i]+'px'}],{duration:parseFloat(tok('--deft-spring-smooth-duration','560ms'))||560,easing:tok('--deft-spring-smooth','ease-out')})}};
  D.addEventListener('pointerup',end);D.addEventListener('pointercancel',end);
}
