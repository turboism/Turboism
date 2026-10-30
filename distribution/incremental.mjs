/** Offline-only v1 admission protocol. Not imported by worker/mirror or deployed.
 * The protected caller supplies verified source/build identity and pinned public keys.
 * Storage adapters must provide immutable create/read and linearizable channel CAS;
 * R2 metadata, ETags and unsigned inventories never authorize publication.
 */
import {compareVersions} from './protocol.mjs';
import layout from '../packaging/framework-update-layout.json' with {type:'json'};
const encoder=new TextEncoder(),decoder=new TextDecoder('utf-8',{fatal:true});
const hex=bytes=>Array.from(new Uint8Array(bytes),b=>b.toString(16).padStart(2,'0')).join('');
export function canonical(value){
 if(Array.isArray(value))return '['+value.map(canonical).join(',')+']';
 if(value&&typeof value==='object')return '{'+Object.keys(value).sort().map(k=>JSON.stringify(k)+':'+canonical(value[k])).join(',')+'}';
 return JSON.stringify(value);
}
export const digest=async bytes=>hex(await crypto.subtle.digest('SHA-256',bytes));
const demand=(ok,message)=>{if(!ok)throw new Error(message);};
const integer=(n,max,min=0)=>Number.isSafeInteger(n)&&n>=min&&n<=max;
const sha=value=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
const keys=(value,names)=>value&&typeof value==='object'&&!Array.isArray(value)&&Object.keys(value).length===names.length&&names.every(k=>Object.hasOwn(value,k));
function active(signal){signal?.throwIfAborted();}
function document(bytes,limit){
 demand(bytes instanceof Uint8Array&&bytes.length>0&&bytes.length<=limit,'Document bounds');
 const text=decoder.decode(bytes),value=JSON.parse(text);
 // v1 publisher contract requires the project's deterministic ASCII-valued JSON.
 // Exact byte comparison rejects duplicate keys, rounded integers and alternate encodings.
 demand(canonical(value)===text,'Non-deterministic or ambiguous JSON');return value;
}
function identity(value){
 demand(keys(value,['version','channel','buildNumber','sourceRevision']),'Identity fields');
 const core='(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)';
 const suffix={stable:'',beta:'-(alpha|beta|rc)\\.(0|[1-9][0-9]*)',nightly:'-0\\.nightly\\.([1-9][0-9]*)'};
 demand(typeof value.version==='string'&&value.version.length<=96&&typeof value.channel==='string'&&Object.hasOwn(suffix,value.channel)&&new RegExp('^'+core+suffix[value.channel]+'$').test(value.version),'Release version/channel');
 demand(value.version.split('-')[0].split('.').every(n=>integer(Number(n),2147483647))&&integer(value.buildNumber,Number.MAX_SAFE_INTEGER,1)&&typeof value.sourceRevision==='string'&&/^[a-f0-9]{40}$/.test(value.sourceRevision),'Source/build bounds');
}
function pathName(name){
 demand(typeof name==='string'&&name.length<=240&&!name.includes('..')&&/^[A-Za-z0-9._/-]+$/.test(name),'Unsafe path');
 demand(name.split('/').every(s=>s&&!s.endsWith('.')&&!/^(con|prn|aux|nul|com[1-9]|lpt[1-9])$/i.test(s.split('.')[0])),'Unsafe path segment');
 demand(layout.frameworkRootFiles.includes(name)||/^graal\/lib\/[A-Za-z0-9._-]+\.jar$/.test(name),'Unknown ownership');
}
async function checkedRead(read,key,size,hash,signal){
 active(signal);const raw=await read(key,size);active(signal);
 demand(raw instanceof Uint8Array&&raw.length===size,'Object content mismatch');
 const bytes=new Uint8Array(raw);
 demand(await digest(bytes)===hash,'Object content mismatch');return bytes;
}
/** Authenticity + exact trusted expected identity; objects.json is deliberately absent. */
export async function verifyIncremental(candidate,expected,trustedKeys,read,signal){
 return verifySnapshot(structuredClone(candidate),structuredClone(expected),structuredClone(trustedKeys),read,signal);
}
async function verifySnapshot(candidate,expected,trustedKeys,read,signal){
 demand(keys(candidate,['format','schemaVersion','repository','identity','manifest','envelope','objectsSha256'])&&candidate.format==='turboism.incremental-candidate'&&candidate.schemaVersion===1&&candidate.repository==='turboism/Turboism','Incremental v1 required');
 identity(candidate.identity);identity(expected.identity);
 demand(canonical(candidate.identity)===canonical(expected.identity),'Expected identity mismatch');
 for(const [name,limit] of [['manifest',1048576],['envelope',16384]]){
  const ref=candidate[name];demand(keys(ref,['bytes','sha256'])&&integer(ref.bytes,limit,1)&&sha(ref.sha256),'Document reference');
 }
 demand(sha(candidate.objectsSha256),'Closure reference');
 const manifestBytes=await checkedRead(read,`documents/${candidate.manifest.sha256}`,candidate.manifest.bytes,candidate.manifest.sha256,signal);
 const envelopeBytes=await checkedRead(read,`documents/${candidate.envelope.sha256}`,candidate.envelope.bytes,candidate.envelope.sha256,signal);
 const envelope=document(envelopeBytes,16384);
 demand(keys(envelope,['format','schemaVersion','algorithm','keyId','manifestSha256','signature'])&&envelope.format==='turboism-update-manifest-sig-v1'&&envelope.schemaVersion===1&&envelope.algorithm==='Ed25519'&&envelope.manifestSha256===candidate.manifest.sha256,'Signature envelope');
 demand(typeof envelope.keyId==='string'&&envelope.keyId.length<=64&&/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(envelope.keyId)&&envelope.keyId===expected.keyId&&Object.hasOwn(trustedKeys,envelope.keyId),'Untrusted signing identity');
 demand(typeof envelope.signature==='string'&&/^[A-Za-z0-9+/]{86}==$/.test(envelope.signature),'Signature encoding');
 const signature=Uint8Array.from(atob(envelope.signature),c=>c.charCodeAt(0));
 demand(btoa(String.fromCharCode(...signature))===envelope.signature,'Signature encoding');
 const key=await crypto.subtle.importKey('spki',trustedKeys[envelope.keyId],{name:'Ed25519'},false,['verify']);
 demand(await crypto.subtle.verify('Ed25519',key,signature,manifestBytes),'Invalid signature');
 const manifest=document(manifestBytes,1048576);
 demand(keys(manifest,['schemaVersion','keyId','version','channel','buildNumber','sourceRevision','minimumUpdaterVersion','targetPlatform','entries'])&&manifest.schemaVersion===1&&manifest.keyId===envelope.keyId&&manifest.targetPlatform==='windows-x64','Manifest fields');
 demand(Object.entries(candidate.identity).every(([k,v])=>manifest[k]===v),'Signed identity mismatch');
 demand(typeof manifest.minimumUpdaterVersion==='string'&&manifest.minimumUpdaterVersion.length<=128&&/^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:[-+][0-9A-Za-z.-]+)?$/.test(manifest.minimumUpdaterVersion)&&manifest.minimumUpdaterVersion.split(/[-+]/)[0].split('.').every(n=>integer(Number(n),2147483647)),'Minimum updater bounds');
 demand(Array.isArray(manifest.entries)&&manifest.entries.length>0&&manifest.entries.length<=512,'Entry bounds');
 const names=new Set(),objects=new Map();let total=0,previous='';
 for(const entry of manifest.entries){
  demand(keys(entry,['relativePath','bytes','sha256','contentId']),'Entry fields');pathName(entry.relativePath);
  demand(!names.has(entry.relativePath.toLowerCase())&&entry.relativePath>previous,'Duplicate or unsorted path');previous=entry.relativePath;names.add(entry.relativePath.toLowerCase());
  demand(integer(entry.bytes,536870912)&&sha(entry.sha256)&&entry.contentId==='sha256:'+entry.sha256,'Entry digest/size');
  demand(!objects.has(entry.sha256)||objects.get(entry.sha256)===entry.bytes,'Conflicting object size');objects.set(entry.sha256,entry.bytes);total+=entry.bytes;
 }
 demand(total<=1073741824,'Expanded payload bounds');
 demand(layout.frameworkRootFiles.every(n=>names.has(n.toLowerCase()))&&names.has(`graal/lib/sdk-${manifest.version}.jar`)&&[...names].filter(n=>new RegExp('^graal/lib/graal-host-'+manifest.version.replaceAll('.', '\\.')+'(?:-[A-Za-z0-9._-]+)?\\.jar$').test(n)).length===1,'Incomplete framework payload');
 const closure=[...objects].sort(([a],[b])=>a.localeCompare(b)).map(([sha256,bytes])=>({sha256,bytes}));
 demand(await digest(encoder.encode(canonical(closure)))===candidate.objectsSha256,'Closure mismatch');
 for(const object of closure)await checkedRead(read,`objects/${object.sha256}/payload`,object.bytes,object.sha256,signal);
 active(signal);return {manifestBytes,envelopeBytes,closure};
}
/** Adapter callbacks receive only verified bytes. Channel CAS is the sole visibility step.
 * A failed/interrupted run may leave immutable orphans, but never deletes or replaces objects.
 * This is NOT a ready-to-use R2 adapter: read bounds and linearizable CAS are adapter obligations.
 */
export async function admitIncremental(candidate,expected,trustedKeys,source,store,{previous=null,initialize=false,signal}={}){
 // Snapshot all untrusted inputs before async callbacks, preventing caller mutation.
 candidate=structuredClone(candidate);expected=structuredClone(expected);trustedKeys=structuredClone(trustedKeys);
 const verified=await verifySnapshot(candidate,expected,trustedKeys,source,signal);
 const id=candidate.manifest.sha256;
 const record={format:'turboism.incremental-release',schemaVersion:1,id,candidate};
 const recordBytes=encoder.encode(canonical(record));
 // Read and authenticate the SAME snapshot that will be supplied to channel CAS.
 // A CAS token alone proves concurrency, never version/build progression.
 demand(previous===null||sha(previous),'Invalid previous snapshot');active(signal);
 const current=await store.readChannel(candidate.identity.channel);active(signal);
 demand(current===null||sha(current),'Invalid current channel');
 demand(current===previous||current===id,'Channel snapshot conflict');
 if(current===null){
  demand(initialize===true&&previous===null,'Explicit initialization required');
 }else{
  const raw=await store.read(`releases/${current}`,1048576);active(signal);
  const oldBytes=raw instanceof Uint8Array?new Uint8Array(raw):raw;
  const old=document(oldBytes,1048576);
  demand(keys(old,['format','schemaVersion','id','candidate'])&&old.format==='turboism.incremental-release'&&old.schemaVersion===1&&old.id===current&&old.candidate?.manifest?.sha256===current,'Current release binding');
  const oldEnvelope=await checkedRead((k,max)=>store.read(k,max),`documents/${old.candidate.envelope?.sha256}`,old.candidate.envelope?.bytes,old.candidate.envelope?.sha256,signal);
  const keyId=document(oldEnvelope,16384).keyId;
  await verifySnapshot(old.candidate,{identity:old.candidate.identity,keyId},trustedKeys,(k,max)=>store.read(k,max),signal);
  demand(old.candidate.identity.channel===candidate.identity.channel,'Current channel identity');
  const oldHash=await digest(oldBytes);
  for(const index of [`versions/${old.candidate.identity.version}`,`builds/${old.candidate.identity.buildNumber}`])await checkedRead((k,max)=>store.read(k,max),index,oldBytes.length,oldHash,signal);
  if(current===id){
   demand(canonical(old)===canonical(record),'Retry record conflict');
   return record; // Exact already-published retry; never switches a newer pointer back.
  }
  demand(compareVersions(candidate.identity.version,old.candidate.identity.version)>0,'Version must advance');
  demand(candidate.identity.buildNumber>old.candidate.identity.buildNumber,'Build must advance');
 }
 async function install(key,bytes){
  active(signal);const hash=await digest(bytes),size=bytes.length;
  await store.createImmutable(key,new Uint8Array(bytes));active(signal);
  await checkedRead((k,max)=>store.read(k,max),key,size,hash,signal);
 }
 for(const o of verified.closure){
  const key=`objects/${o.sha256}/payload`;
  const bytes=await checkedRead(source,key,o.bytes,o.sha256,signal);await install(key,bytes);
 }
 // Independently read back every object before registering either signed document.
 for(const o of verified.closure)await checkedRead((k,max)=>store.read(k,max),`objects/${o.sha256}/payload`,o.bytes,o.sha256,signal);
 await install(`documents/${candidate.manifest.sha256}`,verified.manifestBytes);
 await install(`documents/${candidate.envelope.sha256}`,verified.envelopeBytes);
 // Globally allocated builds and product versions are immutable identities too.
 await install(`versions/${candidate.identity.version}`,recordBytes);
 await install(`builds/${candidate.identity.buildNumber}`,recordBytes);
 await install(`releases/${id}`,recordBytes);
 active(signal);
 // Atomic expected-current comparison, idempotent if already exactly this record.
 demand(await store.compareAndSwapChannel(candidate.identity.channel,current,id),'Channel conflict');
 return record;
}
/** Historical promotion has no caller-controlled legacy fallback. The protected archive
 * supplies exact canonical immutable records captured before activation, keyed by digest.
 * It is separate from new-candidate admission and cannot accept incremental/new candidates.
 */
export async function verifyHistoricalPromotion(recordBytes,archive){
 demand(recordBytes instanceof Uint8Array&&recordBytes.length>0&&recordBytes.length<=1048576,'Historical document bounds');
 recordBytes=recordBytes.slice();
 const id=await digest(recordBytes),evidence=await archive.read(`historical/${id}`,1048576);
 demand(evidence instanceof Uint8Array&&evidence.length===recordBytes.length&&await digest(evidence)===id,'Unverified historical release');
 const record=document(recordBytes,1048576);
 demand(keys(record,['format','schemaVersion','repository','identity','assets'])&&record.format==='turboism.historical-release'&&record.schemaVersion===1&&record.repository==='turboism/Turboism','Historical record');identity(record.identity);
 demand(Array.isArray(record.assets)&&record.assets.length>0&&record.assets.length<=8,'Historical assets');
 const names=new Set();for(const a of record.assets){demand(keys(a,['name','bytes','sha256'])&&typeof a.name==='string'&&/^[A-Za-z0-9._-]+$/.test(a.name)&&!names.has(a.name)&&integer(a.bytes,2147483648,1)&&sha(a.sha256),'Historical asset');names.add(a.name);await checkedRead((k,max)=>archive.read(k,max),`files/${a.sha256}/${a.name}`,a.bytes,a.sha256);}
 return record;
}
