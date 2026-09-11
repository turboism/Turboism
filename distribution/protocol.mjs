/** Public distribution contract. No dependency on the retired Updates service. */
export const REPOSITORY='turboism/Turboism';
export const ORIGIN='https://api.turboism.dev';
export const CHANNELS=['stable','beta','nightly'];
const sha=/^[a-f0-9]{64}$/;
function require(value,message){if(!value)throw new Error(message);}
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
 const expected=names.flatMap(n=>[n,n+'.sha256']);require(Array.isArray(raw.assets)&&raw.assets.length===expected.length,'Incomplete canonical assets');
 const assets=new Map();
 for(const a of raw.assets){
  require(expected.includes(a.name)&&!assets.has(a.name)&&a.state==='uploaded','Unexpected or duplicate asset');
  require(Number.isSafeInteger(a.id)&&a.id>0&&Number.isSafeInteger(a.size)&&a.size>0&&a.size<=2**31,'Invalid asset size or ID');
  require(typeof a.digest==='string'&&a.digest.startsWith('sha256:')&&sha.test(a.digest.slice(7)),'Missing SHA-256 digest');
  const url=`https://github.com/${REPOSITORY}/releases/download/${raw.tag_name}/${a.name}`;
  require(a.browser_download_url===url,'Untrusted asset URL');
  const digest=a.digest.slice(7),key=`files/${digest}/${a.name}`;
  assets.set(a.name,{name:a.name,key,url,mediaType:a.name.endsWith('.zip')?'application/zip':'application/octet-stream',size:a.size,sha256:digest,assetId:a.id,downloadCount:Number.isSafeInteger(a.download_count)&&a.download_count>=0?a.download_count:null});
 }
 return {releaseId:raw.id,version,tag:raw.tag_name,channel,buildNumber:receipt?.buildNumber??null,sourceRevision,publishedAt:new Date(raw.published_at).toISOString(),githubReleaseUrl:raw.html_url,changelogUrl:raw.html_url,
  provenance:receipt?{repository:REPOSITORY,workflow:'.github/workflows/release.yml',runId:receipt.runId,runAttempt:receipt.runAttempt}:{repository:REPOSITORY,workflow:null,runId:null,runAttempt:null},
  compatibility:null,notes:String(raw.body??'').replace(/<!-- turboism-build-v1 [\s\S]*? -->/g,'').trim().slice(0,24000),
  assets:names.map((name,i)=>({...assets.get(name),kind:['windows-installer','java-installer','full','lite'][i],platform:i===0?'windows':null,architecture:null,checksum:assets.get(name+'.sha256'),sources:[{id:'github',url:assets.get(name).url}]}))};
}
export function chooseLatest(releases,channel){
 require(CHANNELS.includes(channel),'Unknown channel');
 return releases.filter(r=>r.channel===channel).sort((a,b)=>compareVersions(b.version,a.version))[0]??null;
}
export function documentFor(snapshot,channel){
 require(CHANNELS.includes(channel),'Unknown channel');
 const common={schemaVersion:1,channel,metadataUrl:`${ORIGIN}/v1/releases/${channel}.json`};
 if(!snapshot||snapshot.error||snapshot.errors?.[channel]||Date.now()-Date.parse(snapshot.syncedAt)>30*60*1000)return {...common,status:'unavailable',release:null,error:{code:'SYNC_UNAVAILABLE',retryAfterSeconds:60}};
 const release=chooseLatest(snapshot.releases,channel);
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
