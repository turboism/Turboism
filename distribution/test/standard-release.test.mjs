import test from 'node:test';
import assert from 'node:assert/strict';
import {parseRelease} from '../protocol.mjs';
import {fixture} from './fixture.mjs';

const standard=()=>fixture('1.2.3',false,true,'standard');
test('standard installer and portable package have verified download identities',()=>{
 const r=parseRelease(standard(),'b'.repeat(40));
 assert.equal(r.assets.length,2);
 assert.deepEqual(r.assets.map(a=>a.kind),['windows-installer','full']);
 assert.deepEqual(r.assets.map(a=>a.variant),['full','full']);
 assert.deepEqual(r.assets.map(a=>a.platform),['windows',null]);
 for(const a of r.assets){assert.ok(a.checksum);assert.ok(a.sources[0].url.endsWith(a.name));}
});
test('standard layout rejects partial packages and mixed old layouts',()=>{
 for(const mutate of [
  r=>{r.assets=r.assets.filter(a=>a.name!=='turboism-1.2.3.zip');},
  r=>{r.assets=r.assets.filter(a=>a.name!=='turboism-1.2.3.zip.sha256');},
  r=>{r.assets.find(a=>a.name==='turboism-1.2.3.zip').digest=null;},
  r=>{r.assets.push(...fixture().assets.filter(a=>a.name==='turboism-1.2.3-thin.zip'));},
 ]){const raw=standard();mutate(raw);assert.throws(()=>parseRelease(raw,'b'.repeat(40)));}
});
test('previous full/thin and Java installer releases remain readable',()=>{
 assert.equal(parseRelease(fixture(),'b'.repeat(40)).assets.length,4);
 assert.equal(parseRelease(fixture('1.2.3',false,true,true),'b'.repeat(40)).assets.length,4);
});
