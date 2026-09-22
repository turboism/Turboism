#!/usr/bin/env python3
"""Reproduce the reviewed legacy display supplement; never mutate a GitHub Release."""
import importlib.util
import json
import subprocess
from pathlib import Path
from turboism_release import prerelease, release_notes
from turboism_release.build_identity import identity

ROOT=Path(__file__).resolve().parents[2]
SOURCE='e87c9ff018d9355aa4c9b818f8616a7f1c273f49'
BASE='e68698ecb9e93e9210b9ff63983456eff67189a9'

def main():
    spec=importlib.util.spec_from_file_location('historical_extract',ROOT/'scripts/release/extract-release-notes.py')
    extractor=importlib.util.module_from_spec(spec);spec.loader.exec_module(extractor)
    changelog=subprocess.check_output(['git','-C',str(ROOT),'show',SOURCE+':CHANGELOG.md'],text=True,encoding='utf-8')
    english=extractor.extract(changelog,'0.43.10').strip()
    locales=release_notes.reviewed_locales(ROOT,'0.43.10',english)
    assert set(locales)=={'en','zh','ja'},'Reviewed translations must exist'
    stable={'releaseId':386022377,'sourceRevision':SOURCE,'originalNotes':english,'notesByLanguage':locales}
    receipt={**identity('0.43.10-0.nightly.3',SOURCE,'34430956331',1),'buildNumber':3}
    context=release_notes.history_context(ROOT,SOURCE,BASE,'v0.43.9')
    translated=release_notes.render_nightly(receipt,context)
    for lang in translated:
        parts=translated[lang].split('\n\n',2)
        translated[lang]=parts[0]+'\n\n'+parts[1]+'\n\n'+locales[lang]+'\n\n'+parts[2]
    nightly={'releaseId':385978975,'sourceRevision':SOURCE,'baseRevision':BASE,'baseTag':'v0.43.9',
             'originalNotes':prerelease.notes_for(ROOT,receipt).split('<!-- turboism-build-v1 ')[0].strip(),
             'notesByLanguage':translated}
    output=ROOT/'distribution/release-notes-overrides.json'
    output.write_text(json.dumps({'0.43.10':stable,'0.43.10-0.nightly.3':nightly},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Reproduced source-bound display supplement for Stable 0.43.10 and Nightly Build 3')

if __name__=='__main__':main()
