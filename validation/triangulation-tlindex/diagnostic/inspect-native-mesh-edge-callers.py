"""Static direct-call inventory for native mesh autoConnect; no host execution."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import zipfile

spec = importlib.util.spec_from_file_location('bytecode', Path(__file__).with_name('inspect-command-return-bytecode.py'))
bytecode = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bytecode)
MESH = 'com/live2d/graphics3d/editableMesh/GEditableMesh2'


def inspect(jar):
    callers = []
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('duplicate archive entries')
        for name in names:
            if not name.endswith('.class'):
                continue
            raw = archive.read(name)
            if b'autoConnect' not in raw or MESH.encode() not in raw:
                continue
            owner, pool, methods = bytecode.methods(raw, name)
            for method in methods:
                instructions = bytecode.instructions(method['code'], pool)
                sites = [i for i in instructions if i.get('owner') == MESH
                         and i.get('name') in ('autoConnect', 'autoConnect$default')
                         and i.get('operation', '').startswith('invoke')]
                if sites:
                    method['codeSha256'] = hashlib.sha256(method.pop('code')).hexdigest()
                    method.update(owner=owner, classSha256=hashlib.sha256(raw).hexdigest(),
                                  sites=sites, instructions=instructions)
                    callers.append(method)
    return {'status': 'STATIC_DIRECT_CALL_INVENTORY', 'callers': callers,
            'nativeExecuted': False, 'ownershipProven': False,
            'limitations': ['Direct invocations only; reflection, method handles and external plugins are not enumerated.',
                            'A caller list does not establish exclusive mesh/list mutation ownership.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = inspect(args.jar)
    with args.output.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    for caller in result['callers']:
        print(caller['owner'], caller['name'], caller['descriptor'], [s['offset'] for s in caller['sites']])


if __name__ == '__main__':
    main()
