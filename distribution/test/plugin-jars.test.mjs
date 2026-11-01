import test from 'node:test';
import assert from 'node:assert/strict';
import edge from '../download-edge/worker.mjs';
import {handlePluginJarGateway,lookupPluginJar,PLUGIN_INDEX_KEY} from '../plugin-jars.mjs';

const sha='a'.repeat(64),filename='acp-0.68.0.jar',size=16;
const pluginId='dev.turboism.plugin.acp',version='0.68.0';
const asset={pluginId,version,sha256:sha,fileName:filename,size};
const index={schemaVersion:1,catalogVersion:15,signedCatalogSha256:'b'.repeat(64),artifacts:[asset]};
const key='plugins/objects/'+sha+'/'+filename;
const gatewayUrl='https://api.turboism.dev/v1/plugins/artifacts/'+sha+'/'+filename;
const cdnUrl='https://dl.turboism.dev/plugins/'+sha+'/'+filename;
const bytes=Uint8Array.from(Array.from({length:size},(_,i)=>i));

function fixture({enabled=true,inventory=index,stored=true}={}){
 const calls=[];
 const bucket={
  async get(k,options){
   calls.push(['get',k,options]);
   if(k===PLUGIN_INDEX_KEY){if(!inventory)return null;
    const value=JSON.stringify(inventory);
    return {size:new TextEncoder().encode(value).length,text:async()=>value};
   }
   if(k!==key||!stored)return null;
   const range=options?.range;
   return {size,body:range?bytes.slice(range.offset,range.offset+range.length):bytes};
  },
  async head(k){calls.push(['head',k]);return k===key&&stored?{size}:null;}
 };
 return{DOWNLOADS:bucket,PLUGIN_JAR_MIRROR_ENABLED:enabled?'true':'false',calls};
}

test('client-compatible API URL redirects only allowlisted immutable JAR',async()=>{
 const env=fixture();
 const response=await handlePluginJarGateway(new Request(gatewayUrl),env);
 assert.equal(response.status,302);
 assert.equal(response.headers.get('location'),cdnUrl);
 assert.equal(response.headers.get('cache-control'),'private, no-store');
 assert.equal((await handlePluginJarGateway(new Request(gatewayUrl+'?token=1'),env)).status,404);
 assert.equal((await handlePluginJarGateway(new Request(gatewayUrl,{method:'POST'}),env)).status,405);
 assert.equal((await handlePluginJarGateway(new Request(gatewayUrl),fixture({enabled:false}))).status,404);
 assert.equal((await handlePluginJarGateway(new Request(gatewayUrl),fixture({stored:false}))).status,503);
 assert.equal((await handlePluginJarGateway(new Request(gatewayUrl.replace(sha,'c'.repeat(64))),env)).status,404);
});

test('independent CDN serves correct bytes, range, conditional and HEAD',async()=>{
 const env=fixture();
 const full=await edge.fetch(new Request(cdnUrl),env);
 assert.equal(full.status,200);
 assert.equal(full.headers.get('content-type'),'application/java-archive');
 assert.equal(full.headers.get('content-length'),String(size));
 assert.equal(full.headers.get('etag'),'"'+sha+'"');
 assert.equal(full.headers.get('cache-tag'),'turboism-plugin-'+sha);
 assert.equal(full.headers.get('cache-control'),'public, max-age=0, s-maxage=60, must-revalidate');
 assert.deepEqual([...new Uint8Array(await full.arrayBuffer())],[...bytes]);
 const partial=await edge.fetch(new Request(cdnUrl,{headers:{Range:'bytes=2-4'}}),env);
 assert.equal(partial.status,206);
 assert.equal(partial.headers.get('content-range'),'bytes 2-4/'+size);
 assert.equal(partial.headers.get('content-length'),'3');
 assert.deepEqual([...new Uint8Array(await partial.arrayBuffer())],[2,3,4]);
 const ifRange=await edge.fetch(new Request(cdnUrl,{headers:{Range:'bytes=2-4','If-Range':'"mismatch"'}}),env);
 assert.equal(ifRange.status,200);
 assert.equal((await ifRange.arrayBuffer()).byteLength,size);
 const head=await edge.fetch(new Request(cdnUrl,{method:'HEAD'}),env);
 assert.equal(head.status,200);
 assert.equal(head.headers.get('content-length'),String(size));
 const notModified=await edge.fetch(new Request(cdnUrl,{headers:{'If-None-Match':'"'+sha+'"'}}),env);
 assert.equal(notModified.status,304);
 assert.equal(notModified.headers.get('content-length'),null);
 const invalid=await edge.fetch(new Request(cdnUrl,{headers:{Range:'bytes=100-101'}}),env);
 assert.equal(invalid.status,416);
 assert.equal(invalid.headers.get('content-range'),'bytes */'+size);
 assert.equal(invalid.headers.get('cache-control'),'no-store');
 assert.equal((await edge.fetch(new Request(cdnUrl,{method:'OPTIONS'}),env)).status,204);
});

test('unknown, withdrawn, tampered and disabled routes fail closed',async()=>{
 const disabled=fixture({enabled:false});
 assert.equal((await edge.fetch(new Request(cdnUrl),disabled)).status,404);
 assert.equal((await edge.fetch(new Request(cdnUrl+'?cachebust=1'),fixture())).status,404);
 assert.equal((await edge.fetch(new Request('https://dl.turboism.dev/plugins/'+sha+'/evil.jar'),fixture())).status,404);
 assert.equal((await edge.fetch(new Request(cdnUrl),fixture({inventory:{...index,artifacts:[]}}))).status,404);
 assert.equal((await edge.fetch(new Request(cdnUrl),fixture({inventory:{...index,signedCatalogSha256:'bad'}}))).status,503);
 assert.equal((await edge.fetch(new Request(cdnUrl),fixture({stored:false}))).status,503);
 assert.equal((await edge.fetch(new Request(cdnUrl,{method:'POST'}),fixture())).status,405);
 assert.equal(await lookupPluginJar(null,sha,filename),null);
});
