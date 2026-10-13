const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const test = require('node:test');
const source = fs.readFileSync('resources/public/js/document-entrance.js', 'utf8');
function deferred() { let resolve, reject; const promise = new Promise((a,b) => {resolve=a; reject=b;}); return {promise,resolve,reject}; }
function fixture({pending=true, width=390, dpr=2, same=false, reduced=false, nativeDecode=true, cached=false}={}) {
  const frames = new Map(), timers = new Map(); let sequence = 0, observer;
  class Element extends EventTarget {
    constructor(attrs={}) { super(); this.attrs=new Map(Object.entries(attrs)); this.listeners=new Map(); }
    getAttribute(k) { return this.attrs.get(k) ?? null; }
    setAttribute(k,v) { this.attrs.set(k,String(v)); }
    removeAttribute(k) { this.attrs.delete(k); }
    hasAttribute(k) { return this.attrs.has(k); }
    addEventListener(k,v) { super.addEventListener(k,v); this.listeners.set(k,(this.listeners.get(k)||0)+1); }
    removeEventListener(k,v) { super.removeEventListener(k,v); this.listeners.set(k,(this.listeners.get(k)||0)-1); }
  }
  const root = new Element(pending ? {'data-document-pending':'true'} : {});
  const hero = new Element({'data-preview-width':'400','data-image-sizes':'100vw'});
  const preview = new Element({src:'/hero.jpg'}), final = new Element({src:'/hero.jpg'});
  const animations = deferred();
  for (const [image, url] of [[preview,'/hero-400.avif'],[final,same?'/hero-400.avif':'/hero-800.avif']]) {
    image.complete=cached; image.naturalWidth=cached?800:0; image.currentSrc=url;
    const decode=deferred(); image.decoding=decode;
    if(nativeDecode) image.decode=()=>decode.promise;
  }
  final.getAnimations=()=>reduced?[]:[{finished:animations.promise}];
  preview.closest=()=>hero;
  root.querySelector=()=>hero; root.contains=node=>[hero,preview,final].includes(node) && !hero.removed && !node.removed;
  hero.querySelector=selector=>selector.includes('preview')?preview:final;
  hero.matches=()=>false;
  const window = new Element();
  class Observer {constructor(callback){this.callback=callback;observer=this;} observe(){} disconnect(){this.disconnected=true;}}
  const context={document:{querySelector:()=>root}, Event, MutationObserver:Observer, window,
    innerWidth:width, devicePixelRatio:dpr, matchMedia:()=>({matches:reduced}),
    getComputedStyle:node=>({transitionDuration:'0.1s',display:node.hidden?'none':'block'}),
    requestAnimationFrame:callback=>{const n=++sequence;frames.set(n,callback);return n;},
    cancelAnimationFrame:n=>frames.delete(n), setTimeout:callback=>{const n=++sequence;timers.set(n,callback);return n;},clearTimeout:n=>timers.delete(n), Promise};
  vm.runInNewContext(source,context);
  const flush=async()=>{await Promise.resolve();await Promise.resolve();};
  const frame=async()=>{const current=[...frames.values()];frames.clear();for(const callback of current)callback();await flush();};
  const load=async image=>{image.complete=true;image.naturalWidth=800;image.dispatchEvent(new Event('load'));image.decoding.resolve();await flush();};
  return {root,hero,preview,final,animations,observer,frames,timers,window,flush,frame,load,
    phase:()=>root.getAttribute('data-document-image-phase')};
}
test('decoded SSR preview releases clock; final overlaps under the same native owner',async()=>{
  const f=fixture(); await f.load(f.preview); assert.equal(f.phase(),'waiting');
  await f.frame();await f.frame();assert.equal(f.root.hasAttribute('data-document-pending'),false);await f.frame();
  assert.equal(f.root.getAttribute('data-document-image-owner'),'native');
  await f.load(f.final);assert.equal(f.phase(),'upgrading');
  // Hydration does not acknowledge ownership until the transition is terminal.
  await f.frame();assert.equal(f.phase(),'upgrading');
  f.animations.resolve();await f.flush();assert.equal(f.phase(),'ready');
  f.hero.setAttribute('data-image-managed','true');f.observer.callback();
  assert.equal(f.root.hasAttribute('data-document-image-owner'),false);
  assert.equal(f.root.__progressiveHero,undefined);assert.equal(f.observer.disconnected,true);
  assert.equal(f.final.listeners.get('load'),0);assert.equal(f.preview.listeners.get('error'),0);
});
test('already usable paired return settles without a fade or entrance',async()=>{
  const f=fixture({pending:false,cached:true,nativeDecode:false});
  assert.equal(f.phase(),'ready');assert.equal(f.frames.size,0);
  assert.equal(f.root.hasAttribute('data-document-pending'),false);
});
test('shared candidate and reduced motion bypass the upgrade',async()=>{
  const same=fixture({same:true});await same.load(same.preview);assert.equal(same.phase(),'single');
  await same.load(same.final);assert.equal(same.phase(),'single');
  const reduced=fixture({reduced:true});await reduced.load(reduced.preview);await reduced.frame();await reduced.frame();await reduced.load(reduced.final);
  assert.equal(reduced.phase(),'ready');
  const pending=fixture({width:375,dpr:1});pending.final.currentSrc='';pending.preview.currentSrc='';
  // Re-run selection at preview readiness, before the shared final decode resolves.
  await pending.load(pending.preview);assert.equal(pending.phase(),'single');
});
test('failure preserves usable preview and does not strand the page gate',async()=>{
  const f=fixture();await f.load(f.preview);f.final.dispatchEvent(new Event('error'));
  assert.equal(f.phase(),'failed');await f.frame();await f.frame();
  assert.equal(f.root.hasAttribute('data-document-pending'),false);
});
test('new source or replaced hero disposes native callbacks; stale decode is ignored',async()=>{
  const f=fixture();f.final.setAttribute('src','/other.jpg');f.observer.callback();
  await f.load(f.preview);assert.equal(f.phase(),null);assert.equal(f.observer.disconnected,true);
  assert.equal(f.root.__progressiveHero,undefined);assert.equal(f.final.listeners.get('error'),0);
  const replaced=fixture();replaced.hero.removed=true;replaced.observer.callback();
  assert.equal(replaced.root.hasAttribute('data-document-image-owner'),false);
});
test('a late final upgrades after the bounded entrance gate, and BFCache settles',async()=>{
  const f=fixture();for(const callback of [...f.timers.values()])callback();
  await f.frame();await f.frame();assert.equal(f.root.hasAttribute('data-document-pending'),false);
  await f.load(f.preview);await f.frame();await f.load(f.final);assert.equal(f.phase(),'upgrading');
  const event=new Event('pageshow');event.persisted=true;f.window.dispatchEvent(event);
  assert.equal(f.phase(),'ready');
});

test('same-source adoption does not release the sole pending final decode gate',async()=>{
  const f=fixture({same:true});assert.equal(f.phase(),'single');
  f.hero.setAttribute('data-image-managed','true');f.preview.removed=true;f.observer.callback();
  assert.equal(f.root.hasAttribute('data-document-pending'),true);
  assert.equal(f.root.hasAttribute('data-document-image-owner'),false);
  assert.equal(f.final.listeners.get('load'),1);
  await f.frame();await f.frame();assert.equal(f.root.hasAttribute('data-document-pending'),true);
  await f.load(f.final);await f.frame();await f.frame();
  assert.equal(f.root.hasAttribute('data-document-pending'),false);
  assert.equal(f.final.listeners.get('load'),0);
});
test('a final ready before the preview paint opportunity does not fade',async()=>{
  const f=fixture();await f.load(f.preview);await f.load(f.final);
  assert.equal(f.phase(),'ready');
});
test('missing animation enumeration waits for native transition completion',async()=>{
  const f=fixture();delete f.final.getAnimations;
  await f.load(f.preview);await f.frame();await f.frame();await f.frame();await f.load(f.final);await f.frame();
  assert.equal(f.phase(),'upgrading');
  const event=new Event('transitionend');event.propertyName='opacity';f.final.dispatchEvent(event);
  assert.equal(f.phase(),'ready');
});

test('usable preview after final failure/managed retry still releases the gate',async()=>{
  const f=fixture();f.final.dispatchEvent(new Event('error'));assert.equal(f.phase(),'failed');
  f.hero.setAttribute('data-image-managed','true');f.hero.setAttribute('data-image-retrying','true');f.observer.callback();
  assert.equal(f.root.hasAttribute('data-document-pending'),true);
  await f.load(f.preview);await f.frame();await f.frame();
  assert.equal(f.root.hasAttribute('data-document-pending'),false);
});
test('final decode in the reveal frame settles without an unseen preview fade',async()=>{
  const f=fixture();await f.load(f.preview);await f.frame();await f.frame();
  await f.load(f.final);assert.equal(f.phase(),'ready');
});

test('BFCache can complete a still-pending same-source gate after managed adoption',async()=>{
  const f=fixture({same:true});f.hero.setAttribute('data-image-managed','true');f.preview.removed=true;f.observer.callback();
  f.final.complete=true;f.final.naturalWidth=400;f.final.decoding.resolve();
  const event=new Event('pageshow');event.persisted=true;f.window.dispatchEvent(event);
  await f.flush();await f.frame();await f.frame();
  assert.equal(f.root.hasAttribute('data-document-pending'),false);
  assert.equal(f.final.listeners.get('load'),0);
});

test('duplicate cached/load decode completions cannot shorten an active overlap',async()=>{
  const f=fixture();await f.load(f.preview);await f.frame();await f.frame();await f.frame();
  await f.load(f.final);assert.equal(f.phase(),'upgrading');
  f.final.dispatchEvent(new Event('load'));await f.flush();assert.equal(f.phase(),'upgrading');
  await f.frame();assert.equal(f.phase(),'upgrading');
  f.animations.resolve();await f.flush();assert.equal(f.phase(),'ready');
});
