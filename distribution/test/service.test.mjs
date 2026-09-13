import test from 'node:test';import assert from 'node:assert/strict';
import { synchronize } from '../github.mjs';import { fixture } from './fixture.mjs';
import { documentFor,jsonResponse } from '../protocol.mjs';
import { parseRange } from '../files.mjs';
const reply=v=>new Response(JSON.stringify(v),{headers:{'Content-Type':'application/json'}});
function source(releases){return async url=>{const p=new URL(url).pathname;if(p.endsWith('/releases'))return reply(releases);if(p.includes('/git/ref/tags/'))return reply({object:{type:'commit',sha:'b'.repeat(40)}});throw new Error('unexpected network request '+p);};}
test('sync ignores drafts, resolves commit, no legacy requests, no fabricated absent channels',async()=>{
 const s=await synchronize(null,{},source([fixture(),{...fixture('1.3.0'),draft:true}]));
 assert.equal(s.releases.length,1);assert.equal(documentFor(s,'stable').release.version,'1.2.3');assert.equal(documentFor(s,'beta').status,'not_published');
});
test('transient per-channel GitHub failure keeps the last verified active release available',async()=>{
 const old=await synchronize(null,{},source([fixture()]));
 const flaky=async url=>{const p=new URL(url).pathname;if(p.endsWith('/releases'))return reply([fixture()]);if(p.includes('/git/ref/tags/'))return new Response('rate limited',{status:403});throw new Error('unexpected network request '+p);};
 const next=await synchronize(old,{},flaky);
 assert.match(next.errors.stable,/GitHub HTTP 403/);
 assert.equal(documentFor(next,'stable').status,'ready');
 assert.equal(documentFor(next,'stable').release.version,'1.2.3');
});
test('GitHub failure cannot silently become unpublished; same-version changed bytes fail',async()=>{
 await assert.rejects(()=>synchronize(null,{},async()=>new Response('rate limited',{status:403})));
 const old=await synchronize(null,{},source([fixture()]));const raw=fixture();raw.assets[0].digest='sha256:'+'c'.repeat(64);
 const next=await synchronize(old,{},source([raw]));assert.equal(documentFor(next,'stable').status,'unavailable');
});
test('withdrawn release is no longer advertised; no production data is deleted',async()=>{
 const old=await synchronize(null,{},source([fixture()]));const next=await synchronize(old,{},source([]));assert.equal(documentFor(next,'stable').status,'not_published');assert.equal(next.known['v1.2.3'].active,false);
});
test('GET conditional responses and HEAD work; sync error never sends 304',async()=>{
 const s=await synchronize(null,{},source([fixture()]));const r=await jsonResponse(new Request('https://api.turboism.dev/v1/releases/stable.json'),documentFor(s,'stable'));
 const h={'If-None-Match':r.headers.get('ETag')};const hit=await jsonResponse(new Request('https://api.turboism.dev/v1/releases/stable.json',{headers:h}),documentFor(s,'stable'));assert.equal(hit.status,304);
 const head=await jsonResponse(new Request('https://api.turboism.dev/v1/releases/stable.json',{method:'HEAD'}),documentFor(s,'stable'));assert.equal(await head.text(),'');
 const err=await jsonResponse(new Request('https://api.turboism.dev/v1/releases/stable.json',{headers:h}),documentFor({...s,error:'offline'},'stable'),503);assert.equal(err.status,503);assert.equal(err.headers.get('Cache-Control'),'no-store');
});
test('single HTTP ranges support offsets and suffixes, reject multipart and unsatisfiable offsets',()=>{
 assert.deepEqual(parseRange('bytes=10-19',100),{offset:10,length:10});assert.deepEqual(parseRange('bytes=-10',100),{offset:90,length:10});assert.deepEqual(parseRange('bytes=10-',100),{offset:10,length:90});assert.equal(parseRange('bytes=100-',100),false);assert.equal(parseRange('bytes=0-1,3-4',100),false);
});
test('Workers-compatible metadata fetch uses manual redirects and rejects redirects rather than following them',async()=>{
 let observed;
 await assert.rejects(()=>synchronize(null,{},async(_url,init)=>{observed=init.redirect;return new Response(null,{status:302,headers:{Location:'https://untrusted.invalid'}});}),/302/);
 assert.equal(observed,'manual');
});
