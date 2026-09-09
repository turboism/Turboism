#!/usr/bin/env python3
"""Allocate a product build on the main workflow, or write the matching candidate receipt."""
import argparse, json, os
from pathlib import Path
from turboism_release.build_identity import identity, allocate, decode_file
from turboism_release.promotion import GitHub
from turboism_release.versions import framework_version

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['allocate','receipt'])
    parser.add_argument('--source',required=True)
    parser.add_argument('--version')
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    if os.environ.get('GITHUB_REPOSITORY')!='turboism/Turboism' or os.environ.get('GITHUB_REF')!='refs/heads/main' or args.source!=os.environ.get('GITHUB_SHA'):
        raise ValueError('Build allocation must identify this main workflow checkout')
    wanted=identity(args.version or framework_version(Path.cwd()),args.source,os.environ['GITHUB_RUN_ID'],int(os.environ['GITHUB_RUN_ATTEMPT']))
    github=GitHub()
    if args.command=='allocate':
        record=allocate(github,wanted)
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'],'a') as f:f.write(f"build_number={record['buildNumber']}\n")
    else:
        record=decode_file(github.api(f"contents/entries/{wanted['runId']}-{wanted['runAttempt']}.json?ref=build-ledger"))
        if any(record.get(k)!=v for k,v in wanted.items()) or str(record.get('buildNumber'))!=os.environ.get('TURBOISM_BUILD_NUMBER'):
            raise ValueError('Candidate receipt does not match allocated build')
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(record,sort_keys=True,separators=(',',':'))+'\n')
    print(f"Build {record['buildNumber']} ({record['version']}, {record['channel']})")
if __name__=='__main__':main()
