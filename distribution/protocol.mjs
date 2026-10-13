import {releaseNotes} from './release-notes.mjs';
import compatibilityOverrides from './release-compatibility-overrides.json' with {type:'json'};
/** Public distribution contract. No dependency on the retired Updates service. */
export const REPOSITORY='turboism/Turboism';
export const ORIGIN='https://api.turboism.dev';
export const CHANNELS=['stable','beta','nightly'];
export const SNAPSHOT_REFRESH_MS=15*60*1000;
export const SNAPSHOT_MAX_STALE_MS=24*60*60*1000;
const sha=/^[a-f0-9]{64}$/;
const cubismVersion=/^5\.\d{1,2}\.\d{2}$/;
const platformId=/^[a-z0-9]+-[a-z0-9]+$/;
function require(value,message){if(!value)throw new Error(message);}
function compareCubismVersions(a,b){
 const x=a.split('.').map(Number),y=b.split('.').map(Number);
 return x[0]-y[0]||x[1]-y[1]||x[2]-y[2];
}
function validMatrixUrl(value){
 if(typeof value!=='string'||value.length>256)return false;
 try{
  const u=new URL(value);
  return u.protocol==='https:'&&u.hostname==='turboism.dev'&&!u.port&&!u.username&&!u.password&&u.pathname.startsWith('/docs/');
 }catch{return false;}
}
function normalizeCompatibility(value,requireSchema=false){
 if(!value||typeof value!=='object'||Array.isArray(value))return null;
 if(requireSchema&&value.schemaVersion!==1)return null;
 if('schemaVersion' in value&&value.schemaVersion!==1)return null;
 if(!Object.keys(value).every(k=>['schemaVersion','cubismEditor','platforms','matrixUrl'].includes(k)))return null;
 const ce=value.cubismEditor;
 if(!ce||typeof ce!=='object'||Array.isArray(ce))return null;
 if(Object.keys(ce).length!==2||!Array.isArray(ce.versions)||!Array.isArray(ce.ranges))return null;
 if(ce.versions.length<1||ce.versions.length>64||!ce.versions.every(v=>typeof v==='string'&&cubismVersion.test(v)))return null;
 for(let i=1;i<ce.versions.length;i++)if(compareCubismVersions(ce.versions[i-1],ce.versions[i])>=0)return null;
 const versionSet=new Set(ce.versions);
 if(ce.ranges.length<1||ce.ranges.length>ce.versions.length)return null;
 for(let i=0;i<ce.ranges.length;i++){
  const r=ce.ranges[i];
  if(!r||typeof r!=='object'||Array.isArray(r)||Object.keys(r).length!==2||typeof r.from!=='string'||typeof r.to!=='string')return null;
  if(!versionSet.has(r.from)||!versionSet.has(r.to)||compareCubismVersions(r.from,r.to)>0)return null;
  if(i>0&&compareCubismVersions(ce.ranges[i-1].to,r.from)>=0)return null;
 }
 if(ce.ranges[0].from!==ce.versions[0]||ce.ranges.at(-1).to!==ce.versions.at(-1))return null;
 if(!ce.versions.every(v=>ce.ranges.some(r=>compareCubismVersions(r.from,v)<=0&&compareCubismVersions(v,r.to)<=0)))return null;
 if(!Array.isArray(value.platforms)||value.platforms.length<1||value.platforms.length>8)return null;
 if(!value.platforms.every(p=>typeof p==='string'&&p.length<=32&&platformId.test(p))||new Set(value.platforms).size!==value.platforms.length)return null;
 if(!validMatrixUrl(value.matrixUrl))return null;
 return {cubismEditor:{versions:[...ce.versions],ranges:ce.ranges.map(r=>({from:r.from,to:r.to}))},platforms:[...value.platforms],matrixUrl:value.matrixUrl};
}
export function releaseCompatibility(raw){
 const matches=[...String(raw?.body??'').matchAll(/<!-- turboism-compatibility-v1 ([\s\S]*?) -->/g)];
 if(matches.length===1&&matches[0][1].length<=4096){
  try{const parsed=normalizeCompatibility(JSON.parse(matches[0][1]),true);if(parsed)return parsed;}catch{/* Invalid optional metadata falls back to null. */}
 }
 if(matches.length>0)return null;
 const tag=String(raw?.tag_name??'');
 const wildcard=/^v\d+\.\d+\.\d+-0\.nightly\.[1-9]\d*$/.test(tag)?tag.replace(/\.\d+$/,'.*'):null;
 const saved=compatibilityOverrides[tag]??(wildcard?compatibilityOverrides[wildcard]:null);
 return saved?normalizeCompatibility(saved,false):null;
}
export function versionParts(version) {
 require(typeof version==='string' && version.length<=96,'Invalid version');
 const m=/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/.exec(version);
 require(m && !(m[4]??'').split('.').some(x=>/^0\d+$/.test(x)),'Invalid SemVer');return {core:m.slice(1,4),pre:m[4]?.split('.')??[]};
}
const numeric=(a,b)=>a.length===b.length?(a===b?0:a<b?-1:1):a.length<b.length?-1:1;
export function compareVersions(a,b){
 const x=versionParts(a),y=versionParts(b);
 for(let i=0;i<3;i++){const c=numeric(x.core[i],y.core[i]);if(c)return c;}
 if(!x.pre.length||!y.pre.length)return x.pre.length===y.pre.length?0:x.pre.length?-1:1;
 for(let i=0;i<Math.max(x.pre.length,y.pre.length);i++){
  const a=x.pre[i],b=y.pre[i];if(a===undefined||b===undefined)return a===undefined?-1:1;if(a===b)continue;
  const an=/^\d+$/.test(a),bn=/^\d+$/.test(b);if(an&&bn)return numeric(a,b);if(an!==bn)return an?-1:1;return a<b?-1:1;
 }return 0;
}
export function classify(release){
 if(release.draft===true||!/^v\d/.test(release.tag_name??''))return null;
 const {pre}=versionParts(release.tag_name.slice(1));
 require(release.prerelease===Boolean(pre.length),'Release channel/prerelease mismatch');
 return !pre.length?'stable':pre.includes('nightly')?'nightly':'beta';
}
export function buildReceipt(body){
 const matches=[...String(body??'').matchAll(/<!-- turboism-build-v1 ([\s\S]*?) -->/g)];
 if(!matches.length)return null;require(matches.length===1,'Duplicate build receipt');
 const r=JSON.parse(matches[0][1]);
 require(r.schemaVersion===1&&Number.isSafeInteger(r.buildNumber)&&r.buildNumber>0&&/^[a-f0-9]{40}$/.test(r.sourceRevision)&&/^[1-9]\d{0,19}$/.test(r.runId)&&Number.isSafeInteger(r.runAttempt)&&r.runAttempt>0&&CHANNELS.includes(r.channel),'Invalid build receipt');
 versionParts(r.version);return r;
}
export function parseRelease(raw,sourceRevision,verifiedIdentity=null){
 const channel=classify(raw);require(channel,'Not a product release');const version=raw.tag_name.slice(1);
 // Build metadata remains a separate field; URLs use an immutable, canonical tag.
 require(!version.includes('+'),'Build metadata belongs in buildNumber, not the published tag');
 require(/^[a-f0-9]{40}$/.test(sourceRevision),'Invalid resolved source SHA');
 require(Number.isSafeInteger(raw.id)&&raw.id>0,'Invalid release ID');
 require(raw.html_url===`https://github.com/${REPOSITORY}/releases/tag/${raw.tag_name}`,'Untrusted release URL');
 require(typeof raw.published_at==='string'&&Number.isFinite(Date.parse(raw.published_at)),'Invalid publication date');
 const receipt=buildReceipt(raw.body);
 if(receipt){require(verifiedIdentity&&Object.keys(receipt).every(k=>receipt[k]===verifiedIdentity[k]),'Unverified build identity');require(receipt.sourceRevision===sourceRevision&&receipt.version===version&&receipt.channel===channel,'Build identity source/version mismatch');}
 const names=[`TurboismInstaller-${version}.exe`,`TurboismInstaller-${version}.jar`,`turboism-${version}-full.zip`,`turboism-${version}-lite.zip`];
 const expected=names.flatMap(n=>[n,n+'.sha256']);
 // Releases also carry standalone developer jars (SDK and event-processor) for
 // plugin developers. They pass the same integrity checks but stay out of the
 // mirrored files API; older releases may have SDK-only or neither.
 const sdkPair=[`turboism-sdk-${version}.jar`,`turboism-sdk-${version}.jar.sha256`];
 const processorPair=[`turboism-event-processor-${version}.jar`,`turboism-event-processor-${version}.jar.sha256`];
 const developer=[...sdkPair,...processorPair];
 const allowed=new Set([...expected,...developer]);
 require(Array.isArray(raw.assets)&&raw.assets.length<=allowed.size,'Unexpected asset count');
 const seen=new Set(),assets=new Map();
 for(const a of raw.assets){
  require(allowed.has(a.name)&&!seen.has(a.name)&&a.state==='uploaded','Unexpected or duplicate asset');
  seen.add(a.name);
  require(Number.isSafeInteger(a.id)&&a.id>0&&Number.isSafeInteger(a.size)&&a.size>0&&a.size<=2**31,'Invalid asset size or ID');
  require(typeof a.digest==='string'&&a.digest.startsWith('sha256:')&&sha.test(a.digest.slice(7)),'Missing SHA-256 digest');
  const url=`https://github.com/${REPOSITORY}/releases/download/${raw.tag_name}/${a.name}`;
  require(a.browser_download_url===url,'Untrusted asset URL');
  if(!expected.includes(a.name))continue;
  const digest=a.digest.slice(7),key=`files/${digest}/${a.name}`;
  assets.set(a.name,{name:a.name,key,url,mediaType:a.name.endsWith('.zip')?'application/zip':'application/octet-stream',size:a.size,sha256:digest,assetId:a.id,downloadCount:Number.isSafeInteger(a.download_count)&&a.download_count>=0?a.download_count:null});
 }
 require(expected.every(n=>seen.has(n)),'Incomplete canonical assets');
 const hasSdk=sdkPair.every(n=>seen.has(n)),noSdk=sdkPair.every(n=>!seen.has(n));
 const hasProcessor=processorPair.every(n=>seen.has(n)),noProcessor=processorPair.every(n=>!seen.has(n));
 require((hasSdk||noSdk)&&(hasProcessor||noProcessor)&&(!hasProcessor||hasSdk),'Partial developer asset set');
 return {releaseId:raw.id,version,tag:raw.tag_name,channel,buildNumber:receipt?.buildNumber??null,sourceRevision,publishedAt:new Date(raw.published_at).toISOString(),githubReleaseUrl:raw.html_url,changelogUrl:raw.html_url,
  provenance:receipt?{repository:REPOSITORY,workflow:'.github/workflows/release.yml',runId:receipt.runId,runAttempt:receipt.runAttempt}:{repository:REPOSITORY,workflow:null,runId:null,runAttempt:null},
  compatibility:releaseCompatibility(raw),...releaseNotes(raw,sourceRevision),
  assets:names.map((name,i)=>({...assets.get(name),kind:['windows-installer','java-installer','full','lite'][i],platform:i===0?'windows':null,architecture:null,checksum:assets.get(name+'.sha256'),sources:[{id:'github',url:assets.get(name).url}]}))};
}
export function chooseLatest(releases,channel){
 require(CHANNELS.includes(channel),'Unknown channel');
 return releases.filter(r=>r.channel===channel).sort((a,b)=>compareVersions(b.version,a.version))[0]??null;
}
export function snapshotAge(snapshot){const t=Date.parse(snapshot?.syncedAt??'');return Number.isFinite(t)?Date.now()-t:Infinity;}
export function snapshotUsable(snapshot){return Boolean(snapshot&&!snapshot.error&&snapshotAge(snapshot)<=SNAPSHOT_MAX_STALE_MS);}
export function documentFor(snapshot,channel){
 require(CHANNELS.includes(channel),'Unknown channel');
 const common={schemaVersion:1,channel,metadataUrl:`${ORIGIN}/v1/releases/${channel}.json`};
 if(!snapshotUsable(snapshot))return {...common,status:'unavailable',release:null,error:{code:'SYNC_UNAVAILABLE',retryAfterSeconds:60}};
 const selected=chooseLatest(snapshot.releases,channel);
 if(snapshot.errors?.[channel]&&!selected)return {...common,status:'unavailable',release:null,error:{code:'SYNC_UNAVAILABLE',retryAfterSeconds:60}};
 let release=selected&&!selected.notesByLanguage?{...selected,...releaseNotes({id:selected.releaseId,tag_name:selected.tag,body:selected.notes},selected.sourceRevision)}:selected;
 if(release&&release.compatibility===null){const compat=releaseCompatibility({tag_name:release.tag,body:release.notes});if(compat)release={...release,compatibility:compat};}
 return {...common,status:release?'ready':'not_published',updatedAt:snapshot.syncedAt,source:{repository:REPOSITORY,synchronizedAt:snapshot.syncedAt},release};
}
export async function jsonResponse(request,value,status=200){
 const headers=new Headers({'Content-Type':'application/json; charset=utf-8','Access-Control-Allow-Origin':'*','Access-Control-Allow-Methods':'GET, HEAD, OPTIONS','Access-Control-Allow-Headers':'If-None-Match, Range, If-Range','Access-Control-Expose-Headers':'ETag, Retry-After, Content-Length, Content-Range, Accept-Ranges','X-Content-Type-Options':'nosniff'});
 const body=JSON.stringify(value);
 if(status>=400){headers.set('Cache-Control','no-store');if(status===503)headers.set('Retry-After','60');return new Response(request.method==='HEAD'?null:body,{status,headers});}
 const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(body));const etag='"'+Array.from(new Uint8Array(digest),n=>n.toString(16).padStart(2,'0')).join('')+'"';
 headers.set('ETag',etag);headers.set('Cache-Control','public, max-age=0, s-maxage=60, must-revalidate');
 const match=(request.headers.get('If-None-Match')??'').split(',').some(v=>v.trim()==='*'||v.trim().replace(/^W\//,'')===etag);
 return new Response(match||request.method==='HEAD'?null:body,{status:match?304:status,headers});
}
