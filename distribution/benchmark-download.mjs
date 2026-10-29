#!/usr/bin/env node
/**
 * Real (fully consumed) Turboism mirror throughput benchmark.
 * From a mainland network with VPN/proxy disabled:
 *   node distribution/benchmark-download.mjs --runs=3 --mib=8 --label=unicom-shanghai
 *   node distribution/benchmark-download.mjs --runs=2 --full --label=mobile-guangzhou
 *
 * The legacy path is an uncacheable DO/R2 download; accelerated is a counted
 * (but verification-excluded) API start + 302 redirect to Cloudflare cache.
 * Output is JSON. Every successful byte is actually consumed and validated.
 */
import {createHash} from 'node:crypto';

function option(name, fallback) {
 const value=process.argv.find(arg=>arg.startsWith('--'+name+'='));
 return value===undefined?fallback:value.slice(name.length+3);
}
const full=process.argv.includes('--full');
const runs=Number(option('runs','3'));
const mib=Number(option('mib','8'));
const label=option('label','unspecified');
const channel=option('channel','stable');
if(process.argv.includes('--help')){
 console.log('Options: --runs=3 (1..5), --mib=8 (1..32), --full, --channel=stable|beta|nightly, --label=name');
 process.exit(0);
}
if(!Number.isInteger(runs)||runs<1||runs>5)throw Error('runs must be 1..5');
if(!Number.isInteger(mib)||mib<1||mib>32)throw Error('mib must be 1..32');
if(!['stable','beta','nightly'].includes(channel))throw Error('invalid channel');
const api='https://api.turboism.dev',cdn='https://dl.turboism.dev';
const manifestResponse=await fetch(api+'/v1/releases/'+channel+'.json',{signal:AbortSignal.timeout(20000)});
if(manifestResponse.status!==200||!manifestResponse.headers.get('content-type')?.includes('application/json'))throw Error('release metadata unavailable');
const doc=await manifestResponse.json();
if(doc.status!=='ready'||doc.channel!==channel||!Array.isArray(doc.release?.assets))throw Error('release not published');
const asset=doc.release.assets.find(x=>x.name?.endsWith('.exe'));
if(!asset||asset.key!=='files/'+asset.sha256+'/'+asset.name||!/^[a-f0-9]{64}$/.test(asset.sha256)||!Number.isSafeInteger(asset.size))throw Error('unverified artifact');
const official=asset.sources?.find(x=>x.id==='official')?.url;
if(official!==api+'/'+asset.key)throw Error('unsafe source');
const expectedBytes=full?asset.size:Math.min(mib*1048576,asset.size);
const headerRange=full?null:'bytes=0-'+String(expectedBytes-1);
const edgeUrl=cdn+'/'+channel+'/'+asset.key;
const startUrl=api+'/v1/downloads/start/'+channel+'/'+asset.key;

async function consume(source,iteration){
 const headers=new Headers();
 if(headerRange)headers.set('Range',headerRange);
 if(source==='official')headers.set('X-Turboism-Download-Purpose','verification');
 const started=performance.now();
 let response,redirectMs=null;
 try{
  if(source==='accelerated'){
   const start=await fetch(startUrl,{redirect:'manual',headers:{'X-Turboism-Download-Purpose':'verification'},signal:AbortSignal.timeout(25000)});
   redirectMs=+(performance.now()-started).toFixed(2);
   if(start.status!==302||start.headers.get('location')!==edgeUrl)throw Error('invalid accelerated redirect: '+start.status);
   await start.body?.cancel();
   response=await fetch(edgeUrl,{headers,signal:AbortSignal.timeout(120000)});
  }else{
   response=await fetch(official,{headers,signal:AbortSignal.timeout(120000)});
  }
  const responseAt=performance.now();
  const expectedStatus=full?200:206;
  if(response.status!==expectedStatus)throw Error('HTTP '+response.status+' expected '+expectedStatus);
  if(!full&&response.headers.get('content-range')!=='bytes 0-'+String(expectedBytes-1)+'/'+asset.size)throw Error('content-range mismatch');
  if(Number(response.headers.get('content-length'))!==expectedBytes)throw Error('content-length mismatch');
  if(!response.body)throw Error('missing response stream');
  let received=0;
  const digest=createHash('sha256');
  for await(const chunk of response.body){received+=chunk.byteLength;if(received>expectedBytes)throw Error('received too many bytes');if(full)digest.update(chunk);}
  const ended=performance.now(),ms=+(ended-started).toFixed(2);
  if(received!==expectedBytes)throw Error('truncated response '+received+'/'+expectedBytes);
  if(full&&digest.digest('hex')!==asset.sha256)throw Error('SHA-256 mismatch');
  return {source,iteration,ok:true,http:response.status,bytes:received,shaVerified:full,ms,redirectMs,ttfbApproxMs:+(responseAt-started).toFixed(2),MiBs:+(received/1048576/(ms/1000)).toFixed(3),cfCacheStatus:response.headers.get('cf-cache-status'),server:response.headers.get('server')};
 }catch(error){
  return {source,iteration,ok:false,error:String(error?.message??error),ms:+(performance.now()-started).toFixed(2)};
 }
}
const results=[];
for(let i=0;i<runs;i++){
 for(const source of (i%2===0?['official','accelerated']:['accelerated','official'])){
  const result=await consume(source,i+1);
  results.push(result);
  console.error(source+' #'+(i+1)+': '+(result.ok?(result.MiBs+' MiB/s, '+result.ms+' ms, bytes='+result.bytes):'FAIL '+result.error));
 }
}
const group=source=>{
 const records=results.filter(x=>x.source===source),good=records.filter(x=>x.ok);
 const times=good.map(x=>x.ms).sort((a,b)=>a-b);
 return {successful:good.length,attempts:records.length,medianMs:times.length?times[Math.floor(times.length/2)]:null,errors:records.filter(x=>!x.ok).map(x=>x.error)};
};
console.log(JSON.stringify({date:new Date().toISOString(),label,channel,version:doc.release.version,artifact:asset.name,expectedBytes,fullSha256Validated:full,comparison:{official:group('official'),accelerated:group('accelerated')},results},null,2));
if(results.some(x=>!x.ok))process.exitCode=1;
