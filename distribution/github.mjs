import { REPOSITORY,ORIGIN,CHANNELS,classify,compareVersions,parseRelease,buildReceipt } from './protocol.mjs';
export async function github(path,fetcher=fetch){
 const response=await fetcher(`https://api.github.com/repos/${REPOSITORY}${path}`,{headers:{Accept:'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28','User-Agent':'Turboism-Release-Sync'},redirect:'error',signal:AbortSignal.timeout(15000)});
 if(!response.ok)throw new Error(`GitHub HTTP ${response.status}`);
 if(!response.headers.get('content-type')?.includes('application/json'))throw new Error('GitHub returned non-JSON');
 const length=Number(response.headers.get('content-length')??0);if(length>4*1024*1024)throw new Error('GitHub document too large');
 const reader=response.body.getReader();let size=0;const chunks=[];
 try{while(true){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>4*1024*1024){await reader.cancel();throw new Error('GitHub document too large');}chunks.push(value);}}finally{reader.releaseLock();}
 const bytes=new Uint8Array(size);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}
 return {value:JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes)),next:/rel="next"/.test(response.headers.get('link')??'')};
}
async function sourceFor(tag,fetcher){
 let obj=(await github(`/git/ref/tags/${tag}`,fetcher)).value.object;
 for(let i=0;obj?.type==='tag'&&i<4;i++){if(!/^[a-f0-9]{40}$/.test(obj.sha))throw new Error('Invalid tag');obj=(await github(`/git/tags/${obj.sha}`,fetcher)).value.object;}
 if(obj?.type!=='commit'||!/^[a-f0-9]{40}$/.test(obj.sha))throw new Error('Invalid source commit');return obj.sha;
}
export function fingerprint(release){return JSON.stringify([release.sourceRevision,...release.assets.flatMap(a=>[a,a.checksum]).map(a=>[a.name,a.size,a.sha256])]);}
async function mirrors(release,env){
 if(!env.DOWNLOADS)return release;
 const marker=await env.DOWNLOADS.get(`mirrors/${release.tag}.json`);if(!marker)return release;
 let saved;try{saved=await marker.json();}catch{return release;}
 if(saved.schemaVersion!==1||saved.fingerprint!==fingerprint(release))return release;
 const assets=release.assets.flatMap(a=>[a,a.checksum]);
 const heads=await Promise.all(assets.map(a=>env.DOWNLOADS.head(a.key)));
 if(heads.some((o,i)=>!o||o.size!==assets[i].size))return release;
 return {...release,assets:release.assets.map(a=>({...a,checksum:{...a.checksum,url:`${ORIGIN}/${a.checksum.key}`},sources:[{id:'official',url:`${ORIGIN}/${a.key}`},...a.sources]}))};
}
/** Only published product releases are observed. No software builds or tags are created. */
export async function synchronize(previous,env={},fetcher=fetch){
 const raws=[];let done=false;
 for(let page=1;page<=10;page++){
  const data=await github(`/releases?per_page=100&page=${page}`,fetcher);if(!Array.isArray(data.value))throw new Error('Malformed release list');raws.push(...data.value);if(!data.next){done=true;break;}
 }if(!done)throw new Error('Release enumeration was incomplete');
 const grouped={stable:[],beta:[],nightly:[]},errors={};
 for(const raw of raws){try{const channel=classify(raw);if(channel)grouped[channel].push(raw);}catch{if(/^v\d/.test(raw.tag_name??''))errors[raw.prerelease?'beta':'stable']='INVALID_RELEASE';}}
 const known=structuredClone(previous?.known??{}),releases=[];const activeTags=new Set(raws.filter(r=>!r.draft).map(r=>r.tag_name));
 for(const record of Object.values(known))record.active=activeTags.has(record.release.tag);
 for(const channel of CHANNELS){
  const raw=grouped[channel].sort((a,b)=>compareVersions(b.tag_name.slice(1),a.tag_name.slice(1)))[0];if(!raw)continue;
  try{
   const source=await sourceFor(raw.tag_name,fetcher),receipt=buildReceipt(raw.body);let identity=null;
   if(receipt){const result=(await github(`/contents/entries/${receipt.runId}-${receipt.runAttempt}.json?ref=build-ledger`,fetcher)).value;identity=JSON.parse(atob(result.content.replace(/\s/g,'')));}
   let release=parseRelease(raw,source,identity);const key=release.tag;
   if(known[key]&&fingerprint(known[key].release)!==fingerprint(release))throw new Error('Immutable release conflict');
   release=await mirrors(release,env);known[key]={active:true,release:{tag:release.tag,sourceRevision:release.sourceRevision,assets:release.assets}};releases.push(release);
  }catch(error){errors[channel]=String(error.message??'RELEASE_INVALID').slice(0,120);}
 }
 return {schemaVersion:1,syncedAt:new Date().toISOString(),releases,known,errors};
}
