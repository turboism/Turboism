#!/usr/bin/env python3
"""Local product entry: preview identity, inspect a package, or dispatch verified CI candidates."""
from __future__ import annotations
import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import zipfile
from pathlib import Path

_SCRIPTS_DIR = str(Path(__file__).resolve().parents[1])
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)
from turboism_dev.paths import repo_root

ROOT = repo_root()
from turboism_release.beta_promotion import (
    STABLE_CHANNEL,
    find_latest_verified_beta_run,
    parse_beta_source_args,
    verify_source_beta_run,
)
from turboism_release.channels import CHANNELS, resolve_version
from turboism_release.versions import framework_version
from turboism_release.promotion import GitHub, validate_run
from turboism_release.contracts import ReleaseError

FRAMEWORK_PUBLISH_MODES = ('admit', 'initialize', 'none')
DEFAULT_FRAMEWORK_PUBLISH = 'admit'


def promote_command(run_id, attempt, source, *, framework_publish=DEFAULT_FRAMEWORK_PUBLISH):
    if framework_publish not in FRAMEWORK_PUBLISH_MODES:
        raise ValueError(f'Unsupported framework publish mode: {framework_publish}')
    return [
        'gh', 'workflow', 'run', 'release-github-only.yml', '--repo', 'turboism/Turboism', '--ref', 'main',
        '-f', 'candidate_run_id=' + run_id,
        '-f', f'candidate_run_attempt={attempt}',
        '-f', 'source_sha=' + source,
        '-f', 'confirmation=publish-github-only:' + source,
        '-f', 'framework_publish=' + framework_publish,
    ]


def candidate_command(channel, version, source, *, beta_run_id='', beta_attempt=''):
    command = ['gh','workflow','run','release.yml','--repo','turboism/Turboism','--ref','main',
               '-f','channel='+channel,'-f','expected_source_sha='+source]
    if version:
        command += ['-f','version='+version]
    parsed_beta = parse_beta_source_args(beta_run_id, beta_attempt)
    if parsed_beta is not None:
        if channel != STABLE_CHANNEL:
            raise ValueError('Fast Beta promotion applies only to the stable channel')
        command += [
            '-f', 'promote_from_beta_run_id=' + parsed_beta[0],
            '-f', f'promote_from_beta_attempt={parsed_beta[1]}',
        ]
    return command


def read_jar_info(path):
    with zipfile.ZipFile(path) as jar:
        name = 'META-INF/turboism/framework-version.properties'
        if name in jar.namelist():
            if jar.namelist().count(name) != 1 or jar.getinfo(name).file_size > 16384:
                raise ValueError('Duplicate or oversized version resource')
            fields = dict(line.split('=',1) for line in jar.read(name).decode('utf-8').splitlines() if '=' in line)
            number = fields.get('buildNumber','')
        else:
            name = 'META-INF/MANIFEST.MF'
            if jar.namelist().count(name) != 1 or jar.getinfo(name).file_size > 16384:
                raise ValueError('Missing or oversized installer identity')
            text = jar.read(name).decode('utf-8').replace('\r\n ', '').replace('\r\n','\n')
            manifest = dict(line.split(': ',1) for line in text.splitlines() if ': ' in line)
            fields = {'version':manifest.get('Turboism-Version',manifest.get('Implementation-Version','unknown')),
                      'channel':manifest.get('Turboism-Channel','unknown'),
                      'sourceRevision':manifest.get('Turboism-Source-Revision','unknown')}
            number = manifest.get('Turboism-Build-Number','')
        if number and (not re.fullmatch('[1-9][0-9]*',number) or int(number) >= 9007199254740991):
            raise ValueError('Invalid embedded build number')
        fields['buildNumber'] = int(number) if number else None
        return fields


def clean_source():
    status = subprocess.run(['git','status','--porcelain'],cwd=ROOT,capture_output=True,text=True,check=True)
    if status.stdout:
        raise ValueError('Commit/stash local changes first; CI will not upload or build your uncommitted files')
    source = subprocess.run(['git','rev-parse','HEAD'],cwd=ROOT,capture_output=True,text=True,check=True).stdout.strip()
    if not re.fullmatch('[a-f0-9]{40}',source):
        raise ValueError('Invalid source revision')
    remote = GitHub().api('git/ref/heads/main')['object']['sha']
    if source != remote:
        raise ValueError('Local HEAD differs from remote main; review and synchronize before dispatch')
    return source


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command',required=True)
    for name in ('inspect','candidate'):
        p = commands.add_parser(name)
        p.add_argument('--channel',choices=CHANNELS,default='stable')
        p.add_argument('--version',default='')
        if name == 'candidate':
            p.add_argument('--from-beta',action='store_true',help='Auto-resolve the latest verified same-SHA Beta run for fast Stable promotion')
            p.add_argument('--from-beta-run',default='',help='Verified same-SHA Beta candidate run id for fast Stable promotion')
            p.add_argument('--from-beta-attempt',default='',help='Verified same-SHA Beta candidate run attempt')
            p.add_argument('--submit',action='store_true',help='Actually dispatch CI (default only prints the verified command)')
    p=commands.add_parser('info');p.add_argument('--jar',type=Path,required=True)
    p=commands.add_parser('promote');p.add_argument('--run-id',required=True);p.add_argument('--attempt',type=int,required=True)
    p.add_argument('--source',required=True)
    p.add_argument('--framework-publish',choices=FRAMEWORK_PUBLISH_MODES,default=DEFAULT_FRAMEWORK_PUBLISH,
                   help='Framework self-update feed admission mode (default: admit)')
    p.add_argument('--submit',action='store_true')
    args=parser.parse_args(argv)
    if args.command == 'info':
        print(json.dumps(read_jar_info(args.jar),ensure_ascii=False,indent=2));return 0
    if args.command == 'inspect':
        gradle = 'gradlew.bat' if os.name == 'nt' else './gradlew'
        command=[str(ROOT/gradle),'--quiet','printBuildInfo',f'-PturboismChannel={args.channel}']
        if args.version:command.append('-PturboismVersion='+args.version)
        return subprocess.run(command,cwd=ROOT).returncode
    if args.command == 'candidate':
        declared = framework_version(ROOT)
        resolve_version(declared,args.channel,args.version)
        explicit_beta = parse_beta_source_args(args.from_beta_run, args.from_beta_attempt)
        if args.from_beta and explicit_beta is not None:
            raise ValueError('Use either --from-beta or --from-beta-run/--from-beta-attempt, not both')
        if (args.from_beta or explicit_beta is not None) and args.channel != STABLE_CHANNEL:
            raise ValueError('Fast Beta promotion applies only to the stable channel')
        source=clean_source()
        beta_run_id = ''
        beta_attempt = ''
        if args.from_beta:
            verified = find_latest_verified_beta_run(GitHub(), source, declared)
            beta_run_id, beta_attempt = verified['runId'], str(verified['runAttempt'])
            print(f"Verified same-SHA Beta source: {verified['version']} (Build {verified['buildNumber']}, run {beta_run_id} attempt {beta_attempt})")
        elif explicit_beta is not None:
            verified = verify_source_beta_run(GitHub(), source, declared, explicit_beta[0], explicit_beta[1])
            beta_run_id, beta_attempt = verified['runId'], str(verified['runAttempt'])
            print(f"Verified same-SHA Beta source: {verified['version']} (Build {verified['buildNumber']}, run {beta_run_id} attempt {beta_attempt})")
        command=candidate_command(args.channel,args.version,source,beta_run_id=beta_run_id,beta_attempt=beta_attempt)
    else:
        validate_run(GitHub().api(f'actions/runs/{args.run_id}'),args.run_id,args.source,args.attempt)
        command=promote_command(args.run_id,args.attempt,args.source,framework_publish=args.framework_publish)
    print(shlex.join(command))
    if not args.submit:
        print('Dry run: no build, build number, tag or release was created. Add --submit to dispatch.')
        return 0
    return subprocess.run(command,cwd=ROOT).returncode


if __name__ == '__main__':
    try:sys.exit(main())
    except (ValueError,OSError,ReleaseError,subprocess.SubprocessError,zipfile.BadZipFile) as error:
        print(f'Product operation refused: {error}',file=sys.stderr);sys.exit(1)
