import test from 'node:test';
import assert from 'node:assert/strict';
import { classify, compareVersions, parseRelease, chooseLatest, buildReceipt, documentFor } from '../protocol.mjs';
import { fixture } from './fixture.mjs';
test('independent stable, beta and nightly channels; draft/plugin tags do not leak',()=>{
 assert.equal(classify(fixture()),'stable'); assert.equal(classify(fixture('1.3.0-beta.2',true)),'beta');
 assert.equal(classify(fixture('1.3.0-0.nightly.42',true)),'nightly');
 assert.equal(classify({...fixture(),draft:true}),null);assert.equal(classify({...fixture(),tag_name:'plugin/test/v1'}),null);
 assert.throws(()=>classify(fixture('1.3.0-beta.2',false)),/channel|prerelease/i);
});
test('version ordering does not confuse beta.10, late old-branch builds or build metadata',()=>{
 assert.ok(compareVersions('1.2.3-beta.10','1.2.3-beta.2')>0);assert.ok(compareVersions('1.2.3','1.2.3-beta.10')>0);
 assert.ok(compareVersions('1.2.3-0.nightly.42','1.2.3-beta.1')<0);assert.ok(compareVersions('1.2.3','1.3.0-0.nightly.1')<0);
 assert.equal(compareVersions('1.2.3+build.42','1.2.3+build.43'),0);
 assert.throws(()=>compareVersions('1.2.3-beta.01','1.2.3'));
});
test('raw release produces four verified binary choices and preserves no invented historical build',()=>{
 const r=parseRelease(fixture(),'b'.repeat(40));assert.equal(r.assets.length,4);assert.equal(r.buildNumber,null);assert.equal(r.channel,'stable');
 assert.equal(r.assets[0].sha256,'a'.repeat(64));assert.equal(r.assets[0].sources[0].id,'github');assert.equal(r.assets[0].architecture,null);
 assert.equal(r.notes,'Release notes');assert.equal(r.sourceRevision,'b'.repeat(40));
});
test('partial assets, unsafe URLs, invalid digests and tag mismatch fail closed',()=>{
 for(const mutate of [r=>r.assets.pop(),r=>r.assets[0].digest=null,r=>r.assets[0].browser_download_url='https://evil.invalid/a',r=>r.assets.push(r.assets[0])]){const r=fixture();mutate(r);assert.throws(()=>parseRelease(r,'b'.repeat(40)));}
});
test('developer SDK assets are validated but never mirrored, and the pair is all-or-nothing',()=>{
 const withSdk=parseRelease(fixture(),'b'.repeat(40));
 assert.equal(withSdk.assets.length,4);
 assert.ok(withSdk.assets.every(a=>!a.name.startsWith('turboism-sdk-')));
 const legacy=parseRelease(fixture('1.2.3',false,false),'b'.repeat(40));
 assert.equal(legacy.assets.length,4);
 const partial=fixture();partial.assets=partial.assets.filter(a=>a.name!=='turboism-sdk-1.2.3.jar.sha256');
 assert.throws(()=>parseRelease(partial,'b'.repeat(40)),/developer/i);
 const unknown=fixture();unknown.assets.push({id:99,name:'turboism-sdk-1.2.3-extra.jar',state:'uploaded',size:1,digest:'sha256:'+'a'.repeat(64),content_type:'application/octet-stream',browser_download_url:'https://github.com/turboism/Turboism/releases/download/v1.2.3/turboism-sdk-1.2.3-extra.jar'});
 assert.throws(()=>parseRelease(unknown,'b'.repeat(40)));
 const badDigest=fixture();badDigest.assets.find(a=>a.name==='turboism-sdk-1.2.3.jar').digest='not-a-digest';
 assert.throws(()=>parseRelease(badDigest,'b'.repeat(40)),/digest/i);
});
test('receipt is source-bound; historical metadata is null, not a sync-assigned number',()=>{
 assert.equal(buildReceipt('notes'),null);
 const identity={schemaVersion:1,version:'1.2.3',buildNumber:42,channel:'stable',sourceRevision:'b'.repeat(40),runId:'123',runAttempt:1};
 const body='notes\n<!-- turboism-build-v1 '+JSON.stringify(identity)+' -->';assert.deepEqual(buildReceipt(body),identity);
 const r=parseRelease({...fixture(),body},'b'.repeat(40),identity);assert.equal(r.buildNumber,42);assert.equal(r.notes,'notes');
 assert.throws(()=>parseRelease({...fixture(),body},'c'.repeat(40),identity));
});
test('latest selection stays within channels and does not select by build number',()=>{
 const a=parseRelease(fixture('1.3.0'),'b'.repeat(40));a.buildNumber=40;
 const b=parseRelease({...fixture('1.2.9'),id:124},'c'.repeat(40));b.buildNumber=42;
 assert.equal(chooseLatest([a,b],'stable').version,'1.3.0');assert.equal(chooseLatest([a,b],'beta'),null);
});
test('a previously verified release snapshot remains available during a transient sync outage',()=>{
 const release=parseRelease(fixture(),'b'.repeat(40));
 const snapshot={schemaVersion:1,syncedAt:new Date(Date.now()-60*60*1000).toISOString(),releases:[release],known:{},errors:{}};
 const document=documentFor(snapshot,'stable');
 assert.equal(document.status,'ready');
 assert.equal(document.release.version,'1.2.3');
 assert.equal(document.release.compatibility,null);
});
test('compatibility marker is parsed and validated strictly, falling back to overrides or null',()=>{
 const source='b'.repeat(40);
 const validMarker={
  schemaVersion:1,
  cubismEditor:{versions:['5.0.00','5.0.01','5.2.03'],ranges:[{from:'5.0.00',to:'5.0.01'},{from:'5.2.03',to:'5.2.03'}]},
  platforms:['windows-x64'],
  matrixUrl:'https://turboism.dev/docs/use/compatibility'
 };
 const withMarker=parseRelease({...fixture(),body:'Release notes\n<!-- turboism-compatibility-v1 '+JSON.stringify(validMarker)+' -->'},source);
 assert.deepEqual(withMarker.compatibility,{
  cubismEditor:validMarker.cubismEditor,
  platforms:['windows-x64'],
  matrixUrl:'https://turboism.dev/docs/use/compatibility'
 });
 assert.equal(withMarker.notes,'Release notes');

 const v044=parseRelease(fixture('0.44.0'),source);
 assert.deepEqual(v044.compatibility,{
  cubismEditor:{versions:['5.2.03','5.3.02','5.3.03'],ranges:[{from:'5.2.03',to:'5.2.03'},{from:'5.3.02',to:'5.3.03'}]},
  platforms:['windows-x64'],
  matrixUrl:'https://turboism.dev/docs/use/compatibility'
 });
 const nightly=parseRelease(fixture('0.44.1-0.nightly.11',true),source);
 assert.deepEqual(nightly.compatibility,v044.compatibility);
 const wildcardNightly=parseRelease(fixture('0.44.1-0.nightly.9',true),source);
 assert.deepEqual(wildcardNightly.compatibility,v044.compatibility);

 const beta=parseRelease(fixture('0.67.0-beta.1',true),source);
 assert.equal(beta.compatibility?.cubismEditor.versions.length,20);
 assert.deepEqual(beta.compatibility?.cubismEditor.ranges,[{from:'5.0.00',to:'5.3.04'}]);

 const overriddenByMarker=parseRelease({...fixture('0.44.0'),body:'Notes\n<!-- turboism-compatibility-v1 '+JSON.stringify(validMarker)+' -->'},source);
 assert.deepEqual(overriddenByMarker.compatibility.cubismEditor,validMarker.cubismEditor);

 assert.equal(parseRelease(fixture('1.2.3'),source).compatibility,null);

 const invalidPayloads=[
  {...validMarker,schemaVersion:2},
  {...validMarker,extra:true},
  {...validMarker,cubismEditor:{versions:[],ranges:[]}},
  {...validMarker,cubismEditor:{versions:['4.2.00'],ranges:[{from:'4.2.00',to:'4.2.00'}]}},
  {...validMarker,cubismEditor:{versions:['5.3.03','5.3.02'],ranges:[{from:'5.3.02',to:'5.3.03'}]}},
  {...validMarker,cubismEditor:{versions:['5.3.02','5.3.02'],ranges:[{from:'5.3.02',to:'5.3.02'}]}},
  {...validMarker,cubismEditor:{versions:['5.2.03','5.3.02'],ranges:[{from:'5.2.03',to:'5.2.03'}]}},
  {...validMarker,cubismEditor:{versions:['5.2.03','5.3.02'],ranges:[{from:'5.3.02',to:'5.2.03'}]}},
  {...validMarker,platforms:[]},
  {...validMarker,platforms:['Windows x64']},
  {...validMarker,matrixUrl:'http://turboism.dev/docs/use/compatibility'},
  {...validMarker,matrixUrl:'https://evil.invalid/docs/use/compatibility'},
  {...validMarker,matrixUrl:'https://turboism.dev/other'},
 ];
 for(const bad of invalidPayloads){
  const r=parseRelease({...fixture('0.44.0'),body:'Notes\n<!-- turboism-compatibility-v1 '+JSON.stringify(bad)+' -->'},source);
  assert.equal(r.compatibility,null);
 }
 const malformed=parseRelease({...fixture('0.44.0'),body:'Notes\n<!-- turboism-compatibility-v1 {not-json -->'},source);
 assert.equal(malformed.compatibility,null);
 const oversized=parseRelease({...fixture('0.44.0'),body:'Notes\n<!-- turboism-compatibility-v1 '+' '.repeat(5000)+JSON.stringify(validMarker)+' -->'},source);
 assert.equal(oversized.compatibility,null);
 const duplicate=parseRelease({...fixture('0.44.0'),body:'Notes\n<!-- turboism-compatibility-v1 '+JSON.stringify(validMarker)+' -->\n<!-- turboism-compatibility-v1 '+JSON.stringify(validMarker)+' -->'},source);
 assert.equal(duplicate.compatibility,null);
});
