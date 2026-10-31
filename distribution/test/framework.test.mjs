import test from 'node:test';import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {mkdtempSync,readFileSync,readdirSync,rmSync} from 'node:fs';
import {DatabaseSync} from 'node:sqlite';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import worker from '../worker.mjs';
import {FrameworkAuthority,serveFramework,withIncremental} from '../framework.mjs';
const hash=x=>createHash('sha256').update(x).digest('hex');
let produced;
/** Real output of the Python producer, signed with a disposable key. */
function producerOutput(){
 if(produced)return structuredClone(produced);
 const directory=mkdtempSync(join(tmpdir(),'framework-api-'));
 try{
  execFileSync('python3',['-c','import sys; from pathlib import Path; from scripts.test.test_release_tooling import write_framework_java_fixture; write_framework_java_fixture(Path(sys.argv[1]))',directory],{cwd:new URL('../../',import.meta.url),stdio:'pipe'});
  const prefix='turboism-0.68.0',candidate=JSON.parse(readFileSync(join(directory,prefix+'-incremental.json'))),source=new Map();
  for(const [name,ref] of [['-manifest.json',candidate.manifest],['-manifest.json.sig',candidate.envelope]])source.set('documents/'+ref.sha256,readFileSync(join(directory,prefix+name)));
  for(const d of readdirSync(join(directory,'objects')))source.set(`objects/${d}/payload`,readFileSync(join(directory,'objects',d,'payload')));
  produced={candidate,source,keys:JSON.parse(readFileSync(join(directory,'public-keys.json')))};
 }finally{rmSync(directory,{recursive:true,force:true});}
 return structuredClone(produced);
}
const toBytes=async body=>body instanceof Uint8Array?body:new Uint8Array(await new Response(body).arrayBuffer());
function bucket(){
 const objects=new Map();
 return {objects,
  async put(key,body,options={}){
   if(options.onlyIf?.etagDoesNotMatch==='*'&&objects.has(key))return null;
   const bytes=await toBytes(typeof body==='string'?new TextEncoder().encode(body):body),digest=hash(bytes);
   if(options.sha256&&options.sha256!==digest)throw new Error('R2 digest mismatch');
   objects.set(key,{bytes,checksums:{sha256:Uint8Array.from(Buffer.from(digest,'hex')).buffer}});return {key};
  },
  async head(key){const o=objects.get(key);return o&&{size:o.bytes.length,checksums:o.checksums};},
  async get(key,options){
   const o=objects.get(key);if(!o)return null;const r=options?.range,bytes=r?o.bytes.slice(r.offset,r.offset+r.length):o.bytes;
   return {size:o.bytes.length,body:new Response(bytes).body,arrayBuffer:async()=>bytes.slice().buffer};
  },
 };
}
function durableObject(env){
 const db=new DatabaseSync(':memory:'),storage={sql:{exec(query,...args){const rows=db.prepare(query).all(...args);return {toArray:()=>rows};}},
  transactionSync(fn){db.exec('SAVEPOINT t');try{const r=fn();db.exec('RELEASE t');return r;}catch(e){db.exec('ROLLBACK TO t');db.exec('RELEASE t');throw e;}}};
 const instance=new FrameworkAuthority({storage,blockConcurrencyWhile:fn=>fn()},env);
 return {idFromName:()=>'turboism',get:()=>({fetch:request=>instance.fetch(request)})};
}
function setup(){
 const {candidate,source,keys}=producerOutput(),r2=bucket();
 for(const [key,bytes] of source)r2.objects.set('framework-incoming/'+key,{bytes:new Uint8Array(bytes)});
 const env={DOWNLOADS:r2,FRAMEWORK_TRUSTED_KEYS:JSON.stringify(keys),FRAMEWORK_PUBLISHER_WORKFLOW:'framework-publish.yml'};
 env.FRAMEWORK=durableObject(env);
 const admit=(extra={})=>env.FRAMEWORK.get().fetch(new Request('https://framework.internal/admit',{method:'POST',body:JSON.stringify({candidate,expected:{identity:candidate.identity,keyId:'offline-test'},initialize:true,...extra})}));
 const get=(path,headers={},method='GET')=>serveFramework(new Request('https://api.turboism.dev'+path,{method,headers}),env);
 const release={schemaVersion:1,channel:candidate.identity.channel,status:'ready',release:{version:candidate.identity.version,buildNumber:candidate.identity.buildNumber,sourceRevision:candidate.identity.sourceRevision}};
 return {candidate,source,r2,env,admit,get,release};
}
const firstObject=x=>[...x.source.keys()].find(k=>k.startsWith('objects/'));
test('admission publishes verified resources that the public API serves byte-for-byte',async()=>{
 const x=setup();
 assert.equal((await x.get('/v1/framework/documents/'+x.candidate.manifest.sha256)).status,404,'nothing is public before admission');
 const result=await x.admit();assert.equal(result.status,200);assert.equal((await result.json()).id,x.candidate.manifest.sha256);
 for(const key of x.source.keys()){
  const response=await x.get('/v1/framework/'+key),expected=x.source.get(key);
  assert.equal(response.status,200,key);assert.equal(response.headers.get('Content-Length'),String(expected.length));assert.equal(response.headers.get('ETag'),`"${hash(expected)}"`);
  assert.equal(response.headers.get('Content-Encoding'),null);assert.equal(response.headers.get('Content-Type'),'application/octet-stream');assert.match(response.headers.get('Cache-Control'),/no-transform/);
  assert.deepEqual(Buffer.from(await response.arrayBuffer()),Buffer.from(expected));
 }
});
test('resources support HEAD, conditional requests, Range and If-Range without trusting stale validators',async()=>{
 const x=setup();await x.admit();const key=firstObject(x),path='/v1/framework/'+key,bytes=x.source.get(key),etag=`"${hash(bytes)}"`;
 const head=await x.get(path,{},'HEAD');assert.equal(head.status,200);assert.equal(head.headers.get('Content-Length'),String(bytes.length));assert.equal(await head.text(),'');
 assert.equal((await x.get(path,{'If-None-Match':etag})).status,304);
 const range=await x.get(path,{Range:'bytes=1-3','If-Range':etag});assert.equal(range.status,206);assert.equal(range.headers.get('Content-Range'),`bytes 1-3/${bytes.length}`);assert.deepEqual(Buffer.from(await range.arrayBuffer()),Buffer.from(bytes.slice(1,4)));
 const stale=await x.get(path,{Range:'bytes=1-3','If-Range':'"other"'});assert.equal(stale.status,200);assert.equal((await stale.arrayBuffer()).byteLength,bytes.length);
 const bad=await x.get(path,{Range:`bytes=${bytes.length}-`});assert.equal(bad.status,416);assert.equal(bad.headers.get('Content-Range'),`bytes */${bytes.length}`);
});
test('only registered resources are public: staged and unknown keys, errors never look like success',async()=>{
 const x=setup();await x.admit();
 assert.equal((await x.get('/v1/framework/objects/'+'0'.repeat(64)+'/payload')).status,404);
 x.r2.objects.set('framework-incoming/documents/'+'1'.repeat(64),{bytes:new Uint8Array([1])});
 const unknown=await x.get('/v1/framework/documents/'+'1'.repeat(64));assert.equal(unknown.status,404);assert.match(unknown.headers.get('Content-Type'),/json/);assert.equal(unknown.headers.get('Cache-Control'),'no-store');
 const key=firstObject(x);x.r2.objects.get('framework/'+key).bytes=new Uint8Array([1,2,3]); // storage corruption must not be served
 assert.equal((await x.get('/v1/framework/'+key)).status,503);
});
test('tampered staging, wrong keys and bad identity are rejected before anything is registered',async()=>{
 const tamper=setup();tamper.r2.objects.set('framework-incoming/'+firstObject(tamper),{bytes:new Uint8Array([9,9,9])});
 const rejected=await tamper.admit();assert.equal(rejected.status,422);
 assert.equal([...tamper.r2.objects.keys()].filter(k=>k.startsWith('framework/')).length,0);
 assert.equal((await tamper.get('/v1/framework/documents/'+tamper.candidate.manifest.sha256)).status,404);
 assert.equal((await withIncremental(tamper.release,tamper.env)).release.incremental,undefined);
 const missing=setup();missing.r2.objects.delete('framework-incoming/'+firstObject(missing));assert.equal((await missing.admit()).status,422);
 const key=setup();key.env.FRAMEWORK_TRUSTED_KEYS='{}';assert.equal((await key.admit()).status,503);
 const identity=setup();identity.candidate.identity.sourceRevision='f'.repeat(40);assert.equal((await identity.admit()).status,422);
 const implicit=setup();assert.equal((await implicit.admit({initialize:false})).status,422,'an empty channel needs explicit initialization');
});
test('release.incremental is projected only for an identical, admitted, unwithdrawn release',async()=>{
 const x=setup();assert.equal((await withIncremental(x.release,x.env)).release.incremental,undefined);
 const {id}=await (await x.admit()).json();
 const projected=await withIncremental(x.release,x.env);
 assert.deepEqual(projected.release.incremental,{source:{origin:'https://api.turboism.dev',redirects:'reject'},descriptor:x.candidate});
 assert.deepEqual({...projected.release,incremental:undefined},{...x.release.release,incremental:undefined},'existing release fields are preserved');
 for(const change of [{buildNumber:x.release.release.buildNumber+1},{sourceRevision:'e'.repeat(40)},{version:'9.9.9'}])
  assert.equal((await withIncremental({...x.release,release:{...x.release.release,...change}},x.env)).release.incremental,undefined);
 assert.equal((await withIncremental({...x.release,channel:'beta'},x.env)).release.incremental,undefined);
 assert.equal((await withIncremental({...x.release,release:{...x.release.release,buildNumber:null}},x.env)).release.incremental,undefined);
 const withdrawn=await x.env.FRAMEWORK.get().fetch(new Request('https://framework.internal/withdraw',{method:'POST',body:JSON.stringify({id})}));assert.equal((await withdrawn.json()).matched,true);
 assert.equal((await withIncremental(x.release,x.env)).release.incremental,undefined,'withdrawn release stops being recommended');
 assert.equal((await withIncremental(x.release,{...x.env,FRAMEWORK:{idFromName:x.env.FRAMEWORK.idFromName,get:()=>({fetch:async()=>{throw new Error('down');}})}})).release.incremental,undefined,'authority failure falls back to the full installer');
});
test('re-admitting the same release is idempotent and cannot replace published bytes',async()=>{
 const x=setup();await x.admit();const before=new Map([...x.r2.objects].filter(([k])=>k.startsWith('framework/')));
 assert.equal((await x.admit({initialize:false})).status,200);
 for(const [k,v] of before)assert.equal(x.r2.objects.get(k).bytes,v.bytes);
 const other=setup();other.candidate.identity.buildNumber+=1;assert.equal((await other.admit()).status,422);
});
test('publisher endpoints stay closed without the dedicated workflow and a valid GitHub identity',async()=>{
 const x=setup();const call=(env,path='/v1/framework/admit',headers={})=>worker.fetch(new Request('https://api.turboism.dev'+path,{method:'POST',headers,body:'{}'}),env);
 assert.equal((await call({...x.env,FRAMEWORK_PUBLISHER_WORKFLOW:''})).status,503);
 assert.equal((await call({...x.env,FRAMEWORK_PUBLISHER_WORKFLOW:'notify-release-api.yml'})).status,503);
 assert.equal((await call(x.env)).status,401);assert.equal((await call(x.env,'/v1/framework/withdraw',{Authorization:'Bearer a.b.c'})).status,401);
 const served=await worker.fetch(new Request('https://api.turboism.dev/v1/framework/documents/'+x.candidate.manifest.sha256),x.env);assert.equal(served.status,404);
 assert.equal((await worker.fetch(new Request('https://api.turboism.dev/v1/framework/documents/xyz'),x.env)).status,404);
});
import {verifyPublic} from '../publish-framework.mjs';
test('public preflight proves exact bytes through the Worker and rejects redirects or altered content',async()=>{
 const x=setup();await x.admit();const expected=new Map(x.source);
 const viaWorker=(url,init)=>worker.fetch(new Request(url,init),x.env);
 assert.deepEqual(await verifyPublic(x.candidate,expected,viaWorker),x.candidate.identity);
 const key=firstObject(x),changed=new Map(expected);changed.set(key,Buffer.from('other'));
 await assert.rejects(()=>verifyPublic(x.candidate,changed,viaWorker),/content mismatch/);
 await assert.rejects(()=>verifyPublic(x.candidate,expected,async()=>new Response(null,{status:302,headers:{Location:'https://example.invalid'}})),/HTTP 302/);
});
