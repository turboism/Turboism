import test from 'node:test';
import assert from 'node:assert/strict';
import { classify, compareVersions, parseRelease, chooseLatest, buildReceipt } from '../protocol.mjs';
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
