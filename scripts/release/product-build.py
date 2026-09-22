#!/usr/bin/env python3
"""Trusted CI entry for three-channel preflight, prerelease evidence and nightly promotion."""
from __future__ import annotations
import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from turboism_release.channels import CHANNELS, resolve_version
from turboism_release.versions import framework_version
from turboism_release.promotion import GitHub
from turboism_release.contracts import ReleaseError
from turboism_release import nightly, prerelease


def check_source(source):
    if (os.environ.get('GITHUB_ACTIONS') != 'true'
            or os.environ.get('GITHUB_REPOSITORY') != 'turboism/Turboism'
            or os.environ.get('GITHUB_REF') != 'refs/heads/main'
            or os.environ.get('GITHUB_SHA') != source
            or not re.fullmatch('[a-f0-9]{40}',source)):
        raise ValueError('Use the main product workflow; do not forge CI variables in a local shell')
    actual = subprocess.run(['git','rev-parse','HEAD'],capture_output=True,text=True,check=True).stdout.strip()
    if actual != source:
        raise ValueError('Checked-out source differs from the requested commit')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['preflight','prepare','publish-nightly'])
    parser.add_argument('--source',required=True)
    parser.add_argument('--channel',choices=CHANNELS,default='stable')
    parser.add_argument('--version',default='')
    parser.add_argument('--attempt',type=int)
    parser.add_argument('--bundle',type=Path,default=Path('build/release-orchestrator'))
    args = parser.parse_args(argv)
    check_source(args.source)
    github = GitHub()
    if args.command == 'preflight':
        declared = framework_version(Path.cwd())
        target = resolve_version(declared,args.channel,args.version)
        if args.channel == 'nightly':
            result = nightly.plan(github,args.source,target)
            target = result['base_version']
        else:
            result = {'should_build':True,'reason':'requested_candidate'}
            if github.api(f'git/ref/tags/v{target}',optional=True) is not None:
                raise ValueError('This version already has a public tag; resume its existing candidate promotion instead of rebuilding')
            if args.channel == 'beta':
                extractor = prerelease._load_script('preflight_beta_notes',prerelease.SCRIPTS/'extract-release-notes.py')
                extractor.extract(Path('CHANGELOG.md').read_text(encoding='utf-8'),declared)
        result.update(channel=args.channel,version=target,source_sha=args.source)
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'],'a') as f:
                for key in ('should_build','channel','version'):
                    value = str(result[key]).lower() if type(result[key]) is bool else result[key]
                    f.write(f'{key}={value}\n')
        print(json.dumps(result,sort_keys=True))
        return 0
    run_id = os.environ['GITHUB_RUN_ID']
    attempt = args.attempt or int(os.environ['GITHUB_RUN_ATTEMPT'])
    if args.command == 'prepare':
        value = prerelease.prepare(github,Path.cwd(),args.bundle,args.source,run_id,attempt,args.version)
        if value['receipt']['channel'] != args.channel:
            raise ValueError('Prepared candidate channel differs from the selected target')
        print(json.dumps({'candidate':value['type'],'receipt':value['receipt']},sort_keys=True))
        return 0
    # The publishing job may resume a previous successful build attempt without rebuilding.
    if attempt < 1 or os.environ.get('GITHUB_EVENT_NAME') not in ('schedule','workflow_dispatch'):
        raise ValueError('Invalid nightly publication identity')
    run = github.api(f'actions/runs/{run_id}/attempts/{attempt}')
    for key in ('repository','head_repository'):
        if run.get(key,{}).get('full_name') != 'turboism/Turboism':
            raise ValueError('Candidate originated from another repository')
    if (str(run.get('id')) != run_id or run.get('head_sha') != args.source
            or run.get('head_branch') != 'main' or run.get('path') != '.github/workflows/release.yml'
            or run.get('name') != 'Product release candidate' or run.get('run_attempt') != attempt
            or run.get('event') not in ('schedule','workflow_dispatch')):
        raise ValueError('Untrusted nightly workflow/source/attempt')
    jobs = github.api(f'actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100')
    if jobs.get('total_count',0) > len(jobs.get('jobs',[])):
        raise ValueError('Candidate job enumeration is incomplete')
    built = [job for job in jobs.get('jobs',[]) if job.get('name') == 'build']
    if len(built) != 1 or built[0].get('conclusion') != 'success':
        raise ValueError('Nightly requires a successful exact build job')
    manifest, receipt, notes = prerelease.verify_candidate(github,Path.cwd(),args.bundle,args.source,run_id,attempt,'nightly')
    plan = nightly.plan(github,args.source,framework_version(Path.cwd()))
    if not plan['should_build']:
        print(json.dumps({'published':False,'reason':plan['reason']}))
        return 0
    tag = prerelease.publish(github,args.bundle/'dist',receipt,manifest,notes)
    print(json.dumps({'published':True,'tag':tag,'buildNumber':receipt['buildNumber']}))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError,OSError,KeyError,ReleaseError,subprocess.SubprocessError) as error:
        print(f'Product build refused: {error}',file=sys.stderr)
        sys.exit(1)
