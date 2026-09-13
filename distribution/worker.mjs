import { DownloadCounter,countsDocument } from './download-counts.mjs';
import { CHANNELS,ORIGIN,SNAPSHOT_REFRESH_MS,documentFor,jsonResponse,snapshotAge,snapshotUsable } from './protocol.mjs';
import { synchronize } from './github.mjs';
import { serveFile } from './files.mjs';
import { verifyGitHubIdentity } from './identity.mjs';
const registry=env=>env.REGISTRY.get(env.REGISTRY.idFromName('turboism'));
export default {
 async fetch(request,env){
  const url=new URL(request.url),path=url.pathname;
  if(path==='/v1/sync'&&request.method==='POST'){
   try{await verifyGitHubIdentity(request.headers.get('Authorization')?.replace(/^Bearer /,''));}catch{return jsonResponse(request,{error:{code:'UNAUTHORIZED'}},401);}
   return registry(env).fetch(new Request('https://registry.internal/_refresh',{method:'POST'}));
  }
  if(request.method==='OPTIONS')return new Response(null,{status:204,headers:{'Access-Control-Allow-Origin':'*','Access-Control-Allow-Methods':'GET, HEAD, OPTIONS','Access-Control-Allow-Headers':'If-None-Match, Range, If-Range','Access-Control-Max-Age':'600'}});
  if(!['GET','HEAD'].includes(request.method))return jsonResponse(request,{error:{code:'METHOD_NOT_ALLOWED'}},405);
  if(path==='/')return jsonResponse(request,{service:'Turboism release API',schemaVersion:1,channels:CHANNELS.map(c=>`${ORIGIN}/v1/releases/${c}.json`),source:'https://github.com/turboism/Turboism/releases'});
  if(/^\/v1\/downloads\/[0-9A-Za-z.-]{1,96}\.json$/.test(path)||path==='/health'||/^\/v1\/releases\/(stable|beta|nightly)\.json$/.test(path)||/^\/files\/[a-f0-9]{64}\/[A-Za-z0-9][A-Za-z0-9._-]*$/.test(path))return registry(env).fetch(request);
  return jsonResponse(request,{error:{code:'NOT_FOUND'}},404);
 },
 async scheduled(_event,env,ctx){ctx.waitUntil(registry(env).fetch(new Request('https://registry.internal/_refresh',{method:'POST'})));},
};
/** A single durable registry serializes snapshots, persists known hashes, and coalesces refreshes. */
export class ReleaseRegistry {
 constructor(ctx,env){
  this.ctx=ctx;this.env=env;this.snapshot=null;this.refreshing=null;this.lastAttempt=0;
  this.ready=ctx.blockConcurrencyWhile(async()=>{
   ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS metadata (id INTEGER PRIMARY KEY, value TEXT NOT NULL)');
   ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS known_releases (tag TEXT PRIMARY KEY, value TEXT NOT NULL)');
   this.counts=new DownloadCounter(ctx.storage);
   try{this.counts.initialize();}catch{console.error('Download analytics initialization failed');}
   const row=ctx.storage.sql.exec('SELECT value FROM metadata WHERE id=1').toArray()[0];
   if(row){this.snapshot=JSON.parse(row.value);this.snapshot.known=Object.fromEntries(ctx.storage.sql.exec('SELECT tag,value FROM known_releases').toArray().map(r=>[r.tag,JSON.parse(r.value)]));}
  });
 }
 save(){
  const {known,...metadata}=this.snapshot;
  this.ctx.storage.transactionSync(()=>{
   this.ctx.storage.sql.exec('INSERT OR REPLACE INTO metadata (id,value) VALUES (1,?)',JSON.stringify(metadata));
   for(const [tag,value] of Object.entries(known??{}))this.ctx.storage.sql.exec('INSERT OR REPLACE INTO known_releases (tag,value) VALUES (?,?)',tag,JSON.stringify(value));
  });
 }
 async refresh(){
  if(this.refreshing)return this.refreshing;
  if(Date.now()-this.lastAttempt<60000&&this.snapshot)return;
  this.lastAttempt=Date.now();
  this.refreshing=(async()=>{
   try{this.counts?.prune();}catch{console.error('Download analytics cleanup failed');}
   const previous=this.snapshot;
   try{this.snapshot=await synchronize(previous,this.env);this.snapshot.refreshError=null;this.save();}
   catch(error){
    if(previous?.syncedAt&&Array.isArray(previous.releases)&&previous.releases.length){this.snapshot={...previous,refreshError:'SYNC_UNAVAILABLE'};delete this.snapshot.error;}
    else this.snapshot={...(previous??{}),error:'SYNC_UNAVAILABLE',refreshError:'SYNC_UNAVAILABLE'};
    this.save();console.error('Release refresh failed:',error.message);
   }
  })().finally(()=>{this.refreshing=null;});return this.refreshing;
 }
 async fetch(request){
  await this.ready;const path=new URL(request.url).pathname;
  if(path==='/_refresh'||!this.snapshot||this.snapshot.error||snapshotAge(this.snapshot)>SNAPSHOT_REFRESH_MS)await this.refresh();
  if(path==='/_refresh'||path==='/health'){
   const usable=snapshotUsable(this.snapshot),fresh=usable&&snapshotAge(this.snapshot)<=SNAPSHOT_REFRESH_MS&&!this.snapshot?.refreshError;
   return jsonResponse(request,{service:'turboism-release-api',status:fresh?'ready':usable?'degraded':'unavailable',synchronizedAt:this.snapshot?.syncedAt??null,mirrorConfigured:Boolean(this.env.DOWNLOADS),channels:CHANNELS.map(channel=>({channel,status:documentFor(this.snapshot,channel).status}))},usable?200:503);
  }
  const stats=/^\/v1\/downloads\/([0-9A-Za-z.-]{1,96})\.json$/.exec(path);
  if(stats){
   const record=this.snapshot?.known?.['v'+stats[1]];
   if(!snapshotUsable(this.snapshot))return jsonResponse(request,{status:'unavailable',error:{code:'STATS_UNAVAILABLE'}},503);
   if(!record?.active)return jsonResponse(request,{status:'unavailable',error:{code:'RELEASE_UNKNOWN_OR_WITHDRAWN'}},404);
   let official=null;try{official=this.counts.read(record.release.assets);}catch{console.error('Download analytics read failed');}
   return jsonResponse(request,countsDocument(stats[1],record,official));
  }
  const match=/^\/v1\/releases\/(stable|beta|nightly)\.json$/.exec(path);
  if(match){const value=documentFor(this.snapshot,match[1]);return jsonResponse(request,value,value.status==='unavailable'?503:200);}
  if(path.startsWith('/files/')){
   if(!snapshotUsable(this.snapshot))return jsonResponse(request,{error:{code:'SYNC_UNAVAILABLE'}},503);
   const key=path.slice(1);
   for(const value of Object.values(this.snapshot.known??{}))if(value.active){
    const a=value.release.assets.flatMap(x=>[x,x.checksum]).find(x=>x.key===key);if(a){
     const response=await serveFile(request,this.env.DOWNLOADS,a);
     try{await this.counts.record(request,response,a);}catch{console.error('Download analytics write failed');}
     return response;
    }
   }
   return jsonResponse(request,{error:{code:'RELEASE_WITHDRAWN_OR_UNKNOWN'}},410);
  }
  return jsonResponse(request,{error:{code:'NOT_FOUND'}},404);
 }
}
