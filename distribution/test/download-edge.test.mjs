import test from 'node:test';
import assert from 'node:assert/strict';
import edge,{edgeAssetPath} from '../download-edge/worker.mjs';
import {beginEdgeDownload} from '../edge-redirect.mjs';

const sha='aa'.repeat(32),name='TurboismInstaller-1.2.3.exe',channel='stable',size=8;
const asset={
 key:`files/${sha}/${name}`,sha256:sha,name,size,mediaType:'application/octet-stream',
 sources:[{id:'official',url:`https://api.turboism.dev/files/${sha}/${name}`},{id:'github',url:'https://github.com/turboism/Turboism/releases/download/v1.2.3/'+name}],
};
const url=`https://dl.turboism.dev/stable/files/${sha}/${name}`;
const startUrl=`https://api.turboism.dev/v1/downloads/start/stable/files/${sha}/${name}`;
const bytes=Uint8Array.from([0,1,2,3,4,5,6,7]);
const checksum=Uint8Array.from(Buffer.from(sha,'hex')).buffer;
function env(overrides={}){
 const calls=[];
 return{
  calls,
  DOWNLOADS:{
   async head(key){calls.push(['head',key]);return{size,checksums:{sha256:checksum}};},
   async get(key,opts){calls.push(['get',key,opts]);const range=opts?.range;return{size,checksums:{sha256:checksum},body:range?bytes.slice(range.offset,range.offset+range.length):bytes};},
  },
  RELEASE_API:{async fetch(request){calls.push(['manifest',new URL(request.url).pathname]);return new Response(JSON.stringify({channel,status:'ready',release:{assets:[asset]}}),{headers:{'Content-Type':'application/json'}});}},
  ...overrides,
 };
}
function snapshot(){return{syncedAt:new Date().toISOString(),releases:[{version:'1.2.3',channel,assets:[asset]}]};}

test('edge allows ONLY latest verified release URLs from a private bucket',async()=>{
 assert.equal(edgeAssetPath(channel,asset),'/stable/'+asset.key);
 assert.equal(edgeAssetPath(channel,{...asset,key:'files/other/'+name}),null);
 const e=env();
 const response=await edge.fetch(new Request(url),e);
 assert.equal(response.status,200);
 assert.equal(response.headers.get('cache-control'),'public, max-age=0, s-maxage=60, must-revalidate');
 assert.equal(response.headers.get('content-length'),String(size));
 assert.equal(response.headers.get('etag'),`"${sha}"`);
 assert.deepEqual([...new Uint8Array(await response.arrayBuffer())],[...bytes]);
 assert.equal(e.calls.filter(c=>c[0]==='get').length,1);
 assert.equal((await edge.fetch(new Request('https://dl.turboism.dev/files/'+sha+'/'+name),e)).status,404);
 assert.equal((await edge.fetch(new Request(url+'?cacheBust=123'),e)).status,404);
 assert.equal((await edge.fetch(new Request(url,{method:'POST'}),e)).status,405);
});
test('Range, If-Range, 304, HEAD and 416 keep correct wire semantics',async()=>{
 const e=env();
 const partial=await edge.fetch(new Request(url,{headers:{Range:'bytes=2-4', 'If-Range':`"${sha}"`}}),e);
 assert.equal(partial.status,206);
 assert.equal(partial.headers.get('content-range'),'bytes 2-4/8');
 assert.equal(partial.headers.get('content-length'),'3');
 assert.deepEqual([...new Uint8Array(await partial.arrayBuffer())],[2,3,4]);
 const mismatch=await edge.fetch(new Request(url,{headers:{Range:'bytes=2-4', 'If-Range':'"different"'}}),e);
 assert.equal(mismatch.status,200);
 assert.equal((await mismatch.arrayBuffer()).byteLength,8);
 const notModified=await edge.fetch(new Request(url,{headers:{'If-None-Match':`"${sha}"`}}),e);
 assert.equal(notModified.status,304);
 assert.equal(notModified.headers.get('content-length'),null);
 const head=await edge.fetch(new Request(url,{method:'HEAD'}),e);
 assert.equal(head.status,200);
 assert.equal(head.headers.get('content-length'),'8');
 assert.equal(await head.text(),'');
 const invalid=await edge.fetch(new Request(url,{headers:{Range:'bytes=100-101'}}),e);
 assert.equal(invalid.status,416);
 assert.equal(invalid.headers.get('content-range'),'bytes */8');
 assert.equal(invalid.headers.get('cache-control'),'no-store');
});
test('edge fails closed for withdrawn, noncurrent, invalid, and unverified artifacts',async()=>{
 const noncurrent=env({RELEASE_API:{async fetch(){return new Response(JSON.stringify({status:'not_published',channel}),{headers:{'content-type':'application/json'}});}}});
 assert.equal((await edge.fetch(new Request(url),noncurrent)).status,404);
 const wrongSource=env({RELEASE_API:{async fetch(){return new Response(JSON.stringify({status:'ready',channel,release:{assets:[{...asset,sources:[{id:'github'}]}]}}),{headers:{'content-type':'application/json'}});}}});
 assert.equal((await edge.fetch(new Request(url),wrongSource)).status,404);
 const missing=env({DOWNLOADS:{async head(){return null;}}});
 assert.equal((await edge.fetch(new Request(url),missing)).status,503);
 const badDigest=env({DOWNLOADS:{async head(){return{size,checksums:{sha256:new Uint8Array(32).buffer}};}}});
 assert.equal((await edge.fetch(new Request(url),badDigest)).status,503);
 assert.equal((await edge.fetch(new Request(url),env({RELEASE_API:null}))).status,404);
});
test('opt-in API start counts before redirect; disabled mode and withdrawals remain closed',async()=>{
 const calls=[];const counter={async record(request,response,file){calls.push({method:request.method,status:response.status,key:file.key});}};
 const e=env({EDGE_DOWNLOADS_ENABLED:'true'});
 const response=await beginEdgeDownload(new Request(startUrl),e,snapshot(),counter);
 assert.equal(response.status,302);
 assert.equal(response.headers.get('Location'),url);
 assert.equal(response.headers.get('Cache-Control'),'private, no-store');
 assert.deepEqual(calls,[{method:'GET',status:200,key:asset.key}]);
 assert.equal((await beginEdgeDownload(new Request(startUrl),env(),snapshot(),counter)).status,404);
 assert.equal((await beginEdgeDownload(new Request(startUrl,{method:'HEAD'}),e,snapshot(),counter)).status,405);
 assert.equal((await beginEdgeDownload(new Request(startUrl),e,{syncedAt:new Date().toISOString(),releases:[]},counter)).status,404);
 assert.equal((await beginEdgeDownload(new Request(startUrl),e,{syncedAt:'2020-01-01T00:00:00Z',releases:[{version:'1.2.3',channel,assets:[asset]}]},counter)).status,503);
});
