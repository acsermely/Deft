/** motion: enter stagger, press haptics, deft.haptic, deft.toast, deft.open (widgets), frame rest; starts sheet.js and elements.js. Budget 4 KB. Auto-init, idempotent (DESIGN-SYSTEM-PROPOSAL 3.6). */
import{toast,dialog,confirm,prompt}from'./app.js';import'./elements.js';import{initSheets}from'./sheet.js';
const D=typeof document=='undefined'?0:document,KINDS=['light','medium','success','warning','selection'];
const RM=()=>matchMedia('(prefers-reduced-motion: reduce)').matches;
const tok=(n,f)=>getComputedStyle(D.documentElement).getPropertyValue(n).trim()||f;
const KF={rise:[{opacity:0,transform:'translateY(12px)'},{opacity:1,transform:'none'}],scale:[{opacity:0,transform:'scale(.94)'},{opacity:1,transform:'none'}],fade:[{opacity:0},{opacity:1}]};
/** Milliseconds from a CSS time ("560ms" or "0.5s"). */
export const parseMs=s=>{const v=parseFloat(s)||0;return/ms$/.test(s)?v:v*1e3};
/** Stagger offset for child i: step ms each, capped at max children. */
export const staggerDelay=(i,step=45,max=10)=>Math.min(i,max)*step;
/** WAAPI options. Reduced motion is an opacity cross-fade: 200 ms, no delay. */
export const motionFor=(kind,rm,ease='linear',dur=560)=>rm?{keyframes:KF.fade,duration:200,easing:'linear'}:{keyframes:KF[kind]||KF.fade,duration:dur,easing:ease};
/** Haptic rate limit: true once gap ms have passed. */
export const shouldFire=(now,last,gap=40)=>now-last>=gap;
let last=-1e9;
/** Vibration via the native bridge (permission-free haptics.play). Silent outside the launcher and under <html data-deft-haptic="off"> (IME pages). */
export function haptic(kind){const b=self.deftBridge,now=performance.now();if(!b||D?.documentElement?.dataset.deftHaptic=='off'||!KINDS.includes(kind)||!shouldFire(now,last))return;last=now;b.invoke('haptics.play',{kind}).catch(()=>{})}
/** deft.open(path?): a home widget opens its own app (start page or a path like "/day.html"). */
export const open=path=>self.deftBridge?self.deftBridge.invoke('widget.open',path?{path}:{}):Promise.reject(Error('Open in Deft'));
/** Widget frame: the canvas's 6 s rest arrives as 'deft:rest' / 'deft:resume' from the parent. */
function rest(){const P=self.parent;if(!D||!P||P===self)return;addEventListener('message',e=>{const m=e.data;if(e.source!==P||m!='deft:rest'&&m!='deft:resume')return;const was=self.__deftRested,l=self.__deftRested=m=='deft:rest'?was||[]:null;if(l)D.getAnimations().forEach(a=>{const t=a.effect?.getComputedTiming?.();if(a.playState=='running'&&t?.iterations===Infinity){a.pause();l.push(a)}});else was?.forEach(a=>a.playState=='paused'&&a.play())})}
const seen=new WeakSet(),io=typeof IntersectionObserver=='function'&&new IntersectionObserver(es=>es.forEach(e=>{if(e.isIntersecting){io.unobserve(e.target);enter(e.target)}}),{threshold:.1});
function enter(box){if(seen.has(box))return;seen.add(box);const rm=RM(),m=motionFor(box.dataset.deftEnter,rm,tok('--deft-spring-smooth','cubic-bezier(.2,.8,.2,1)'),parseMs(tok('--deft-spring-smooth-duration','560ms')));box.classList.add('deft-in');[...box.children].forEach((c,i)=>c.animate(m.keyframes,{duration:m.duration,easing:m.easing,fill:'backwards',delay:rm?0:staggerDelay(i)}))}
/** deft.enter(el, kind?): play the enter stagger on el's children now (again, if it already ran). For lists filled after load. */
export const replay=(box,kind)=>{if(kind)box.dataset.deftEnter=kind;seen.delete(box);enter(box)};
const scan=r=>{if(!r.querySelectorAll||!io)return;[...(r.matches?.('[data-deft-enter]')?[r]:[]),...r.querySelectorAll('[data-deft-enter]')].forEach(e=>seen.has(e)||io.observe(e))};
function init(){if(self.__deftMotion||!D?.documentElement)return;self.__deftMotion=1;D.documentElement.classList.add('deft-motion');self.deft=Object.assign(self.deft||{},{haptic,toast,dialog,confirm,prompt,enter:replay,open});self.fl=self.deft;/* fl: pre-rename alias */
  rest();
  D.addEventListener('pointerdown',e=>{if(e.target.closest?.('.deft-btn,.deft-cell[href],[data-deft-press]'))haptic('light')},{passive:true});
  initSheets(D,haptic);
  new MutationObserver(ms=>ms.forEach(m=>m.addedNodes.forEach(n=>n.nodeType==1&&scan(n)))).observe(D,{subtree:true,childList:true});
  scan(D);
}
init();
