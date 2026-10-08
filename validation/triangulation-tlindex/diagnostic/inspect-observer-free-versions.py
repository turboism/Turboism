"""Pin three official archives and inventory command/output paths without a JVM.

This is bounded static evidence, not native admission or a whole-call-chain proof.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path


PINS = {
    '5203': 'bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd',
    '5302': '988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21',
    '5303': 'bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166',
}
MESH = 'com/live2d/graphics3d/editableMesh/GEditableMesh2'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for version in PINS:
        parser.add_argument('--jar-' + version, type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('bytecode',
            Path(__file__).with_name('inspect-command-return-bytecode.py'))
    decoder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(decoder)
    out = args.output
    out.mkdir(parents=True, exist_ok=False)
    report = {'status': 'PASS_PINNED_STATIC_INVENTORY_ONLY', 'versions': {},
              'nativeExecuted': False, 'productionAcceptance': 'NOT_GRANTED',
              'limitations': ['No interprocedural side-effect or exceptional-path proof.',
                              'Publication calls/array stores are inventoried, not proven to execute.',
                              'No all-version cold-start or plugin admission established.']}
    for version, expected in PINS.items():
        jar = getattr(args, 'jar_' + version)
        actual = hashlib.sha256(jar.read_bytes()).hexdigest()
        if actual != expected:
            raise ValueError('unreviewed official archive: ' + version)
        detail = decoder.inspect(jar, {
            MESH + '.class': {'autoConnect', 'updateIndices', 'getCached_indices$core',
                             'getCached_positions$core', 'setCached_indices$core'},
            'com/live2d/graphics3d/editableMesh/b.class': {'a', 'b'},
        })
        detail['archiveIdentityRevalidated'] = True
        detail['archiveSha256'] = actual
        detail['limitations'][0] = 'Whole official archive digest revalidated against frozen version pin.'
        raw = out / (version + '-instructions.json')
        raw.write_text(json.dumps(detail, indent=2) + '\n')
        summaries = []
        for owner, data in detail['classes'].items():
            for method in data['methods']:
                ins = method['instructions']
                summaries.append({
                    'owner': owner, 'name': method['name'], 'descriptor': method['descriptor'],
                    'codeSha256': method['codeSha256'],
                    'normalReturnOffsets': [i['offset'] for i in ins if 172 <= i['opcode'] <= 177],
                    'exceptionTable': method['exceptions'],
                    'arrayStoreOffsets': [i['offset'] for i in ins if 79 <= i['opcode'] <= 86],
                    'nativeMeshCalls': [i for i in ins if i.get('owner', '').startswith(
                        'com/live2d/graphics3d/editableMesh/') and i.get('operation', '').startswith('invoke')],
                    'fieldWrites': [i for i in ins if i.get('operation') in ('putfield', 'putstatic')],
                })
        auto = [m for m in summaries if m['owner'] == MESH and m['name'] == 'autoConnect']
        generate = [m for m in summaries if m['owner'].endswith('/b') and m['name'] == 'a'
                    and ';Ljava/util/List;ZL' in m['descriptor']]
        loops = [m for m in summaries if m['owner'].endswith('/b') and m['name'] == 'b'
                 and m['descriptor'].endswith(')Ljava/util/List;')]
        if len(auto) != 1 or len(generate) != 1 or len(loops) != 1:
            raise ValueError('missing/ambiguous command, generator or loop reader: ' + version)
        report['versions'][version] = {
            'archiveSha256': actual, 'instructionEvidenceSha256': hashlib.sha256(raw.read_bytes()).hexdigest(),
            'autoConnectReturns': auto[0]['normalReturnOffsets'],
            'generatorReturns': generate[0]['normalReturnOffsets'],
            'loopReaderReturns': loops[0]['normalReturnOffsets'],
            'methods': summaries,
        }
    (out / 'review.json').write_text(json.dumps(report, indent=2) + '\n')
    print(report['status'])
    for version, data in report['versions'].items():
        print(version, 'autoConnect returns', data['autoConnectReturns'],
              'generator returns', data['generatorReturns'], 'loop reader returns', data['loopReaderReturns'])


if __name__ == '__main__':
    main()
