import test from 'node:test';
import assert from 'node:assert/strict';
import {synchronize} from '../github.mjs';
import {fixture} from './fixture.mjs';

const sourceSha='b'.repeat(40),promotedCommit='c'.repeat(40),tagObject='d'.repeat(40);
const identity={schemaVersion:1,version:'1.2.3',buildNumber:42,channel:'stable',
 sourceRevision:sourceSha,runId:'123',runAttempt:1};
const body='Release notes\n<!-- turboism-build-v1 '+JSON.stringify(identity)+' -->';
const validTagMessage='Turboism 1.2.3 ('+sourceSha+')\nCandidate payload SHA-256: '+'e'.repeat(64)+'\n';
const reply=value=>new Response(JSON.stringify(value),{headers:{'Content-Type':'application/json'}});
function upstream({message=validTagMessage,ledger=identity,tagTarget=promotedCommit,direct=false}={}){
 return async url=>{
  const p=new URL(url).pathname;
  if(p.endsWith('/releases'))return reply([{...fixture(),body}]);
  if(p.includes('/git/ref/tags/'))return reply({object:{type:direct?'commit':'tag',sha:direct?tagTarget:tagObject}});
  if(p.includes('/git/tags/'))return reply({tag:'v1.2.3',message,object:{type:'commit',sha:tagTarget}});
  if(p.includes('/contents/entries/123-1.json'))return reply({content:Buffer.from(JSON.stringify(ledger)).toString('base64')});
  throw new Error('Unexpected upstream '+url);
 };
}

test('a provenance-anchored promoted tag is accepted without claiming the tag commit built the asset',async()=>{
 const snapshot=await synchronize(null,{},upstream());
 assert.deepEqual(snapshot.errors,{});
 assert.equal(snapshot.releases.length,1);
 assert.equal(snapshot.releases[0].sourceRevision,sourceSha);
 assert.equal(snapshot.releases[0].provenance.runId,'123');
 assert.equal(snapshot.releases[0].buildNumber,42);
});

test('cannot forge the tag message or the identity ledger to bypass receipt validation',async()=>{
 const cases=[
  {message:'Turboism 1.2.3 ('+'f'.repeat(40)+')\nCandidate payload SHA-256: '+'e'.repeat(64)+'\n'},
  {message:'Turboism 1.2.3 ('+sourceSha+')\n'},
  {message:'Turboism 2.0.0 ('+sourceSha+')\nCandidate payload SHA-256: '+'e'.repeat(64)+'\n'},
  {ledger:{...identity,sourceRevision:'f'.repeat(40)}},
  {direct:true},
 ];
 for(const opts of cases){
  const snapshot=await synchronize(null,{},upstream(opts));
  assert.equal(snapshot.releases.length,0,'Tampered provenance must not be advertised');
  assert.ok(snapshot.errors.stable,'Tampering must be recorded');
 }
});

test('normal directly anchored releases remain supported unchanged',async()=>{
 const snapshot=await synchronize(null,{},upstream({direct:true,tagTarget:sourceSha}));
 assert.deepEqual(snapshot.errors,{});
 assert.equal(snapshot.releases[0].sourceRevision,sourceSha);
});
