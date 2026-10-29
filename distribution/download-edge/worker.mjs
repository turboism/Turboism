import {hasVerifiedBytes} from '../mirror.mjs';
import {parseRange} from '../files.mjs';

const channels=new Set(['stable','beta','nightly']);
const FILE_RE=/^\/(stable|beta|nightly)\/files\/([a-f0-9]{64})\/([A-Za-z0-9][A-Za-z0-9._-]*)$/;
const cacheControl='public, max-age=0, s-maxage=60, must-revalidate';
const noStore={'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'};
const fail=(status,message)=>new Response(message,{status,headers:noStore});
const shaRegex=/^[a-f0-9]{64}$/;

export function edgeAssetPath(channel,asset){
 if(!channels.has(channel)||!shaRegex.test(asset?.sha256??'')||!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(asset?.name??''))return null;
 if(asset.key!==`files/${asset.sha256}/${asset.name}`)return null;
 return `/${channel}/${asset.key}`;
}

async function activeAsset(env,channel,sha,name){
 if(!env.RELEASE_API?.fetch)return null;
 const response=await env.RELEASE_API.fetch(new Request(`https://api.turboism.dev/v1/releases/${channel}.json`,{headers:{Accept:'application/json'}}));
 if(response.status!==200||!response.headers.get('content-type')?.includes('application/json')){await response.body?.cancel();return null;}
 if(Number(response.headers.get('content-length')??0)>256*1024){await response.body?.cancel();return null;}
 const raw=await response.text();
 if(raw.length>256*1024)return null;
 const doc=JSON.parse(raw);
 if(doc.channel!==channel||doc.status!=='ready'||!Array.isArray(doc.release?.assets))return null;
 const asset=doc.release.assets.find(x=>x.sha256===sha&&x.name===name&&edgeAssetPath(channel,x)===`/${channel}/files/${sha}/${name}`);
 if(!asset?.sources?.some(x=>x.id==='official'&&x.url===`https://api.turboism.dev/${asset.key}`))return null;
 if(!Number.isSafeInteger(asset.size)||asset.size<1||asset.size>2**31)return null;
 if(!['application/octet-stream','application/zip'].includes(asset.mediaType))return null;
 return asset;
}

/**
 * Independent, read-only byte delivery worker.
 * - Source bucket remains PRIVATE: no R2 Custom Domain and no r2.dev exposure.
 * - Only current, independently verified release assets are accessible.
 * - This endpoint is deliberately NOT the analytics entrypoint.
 * - The Worker has a short 60s cache lifetime to bound delayed withdrawal;
 *   immediately purge a withdrawn path before relying on its inaccessibility.
 */
export default {
 async fetch(request,env){
  const requestUrl=new URL(request.url);
  // Avoid unbounded query-string cache keys and custom host override tricks.
  if(requestUrl.search)return fail(404,'Not found');
  const match=FILE_RE.exec(requestUrl.pathname);
  if(!match)return fail(404,'Not found');
  if(request.method==='OPTIONS')return new Response(null,{status:204,headers:{'Access-Control-Allow-Origin':'*','Access-Control-Allow-Methods':'GET, HEAD, OPTIONS','Access-Control-Allow-Headers':'Range, If-Range, If-None-Match'}});
  if(!['GET','HEAD'].includes(request.method))return fail(405,'Method not allowed');
  if(!env.DOWNLOADS)return fail(503,'Mirror unavailable');
  const [,channel,sha,name]=match;
  let asset;
  try{asset=await activeAsset(env,channel,sha,name);}catch{return fail(503,'Verification unavailable');}
  if(!asset)return fail(404,'Not published');
  let head;
  try{head=await env.DOWNLOADS.head(asset.key);}catch{return fail(503,'Mirror unavailable');}
  if(!hasVerifiedBytes(head,asset))return fail(503,'Unverified mirror');

  const etag=`"${sha}"`;
  const headers=new Headers({
   'Content-Type':asset.mediaType,
   'Content-Disposition':`attachment; filename="${name}"`,
   'Content-Length':String(asset.size),
   'Accept-Ranges':'bytes',
   'ETag':etag,
   'X-Content-Type-Options':'nosniff',
   'Access-Control-Allow-Origin':'*',
   'Access-Control-Expose-Headers':'ETag, Content-Length, Content-Range, Accept-Ranges',
   'Cache-Control':cacheControl,
   'Cache-Tag':`turboism-${channel}-${sha}`,
  });
  if((request.headers.get('if-none-match')??'').split(',').some(x=>x.trim()==='*'||x.trim().replace(/^W\//,'')===etag)){
   headers.delete('Content-Length');
   return new Response(null,{status:304,headers});
  }
  if(request.method==='HEAD')return new Response(null,{status:200,headers});

  const ifRange=request.headers.get('if-range');
  const range=parseRange(!ifRange||ifRange===etag?request.headers.get('range'):null,asset.size);
  if(range===false){headers.set('Content-Range',`bytes */${asset.size}`);headers.delete('Content-Length');headers.set('Cache-Control','no-store');return new Response(null,{status:416,headers});}
  let object;
  try{object=await env.DOWNLOADS.get(asset.key,range?{range}:undefined);}catch{return fail(503,'Mirror unavailable');}
  if(!object||!hasVerifiedBytes(object,asset))return fail(503,'Unverified mirror');
  if(range){headers.set('Content-Range',`bytes ${range.offset}-${range.offset+range.length-1}/${asset.size}`);headers.set('Content-Length',String(range.length));}
  return new Response(object.body,{status:range?206:200,headers});
 },
};
