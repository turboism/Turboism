/** Framework self-update resources: admitted signed manifests, immutable payload objects and
 * the optional `release.incremental` projection. Signature and layout verification stay in
 * incremental.mjs; this module supplies the storage authority and public reads.
 *
 * R2 holds immutable bytes (`framework/<key>`); a SQLite Durable Object is the linearizable
 * authority for channel pointers and for which resources are public. The protected publisher
 * stages bytes under `framework-incoming/` (never served) and requests admission.
 */
import {admitIncremental} from './incremental.mjs';
import {parseRange} from './files.mjs';
import {hasVerifiedBytes} from './mirror.mjs';
import {CHANNELS,ORIGIN,jsonResponse} from './protocol.mjs';
export const FRAMEWORK_PATH='/v1/framework/';
export const RESOURCE_RE=/^\/v1\/framework\/(documents\/[a-f0-9]{64}|objects\/[a-f0-9]{64}\/payload)$/;
const ADMIT_LIMIT=65536,PUBLIC_KEY_PREFIX='302a300506032b6570032100';
const FINAL='framework/',INCOMING='framework-incoming/';
const hex=bytes=>Array.from(new Uint8Array(bytes),b=>b.toString(16).padStart(2,'0')).join('');
const demand=(ok,message)=>{if(!ok)throw new Error(message);};
const authority=env=>env.FRAMEWORK.get(env.FRAMEWORK.idFromName('turboism'));
const json=(value,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json','Cache-Control':'no-store'}});
async function streamSha256(body){
 if(typeof crypto.DigestStream==='function'){const stream=new crypto.DigestStream('SHA-256');await body.pipeTo(stream);return hex(await stream.digest);}
 return hex(await crypto.subtle.digest('SHA-256',await new Response(body).arrayBuffer())); // runtimes without DigestStream (tests)
}
/** Operator-pinned public keys: {"key-id":"base64 X.509 Ed25519 SPKI"}. Empty/invalid disables admission. */
export function trustedKeys(env){
 let parsed;try{parsed=JSON.parse(env.FRAMEWORK_TRUSTED_KEYS??'{}');}catch{throw new Error('FRAMEWORK_TRUSTED_KEYS is not JSON');}
 const entries=Object.entries(parsed);demand(parsed&&typeof parsed==='object'&&!Array.isArray(parsed)&&entries.length>0&&entries.length<=8,'No framework signing keys configured');
 return Object.fromEntries(entries.map(([id,value])=>{
  demand(/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(id)&&id.length<=64&&typeof value==='string','Invalid framework key id');
  const der=Uint8Array.from(atob(value),c=>c.charCodeAt(0));
  demand(hex(der.slice(0,12))===PUBLIC_KEY_PREFIX&&der.length===44,'Framework key is not an Ed25519 SPKI');return [id,der];
 }));
}
/** R2-backed adapters for admitIncremental. Payloads are only ever streamed, never buffered. */
export function r2Adapters(bucket,pending){
 demand(bucket,'Framework storage is not configured');
 const objectKey=sha=>`objects/${sha}/payload`;
 const store={
  async read(key,max){
   const object=await bucket.get(FINAL+key);if(!object)return null;
   demand(object.size<=max,'Stored document is too large');return new Uint8Array(await object.arrayBuffer());
  },
  async createImmutable(key,bytes){
   const digest=hex(await crypto.subtle.digest('SHA-256',bytes));
   const created=await bucket.put(FINAL+key,bytes,{onlyIf:{etagDoesNotMatch:'*'},sha256:digest,httpMetadata:{contentType:'application/octet-stream',cacheControl:'public, max-age=31536000, immutable'}});
   if(!created){
    const existing=await store.read(key,bytes.length);
    demand(existing&&existing.length===bytes.length&&hex(await crypto.subtle.digest('SHA-256',existing))===digest,'Immutable framework key conflict');
   }
   if(key.startsWith('documents/'))pending.resources.set(key,bytes.length);
   if(key.startsWith('releases/'))pending.record=JSON.parse(new TextDecoder().decode(bytes));
  },
  readChannel:channel=>pending.authority.readChannel(channel),
  compareAndSwapChannel:(channel,expected,next)=>pending.authority.swap(channel,expected,next,pending),
 };
 const objects={
  async verifySource(sha,size){
   const object=await bucket.get(INCOMING+objectKey(sha));
   demand(object&&object.size===size&&await streamSha256(object.body)===sha,'Staged object content mismatch');
  },
  async verifyStored(sha,size){demand(hasVerifiedBytes(await bucket.head(FINAL+objectKey(sha)),{size,sha256:sha}),'Stored object content mismatch');},
  async copy(sha,size){
   const key=FINAL+objectKey(sha);
   if(!await bucket.head(key)){
    const source=await bucket.get(INCOMING+objectKey(sha));demand(source&&source.size===size,'Staged object missing');
    let body=source.body,piping=null;
    if(typeof FixedLengthStream==='function'){const fixed=new FixedLengthStream(size);piping=source.body.pipeTo(fixed.writable);body=fixed.readable;}
    // R2 verifies the digest while consuming the stream and never replaces an existing key.
    try{await bucket.put(key,body,{onlyIf:{etagDoesNotMatch:'*'},sha256:sha,httpMetadata:{contentType:'application/octet-stream',cacheControl:'public, max-age=31536000, immutable'}});}
    finally{if(piping)await piping.catch(()=>{});}
   }
   await objects.verifyStored(sha,size);pending.resources.set(objectKey(sha),size);
  },
 };
 return {store,objects,source:async(key,max)=>{
  const object=await bucket.get(INCOMING+key);if(!object)return null;
  demand(object.size<=max,'Staged document is too large');return new Uint8Array(await object.arrayBuffer());
 }};
}
export class FrameworkAuthority {
 constructor(ctx,env){
  this.ctx=ctx;this.env=env;this.queue=Promise.resolve();
  this.ready=ctx.blockConcurrencyWhile(async()=>{
   const sql=ctx.storage.sql;
   sql.exec('CREATE TABLE IF NOT EXISTS framework_channels (channel TEXT PRIMARY KEY, id TEXT NOT NULL)');
   sql.exec('CREATE TABLE IF NOT EXISTS framework_releases (id TEXT PRIMARY KEY, version TEXT NOT NULL UNIQUE, build_number INTEGER NOT NULL UNIQUE, channel TEXT NOT NULL, candidate TEXT NOT NULL, withdrawn INTEGER NOT NULL DEFAULT 0)');
   sql.exec('CREATE TABLE IF NOT EXISTS framework_resources (key TEXT PRIMARY KEY, bytes INTEGER NOT NULL)');
  });
 }
 readChannel(channel){return this.ctx.storage.sql.exec('SELECT id FROM framework_channels WHERE channel=?',channel).toArray()[0]?.id??null;}
 /** Linearizable expected-current CAS; registration of the release and its public resources commits with it. */
 swap(channel,expected,next,pending){
  return this.ctx.storage.transactionSync(()=>{
   if(this.readChannel(channel)!==expected)return false;
   const record=pending.record;demand(record?.id===next&&record.candidate.identity.channel===channel,'Release record was not registered');
   const identity=record.candidate.identity,sql=this.ctx.storage.sql;
   sql.exec('INSERT OR IGNORE INTO framework_releases (id,version,build_number,channel,candidate) VALUES (?,?,?,?,?)',next,identity.version,identity.buildNumber,channel,JSON.stringify(record.candidate));
   for(const [key,bytes] of pending.resources)sql.exec('INSERT OR IGNORE INTO framework_resources (key,bytes) VALUES (?,?)',key,bytes);
   sql.exec('INSERT OR REPLACE INTO framework_channels (channel,id) VALUES (?,?)',channel,next);
   return true;
  });
 }
 async admit(request){
  let keys;try{keys=trustedKeys(this.env);}catch{return json({error:{code:'FRAMEWORK_DISABLED'}},503);}
  const text=await request.text();
  try{
   demand(text.length<=ADMIT_LIMIT,'Admission request is too large');
   const body=JSON.parse(text);
   demand(body&&typeof body==='object'&&Object.keys(body).every(k=>['candidate','expected','initialize'].includes(k)),'Admission fields');
   const channel=body.candidate?.identity?.channel;demand(CHANNELS.includes(channel),'Unknown channel');
   const pending={authority:this,resources:new Map(),record:null},{store,objects,source}=r2Adapters(this.env.DOWNLOADS,pending);
   // The authority supplies the snapshot; the CAS inside admission is what resolves races.
   const previous=this.readChannel(channel);
   const record=await admitIncremental(body.candidate,body.expected,keys,source,store,{previous,initialize:previous===null&&body.initialize===true,objects,signal:AbortSignal.timeout(600000)});
   this.ctx.storage.sql.exec('UPDATE framework_releases SET withdrawn=0 WHERE id=?',record.id);
   return json({status:'admitted',id:record.id,identity:record.candidate.identity});
  }catch(error){
   console.error('Framework admission rejected:',error.message);
   return json({error:{code:error.message==='Channel conflict'?'CHANNEL_CONFLICT':'ADMISSION_REJECTED',message:String(error.message).slice(0,200)}},error.message==='Channel conflict'?409:422);
  }
 }
 async fetch(request){
  await this.ready;const url=new URL(request.url),sql=this.ctx.storage.sql;
  if(url.pathname==='/admit'&&request.method==='POST'){
   const run=this.queue.then(()=>this.admit(request),()=>this.admit(request));this.queue=run.catch(()=>{});return run;
  }
  if(url.pathname==='/withdraw'&&request.method==='POST'){
   const id=(await request.json().catch(()=>null))?.id;
   if(typeof id!=='string'||!/^[a-f0-9]{64}$/.test(id))return json({error:{code:'INVALID_REQUEST'}},400);
   sql.exec('UPDATE framework_releases SET withdrawn=1 WHERE id=?',id);
   return json({status:'withdrawn',id,matched:sql.exec('SELECT 1 FROM framework_releases WHERE id=?',id).toArray().length===1});
  }
  if(url.pathname==='/resource'){
   const row=sql.exec('SELECT bytes FROM framework_resources WHERE key=?',url.searchParams.get('key')??'').toArray()[0];
   return row?json({bytes:row.bytes}):json({error:{code:'FRAMEWORK_RESOURCE_UNKNOWN'}},404);
  }
  if(url.pathname==='/advertise'){
   const row=sql.exec('SELECT candidate FROM framework_releases WHERE version=? AND channel=? AND withdrawn=0',url.searchParams.get('version')??'',url.searchParams.get('channel')??'').toArray()[0];
   return row?json({candidate:JSON.parse(row.candidate)}):json({error:{code:'NOT_ADVERTISED'}},404);
  }
  return json({error:{code:'NOT_FOUND'}},404);
 }
}
/** Stream one registered immutable resource. Unregistered keys, including staged uploads, are never readable. */
export async function serveFramework(request,env){
 const key=RESOURCE_RE.exec(new URL(request.url).pathname)[1];
 if(!env.FRAMEWORK||!env.DOWNLOADS)return jsonResponse(request,{error:{code:'FRAMEWORK_UNAVAILABLE'}},503);
 const known=await authority(env).fetch(new Request('https://framework.internal/resource?key='+encodeURIComponent(key)));
 if(known.status===404)return jsonResponse(request,{error:{code:'FRAMEWORK_RESOURCE_UNKNOWN'}},404);
 if(!known.ok)return jsonResponse(request,{error:{code:'FRAMEWORK_UNAVAILABLE'}},503);
 const {bytes}=await known.json(),sha=key.startsWith('documents/')?key.slice(10):key.slice(8,72),asset={key:FINAL+key,size:bytes,sha256:sha};
 if(!hasVerifiedBytes(await env.DOWNLOADS.head(asset.key),asset))return jsonResponse(request,{error:{code:'FRAMEWORK_RESOURCE_UNAVAILABLE'}},503);
 const etag='"'+sha+'"',headers=new Headers({'Content-Type':'application/octet-stream','Content-Length':String(bytes),'Accept-Ranges':'bytes',ETag:etag,'X-Content-Type-Options':'nosniff','Cache-Control':'public, max-age=31536000, immutable, no-transform','Access-Control-Allow-Origin':'*','Access-Control-Expose-Headers':'ETag, Content-Length, Content-Range, Accept-Ranges'});
 if((request.headers.get('if-none-match')??'').split(',').some(v=>v.trim().replace(/^W\//,'')===etag||v.trim()==='*'))return new Response(null,{status:304,headers});
 if(request.method==='HEAD')return new Response(null,{headers});
 const ifRange=request.headers.get('if-range'),range=parseRange(!ifRange||ifRange===etag?request.headers.get('range'):null,bytes);
 if(range===false){headers.set('Content-Range',`bytes */${bytes}`);headers.delete('Content-Length');return new Response(null,{status:416,headers});}
 const object=await env.DOWNLOADS.get(asset.key,range?{range}:undefined);
 if(!object||object.size!==bytes)return jsonResponse(request,{error:{code:'FRAMEWORK_RESOURCE_UNAVAILABLE'}},503);
 if(range){headers.set('Content-Range',`bytes ${range.offset}-${range.offset+range.length-1}/${bytes}`);headers.set('Content-Length',String(range.length));}
 return new Response(object.body,{status:range?206:200,headers});
}
/** Add `release.incremental` only for an admitted, unwithdrawn release with an identical identity.
 * Any lookup failure falls back to the plain full-installer document. */
export async function withIncremental(document,env){
 const release=document.release;
 if(!env.FRAMEWORK||document.status!=='ready'||!release||!Number.isSafeInteger(release.buildNumber))return document;
 try{
  const query=new URLSearchParams({version:release.version,channel:document.channel});
  const response=await Promise.race([authority(env).fetch(new Request('https://framework.internal/advertise?'+query)),new Promise((_,reject)=>setTimeout(()=>reject(new Error('timeout')),3000))]);
  if(!response.ok)return document;
  const {candidate}=await response.json(),identity=candidate?.identity;
  if(identity?.version!==release.version||identity.channel!==document.channel||identity.buildNumber!==release.buildNumber||identity.sourceRevision!==release.sourceRevision)return document;
  return {...document,release:{...release,incremental:{source:{origin:ORIGIN,redirects:'reject'},descriptor:candidate}}};
 }catch{return document;}
}
/** Worker-side entry for protected publisher calls (OIDC already verified). */
export function forwardPublisher(request,env,action){
 if(!env.FRAMEWORK)return json({error:{code:'FRAMEWORK_DISABLED'}},503);
 return authority(env).fetch(new Request('https://framework.internal/'+action,{method:'POST',body:request.body,headers:{'Content-Type':'application/json'}}));
}
