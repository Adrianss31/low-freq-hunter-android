const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const M=require('../app/src/main/assets/dashboard-model.js');
function renderer(reduced){
 let change;const query={matches:reduced,addEventListener(_,fn){change=fn}};
 const ctx={LFHModel:M,window:{},document:{},matchMedia:()=>query,performance:{now:()=>100},requestAnimationFrame(){}};
 vm.runInNewContext(fs.readFileSync(require.resolve('../app/src/main/assets/dashboard-render.js'),'utf8'),ctx);
 const r=new ctx.window.LFHRenderer({night:{from:0,to:3600}},{onView(){}});
 // Bitmap generation is tested visually in the browser; these tests isolate motion policy.
 r.prepare=()=>{};r.view={t0:0,t1:3600,f0:20,f1:200};r.target={...r.view};
 return {r,change:()=>change({matches:true})};
}
test('reduced motion snaps directly to the requested view without a tween',()=>{
 const {r}=renderer(true);r.setView({t0:100,t1:500,f0:20,f1:80},440);
 assert.equal(r.view.t0,100);assert.equal(r.view.t1,500);assert.equal(r.tween,null);
});
test('turning reduced motion on finishes zoom and removes wipe, flash and fade',()=>{
 const {r,change}=renderer(false);r.setView({t0:100,t1:500,f0:20,f1:80},440);
 assert.ok(r.tween);r.wipe={};r.flash={};r.fade={};change();
 assert.equal(r.view.t0,100);assert.equal(r.tween,null);assert.equal(r.wipe,null);assert.equal(r.flash,null);assert.equal(r.fade,null);
});
