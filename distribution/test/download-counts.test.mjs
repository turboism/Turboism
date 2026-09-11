import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { parseRelease } from '../protocol.mjs';
import { fixture } from './fixture.mjs';
import { synchronize } from '../github.mjs';
import { ReleaseRegistry } from '../worker.mjs';
const source = 'b'.repeat(40);
const baseTime = Date.parse('2026-09-11T00:00:00.000Z');
async function implementation() {
  const url = new URL('../download-counts.mjs', import.meta.url);
  assert.equal(existsSync(url), true, 'Persistent download analytics must exist');
  return import(url.href);
}
function storage() {
  const db = new DatabaseSync(':memory:');
  return {db, sql: {exec(query, ...args) {return {toArray:()=>db.prepare(query).all(...args)};}},
    transactionSync(fn) {db.exec('SAVEPOINT test');try {const r=fn();db.exec('RELEASE test');return r;}catch(e){db.exec('ROLLBACK TO test');db.exec('RELEASE test');throw e;}}};
}
// Cloudflare SQL executes eagerly; emulate that property without mocking analytics behavior.
function realStorage() {
 const s=storage();s.sql.exec=(query,...args)=>{const statement=s.db.prepare(query);const rows=statement.all(...args);return{toArray:()=>rows};};return s;
}
function request(headers={}, method='GET') { return new Request('https://api.turboism.dev/files/hash/TurboismInstaller-1.2.3.exe',{method,headers:{'CF-Connecting-IP':'192.0.2.10','User-Agent':'Test browser',...headers}}); }
const response=(status=200,start=0)=>new Response(status===304?null:'bytes',{status,headers:status===206?{'Content-Range':`bytes ${start}-${start+3}/100`}:{}});
const binary={name:'TurboismInstaller-1.2.3.exe',key:'files/'+'a'.repeat(64)+'/TurboismInstaller-1.2.3.exe'};

test('GitHub analytics are nullable and do not weaken published asset validation',()=>{
 const raw=fixture();for(const a of raw.assets)a.download_count=a.name.endsWith('.sha256')?1000:10;
 const release=parseRelease(raw,source);
 assert.equal(release.assets[0].downloadCount,10);
 raw.assets[0].download_count=-1;
 assert.equal(parseRelease(raw,source).assets[0].downloadCount,null);
});
test('official starts persist; full/retry/range starts deduplicate without retaining raw IP',async()=>{
 const {DownloadCounter}=await implementation(),s=realStorage();let time=baseTime;
 const c=new DownloadCounter(s,()=>time);c.initialize();
 assert.equal(await c.record(request(),response(),binary),true);
 assert.equal(await c.record(request(),response(),binary),false);
 assert.equal(await c.record(request({Range:'bytes=0-3'}),response(206),binary),false);
 assert.equal(await c.record(request({Range:'bytes=4-7'}),response(206,4),binary),false);
 assert.equal(await c.record(request({'CF-Connecting-IP':'192.0.2.11'}),response(),binary),true);
 assert.equal(c.read([binary]).count,2);
 const restored=new DownloadCounter(s,()=>time);restored.initialize();assert.equal(restored.read([binary]).count,2);
 assert.equal(restored.read([binary]).since,new Date(baseTime).toISOString());
 assert.doesNotMatch(JSON.stringify(s.sql.exec('SELECT * FROM download_seen').toArray()),/192\.0\.2|Test browser/);
 time+=1800001;assert.equal(await restored.record(request(),response(),binary),true);assert.equal(restored.read([binary]).count,3);
});
test('HEAD, sidecars, nonzero resumes, failures, validation/prefetch requests do not count',async()=>{
 const {DownloadCounter}=await implementation(),s=realStorage(),c=new DownloadCounter(s,()=>baseTime);c.initialize();
 for(const [req,res,asset] of [[request({},'HEAD'),response(),binary],[request(),response(),{...binary,name:binary.name+'.sha256'}],[request(),response(304),binary],[request(),response(503),binary],[request(),response(416),binary],[request(),response(206,5),binary],[request({'X-Turboism-Download-Purpose':'verification'}),response(),binary],[request({'Sec-Purpose':'prefetch'}),response(),binary]])assert.equal(await c.record(req,res,asset),false);
 assert.equal(c.read([binary]).count,0);
 assert.equal(c.read([binary]).since,new Date(baseTime).toISOString());
});
test('source breakdown excludes checksum downloads, retains unknowns, and is version scoped',async()=>{
 const {countsDocument,DownloadCounter}=await implementation(),s=realStorage(),c=new DownloadCounter(s,()=>baseTime);c.initialize();
 const raw=fixture();raw.assets.forEach(a=>a.download_count=a.name.endsWith('.sha256')?10000:7);const r=parseRelease(raw,source);
 const record={active:true,countsAsOf:new Date(baseTime).toISOString(),release:r};
 const d=countsDocument('1.2.3',record,c.read(r.assets));assert.equal(d.github.count,28);assert.equal(d.official.count,0);assert.equal(d.total,28);assert.equal(d.status,'ready');
 r.assets[0].downloadCount=null;const partial=countsDocument('1.2.3',record,c.read(r.assets));assert.equal(partial.total,null);assert.equal(partial.github.count,null);assert.equal(partial.status,'partial');
 assert.equal(countsDocument('1.2.3',record,null).official.count,null);
 assert.throws(()=>countsDocument('1.2.4',record,c.read(r.assets)));
});
test('refresh snapshots update analytics without treating count changes as immutable binary changes',async()=>{
 const raw=fixture();raw.assets.forEach(a=>a.download_count=10);
 const fetcher=async url=>Response.json(String(url).includes('/releases?')?[raw]:{object:{type:'commit',sha:source}});
 const first=await synchronize(null,{},fetcher);raw.assets.forEach(a=>a.download_count++);
 const next=await synchronize(first,{},fetcher);assert.equal(next.releases.length,1);
 assert.equal(next.known['v1.2.3'].release.assets[0].downloadCount,11);assert.ok(next.known['v1.2.3'].countsAsOf);
});
test('registry stats read uses the actual SQLite store and analytics writes cannot interrupt files',async()=>{
 const s=realStorage(),ctx={storage:s,blockConcurrencyWhile:fn=>fn()};
 const r=parseRelease(fixture(),source),asset=r.assets[0];
 const bucket={head:async()=>({size:asset.size,checksums:{sha256:Uint8Array.from(asset.sha256.match(/../g),h=>parseInt(h,16))}}),get:async()=>({size:asset.size,body:new Uint8Array(asset.size)})};
 const registry=new ReleaseRegistry(ctx,{DOWNLOADS:bucket});await registry.ready;
 registry.snapshot={syncedAt:new Date().toISOString(),errors:{},releases:[r],known:{[r.tag]:{active:true,countsAsOf:new Date().toISOString(),release:r}}};
 const stats=await registry.fetch(new Request('https://api.turboism.dev/v1/downloads/1.2.3.json'));
 assert.equal(stats.status,200);assert.equal((await stats.json()).official.count,0);
 const missing=await registry.fetch(new Request('https://api.turboism.dev/v1/downloads/1.2.4.json'));assert.equal(missing.status,404);
 registry.counts.record=async()=>{throw new Error('storage unavailable');};
 // The exact mirror metadata requirements are exercised by the existing file-serving tests.
 const file=await registry.fetch(new Request('https://api.turboism.dev/'+asset.key,{headers:{'CF-Connecting-IP':'192.0.2.10'}}));
 assert.equal(file.status,200);assert.equal(file.headers.get('Cache-Control'),'private, no-store');
 assert.equal((await file.arrayBuffer()).byteLength,asset.size);
});
test('concurrent duplicate starts increment once and expired fingerprints are pruned',async()=>{
 const {DownloadCounter}=await implementation(),s=realStorage();let time=baseTime;const c=new DownloadCounter(s,()=>time);c.initialize();
 await Promise.all(Array.from({length:20},()=>c.record(request(),response(),binary)));
 assert.equal(c.read([binary]).count,1);assert.equal(s.sql.exec('SELECT COUNT(*) AS n FROM download_seen').toArray()[0].n,1);
 time+=1800001;c.prune();assert.equal(s.sql.exec('SELECT COUNT(*) AS n FROM download_seen').toArray()[0].n,0);assert.equal(c.read([binary]).count,1);
});
