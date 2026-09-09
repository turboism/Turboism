import {ORIGIN} from './protocol.mjs';
const ALLOWED_DOWNLOAD_HOSTS=new Set(['github.com','release-assets.githubusercontent.com','objects.githubusercontent.com']);
const hexadecimal=value=>value?Array.from(new Uint8Array(value),b=>b.toString(16).padStart(2,'0')).join(''):null;
export function mirrorFingerprint(release){return JSON.stringify([release.sourceRevision,...release.assets.flatMap(a=>[a,a.checksum]).map(a=>[a.name,a.size,a.sha256])]);}
export function hasVerifiedBytes(object,asset){return Boolean(object&&object.size===asset.size&&hexadecimal(object.checksums?.sha256)===asset.sha256);}
async function download(asset,fetcher){
 let url=asset.url;
 for(let i=0;i<4;i++){
  const parsed=new URL(url);
  if(parsed.protocol!=='https:'||parsed.username||parsed.password||!ALLOWED_DOWNLOAD_HOSTS.has(parsed.hostname))throw new Error('Untrusted download origin');
  const response=await fetcher(url,{headers:{'User-Agent':'Turboism-Release-Mirror',Accept:'application/octet-stream'},redirect:'manual',signal:AbortSignal.timeout(180000)});
  if([301,302,303,307,308].includes(response.status)){const location=response.headers.get('Location');await response.body?.cancel();if(!location)throw new Error('Missing release redirect');url=new URL(location,url).href;continue;}
  if(!response.ok||!response.body||Number(response.headers.get('content-length'))!==asset.size){await response.body?.cancel();throw new Error(`Download size/status mismatch: ${asset.name}`);}
  return response;
 }
 throw new Error('Too many release redirects');
}
async function sidecarText(response,maxBytes=4096){
 if(Number(response.headers.get('content-length'))>maxBytes){await response.body?.cancel();throw new Error('Checksum sidecar is too large');}
 const reader=response.body.getReader(),chunks=[];let size=0;
 try{while(true){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>maxBytes){await reader.cancel();throw new Error('Checksum sidecar is too large');}chunks.push(value);}}finally{reader.releaseLock();}
 const data=new Uint8Array(size);let offset=0;for(const c of chunks){data.set(c,offset);offset+=c.length;}
 return new TextDecoder('utf-8',{fatal:true}).decode(data);
}
/** Stream existing GitHub bytes into R2, never rebuild or overwrite a product version. */
export async function mirrorRelease(release,bucket,fetcher=fetch){
 if(!bucket)return release;
 const markerKey=`mirrors/${release.tag}.json`,fingerprint=mirrorFingerprint(release);
 const marker=await bucket.get(markerKey);let previous=null;
 if(marker){if(marker.size>24000)throw new Error('Mirror marker integrity error');previous=await marker.json();if(previous.schemaVersion!==1||previous.fingerprint!==fingerprint)throw new Error('Immutable mirror identity conflict');}
 for(const asset of release.assets.flatMap(a=>[a,a.checksum])){
  const existing=await bucket.head(asset.key);
  if(existing){if(!hasVerifiedBytes(existing,asset))throw new Error(`Existing mirror integrity conflict: ${asset.name}`);continue;}
  const response=await download(asset,fetcher);
  let body=response.body;
  if(asset.name.endsWith('.sha256')){
   const text=await sidecarText(response),binary=release.assets.find(a=>a.name+'.sha256'===asset.name);
   if(!binary||![`${binary.sha256}  ${binary.name}`,`${binary.sha256} *${binary.name}`].includes(text.trim()))throw new Error('Checksum sidecar content mismatch');
   body=text;
  }
  // R2 validates the expected digest while consuming the stream. No large buffers
  // and no mutable object keys: conditional creation cannot replace other bytes.
  try{
   await bucket.put(asset.key,body,{onlyIf:{etagDoesNotMatch:'*'},sha256:asset.sha256,httpMetadata:{contentType:asset.mediaType,cacheControl:'public, max-age=31536000, immutable'},customMetadata:{product:'turboism',version:release.version}});
  }finally{if(response.body&&!response.bodyUsed)await response.body.cancel().catch(()=>{});}
  if(!hasVerifiedBytes(await bucket.head(asset.key),asset))throw new Error(`Mirror upload integrity mismatch: ${asset.name}`);
 }
 if(!previous){
  const completed={schemaVersion:1,fingerprint,verifiedAt:new Date().toISOString()};
  const result=await bucket.put(markerKey,JSON.stringify(completed),{onlyIf:{etagDoesNotMatch:'*'},httpMetadata:{contentType:'application/json'}});
  if(!result){const concurrent=await bucket.get(markerKey);if(!concurrent||concurrent.size>24000||(await concurrent.json()).fingerprint!==fingerprint)throw new Error('Concurrent mirror marker conflict');}
 }
 return {...release,assets:release.assets.map(a=>({...a,url:`${ORIGIN}/${a.key}`,checksum:{...a.checksum,url:`${ORIGIN}/${a.checksum.key}`},sources:[{id:'official',url:`${ORIGIN}/${a.key}`},...a.sources.filter(s=>s.id!=='official')]}))};
}
