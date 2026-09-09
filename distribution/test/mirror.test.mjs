import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {parseRelease} from '../protocol.mjs';
import {fixture} from './fixture.mjs';
import {mirrorRelease, mirrorFingerprint} from '../mirror.mjs';
const hash=x=>createHash('sha256').update(x).digest('hex');
function setup() {
 const raw=fixture(), bytes=new Map();
 for(const a of raw.assets.filter(a=>!a.name.endsWith('.sha256'))){const b=Buffer.from('reviewed payload '+a.name);a.size=b.length;a.digest='sha256:'+hash(b);bytes.set(a.name,b);}
 for(const a of raw.assets.filter(a=>a.name.endsWith('.sha256'))){const name=a.name.slice(0,-7),b=Buffer.from(hash(bytes.get(name))+'  '+name+'\n');a.size=b.length;a.digest='sha256:'+hash(b);bytes.set(a.name,b);}
 const release=parseRelease(raw,'b'.repeat(40)),store=new Map(),events=[];
 const meta=(key,x)=>({key,size:x.bytes.length,etag:'stored-'+hash(x.bytes),checksums:{sha256:Uint8Array.from(Buffer.from(hash(x.bytes),'hex')).buffer}});
 const bucket={
  async head(key){events.push('head '+key);const x=store.get(key);return x?meta(key,x):null;},
  async get(key){const x=store.get(key);return x?{...meta(key,x),body:new Response(x.bytes).body,json:async()=>JSON.parse(x.bytes),text:async()=>x.bytes.toString()}:null;},
  async put(key,body,options={}){events.push('put '+key);assert.equal(options.onlyIf?.etagDoesNotMatch,'*');if(store.has(key))return null;const b=Buffer.from(await new Response(body).arrayBuffer());if(options.sha256)assert.equal(hash(b),options.sha256);const x={bytes:b};store.set(key,x);return meta(key,x);}
 };
 const fetcher=async(input)=>{const url=new URL(input);assert.equal(url.origin,'https://github.com');const b=bytes.get(url.pathname.split('/').at(-1));assert.ok(b);events.push('fetch '+url.pathname);return new Response(b,{headers:{'Content-Length':String(b.length),'Content-Type':'application/octet-stream'}});};
 return{raw,release,bytes,store,events,bucket,fetcher};
}
test('mirrors all eight original files with R2-enforced SHA-256 before advertising official sources',async()=>{
 const x=setup();const r=await mirrorRelease(x.release,x.bucket,x.fetcher);
 assert.equal(x.store.size,9);assert.equal(x.events.filter(e=>e.startsWith('fetch ')).length,8);
 assert.ok(x.events.at(-1).includes('mirrors/v1.2.3.json'));
 for(const a of r.assets){assert.equal(a.sources[0].id,'official');assert.match(a.sources[0].url,/^https:\/\/api.turboism.dev\/files\//);}
 x.events.length=0;await mirrorRelease(x.release,x.bucket,x.fetcher);assert.ok(!x.events.some(e=>e.startsWith('fetch ')||e.startsWith('put ')));
});
test('same-version changed bytes cannot replace the immutable mirror marker',async()=>{
 const x=setup();await mirrorRelease(x.release,x.bucket,x.fetcher);const changed=structuredClone(x.release);changed.sourceRevision='c'.repeat(40);
 await assert.rejects(()=>mirrorRelease(changed,x.bucket,x.fetcher),/conflict/i);
});
test('bad sidecar or upload failure never publishes the completion marker',async()=>{
 const x=setup();const side=x.release.assets[0].checksum;const bad=Buffer.from('not the reviewed checksum');x.bytes.set(side.name,bad);side.size=bad.length;side.sha256=hash(bad);side.key=`files/${side.sha256}/${side.name}`;
 await assert.rejects(()=>mirrorRelease(x.release,x.bucket,x.fetcher),/sidecar/i);assert.ok(!x.store.has('mirrors/v1.2.3.json'));
 const y=setup();y.bucket.put=async()=>{throw new Error('storage offline');};await assert.rejects(()=>mirrorRelease(y.release,y.bucket,y.fetcher),/offline/);assert.equal(y.store.size,0);
});
test('existing size-only objects are not trusted and unsupported redirect origins are rejected',async()=>{
 const x=setup();const asset=x.release.assets[0];x.bucket.head=async()=>({size:asset.size});await assert.rejects(()=>mirrorRelease(x.release,x.bucket,x.fetcher),/integrity/i);
 const y=setup();await assert.rejects(()=>mirrorRelease(y.release,y.bucket,async()=>new Response(null,{status:302,headers:{Location:'https://untrusted.invalid/file'}})),/origin/i);
});
test('missing R2 binding preserves honest GitHub-only sources',async()=>{
 const x=setup();assert.equal(await mirrorRelease(x.release,null,x.fetcher),x.release);assert.equal(x.events.length,0);assert.equal(typeof mirrorFingerprint(x.release),'string');
});
