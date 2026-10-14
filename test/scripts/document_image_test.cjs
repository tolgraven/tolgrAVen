const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const test = require('node:test');
const head = fs.readFileSync('resources/public/js/document/head.js', 'utf8');
const source = fs.readFileSync('resources/public/js/document/entrance.js', 'utf8');
function deferred() { let resolve, reject; const promise = new Promise((a,b) => {resolve=a; reject=b;}); return {promise,resolve,reject}; }
function fixture({pending=true, width=390, dpr=2, same=false, reduced=false, nativeDecode=true, cached=false, direct=false, scale=1, duplicate=false, placeholder=false, previewOmitted=false, managed=false, nativeImage=false, skeleton=false, complete=true, target=false, targetMounted=true, fontsLoading=false}={}) {
  const frames = new Map(), timers = new Map(), observers=[]; let sequence = 0, observer, completed=complete;
  class Element extends EventTarget {
    constructor(attrs={}) { super(); this.attrs=new Map(Object.entries(attrs)); this.listeners=new Map(); this.properties=new Map(); this.style={setProperty:(k,v)=>this.properties.set(k,v),removeProperty:k=>this.properties.delete(k)}; }
    querySelectorAll() { return []; }
    matches() { return false; }
    getAttribute(k) { return this.attrs.get(k) ?? null; }
    setAttribute(k,v) { this.attrs.set(k,String(v)); }
    removeAttribute(k) { this.attrs.delete(k); }
    hasAttribute(k) { return this.attrs.has(k); }
    addEventListener(k,v) { super.addEventListener(k,v); this.listeners.set(k,(this.listeners.get(k)||0)+1); }
    removeEventListener(k,v) { super.removeEventListener(k,v); this.listeners.set(k,(this.listeners.get(k)||0)-1); }
  }
  const root = new Element(pending ? {'data-document-pending':'true'} : {});
  if(target) root.setAttribute('data-document-target','comment-target');
  root.offsetTop=300; root.offsetParent=null;
  const destination = new Element();destination.offsetTop=1200;destination.offsetParent=root;destination.offsetHeight=40;
  let mounted=targetMounted;
  const fonts=deferred(), scrolls=[];
  const body = new Element();
  const shell = skeleton ? new Element() : null;
  if (shell) shell.remove=()=>{shell.removed=true;};
  let hero = new Element({'data-preview-width':'400','data-image-sizes':'100vw','data-image-key':'_R_hero'});
  const html = new Element(direct?{'data-image-direct':'true'}:{});
  html.scrollHeight=4000;
  hero.parentElement=root;
  if(placeholder) hero.setAttribute('data-image-placeholder','true');
  if(managed) hero.setAttribute('data-image-managed','true');
  const preview = new Element({src:'/hero.jpg'}), final = new Element({src:'/hero.jpg'});
  const animations = deferred();
  for (const [image, url] of [[preview,'/hero-400.avif'],[final,same?'/hero-400.avif':'/hero-800.avif']]) {
    image.parentElement=hero;
    image.complete=cached; image.naturalWidth=cached?800:0; image.currentSrc=url;
    const decode=deferred(); image.decoding=decode;
    if(nativeDecode) image.decode=()=>decode.promise;
  }
  final.getAnimations=()=>reduced?[]:[{finished:animations.promise}];
  preview.closest=()=>hero;
  const other = duplicate ? {hero:new Element({'data-preview-width':'400','data-image-sizes':'100vw','data-image-key':'_R_other'}),preview:new Element({src:'/hero.jpg'}),final:new Element({src:'/hero.jpg'})} : null;
  if (other) {
    other.hero.parentElement=root;
  if(placeholder) hero.setAttribute('data-image-placeholder','true');
  if(managed) hero.setAttribute('data-image-managed','true');
    other.hero.querySelector=selector=>selector.includes('preview')?other.preview:other.final;
    for (const [image,url] of [[other.preview,'/hero-400.avif'],[other.final,'/hero-800.avif']]) {
      image.parentElement=other.hero;image.complete=false;image.naturalWidth=0;image.currentSrc=url;
      image.decoding=deferred();image.decode=()=>image.decoding.promise;image.closest=()=>other.hero;
    }
    other.final.getAnimations=()=>[];
  }
  if(nativeImage) {
    hero=final;final.parentElement=root;
    final.setAttribute('data-image-key','_R_hero');
    if(placeholder) final.setAttribute('data-image-placeholder','true');
    if(managed) final.setAttribute('data-image-managed','true');
  }
  root.querySelector=()=>hero; root.querySelectorAll=()=>other?[hero,other.hero]:[hero]; root.contains=node=>[hero,preview,final,...(other?Object.values(other):[])].includes(node) && !hero.removed && !node.removed;
  hero.querySelector=selector=>nativeImage?null:(selector.includes('preview')?(previewOmitted?null:preview):final);
  hero.matches=()=>nativeImage; hero.querySelectorAll=()=>[];
  const window = new Element();window.matchMedia=()=>({matches:reduced});
  window.scrollTo=options=>{scrolls.push({...options,pending:root.hasAttribute('data-document-pending')});};
  class Observer {constructor(callback){this.callback=callback;observer=this;observers.push(this);this.observations=[];} observe(target,options){this.observations.push({target,options});this.target=target;this.options??=options;} disconnect(){this.disconnected=true;}}
  const document=new Element();Object.assign(document,{body,querySelector:selector=>selector==='#app'?root:selector==='#ssr-shell'?shell:selector==='#ssr-complete'&&shell&&completed?{}:null,documentElement:html,getElementById:id=>id==='comment-target'&&mounted?destination:null,fonts:{status:fontsLoading?'loading':'loaded',ready:fonts.promise}});
  if(!fontsLoading) fonts.resolve();
  const context={performance:{getEntriesByType:()=>[{type:direct?'back_forward':'navigate'}]},document, location:{pathname:'/blog/post/42/comment/target'}, Event, MutationObserver:Observer, window,
    innerWidth:width, innerHeight:700, devicePixelRatio:dpr, matchMedia:()=>({matches:reduced}),
    DOMMatrixReadOnly:class{constructor(){this.a=scale;}},
    getComputedStyle:node=>({animationName:node===shell&&!reduced?'skeleton-dissolve':'none',animationDuration:node===shell&&!reduced?'0.16s':'0s',transitionDuration:'0.15s',scrollMarginTop:'100px',opacity:'1',visibility:'visible',transform:node===hero?'matrix(1,0,0,1,0,0)':'none',display:node.hidden||(node===root&&shell&&!completed)?'none':'block'}),
    requestAnimationFrame:callback=>{const n=++sequence;frames.set(n,callback);return n;},
    cancelAnimationFrame:n=>frames.delete(n), setTimeout:callback=>{const n=++sequence;timers.set(n,callback);return n;},clearTimeout:n=>timers.delete(n), Promise};
  vm.runInNewContext(head,context);
  vm.runInNewContext(source,context);
  const flush=async()=>{await Promise.resolve();await Promise.resolve();};
  const frame=async()=>{const current=[...frames.values()];frames.clear();for(const callback of current)callback();await flush();};
  const load=async image=>{image.complete=true;image.naturalWidth=800;image.dispatchEvent(new Event('load'));image.decoding.resolve();await flush();};
  const completeMarker=()=>{completed=true;for(const o of observers)if(!o.disconnected&&o.observations.some(x=>x.target===body&&x.options.childList))o.callback();};
  return {root,hero,preview,final,other,shell,animations,observer,observers,frames,timers,window,document,destination,scrolls,flush,frame,load,completeMarker,
    mountTarget:()=>{mounted=true;for(const o of observers)if(!o.disconnected&&o.observations.some(x=>x.target===root&&x.options.childList))o.callback();},
    readyFonts:async()=>{document.fonts.status='loaded';fonts.resolve();await flush();},
    phase:()=>root.__progressiveImages.get(hero)?.phase??null};
}
test('cold target positions before reveal and waits for mounted text and fonts',async()=>{
  const f=fixture({target:true,targetMounted:false,fontsLoading:true});
  await f.load(f.preview);await f.frame();await f.frame();
  assert.equal(f.root.hasAttribute('data-document-pending'),true);
  assert.equal(f.scrolls.length,0);
  f.mountTarget();await f.frame();assert.equal(f.scrolls.length,0);
  await f.readyFonts();await f.frame();await f.frame();
  assert.deepEqual(f.scrolls,[{top:1400,left:0,behavior:'instant',pending:true}]);
  assert.equal(f.root.getAttribute('data-document-positioned'),'comment-target');
  await f.frame();await f.frame();
  assert.equal(f.root.hasAttribute('data-document-pending'),false);
  assert.equal(f.document.listeners.get('load'),0);
  await f.frame(); // the independent preview-paint acknowledgement
  assert.equal(f.frames.size,0);
});
test('missing cold target releases the page without jumping or leaking its observer',async()=>{
  const f=fixture({target:true,targetMounted:false});
  await f.load(f.preview);await f.frame();
  for(const callback of [...f.timers.values()]) callback();
  await f.frame();await f.frame();
  assert.equal(f.root.hasAttribute('data-document-pending'),false);
  assert.equal(f.scrolls.length,0);
  assert.equal(f.document.listeners.get('load'),0);
  assert.equal(f.window.listeners.get('pagehide'),fixture().window.listeners.get('pagehide'),
    'The target listener is released while existing document/image owners remain');
});
test('the trailing stream marker wakes a target hidden from layout before completion',async()=>{
  const f=fixture({target:true,skeleton:true,complete:false});
  await f.load(f.preview);await f.frame();await f.frame();
  assert.equal(f.scrolls.length,0);
  assert.equal(f.root.hasAttribute('data-document-pending'),true);
  f.completeMarker();await f.frame();await f.frame();
  assert.equal(f.scrolls[0].pending,true);
  await f.frame();await f.frame();
  assert.equal(f.root.hasAttribute('data-document-pending'),false);
});
test('paired return and native history retain their saved position rather than reacquiring a cold target',async()=>{
  for(const options of [{pending:false},{direct:true}]) {
    const f=fixture({target:true,targetMounted:false,...options});
    await f.load(options.direct?f.final:f.preview);await f.frame();await f.frame();
    assert.equal(f.scrolls.length,0);
    assert.equal(f.document.listeners.get('load')??0,0);
    assert.equal(f.root.hasAttribute('data-document-pending'),false);
  }
});
test('streamed shell waits for the hero gate then releases its exact animation owner',async()=>{
  const f=fixture({skeleton:true});
  const owner=f.observers.find(o=>o.options.attributeFilter.includes('data-document-pending'));
  assert.equal(f.shell.removed,undefined);
  await f.load(f.preview);await f.frame();await f.frame();owner.callback();
  assert.equal(f.root.hasAttribute('data-document-pending'),false);
  assert.equal(f.shell.removed,undefined,'Keep the shell until its opacity animation ends');
  const wrong=new Event('animationend');Object.defineProperty(wrong,'animationName',{value:'component-pulse'});
  f.shell.dispatchEvent(wrong);assert.equal(f.shell.removed,undefined);
  const end=new Event('animationend');Object.defineProperty(end,'animationName',{value:'skeleton-dissolve'});
  f.shell.dispatchEvent(end);
  assert.equal(f.shell.removed,true);assert.equal(owner.disconnected,true);
  assert.equal(f.shell.listeners.get('animationend'),0);assert.equal(f.shell.listeners.get('animationcancel'),0);
  assert.equal(f.timers.size,0);
});
test('streamed shell removes immediately for reduced motion and bounds missing animation events',async()=>{
  const reduced=fixture({skeleton:true,reduced:true,pending:false});
  assert.equal(reduced.shell.removed,true);
  const timed=fixture({skeleton:true,pending:false});
  assert.equal(timed.shell.removed,undefined);
  for(const callback of [...timed.timers.values()]) callback();
  assert.equal(timed.shell.removed,true);
  assert.equal(timed.shell.listeners.get('animationend'),0);
});
test('a hero gate opening before the final stream marker cannot strand the shell',async()=>{
  const f=fixture({skeleton:true,complete:false,reduced:true});
  const owner=f.observers.find(o=>o.options.attributeFilter.includes('data-document-pending'));
  await f.load(f.preview);await f.frame();await f.frame();owner.callback();
  assert.equal(f.root.hasAttribute('data-document-pending'),false);
  assert.equal(f.shell.removed,undefined);
  f.completeMarker();
  assert.equal(f.shell.removed,true);assert.equal(owner.disconnected,true);
});
test('decoded SSR preview releases clock; final overlaps under the same native owner',async()=>{
  const f=fixture(); await f.load(f.preview); assert.equal(f.phase(),'waiting');
  await f.frame();await f.frame();assert.equal(f.root.hasAttribute('data-document-pending'),false);await f.frame();
  assert.equal(f.root.__progressiveImages.has(f.hero),true);
  await f.load(f.final);assert.equal(f.phase(),'upgrading');
  // Hydration does not acknowledge ownership until the transition is terminal.
  await f.frame();assert.equal(f.phase(),'upgrading');
  f.animations.resolve();await f.flush();assert.equal(f.phase(),'ready');
  f.hero.setAttribute('data-image-managed','true');f.observer.callback();
  assert.equal(f.root.__progressiveImages.has(f.hero),false);
  assert.equal(f.root.__progressiveImages.has(f.hero),false);assert.equal(f.observer.disconnected,true);
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
  assert.equal(f.root.__progressiveImages.has(f.hero),false);assert.equal(f.final.listeners.get('error'),0);
  const replaced=fixture();replaced.hero.removed=true;replaced.observer.callback();
  assert.equal(replaced.root.__progressiveImages.has(replaced.hero),false);
});
test('a late final upgrades after the bounded entrance gate, and BFCache settles',async()=>{
  const f=fixture();for(const callback of [...f.timers.values()])callback();
  await f.frame();await f.frame();assert.equal(f.root.hasAttribute('data-document-pending'),false);
  await f.load(f.preview);await f.frame();await f.load(f.final);assert.equal(f.phase(),'upgrading');
  const event=new Event('pageshow');event.persisted=true;f.window.dispatchEvent(event);
  assert.equal(f.phase(),'single');
});

test('same-source adoption does not release the sole pending final decode gate',async()=>{
  const f=fixture({same:true});assert.equal(f.phase(),'single');
  f.hero.setAttribute('data-image-managed','true');f.preview.removed=true;f.observer.callback();
  assert.equal(f.root.hasAttribute('data-document-pending'),true);
  assert.equal(f.root.__progressiveImages.has(f.hero),false);
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

test('unnoticeable opening scale bypasses blur/fade without a minimum preview duration',async()=>{
  const f=fixture({scale:0.6});await f.load(f.preview);await f.frame();await f.frame();await f.frame();
  await f.load(f.final);assert.equal(f.phase(),'ready');
  assert.equal(f.root.properties.get('--pi-_R_hero-motion'),'none');
});
test('native Back and departure during pending upgrade settle directly',async()=>{
  const back=fixture({direct:true});assert.equal(back.phase(),'single');
  await back.load(back.preview);await back.frame();await back.frame();await back.frame();
  await back.load(back.final);assert.equal(back.phase(),'single');
  const f=fixture();await f.load(f.preview);await f.frame();await f.frame();await f.frame();
  await f.load(f.final);assert.equal(f.phase(),'upgrading');
  f.window.dispatchEvent(new Event('pagehide'));assert.equal(f.phase(),'single');
  f.animations.resolve();await f.flush();assert.equal(f.phase(),'single');
  assert.equal(f.root.properties.get('--pi-_R_hero-motion'),'none');
});

test('repeated sources retain independent native staging and collision-free styles',async()=>{
  const f=fixture({duplicate:true});
  const first=f.root.__progressiveImages.get(f.hero),second=f.root.__progressiveImages.get(f.other.hero);
  assert.notEqual(first,second);
  await f.load(f.preview);await f.frame();await f.frame();await f.frame();await f.load(f.final);
  assert.equal(first.phase,'upgrading');assert.equal(second.phase,'waiting');
  assert.equal(f.root.properties.get('--pi-_R_hero-final'),'1');
  assert.equal(f.root.properties.get('--pi-_R_other-final'),'0');
  await f.load(f.other.final);assert.equal(second.phase,'ready');assert.equal(first.phase,'upgrading');
});

test('compiled bootstrap exposes the exact native component handoff contract',()=>{
  const f=fixture(),owner=f.root.__progressiveImages.get(f.hero);
  assert.equal(owner.root,f.root);assert.equal(owner.src,'/hero.jpg');assert.equal(owner.phase,'waiting');
  assert.equal(typeof owner.current,'function');assert.equal(owner.current(),true);
  assert.equal(typeof owner.previewPainted,'function');assert.equal(typeof owner.gateFinished,'function');
});


test('static SSR gradient opens the entrance before any image can decode',async()=>{
  const f=fixture({placeholder:true});
  assert.equal(f.preview.complete,false);assert.equal(f.final.complete,false);
  await f.frame();await f.frame();
  assert.equal(f.root.hasAttribute('data-document-pending'),false);
  assert.equal(f.phase(),'waiting');
  await f.load(f.preview);await f.frame();await f.load(f.final);
  assert.equal(f.phase(),'upgrading');
  assert.equal(f.root.properties.get('--pi-_R_hero-placeholder'),'none');
});
test('paired final-only gradient remains until the selected sharp image decodes',async()=>{
  const f=fixture({placeholder:true,previewOmitted:true,direct:true,managed:true});
  assert.equal(f.phase(),'single');
  assert.equal(f.preview.listeners.get('load'),undefined);
  await f.frame();await f.frame();
  assert.equal(f.root.hasAttribute('data-document-pending'),false);
  await f.load(f.final);
  assert.equal(f.phase(),'single');
  assert.equal(f.root.__progressiveImages.get(f.hero).finalReady,true);
  assert.equal(f.root.properties.get('--pi-_R_hero-placeholder'),'none');
});

test('committed paired adoption releases its already-managed native owner',async()=>{
 const f=fixture({placeholder:true,previewOmitted:true,direct:true,managed:true});
 await f.frame();await f.frame();await f.load(f.final);
 const owner=f.root.__progressiveImages.get(f.hero);
 assert.equal(owner.phase,'single');assert.equal(owner.finalReady,true);
 owner.handoff();
 assert.equal(f.root.__progressiveImages.has(f.hero),false);
 assert.equal(f.final.listeners.get('load'),0);assert.equal(f.observer.disconnected,true);
});

test('a static single native image keeps its identity and clears the gradient on decode',async()=>{
 const f=fixture({placeholder:true,managed:true,nativeImage:true});
 assert.equal(f.hero,f.final);assert.equal(f.phase(),'single');
 assert.equal(f.preview.listeners.get('load'),undefined);
 await f.frame();await f.frame();
 assert.equal(f.root.hasAttribute('data-document-pending'),false);
 await f.load(f.final);
 assert.equal(f.root.properties.get('--pi-_R_hero-placeholder'),'none');
 f.root.__progressiveImages.get(f.final).handoff();
 assert.equal(f.root.__progressiveImages.has(f.final),false);
 assert.equal(f.final.listeners.get('load'),0);
});
