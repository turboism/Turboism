"""Inspect the bounded native autoConnect edge loop; never execute host classes.

This is research evidence, not production admission or a transitive safety proof.
"""
import argparse
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('bytecode', HERE / 'inspect-command-return-bytecode.py')
bytecode = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bytecode)
MESH = 'com/live2d/graphics3d/editableMesh/GEditableMesh2'
EDGE = 'com/live2d/graphics3d/editableMesh/MEdge'


def summarize_loop(method):
    code = method['instructions']
    publications = [i for i in code if i.get('operation') == 'getfield'
                    and i.get('owner') == MESH and i.get('name') == 'cached_indices']
    if len(publications) != 1:
        raise ValueError('expected exactly one cached_indices read')
    start = publications[0]['offset']
    suffix = [i for i in code if i['offset'] >= start]
    calls = [i for i in suffix if i.get('operation', '').startswith('invoke')]
    additions = [i for i in calls if i.get('owner') == MESH
                 and i.get('name') == 'addEdgeIfNotExists$default']
    if len(additions) != 3:
        raise ValueError('expected three triangle edge insertion sites')
    allowed = {(MESH, 'addEdgeIfNotExists$default'),
               ('kotlin/jvm/internal/Intrinsics', 'checkNotNull'),
               ('kotlin/internal/ProgressionUtilKt', 'getProgressionLastElement')}
    if any((i.get('owner'), i.get('name')) not in allowed for i in calls):
        raise ValueError('unexpected direct call in final loop')
    # Reject edges entering the proposed region from an unexamined earlier path.
    crossing = [i for i in code if i['offset'] < start
                and i.get('branchTarget', -1) >= start]
    escapes = [i for i in suffix if 'branchTarget' in i and i['branchTarget'] < start]
    unsupported = [i for i in suffix if i['opcode'] in (170, 171, 168, 169, 196, 201)
                   or i.get('operation') in ('putfield', 'putstatic')]
    if crossing or escapes or unsupported or method['exceptions']:
        raise ValueError('unexpected entry branch or exception handler')
    returns = [i['offset'] for i in suffix if i['opcode'] == 177]
    if len(returns) != 1:
        raise ValueError('expected single normal exit')
    for addition in additions:
        pos = code.index(addition)
        preceding = code[pos - 4:pos]
        # NORMAL, false crossing flag, mask 8, null marker.
        if [(i.get('owner'), i.get('name')) for i in preceding[:1]] != [(EDGE + '$EdgeType', 'NORMAL')]:
            raise ValueError('unexpected edge type')
        if [i['bytes'] for i in preceding[1:]] != ['03', '1008', '01']:
            raise ValueError('unexpected default arguments')
    return {'cachedIndicesReadOffset': start,
            'edgeInsertionOffsets': [i['offset'] for i in additions],
            'normalReturnOffset': returns[0], 'directCalls': calls,
            'exceptionHandlers': method['exceptions'],
            'status': 'DIRECT_SUFFIX_SHAPE_PASS'}


def inspect(jar):
    selections = {
        MESH + '.class': {'autoConnect', 'addEdge', 'addEdgeIfNotExists',
                         'addEdgeIfNotExists$default', 'checkExitingTypedEdge',
                         'chechExistingEdge_exe', 'setEdgeUpdated', 'clearAutoTriangulation',
                         'removeEdge', 'removeAllEdges', 'getEdges', 'access$get_edges$p'},
        EDGE + '.class': {'<init>', 'getIndex1', 'getIndex2', 'getType'},
        EDGE + '$EdgeType.class': {'<clinit>', 'a'},
        'com/live2d/type/CArrayList.class': {'get', 'set', 'add', 'size', 'removeIf'},
    }
    result = bytecode.inspect(jar, selections)
    methods = result['classes'][MESH]['methods']
    loops = [m for m in methods if m['name'] == 'autoConnect']
    if len(loops) != 1:
        raise ValueError('ambiguous autoConnect definition')
    result['finalEdgeLoop'] = summarize_loop(loops[0])
    result['limitations'].extend([
        'Direct suffix checks do not prove called-method, loader, list identity, concurrency or exception equivalence.',
        'No helper, bytecode transformation, production integration or performance measurement is performed.',
    ])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = inspect(args.jar)
    with args.output.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    print(json.dumps(result['finalEdgeLoop']))


if __name__ == '__main__':
    main()
