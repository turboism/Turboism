#!/usr/bin/env python3
"""Isolated official serializer projection proof; never touches an installed Editor."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('lib', 'asm', 'model', 'out'):
        parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--project-grid', action='store_true')
    parser.add_argument('--reopen-heap', choices=['3g', '4g', '6g'])
    parser.add_argument('--heap', default='6g', choices=['3g', '4g', '6g'])
    args = parser.parse_args()
    host = args.lib / 'Live2D_Cubism.jar'
    if sha(host) != '988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21' or sha(args.model) != '70983ff77d29ebb644f3f2bf5ac5ed5b663199d2433919dcfb795dbecdeeaba5':
        parser.error('unreviewed official library or Cat model')
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    (out / 'classes').mkdir()
    here = Path(__file__).resolve().parent
    sources = sorted(here.glob('*.java')) + sorted((here.parents[2] / 'runtime/src/main/java/dev/turboism/adapter/cubism/interpolation').glob('*.java'))
    # Root path is three parents above performance/.
    root = here.parents[2]
    for source in sources:
        target = out / 'source' / source.relative_to(root)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    classpath = os.pathsep.join(map(str, [out / 'classes', *sorted(args.lib.resolve().rglob('*.jar')), args.asm.resolve()]))
    commands = []

    def run(command, name):
        commands.append(dict(stage=name, argv=command))
        (out / (name + '-command.json')).write_text(json.dumps(command, indent=2) + '\n')
        with (out / (name + '.log')).open('xb') as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
        (out / (name + '-exit.json')).write_text(json.dumps(dict(exitCode=result.returncode)) + '\n')
        print(name, result.returncode, flush=True)
        if result.returncode:
            raise RuntimeError((out / (name + '.log')).read_text(errors='replace')[-4000:])

    try:
        run(['javac', '--release', '17', '-cp', classpath, '-d', str(out / 'classes'), *map(str, sources)], 'compile')
        agent = out / 'projection-agent.jar'
        with zipfile.ZipFile(agent, 'w') as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nPremain-Class: dev.turboism.diagnostic.CatRuntimeInterpolationAgent\n\n')
            for path in sorted((out / 'classes').rglob('*.class')):
                archive.write(path, str(path.relative_to(out / 'classes')))
        projected = ['java', '-javaagent:' + str(agent), '-Xmx' + args.heap, '-Djava.awt.headless=true',
                     '-Dturboism.meshInterpolation.serializationStreaming=true', '-Dturboism.meshInterpolation.projectionDiagnostic=true', '-Dturboism.meshInterpolation.serializationProjection=true', '-Dturboism.meshInterpolation.coefficients=true', '-cp', classpath]
        if args.project_grid:
            projected.insert(2, '-Dturboism.meshInterpolation.serializationGridProjection=true')
        run([*projected, 'dev.turboism.diagnostic.CacheProjectionProof', str(out)], 'small-projection')
        model = out / 'cat-projected.cmo3'
        run([*projected, 'dev.turboism.diagnostic.CatProjectionRoundtripProof', 'save', str(args.model.resolve()), str(model)], 'full-save')
        run(['java', '-Xmx' + (args.reopen_heap or args.heap), '-Djava.awt.headless=true', '-cp', classpath, 'dev.turboism.diagnostic.CatProjectionRoundtripProof', 'reopen', str(model)], 'unmodified-official-reopen')
    finally:
        (out / 'manifest.json').write_text(json.dumps(dict(scope='Official isolated serializer projection and source/cache reconstruction; excludes Editor auto-backup trigger, UI and rendered pixels',
            modelUnchanged=sha(args.model) == '70983ff77d29ebb644f3f2bf5ac5ed5b663199d2433919dcfb795dbecdeeaba5', commands=commands,
            inputs={str(p): sha(p) for p in [host, args.model, args.asm, Path(__file__), *sources]}), indent=2) + '\n')


if __name__ == '__main__':
    main()
