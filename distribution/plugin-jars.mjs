import {parseRange} from './files.mjs';

export const PLUGIN_INDEX_KEY = 'plugins/index-v1.json';
export const PLUGIN_API_RE = /^\/v1\/plugins\/artifacts\/([a-f0-9]{64})\/([A-Za-z0-9][A-Za-z0-9._-]{0,127}\.jar)$/;
export const PLUGIN_CDN_RE = /^\/plugins\/([a-f0-9]{64})\/([A-Za-z0-9][A-Za-z0-9._-]{0,127}\.jar)$/;
const MAX_INDEX_BYTES=1024*1024;
const noStore={'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'};
const fail=(status,message)=>new Response(message,{status,headers:noStore});
const SHA=/^[a-f0-9]{64}$/;
const NAME=/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.jar$/;
const PLUGIN_ID=/^[a-z][a-z0-9-]*(?:\.[a-z][a-z0-9-]*)+$/;
const VERSION=/^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9._-]+)?$/;

/**
 * Trusted inventory is uploaded last by the mirror publisher ONLY AFTER
 * validating the production Ed25519 catalog and each JAR's SHA256.
 * The private index is NOT provided by a browser request.
 */
export async function lookupPluginJar(bucket,sha,filename){
 if(!bucket||!SHA.test(sha)||!NAME.test(filename))return null;
 const object=await bucket.get(PLUGIN_INDEX_KEY);
 if(!object)return null;
 if(!Number.isSafeInteger(object.size)||object.size<2||object.size>MAX_INDEX_BYTES){
  await object.body?.cancel();throw new Error('Invalid plugin mirror index size');
 }
 const data=await object.text();
 if(new TextEncoder().encode(data).length>MAX_INDEX_BYTES)throw new Error('Oversized plugin mirror index');
 const index=JSON.parse(data);
 if(index?.schemaVersion!==1||!Number.isSafeInteger(index.catalogVersion)||index.catalogVersion<1||
   !SHA.test(index.signedCatalogSha256??'')||!Array.isArray(index.artifacts)||index.artifacts.length>10000){
  throw new Error('Invalid plugin mirror index');
 }
 let found=null;
 for(const item of index.artifacts){
  if(!item||!SHA.test(item.sha256??'')||!NAME.test(item.fileName??'')||
     !PLUGIN_ID.test(item.pluginId??'')||!VERSION.test(item.version??'')||
     !Number.isSafeInteger(item.size)||item.size<1||item.size>16*1024*1024){
   throw new Error('Invalid plugin artifact entry');
  }
  if(item.sha256===sha&&item.fileName===filename){
   if(found&&(found.size!==item.size||found.pluginId!==item.pluginId))throw new Error('Conflicting plugin artifact identity');
   found=item;
  }
 }
 return found;
}

export async function handlePluginJarGateway(request,env){
 const url=new URL(request.url);
 if(url.search)return fail(404,'Not found');
 const match=PLUGIN_API_RE.exec(url.pathname);
 if(!match)return fail(404,'Not found');
 if(request.method!=='GET')return fail(405,'Method not allowed');
 if(env.PLUGIN_JAR_MIRROR_ENABLED!=='true')return fail(404,'Mirror disabled');
 let asset;
 try{asset=await lookupPluginJar(env.DOWNLOADS,match[1],match[2]);}
 catch{return fail(503,'Mirror inventory unavailable');}
 if(!asset)return fail(404,'Unknown or withdrawn plugin artifact');
 const key='plugins/objects/'+match[1]+'/'+match[2];
 let object;
 try{object=await env.DOWNLOADS.head(key);}catch{return fail(503,'Mirror storage unavailable');}
 if(!object||object.size!==asset.size)return fail(503,'Mirror object unavailable');
 return new Response(null,{status:302,headers:{
  Location:'https://dl.turboism.dev/plugins/'+match[1]+'/'+match[2],
  'Cache-Control':'private, no-store',
  'Referrer-Policy':'no-referrer',
  'X-Content-Type-Options':'nosniff'
 }});
}

export async function servePluginJar(request,env){
 const url=new URL(request.url);
 if(url.search)return fail(404,'Not found');
 const match=PLUGIN_CDN_RE.exec(url.pathname);
 if(!match)return fail(404,'Not found');
 if(request.method==='OPTIONS')return new Response(null,{status:204,headers:{
  'Access-Control-Allow-Origin':'*',
  'Access-Control-Allow-Methods':'GET, HEAD, OPTIONS',
  'Access-Control-Allow-Headers':'Range, If-Range, If-None-Match',
  'Access-Control-Max-Age':'600'
 }});
 if(!['GET','HEAD'].includes(request.method))return fail(405,'Method not allowed');
 if(env.PLUGIN_JAR_MIRROR_ENABLED!=='true')return fail(404,'Mirror disabled');
 const [,sha,filename]=match;
 let asset;
 try{asset=await lookupPluginJar(env.DOWNLOADS,sha,filename);}catch{return fail(503,'Mirror inventory unavailable');}
 if(!asset)return fail(404,'Unknown or withdrawn plugin artifact');
 const key='plugins/objects/'+sha+'/'+filename;
 let head;
 try{head=await env.DOWNLOADS.head(key);}catch{return fail(503,'Mirror storage unavailable');}
 if(!head||head.size!==asset.size)return fail(503,'Unverified mirror object');
 const etag='"'+sha+'"';
 const headers=new Headers({
  'Content-Type':'application/java-archive',
  'Content-Disposition':'attachment; filename="'+filename+'"',
  'Content-Length':String(asset.size),
  'Cache-Control':'public, max-age=0, s-maxage=60, must-revalidate',
  'Cache-Tag':'turboism-plugin-'+sha,
  'ETag':etag,
  'Accept-Ranges':'bytes',
  'Access-Control-Allow-Origin':'*',
  'Access-Control-Expose-Headers':'Content-Length, Content-Range, ETag, Accept-Ranges',
  'X-Content-Type-Options':'nosniff'
 });
 if((request.headers.get('If-None-Match')??'').split(',')
   .some(value=>value.trim()==='*'||value.trim().replace(/^W\//,'')===etag)){
  headers.delete('Content-Length');return new Response(null,{status:304,headers});
 }
 if(request.method==='HEAD')return new Response(null,{status:200,headers});
 const ifRange=request.headers.get('If-Range');
 const range=parseRange(!ifRange||ifRange===etag?request.headers.get('Range'):null,asset.size);
 if(range===false){
  headers.set('Content-Range','bytes */'+asset.size);
  headers.delete('Content-Length');headers.set('Cache-Control','no-store');
  return new Response(null,{status:416,headers});
 }
 let object;
 try{object=await env.DOWNLOADS.get(key,range?{range}:undefined);}catch{return fail(503,'Mirror storage unavailable');}
 if(!object||object.size!==asset.size)return fail(503,'Mirror object unavailable');
 if(range){
  headers.set('Content-Range','bytes '+range.offset+'-'+(range.offset+range.length-1)+'/'+asset.size);
  headers.set('Content-Length',String(range.length));
 }
 return new Response(object.body,{status:range?206:200,headers});
}
