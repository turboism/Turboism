import test from 'node:test';import assert from 'node:assert/strict';import worker from '../worker.mjs';import { serveFile } from '../files.mjs';
test('public callers cannot reach internal refresh or invoke sync without GitHub identity',async()=>{
 const env={REGISTRY:{idFromName(){throw new Error('unauthorized registry invocation');}}};
 assert.equal((await worker.fetch(new Request('https://api.turboism.dev/_refresh'),env)).status,404);
 assert.equal((await worker.fetch(new Request('https://api.turboism.dev/v1/sync',{method:'POST'}),env)).status,401);
 assert.equal((await worker.fetch(new Request('https://api.turboism.dev/v1/releases/stable.json',{method:'POST'}),env)).status,405);
 assert.equal((await worker.fetch(new Request('https://api.turboism.dev/',{method:'OPTIONS'}),env)).status,204);
});
test('mirror responses stream exact ranges and never substitute a GitHub proxy',async()=>{
 const bytes=new Uint8Array([1,2,3,4]),asset={key:'files/hash/file.zip',name:'file.zip',sha256:'a'.repeat(64),size:4,mediaType:'application/zip'};
 const bucket={head:async()=>({size:4,checksums:{sha256:Uint8Array.from({length:32},()=>170).buffer}}),get:async(_key,o)=>({size:4,body:o?.range?bytes.slice(o.range.offset,o.range.offset+o.range.length):bytes})};
 const range=await serveFile(new Request('https://api.turboism.dev/'+asset.key,{headers:{Range:'bytes=1-2'}}),bucket,asset);assert.equal(range.status,206);assert.equal(range.headers.get('Content-Range'),'bytes 1-2/4');assert.deepEqual([...new Uint8Array(await range.arrayBuffer())],[2,3]);
 assert.equal((await serveFile(new Request('https://api.turboism.dev/'+asset.key),null,asset)).status,503);
 const full=await serveFile(new Request('https://api.turboism.dev/'+asset.key,{headers:{Range:'bytes=1-2','If-Range':'"different"'}}),bucket,asset);assert.equal(full.status,200);assert.equal((await full.arrayBuffer()).byteLength,4);
});
test('file serving rejects an object whose checksum is absent even when its size matches',async()=>{
 const asset={key:'files/hash/a.zip',name:'a.zip',sha256:'a'.repeat(64),size:4,mediaType:'application/zip'};
 const bucket={head:async()=>({size:4}),get:async()=>{throw new Error('Unverified bytes must not be read');}};
 const response=await serveFile(new Request('https://api.turboism.dev/'+asset.key),bucket,asset);
 assert.equal(response.status,503);
});
