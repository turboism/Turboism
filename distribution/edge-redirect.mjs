import {chooseLatest,snapshotUsable} from './protocol.mjs';
import {hasVerifiedBytes} from './mirror.mjs';

/**
 * Cache-bypassing, count-preserving download entrypoint.
 * The edge endpoint does not record starts because cached requests bypass Worker.
 * Only explicitly opted-in callers use this route; legacy /files is unchanged.
 */
export const START_RE=/^\/v1\/downloads\/start\/(stable|beta|nightly)\/files\/([a-f0-9]{64})\/([A-Za-z0-9][A-Za-z0-9._-]*)$/;
const failure=(status)=>new Response(null,{status,headers:{'Cache-Control':'no-store'}});

export async function beginEdgeDownload(request,env,snapshot,counter){
 if(request.method!=='GET')return failure(405);
 if(env.EDGE_DOWNLOADS_ENABLED!=='true')return failure(404);
 const match=START_RE.exec(new URL(request.url).pathname);
 if(!match)return failure(404);
 if(!snapshotUsable(snapshot))return failure(503);
 const [,channel,sha,name]=match;
 const current=chooseLatest(snapshot.releases,channel);
 if(!current)return failure(404);
 const key=`files/${sha}/${name}`;
 const asset=current.assets.find(x=>x.key===key&&x.sha256===sha&&x.name===name&&x.sources?.some(s=>s.id==='official'));
 if(!asset||!env.DOWNLOADS)return failure(404);
 let stored;
 try{stored=await env.DOWNLOADS.head(key);}catch{return failure(503);}
 if(!hasVerifiedBytes(stored,asset))return failure(503);
 // A start is not a completed download. Existing dedup logic excludes automated
 // verification, prefetches, and starts from the same client within 30 minutes.
 try{await counter?.record(request,new Response(null,{status:200}),asset);}catch(error){console.error('Download start counter failed',error);}
 const url=`https://dl.turboism.dev/${channel}/${key}`;
 const response=Response.redirect(url,302);
 response.headers.set('Cache-Control','private, no-store');
 response.headers.set('Referrer-Policy','no-referrer');
 return response;
}
