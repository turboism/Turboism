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

ROOT = Path(__file__).resolve().parents[2]
if str(Path(__file__).resolve().parent) not in sys.path:
    sys.path.insert(0,str(Path(__file__).resolve().parent))
from turboism_release.channels import CHANNELS, resolve_version
from turboism_release.versions import framework_version
from turboism_release.promotion import GitHub, validate_run
from turboism_release.contracts import ReleaseError


def candidate_command(channel, version, source):
    command = ['gh','workflow','run','release.yml','--repo','turboism/Turboism','--ref','main',
               '-f','channel='+channel,'-f','expected_source_sha='+source]
    if version:
        command += ['-f','version='+version]
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
            p.add_argument('--submit',action='store_true',help='Actually dispatch CI (default only prints the verified command)')
    p=commands.add_parser('info');p.add_argument('--jar',type=Path,required=True)
    p=commands.add_parser('promote');p.add_argument('--run-id',required=True);p.add_argument('--attempt',type=int,required=True)
    p.add_argument('--source',required=True);p.add_argument('--submit',action='store_true')
    args=parser.parse_args(argv)
    if args.command == 'info':
        print(json.dumps(read_jar_info(args.jar),ensure_ascii=False,indent=2));return 0
    if args.command == 'inspect':
        gradle = 'gradlew.bat' if os.name == 'nt' else './gradlew'
        command=[str(ROOT/gradle),'--quiet','printBuildInfo',f'-PturboismChannel={args.channel}']
        if args.version:command.append('-PturboismVersion='+args.version)
        return subprocess.run(command,cwd=ROOT).returncode
    if args.command == 'candidate':
        resolve_version(framework_version(ROOT),args.channel,args.version)
        source=clean_source()
        command=candidate_command(args.channel,args.version,source)
    else:
        validate_run(GitHub().api(f'actions/runs/{args.run_id}'),args.run_id,args.source,args.attempt)
        command=['gh','workflow','run','release-github-only.yml','--repo','turboism/Turboism','--ref','main',
                 '-f','candidate_run_id='+args.run_id,'-f',f'candidate_run_attempt={args.attempt}',
                 '-f','source_sha='+args.source,'-f','confirmation=publish-github-only:'+args.source]
    print(shlex.join(command))
    if not args.submit:
        print('Dry run: no build, build number, tag or release was created. Add --submit to dispatch.')
        return 0
    return subprocess.run(command,cwd=ROOT).returncode


if __name__ == '__main__':
    try:sys.exit(main())
    except (ValueError,OSError,ReleaseError,subprocess.SubprocessError,zipfile.BadZipFile) as error:
        print(f'Product operation refused: {error}',file=sys.stderr);sys.exit(1)
