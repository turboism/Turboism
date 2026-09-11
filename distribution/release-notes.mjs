import supplements from './release-notes-overrides.json' with {type:'json'};
const REPO='https://github.com/turboism/Turboism';
const strip=body=>String(body??'').replace(/<!-- turboism-(?:build|notes)-v1 [\s\S]*? -->/g,'').trim();
const validLocales=l=>l&&typeof l==='object'&&!Array.isArray(l)&&typeof l.en==='string'&&
 Object.keys(l).every(k=>['en','zh','ja'].includes(k)&&typeof l[k]==='string'&&l[k].trim().length>0&&l[k].length<=20000);
/** Read optional display metadata, never edit release identity, notes bindings or file manifests. */
export function releaseNotes(raw,sourceRevision){
 const original=strip(raw.body),version=raw.tag_name.slice(1);
 let locales={en:original.slice(0,20000)},origin='release',base=null;
 const matches=[...String(raw.body??'').matchAll(/<!-- turboism-notes-v1 ([\s\S]*?) -->/g)];
 if(matches.length===1&&matches[0][1].length<60000){
  try{const m=JSON.parse(matches[0][1]);
   if(m.schemaVersion===1&&m.version===version&&m.sourceRevision===sourceRevision&&validLocales(m.locales)){
    locales=m.locales;base=m.baseRevision??null;
   }
  }catch{/* Optional display metadata cannot disable verified downloads. */}
 }
 const saved=supplements[version];
 if(!matches.length&&saved?.releaseId===raw.id&&saved.sourceRevision===sourceRevision&&saved.originalNotes===original&&validLocales(saved.notesByLanguage)){
  locales=saved.notesByLanguage;origin='supplement';base=saved.baseRevision??null;
 }
 const compare=typeof base==='string'&&/^[a-f0-9]{40}$/.test(base)&&base!==sourceRevision?`${REPO}/compare/${base}...${sourceRevision}`:null;
 return {notes:locales.en.slice(0,24000),notesByLanguage:locales,notesOrigin:origin,notesCompareUrl:compare};
}
