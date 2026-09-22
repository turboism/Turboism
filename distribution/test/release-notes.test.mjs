import test from 'node:test';import assert from 'node:assert/strict';
import {parseRelease} from '../protocol.mjs';import {fixture} from './fixture.mjs';
const source='b'.repeat(40);
const marker=(data)=>'<!-- turboism-notes-v1 '+JSON.stringify(data)+' -->';
test('release locales are source/version bound and internal markers do not leak to readers',()=>{
 const r=fixture();r.body='Original notes\n'+marker({schemaVersion:1,version:'1.2.3',sourceRevision:source,locales:{en:'Changes',zh:'新增与修复',ja:'変更点',ko:'변경 사항'}});
 const parsed=parseRelease(r,source);assert.equal(parsed.notesByLanguage?.zh,'新增与修复');assert.equal(parsed.notesByLanguage?.ko,'변경 사항');assert.doesNotMatch(parsed.notes,/turboism-notes/);
 r.body='Original notes\n'+marker({schemaVersion:1,version:'9.9.9',sourceRevision:source,locales:{en:'wrong',zh:'wrong'}});
 assert.equal(parseRelease(r,source).notesByLanguage?.zh,undefined);assert.equal(parseRelease(r,source).notes,'Original notes');
});
test('an unreviewed release language is rejected instead of displayed',()=>{
 const r=fixture();r.body='Original notes\n'+marker({schemaVersion:1,version:'1.2.3',sourceRevision:source,locales:{en:'Changes',de:'Neuerungen'}});
 const parsed=parseRelease(r,source);assert.deepEqual(parsed.notesByLanguage,{en:'Original notes'});assert.equal(parsed.notesOrigin,'release');
});
test('legacy releases remain readable with a single original-language fallback',()=>{
 const parsed=parseRelease(fixture(),source);assert.deepEqual(parsed.notesByLanguage,{en:'Release notes'});
});
test('current Nightly supplement has real changes and three languages without changing immutable body',async()=>{
 const {default:overrides}=await import('../release-notes-overrides.json',{with:{type:'json'}});
 const saved=overrides['0.43.10-0.nightly.3'];assert.ok(saved);
 const r=fixture('0.43.10-0.nightly.3',true);r.id=saved.releaseId;r.body=saved.originalNotes;
 const before=JSON.stringify(r),parsed=parseRelease(r,saved.sourceRevision);
 assert.ok(parsed.notesByLanguage.zh.includes('新增'));assert.ok(parsed.notesByLanguage.ja.includes('追加'));
 assert.match(parsed.notesCompareUrl,/\/compare\/[a-f0-9]{40}\.\.\.[a-f0-9]{40}$/);
 assert.notEqual(parsed.notes,saved.originalNotes);assert.equal(JSON.stringify(r),before);
 r.body+='\nIndependent edit';assert.equal(parseRelease(r,saved.sourceRevision).notesOrigin,'release');
 assert.equal(parseRelease({...r,body:saved.originalNotes},source).notesOrigin,'release');
});
