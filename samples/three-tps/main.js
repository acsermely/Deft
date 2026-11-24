// Fireball Duel — game logic + main loop. Practice vs AI or BLE duel over deftBridge.
import '/_deft/v1/motion.js';
import bt from './lib/bt.js';

const status = document.getElementById('status');
const startBtn = document.getElementById('startBtn');
const hostJoin = document.getElementById('hostJoin');
const scanList = document.getElementById('scanList');
const btInfo = document.getElementById('bt');
let THREE;
try {
  THREE = await import('https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.min.js');
  status.textContent = '✓ three.js loaded';
} catch(e){
  status.textContent = '✗ ' + e.message;
  throw e;
}

// ---------- game constants ----------
const COURT_W = 10, COURT_L = 24;
const PLAYER_Z = 9, ENEMY_Z = -9;
const FB_SPEED = 24, FB_DMG = 12, FB_CD = 0.9;
const SHIELD_DRAIN = 40, SHIELD_REGEN = 18, SHIELD_MAX = 100;

// ---------- bridge ----------
let bridge = null;
if (window.deftBridge){
  bridge = window.deftBridge;
  await bridge.ready;
}
bt.init(bridge, {
  onMessage: onNetMsg,
  onStatus: t => {
    btInfo.textContent = t;
    btInfo.style.display = 'block';
    if (t === 'guest connected'){
      mode = 'host';
      hostJoin.hidden = true;
      startBtn.hidden = false;
      status.textContent = '✓ Guest joined — tap to calibrate & start';
    }
  },
});

// ---------- scene ----------
const scene = new THREE.Scene();
scene.background = new THREE.Color(0x0d1420);
scene.fog = new THREE.Fog(0x0d1420, 30, 70);
const camera = new THREE.PerspectiveCamera(70, innerWidth/innerHeight, 0.1, 100);
const renderer = new THREE.WebGLRenderer({antialias:true});
renderer.setSize(innerWidth, innerHeight);
renderer.setPixelRatio(Math.min(devicePixelRatio,2));
document.body.insertBefore(renderer.domElement, document.getElementById('ui'));
addEventListener('resize', ()=>{camera.aspect=innerWidth/innerHeight;camera.updateProjectionMatrix();renderer.setSize(innerWidth,innerHeight);});

// arena: circular stone floor, rings, spokes, wall segments, torch pillars, banners
const ARENA_R = 12, WALL_R = 12.6;
const arenaFloor = new THREE.Mesh(new THREE.CircleGeometry(ARENA_R+2, 48), new THREE.MeshLambertMaterial({color:0x6b6154}));
arenaFloor.rotation.x = -Math.PI/2; scene.add(arenaFloor);
const ringMat = new THREE.MeshBasicMaterial({color:0x4a4238});
for (let r=3; r<=ARENA_R; r+=3){
  const ring = new THREE.Mesh(new THREE.RingGeometry(r-0.12, r, 48), ringMat);
  ring.rotation.x = -Math.PI/2; ring.position.y = 0.01; scene.add(ring);
}
for (let a=0; a<Math.PI*2; a+=Math.PI/6){
  const spoke = new THREE.Mesh(new THREE.PlaneGeometry(0.12, ARENA_R*2), ringMat);
  spoke.rotation.x = -Math.PI/2; spoke.rotation.z = a; spoke.position.y = 0.008; scene.add(spoke);
}
const wallSegs = 36;
for (let i=0;i<wallSegs;i++){
  const a = (i/wallSegs)*Math.PI*2;
  const seg = new THREE.Mesh(new THREE.BoxGeometry(2.3, 1.6, 0.5), new THREE.MeshLambertMaterial({color:0x7a7062}));
  seg.position.set(Math.cos(a)*WALL_R, 0.8, Math.sin(a)*WALL_R);
  seg.rotation.y = -a; scene.add(seg);
}
const torchMat = new THREE.MeshLambertMaterial({color:0x55504a});
const flameMat = new THREE.MeshBasicMaterial({color:0xffaa33});
for (let i=0;i<8;i++){
  const a = (i/8)*Math.PI*2 + Math.PI/8;
  const px = Math.cos(a)*WALL_R, pz = Math.sin(a)*WALL_R;
  const pillar = new THREE.Mesh(new THREE.CylinderGeometry(0.35,0.45,3.2,8), torchMat);
  pillar.position.set(px, 1.6, pz); scene.add(pillar);
  const flame = new THREE.Mesh(new THREE.SphereGeometry(0.28,8,6), flameMat);
  flame.position.set(px, 3.4, pz); scene.add(flame);
  const tl = new THREE.PointLight(0xff8833, 1.5, 9);
  flame.add(tl);
  scene.add(flame);
}
const bannerCols = [0x8b1a1a, 0x1a3a8b, 0x8b7a1a, 0x3a1a8b];
for (let i=0;i<8;i++){
  const a = (i/8)*Math.PI*2 + Math.PI/8;
  const b = new THREE.Mesh(new THREE.PlaneGeometry(1.2, 2.2), new THREE.MeshLambertMaterial({color:bannerCols[i%4], side:THREE.DoubleSide}));
  b.position.set(Math.cos(a)*WALL_R*0.98, 2.2, Math.sin(a)*WALL_R*0.98);
  b.rotation.y = -a + Math.PI/2; scene.add(b);
}
scene.add(new THREE.HemisphereLight(0x8899bb, 0x223322, 1.2));
const dl = new THREE.DirectionalLight(0xffffff, 0.8); dl.position.set(5,10,3); scene.add(dl);

// characters
function makeChar(color){
  const g = new THREE.Group();
  const body = new THREE.Mesh(new THREE.CapsuleGeometry(0.4,0.9,4,8), new THREE.MeshLambertMaterial({color}));
  body.position.y = 0.95; g.add(body);
  const head = new THREE.Mesh(new THREE.SphereGeometry(0.3,12,10), new THREE.MeshLambertMaterial({color:0xffcc99}));
  head.position.y = 1.85; g.add(head);
  return g;
}
const player = makeChar(0x3a7bd5); player.position.set(0,0,PLAYER_Z); scene.add(player);
const enemy  = makeChar(0xd53a3a); enemy.position.set(0,0,ENEMY_Z); scene.add(enemy);
const shieldMesh = new THREE.Mesh(new THREE.SphereGeometry(1.15,16,12,0,Math.PI*2,0,Math.PI),
  new THREE.MeshBasicMaterial({color:0x44aaff,transparent:true,opacity:0.35,side:THREE.DoubleSide}));
shieldMesh.position.y = 1.1; shieldMesh.visible = false; player.add(shieldMesh);
const enemyShield = shieldMesh.clone(); enemyShield.position.y=1.1; enemy.add(enemyShield);
const aimDot = new THREE.Mesh(new THREE.RingGeometry(0.12,0.22,20),
  new THREE.MeshBasicMaterial({color:0xffcc44, transparent:true, opacity:0.9, side:THREE.DoubleSide}));
aimDot.rotation.x = -Math.PI/2; scene.add(aimDot);

function camUpdate(){
  const back = 6.5, height = 3.6;
  camera.position.set(player.position.x + Math.sin(yaw)*back*0.35, height, player.position.z + back);
  camera.lookAt(player.position.x, 1.3, player.position.z - 5);
}

// ---------- input ----------
let kbYaw = 0; // keyboard aim fallback
const keys = {};
let yaw = 0, targetYaw = 0;
addEventListener('keydown', e=>keys[e.key.toLowerCase()]=true);
addEventListener('keyup', e=>keys[e.key.toLowerCase()]=false);

const stick = document.getElementById('stick'), knob = document.getElementById('knob');
let jx=0, jy=0, stickId=null;
function stickMove(t){
  const r = stick.getBoundingClientRect();
  let dx = (t.clientX-(r.left+r.width/2))/(r.width/2), dy=(t.clientY-(r.top+r.height/2))/(r.height/2);
  const len = Math.hypot(dx,dy); if(len>1){dx/=len;dy/=len;}
  jx=dx; jy=dy;
  knob.style.left = (40+dx*40)+'px'; knob.style.top = (40+dy*40)+'px';
}
stick.addEventListener('touchstart', e=>{stickId=e.changedTouches[0].identifier;stickMove(e.changedTouches[0]);e.preventDefault();});
stick.addEventListener('touchmove', e=>{for(const t of e.changedTouches) if(t.identifier===stickId) stickMove(t);e.preventDefault();});
stick.addEventListener('touchend', e=>{for(const t of e.changedTouches) if(t.identifier===stickId){stickId=null;jx=jy=0;knob.style.left='40px';knob.style.top='40px';}});
let mdown=false;
stick.addEventListener('mousedown',e=>{mdown=true;stickMove(e);});
addEventListener('mousemove',e=>{if(mdown)stickMove(e);});
addEventListener('mouseup',()=>{mdown=false;jx=jy=0;knob.style.left='40px';knob.style.top='40px';});

const fireBtn = document.getElementById('fire'), shieldBtn = document.getElementById('shield');
let fireHeld=false, shieldHeld=false;
fireBtn.addEventListener('touchstart',e=>{e.preventDefault();fireHeld=true;});
fireBtn.addEventListener('touchend',e=>{fireHeld=false;});
fireBtn.addEventListener('mousedown',()=>fireHeld=true);
addEventListener('mouseup',()=>fireHeld=false);
shieldBtn.addEventListener('touchstart',e=>{e.preventDefault();shieldHeld=true;});
shieldBtn.addEventListener('touchend',e=>{shieldHeld=false;});
shieldBtn.addEventListener('mousedown',()=>shieldHeld=true);
addEventListener('mouseup',()=>shieldHeld=false);

// ---------- state ----------
let hp=100, ehp=100, stam=SHIELD_MAX, fireTimer=0, shieldOn=false;
let running=false, over=false;
let mode = 'ai';            // 'ai' | 'host' | 'guest'
let enemyState = { x:0, z:ENEMY_Z, shield:false };  // remote enemy pos (BLE mode)
const fireballs = []; // {mesh, from:'p'|'e', dirX, dirZ}
const msg = document.getElementById('msg');
const phpBar=document.getElementById('php'), ehpBar=document.getElementById('ehp'),
      stBar=document.getElementById('stam'), phpTxt=document.getElementById('phpTxt'), ehpTxt=document.getElementById('ehpTxt');

function spawnFB(from, x, z, dirX, dirZ){
  const m = new THREE.Mesh(new THREE.SphereGeometry(0.28,10,8), new THREE.MeshBasicMaterial({color: from==='p'?0xff7722:0xff3333}));
  const light = new THREE.PointLight(0xff6622, 2, 6); m.add(light);
  m.position.set(x, 1.1, z); scene.add(m);
  fireballs.push({mesh:m, from, dirX, dirZ});
}

// ---------- networking ----------
// messages: {t:'state', x, z, sy}  (pos + shield)  {t:'fire', x, z, dx, dz}  {t:'hit'}  {t:'over', win}
let lastSend = 0;
function sendState(){
  if (mode === 'ai') return;
  bt.send({ t:'state', x:+player.position.x.toFixed(2), z:+player.position.z.toFixed(2), sy:shieldOn?1:0 });
}
function onNetMsg(m){
  if (m.t === 'state'){
    enemyState.x = m.x; enemyState.z = m.z; enemyState.shield = !!m.sy;
    enemy.position.x = m.x; enemy.position.z = m.z;
    enemyShield.visible = !!m.sy;
  } else if (m.t === 'fire'){
    spawnFB('e', m.x, m.z, m.dx, m.dz);
  } else if (m.t === 'hit'){
    hp = Math.max(0, hp - FB_DMG); // sender confirms they hit us (authoritative)
    if (hp<=0) endGame(true);
  } else if (m.t === 'over'){
    endGame(false);
  }
}

// ---------- AI opponent (practice mode) ----------
let aiTimer = 2;
function aiUpdate(dt){
  if(over||!running) return;
  const ESPEED = 2.2;
  const dxTrack = player.position.x - enemy.position.x;
  if (Math.abs(dxTrack) > 0.8) {
    enemy.position.x = THREE.MathUtils.clamp(
      enemy.position.x + Math.sign(dxTrack) * ESPEED * dt,
      -COURT_W/2+0.6, COURT_W/2-0.6);
  }
  aiTimer -= dt;
  if(aiTimer <= 0){
    aiTimer = 1.6 + Math.random()*1.4;
    const dz = player.position.z - enemy.position.z;
    const dx = player.position.x - enemy.position.x + (Math.random()-0.5)*1.4;
    const aim = Math.atan2(dx, dz);
    enemyShield.visible = false;
    spawnFB('e', enemy.position.x, enemy.position.z, Math.sin(aim), Math.cos(aim));
  }
}

// ---------- combat ----------
function hitTest(f){
  const target = f.from==='p' ? enemy : player;
  const dx = f.mesh.position.x - target.position.x, dz = f.mesh.position.z - target.position.z;
  if (Math.hypot(dx,dz) < 0.75){
    if (f.from==='e' && shieldOn){ scene.remove(f.mesh); return true; }
    if (mode === 'ai'){
      // practice: local authority
      if (f.from==='p') ehp = Math.max(0, ehp - FB_DMG); else hp = Math.max(0, hp - FB_DMG);
      if (hp<=0||ehp<=0) endGame(hp<=0);
    } else {
      // BLE: each side is authoritative over its own HP; tell the victim
      if (f.from==='p'){
        bt.send({ t:'hit' });
        ehp = Math.max(0, ehp - FB_DMG);
        if (ehp<=0){ bt.send({ t:'over', win:true }); endGame(false); }
      }
      // incoming hit on us is applied via the 'hit' message (onNetMsg)
    }
    scene.remove(f.mesh);
    return true;
  }
  return false;
}

function endGame(lost){
  over = true;
  msg.textContent = lost ? '💀 YOU LOSE — tap to rematch' : '🏆 YOU WIN — tap to rematch';
  msg.style.pointerEvents='auto'; msg.onclick = ()=>location.reload();
}

// ---------- orientation ----------
let orientActive = false;
function onOrient(e){
  if (!running) return;
  let v = e.gamma ?? 0;
  if (Math.abs(e.beta ?? 0) > 60) v = e.alpha != null ? 0 : v;
  targetYaw = -THREE.MathUtils.degToRad(v) * 1.6;
}
function startOrient(){
  if (typeof DeviceOrientationEvent!=='undefined' && DeviceOrientationEvent.requestPermission){
    DeviceOrientationEvent.requestPermission().then(s=>{
      if(s==='granted'){ addEventListener('deviceorientation', onOrient); orientActive=true; }
    }).catch(()=>{});
  } else {
    addEventListener('deviceorientation', onOrient); orientActive=true;
  }
}
startBtn.addEventListener('click', ()=>{
  startOrient();
  document.getElementById('cal').hidden = true;
  running = true;
  msg.textContent = '';
});

// ---------- lobby ----------
document.getElementById('btnAi').addEventListener('click', ()=>{
  mode = 'ai';
  hostJoin.hidden = true;
  startBtn.hidden = false;
  status.textContent = '✓ Practice mode — three.js r' + THREE.REVISION;
});

document.getElementById('btnHost').addEventListener('click', async ()=>{
  if (!bridge){ status.textContent = 'BLE requires Deft'; return; }
  try {
    await bt.startHost();
    status.textContent = 'Hosting… waiting for guest (both phones need Bluetooth on)';
  } catch(e){ status.textContent = 'Host failed: ' + (e.message||e); }
});

document.getElementById('btnJoin').addEventListener('click', async ()=>{
  if (!bridge){ status.textContent = 'BLE requires Deft'; return; }
  scanList.replaceChildren();
  status.textContent = 'Scanning for FireDuel hosts…';
  try {
    const off = bt.scanForHost(d => {
      if (!d.deviceId || scanList.querySelector(`[data-id="${d.deviceId}"]`)) return;
      const b = document.createElement('button');
      b.className = 'deft-cell';
      b.dataset.deftPress = '';
      b.dataset.id = d.deviceId;
      b.textContent = d.name || d.deviceId;
      b.addEventListener('click', async ()=>{
        await bt.stopScan();
        status.textContent = 'Connecting…';
        try {
          await bt.connectGuest(d.deviceId);
          mode = 'guest';
          hostJoin.hidden = true;
          startBtn.hidden = false;
          status.textContent = '✓ Connected — three.js r' + THREE.REVISION;
        } catch(e){ status.textContent = 'Connect failed: ' + (e.message||e); }
      });
      scanList.appendChild(b);
    });
    // stop scanning after 12s
    setTimeout(()=>{
      off();
      bt.stopScan();
      if (mode !== 'guest') status.textContent = 'Scan finished — no hosts found';
    }, 12000);
  } catch(e){ status.textContent = 'Scan failed: ' + (e.message||e); }
});

// ---------- loop ----------
let last = performance.now();
function loop(t){
  requestAnimationFrame(loop);
  const dt = Math.min(0.05,(t-last)/1000); last = t;
  if (running && !over){
    // movement
    const sp = 5;
    let mx = jx, mz = jy;
    if (keys['a']) mx -= 1; if (keys['d']) mx += 1;
    if (keys['w']) mz -= 1; if (keys['s']) mz += 1;
    player.position.x = THREE.MathUtils.clamp(player.position.x + mx*sp*dt, -COURT_W/2+0.6, COURT_W/2-0.6);
    player.position.z = THREE.MathUtils.clamp(player.position.z + mz*sp*dt, 1.5, COURT_L/2-0.6);
    if (keys['arrowleft']) kbYaw -= dt*1.5;
    if (keys['arrowright']) kbYaw += dt*1.5;
    if (!orientActive) targetYaw = kbYaw;
    yaw += (targetYaw - yaw) * Math.min(1, dt*8);
    // fire (rate limited)
    fireTimer = Math.max(0, fireTimer - dt);
    fireBtn.classList.toggle('cd', fireTimer>0);
    if (fireHeld && fireTimer<=0){
      fireTimer = FB_CD;
      const dx = Math.sin(yaw), dz = -Math.cos(yaw);
      spawnFB('p', player.position.x, player.position.z, dx, dz);
      if (mode !== 'ai') bt.send({ t:'fire', x:+player.position.x.toFixed(2), z:+player.position.z.toFixed(2), dx:+dx.toFixed(3), dz:+dz.toFixed(3) });
    }
    // shield
    if (shieldHeld && stam>1 && !shieldOn){ shieldOn=true; }
    if (!shieldHeld || stam<=0.5){ shieldOn=false; }
    shieldMesh.visible = shieldOn;
    if (shieldOn) stam = Math.max(0, stam - SHIELD_DRAIN*dt);
    else stam = Math.min(SHIELD_MAX, stam + SHIELD_REGEN*dt);
    shieldMesh.material.opacity = 0.2 + 0.25*(stam/SHIELD_MAX);
    // fireballs
    for (let i=fireballs.length-1;i>=0;i--){
      const f = fireballs[i];
      f.mesh.position.x += f.dirX*FB_SPEED*dt;
      f.mesh.position.z += f.dirZ*FB_SPEED*dt;
      if (hitTest(f)){ fireballs.splice(i,1); continue; }
      if (Math.abs(f.mesh.position.z)>COURT_L/2+2 || Math.abs(f.mesh.position.x)>COURT_W/2+4){
        scene.remove(f.mesh); fireballs.splice(i,1);
      }
    }
    // enemy: AI in practice, remote state in BLE
    if (mode === 'ai') aiUpdate(dt);
    // send our state ~10/s over BLE
    if (mode !== 'ai'){ lastSend -= dt; if (lastSend <= 0){ lastSend = 0.1; sendState(); } }
    // aim dot
    const ad = 4.5;
    aimDot.position.set(player.position.x + Math.sin(yaw)*ad, 0.02, player.position.z - Math.cos(yaw)*ad);
    aimDot.material.opacity = 0.35 + 0.35*(1 - fireTimer/FB_CD);
    // HUD
    phpBar.style.setProperty('--deft-level', hp/100); ehpBar.style.setProperty('--deft-level', ehp/100); stBar.style.setProperty('--deft-level', stam/SHIELD_MAX);
    phpTxt.textContent = hp; ehpTxt.textContent = ehp;
  }
  camUpdate();
  renderer.render(scene, camera);
}
requestAnimationFrame(loop);
