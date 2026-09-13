import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {DownloadCounter,countsDocument} from '../download-counts.mjs';
import {parseRelease} from '../protocol.mjs';
import {fixture} from './fixture.mjs';

test('individual persisted binary counts reconcile to the release total without sidecars',async()=>{
 const db=new DatabaseSync(':memory:');
 try{
  const storage={sql:{exec(query,...args){const rows=db.prepare(query).all(...args);return{toArray:()=>rows};}},
   transactionSync(fn){db.exec('SAVEPOINT t');try{const v=fn();db.exec('RELEASE t');return v;}catch(e){db.exec('ROLLBACK TO t');db.exec('RELEASE t');throw e;}}};
  const time=Date.parse('2026-09-11T00:00:00Z'),counter=new DownloadCounter(storage,()=>time);counter.initialize();
  const raw=fixture();raw.assets.forEach((a,i)=>a.download_count=a.name.endsWith('.sha256')?999:i+1);
  const release=parseRelease(raw,'b'.repeat(40)),record={active:true,countsAsOf:new Date(time).toISOString(),release};
  const request=new Request('https://api.turboism.dev/'+release.assets[0].key,{headers:{'CF-Connecting-IP':'192.0.2.10','User-Agent':'Counter test'}});
  await counter.record(request,new Response('bytes'),release.assets[0]);
  const restored=new DownloadCounter(storage,()=>time);restored.initialize();
  const d=countsDocument('1.2.3',record,restored.read(release.assets));
  assert.equal(d.assets.length,4);assert.equal(d.assets[0].official,1);assert.equal(d.assets[1].official,0);
  assert.equal(d.assets.reduce((n,a)=>n+a.total,0),d.total);
  assert.equal(d.assets[0].key,release.assets[0].key);assert.equal(d.assets[0].sha256,release.assets[0].sha256);
  release.assets[1].downloadCount=null;
  const partial=countsDocument('1.2.3',record,restored.read(release.assets));
  assert.equal(partial.total,null);assert.equal(partial.assets[1].total,null);assert.equal(partial.assets[0].total,d.assets[0].total);
 }finally{db.close();}
});
