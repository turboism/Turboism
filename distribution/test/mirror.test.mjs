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
test('mirrors both packages and their checksum files with R2-enforced SHA-256 before advertising official sources',async()=>{
 const x=setup();const r=await mirrorRelease(x.release,x.bucket,x.fetcher);
 assert.equal(x.store.size,5);assert.equal(x.events.filter(e=>e.startsWith('fetch ')).length,4);
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
test('corrupted mirror marker baseline is rejected fail-closed',async()=>{
 const x=setup();await mirrorRelease(x.release,x.bucket,x.fetcher);
 const marker=JSON.parse(x.store.get('mirrors/v1.2.3.json').bytes.toString());
 x.store.set('mirrors/v1.2.3.json',{bytes:Buffer.from(JSON.stringify({...marker,baseline:{[x.release.assets[0].name]:-1}}))});
 await assert.rejects(()=>mirrorRelease(x.release,x.bucket,x.fetcher),/integrity/i);
});

// The offline incremental path is intentionally separate from deployed mirrorRelease.
import {admitIncremental,verifyIncremental,verifyHistoricalPromotion,canonical} from '../incremental.mjs';
import {generateKeyPairSync,sign} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {mkdtempSync,readFileSync,readdirSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
const enc=x=>Buffer.from(canonical(x));
let producerFixture;
function incrementalSetup(oldIdentity={version:"0.67.0",channel:"stable",buildNumber:49,sourceRevision:"b".repeat(40)}){
 if(!producerFixture){
  const directory=mkdtempSync(join(tmpdir(),'incremental-admission-'));
  try{
   execFileSync('python3',['-c','import sys; from pathlib import Path; from scripts.test.test_release_tooling import write_framework_java_fixture; write_framework_java_fixture(Path(sys.argv[1]))',directory],{cwd:new URL('../../',import.meta.url),stdio:'pipe'});
   const prefix='turboism-0.68.0',candidate=JSON.parse(readFileSync(join(directory,prefix+'-incremental.json')));
   const source=new Map();
   for(const [name,ref] of [['-manifest.json',candidate.manifest],['-manifest.json.sig',candidate.envelope]])source.set('documents/'+ref.sha256,readFileSync(join(directory,prefix+name)));
   for(const d of readdirSync(join(directory,'objects')))source.set(`objects/${d}/payload`,readFileSync(join(directory,'objects',d,'payload')));
   const keys=Object.fromEntries(Object.entries(JSON.parse(readFileSync(join(directory,'public-keys.json')))).map(([k,v])=>[k,Buffer.from(v,'base64')]));
   producerFixture={candidate,source,keys};
  }finally{rmSync(directory,{recursive:true,force:true});}
 }
 const {candidate,source,keys}=structuredClone(producerFixture),objects=new Map(),channels=new Map(),events=[];
 const old=structuredClone(candidate),manifest=JSON.parse(Buffer.from(source.get('documents/'+candidate.manifest.sha256)));
 for(const entry of manifest.entries)entry.relativePath=entry.relativePath.replace(candidate.identity.version,oldIdentity.version);
 Object.assign(manifest,oldIdentity,{keyId:'historical-test'});old.identity=structuredClone(oldIdentity);
 const oldPair=generateKeyPairSync('ed25519'),oldManifest=enc(manifest);
 keys['historical-test']=oldPair.publicKey.export({format:'der',type:'spki'});
 const oldEnvelope=enc({format:'turboism-update-manifest-sig-v1',schemaVersion:1,algorithm:'Ed25519',keyId:'historical-test',manifestSha256:hash(oldManifest),signature:sign(null,oldManifest,oldPair.privateKey).toString('base64')});
 old.manifest={bytes:oldManifest.length,sha256:hash(oldManifest)};old.envelope={bytes:oldEnvelope.length,sha256:hash(oldEnvelope)};
 const oldId=old.manifest.sha256,oldRecord=enc({format:'turboism.incremental-release',schemaVersion:1,id:oldId,candidate:old});
 for(const [k,b] of source)if(k.startsWith('objects/'))objects.set(k,b.slice());
 objects.set('documents/'+old.manifest.sha256,oldManifest);objects.set('documents/'+old.envelope.sha256,oldEnvelope);
 objects.set('releases/'+oldId,oldRecord);objects.set('versions/'+oldIdentity.version,oldRecord);objects.set('builds/'+oldIdentity.buildNumber,oldRecord);
 for(const [k,b] of objects)objects.set(k,new Uint8Array(b));
 channels.set(oldIdentity.channel,oldId);
 const store={
  async read(key,max){const b=objects.get(key);assert.ok(!b||b.length<=max);return b?.slice();},
  async createImmutable(key,bytes){events.push(key);if(!objects.has(key))objects.set(key,bytes.slice());},
  async readChannel(channel){return channels.get(channel)??null;},
  async compareAndSwapChannel(channel,previous,next){events.push('channel');if((channels.get(channel)??null)!==previous)return false;channels.set(channel,next);return true;}
 };
 const expected={identity:structuredClone(candidate.identity),keyId:'offline-test'};
 const read=async(key,max)=>{const b=source.get(key);assert.ok(!b||b.length<=max);return b?.slice();};
 const run=(options={previous:oldId})=>admitIncremental(candidate,expected,keys,read,store,options);
 return {candidate,source,keys,objects,channels,events,store,expected,read,run,oldId,old,oldRecord};
}
function resign(x,modify){
 const m=JSON.parse(Buffer.from(x.source.get('documents/'+x.candidate.manifest.sha256)));modify(m);
 const manifest=enc(m),pair=x.signingPair??generateKeyPairSync('ed25519');x.signingPair=pair;
 x.keys['offline-test']=pair.publicKey.export({format:'der',type:'spki'});
 const envelope=enc({format:'turboism-update-manifest-sig-v1',schemaVersion:1,algorithm:'Ed25519',keyId:'offline-test',manifestSha256:hash(manifest),signature:sign(null,manifest,pair.privateKey).toString('base64')});
 for(const [name,bytes] of [['manifest',manifest],['envelope',envelope]]){
  x.candidate[name]={bytes:bytes.length,sha256:hash(bytes)};x.source.set('documents/'+hash(bytes),bytes);
 }
}
test('incremental admits REAL Python output only after content readback; retries reuse immutable bytes',async()=>{
 const x=incrementalSetup();const r=await x.run();
 assert.equal(x.channels.get('stable'),r.id);assert.equal(x.events.at(-1),'channel');
 const documents=x.events.findIndex(k=>k.startsWith('documents/'));
 assert.ok(documents>0&&x.events.slice(0,documents).every(k=>k.startsWith('objects/')));
 assert.ok(x.events.at(-2).startsWith('releases/'));
 const before=structuredClone(x.objects);await x.run();assert.deepEqual(x.objects,before);
});
test('incremental rejects missing/tampered bytes, bad signatures, expected identity or key mismatch without switching',async()=>{
 for(const failure of ['missing','tamper','signature','identity','key','closure','legacy']){
  const x=incrementalSetup();const object=[...x.source.keys()].find(k=>k.startsWith('objects/'));
  if(failure==='missing')x.source.delete(object);
  if(failure==='tamper')x.source.set(object,Buffer.from('changed'));
  if(failure==='signature'){
   const b=x.source.get('documents/'+x.candidate.envelope.sha256),e=JSON.parse(Buffer.from(b));e.signature=Buffer.alloc(64).toString('base64');const bad=enc(e);x.candidate.envelope={bytes:bad.length,sha256:hash(bad)};x.source.set('documents/'+hash(bad),bad);
  }
  if(failure==='identity')x.expected.identity.sourceRevision='f'.repeat(40);
  if(failure==='key')x.expected.keyId='unknown';
  if(failure==='closure')x.candidate.objectsSha256='a'.repeat(64);
  if(failure==='legacy')delete x.candidate.manifest;
  await assert.rejects(x.run,undefined,failure);assert.equal(x.channels.get('stable'),x.oldId);assert.ok(!x.objects.has('releases/'+x.candidate.manifest?.sha256));
 }
});
test('signed protocol boundary regressions fail closed across path, numbers, limits and ownership',async()=>{
 const mutations=[m=>m.entries[0].relativePath='graal/lib/foo..bar.jar',m=>m.entries[0].bytes=536870913,m=>m.entries[0].bytes=true,m=>m.buildNumber=9007199254740992,m=>m.schemaVersion=4294967297,m=>m.minimumUpdaterVersion='2147483648.0.0',m=>m.entries.push(m.entries[0]),m=>m.entries=Array(513).fill(m.entries[0]),m=>m.entries[0].relativePath='plugins/acp.jar',m=>m.unknown=true];
 for(const change of mutations){const x=incrementalSetup();resign(x,change);await assert.rejects(x.run);assert.equal(x.channels.get('stable'),x.oldId);}
});
test('interruption and failure at each immutable stage keep old channel; retry completes, no objects deleted',async()=>{
 const baseline=incrementalSetup();await baseline.run();const writes=baseline.events.filter(e=>e!=='channel').length;
 for(let stage=0;stage<writes;stage++){
  const x=incrementalSetup(),original=x.store.createImmutable;let n=0;
  x.store.createImmutable=async(...args)=>{if(n++===stage)throw new Error('interrupted');return original(...args);};
  await assert.rejects(x.run,/interrupted/);assert.equal(x.channels.get('stable'),x.oldId);
  const retained=[...x.objects.keys()];x.store.createImmutable=original;await x.run();assert.ok(retained.every(k=>x.objects.has(k)));
 }
 const x=incrementalSetup(),abort=new AbortController(),original=x.store.createImmutable;
 x.store.createImmutable=async(...args)=>{await original(...args);abort.abort();};
 await assert.rejects(()=>x.run({previous:x.oldId,signal:abort.signal}));assert.equal(x.channels.get('stable'),x.oldId);
});
test('existing corrupt objects, immutable identity conflict, CAS races and forged metadata cannot expose new release',async()=>{
 for(const kind of ['payload','manifest','release','version','build','cas','lying-store','mutating-store']){
  const x=incrementalSetup();const id=x.candidate.manifest.sha256;
  if(kind==='payload')x.objects.set([...x.source.keys()].find(k=>k.startsWith('objects/')),Buffer.from('corrupt'));
  if(kind==='manifest')x.objects.set('documents/'+id,Buffer.from('corrupt'));
  if(kind==='release')x.objects.set('releases/'+id,Buffer.from('different identity'));
  if(kind==='version')x.objects.set('versions/'+x.candidate.identity.version,Buffer.from('different release'));
  if(kind==='build')x.objects.set('builds/'+x.candidate.identity.buildNumber,Buffer.from('different release'));
  if(kind==='cas')x.store.compareAndSwapChannel=async()=>false;
  if(kind==='mutating-store')x.store.createImmutable=async(key,b)=>{b.fill(0);x.objects.set(key,b);};
  if(kind==='lying-store')x.store.createImmutable=async()=>({etag:'looks-good',sha256:'claimed'});
  await assert.rejects(x.run);assert.equal(x.channels.get('stable'),x.oldId);
 }
});
test('new candidates have no legacy flag fallback; historical promotion requires protected immutable evidence and real assets',async()=>{
 const x=incrementalSetup();x.candidate.legacy=true;await assert.rejects(x.run,/required/);
 const payload=Buffer.from('historical verified artifact');const record=enc({format:'turboism.historical-release',schemaVersion:1,repository:'turboism/Turboism',identity:x.expected.identity,assets:[{name:'turboism-0.68.0.zip',bytes:payload.length,sha256:hash(payload)}]});
 const saved=new Map([['historical/'+hash(record),record],['files/'+hash(payload)+'/turboism-0.68.0.zip',payload]]);
 const archive={read:async k=>saved.get(k)};
 assert.equal((await verifyHistoricalPromotion(record,archive)).identity.buildNumber,50);
 await assert.rejects(()=>verifyHistoricalPromotion(enc({...JSON.parse(record),identity:{...x.expected.identity,buildNumber:51}}),archive),/historical/);
 saved.set('files/'+hash(payload)+'/turboism-0.68.0.zip',Buffer.from('corrupted'));
 await assert.rejects(()=>verifyHistoricalPromotion(record,archive),/content/);
});

test('incremental accepts actual Gradle classifier convention and rejects multiple host roles',async()=>{
 const x=incrementalSetup();resign(x,m=>{m.entries.find(e=>e.relativePath==='graal/lib/graal-host-0.68.0.jar').relativePath='graal/lib/graal-host-0.68.0-feat-issue-50.jar';});await x.run();
 const y=incrementalSetup();resign(y,m=>{const host=m.entries.find(e=>e.relativePath==='graal/lib/graal-host-0.68.0.jar');m.entries.push({...host,relativePath:'graal/lib/graal-host-0.68.0-second.jar'});m.entries.sort((a,b)=>a.relativePath<b.relativePath?-1:1);});await assert.rejects(y.run,/Incomplete/);assert.equal(y.channels.get('stable'),y.oldId);
});

function targetCandidate(x,next){
 const before=x.candidate.identity.version;
 x.candidate.identity=structuredClone(next);x.expected.identity=structuredClone(next);
 resign(x,m=>{Object.assign(m,next);for(const e of m.entries)e.relativePath=e.relativePath.replace(before,next.version);m.entries.sort((a,b)=>a.relativePath<b.relativePath?-1:1);});
}
test('channel admission enforces SemVer AND global build advance against authenticated current record',async()=>{
 const cases=[
  ['0.69.0',49,'0.68.0',100,false], // Older branch with a newer allocated build still loses.
  ['0.67.0',51,'0.68.0',50,false],
  ['0.67.0',50,'0.68.0',50,false],
  ['0.68.0',49,'0.68.0',50,false],
  ['0.68.0-beta.10',49,'0.68.0-beta.2',50,false],
  ['0.68.0-beta.2',49,'0.68.0-beta.10',50,true],
  ['0.67.0',49,'0.68.0',50,true]
 ];
 for(const [oldVersion,oldBuild,version,buildNumber,allowed] of cases){
  const channel=version.includes('beta')?'beta':'stable';
  const x=incrementalSetup({version:oldVersion,channel,buildNumber:oldBuild,sourceRevision:'b'.repeat(40)});
  targetCandidate(x,{version,channel,buildNumber,sourceRevision:'c'.repeat(40)});
  if(allowed){await x.run();assert.equal(x.channels.get(channel),x.candidate.manifest.sha256);}
  else{await assert.rejects(x.run,/advance/);assert.equal(x.channels.get(channel),x.oldId);assert.equal(x.events.length,0);}
 }
});
test('explicit empty-channel initialization and exact record retry are distinct from reinterpreting a version',async()=>{
 const x=incrementalSetup();x.channels.clear();
 await assert.rejects(()=>x.run({previous:null}),/initialization/);
 await x.run({previous:null,initialize:true});
 await x.run({previous:x.oldId}); // exact already-published record, no pointer write
 const sameVersion=incrementalSetup({version:'0.68.0',channel:'stable',buildNumber:50,sourceRevision:'f'.repeat(40)});
 await assert.rejects(sameVersion.run,/advance/);assert.equal(sameVersion.events.length,0);
});
test('missing, hash-rebound, identity-modified or corrupted current records fail before writes',async()=>{
 for(const kind of ['missing','malformed','id','identity','signature','manifest','key','index','pointer']){
  const x=incrementalSetup();const key='releases/'+x.oldId;
  if(kind==='missing')x.objects.delete(key);
  if(kind==='malformed')x.objects.set(key,Buffer.from('broken'));
  if(kind==='id'){const r=JSON.parse(x.oldRecord);r.id='a'.repeat(64);x.objects.set(key,enc(r));}
  if(kind==='identity'){const r=JSON.parse(x.oldRecord);r.candidate.identity.buildNumber=1;x.objects.set(key,enc(r));}
  if(kind==='signature')x.objects.set('documents/'+x.old.envelope.sha256,Buffer.from('broken'));
  if(kind==='manifest')x.objects.set('documents/'+x.old.manifest.sha256,Buffer.from('broken'));
  if(kind==='key')delete x.keys['historical-test'];
  if(kind==='index')x.objects.delete('builds/'+x.old.identity.buildNumber);
  if(kind==='pointer')x.channels.set('stable','not-a-record-id');
  await assert.rejects(x.run);assert.equal(x.events.length,0);
 }
});
test('CAS race never silently changes previous; a retry revalidates the winning authenticated snapshot',async()=>{
 const x=incrementalSetup(),winner=incrementalSetup();
 targetCandidate(x,{...x.candidate.identity,version:'0.69.0',buildNumber:51});
 winner.signingPair=x.signingPair;resign(winner,()=>{});await winner.run();const winningId=winner.candidate.manifest.sha256;
 const write=x.store.createImmutable;let injected=false;
 x.store.createImmutable=async(...args)=>{await write(...args);if(!injected){injected=true;for(const [k,b] of winner.objects)x.objects.set(k,b);x.channels.set('stable',winningId);}};
 await assert.rejects(x.run,/Channel conflict/);assert.equal(x.channels.get('stable'),winningId);
 await assert.rejects(x.run,/snapshot conflict/); // Caller must explicitly retry with a fresh snapshot.
 await x.run({previous:winningId});assert.equal(x.channels.get('stable'),x.candidate.manifest.sha256);
});
test('public verify snapshots candidate, expected and keys before any untrusted callback await',async()=>{
 const x=incrementalSetup();let changed=false;
 const result=await verifyIncremental(x.candidate,x.expected,x.keys,async(...args)=>{
  if(!changed){changed=true;x.candidate.identity.sourceRevision='f'.repeat(40);x.expected.identity.buildNumber=1;x.keys['offline-test']=new Uint8Array(44);}
  return x.read(...args);
 });
 assert.ok(result.closure.length>0);
});
